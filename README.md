# TAK-XVoice

An ATAK voice plugin focused on operational use cases that existing
options don't cover well: correct Bluetooth audio routing, predictable
auto-reconnect, a flexible model for external PTT hardware, and a
foundation for bridging server-mediated voice to mesh radios.

## What's different

1. **Audio routing.** The plugin treats your Bluetooth speakermic as
   XV-exclusive comms gear while Spotify, navigation, ATAK alerts, and
   BYO headphones keep working on their normal paths. HFP/SCO is only
   engaged while the mic is actually open (PTT-down or hot-mic). Plugin
   load and channel join do not engage SCO.

2. **Auto-start.** XV remembers the last-used mission and auto-reconnects
   on plugin start. A configurable startup mission is also available for
   fleet-managed deployments — no need to re-open and re-join after every
   ATAK restart.

3. **External button registration.** XV registers BT speakermic buttons
   per-MAC, including SPP-based hardware (AINA APTT V1, similar Pryme /
   Sheepdog speakermics) and BLE GATT devices (AINA APTT V2, Pryme
   BT-PTT-Z, and other generic BLE PTT pucks via a learn-mode wizard).

XV also adds:

- **Per-device defaults with overrides** — each speakermic ships pre-
  mapped (PTT → Channel 1, secondary → Channel 2, emergency button,
  prev/next channel) so a fresh pairing "just works." Operators edit
  the defaults; overrides persist by BT address.
- **LMR-style emergency button** — short press fires the emergency
  configured in ATAK's Alert Tool; long press (1 s) cancels.
- **Configurable Talk Permit Tones** (ASTRO 25 / Nextel / DMR / MOTOTRBO
  / None) for operators transitioning from public-safety LMR systems.
- **Mumble + multicast channels under a single UX** — transport is an
  implementation detail. Mumble uses the device's ATAK enrollment cert
  for TLS auth; multicast joins a deterministic UDP group derived from
  the Mumble channel + server cert fingerprint.
- **Direct calling** — Notification.CallStyle ring + full-screen call
  surface, VX-compatible private-call signaling.
- **Mesh-voice-bridge foundation** — per-frame AEAD (ChaCha20-Poly1305),
  TAK-cert-wrapped channel keys, distributed key election. RTP framing
  (RFC 3550 + 7587) for OpenMANET / Doodle Labs Mesh Rider interop.

## Build

Drop the ATAK CIV SDK jar in place (gitignored, not bundled):

```powershell
Copy-Item C:\path\to\ATAK-CIV-5.6.0.17-SDK\main.jar `
          app\libs\main.jar
```

Then:

```powershell
.\gradlew test                   # unit tests
.\gradlew ktlintCheck            # style
.\gradlew assembleCivDebug       # loads on developer SDK ATAK
.\gradlew assembleCivRelease     # for TPP signing pipeline
.\gradlew tppArchive             # build/tpp/xv-tpp-source-<ver>-{5.6,5.7}.zip
```

The plugin must be signed through the TAK Third-Party Plugin pipeline
to load on Play Store ATAK. The `tppArchive` task produces a source
zip for each ATAK baseline (5.6 + 5.7) ready to upload to the TPP
portal.

## Hardware tested

- Pixel 9 Pro (primary test device)
- Motorola legacy Android (AINA APTT V1 SPP rig)
- Samsung Galaxy Tab (XV + existing voice-plugin coexistence)

Speakermics: AINA APTT V1 (BR/EDR SPP), AINA APTT V2 (BLE GATT), Pryme
BT-PTT-Z (HM-10 UART), generic BLE HID pucks.

## License

Apache License 2.0 — see [LICENSE](LICENSE) and [NOTICE](NOTICE).
