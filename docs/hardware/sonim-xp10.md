# Sonim XP10 (XP9900) — support retired

**Status: retired.** TAK-XVoice no longer supports the Sonim XP10 /
XP9900. The device-specific readers, the `Build.BRAND` / `Build.MODEL`
gate, the settings row, and the AIDL surface that backed them were
removed. This page is kept as a pointer so existing links resolve to an
explanation rather than a 404.

If you are looking for a supported ruggedized handset with on-device
hardware keys, see [Samsung Active Key](samsung-active-key.md).

## Why support was removed

The XP10 was integrated and carried as a supported device for a period,
then retired. The decision was driven by device behavior observed during
integration and field testing, not by a change in what XV needs from a
handset:

- **It did not meet the project's stability bar.** The handset showed
  persistent instability across testing on a clean OS install — not
  traceable to XV's own state machines, and not resolved by reflashing
  or by carrier firmware updates.
- **It was problematic under performance and endurance testing.**
  Behavior was inconsistent run-to-run, which made it unusable as a
  reference device: a failure on the XP10 could not be trusted to
  indicate an XV regression, and a pass could not be trusted to indicate
  a fix.
- **Bluetooth was the disqualifying problem.** The handset had serious,
  reproducible Bluetooth issues. Because XV's speakermic and BLE-button
  integration depends on a dependable Bluetooth stack — SCO setup and
  teardown, bonded-device reconnect, and HID/SPP button event delivery —
  a handset whose Bluetooth cannot be relied on is a poor candidate for
  custom button integration. This is the reason the device was dropped
  rather than kept on a best-effort basis.
- **End-of-life with no security update path.** The platform is
  approaching vendor end-of-life without further security patches.
  Carrying a supported device that will not receive updates is
  inconsistent with the project's security posture.

Taken together, the maintenance cost of four device-specific readers and
a bespoke AIDL surface was not justified by a device that could not be
trusted as a test target and could not be recommended to operators.

## What this means in practice

- XV will install and run on an XP10 — nothing blocks it — but the
  dedicated PTT key, the SOS / Emergency key, and the Programmable-Keys
  "assign to ATAK" mode are no longer wired to XV.
- On-screen PTT and Bluetooth speakermic PTT behave as they do on any
  other handset, subject to the Bluetooth caveats above.
- No migration is required for other hardware. The Samsung Active Key
  path is unaffected.

## Related

- [Hardware setup guides](README.md)
- Curated-hardware policy — see the top-level [README](../../README.md)
