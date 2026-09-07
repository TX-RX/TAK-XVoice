# Field-verification checklist — channel share

Operator-runnable smoke test for the CoT channel-share feature (the
passphrase/string-free "join my channel" nudge). It verifies the
**current** behavior on this branch: sharing to *Everyone* broadcasts
to your server contacts; sharing to *specific teammates* is a directed
send that **fails closed** (nothing goes out, no broadcast) if the
recipients can't be resolved, and the UI then steers you to the
offline / QR path.

Run it with two XV devices before shipping a build that touches
sharing, presence, or multicast derivation. Tick each box.

---

## Setup

- [ ] Two XV devices (call them **Alpha** and **Bravo**), both on
      ATAK-CIV Developer edition, both enrolled to the same TAK server
      (`tak.example.com`) and showing each other in the Recent Users /
      presence roster.
- [ ] Mesh voice is enabled on both, on the same L2 network
      (multicast-capable Wi-Fi), so a derived failover leg can actually
      come up.
- [ ] At least one channel exists on Alpha to share (an encrypted one
      is the more interesting case).

## Everyone-share (broadcast)

- [ ] On Alpha, open the share flow, pick one or more channels, choose
      **Everyone**, and Send. Alpha toasts `Shared N channel(s).`
- [ ] Bravo raises a **"Join shared channel(s)?"** prompt naming the
      sharer, the channel(s), and (if known) the server. Tap **Join**.
- [ ] Both devices independently derive the **same** multicast
      `group:port` for that channel name — they converge without the
      address ever being on the wire (derivation is
      `(serverIdentity, channelName)` → `239.224.0.0/12 : 16800–16899`).
      Confirm mesh voice passes both directions.
- [ ] For an encrypted channel, the key **auto-exchanges** over the
      existing election — no passphrase was typed and no key was on the
      share wire. Encrypted mesh audio decodes on both ends.
- [ ] Send the same Everyone-share again. Bravo does **not** re-prompt
      for the duplicate delivery (dedup by share event UID; server
      backlog replay and multi-path copies must not re-pop Join).

## Individual-target share (directed)

- [ ] With a third device (or by targeting only Bravo), share to a
      **specific** teammate. Only the addressee prompts; other
      presence-roster peers do **not** see a Join prompt.
- [ ] Confirm the addressee joins and hears mesh voice as above.

## Targeted share with an unresolvable / stale contact (fail-closed)

This is the privacy-critical case. A targeted share must never fall
back to a server-wide broadcast.

- [ ] Target a teammate whose contact is stale/unresolvable (e.g. the
      peer just dropped off the server, or pick a roster entry that no
      longer resolves to a live contact) and Send.
- [ ] **Nothing is dispatched.** No third party — and specifically not
      the whole-server audience — receives a Join prompt or sees the
      channel name / sharer / server host. (The wire `targets`
      attribute only filters who *acts*; it does not limit who
      *receives*, which is exactly why a targeted share is never
      broadcast.)
- [ ] Alpha shows the toast **"Couldn't share over the network — use
      offline QR."** and the offline share path opens automatically.
- [ ] Nothing leaked to the server: confirm no other roster device
      prompted.

## Offline comms-plan import/export regression

- [ ] From the offline path (the fallback above, or the explicit
      **Offline / QR** button), export a comms plan for a **keyless**
      channel. It encodes **clear** — no passphrase demanded.
- [ ] Export a plan for a channel that **carries a key**. It is
      passphrase-locked; a blank passphrase is refused.
- [ ] Import each plan on the other device. A keyless plan imports and
      the channel becomes visible + joinable in the picker; a locked
      plan imports only with the correct passphrase.
- [ ] Import round-trips: an accepted plan's channels persist across an
      ATAK restart (force-stop `com.atakmap.app.civ`, relaunch) and are
      still in the picker — not just the auto-primary.

## What to capture (and what NOT to commit)

- Watch these log tags: **`XvChannelShare`** (send / parse / fail-closed
  decisions), **`XvCotListener`** (inbound share + presence), and
  **`XV`** (accept / join / derivation).
- The fail-closed line to look for on a blocked targeted share:
  `send: targeted recipients unresolved — failing closed (no broadcast)`.
- **Do NOT commit raw field logs.** logcat / dumpsys captures routinely
  contain Bluetooth MACs, device serials, real TAK hostnames, and call
  metadata. Keep captures under `logs/` / `diagnostics/` (already
  gitignored) and never `git add -f` them. If you need to quote a line
  in an issue or PR, scrub hostnames to `tak.example`, MACs to
  `XX:XX:XX:XX:XX:XX`, and callsigns to `Alpha` / `Bravo` first.
