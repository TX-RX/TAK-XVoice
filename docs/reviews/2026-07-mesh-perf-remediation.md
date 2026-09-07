# Mesh / UX / perf review remediation — 2026-07

Remediation of an external (Copilot) code review of the multicast mesh,
settings UI, and performance paths. Every finding was verified against
the actual code first; **4 of 15 were false positives** (the reviewer
saw superseded code or misread wiring) and were left untouched.

All fixes are **committed and pushed** on
`feat/mcast-a1-derivation-and-config-q51399`. The pure-JVM logic was
unit-verified in a cloud session (standalone Kotlin + ktlint); the
Android-runtime and UI items are marked **[LOCAL]** and still need
verification on this machine (gradle / Robolectric / device) before
merge — that is what this doc hands off.

## What shipped (by commit)

| Commit subject | Findings | Layer | Verified in cloud |
|---|---|---|---|
| `fix(mcast): own the multicast lock + socket per receive loop` | #1 (+sibling socket race) | Android transport | no — **[LOCAL]** |
| `fix(mcast): shutdownNow + connected-guard at teardown` | #14 | Android transport | no — **[LOCAL]** |
| `fix(mesh): evict peer maps on departure; bound the ssrc map` | #4, #13 | pure-JVM | yes (unit) |
| `fix(mesh): prune relay codec/timestamp maps on bridge-role loss` | #3 | pure-JVM (+leg) | yes (unit) |
| `feat(mesh): warn on unresolved-SSRC relay; cache key fingerprint` | #5, #12 | pure-JVM | yes (unit) |
| `chore(ui): drop dead wireChannelSelectors; clarify tab wiring` | #7, #6 | UI (no behavior) | ktlint only |
| `perf(ui): reuse cached main view on back; refresh picker on reconnect` | #9, #10 | UI runtime | no — **[DEVICE]** |

## Did NOT touch — verified already-correct (do not re-flag)

- **#2** `relayedSpeakerKeys` "leak" — that field is gone; it's now the
  self-evicting TTL map `relayedSpeakerLastMs` (`MulticastMeshLeg.kt`),
  already covered by `MulticastMeshLegRelayTest`.
- **#8** "mesh status invisible on main panel" — it IS shown via
  `Controller.meshStatus()` → `R.id.xv_mesh_status` in `refreshMain`,
  including the red CLEAR warning. Only `statusBadge()` is UI-unused, and
  that's a diagnostics helper.
- **#11** `relayToMumble` "under the monitor" — `onVoice` is deliberately
  NOT `@Synchronized`; the lock is taken only inside `decideVoiceRx` and
  released before the relay/playback side effects (design comment cites a
  prior ANR fix).
- **#15** "mic held open onCreate→onDestroy" — `AudioRecord`/`AudioTrack`
  are built lazily per-burst and released on idle; capture audioSource is
  already `VOICE_COMMUNICATION`, playback usage `USAGE_VOICE_COMMUNICATION`.

## Notes on two fixes where the review's premise was wrong

- **#5** — the suggested *hard-gate* on unresolved SSRCs would silence a
  legitimate fully-offline mesh-only peer (no CoT presence → unresolvable
  SSRC is exactly the bridge case). Shipped a **rate-limited WARN** for
  observability instead; relay behavior is unchanged.
- **#1** — the review named only the lock race; there is a **sibling
  `socket = null` race** in the same `cleanup()` that could null the new
  loop's socket. Both are fixed by per-invocation ownership.

## Local verification required before merge

Run all three gates from `CLAUDE.md` (local-only — CI can't compile this):

```sh
./gradlew ktlintCheck
./gradlew assembleCivDebug
./gradlew testCivDebugUnitTest   # the only gate that compiles the test source set
```

Then the runtime-specific checks the cloud session could not do:

- **#1 (Robolectric + device):** extend `MulticastTransportSwapTest` to
  assert the post-swap multicast lock is still held (the old loop's
  cleanup must not release the new loop's lock). On a device, force a
  Wi-Fi↔cellular swap and confirm multicast RX survives (previously RX
  went dead for the session after any swap).
- **#14 (Robolectric):** confirm no `txSendFailed` increment on a
  `disconnect()` that races queued sends.
- **#9 (device):** Back from the channel picker / members dialog / TAK
  server picker — no flicker, no detached-view crash, content current.
- **#10 (device):** open the channel picker, drop + reconnect Mumble, and
  confirm the list updates in place (no stale channels).

The pure-JVM suites (`MeshVoiceManagerTest`, `MulticastMeshLegRelayTest`)
already pass and are re-run by `testCivDebugUnitTest`; #4/#3/#5 added
tests there (departure eviction, bridge-role-loss relay shed, unresolved
relay warns + rate-limits).
