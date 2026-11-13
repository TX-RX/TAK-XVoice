#!/usr/bin/env pwsh
#requires -Version 7.0
# scripts/verify-play-aab.ps1
#
# Verify that an Android App Bundle carries the TAK Product Center
# code-transparency signature ATAK requires before it will load a
# plugin delivered through Google Play. Run it on any AAB before it is
# attached to a GitHub Release or uploaded to Play; the Play deploy
# workflow runs it as a hard gate.
#
# Why this exists — a Play-delivered plugin is signed twice:
#   1. TPP signs the bundle. The TAK third-party plugin pipeline embeds
#      BUNDLE-METADATA/com.android.tools.build.bundletool/
#      code_transparency_signed.jwt: an RS256 JWT, signed by a TAK
#      Product Center "Bundle Release" key, listing the SHA-256 of every
#      dex / native lib in the bundle.
#   2. Play signs the APKs. Play App Signing replaces the APK signer
#      with the app-signing key and copies the JWT into base.apk as
#      META-INF/code_transparency_signed.jwt.
# ATAK cannot match (2) against its plugin allowlist, so it falls back
# to (1): PluginValidator.checkAppTransparencySignature verifies the JWT
# against ATAK's ACCEPTABLE_KEY_LIST and re-hashes every listed code
# file. An AAB built locally (bundle<Flavor>Release) has no JWT; it
# uploads, installs, and runs through Play without complaint, and ATAK
# then reports "The signature for the plugin is INVALID".
#
# The upload-key signature the deploy workflow adds is stripped by Play
# and plays no part in ATAK's decision.
#
# Usage:
#   .\scripts\verify-play-aab.ps1 <app-civ-release.aab>
#   .\scripts\verify-play-aab.ps1 <app-civ-release.aab> -RequireUnsigned
#
# -RequireUnsigned additionally fails if the AAB carries any JAR
# signature. TPP returns its AAB unsigned; the upload key is applied
# afterwards. A signed AAB at the "raw TPP output" stage means it came
# from somewhere else.
#
# Exit code 0 = ATAK will accept the bundle's code after Play delivery;
# 1 = it will not (or the bundle was modified after TPP signed it).

[CmdletBinding()]
param(
    [Parameter(Mandatory, Position = 0)]
    [string] $Path,

    [switch] $RequireUnsigned
)

$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent (Split-Path -Parent $PSCommandPath)

. (Join-Path $repoRoot "scripts/lib/Read-Config.ps1")
$cfg = Read-ScriptConfig -RepoRoot $repoRoot
$trusted = $cfg.trustedTransparencyCerts

$jwtEntryName = "BUNDLE-METADATA/com.android.tools.build.bundletool/code_transparency_signed.jwt"
# Present in a plugin's dex only when it was compiled without ATAK's
# release ProGuard mapping; release ATAK ships the class obfuscated, so
# such a plugin fails to load (ClassNotFound) even when correctly signed.
$unmappedSdkSymbol = "Lgov/tak/api/plugin/IServiceController;"
$dexEntryPattern = '^[^/]+/dex/[^/]+\.dex$'
$nativeLibEntryPattern = '^[^/]+/lib/.+\.so$'
$unmappedSdkSymbolBytes = [Text.Encoding]::ASCII.GetBytes($unmappedSdkSymbol)

$failures = [System.Collections.Generic.List[string]]::new()

function Write-Check([string] $label, [bool] $ok, [string] $detail) {
    $mark  = if ($ok) { "PASS" } else { "FAIL" }
    $color = if ($ok) { "Green" } else { "Red" }
    Write-Host ("  [{0}] {1}" -f $mark, $label) -ForegroundColor $color
    if ($detail) { Write-Host "         $detail" -ForegroundColor DarkGray }
    if (-not $ok) { $script:failures.Add($label) }
}

function ConvertFrom-Base64Url([string] $s) {
    $t = $s.Replace('-', '+').Replace('_', '/')
    switch ($t.Length % 4) {
        2 { $t += '==' }
        3 { $t += '=' }
        1 { throw "malformed base64url segment" }
    }
    return [Convert]::FromBase64String($t)
}

