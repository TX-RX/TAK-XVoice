# Share-channel UX — placement spec (for the UI overhaul)

Hand-off spec for the parallel UX work. The **backend is already fixed**
(commit `fix(provisioning): share the channels the operator actually
sees`); this describes the **UI placement + passphrase gating** that
should land in `XvDropDownReceiver.kt` / `xv_settings.xml` as part of the
overhaul, so the two efforts don't edit those files simultaneously.

## Why

The operator "couldn't see how to clearly share channels." Two causes,
one now fixed in the backend, one still UI:

1. **Fixed (backend):** `shareableChannels()` / `canShareChannelPlan()`
   now return the *visible* channel list (`meshChannelCandidates()`), and
   `buildChannelPlanCarrier` can build a plan for any visible channel
   (with its key when held, else config-only). So "Share" no longer says
   "nothing to share" when the operator is looking at a full list.
2. **Still UI:** the only Share affordance is the `xv_btn_mesh_share`
   button buried in **Settings → Mesh**, detached from the unified main
   channel picker where channels now actually live, and it forces a
   passphrase even for keyless channels.

## What to change (UI)

### 1. Surface Share where channels live — the main picker
`showChannelPicker(slot)` (`XvDropDownReceiver.kt`) is the unified list
now. Add a Share entry point there:
- A **"Share channels…"** header button in the picker, and/or
- **per-row long-press → share that one channel** (mirrors the existing
  long-press-to-forget on offline rows).

Keep (or de-emphasize) the Settings→Mesh `xv_btn_mesh_share` button; the
picker is the primary surface. The tab is now just "Mesh" — Share reads as
unrelated to the picker the operator uses.

### 2. Reuse the existing flow, gate the passphrase
The building blocks already exist and are unchanged:
`promptSelectChannelsThenShare()` → `promptPassphraseThenShare(selected)`
→ `showChannelPlanShareDialog(carrier)` (QR + share sheet).

Change only the passphrase step: **prompt for a passphrase only when the
selected plan carries a key.** The backend seam to add for this (small,
additive Controller-interface changes — the UX PR owns them since the
interface lives in `XvDropDownReceiver.kt`):

- Add to the `Controller` interface:
  ```kotlin
  // True iff any selected channel resolves to a key this device holds,
  // so the shared plan must be passphrase-locked. Keyless/config-only
  // plans can go in the clear.
  fun channelPlanNeedsPassphrase(selected: List<String>): Boolean = false
  ```
  and widen the existing method to a nullable passphrase (backward-
  compatible — a non-null `CharArray` still binds):
  ```kotlin
  fun buildChannelPlanCarrier(passphrase: CharArray?, selected: List<String>): String? = null
  ```
- Implement both in `XvMapComponent`'s Controller:
  - `channelPlanNeedsPassphrase` = any selected channel has a live key
    (`meshVoiceManager?.currentKeyFor(canonical) != null`, resolving names
    the same way `buildChannelPlanCarrierInternal` now does).
  - `buildChannelPlanCarrierInternal(passphrase: CharArray?, selected)`:
    after building `channels`, `val hasKeys = channels.any { it.preSharedKey != null }`.
    If `hasKeys` → require a non-blank passphrase and `encodeLocked`
    (as today); else → `encodeClear(plan)` (the clear carrier refuses
    keys by design, so this path is only taken when there are none).
    `encodeClear` is already used by `exportCommsPlanInternal`.
- UI: in `promptSelectChannelsThenShare`, after the channel selection, call
  `controller.channelPlanNeedsPassphrase(selected)`; if false, skip
  `promptPassphraseThenShare` and go straight to
  `showChannelPlanShareDialog(controller.buildChannelPlanCarrier(null, selected))`.

### 3. Messaging
Replace the "No channels to share yet — create one first" / "Nothing to
share" copy with wording that reflects the new reality: any visible
channel can be shared — with its key when held, otherwise config-only
(the recipient joins the same channel; it just isn't encrypted unless a
key is shared).

## Verification (on device)
- On a TAK server: open the picker, Share a server channel → get a
  scannable/importable **clear** plan with no passphrase prompt.
- A provisioned/encrypted channel → Share prompts for a passphrase and
  produces a **locked** plan; import on a second device round-trips the
  key.
- Confirm the Share affordance is reachable from the main picker without
  entering Settings.
