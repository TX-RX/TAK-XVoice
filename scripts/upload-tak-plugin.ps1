#!/usr/bin/env pwsh
#requires -Version 7.0
# scripts/upload-tak-plugin.ps1
#
# Upload one or more signed plugin APKs to a TAK / OpenTAK server for
# distribution to team devices. Reads server URL + auth + upload params
# from scripts/config.json's `takServer` block; secrets live in env
# vars named by that block, never in config.json itself.
#
# Usage:
#   .\scripts\upload-tak-plugin.ps1 -Apk <path> -Baseline <5.6.0|5.7.0> [-Send]
#   .\scripts\upload-tak-plugin.ps1 -Apk a.apk,b.apk -Baseline 5.6.0,5.7.0 [-Send]
#
# Without -Send, the script performs a dry-run: prints exactly what
# request it would POST (URL, the auth line with the secret redacted to
# env-var-name + length, form fields, per-file size + SHA-256) and exits
# without touching the network. Re-run with -Send once it looks right.
#
# The Baseline value is templated into extraFormFields (any string of
# form "{baseline}" in a config value is replaced at request time).
# Order of -Apk and -Baseline matters and their counts must match.

[CmdletBinding()]
param(
    [Parameter(Mandatory)]
    [string[]] $Apk,

    [Parameter(Mandatory)]
    [string[]] $Baseline,

    # Only when this switch is passed does the script actually POST.
    # Defaults to false so an accidental invocation just prints the
    # intended request.
    [switch]   $Send
)

$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent (Split-Path -Parent $PSCommandPath)
Set-Location $repoRoot

. (Join-Path $repoRoot "scripts/lib/Read-Config.ps1")
$cfg = Read-ScriptConfig -RepoRoot $repoRoot
$tak = $cfg.takServer

if (-not $tak) {
    throw "No 'takServer' block in scripts/config.json. Copy from scripts/config.example.json and fill in baseUrl + auth settings."
}
if (-not $tak.baseUrl) {
    throw "takServer.baseUrl is not set in scripts/config.json. Set it to your TAK / OpenTAK server URL (e.g. https://tak.example.com:8443)."
}
if (-not $tak.uploadPath) {
    throw "takServer.uploadPath is not set in scripts/config.json (the server's upload endpoint path, e.g. /api/plugins/upload)."
}
if (-not $tak.fileFieldName) {
    throw "takServer.fileFieldName is not set in scripts/config.json (the multipart form field the server expects the APK under, e.g. 'file')."
}
if ($Apk.Count -ne $Baseline.Count) {
    throw "-Apk count ($($Apk.Count)) must equal -Baseline count ($($Baseline.Count))."
}

# -- Resolve auth header / TLS options ----------------------------------

$auth = @{ Header = @{}; ClientCertPath = $null; ClientCertPass = $null; TokenPreview = "(none)" }
switch -Regex ($tak.authMode) {
    "bearer" {
        $var = $tak.tokenEnvVar
        if (-not $var) { throw "authMode=bearer but takServer.tokenEnvVar is not set." }
        $tok = [Environment]::GetEnvironmentVariable($var)
        if (-not $tok) {
            throw "Env var '$var' is empty. Set it in this shell (or system-wide via setx) before running with -Send."
        }
        $auth.Header["Authorization"] = "Bearer $tok"
        $auth.TokenPreview = "Bearer <$var; $($tok.Length) chars>"
    }
    "basic" {
        if (-not $tak.basicUser) { throw "authMode=basic but takServer.basicUser is not set." }
        $passVar = $tak.basicPassEnvVar
        if (-not $passVar) { throw "authMode=basic but takServer.basicPassEnvVar is not set." }
        $pass = [Environment]::GetEnvironmentVariable($passVar)
        if (-not $pass) { throw "Env var '$passVar' is empty." }
        $pair = "$($tak.basicUser):$pass"
        $b64  = [Convert]::ToBase64String([Text.Encoding]::ASCII.GetBytes($pair))
        $auth.Header["Authorization"] = "Basic $b64"
        $auth.TokenPreview = "Basic <$($tak.basicUser):(pw from $passVar)>"
    }
    "mtls" {
        if (-not $tak.clientCertPath) { throw "authMode=mtls but takServer.clientCertPath is not set." }
        if (-not (Test-Path $tak.clientCertPath)) { throw "Client cert not found at $($tak.clientCertPath)" }
        $auth.ClientCertPath = $tak.clientCertPath
        if ($tak.clientCertPassEnvVar) {
            $auth.ClientCertPass = [Environment]::GetEnvironmentVariable($tak.clientCertPassEnvVar)
        }
        $auth.TokenPreview = "mTLS cert=$(Split-Path $tak.clientCertPath -Leaf)"
    }
    "none" {
        $auth.TokenPreview = "(none)"
    }
    default {
        throw "Unknown takServer.authMode '$($tak.authMode)'. Use one of: bearer, basic, mtls, none."
    }
}

