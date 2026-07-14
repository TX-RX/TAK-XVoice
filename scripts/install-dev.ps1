#!/usr/bin/env pwsh
# scripts/install-dev.ps1
#
# Build the current branch's debug APK and install it on every
# connected + authorized Android device. Also supports installing ATAK
# itself (Developer or Release edition) for phone-prep workflows.
#
# Configuration (all keys optional) lives in scripts/config.json —
# copy scripts/config.example.json and edit. See scripts/lib/Read-Config.ps1
# for the fallback defaults if no config is present.
#
# Prerequisites:
#   - At least one adb device with USB debugging authorized. The script
#     installs to EVERY authorized device it sees so you can flash
#     several phones in one shot. Use -Serial to target one.
#   - For the plugin install path: ATAK-CIV Developer edition already
#     on the phone. The debug plugin APK only loads on the Developer
#     edition (DEBUGGABLE flag set). If not, run -InstallAtakDev first.
#
# Common invocations:
#   .\scripts\install-dev.ps1                   # build + install plugin on every attached device
#   .\scripts\install-dev.ps1 -Baseline 5.6.0
#   .\scripts\install-dev.ps1 -Serial <ID>      # single device
#   .\scripts\install-dev.ps1 -Uninstall        # signing cert change (e.g. after TPP-signed install)
#   .\scripts\install-dev.ps1 -SkipBuild        # reinstall the last-built APK
#   .\scripts\install-dev.ps1 -InstallAtakDev                 # install Dev ATAK for defaultBaseline
#   .\scripts\install-dev.ps1 -InstallAtakDev -Baseline 5.6.0 # install the 5.6 Dev ATAK
#   .\scripts\install-dev.ps1 -InstallAtakRelease -Baseline 5.7.0
#
# Notes:
#   - versionCode / versionName are NEVER bumped by this script — dev
#     iteration is tracked by lastUpdateTime + commit SHA, not by
#     incrementing version fields.
#   - -InstallAtakDev and -InstallAtakRelease are terminal: they install
#     ATAK and exit without touching the plugin. Chain another invocation
#     without them to install the plugin afterward.
#   - Per-baseline ATAK APK paths live in config.atakDevApks /
#     config.atakReleaseApks (hash keyed by baseline). -Baseline picks
#     which entry to use, so multi-SDK setups (5.6 + 5.7 in parallel)
#     work with a single script invocation each.

[CmdletBinding()]
param(
    # ATAK baseline to build against. Defaults to config.defaultBaseline.
    [string] $Baseline,

    # Uninstall the plugin first. Required when the signing cert has
    # changed (e.g. moving from a TPP-signed release install to a
    # locally dev-signed debug install). Loses plugin prefs / data.
    [switch] $Uninstall,

    # Skip the Gradle build and reinstall the last-built APK.
    [switch] $SkipBuild,

    # Optional single-device targeting. Without this, the script fans
    # out to every authorized device. Pass the serial from `adb devices`.
    [string] $Serial,

    # Install the ATAK-CIV Developer edition APK (from config.atakDevApks,
    # keyed by -Baseline) on the target devices instead of the plugin.
    # Uninstalls any existing ATAK first because the signing cert almost
    # always differs.
    [switch] $InstallAtakDev,

    # Install a production/release ATAK-CIV APK (from config.atakReleaseApks,
    # keyed by -Baseline) instead of the plugin. Same uninstall-first behavior.
    [switch] $InstallAtakRelease
)

$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent (Split-Path -Parent $PSCommandPath)
Set-Location $repoRoot

. (Join-Path $repoRoot "scripts/lib/Read-Config.ps1")
$cfg = Read-ScriptConfig -RepoRoot $repoRoot

if (-not $Baseline) { $Baseline = $cfg.defaultBaseline }

if ($InstallAtakDev -and $InstallAtakRelease) {
    throw "-InstallAtakDev and -InstallAtakRelease are mutually exclusive."
}

if (-not (Get-Command adb -ErrorAction SilentlyContinue)) {
    throw "adb not found on PATH. Install Android platform-tools and add them to PATH (e.g. %USERPROFILE%\Android\Sdk\platform-tools), then reopen the shell."
}

# -- 1. Enumerate authorized devices -----------------------------------

# `adb devices` returns one row per attached device with a status:
# "device" = authorized, "unauthorized" = accepted USB but RSA not
# yet approved, "offline" = flaky USB. We only want authorized.
$authorized = @(& adb devices 2>$null |
    Select-Object -Skip 1 |
    Where-Object { $_ -match '^(\S+)\s+device\s*$' } |
    ForEach-Object { ($_ -split '\s+')[0] })

