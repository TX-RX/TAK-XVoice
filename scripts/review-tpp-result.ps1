#!/usr/bin/env pwsh
# scripts/review-tpp-result.ps1
#
# Review a TAK Product Portal (TPP) build+scan result bundle. Reads the
# ZIP that comes back from tak.gov after a submission, extracts the
# signed APK / AAB, and summarizes Fortify + OWASP Dependency-Check
# findings so an operator can decide whether to promote the APKs to
# their TAK server without wading through hundreds of MB of scan output.
#
# Usage:
#   .\scripts\review-tpp-result.ps1 <bundle.zip> [<bundle2.zip> ...]
#   .\scripts\review-tpp-result.ps1 -Dir path\to\already-extracted
#
# What the script does per bundle:
#   1. Extract into diagnostics/tpp-review-<ts>/<bundleName>/
#   2. Verify each APK's signing cert + baseline (plugin-api meta-data)
#   3. Parse scan_results.fpr (a ZIP containing audit.fvdl XML) and
#      report findings grouped by Kingdom, Category, and severity
#   4. Skim dependency-check-report.html for any CVE identifiers
#   5. Print a single-page summary; extraction stays on disk under
#      diagnostics/ (gitignored) for follow-up drilldown.
#
# Rename to include baseline:
#   Pass -RenameApks. This copies each APK to
#   <sourceDir>/<sanityApkBaseName>-<versionName>-<manifestBaseline>-<flavor>-release.apk
#   so downstream TAK-server uploads can distinguish 5.6 vs 5.7 builds
#   even when TPP names both the same. The original files stay in place.

[CmdletBinding()]
param(
    # One or more TPP result bundle ZIPs (as downloaded from tak.gov).
    [Parameter(Position=0, ValueFromRemainingArguments)]
    [string[]] $Bundles,

    # Alternatively point at an already-extracted directory. Skips extraction.
    [string]   $Dir,

    # Rename each APK to include its actual manifest baseline in the
    # filename. Original filename stays; a renamed copy sits alongside.
    [switch]   $RenameApks
)

$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent (Split-Path -Parent $PSCommandPath)
Set-Location $repoRoot

. (Join-Path $repoRoot "scripts/lib/Read-Config.ps1")
$cfg = Read-ScriptConfig -RepoRoot $repoRoot

# Locate the Android SDK build-tools. Honor the standard env vars first
# (ANDROID_SDK_ROOT / ANDROID_HOME), then fall back to the usual install
# locations. Pick the highest build-tools version present.
$sdkRoots = @(
    $env:ANDROID_SDK_ROOT,
    $env:ANDROID_HOME,
    (Join-Path $env:LOCALAPPDATA "Android/Sdk"),
    (Join-Path $env:USERPROFILE  "Android/Sdk"),
    (Join-Path $env:HOME         "Android/Sdk"),
    (Join-Path $env:HOME         "Library/Android/sdk")
) | Where-Object { $_ } | Select-Object -Unique

$sdkBuildTools = $null
foreach ($root in $sdkRoots) {
    $bt = Join-Path $root "build-tools"
    if (Test-Path $bt) {
        $sdkBuildTools = Get-ChildItem $bt -Directory -ErrorAction SilentlyContinue |
            Sort-Object Name -Descending | Select-Object -First 1
        if ($sdkBuildTools) { break }
    }
}
if (-not $sdkBuildTools) {
    throw "Could not find Android SDK build-tools. Set ANDROID_SDK_ROOT (or ANDROID_HOME), or install build-tools via Android Studio SDK Manager. Searched: $($sdkRoots -join ', ')."
}

