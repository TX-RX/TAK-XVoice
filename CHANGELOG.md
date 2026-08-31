# Changelog

All notable user-visible and operationally significant changes to TAK-XVoice are documented in this file.

## [Unreleased]

### Removed
- Version bumped to 0.3.0 (`versionCode` 21). Minor rather than patch:
  this drops a previously-supported device and makes a breaking AIDL
  schema change, both of which are user-visible.
- Dropped Sonim XP10 (XP9900) support entirely: the four device-specific
  button readers (PTT and Emergency, in both the service-process
  broadcast and ATAK-process foreground-KeyEvent forms), the
  assigned-app receiver, the `Build.BRAND` / `Build.MODEL` device gate,
  the settings row, and the six Sonim methods on `IXvVoice.aidl`.
- `AIDL_API_VERSION` 4 → 5. This is a **breaking** schema change, not an
  additive one — the removed methods sat mid-interface, so every method
  after them shifts Binder transaction ID. `XvVoiceClient`'s
  `EXPECTED_API_VERSION` was realigned to match; it had drifted to 1
  while the service was at 4, so the mismatch warning had been firing on
  every bind and meant nothing.

### Why the Sonim XP10 was retired
- **It did not meet the project's stability bar.** The handset showed
  persistent instability across testing on a clean OS install, not
  traceable to XV's own state machines.
- **It was problematic under performance and endurance testing.**
  Run-to-run behavior was inconsistent enough that the device was
  useless as a reference target — a failure could not be trusted to
  indicate an XV regression, nor a pass to indicate a fix.
- **Bluetooth was the disqualifying problem.** Serious, reproducible
  Bluetooth issues. XV's speakermic and BLE-button integration depends
  on dependable SCO setup/teardown, bonded-device reconnect, and HID/SPP
  event delivery, so a handset with an unreliable Bluetooth stack is a
  poor candidate for custom button integration.
- **End-of-life with no security update path**, which is inconsistent
  with the project's security posture.
- Full rationale and operator-facing impact:
  [docs/hardware/sonim-xp10.md](docs/hardware/sonim-xp10.md).

### Changed
- Hardened TX startup/restart behavior in the audio pipeline:
  - Suppressed false-positive capture restart on initial route-settle (`routedDevice` unresolved to first stable device id).
  - Added restart callback flow from capture to TX controller so mid-burst in-place restarts can re-arm stabilization.
  - Re-applied start-of-stream mitigation after in-place restart (short hold + leading frame drop), and reset TX frame numbering on restart.
- Centralized cold-start mitigation tuning in `ColdStartMitigationPolicy` and routed companion helpers through the policy.

### Why
- Field testing showed a startup race and mid-burst restart path could bypass the normal start-of-stream mitigations, causing garbled onset risk on some devices.

### Validation
- `./gradlew testCivDebugUnitTest --tests TxControllerColdScoWarmupTest --tests TxControllerReadinessBarrierTest`
- `./gradlew assembleCivDebug`
- Deployed to three connected test devices via `./scripts/install-dev.ps1`.

### Notes
- Follow-up verification is expected from fresh post-fix field logs and audio captures to confirm reduction of startup garble recurrence.