if ($Serial) {
    if ($authorized -notcontains $Serial) {
        Write-Host "Authorized devices seen by adb:" -ForegroundColor Yellow
        $authorized | ForEach-Object { Write-Host "  $_" -ForegroundColor Yellow }
        throw "Requested serial '$Serial' is not authorized. Check the phone screen for a USB-debug authorization prompt."
    }
    $targets = @($Serial)
} else {
    $targets = $authorized
}

if (-not $targets -or $targets.Count -eq 0) {
    throw "No authorized adb devices. Plug in a phone, enable USB debugging, and authorize this computer's RSA key."
}

Write-Host "Targeting $($targets.Count) device(s): $($targets -join ', ')" -ForegroundColor Cyan

# -- 2. ATAK-install path (terminal — no plugin work after) -------------

if ($InstallAtakDev -or $InstallAtakRelease) {
    $bucket  = if ($InstallAtakDev) { $cfg.atakDevApks } else { $cfg.atakReleaseApks }
    $label   = if ($InstallAtakDev) { "ATAK Developer edition" } else { "ATAK Release/production" }
    $key     = if ($InstallAtakDev) { "atakDevApks" } else { "atakReleaseApks" }

    if (-not $bucket -or $bucket.Count -eq 0) {
        throw "No paths configured under $key in scripts/config.json. Add at least one baseline entry, e.g. { `"$Baseline`": `"path/to/atak.apk`" }."
    }
    $apkPath = $bucket[$Baseline]
    if (-not $apkPath) {
        $available = ($bucket.Keys | Sort-Object) -join ", "
        throw "No $label APK configured for baseline '$Baseline' under $key in scripts/config.json. Available: [$available]. Add an entry for '$Baseline' or pass a different -Baseline."
    }
    if (-not (Test-Path $apkPath)) {
        throw "$label APK for baseline '$Baseline' not found at: $apkPath"
    }
    Write-Host "`nInstalling $label for baseline $Baseline from $apkPath" -ForegroundColor Cyan

    $results = @()
    foreach ($t in $targets) {
        Write-Host "`n=== $t ===" -ForegroundColor Cyan

        # Uninstall existing ATAK — variants have different signing
        # certs so a plain `install -r` typically fails.
        $existing = & adb -s $t shell pm list packages $cfg.atakPackage 2>$null
        if ($existing) {
            Write-Host "  Uninstalling existing $($cfg.atakPackage)..." -ForegroundColor DarkGray
            & adb -s $t uninstall $cfg.atakPackage | Out-Null
        }

        Write-Host "  Installing..." -ForegroundColor Cyan
        $installOut = & adb -s $t install -r $apkPath 2>&1
        if ($LASTEXITCODE -ne 0) {
            Write-Host "  FAILED:" -ForegroundColor Red
            $installOut | ForEach-Object { Write-Host "    $_" -ForegroundColor Red }
            $results += [PSCustomObject]@{ Serial = $t; Status = "FAILED" }
            continue
        }

        # Report installed state. Look for DEBUGGABLE flag to confirm
        # it's actually the dev edition when that was requested.
        $after = & adb -s $t shell dumpsys package $cfg.atakPackage 2>$null
        $ver   = ($after | Select-String -Pattern 'versionName=(\S+)').Matches.Groups[1].Value
        $sig   = ($after | Select-String -Pattern 'signatures:\[([0-9a-f]+)\]').Matches.Groups[1].Value
        $flags = ($after | Select-String -Pattern 'flags=\[([^\]]+)\]').Matches.Groups[1].Value
        $isDbg = $flags -match "DEBUGGABLE"

        Write-Host "  Installed: $ver  sig=$sig  DEBUGGABLE=$isDbg" -ForegroundColor Green
        if ($InstallAtakDev -and -not $isDbg) {
            Write-Host "  WARNING: -InstallAtakDev requested but DEBUGGABLE flag is NOT set. This APK is not the Developer edition." -ForegroundColor Yellow
        }
        if ($InstallAtakRelease -and $isDbg) {
            Write-Host "  WARNING: -InstallAtakRelease requested but DEBUGGABLE flag IS set. This APK looks like the Developer edition, not Release." -ForegroundColor Yellow
        }
        $results += [PSCustomObject]@{ Serial = $t; Status = "OK"; Version = $ver; Debuggable = $isDbg }
    }

    Write-Host "`n=== Summary ===" -ForegroundColor Cyan
    $results | Format-Table -AutoSize
    $failed = @($results | Where-Object { $_.Status -ne "OK" })
    if ($failed.Count -gt 0) { exit 1 }
    return
}

