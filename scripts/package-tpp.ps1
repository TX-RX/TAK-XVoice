#!/usr/bin/env pwsh
#requires -Version 7.0
# scripts/package-tpp.ps1
#
# Build a TAK Product Portal (TPP) submission bundle from the current HEAD.
#
# Per-plugin/operator values (package name, filename prefix, baselines,
# forbidden-content patterns) live in scripts/config.json — copy
# scripts/config.example.json and edit. See scripts/lib/Read-Config.ps1
# for the fallback defaults.
#
# What lands in build/tpp/ :
#
#   <sourceZipBaseName>-<versionName>-<baseline>.zip   (one per baseline)
#     The actual TPP submission artifact — a source zip that TPP's
#     third-party pipeline builds and signs on their side.
#     Built with `git archive HEAD`, which includes ONLY tracked files.
#     Everything gitignored (logs/, .audio/, .logs/, .tmp/, diagnostics/,
#     build/, .gradle/, keystore.properties, credentials, .env*) is
#     automatically excluded — the load-bearing safety net that stops
#     operator field captures from leaking into a submission.
#
#   <sanityApkBaseName>-<versionName>-<baseline>-<flavor>-release.apk (sanity only)
#     Unsigned civ-release APKs, built as a local sanity check that
#     the source builds cleanly. DO NOT SUBMIT — TPP applies the
#     Untrusted Plugin Release cert on their side from the source zip.
#     Skip with -SkipApkBuild if you only need source zips.
#
# Post-build sanity check greps every source zip for forbidden content
# patterns (universal built-in list + config.forbiddenPatterns). If ANY
# match, the script errors out and refuses to treat the bundle as ready.
# Motivating incident: 2026-07-11 TPP submission included ~175 MB of
# raw field logcats and mumble recordings whose filenames leaked the
# operator's real TAK server hostname and callsigns, because the zip
# was built with a tool that ignored .gitignore. Never again.

[CmdletBinding()]
param(
    # Baselines to produce zips + sanity APKs for. Defaults to
    # config.tppBaselines.
    [string[]] $Baselines,

    # Skip the local sanity APK build (source zips only).
    [switch]   $SkipApkBuild,

    # Allow building from a dirty working tree. Off by default because
    # a TPP submission that doesn't match HEAD is hard to reproduce.
    [switch]   $AllowDirtyTree
)

$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent (Split-Path -Parent $PSCommandPath)
Set-Location $repoRoot

. (Join-Path $repoRoot "scripts/lib/Read-Config.ps1")
$cfg = Read-ScriptConfig -RepoRoot $repoRoot

if (-not $Baselines -or $Baselines.Count -eq 0) { $Baselines = $cfg.tppBaselines }

$flavor    = $cfg.productFlavor
$flavorCap = [char]::ToUpper($flavor[0]) + $flavor.Substring(1)

# -- 1. Git state sanity ------------------------------------------------

$dirty = & git status --porcelain
if ($LASTEXITCODE -ne 0) {
    throw "git status failed — is this a git checkout?"
}
if ($dirty -and -not $AllowDirtyTree) {
    Write-Host "Working tree has uncommitted changes:" -ForegroundColor Red
    $dirty | ForEach-Object { Write-Host "  $_" -ForegroundColor Red }
    throw "Commit or stash before packaging TPP. Override with -AllowDirtyTree only if you know what you're doing."
}

$branch = (& git rev-parse --abbrev-ref HEAD).Trim()
$sha    = (& git rev-parse HEAD).Trim()

if ($branch -ne "main" -and -not $AllowDirtyTree) {
    Write-Host "WARNING: not on main (currently on '$branch'). TPP submissions usually come off main." -ForegroundColor Yellow
    Write-Host "         Continuing because HEAD is clean; pass -AllowDirtyTree to silence this note.`n" -ForegroundColor Yellow
}

Write-Host "Building TPP bundle from $branch @ $sha" -ForegroundColor Cyan

