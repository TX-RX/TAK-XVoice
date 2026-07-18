# Changelog

All notable user-visible and operationally significant changes to TAK-XVoice are documented in this file.

## [Unreleased]

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
