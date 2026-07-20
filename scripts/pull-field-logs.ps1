#!/usr/bin/env pwsh
#requires -Version 7.0
# scripts/pull-field-logs.ps1
#
# Pull the plugin's on-device diagnostic logs (written by
# DiagnosticLogger to <app-external-files>/xv-logs/xv-YYYY-MM-DD.log) off
# an attached device, then produce a SCRUBBED copy safe to hand to a
# reviewer. The raw pull and the scrubbed bundle both land under the
# gitignored logs/ directory — nothing here is ever committed (CLAUDE.md:
# logs/ .logs/ .tmp/ diagnostics/ are field-capture material, never
# committed, no `git add -f`).
#
# Configuration (all optional) lives in scripts/config.json — copy
# scripts/config.example.json and edit. The operator-specific redaction
# regexes (real TAK hostnames, callsigns, unit names) come from
# config.forbiddenPatterns, the same list package-tpp.ps1 uses.
#
# Prerequisites:
#   - adb on PATH with at least one authorized device (see install-dev.ps1).
#   - The plugin must have run on the device (so xv-logs/ exists). The path
#     is app-scoped external storage, readable over `adb pull` without root
#     even on locked-down field devices.
#
# Redaction (safety-first — over-redacts rather than leak):
#   - Bluetooth MACs      -> first/last octet kept, middle four -> XX
#                            (mirrors app/.../aina/MacRedact.kt).
#   - IPv4 / IPv6 addrs   -> [IPv4] / [IPv6]. NOTE: 4-part version quads
#                            (e.g. an ATAK "5.7.0.10") are collaterally
#                            masked — that is acceptable; the commit SHA in
#                            the log identifies the build.
#   - GPS lat,lon pairs   -> [GEO].
#   - Operator patterns   -> [REDACTED] (config.forbiddenPatterns: real TAK
#                            hostnames/FQDNs, callsigns, unit/agency names).
#   A final gate re-scans the SCRUBBED output for any residual raw MAC,
#   IPv4, or operator pattern and FAILS (non-zero exit) if one survives —
#   so an incomplete scrub can't be handed off by mistake.
#
# Common invocations:
#   .\scripts\pull-field-logs.ps1                 # every authorized device
#   .\scripts\pull-field-logs.ps1 -Serial <ID>    # one device
#   .\scripts\pull-field-logs.ps1 -KeepRaw        # keep the un-scrubbed pull
#   .\scripts\pull-field-logs.ps1 -DevicePath /sdcard/.../files/xv-logs

[CmdletBinding()]
param(
    # Single-device targeting; without it, every authorized device is pulled.
    [string] $Serial,

    # Override the on-device log directory. Defaults to the app-scoped
    # external files path derived from config.pluginPackage.
    [string] $DevicePath,

    # Keep the raw (un-scrubbed) pull on disk. Off by default: the raw pull
    # is deleted after a successful scrub so the only bundle left is safe.
    [switch] $KeepRaw
)

$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent (Split-Path -Parent $PSCommandPath)
Set-Location $repoRoot

. (Join-Path $repoRoot "scripts/lib/Read-Config.ps1")
$cfg = Read-ScriptConfig -RepoRoot $repoRoot

if (-not (Get-Command adb -ErrorAction SilentlyContinue)) {
    throw "adb not found on PATH. Install Android platform-tools and add them to PATH, then reopen the shell."
}

if (-not $DevicePath) {
    $DevicePath = "/sdcard/Android/data/$($cfg.pluginPackage)/files/xv-logs"
}

# -- Enumerate authorized devices (same rule as install-dev.ps1) --------
$authorized = @(& adb devices 2>$null |
    Select-Object -Skip 1 |
    Where-Object { $_ -match '^(\S+)\s+device\s*$' } |
    ForEach-Object { ($_ -split '\s+')[0] })

if ($Serial) {
    if ($authorized -notcontains $Serial) {
        throw "Requested serial '$Serial' is not authorized. Run `adb devices` and approve the RSA prompt on the phone."
    }
    $targets = @($Serial)
} else {
    $targets = $authorized
}
if (-not $targets -or $targets.Count -eq 0) {
    throw "No authorized adb devices. Plug in a phone, enable USB debugging, and authorize this computer's RSA key."
}

$stamp  = Get-Date -Format 'yyyyMMdd-HHmmss'
$logDir = Join-Path $repoRoot "logs"
New-Item -ItemType Directory -Force -Path $logDir | Out-Null