# -- 2. Read versionName from app/build.gradle.kts ----------------------

$buildGradle = Get-Content -Raw "app/build.gradle.kts"
if ($buildGradle -notmatch 'versionName\s*=\s*"([^"]+)"') {
    throw "Could not read versionName from app/build.gradle.kts"
}
$versionName = $matches[1]
Write-Host "versionName = $versionName  (flavor=$flavor, prefix=$($cfg.sourceZipPrefix))" -ForegroundColor Cyan

# -- 3. Output dir ------------------------------------------------------

$out = Join-Path $repoRoot "build/tpp"
New-Item -ItemType Directory -Force -Path $out | Out-Null

function Get-BaselineShort([string]$b) {
    ($b -split '\.')[0..1] -join '.'
}

# -- 4. Source zips (git archive + per-baseline gradle.properties patch)

# TPP runs `./gradlew assemble<Flavor>Release` on a clean checkout of
# whatever we submit — it never sees a `-P` CLI override. So each
# baseline's zip needs its own `gradle.properties` with
# `atakBaselineVersion=<baseline>` baked in. Otherwise TPP falls back
# to the checked-in default and produces the wrong-baseline APK (this
# happened 2026-07-13: both submissions returned 5.6.0-baseline APKs
# despite the intent being 5.6 + 5.7).

$baseZip = Join-Path $out "_base-source.tmp.zip"
Write-Host "`nBuilding base source zip via git archive HEAD..." -ForegroundColor Cyan
& git archive --format=zip --prefix="$($cfg.sourceZipPrefix)/" HEAD -o $baseZip
if ($LASTEXITCODE -ne 0) {
    throw "git archive failed"
}

$gradlePropsEntry = "$($cfg.sourceZipPrefix)/gradle.properties"
foreach ($b in $Baselines) {
    $short   = Get-BaselineShort $b
    $zipPath = Join-Path $out "$($cfg.sourceZipBaseName)-$versionName-$short.zip"
    Copy-Item $baseZip $zipPath -Force

    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $zip = [System.IO.Compression.ZipFile]::Open($zipPath, [System.IO.Compression.ZipArchiveMode]::Update)
    try {
        $entry = $zip.GetEntry($gradlePropsEntry)
        if (-not $entry) {
            throw "Expected entry '$gradlePropsEntry' not found in the source zip. Adjust sourceZipPrefix in scripts/config.json or add a repo-root gradle.properties."
        }

        # Read existing content.
        $stream = $entry.Open()
        $sr = New-Object System.IO.StreamReader($stream)
        $content = $sr.ReadToEnd()
        $sr.Dispose()

        # Replace atakBaselineVersion line, or append if not present.
        if ($content -match '(?m)^atakBaselineVersion=.*$') {
            $content = [regex]::Replace($content, '(?m)^atakBaselineVersion=.*$', "atakBaselineVersion=$b")
        } else {
            $content = $content.TrimEnd() + "`natakBaselineVersion=$b`n"
        }

        # Overwrite entry: delete + recreate. .NET's ZipArchive doesn't
        # expose in-place content replacement so this is the sanctioned
        # pattern.
        $entry.Delete()
        $newEntry = $zip.CreateEntry($gradlePropsEntry)
        $ws = $newEntry.Open()
        $sw = New-Object System.IO.StreamWriter($ws)
        $sw.Write($content)
        $sw.Dispose()
    } finally {
        $zip.Dispose()
    }
    Write-Host "  patched atakBaselineVersion=$b → $(Split-Path $zipPath -Leaf)" -ForegroundColor DarkGray
}

Remove-Item $baseZip -Force

# -- 5. Sensitive-content sanity check ----------------------------------