# Normalize the join so a config uploadPath with or without a leading
# slash both produce a valid URL (baseUrl + "/api/..." not "host:8443api/...").
$uploadPath = $tak.uploadPath
if ($uploadPath -notmatch '^/') { $uploadPath = "/$uploadPath" }
$fullUrl = "$($tak.baseUrl.TrimEnd('/'))$uploadPath"

Write-Host "TAK server upload target" -ForegroundColor Cyan
Write-Host "  URL:      $fullUrl"
Write-Host "  auth:     $($auth.TokenPreview)"
Write-Host "  fileField: $($tak.fileFieldName)"
if ($tak.insecureSkipTlsVerify) {
    Write-Host "  WARNING: insecureSkipTlsVerify=true. TLS certificate validation is DISABLED." -ForegroundColor Yellow
}

# -- Per-file upload loop ----------------------------------------------

$anyFailed = $false
for ($i = 0; $i -lt $Apk.Count; $i++) {
    $apkPath = $Apk[$i]
    $bl      = $Baseline[$i]

    if (-not (Test-Path $apkPath)) {
        Write-Host "`nAPK not found: $apkPath" -ForegroundColor Red
        $anyFailed = $true
        continue
    }
    $apkInfo = Get-Item $apkPath
    $sha     = (Get-FileHash $apkPath -Algorithm SHA256).Hash.ToLower()

    Write-Host "`n=== $($apkInfo.Name)  (baseline $bl)" -ForegroundColor Cyan
    Write-Host "  size:   $($apkInfo.Length) bytes"
    Write-Host "  sha256: $sha"

    # Template extra fields with the baseline value.
    $formFields = @{}
    if ($tak.extraFormFields) {
        foreach ($p in $tak.extraFormFields.PSObject.Properties) {
            $v = $p.Value
            if ($v -is [string]) { $v = $v.Replace("{baseline}", $bl) }
            $formFields[$p.Name] = $v
        }
    }
    Write-Host "  extra form fields:"
    if ($formFields.Count -eq 0) { Write-Host "    (none)" -ForegroundColor DarkGray }
    foreach ($k in $formFields.Keys) { Write-Host "    $k = $($formFields[$k])" }

    if (-not $Send) {
        Write-Host "  DRY RUN — not sending. Re-run with -Send to actually upload." -ForegroundColor Yellow
        continue
    }

    # Build the multipart request and POST.
    # PowerShell's Invoke-WebRequest -Form supports Path values, which
    # streams the file rather than buffering it in memory. Good for a
    # ~3 MB APK; also correct MIME type inference.
    $form = @{ $tak.fileFieldName = Get-Item $apkPath }
    foreach ($k in $formFields.Keys) { $form[$k] = $formFields[$k] }

    $iwArgs = @{
        Uri     = $fullUrl
        Method  = "POST"
        Form    = $form
        Headers = $auth.Header
    }
    if ($auth.ClientCertPath) {
        # Load the client cert directly so a password-protected PFX works
        # (Get-PfxCertificate can't take a password non-interactively).
        # clientCertPassEnvVar, if set, was read into $auth.ClientCertPass.
        if ($auth.ClientCertPass) {
            $iwArgs["Certificate"] = [System.Security.Cryptography.X509Certificates.X509Certificate2]::new(
                $auth.ClientCertPath, $auth.ClientCertPass)
        } else {
            $iwArgs["Certificate"] = [System.Security.Cryptography.X509Certificates.X509Certificate2]::new(
                $auth.ClientCertPath)
        }
    }
    if ($tak.insecureSkipTlsVerify) {
        $iwArgs["SkipCertificateCheck"] = $true
    }

    try {
        $resp = Invoke-WebRequest @iwArgs
        Write-Host "  HTTP $($resp.StatusCode)" -ForegroundColor Green
        if ($resp.Content) {
            $body = $resp.Content
            if ($body.Length -gt 500) { $body = $body.Substring(0, 500) + "…" }
            Write-Host "  Response: $body" -ForegroundColor DarkGray
        }
    } catch {
        Write-Host "  FAILED: $($_.Exception.Message)" -ForegroundColor Red
        if ($_.Exception.Response) {
            Write-Host "  HTTP $($_.Exception.Response.StatusCode.value__) $($_.Exception.Response.ReasonPhrase)" -ForegroundColor Red
        }
        $anyFailed = $true
    }
}

if ($anyFailed) { exit 1 }
