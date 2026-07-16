# Mesh voice — backlog & deferred work

Working tracker for the mesh / offline voice feature set. Captures what
is deliberately deferred so we can ship the simple thing first and layer
complexity on afterward, instead of boiling the ocean.

## Guiding objective

**Reduce the amount of configuration an operator does in the field, while
still connecting to the people they need to reach.** Every item below is
weighed against that: a feature that adds field config has to earn it.
The default path should be zero-config; manual control is the escape
hatch, never the norm.

Corollary already honored: multicast group/port derive from the channel
name, so provisioning a channel is naming it — no IP/port to enter.
Anything new (including the bridge/patch below) must keep that property:
prefer *derived* or *shared-via-comms-plan* over hand-typed addresses.

## Shipped (for context)

- Four provisioning paths: Mumble-derived (auto), auto-generate
  (one-tap random named + encrypted), manual/interop (name it, optional
  pin for OpenMANET), received (import).
- Transports: Android share sheet (= Quick Share / messaging / email /
  file), QR display (scan-to-import), paste import.
- Mesh status + CLEAR (unencrypted) badge on the main panel.
- Dedicated Mesh & Offline settings tab; long-press to forget a channel.
- Per-channel keys persisted **encrypted at rest**: `MeshKeyVault`
  binary blob → `KeystoreSecretBox` AES-256-GCM under a non-exportable
  Android Keystore key; ciphertext lives in the plugin's prefs (ATAK
  data dir, so "Clear data" wipes it). Restored on mesh startup, so a
  provisioned/imported channel survives a restart and stays shareable.
- Panic **"Wipe mesh keys"** in Mesh & Offline → Danger zone: double
  slide-to-confirm, then zeroize configs + delete the Keystore key
  (shreds the sealed vault) without a full ATAK data clear.

## Deferred — channel bridge / patch (two groups on one channel)

Bridge a channel's internal group to a second "bridge" group so an
outside element (another unit, an OpenMANET net) can talk with the team
without being inside the team's crypto. Reuses the existing relay
primitives (per-speaker SSRC preservation, TTL loop filter, dedup).

- **v1 (build first):** one bridge group per channel, **full-duplex**
  (outsiders can talk into the net), **cleartext** bridge group (interop
  default). CLEAR badge lights while patched; add a distinct
  "patched to external" indicator.
- **Deferred → listen-only toggle:** per-patch direction control
  (monitor vs. talk-back) so outsiders can be made receive-only. Requested
  after v1 is tested. *This is the tracked backlog item from 2026-07-16.*
- **Deferred → separately-keyed private bridge:** encrypt the bridge
  group with a different key than the internal group (private patch to a
  trusted-but-separate element) instead of cleartext.
- **Design guardrails (carry into v1):**
  - Voice crosses the encryption boundary — must be loud/visible.
  - Extend the "never repeat traffic onto its source channel" loop rule
    to the mesh↔mesh hop (we've already fixed this class of echo once).
  - Keep the bridge group low-config: derive it or carry it in the comms
    plan; do not make the field operator hand-type a second IP/port.

## Deferred — remaining share transports

The carrier seam is done (one plan string, many transports); each below
is a thin adapter, but all need on-device validation.

- **In-app QR scan** (receive side): camera preview + decode +
  DropDownReceiver activity-result plumbing. Today: scan our QR with any
  camera and paste into Import.
- **NFC tap:** `NFC` permission + NDEF foreground dispatch + manifest.
- **ATAK data package:** package the plan via the ATAK MissionPackage /
  import API (needs the SDK API surface; validate on device).

## Sharing UX (selective share)

Sharing used to bundle **every** channel this device held a key for —
clumsy and a leak risk (you could hand someone channels they shouldn't
have).

- **Per-channel selection (shipped):** Share now opens a checkbox picker
  (defaults to all checked, every box editable); only the chosen channels
  go into the plan. `buildChannelPlanCarrier(passphrase, selected)` builds
  from the subset. A single-channel device skips the picker.
- **Share to one or many:** the Android share sheet already fans out to
  any/multiple recipients; selection is per-channel, not per-recipient.
- **Saved shareable plans (deferred):** name + persist a channel
  selection so a recurring share is pre-canned and ready to re-send
  without re-picking. Persist the *selection* (channel names); the keys
  come from the encrypted key store.

## Deferred — encrypted key persistence follow-ups

The core (seal-at-rest, restore-on-start, panic wipe) shipped; these
refinements are deferred:

- **On-device validation** of the Keystore round-trip across an app
  restart and an ATAK "Clear data" (confirm the sealed blob is gone and
  the channel is unrecoverable afterward).
- **StrongBox** (`setIsStrongBoxBacked`) opportunistically where the
  device advertises a secure element, falling back to TEE.
- **Re-key / rotate** the wrapping key without losing channels
  (re-seal under a fresh Keystore key).

## Deferred — mission auto-channels hardening

- Explicit server-refusal detection for channel creation (currently
  modeled as a timeout).
- Native ATAK Data Sync mission detection (today: fed via the documented
  `SET_MISSIONS` broadcast only).
- On-screen indication of which mission is driving the primary channel.

## Deferred — misc

- Organize channels by originating server (multi-server): store known
  channels per-server, group the picker by server, tag ad-hoc/offline
  channels; unify the mesh channel list with the primary channel picker;
  restore mesh failover on VS2 (secondary slot). (Raised 2026-07-16.)
