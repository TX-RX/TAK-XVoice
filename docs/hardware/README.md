# Hardware setup guides

Device-specific setup and behavior notes for the ruggedized handsets and
speakermics TAK-XVoice supports. These pages cover the parts that vary by
chassis — which physical keys map to what, the OS settings an operator
has to touch, and the foreground vs. background behavior.

For the curated-hardware policy (what "supported" means and why a device
is or isn't listed), see the top-level [README](../../README.md).

## On-device hardware keys

- [Samsung Active Key](samsung-active-key.md) — programmable side key on
  Galaxy Tab Active5 / Active4 Pro / Active3 and XCover6 Pro / 7.

## Bluetooth speakermics

The curated / validated Bluetooth speakermics (AINA APTT V1 / V2, Pryme
BT-PTT-Z) are registered per-MAC and mapped to channels automatically;
see the top-level README's "What's different" and "Hardware tested"
sections.

- [Pryme BT-PTT-Z Pairing Guide](pryme-bt-ptt-z.md) — instructions and troubleshooting for Pryme BLE PTT buttons.

## Retired hardware

Devices that were supported at one point and have since been dropped.
The pages are kept so existing links resolve to an explanation of what
changed and why, rather than a 404.

- [Sonim XP10 (XP9900)](sonim-xp10.md) — retired. Dropped for device
  stability and Bluetooth reliability problems that made it unsuitable
  as a custom-button integration target, compounded by an end-of-life
  platform with no further security updates.