# -- 3. Plugin install path ---------------------------------------------

$branch = (& git rev-parse --abbrev-ref HEAD).Trim()
$sha    = (& git rev-parse --short HEAD).Trim()
Write-Host "Building from $branch @ $sha (baseline=$Baseline)" -ForegroundColor Cyan

if (-not $SkipBuild) {
    Write-Host "`nGradle assemble$([char]::ToUpper($cfg.productFlavor[0]) + $cfg.productFlavor.Substring(1))Debug..." -ForegroundColor Cyan
    $flavorCap = [char]::ToUpper($cfg.productFlavor[0]) + $cfg.productFlavor.Substring(1)
    & ".\gradlew.bat" "-PatakBaselineVersion=$Baseline" "assemble${flavorCap}Debug" --console=plain
    if ($LASTEXITCODE -ne 0) { throw "assemble${flavorCap}Debug failed" }
}

$apk = "app/build/outputs/apk/$($cfg.productFlavor)/debug/app-$($cfg.productFlavor)-debug.apk"
if (-not (Test-Path $apk)) {
    throw "APK not found at $apk — build must produce it before install can run."
}

# -- 4. Fan out to every target device --------------------------------

$results = @()
foreach ($t in $targets) {
    Write-Host "`n=== $t ===" -ForegroundColor Cyan

    $existing = & adb -s $t shell dumpsys package $cfg.pluginPackage 2>$null
    if ($existing) {
        $exVer  = ($existing | Select-String -Pattern 'versionName=(\S+)').Matches.Groups[1].Value
        $exSig  = ($existing | Select-String -Pattern 'signatures:\[([0-9a-f]+)\]').Matches.Groups[1].Value
        Write-Host "  Currently installed: $exVer sig=$exSig" -ForegroundColor DarkGray
    } else {
        Write-Host "  Plugin not yet installed on this device." -ForegroundColor DarkGray
    }

    if ($Uninstall -and $existing) {
        Write-Host "  Uninstalling old plugin (signing cert change expected)..." -ForegroundColor DarkGray
        & adb -s $t uninstall $cfg.pluginPackage | Out-Null
    }

    Write-Host "  Installing $apk..." -ForegroundColor Cyan
    $installOut = & adb -s $t install -r $apk 2>&1
    if ($LASTEXITCODE -ne 0) {
        Write-Host "  FAILED:" -ForegroundColor Red
        $installOut | ForEach-Object { Write-Host "    $_" -ForegroundColor Red }
        if ($installOut -match "INSTALL_FAILED_UPDATE_INCOMPATIBLE") {
            Write-Host "  Signing cert mismatch — re-run with -Uninstall to drop the existing cert first." -ForegroundColor Yellow
        }
        $results += [PSCustomObject]@{ Serial = $t; Status = "FAILED"; Version = ""; Sig = "" }
        continue
    }

    $after = & adb -s $t shell dumpsys package $cfg.pluginPackage 2>$null
    if ($after) {
        $ver  = ($after | Select-String -Pattern 'versionName=(\S+)').Matches.Groups[1].Value
        $vc   = ($after | Select-String -Pattern 'versionCode=(\d+)').Matches.Groups[1].Value
        $sigs = ($after | Select-String -Pattern 'signatures:\[([0-9a-f]+)\]').Matches.Groups[1].Value
        Write-Host "  Installed: $ver (vc=$vc) sig=$sigs" -ForegroundColor Green
        $results += [PSCustomObject]@{ Serial = $t; Status = "OK"; Version = $ver; Sig = $sigs }
    } else {
        Write-Host "  Installed but dumpsys can't find the plugin — unusual." -ForegroundColor Yellow
        $results += [PSCustomObject]@{ Serial = $t; Status = "UNVERIFIED"; Version = ""; Sig = "" }
    }
}

# -- 5. Summary --------------------------------------------------------

Write-Host "`n=== Summary ===" -ForegroundColor Cyan
$results | Format-Table -AutoSize
Write-Host "Source: $branch @ $sha (baseline $Baseline)" -ForegroundColor Cyan

$failed = @($results | Where-Object { $_.Status -ne "OK" })
if ($failed.Count -gt 0) { exit 1 }