function Get-EntrySha256([System.IO.Compression.ZipArchiveEntry] $entry) {
    $sha = [System.Security.Cryptography.SHA256]::Create()
    $st  = $entry.Open()
    try {
        return [Convert]::ToHexString($sha.ComputeHash($st)).ToLowerInvariant()
    } finally {
        $st.Dispose()
        $sha.Dispose()
    }
}

function Test-EntryContainsBytes(
    [System.IO.Compression.ZipArchiveEntry] $entry,
    [byte[]] $needle
) {
    $stream = $entry.Open()
    $buffer = [byte[]]::new(8192 + $needle.Length)
    $carry = 0
    try {
        while (($read = $stream.Read($buffer, $carry, $buffer.Length - $carry)) -gt 0) {
            $length = $carry + $read
            for ($start = 0; $start -le $length - $needle.Length; $start++) {
                $matched = $true
                for ($i = 0; $i -lt $needle.Length; $i++) {
                    if ($buffer[$start + $i] -ne $needle[$i]) {
                        $matched = $false
                        break
                    }
                }
                if ($matched) { return $true }
            }

            $carry = [Math]::Min($needle.Length - 1, $length)
            [Array]::Copy($buffer, $length - $carry, $buffer, 0, $carry)
        }
        return $false
    } finally {
        $stream.Dispose()
    }
}

if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) { throw "AAB not found: $Path" }
$aabPath = (Resolve-Path -LiteralPath $Path).ProviderPath

Add-Type -AssemblyName System.IO.Compression.FileSystem

Write-Host "Verifying $aabPath" -ForegroundColor Cyan
Write-Host ("  file sha256: {0}" -f (Get-FileHash -LiteralPath $aabPath -Algorithm SHA256).Hash.ToLowerInvariant()) -ForegroundColor DarkGray

