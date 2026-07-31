# Design decisions (enforced on this branch)

Durable record of the load-bearing decisions behind channel sharing,
multicast failover, and the audio plant, so future contributors don't
re-litigate them. Each states the decision and the *why*, and cites the
file that enforces it. If you think one of these is wrong, raise it with
the operator before changing the behavior — several were paid for in
field repros.

## 1. Recipient scoping rides on ATAK's contact model, not a plugin group

Channel share uses ATAK's native sharing model — **individual /
group / everyone** — and delegates recipient scoping entirely to
ATAK's own contact model. XV does **not** implement a plugin-side group
to bound the blast radius.

*Why:* the TAK server's group membership is not reliably visible to a
plugin locally, so any plugin-side "share only to my group" would be
guessing at membership the plugin can't authoritatively see. Bounding
the audience is the server's / ATAK's job; reimplementing it in the
plugin is the wrong layer and would fork from what the operator sees in
the ATAK contact list.

*Enforced in:* `provisioning/XvChannelShare.kt` (`send()` — empty
targets → `dispatchToBroadcast`; specific targets → `dispatchToContacts`
resolved through `Contacts.fromUIDs`).

## 2. Targeted shares fail closed — never fall back to broadcast

A directed share to specific recipients that cannot be resolved to live
contacts returns `false` and sends **nothing**. It never degrades to a
server broadcast.

*Why:* privacy. The wire `targets` attribute only filters who *acts* on
a share, not who *receives* it. A targeted share that fell back to
broadcast would disclose the channel name(s), the sharer's identity, and
the server host to the entire server audience — the opposite of what the
operator asked for. Failing closed and steering to the offline / QR path
is the safe default.

*Enforced in:* `provisioning/XvChannelShare.kt` (`send()`, the
`contacts.isNullOrEmpty()` guard) and `ui/XvDropDownReceiver.kt`
(`promptPickTeammatesThenShare` → "Couldn't share over the network —
use offline QR").

## 3. We do not share server connection info with third parties

A share — whether the CoT nudge or the offline comms plan — carries the
**channel configuration** (and, offline, an optional pre-shared key) so
a recipient can bring up multicast voice. It never carries the means to
connect to and authenticate against the TAK / Mumble server (certs,
credentials, a connection profile).

*Why:* a local-network third party may legitimately need to join the
voice mesh, and the channel config plus key is exactly enough for that.
It is not entitled to your server. The multicast config is
self-sufficient for LAN voice — the receiver derives the endpoint (or
uses a pinned group/port) without ever touching the server — so there is
no reason to hand over server access to make voice work.

*Enforced in:* `provisioning/CommsPlan.kt` (the plan schema carries
`channels` + optional `psk` + a derivation `serverIdentity` hint, and
**no** server credentials) and `plugin/XvMapComponent.kt`
(`buildChannelPlanCarrierInternal`).

## 4. Multicast group is deterministically derived, not exchanged

The failover multicast endpoint is a pure local hash:
`H = SHA-256("xv-mcast-v1|" + serverIdentity + "|" + canon(channelName))`,
mapped to `group = 239.224.0.0/12` and `port = 16800–16899`.

*Why:* two peers on the same server and channel independently compute
the byte-identical group + port with no broker and no coordination, so
failover works the instant the Mumble server disappears — and the share
signal only needs to carry the channel *name*, never the address or a
key. Inputs are chosen for stability: the **channel name** (not the
server-assigned numeric id, which changes when an admin rebuilds the
tree) and the **server hostname** (not the cert fingerprint, which a
renewal would silently fork mid-deployment). SHA-256 (not
`String.hashCode`) keeps same-deployment collisions past observability.

*Enforced in:* `transport/multicast/MulticastGroupDerivation.kt` and
`transport/multicast/ServerIdentity.kt`.

## 5. Mesh legs and MESH-source presence survive a Mumble server drop

Multicast failover legs and presence learned over the mesh are tracked
independently of the TAK-server CoT path, and deliberately persist when
the Mumble server drops.

*Why:* surviving a server outage is the entire point of the mesh. A
channel's default multicast posture is `FAILOVER` — the RX leg is
joined whenever the network is multicast-capable, which is how
Mumble-down is survivable at all. MESH-source presence is kept in its
own map so it does not vanish with the server-bound CoT roster; a peer
heard only over multicast still shows as callable. Tearing either down
on a server disconnect would blind the operator at exactly the moment
failover is supposed to carry them.

*Enforced in:* `transport/multicast/ChannelMulticastConfig.kt`
(`MulticastMode.FAILOVER`), `presence/XvPresenceRegistry.kt` (separate
`meshByUid` map), and `transport/multicast/MeshVoiceManager.kt`
(`FailoverPolicy`).

## 6. Phone stays a phone — no held audio resources between transmissions

XV holds no audio resources (no pre-armed mic session, no retained
capture path) between PTT bursts. Readiness is established by
probe/verify at TX time, not by keeping the plant warm.

*Why:* the device is still the operator's phone. Holding the mic or an
audio route between transmissions starves other apps, drains battery,
and fights the OS audio focus model. Probe-verified readiness is
sanctioned; a persistently pre-armed session is not. HAL warmth is a
property of *recent activity*, not of a per-record held resource.

*Enforced in:* `service/VoicePlant.kt`, `audio/AudioCapture.kt`, and
`audio/ScoLink.kt`.
