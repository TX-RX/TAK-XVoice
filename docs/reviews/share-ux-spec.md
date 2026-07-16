# Share-channel UX — spec for the UI overhaul (CoT-push architecture)

Hand-off for the parallel UX work. The **backend is done**; this is the UI
placement to land in `XvDropDownReceiver.kt` / `xv_settings.xml` so the two
efforts don't edit those files at once.

## Why (the architecture changed)

The passphrase + giant-carrier-string share was the wrong model. On the
**same TAK server** a teammate needs **nothing but the channel name**: the
multicast group/port is a pure local hash of (server hostname, channel
name), and an encrypted channel's key auto-distributes via the cert-wrapped
election. So the default "share" is now a **broadcast CoT nudge** carrying
just the name(s) — no passphrase, no string, no key on the wire. The QR /
carrier survives only as a labeled **offline / other-platform** fallback.

## Backend already in place (call these)

- **Send:** `XvMapComponent.shareChannelsViaCot(channelNames, targetUids)`
  broadcasts the nudge (`XvChannelShare`); empty `targetUids` = everyone.
- **Targets:** `shareTargetsInternal()` → `List<Pair<uid, callsign>>` from
  the presence roster.
- **Receive:** already wired — an inbound share raises a Join prompt and
  records+joins on accept (`handleIncomingChannelShare`). The UX agent may
  restyle that prompt but does not need to build it.
- **Server label:** `XvSettings.channelServer(name)` → host or null.
- **Sorted list:** `meshChannelCandidates()` already returns alpha-sorted,
  Lobby-first.
- **Offline carrier:** `buildChannelPlanCarrierInternal` already emits a
  **clear** carrier (no passphrase) when the plan carries no key; locked
  only when a key must travel.

## Controller-interface additions (the UX PR owns these — the interface is in `XvDropDownReceiver.kt`)

```kotlin
// Teammates to offer in the share picker (uid to address, callsign to show).
fun shareTargets(): List<Pair<String, String>> = emptyList()

// Broadcast a channel-share nudge to the chosen teammates (empty = everyone).
fun shareChannels(channelNames: List<String>, targetUids: List<String>): Boolean = false

// Originating server label for a channel (for the grouped picker), or null.
fun channelServerLabel(name: String): String? = null
```
Override each in `XvMapComponent`'s Controller to call the backend methods
above (`shareChannelsViaCot`, `shareTargetsInternal`, `settings.channelServer`).

## UI to build

### 1. Default Share = CoT push (no passphrase, no string)
Surface Share **in the main channel picker** (`showChannelPicker`) — a
"Share channels…" action and/or per-row long-press. Flow:
1. **Pick channels** — multi-select with **Select all / Deselect all**.
   The list is already sorted (Lobby first); **group/label rows by
   `channelServerLabel(name)`** ("Server: tak.example.com" headers; an
   "Offline / ad-hoc" bucket for null).
2. **Pick teammates** — from `shareTargets()`, with an **"Everyone"**
   option (→ empty `targetUids`).
3. **Send** → `shareChannels(selectedNames, selectedUids)`. Toast the
   result. **No passphrase step, no string.**

### 2. Receiving side
`handleIncomingChannelShare` already prompts "Join shared channel(s)?" and
joins on accept. Optional: restyle to match the overhaul.

### 3. Offline / other-platform (the exception)
A separate, clearly-labeled **"Share offline / other platform"** action
(not the default) that produces the QR / carrier via
`buildChannelPlanCarrier`. Keyless plans encode clear (no passphrase);
prompt for a passphrase only when the selection carries a key — gate on the
`channelPlanNeedsPassphrase(selected)` seam:
```kotlin
fun channelPlanNeedsPassphrase(selected: List<String>): Boolean = false
fun buildChannelPlanCarrier(passphrase: CharArray?, selected: List<String>): String? = null
```
(Backend `buildChannelPlanCarrierInternal` already does clear-vs-locked by
key presence; just widen the Controller signature and skip the passphrase
dialog when `!channelPlanNeedsPassphrase`.)

### 4. Messaging
Retire "No channels to share yet." Share is about pushing the name to
teammates; any visible channel qualifies.

## Verification (on device)
- Two phones on the same TAK server: provision an ad-hoc channel on A,
  Share → pick B → Send. B gets a **Join** prompt, lands on the same
  channel, and audio flows **encrypted with no passphrase/string** (key
  auto-exchanged).
- "Everyone" broadcast reaches all XV peers on the mission.
- Grouped picker shows the server label + Lobby first.
- Offline path: QR still round-trips for a no-server pair (keyless = no
  passphrase; keyed = passphrase-locked).