# Early returns stop at the first check whose failure makes the rest
# meaningless; the exit code is decided once, after the function returns.
function Test-Bundle([System.IO.Compression.ZipArchive] $zip) {
    if (-not $zip.GetEntry("BundleConfig.pb")) {
        Write-Check "file is an Android App Bundle" $false "no BundleConfig.pb — this is not an .aab (an .apk cannot go to Play as a plugin bundle)."
        return
    }

    # Run first, without an early return, so a local build reports both
    # this and the missing JWT below.
    $unmappedDex = @(foreach ($e in $zip.Entries | Where-Object { $_.FullName -match $dexEntryPattern }) {
        if (Test-EntryContainsBytes $e $unmappedSdkSymbolBytes) { $e.FullName }
    })
    Write-Check "dex uses ATAK's release ProGuard mapping" ($unmappedDex.Count -eq 0) `
        $(if ($unmappedDex.Count) { ($unmappedDex -join ", ") + " reference unobfuscated $unmappedSdkSymbol; release ATAK cannot load it." } else { "" })

    # 1. The transparency JWT must exist.
    $jwtEntry = $zip.GetEntry($jwtEntryName)
    if (-not $jwtEntry) {
        Write-Check "code-transparency JWT present" $false ("missing $jwtEntryName. Not TPP output — likely a local " +
            "bundle<Flavor>Release build. ATAK will report the plugin signature INVALID after Play delivery.")
        return
    }
    Write-Check "code-transparency JWT present" $true ""

    $sr  = [System.IO.StreamReader]::new($jwtEntry.Open())
    $jwt = $sr.ReadToEnd().Trim()
    $sr.Dispose()
    $parts = $jwt.Split('.')
    if ($parts.Count -ne 3) {
        Write-Check "JWT is well-formed" $false "expected 3 dot-separated segments, found $($parts.Count)."
        return
    }

    $header  = [Text.Encoding]::UTF8.GetString((ConvertFrom-Base64Url $parts[0])) | ConvertFrom-Json
    $payload = [Text.Encoding]::UTF8.GetString((ConvertFrom-Base64Url $parts[1])) | ConvertFrom-Json

    # 2. ATAK verifies with SHA256withRSA only.
    Write-Check "JWT alg is RS256" ($header.alg -eq "RS256") "alg = $($header.alg)"

    # 3. The signing cert must be one ATAK allowlists. ATAK ignores x5c and
    #    tries each allowlisted cert in turn; pinning x5c[0] by fingerprint
    #    and verifying against it is equivalent and gives a clearer error.
    if (-not $header.x5c -or $header.x5c.Count -lt 1) {
        Write-Check "JWT names its signing certificate" $false "no x5c header."
        return
    }
    $der    = [Convert]::FromBase64String($header.x5c[0])
    $loader = 'System.Security.Cryptography.X509Certificates.X509CertificateLoader' -as [type]
    $cert   = if ($loader) { $loader::LoadCertificate($der) }
              else { [System.Security.Cryptography.X509Certificates.X509Certificate2]::new($der) }
    $certSha = [Convert]::ToHexString([System.Security.Cryptography.SHA256]::HashData($der)).ToLowerInvariant()
    Write-Check "signer is an ATAK-allowlisted TPP bundle key" ($trusted -contains $certSha) `
        "$($cert.Subject)  (sha256 $certSha)"

    # 4. The RS256 signature over "<header>.<payload>" must verify.
    $rsa = $cert.PublicKey.GetRSAPublicKey()
    $sigOk = $false
    if ($rsa) {
        $sigOk = $rsa.VerifyData(
            [Text.Encoding]::ASCII.GetBytes("$($parts[0]).$($parts[1])"),
            (ConvertFrom-Base64Url $parts[2]),
            [System.Security.Cryptography.HashAlgorithmName]::SHA256,
            [System.Security.Cryptography.RSASignaturePadding]::Pkcs1)
    }
    Write-Check "JWT signature verifies" $sigOk $(if (-not $rsa) { "signing key is not RSA" } else { "" })

    # 5. Every listed code file must be present with a matching hash, and
    #    every code file in the bundle must be listed. ATAK itself only
    #    re-hashes the listed files that land in base.apk, but an unlisted
    #    or altered file means the bundle was changed after TPP signed it.
    $listed = @{}
    $bad    = [System.Collections.Generic.List[string]]::new()
    foreach ($f in @($payload.codeRelatedFile | Where-Object { $_ })) {
        $listed[$f.path] = $true
        $e = $zip.GetEntry($f.path)
        if (-not $e) { $bad.Add("missing: $($f.path)"); continue }
        $actual = Get-EntrySha256 $e
        if ($actual -ne $f.sha256) { $bad.Add("hash mismatch: $($f.path)") }
    }
    foreach ($e in $zip.Entries) {
        $isCode = $e.FullName -match $dexEntryPattern -or $e.FullName -match $nativeLibEntryPattern
        if ($isCode -and -not $listed.ContainsKey($e.FullName)) { $bad.Add("not covered by JWT: $($e.FullName)") }
    }
    $detail = if ($bad.Count) { $bad -join "; " } else { "$($listed.Count) code file(s) match" }
    Write-Check "dex / native-lib hashes match the JWT" ($listed.Count -gt 0 -and $bad.Count -eq 0) $detail

    # 6. Optional provenance check: raw TPP output is unsigned.
    if ($RequireUnsigned) {
        $sigFiles = @($zip.Entries | Where-Object { $_.FullName -match '^META-INF/[^/]+\.(SF|RSA|EC|DSA)$' })
        Write-Check "AAB is unsigned (raw TPP output)" ($sigFiles.Count -eq 0) `
            $(if ($sigFiles.Count) { "found: " + (($sigFiles | ForEach-Object FullName) -join ", ") } else { "" })
    }
}

$zip = $null
try {
    $zip = [System.IO.Compression.ZipFile]::OpenRead($aabPath)
    Test-Bundle $zip
} catch {
    Write-Check "bundle could be parsed" $false $_.Exception.Message
} finally {
    if ($null -ne $zip) { $zip.Dispose() }
}

if ($failures.Count) {
    Write-Host "NOT DEPLOYABLE ($($failures.Count) check(s) failed). Attach the AAB exactly as TPP returned it — never a local build." -ForegroundColor Red
    exit 1
}
Write-Host "OK: TPP code-transparency signature is valid for ATAK." -ForegroundColor Green
exit 0