# Windows build-tools ship aapt.exe / apksigner.bat; macOS/Linux ship
# bare 'aapt' / 'apksigner'. $IsWindows is $true on Windows pwsh, $false
# on *nix pwsh, and $null on Windows PowerShell 5.1 (treat null as Win).
$onWindows = $IsWindows -ne $false
$aapt      = Join-Path $sdkBuildTools.FullName ("aapt"      + $(if ($onWindows) { ".exe" } else { "" }))
$apksigner = Join-Path $sdkBuildTools.FullName ("apksigner" + $(if ($onWindows) { ".bat" } else { "" }))
foreach ($tool in @($aapt, $apksigner)) {
    if (-not (Test-Path $tool)) {
        throw "Expected build-tools binary not found: $tool (build-tools dir: $($sdkBuildTools.FullName)). Reinstall/repair the build-tools package via Android Studio SDK Manager."
    }
}

# -- Determine which directories to process -----------------------------

$reviewDirs = @()

if ($Dir) {
    if (-not (Test-Path $Dir)) { throw "Directory not found: $Dir" }
    $reviewDirs = @([PSCustomObject]@{ Label = Split-Path $Dir -Leaf; Path = $Dir })
} else {
    if (-not $Bundles -or $Bundles.Count -eq 0) {
        throw "No bundles specified. Pass one or more .zip paths, or use -Dir <path>."
    }
    $ts       = (Get-Date).ToUniversalTime().ToString("yyyyMMddTHHmmssZ")
    $outRoot  = Join-Path $repoRoot "diagnostics/tpp-review-$ts"
    New-Item -ItemType Directory -Force -Path $outRoot | Out-Null

    foreach ($b in $Bundles) {
        if (-not (Test-Path $b)) { throw "Bundle not found: $b" }
        $label = [IO.Path]::GetFileNameWithoutExtension($b)
        $dest  = Join-Path $outRoot $label
        New-Item -ItemType Directory -Force -Path $dest | Out-Null
        Write-Host "Extracting $label ..." -ForegroundColor DarkGray
        Expand-Archive -Path $b -DestinationPath $dest -Force
        $reviewDirs += [PSCustomObject]@{ Label = $label; Path = $dest }
    }
}

# -- Helpers ------------------------------------------------------------

Add-Type -AssemblyName System.IO.Compression.FileSystem

function Get-ApkBaseline([string]$apkPath) {
    $out = & $aapt dump xmltree $apkPath AndroidManifest.xml 2>$null
    if ($LASTEXITCODE -ne 0) { return "?" }
    # The plugin-api value sits on the line after the "plugin-api" name
    # attribute in aapt's xmltree dump. Select-String is line-oriented so
    # a single cross-line regex can't catch it — walk adjacent lines.
    $lines = $out -split "`n"
    for ($i = 0; $i -lt $lines.Length - 1; $i++) {
        if ($lines[$i] -match '"plugin-api"' -and $lines[$i+1] -match '"([^"]+@[^"]+)"') {
            return $matches[1]
        }
    }
    return "?"
}

function Get-ApkSigningCert([string]$apkPath) {
    $out = & $apksigner verify --print-certs $apkPath 2>&1
    if ($LASTEXITCODE -ne 0) {
        return [PSCustomObject]@{ Verifies = $false; DN = ""; Sha256 = "" }
    }
    # Guard each extraction: if apksigner's output format differs (or is
    # localized) Select-String returns nothing, and blindly chaining
    # .Matches.Groups would surface a confusing error. Degrade to "".
    $dnMatch  = $out | Select-String -Pattern 'Signer #1 certificate DN: (.+)' | Select-Object -First 1
    $shaMatch = $out | Select-String -Pattern 'Signer #1 certificate SHA-256 digest: ([0-9a-f]+)' | Select-Object -First 1
    $dn     = if ($dnMatch)  { $dnMatch.Matches[0].Groups[1].Value }  else { "" }
    $sha256 = if ($shaMatch) { $shaMatch.Matches[0].Groups[1].Value } else { "" }
    return [PSCustomObject]@{ Verifies = $true; DN = $dn; Sha256 = $sha256 }
}

