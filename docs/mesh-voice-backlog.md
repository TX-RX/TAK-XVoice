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

## Deferred — mission auto-channels hardening

- Explicit server-refusal detection for channel creation (currently
  modeled as a timeout).
- Native ATAK Data Sync mission detection (today: fed via the documented
  `SET_MISSIONS` broadcast only).
- On-screen indication of which mission is driving the primary channel.

## Deferred — misc

- Provisioned/imported channel keys are session-scoped (held in memory so
  a freshly-created channel is shareable). Persisting them across
  sessions is a separate secret-at-rest decision.
