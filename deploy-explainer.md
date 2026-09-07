# How this build + device deploy is being done

_Generated 2026-07-20. Local review doc — not tracked, safe to delete._

## What you asked for

1. Build the latest code on the current branch.
2. Install it on **all** connected devices.
3. Target **ATAK 5.7** (`atakBaselineVersion=5.7.0`).
4. This write-up explaining exactly how.

## The one command doing it

```powershell
# First attempt (in-place update):
pwsh -NoProfile -File ./scripts/install-dev.ps1 -Baseline 5.7.0

# Actual command used, after the first attempt hit a signing-cert mismatch:
pwsh -NoProfile -File ./scripts/install-dev.ps1 -Uninstall -Baseline 5.7.0
```

`scripts/install-dev.ps1` is the project's build-and-fan-out helper. Nothing bespoke was
done by hand — this is the sanctioned dev-loop path, invoked with the 5.7 baseline pinned
explicitly on the command line.

### Why `-Uninstall` was needed

The first run **built the 5.7 APK successfully** but the install was refused on all three
devices with `INSTALL_FAILED_UPDATE_INCOMPATIBLE`: the plugin already on each phone was signed
with a different cert (`sig=4f0df9aa`) than this debug build. Android will not do an in-place
`install -r` across a signing-cert change. `-Uninstall` drops the old package first (which
**wipes the plugin's on-device prefs/data**) and then installs the fresh 5.7 build. You approved
this trade-off before the second run.

## What "latest code" means here

| Field  | Value |
| ------ | ----- |
| Branch | `feat/mcast-a1-derivation-and-config-q51399` |
| Commit | `77f7aef` (`77f7aef5b4958d904a11dd1dedae2cbc70040cec`) |
| Subject | `fix: restore control messages for rendezvous and restrict muting to VX compat` |

The build compiles the **working tree**, not just the committed SHA. There is one uncommitted
change on disk right now — `gradle.properties` flipped `atakBaselineVersion` from `5.6.0` to
`5.7.0` — which is consistent with the 5.7 target and does not affect what gets compiled
(the baseline is also passed on the command line, see below).

## Why 5.7, and how it's enforced

The 5.7 target is pinned in **two** independent places so a debug APK that only loads on 5.7
ATAK is produced:

1. **Command line:** `-Baseline 5.7.0` → the script runs
   `.\gradlew.bat -PatakBaselineVersion=5.7.0 assembleCivDebug`. The `-P` project property is
   what takdev uses to resolve the SDK and stamp the plugin's target ATAK API version.
2. **`scripts/config.json`:** `defaultBaseline` is already `5.7.0`, so even the bare invocation
   would have picked 5.7 — the explicit flag just removes all doubt.

This is a **full build** (no `-SkipBuild`). That matters: a `-SkipBuild` reinstall would reuse
whatever APK was last on disk — which, after a gate run, can be **5.6-stamped and refuse to load
on 5.7 ATAK**. Doing the full `assembleCivDebug` guarantees the APK is freshly stamped for 5.7.

> Note: after this lands you may need to tap **Load** on the plugin once inside ATAK if the
> plugin's declared API version changed — ATAK re-approves a plugin whose apiVersion moved.

## Which devices it installs to

The script calls `adb devices`, keeps only rows whose status is exactly `device` (authorized —
it skips `unauthorized` and `offline`), and installs to **every** one of them. No `-Serial` was
passed, so it fans out to all three currently attached and authorized:

| Model | Product | adb serial (truncated) |
| ----- | ------- | ---------------------- |
| XP9900 | XP9901 | `4236…4b` |
| Pixel 9 Pro | caiman | `5107…ZX` |
| SM-X308U (Galaxy Tab Active5) | gtactive5ue | `R52Y…EV` |

## Step by step, in order

1. **Load config** — reads `scripts/config.json` (`pluginPackage=com.atakmap.android.xv.plugin`,
   `productFlavor=civ`, `defaultBaseline=5.7.0`).
2. **Verify adb** — errors out early if `adb` isn't on PATH.
3. **Enumerate devices** — `adb devices`, authorized-only, all three targeted.
4. **Build** — `.\gradlew.bat -PatakBaselineVersion=5.7.0 assembleCivDebug --console=plain`.
   Fails loudly if the assemble fails.
5. **Locate the APK** — `app/build/outputs/apk/civ/debug/app-civ-debug.apk`; aborts if missing.
6. **Fan-out install** — for each device: print the currently-installed version/signature, then
   `adb -s <serial> install -r <apk>`. On `INSTALL_FAILED_UPDATE_INCOMPATIBLE` it tells you to
   re-run with `-Uninstall` (a signing-cert mismatch, e.g. replacing a TPP-signed install).
7. **Verify** — re-reads `dumpsys package` on each device and prints the installed
   `versionName` / `versionCode` / signature so you can confirm the new build actually landed.
8. **Summary** — a table of `Serial / Status / Version / Sig`, and a non-zero exit if any device
   failed.

## What it deliberately does **not** do

- **No version bump** — `versionCode` / `versionName` are untouched. Dev iterations are tracked by
  `lastUpdateTime` + commit SHA, not by incrementing version fields.
- **No uninstall** — a plain `install -r` (in-place update) preserves plugin prefs/data. It only
  uninstalls if you pass `-Uninstall` (needed after a signing-cert change).
- **No device reboot / no ATAK reinstall** — it only touches the plugin package.

## Result (this run)

Build succeeded (`assembleCivDebug`, baseline 5.7.0), and all three devices installed cleanly
after the uninstall-first pass:

| Serial | Status | Version | Signature |
| ------ | ------ | ------- | --------- |
| `4236…4b` (XP9900) | **OK** | 0.2.0 (vc=20) | `8e77dbaf` |
| `5107…ZX` (Pixel 9 Pro) | **OK** | 0.2.0 (vc=20) | `8e77dbaf` |
| `R52Y…EV` (SM-X308U) | **OK** | 0.2.0 (vc=20) | `8e77dbaf` |

Note the signature changed from the old `4f0df9aa` to `8e77dbaf` (this machine's debug keystore) —
that's the expected effect of the cert-change uninstall.

## What you still need to do on the phones

Because the plugin was **uninstalled and freshly reinstalled** (not updated in place), ATAK treats
it as a new plugin: open ATAK on each device and **tap Load** to approve/enable the XV plugin.
Any plugin prefs/settings from before the reinstall were cleared and may need reconfiguring.
