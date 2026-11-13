# scripts/

Reusable PowerShell tooling for the TAK-XVoice dev + submission workflow. Everything here is generic across TAK plugins — plugin-specific values live in `scripts/config.json` (gitignored).

These scripts target **PowerShell 7+ (`pwsh`)** — not Windows PowerShell 5.1 (`powershell.exe`). Each carries a `#requires -Version 7.0`, so a 5.1 invocation fails fast with a clear message; some also use 7-only cmdlet features (e.g. `Invoke-WebRequest -Form`/`-SkipCertificateCheck`). Run them as `pwsh ./scripts/<name>.ps1` (or just `./scripts/<name>.ps1` from a `pwsh` prompt). Get it via `winget install Microsoft.PowerShell` if it isn't installed.

## Setup, once per clone

```powershell
Copy-Item scripts/config.example.json scripts/config.json
notepad scripts/config.json
```

Then edit the values to match your plugin. Every key is optional; falls back to the safe generic defaults in `scripts/lib/Read-Config.ps1` if omitted. Keys you'll almost certainly want to override: `pluginPackage`, `pluginShortName`, `productFlavor`, `sanityApkBaseName`, `sourceZipBaseName`, `sourceZipPrefix`, `defaultBaseline`, `tppBaselines`. Add any operator-specific redaction patterns to `forbiddenPatterns` (e.g. your real TAK server hostname, callsign lists).

Then install the git hooks (see `verify-gates.ps1` below — this is what keeps a broken build off `main`):

```powershell
pipx install pre-commit                                          # or: python -m pip install --user pre-commit
pre-commit install --hook-type pre-commit --hook-type pre-push
Get-ChildItem .git/hooks -File | Where-Object Name -notlike '*.sample'   # expect: pre-commit, pre-push
```

That last line is not ceremony — verify it. If `core.hooksPath` is set in your clone, `pre-commit install` **refuses and exits non-zero**, and it is easy to miss in scrollback. The result is a clone where `.pre-commit-config.yaml` looks authoritative but no hook has ever run. Clear it with `git config --unset-all core.hooksPath` (check `git config --get core.hooksPath` first — if it points somewhere other than `.git/hooks`, something else owns your hooks and you should find out what before unsetting).

## `verify-gates.ps1` — run the pre-merge build gates

```powershell
.\scripts\verify-gates.ps1                       # ktlintCheck + assembleCivDebug + testCivDebugUnitTest
.\scripts\verify-gates.ps1 -Task testCivDebugUnitTest   # one gate
```

Runs as a **pre-push hook**, so a red tree can't reach the remote. This is the project's only real build gate, and it exists because public CI structurally cannot provide one:

- GitHub runners have no ATAK CIV SDK. The SDK isn't redistributable and the tak.gov maven coordinates need USG-sponsored credentials, so CI cannot compile the main source set — let alone run tests. (`.github/workflows/ci.yml` documents this at length.)
- What CI *can* run is ktlint, which **parses without resolving symbols**. An unresolved reference is invisible to it.
- So an entire class of break reaches `main` with CI fully green. PR #72 (e12dc3b) did exactly that: it landed a test source set that didn't compile, and `main` stayed red through #80 and #81 until someone ran the tests by hand.

Two things worth internalizing:

- **`assembleCivDebug` is not a substitute for the test gate.** Assembling the APK does not compile the unit-test source set, so it cannot catch a broken test symbol. #72 built clean APKs the whole time it was red. Don't trim the test task to speed up pushes.
- **The gate builds the working tree, not the commits being pushed.** Normally identical (you push what you built), but with a dirty tree it verifies what's on disk rather than what's going out. Push from a clean tree.

The task list is derived from `config.productFlavor` (`civ` → `assembleCivDebug`), so the script stays generic across TAK plugins. Override with `config.verifyGateTasks` only if your gates don't follow that shape. `git push --no-verify` bypasses it — an explicit operator decision, per CLAUDE.md.

## `install-dev.ps1` — build the debug APK and install on connected phones

Fans out to every authorized adb device so multi-device flashing takes one command.

```powershell
.\scripts\install-dev.ps1                        # build + install plugin on every attached device
.\scripts\install-dev.ps1 -Baseline 5.6.0
.\scripts\install-dev.ps1 -Serial <ID>           # single device
.\scripts\install-dev.ps1 -Uninstall             # signing cert change (e.g. after replacing a TPP-signed install)
.\scripts\install-dev.ps1 -SkipBuild             # reinstall the last-built APK

.\scripts\install-dev.ps1 -InstallAtakDev        # install ATAK Developer edition from config.atakDevApkPath
.\scripts\install-dev.ps1 -InstallAtakRelease    # install production/release ATAK from config.atakReleaseApkPath
```

The two `-InstallAtak*` switches are terminal — they install ATAK and exit without touching your plugin. Chain another invocation (no switches) to install the plugin afterward. Both variants uninstall any existing ATAK first because signing certs almost always differ between editions.

`-InstallAtakDev` also flags a warning if the installed ATAK does NOT have the `DEBUGGABLE` flag set, which would mean the configured path doesn't actually point at the Developer edition.

## `package-tpp.ps1` — build a TAK Product Portal submission bundle

Produces the artifacts TPP consumes: source zips (the actual submission) plus sanity APKs (for local verification only).

```powershell
.\scripts\package-tpp.ps1                        # full bundle (source zips + sanity APKs)
.\scripts\package-tpp.ps1 -SkipApkBuild          # source zips only (fast; skips ~1min of Gradle)
.\scripts\package-tpp.ps1 -Baselines 5.6.0, 5.7.0
.\scripts\package-tpp.ps1 -AllowDirtyTree        # override the clean-tree requirement
```