# Two passes, per Read-Config.ps1:
#   PATH pass    — universal ∪ operator patterns vs every entry's name.
#                  Catches leaked files by filename (the 2026-07-11
#                  logcat/mumble-recording incident).
#   CONTENT pass — operator patterns only vs the text of each tracked
#                  text file. Catches a real hostname / callsign / unit
#                  name embedded in a committed comment or fixture, which
#                  the path pass would never see. Universal path shapes
#                  ('/logs/', 'credentials', '.env') are excluded from
#                  this pass on purpose — legitimate source routinely
#                  contains those words and would false-positive.
$pathRe    = $cfg.forbiddenPatterns -join '|'
$contentRe = if ($cfg.contentForbiddenPatterns.Count -gt 0) { $cfg.contentForbiddenPatterns -join '|' } else { $null }

# Only text-like entries are content-scanned. Binaries (images, the
# gradle wrapper jar, fonts) are skipped — they can't carry a
# human-readable operator string and would waste time / trip the NUL
# guard. Anything not on this list still gets a NUL-byte sniff before
# being treated as text.
$textExt = @(
    '.kt', '.kts', '.java', '.xml', '.json', '.gradle', '.properties',
    '.txt', '.md', '.pro', '.cfg', '.yml', '.yaml', '.gitignore',
    '.gitattributes', '.ps1', '.sh', '.bat', '.py', '.html'
)
$maxScanBytes = 4MB

function Get-ScanText([System.IO.Compression.ZipArchiveEntry]$entry, [byte[]]$bytes) {
    # Decode an entry's bytes into the text variants worth scanning, or
    # @() to treat it as binary (path-scan only). UTF-8 is scanned
    # always; UTF-16 LE/BE are added whenever a BOM or interior NUL byte
    # signals a UTF-16 text file — otherwise a hostname stored as UTF-16
    # ("t\0e\0x\0a\0s…") slides straight past a UTF-8-only decode. Note a
    # NUL sniff alone can't gate this: a legitimate UTF-16 .xml is full
    # of NULs, so we must decode it as UTF-16 rather than skip it.
    $ext   = [IO.Path]::GetExtension($entry.Name).ToLower()
    $known = $textExt -contains $ext

    $bomLE = $bytes.Length -ge 2 -and $bytes[0] -eq 0xFF -and $bytes[1] -eq 0xFE
    $bomBE = $bytes.Length -ge 2 -and $bytes[0] -eq 0xFE -and $bytes[1] -eq 0xFF
    $probe = [Math]::Min($bytes.Length, 8192)
    $hasNul = $false
    for ($i = 0; $i -lt $probe; $i++) { if ($bytes[$i] -eq 0) { $hasNul = $true; break } }

    if (-not $known) {
        # Non-text extension (.png, .jar, ...) → binary, path-scan only.
        if (-not [string]::IsNullOrEmpty($ext)) { return @() }
        # Extensionless (LICENSE, gradlew, Dockerfile...): NUL without a
        # UTF-16 BOM ⇒ a genuine binary; skip it.
        if ($hasNul -and -not ($bomLE -or $bomBE)) { return @() }
    }

    $texts = @([System.Text.Encoding]::UTF8.GetString($bytes))
    if ($bomLE -or $bomBE -or $hasNul) {
        $texts += [System.Text.Encoding]::Unicode.GetString($bytes)          # UTF-16 LE
        $texts += [System.Text.Encoding]::BigEndianUnicode.GetString($bytes) # UTF-16 BE
    }
    return $texts
}

