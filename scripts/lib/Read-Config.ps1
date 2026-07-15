#requires -Version 7.0
# scripts/lib/Read-Config.ps1
#
# Shared config loader for scripts/*.ps1. Dot-source, then call
# Read-ScriptConfig to get a hashtable of merged (config.json ⊕
# defaults) values. Missing keys fall back to the default. Missing
# config file falls back to all-defaults (so first-clone-on-fresh-repo
# just works for the operator who owns the shipped defaults).
#
# Universal forbidden patterns are hardcoded here rather than in
# config so an operator can't accidentally weaken the sensitive-content
# guardrails by editing their local config. Config's forbiddenPatterns
# is *added* to the universal list; there is no path to remove one.

function Read-ScriptConfig {
    param(
        [string] $RepoRoot
    )

    $configPath = Join-Path $RepoRoot "scripts/config.json"
    $userCfg    = @{}
    if (Test-Path $configPath) {
        try {
            $raw = Get-Content -Raw $configPath | ConvertFrom-Json
        } catch {
            throw "scripts/config.json is not valid JSON: $($_.Exception.Message) — fix it or delete it to fall back to defaults (see scripts/config.example.json)."
        }
        # Convert PSCustomObject to hashtable and strip _comment_ keys.
        foreach ($p in $raw.PSObject.Properties) {
            if ($p.Name -notlike "_*_") {
                $userCfg[$p.Name] = $p.Value
            }
        }
    }

    $defaults = @{
        pluginPackage      = "com.atakmap.android.example.plugin"
        pluginShortName    = "example"
        productFlavor      = "civ"
        defaultBaseline    = "5.7.0"
        tppBaselines       = @("5.6.0", "5.7.0")
        sourceZipPrefix    = "plugin"
        sourceZipBaseName  = "plugin-tpp-source"
        sanityApkBaseName  = "ATAK-Plugin-example"
        atakPackage        = "com.atakmap.app.civ"
        atakDevApks        = @{}
        atakReleaseApks    = @{}
        forbiddenPatterns  = @()
        takServer          = $null
        # null => verify-gates.ps1 derives the task list from
        # productFlavor (ktlintCheck + assemble<F>Debug +
        # test<F>DebugUnitTest). Set only to override for a plugin whose
        # gates don't follow that shape.
        verifyGateTasks    = $null
    }

    # Universal patterns — always applied on top of whatever config
    # adds. Anything a TAK plugin operator would categorically want to
    # keep out of a TPP submission zip.
    $universalForbidden = @(
        '\.audio/',
        '/logs/',
        '\.logs/',
        '\.tmp/',
        'diagnostics/',
        '/build/',
        'keystore\.properties',
        'credentials',
        '\.env$', '\.env\.'
    )

    $merged = @{}
    foreach ($k in $defaults.Keys) {
        if ($userCfg.ContainsKey($k)) {
            $merged[$k] = $userCfg[$k]
        } else {
            $merged[$k] = $defaults[$k]
        }
    }

    # Special-case forbiddenPatterns: union of config + universal.
    #
    # Two consumers, two scopes:
    #   forbiddenPatterns        — universal ∪ operator. Matched against
    #                              zip *entry paths* (filename leaks like
    #                              a stray logcat). The universal list is
    #                              path-shaped ('/logs/', '.env', ...) and
    #                              would false-positive against source
    #                              text, so it is path-scan only.
    #   contentForbiddenPatterns — operator patterns only. Matched against
    #                              text-file *content* inside the zip
    #                              (a real hostname / callsign embedded in
    #                              a tracked comment or fixture). Universal
    #                              path shapes are deliberately excluded
    #                              here — a source file legitimately
    #                              mentioning "credentials" is not a leak.
    $userForbidden = @()
    if ($userCfg.ContainsKey("forbiddenPatterns")) {
        # Drop null / blank entries. A "" left in the list joins into the
        # scan regex as an empty alternative ('a||b'), and -match "" is
        # always true — every zip entry would report as tainted, a false
        # alarm that's maddening to trace back to one stray comma.
        $userForbidden = @($userCfg["forbiddenPatterns"] |
            Where-Object { $_ -is [string] -and $_.Trim() -ne "" })
    }
    $merged.forbiddenPatterns        = @($universalForbidden + $userForbidden | Select-Object -Unique)
    $merged.contentForbiddenPatterns = @($userForbidden | Select-Object -Unique)

    # atakDevApks / atakReleaseApks: normalize from PSCustomObject to
    # hashtable + expand env vars like %USERPROFILE% in each value so
    # config.json can stay generic. Missing baselines just return $null.
    foreach ($k in @("atakDevApks", "atakReleaseApks")) {
        $expanded = @{}
        $raw = $merged[$k]
        if ($raw -is [PSCustomObject]) {
            foreach ($p in $raw.PSObject.Properties) {
                if ($null -ne $p.Value) {
                    $expanded[$p.Name] = [Environment]::ExpandEnvironmentVariables($p.Value)
                } else {
                    $expanded[$p.Name] = $null
                }
            }
        } elseif ($raw -is [hashtable]) {
            foreach ($kk in $raw.Keys) {
                if ($null -ne $raw[$kk]) {
                    $expanded[$kk] = [Environment]::ExpandEnvironmentVariables($raw[$kk])
                } else {
                    $expanded[$kk] = $null
                }
            }
        }
        $merged[$k] = $expanded
    }

    return $merged
}
