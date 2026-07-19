# TAK-XVoice

An ATAK voice plugin focused on operational use cases that existing
options don't cover well: correct Bluetooth audio routing, predictable
auto-reconnect, a curated model for external PTT hardware, and a
foundation for bridging server-mediated voice to mesh radios.

## Status and intended use

**TAK-XVoice is provided for non-mission-critical use only.** It is
distributed under Apache 2.0 (see [LICENSE](LICENSE) and
[NOTICE](NOTICE)) and comes with **no warranty of any kind, express or
implied**, including no warranty of merchantability, fitness for a
particular purpose, or non-infringement. Do not rely on this plugin as
the sole means of communication for any life-safety, public-safety,
military, or other mission-critical application. Operators assume all
risk associated with its use.

The plugin is under active development. Interfaces, defaults, and
on-wire behavior can change between builds while the codebase
stabilizes.

## Why this exists

TAK-XVoice began as a response to gaps in the existing VX voice plugin
that made it difficult to run ATAK the way volunteer comms operators
actually use their phones in the field:

- **No broad external-PTT support.** VX did not integrate cleanly with
  the range of Bluetooth speakermics and BLE PTT pucks that operators
  had already invested in. XV registers external buttons per-MAC
  address and ships with vetted defaults for a small, curated set of
  hardware (see below).
- **Audio routing that fought the rest of the phone.** With VX in the
  loop, keeping music, navigation prompts, and ATAK alerts flowing to
  their normal outputs while driving between event checkpoints was
  awkward at best. XV treats the BT speakermic as XV-exclusive comms
  gear and only engages HFP/SCO while the mic is actually open, so
  A2DP media and ATAK's own audio keep working the way the operator
  expects.
- **A place to add functionality without forking the world.** XV is a
  clean surface to iterate on features like configurable talk-permit
  tones, an LMR-style emergency button, direct calling, and the mesh /
  multicast work described below.

The near-term focus is **mobile-connectivity reliability** — Wi-Fi ↔
cell handoff, fast reconnect, Bluetooth stability across audio-plant
edge cases, and correct behavior on locked/sleeping phones. Once that
baseline is solid, the roadmap moves toward **multicast and
decentralized voice**: server-optional operation, mesh-radio
interoperability via standard RTP framing, and per-frame AEAD with
distributed key election so a channel can keep running when the server
is gone.

## Hardware philosophy — curated, not exhaustive

The goal is **not** to claim support for every Bluetooth PTT device on
the market. Each device that ships as "supported" is deliberately
curated — sourced, integrated, and validated against real event
traffic — so operators can trust that what's listed actually works
under load. New hardware is added on that basis, not on a spec-sheet
claim. If a device isn't listed as supported, it isn't yet.

## Primary mission

The initial deployment target is **volunteer communications teams
supporting public-service events** — parades, bike rallies, triathlons,
marathons, 5Ks, and similar community events where a small comms crew
covers a large geographic footprint over a few hours and needs
lightweight, phone-first voice that plays nicely with the operator's
music, GPS, and ATAK situational picture.

One volunteer organization put TAK-XVoice into production just a
couple of weeks after the first working build, and it has been running
in event traffic since. Work continues on maintenance, stabilization,
and feature polish.

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

3. **Bluetooth and on-device PTT buttons.** XV registers BT speakermic
   buttons per-MAC for the curated device list — SPP-based hardware
   (AINA APTT V1 and similar Pryme speakermics) and BLE GATT devices
   (AINA APTT V2, Pryme BT-PTT-Z). It also drives the programmable
   hardware keys built into curated ruggedized handsets, alongside any
   bonded speakermic:
   - **Samsung Active Key** (Galaxy Tab Active5, XCover6 Pro / 7, Tab
     Active4 Pro, Tab Active3) — the side Active Key becomes a PTT
     source. See [docs/hardware/samsung-active-key.md](docs/hardware/samsung-active-key.md).
   - **Sonim XP10 (XP9900)** — the dedicated side PTT key drives PTT and
     the SOS key fires the ATAK emergency alert. Works with the classic
     Sonim key broadcasts, MCX / MCPTT carrier firmware, and the
     handset's "assign key to ATAK" Programmable-Keys mode. The Yellow
     key is left as an app-launcher convenience key and does not key PTT.
     See [docs/hardware/sonim-xp10.md](docs/hardware/sonim-xp10.md).

   These toggles appear only on hardware that actually has the keys. An
   optional, tightly-scoped accessibility service — enabled once by the
   operator in system settings, reading no screen content and acting only
   on the PTT keycode — extends these hardware buttons to keep working
   while ATAK is backgrounded or the screen is off. XV deep-links
   straight to the relevant system settings page to reduce setup
   friction.