function Get-FortifyFindings([string]$fprPath) {
    # audit.fvdl is XML. Read the whole thing as text — the file is
    # usually 1-5 MB so no need for a streaming parser.
    $zip = $null
    try {
        $zip   = [System.IO.Compression.ZipFile]::OpenRead($fprPath)
        $entry = $zip.Entries | Where-Object { $_.FullName -eq "audit.fvdl" } | Select-Object -First 1
        if (-not $entry) { return @() }
        $sr = New-Object System.IO.StreamReader($entry.Open())
        $xml = $sr.ReadToEnd()
        $sr.Dispose()
    } finally {
        if ($zip) { $zip.Dispose() }
    }

    # Findings live inside <Vulnerability>...<AnalysisInfo>...</AnalysisInfo>
    # <ClassInfo><ClassID>..</ClassID><Kingdom>..</Kingdom><Type>..</Type>
    # <Subtype>..</Subtype><AnalyzerName>..</AnalyzerName>
    # <DefaultSeverity>..</DefaultSeverity></ClassInfo>
    # ...<InstanceInfo><InstanceID>..</InstanceID><InstanceSeverity>..
    # </InstanceSeverity>...
    $findings = @()
    $rx = [regex] '(?s)<Vulnerability>.*?<ClassInfo>.*?<ClassID>([^<]+)</ClassID>.*?<Kingdom>([^<]*)</Kingdom>.*?<Type>([^<]*)</Type>(?:.*?<Subtype>([^<]*)</Subtype>)?.*?<DefaultSeverity>([^<]*)</DefaultSeverity>.*?</ClassInfo>(?:.*?<InstanceInfo>.*?<InstanceSeverity>([^<]*)</InstanceSeverity>)?.*?</Vulnerability>'
    foreach ($m in $rx.Matches($xml)) {
        $findings += [PSCustomObject]@{
            ClassID          = $m.Groups[1].Value
            Kingdom          = $m.Groups[2].Value
            Type             = $m.Groups[3].Value
            Subtype          = $m.Groups[4].Value
            DefaultSeverity  = $m.Groups[5].Value
            InstanceSeverity = $m.Groups[6].Value
        }
    }
    return $findings
}

function Get-CvesFromDepCheck([string]$htmlPath) {
    $html = Get-Content -Raw $htmlPath
    return @($html | Select-String -Pattern 'CVE-\d{4}-\d+' -AllMatches).Matches |
        ForEach-Object { $_.Value } | Sort-Object -Unique
}

# -- Report per bundle --------------------------------------------------

$rowsForRename = @()