The load-bearing safety guarantee: source zips are built with `git archive HEAD`, which includes only tracked files. Anything gitignored (`logs/`, `.audio/`, `.logs/`, `.tmp/`, `diagnostics/`, `build/`, `.gradle/`, `keystore.properties`, `credentials`, `.env*`) is automatically excluded. Never use `zip -r`, `Compress-Archive`, or a GUI right-click — those tools ignore `.gitignore` and can leak field captures into a public submission (this happened, 2026-07-11).

Every produced zip is then scanned in two passes and any hit refuses to declare the bundle ready:

- **Path pass** — universal built-ins + your `config.forbiddenPatterns`, matched against every entry's *filename*. Catches leaked files (a stray logcat, an `.env`) by name.
- **Content pass** — your `config.forbiddenPatterns` only, matched against the *text* of each tracked text file. Catches a real hostname / callsign / unit name embedded in a committed comment or fixture that the path pass would never see. Universal path shapes (`/logs/`, `credentials`, `.env`) are deliberately excluded here so legitimate source that merely mentions those words doesn't false-positive. Binary entries and files over 4 MB are path-scanned only.

Config can add patterns, never remove them. Put anything operator-specific — your real TAK server hostname, callsign lists, unit names — in `config.forbiddenPatterns` so the content pass can catch it.

Output goes to `build/tpp/`. `build/` is gitignored, so nothing accidentally lands in a commit — but it also gets wiped by `./gradlew clean`. If a submission spans days, copy the zips somewhere outside `build/` before running anything that could clean.

## `verify-play-aab.ps1` — check an AAB before it goes to Google Play

```powershell
.\scripts\verify-play-aab.ps1 <app-civ-release.aab> -RequireUnsigned   # raw TPP output, before attaching it to a release
.\scripts\verify-play-aab.ps1 <upload-signed.aab>                       # after the upload key has been applied
```

A plugin installed from Google Play is signed twice, and ATAK trusts only one of the two:

1. **TPP signs the bundle.** The TAK third-party plugin pipeline embeds `BUNDLE-METADATA/com.android.tools.build.bundletool/code_transparency_signed.jwt` in the AAB: an RS256 JWT, signed by a TAK Product Center *Bundle Release* key, listing the SHA-256 of every dex / native lib.
2. **Play signs the APKs.** Play App Signing replaces the APK signer with the app-signing key and copies the JWT into `base.apk`.

ATAK can't match (2) against its plugin allowlist, so it falls back to (1) — `PluginValidator.checkAppTransparencySignature` verifies the JWT against `AtakPluginRegistry.ACCEPTABLE_KEY_LIST` and re-hashes the code. A locally built bundle (`bundleCivRelease`) has no JWT. Play accepts and installs it without complaint; ATAK then shows **"The signature for the plugin is INVALID"**. That is how 1.0.1 and 1.0.2 reached the internal track unloadable. The upload-key signature is stripped by Play and is irrelevant to ATAK.

The script mirrors ATAK's check and exits 1 unless all of these hold:

- the dex does not reference `gov.tak.api.plugin.IServiceController` unobfuscated — the fingerprint of a build made without ATAK's release ProGuard mapping, which release ATAK can't load even when signed correctly;
- the JWT is present, `alg` is RS256, and its signing cert is an ATAK-allowlisted TAK Product Center bundle key (pinned by SHA-256; `config.trustedTransparencyCerts` can add to the list, never remove);
- the signature over `header.payload` verifies;
- every listed dex / `.so` is present with a matching hash, and no code file in the bundle is unlisted (i.e. nothing changed after TPP signed it);
- with `-RequireUnsigned`, the AAB carries no JAR signature — raw TPP output is unsigned.

Where it runs: `review-tpp-result.ps1` checks every AAB in a TPP result bundle (and exits 1 on failure), and the **Play deploy** workflow runs it as a hard gate before the upload key touches the bundle. Keep Play Console's **automatic protection** off for the plugin — Google documents it as incompatible with code transparency, and it rewrites the dex the JWT hashes.

## `pull-field-logs.ps1` — pull on-device diagnostic logs and scrub them

Pulls the plugin's on-device logs (`DiagnosticLogger` writes them to the
app-scoped `.../files/xv-logs/xv-YYYY-MM-DD.log`, `adb pull`-able without
root) off every authorized device, then writes a **scrubbed** copy safe to
hand to a reviewer.

```powershell
.\scripts\pull-field-logs.ps1                 # every authorized device
.\scripts\pull-field-logs.ps1 -Serial <ID>    # single device
.\scripts\pull-field-logs.ps1 -KeepRaw        # keep the un-scrubbed pull too
```

Both the raw pull and the scrubbed bundle land under the gitignored
`logs/` directory — **never commit either** (CLAUDE.md; no `git add -f`).
Scrubbing redacts Bluetooth MACs (first/last octet kept, mirroring
`MacRedact.kt`), IPv4/IPv6 addresses, GPS lat,lon pairs, and your
operator-specific strings from `config.forbiddenPatterns` (real TAK
hostnames, callsigns, unit names). A final gate re-scans the scrubbed
output and **fails with a non-zero exit** if any raw MAC, IPv4, or operator
pattern survived — so a partial scrub can't be handed off. By default the
raw pull is deleted after a clean scrub; `-KeepRaw` retains it (marked
un-shareable). It over-redacts by design: 4-part version quads (an ATAK
`5.7.0.10`) are masked as `[IPv4]` — the commit SHA in the log identifies
the build.

## `lib/Read-Config.ps1`

Shared config loader. Dot-source it (`. (Join-Path $repoRoot "scripts/lib/Read-Config.ps1")`) and call `Read-ScriptConfig -RepoRoot $repoRoot` to get a hashtable. Missing keys fall back to defaults. See the file for the full default list.