4. **Reachability-aware BT device picker.** On plugin load, XV restores
   the last-connected speakermic when it's actually reachable and
   otherwise auto-picks the first live device — a bonded-but-off puck
   no longer wins over a powered speakermic. The Settings picker paints
   unreachable rows dim and non-tappable so operators can see at a
   glance which pairing is live. If a picked BT device stays
   unreachable for 15 s during a session, XV falls back to the phone
   speaker while preserving the operator's preferred MAC so audio
   returns to BT as soon as the device reconnects.

5. **TX startup hardening.** XV suppresses a known Android route-settle
  race at capture start (initial unresolved route to first stable
  route) so it does not trigger a false in-place restart. If a real
  in-place capture restart occurs during an active burst, XV now
  reapplies start-of-stream stabilization (short hold plus leading
  frame drop) before encoding resumes.

XV also adds:

- **Per-device defaults with overrides** — each supported speakermic
  ships pre-mapped (PTT → Channel 1, secondary → Channel 2, emergency
  button, prev/next channel) so a fresh pairing "just works." Operators
  edit the defaults; overrides persist by BT address.
- **LMR-style emergency button** — short press fires the emergency
  configured in ATAK's Alert Tool; long press (1 s) cancels. On Sonim
  handsets the dedicated SOS key maps to the same emergency.
- **Configurable Talk Permit Tones** (ASTRO 25 / Nextel / DMR / MOTOTRBO
  / None) for operators transitioning from public-safety LMR systems.
- **Mumble + multicast channels under a single UX** — transport is an
  implementation detail. Mumble uses the device's ATAK enrollment cert
  for TLS auth; multicast joins a deterministic UDP group derived from
  the Mumble channel + server cert fingerprint.
- **Direct calling** — Notification.CallStyle ring + full-screen call
  surface, VX-compatible private-call signaling.
- **Mesh-voice-bridge foundation** — per-frame AEAD (ChaCha20-Poly1305),
  TAK-cert-wrapped channel keys, distributed key election. Standard
  RTP framing (RFC 3550 + 7587) for mesh-radio interop.

## Roadmap

- **Now:** mobile-connectivity reliability — Wi-Fi/cell handoff, fast
  reconnect, BT stability across audio-plant edge cases, background
  behavior on locked phones.
- **Next:** multicast channel operation, offline / server-optional
  calling, mesh-radio RTP bridge.
- **Later:** decentralized channel key management (already scaffolded
  via distributed key election and AEAD), and additional curated
  hardware as devices are validated in the field.

## Reporting issues

If you find a defect or see an opportunity for improvement, please
open a GitHub issue on this repository so it can be discussed and
tracked. Please **do not** paste operator-identifying information into
issues — no real Bluetooth MAC addresses, TAK server URLs, callsigns
of live operators, unit or organization names, or GPS coordinates
tied to a real deployment. Redacted / example values (`AA:BB:CC:DD:EE:FF`,
`tak.example`, `Alpha` / `Bravo`) are fine and preferred.

## Acknowledgments

Special thanks to **Bernie, K5BP**, for jumping in head-first —
helping build this out, hammering on it in the field, and pushing it
to where it is today. This project would not be in the shape it is
without his time and testing.

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

Test devices:

- Pixel 9 Pro (primary test device)
- Motorola legacy Android (AINA APTT V1 SPP rig)
- Samsung Galaxy Tab (XV + existing voice-plugin coexistence)

Curated / validated speakermics:

- AINA APTT V1 (BR/EDR SPP)
- AINA APTT V2 (BLE GATT)
- Pryme BT-PTT-Z (HM-10 UART)

Curated / validated ruggedized handsets (on-device hardware keys):

- Samsung Galaxy Tab Active5 (SM-X308U) — Active Key PTT, foreground and
  background (accessibility). See
  [docs/hardware/samsung-active-key.md](docs/hardware/samsung-active-key.md).
- Sonim XP10 (XP9900, AT&T carrier / Android 12) — side PTT key and SOS
  key, via MCX / MCPTT firmware and the assign-to-ATAK mode. The Yellow
  key is an app-launcher convenience key and does not key PTT. See
  [docs/hardware/sonim-xp10.md](docs/hardware/sonim-xp10.md).

This list only includes devices that have been integrated end-to-end
and validated against event traffic. Additional hardware is added the
same way — one device at a time, once it's been tested. Other chassis
that share these keys (other XCover / Tab Active models, non-carrier
Sonim variants) are gated on by model prefix but are not yet validated.

## License

Apache License 2.0 — see [LICENSE](LICENSE) and [NOTICE](NOTICE).
Distributed **as-is, without warranty**. See the "Status and intended
use" section above.
