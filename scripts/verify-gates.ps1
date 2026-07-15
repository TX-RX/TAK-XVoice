#requires -Version 7.0
<#
.SYNOPSIS
    Runs the pre-merge Gradle gates and fails if any is red.

.DESCRIPTION
    This is the local stand-in for a CI build. Public GitHub CI cannot
    compile this project: the Android build needs the ATAK CIV SDK
    (app/libs/main.jar) on the compile classpath, the SDK is not
    redistributable, and the tak.gov maven coordinates the takdev
    plugin would fetch from require USG-sponsored credentials. See the
    header of .github/workflows/ci.yml for the full rationale.

    The consequence: ktlint is the ONLY gate GitHub can enforce, and
    ktlint merely parses Kotlin — it never resolves symbols. A broken
    symbol reference is therefore invisible to CI by construction.
    That is not hypothetical. PR #72 (e12dc3b) landed a test source set
    that did not compile ('Unresolved reference: PlaceDecision'), CI was
    green, and main stayed red through #80 and #81 until it was caught
    by hand.

    Note that `assemble<Flavor>Debug` alone would NOT have caught it
    either — assembling the APK does not compile the unit-test source
    set. `test<Flavor>DebugUnitTest` is the gate that actually resolves
    test symbols, which is why it is not optional here.

    Wired to a pre-push hook via .pre-commit-config.yaml, so a red tree
    cannot reach the remote in the first place.

.PARAMETER Task
    Override the gradle task list. Default is derived from the
    productFlavor in scripts/config.json (see below), or taken verbatim
    from the config's verifyGateTasks if that key is set.

.PARAMETER SkipSdkHint
    Suppress the ATAK-SDK troubleshooting hint on failure.

.EXAMPLE
    pwsh -File scripts/verify-gates.ps1
    Runs ktlintCheck + assembleCivDebug + testCivDebugUnitTest.

.EXAMPLE
    pwsh -File scripts/verify-gates.ps1 -Task testCivDebugUnitTest
    Runs just the unit-test gate.
#>
[CmdletBinding()]
param(
    [string[]] $Task,
    [switch]   $SkipSdkHint
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
. (Join-Path $PSScriptRoot 'lib/Read-Config.ps1')
$cfg = Read-ScriptConfig -RepoRoot $repoRoot

# Task list resolution, most-specific first:
#   -Task param  >  config verifyGateTasks  >  derived from productFlavor
#
# Deriving from productFlavor keeps this script generic across TAK
# plugins (CLAUDE.md: nothing plugin-specific hardcoded in scripts/*.ps1).
# 'civ' -> 'Civ' gives assembleCivDebug / testCivDebugUnitTest; a plugin
# built with productFlavor 'mil' gets assembleMilDebug for free.
if ($Task -and $Task.Count -gt 0) {
    $tasks = $Task
} elseif ($cfg.ContainsKey('verifyGateTasks') -and $cfg.verifyGateTasks) {
    $tasks = @($cfg.verifyGateTasks)
} else {
    $flavor = $cfg.productFlavor
    if ([string]::IsNullOrWhiteSpace($flavor)) { $flavor = 'civ' }
    $F = $flavor.Substring(0, 1).ToUpperInvariant() + $flavor.Substring(1)
    $tasks = @(
        'ktlintCheck'
        "assemble${F}Debug"
        "test${F}DebugUnitTest"
    )
}

# Windows needs the .bat wrapper; the extensionless script is POSIX-only.
$gradlew = if ($IsWindows) {
    Join-Path $repoRoot 'gradlew.bat'
} else {
    Join-Path $repoRoot 'gradlew'
}
if (-not (Test-Path $gradlew)) {
    throw "No Gradle wrapper at $gradlew — run from a full clone."
}

Write-Host ''
Write-Host "  Gradle gates: $($tasks -join ' ')" -ForegroundColor Cyan
Write-Host "  (local build gate — GitHub cannot compile this project; see script header)" -ForegroundColor DarkGray
Write-Host ''

$sw = [System.Diagnostics.Stopwatch]::StartNew()

# One invocation for all tasks: a single daemon + one configuration
# pass is markedly faster than N invocations, and this sits in the push
# path where latency is felt directly. Gradle names the failing task in
# its own output, so per-task granularity buys nothing here.
Push-Location $repoRoot
try {
    # Do NOT assign this pipeline to a variable. `$x = ... | Tee-Object`
    # captures the stream into $x instead of passing it to the host, so
    # gradle's output — including the actual compile error — is
    # swallowed and the operator sees a bare "FAIL" with no cause.
    # Tee-Object -Variable both prints and captures; keep it that way.
    & $gradlew @tasks 2>&1 | Tee-Object -Variable teed
    $exit = $LASTEXITCODE
} finally {
    Pop-Location
}

$sw.Stop()
$elapsed = '{0:mm}m{0:ss}s' -f ([timespan]::FromSeconds($sw.Elapsed.TotalSeconds))

Write-Host ''
if ($exit -eq 0) {
    Write-Host "  PASS  all gates green ($elapsed)" -ForegroundColor Green
    Write-Host ''
    exit 0
}

Write-Host "  FAIL  gradle exited $exit after $elapsed" -ForegroundColor Red
Write-Host "        Gates: $($tasks -join ' ')" -ForegroundColor Red

# The single most common failure on a fresh clone is a missing SDK jar.
# CLAUDE.md is explicit that the fix is to install the SDK, never to
# vendor a copy of main.jar into app/libs/ — so say so here, at the
# moment someone is staring at the error and looking for a shortcut.
if (-not $SkipSdkHint) {
    $text = ($teed | Out-String)

    # Match only genuine *resolution* failures. An earlier version of
    # this heuristic matched bare 'main.jar|takdev' against the whole
    # log — but those strings appear in ordinary gradle output on every
    # run, so the hint fired on every failure, including plain
    # compile errors. Telling someone "this is not a code defect" when
    # it is one is worse than saying nothing: it sends them off
    # reinstalling an SDK that was never broken.
    $missingSdk = (
        $text -match 'main\.jar.{0,80}(does not exist|no such file|not found|cannot be found)' -or
        $text -match 'Could not find .{0,40}atak' -or
        $text -match 'atak-gradle-takdev.{0,80}(does not exist|not found)' -or
        $text -match 'SDK location not found'
    )

    # A Kotlin/Java diagnostic means the toolchain resolved fine and the
    # source is at fault — never blame the SDK in that case.
    $compileError = (
        $text -match '(?m)^e: file:' -or
        $text -match 'Compilation error' -or
        $text -match 'Unresolved reference'
    )

    if ($missingSdk -and -not $compileError) {
        Write-Host ''
        Write-Host '        This looks like a missing ATAK CIV SDK, not a code defect.' -ForegroundColor Yellow
        Write-Host '        Install the SDK distribution and point the build at it per' -ForegroundColor Yellow
        Write-Host '        build.gradle.kts (default: ../ATAK-CIV-<ver>-SDK/ next to this repo).' -ForegroundColor Yellow
        Write-Host '        Do NOT commit a copy of main.jar into app/libs/ — it is gitignored' -ForegroundColor Yellow
        Write-Host '        and non-redistributable. See CLAUDE.md "ATAK SDK jar".' -ForegroundColor Yellow
    }
}

Write-Host ''
Write-Host '        To push anyway (needs an explicit operator decision):' -ForegroundColor DarkGray
Write-Host '            git push --no-verify' -ForegroundColor DarkGray
Write-Host ''
exit 1