foreach ($r in $reviewDirs) {
    Write-Host "`n============================================================" -ForegroundColor Cyan
    Write-Host "  Bundle: $($r.Label)" -ForegroundColor Cyan
    Write-Host "  Path:   $($r.Path)" -ForegroundColor DarkGray
    Write-Host "============================================================" -ForegroundColor Cyan

    $apks = @(Get-ChildItem $r.Path -Filter "*.apk" -File)
    foreach ($apk in $apks) {
        Write-Host "`n-- APK: $($apk.Name)" -ForegroundColor Yellow
        $baseline = Get-ApkBaseline -apkPath $apk.FullName
        $signing  = Get-ApkSigningCert -apkPath $apk.FullName
        Write-Host "  baseline (manifest plugin-api): $baseline"
        if ($signing.Verifies) {
            Write-Host "  signed OK: cert SHA-256 $($signing.Sha256)"
            Write-Host "  cert DN: $($signing.DN)" -ForegroundColor DarkGray
        } else {
            Write-Host "  NOT SIGNED / signature verification failed" -ForegroundColor Red
        }
        Write-Host "  file sha256: $((Get-FileHash $apk.FullName -Algorithm SHA256).Hash.ToLower())"
        Write-Host "  file size:   $($apk.Length) bytes"

        $rowsForRename += [PSCustomObject]@{
            Bundle   = $r.Label
            SrcPath  = $apk.FullName
            Baseline = $baseline
        }
    }

    $fpr = Get-ChildItem $r.Path -Filter "*.fpr" -File | Select-Object -First 1
    if ($fpr) {
        Write-Host "`n-- Fortify SAST ($($fpr.Name))" -ForegroundColor Yellow
        $findings = Get-FortifyFindings -fprPath $fpr.FullName
        Write-Host "  total findings: $($findings.Count)"
        if ($findings.Count -gt 0) {
            $findings |
                Group-Object -Property Kingdom, Type |
                Sort-Object Count -Descending |
                ForEach-Object {
                    $sample = $_.Group[0]
                    "    {0,3}x  {1} / {2}  (severity {3})" -f `
                        $_.Count, $sample.Kingdom, $sample.Type, $sample.DefaultSeverity |
                        Write-Host
                }
        }
    } else {
        Write-Host "`n-- Fortify SAST: no .fpr found in bundle" -ForegroundColor DarkGray
    }

    $depCheck = Join-Path $r.Path "dependency-check-report.html"
    if (Test-Path $depCheck) {
        Write-Host "`n-- OWASP Dependency-Check (dependency-check-report.html)" -ForegroundColor Yellow
        $cves = Get-CvesFromDepCheck -htmlPath $depCheck
        if ($cves.Count -eq 0) {
            Write-Host "  no CVE identifiers found in report"
        } else {
            Write-Host "  CVE(s) reported ($($cves.Count)):"
            $cves | ForEach-Object { Write-Host "    $_" }
            Write-Host "  Open $depCheck in a browser for the full evidence table (CPE match confidence, affected file paths, etc.)."
        }
    } else {
        Write-Host "`n-- OWASP Dependency-Check: no report found" -ForegroundColor DarkGray
    }

    $buildLog = Join-Path $r.Path "build.log"
    if (Test-Path $buildLog) {
        $errCount = @(Select-String -Path $buildLog -Pattern '^\s*\[?(ERROR|FATAL)\]?').Count
        $warnCount = @(Select-String -Path $buildLog -Pattern '^\s*\[?(WARNING|WARN)\]?').Count
        Write-Host "`n-- build.log: $errCount error line(s), $warnCount warning line(s)" -ForegroundColor Yellow
        if ($errCount -gt 0) {
            Write-Host "    First few errors:" -ForegroundColor Red
            Select-String -Path $buildLog -Pattern '^\s*\[?(ERROR|FATAL)\]?' |
                Select-Object -First 5 |
                ForEach-Object { Write-Host "      $($_.Line)" -ForegroundColor Red }
        }
    }
}

# -- Optional rename step ----------------------------------------------

if ($RenameApks) {
    Write-Host "`n============================================================" -ForegroundColor Cyan
    Write-Host "  Renamed copies (original filenames left in place)" -ForegroundColor Cyan
    Write-Host "============================================================" -ForegroundColor Cyan

    # Read versionName from HEAD's build.gradle.kts.
    $bg   = Get-Content -Raw "app/build.gradle.kts"
    $vRaw = if ($bg -match 'versionName\s*=\s*"([^"]+)"') { $matches[1] } else { "unknown" }
    $flavor = $cfg.productFlavor

    foreach ($row in $rowsForRename) {
        # Extract just the "5.6.0" or "5.7.0" part from "com.atakmap.app@5.7.0.CIV"
        $short = if ($row.Baseline -match '@([0-9]+\.[0-9]+\.[0-9]+)') { $matches[1] } else { "unknown" }
        $newName = "$($cfg.sanityApkBaseName)-$vRaw-$short-$flavor-release.apk"
        $newPath = Join-Path (Split-Path $row.SrcPath -Parent) $newName
        if ($row.SrcPath -eq $newPath) {
            Write-Host "  $($row.Bundle): already correctly named ($newName)"
        } else {
            Copy-Item $row.SrcPath $newPath -Force
            Write-Host "  $($row.Bundle): $newName" -ForegroundColor Green
        }
    }
}

Write-Host "`nReview complete. Full extraction is under diagnostics/ (gitignored) for drilldown." -ForegroundColor Cyan
