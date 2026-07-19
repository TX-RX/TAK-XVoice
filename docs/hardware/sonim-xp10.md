# Sonim XP10 (XP9900) hardware buttons

The Sonim XP10 (commercial model **XP9900**) is a ruggedized handset with
a dedicated side **PTT** key, a **Yellow** convenience key, and a red
**SOS / Emergency** key. TAK-XVoice drives PTT from the side PTT key and
the emergency alert from the SOS key, foreground and background. The
Yellow key is treated as an **application-launcher** convenience key —
XV does **not** key PTT from it (see the note below).

Sonim firmware exposes the keys in more than one way depending on carrier
build and how the operator configures **Settings → System → Buttons
(Programmable Keys)**. XV registers for all of them at once, so a single
build covers every variant with no startup firmware check. Downstream
de-duplication (the PTT OR-gate and the emergency state machine) absorbs
any key that happens to fire on two paths for the same press.

## Delivery modes

### 1. Classic broadcasts (any process)

Older / non-carrier firmware emits system-wide broadcasts:

- PTT: `com.sonim.intent.action.PTT_KEY_DOWN` / `_UP`
- SOS: `android.intent.action.SOS.down` / `.up`

### 2. MCX / MCPTT carrier firmware

Carrier builds that activate the MCX / MCPTT policy engine (validated on
an **AT&T XP9900, Android 12**) fire a single action carrying a `state`
extra (`1` = pressed, `0` = released):

- `com.mcx.intent.action.CRITICAL_COMMUNICATION_CONTROL_KEY`

> **Prerequisite (AT&T XP9900):** the carrier's Dispatch Hub
> (`com.att.dh`) grabs the PTT key by default. Set **Programmable Keys →
> PTT key → No Action** to release the key so the broadcast reaches XV.

### 3. Assigned directly to ATAK (package-scoped)

If the operator assigns a key to ATAK in Programmable Keys, Sonim fires
the press as a broadcast scoped to `com.atakmap.app.civ`. Field-verified
mapping on the XP9900 (2026-07-14):

| Key    | keyCode | Assigned-to-ATAK action                     | XV routes to |
| ------ | ------- | ------------------------------------------- | ------------ |
| Yellow | 291     | `com.sonim.intent.action.YELLOW_KEY_DOWN` / `_UP` | **nothing** (app-launcher key — not handled) |
| SOS    | 294     | `com.sonim.intent.action.SOS_KEY_DOWN` / `_UP` (+ `com.kodiak.intent.action.KEYCODE_SOS`) | **Emergency** |

> **The Yellow key is an application-launcher key, not a PTT trigger.**
> Operators assign it to "Launch ATAK" in Programmable Keys so a press
> foregrounds the app. An earlier XV revision routed the `YELLOW_KEY`
> broadcast to PTT — on the mistaken belief that Sonim's assigned-app API
> named the physical PTT button "Yellow" — which made the launcher key
> transmit. XV no longer registers for the Yellow-key actions. PTT comes
> from the dedicated side **PTT** key (keyCode 228) via the classic / MCX
> broadcasts and the KeyEvent / accessibility paths.
>
> The SOS press emits two down events in the same millisecond (Sonim +
> Kodiak); XV drops the duplicate.

## Background & screen-off PTT

Like the Samsung Active Key, the Sonim side PTT key (keyCode **228**) is
also handled by XV's tightly-scoped accessibility service so PTT works
while ATAK is backgrounded or the screen is off. The service acts only on
the PTT keycode, reads no screen content, and passes every other key
through untouched. See
[samsung-active-key.md](samsung-active-key.md#background-ptt--optional-accessibility-service)
for how to enable it and what it can and cannot access.

## Emergency behavior

The SOS key follows the same contract as XV's LMR-style emergency button
and the AINA PTTE key: a **short press fires the emergency configured in
ATAK's Alert Tool**; a **long hold (1 s) cancels**. It does **not** key
voice.

## Setup summary

1. **Settings → System → Buttons (Programmable Keys):** either set the
   PTT key to **No Action** (to use the broadcast path) or **assign** the
   PTT / SOS keys to ATAK (to use the package-scoped path). Both work.
   The Yellow key can be assigned to "Launch ATAK" (or anything else) —
   XV ignores it, so it will not key PTT.
2. Enable the **Sonim hardware buttons** row in XV settings (visible only
   on Sonim hardware). XV deep-links you straight to the Programmable
   Keys and Accessibility pages.
3. For background / screen-off PTT, enable XV's accessibility service (see
   the Samsung page linked above).

## On-device validation

- **XP9900 (AT&T carrier, Android 12)** — `Build.MODEL = XP9900`,
  `Build.BRAND = Sonim`, `Build.MANUFACTURER = Sonimtech`. PTT via the
  MCX action and the assigned-to-ATAK SOS mapping are field-verified
  (2026-07-11 / 2026-07-14). The Yellow key is an app-launcher key and
  is intentionally not routed to PTT.

Non-carrier XP10 variants and the classic broadcast path are covered in
code but not yet validated end-to-end — see the curated-hardware policy
in the top-level README.
