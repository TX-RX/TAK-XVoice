# scripts/

Reusable PowerShell tooling for the TAK-XVoice dev + submission workflow. Everything here is generic across TAK plugins — plugin-specific values live in `scripts/config.json` (gitignored).

These scripts target **PowerShell 7+ (`pwsh`)** — not Windows PowerShell 5.1 (`powershell.exe`). Each carries a `#requires -Version 7.0`, so a 5.1 invocation fails fast with a clear message; some also use 7-only cmdlet features (e.g. `Invoke-WebRequest -Form`/`-SkipCertificateCheck`). Run them as `pwsh ./scripts/<name>.ps1` (or just `./scripts/<name>.ps1` from a `pwsh` prompt). Get it via `winget install Microsoft.PowerShell` if it isn't installed.

## Setup, once per clone

```powershell
Copy-Item scripts/config.example.json scripts/config.json
notepad scripts/config.json
```

Then edit the values to match your plugin. Every key is optional; falls back to the safe generic defaults in `scripts/lib/Read-Config.ps1` if omitted. Keys you'll almost certainly want to override: `pluginPackage`, `pluginShortName`, `productFlavor`, `sanityApkBaseName`, `sourceZipBaseName`, `sourceZipPrefix`, `defaultBaseline`, `tppBaselines`. Add any operator-specific redaction patterns to `forbiddenPatterns` (e.g. your real TAK server hostname, callsign lists).

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

## `lib/Read-Config.ps1`

Shared config loader. Dot-source it (`. (Join-Path $repoRoot "scripts/lib/Read-Config.ps1")`) and call `Read-ScriptConfig -RepoRoot $repoRoot` to get a hashtable. Missing keys fall back to defaults. See the file for the full default list.