# -- Redaction ----------------------------------------------------------
# Order matters: redact MACs BEFORE IPv6 — a raw MAC matches the IPv6
# shape, but a redacted "AA:XX:XX:XX:XX:FF" does not (XX isn't hex), so
# masking MACs first keeps them from being double-tagged as IPv6.
$macRe   = '(?i)\b([0-9A-F]{2}):[0-9A-F]{2}:[0-9A-F]{2}:[0-9A-F]{2}:[0-9A-F]{2}:([0-9A-F]{2})\b'
$ipv4Re  = '\b(?:\d{1,3}\.){3}\d{1,3}\b'
$ipv6Re  = '(?i)\b(?:[0-9A-F]{1,4}:){2,7}[0-9A-F]{1,4}\b|(?<![a-zA-Z0-9:])(?:[0-9A-F]{1,4}:)*::(?:[0-9A-F]{1,4}:)*[0-9A-F]{1,4}(?![a-zA-Z0-9:])'
$geoRe   = '-?\d{1,3}\.\d{4,}\s*,\s*-?\d{1,3}\.\d{4,}'
# Operator content patterns (real hostnames / callsigns / unit names).
$opPatterns = @($cfg.contentForbiddenPatterns | Where-Object { $_ -and $_.Trim() -ne "" })

function Invoke-Scrub([string] $Text) {
    if ([string]::IsNullOrEmpty($Text)) { return "" } # empty/rotated-out log
    $t = $Text
    $t = [regex]::Replace($t, $macRe, '${1}:XX:XX:XX:XX:${2}')
    $t = [regex]::Replace($t, $ipv6Re, '[IPv6]')
    $t = [regex]::Replace($t, $ipv4Re, '[IPv4]')
    $t = [regex]::Replace($t, $geoRe, '[GEO]')
    foreach ($p in $opPatterns) {
        $t = [regex]::Replace($t, $p, '[REDACTED]', 'IgnoreCase')
    }
    return $t
}

# Residual-leak gate: patterns that must NOT survive a scrub.
$leakGate = @(
    @{ name = 'raw MAC'; re = '(?i)\b[0-9A-F]{2}(:[0-9A-F]{2}){5}\b' },
    @{ name = 'IPv4';    re = $ipv4Re },
    @{ name = 'IPv6';    re = $ipv6Re }
) + ($opPatterns | ForEach-Object { @{ name = "operator pattern /$_/"; re = $_ } })

$anyLeak = $false

foreach ($t in $targets) {
    Write-Host "== $t ==" -ForegroundColor Cyan
    $rawDir      = Join-Path $logDir "$t-raw-$stamp"
    $scrubbedDir = Join-Path $logDir "$t-scrubbed-$stamp"
    New-Item -ItemType Directory -Force -Path $rawDir, $scrubbedDir | Out-Null

    # adb pull the whole xv-logs directory. Trailing /. copies contents.
    $pullOut = & adb -s $t pull "$DevicePath/." $rawDir 2>&1
    $files = @(Get-ChildItem -Path $rawDir -Recurse -File -ErrorAction SilentlyContinue)
    if ($files.Count -eq 0) {
        Write-Host "  no logs found at $DevicePath (has the plugin run on this device?)" -ForegroundColor Yellow
        Write-Host "  adb: $pullOut" -ForegroundColor DarkGray
        Remove-Item -Recurse -Force $rawDir, $scrubbedDir -ErrorAction SilentlyContinue
        continue
    }

    foreach ($f in $files) {
        $rel     = $f.FullName.Substring($rawDir.Length).TrimStart('\', '/')
        $outPath = Join-Path $scrubbedDir $rel
        New-Item -ItemType Directory -Force -Path (Split-Path -Parent $outPath) | Out-Null
        $scrubbed = Invoke-Scrub (Get-Content -Raw -LiteralPath $f.FullName)
        Set-Content -LiteralPath $outPath -Value $scrubbed -NoNewline

        foreach ($g in $leakGate) {
            if ($scrubbed -match $g.re) {
                Write-Host "  LEAK: '$($g.name)' still present in $rel" -ForegroundColor Red
                $anyLeak = $true
            }
        }
    }

    Write-Host "  pulled $($files.Count) file(s) -> $scrubbedDir" -ForegroundColor Green
    if (-not $KeepRaw) {
        Remove-Item -Recurse -Force $rawDir
    } else {
        Write-Host "  raw (UN-SCRUBBED, do not share) kept at $rawDir" -ForegroundColor Yellow
    }
}

if ($anyLeak) {
    throw "Scrub gate FAILED — residual sensitive content remains. Do NOT share these logs. Extend config.forbiddenPatterns for the offending strings and re-run."
}

Write-Host ""
Write-Host "Scrubbed logs are ready under logs/*-scrubbed-$stamp/." -ForegroundColor Green
Write-Host "They are gitignored — never commit them. Hand the scrubbed bundle to the reviewer." -ForegroundColor Green