Add-Type -AssemblyName System.IO.Compression.FileSystem
Write-Host "`nSanity-checking source zips (path + content)..." -ForegroundColor Cyan
$anyBad = $false
foreach ($z in Get-ChildItem "$out/$($cfg.sourceZipBaseName)-$versionName-*.zip") {
    $badPaths   = @()
    $badContent = @()
    $zip = $null
    try {
        $zip = [System.IO.Compression.ZipFile]::OpenRead($z.FullName)
        foreach ($e in $zip.Entries) {
            if ($e.FullName -match $pathRe) {
                $badPaths += $e.FullName
            }
            # Directory entries and oversized blobs: path-scan only.
            if (-not $contentRe -or $e.Length -eq 0 -or $e.Length -gt $maxScanBytes) { continue }

            $ms = New-Object System.IO.MemoryStream
            $es = $e.Open()
            try { $es.CopyTo($ms) } finally { $es.Dispose() }
            $bytes = $ms.ToArray()
            $ms.Dispose()

            $hits = foreach ($text in (Get-ScanText $e $bytes)) {
                foreach ($m in [regex]::Matches($text, $contentRe)) { $m.Value }
            }
            # Dedupe: the same secret can surface in more than one decode.
            foreach ($v in ($hits | Select-Object -Unique)) {
                $badContent += "$($e.FullName): matched /$v/"
            }
        }
    } finally {
        if ($zip) { $zip.Dispose() }
    }

    if ($badPaths.Count -gt 0 -or $badContent.Count -gt 0) {
        Write-Host "  TAINTED: $($z.Name)" -ForegroundColor Red
        if ($badPaths.Count -gt 0) {
            Write-Host "    forbidden PATHS:" -ForegroundColor Red
            $badPaths | Select-Object -First 10 | ForEach-Object { Write-Host "      $_" -ForegroundColor Red }
            if ($badPaths.Count -gt 10) { Write-Host "      ... $($badPaths.Count - 10) more" -ForegroundColor Red }
        }
        if ($badContent.Count -gt 0) {
            Write-Host "    forbidden CONTENT:" -ForegroundColor Red
            $badContent | Select-Object -First 10 | ForEach-Object { Write-Host "      $_" -ForegroundColor Red }
            if ($badContent.Count -gt 10) { Write-Host "      ... $($badContent.Count - 10) more" -ForegroundColor Red }
        }
        $anyBad = $true
    } else {
        Write-Host "  clean:   $($z.Name)" -ForegroundColor Green
    }
}
if ($anyBad) {
    throw "Sensitive content found in source zips. Do NOT submit. Investigate .gitignore coverage and forbiddenPatterns in scripts/config.json."
}

# -- 6. Sanity APK build (optional) -------------------------------------

if (-not $SkipApkBuild) {
    foreach ($b in $Baselines) {
        Write-Host "`nBuilding $flavor-release APK for baseline $b (sanity only, not for submission)..." -ForegroundColor Cyan

        # `clean` deletes app/build/empty-atak-proguard-mapping.txt that
        # build.gradle.kts writes at config time. R8 then can't find it.
        # Fix: split clean and assemble into separate Gradle invocations
        # so the config pass runs again before R8 executes.
        & ".\gradlew.bat" clean
        if ($LASTEXITCODE -ne 0) { throw "gradle clean failed" }

        & ".\gradlew.bat" "-PatakBaselineVersion=$b" "assemble${flavorCap}Release"
        if ($LASTEXITCODE -ne 0) { throw "assemble${flavorCap}Release failed for baseline $b" }

        $apk = "app/build/outputs/apk/$flavor/release/$($cfg.sanityApkBaseName)-$versionName-$b-$flavor-release.apk"
        if (-not (Test-Path $apk)) {
            throw "Expected APK not found: $apk"
        }
        Copy-Item $apk (Join-Path $out (Split-Path -Leaf $apk)) -Force
    }
}

# -- 7. Summary + SHA-256 -----------------------------------------------

Write-Host "`n=== TPP bundle ===" -ForegroundColor Cyan
Get-ChildItem $out | Sort-Object Name | ForEach-Object {
    $h = (Get-FileHash $_.FullName -Algorithm SHA256).Hash.ToLower()
    ("  {0,10:N0}  {1}" -f $_.Length, $_.Name) | Write-Host
    Write-Host ("              sha256: $h") -ForegroundColor DarkGray
}
Write-Host "`nSource: $branch @ $sha" -ForegroundColor Cyan
Write-Host "Submit the .zip files to the TPP portal. The .apk files are LOCAL SANITY ONLY, not for submission." -ForegroundColor Green
