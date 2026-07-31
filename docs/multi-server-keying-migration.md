# Multi-server keying migration — rekey persisted channel state to (server, channel)

Status: **planned, not started.** This document is the migration plan
referenced by the characterization test
`app/src/test/java/com/atakmap/android/xv/plugin/XvSettingsMultiServerCollisionTest.kt`,
which fences the current (incorrect) single-key behavior. The rekey is
deliberately deferred (persistence migration is risky and the current
behavior is tolerable for single-server operators); this file records
what must change and how, so the work can be picked up safely later.

## Problem

Channel identity is really the pair **(server, channel)**. The same
canonical channel name (`ops`) on two different TAK servers is two
different multicast channels — different derived group/port, potentially
different operator pins, crypto posture, and provenance. But several
`XvSettings` stores key per-channel state by the **canonical channel
name alone** (`MulticastGroupDerivation.canonicalChannelName`), so the
second server's write silently overwrites the first server's state.

The known-channel directory being a flat name set is the root
enabler: because `PREF_KNOWN_CHANNELS` never carried a server axis, the
per-channel side-tables that hang off it inherited the same flat key.

## Affected stores in `XvSettings`

All keys live in the plugin prefs file `xv_settings` (`PREFS_NAME`).

| Pref key (constant) | Methods | Current key | Collision |
| --- | --- | --- | --- |
| `channel_multicast_configs` (`PREF_CHANNEL_MULTICAST`) | `persistChannelMulticastConfig`, `channelMulticastConfigFor`, `channelMulticastConfigs`, `removeChannelMulticastConfig`, `clearAllChannelMulticastConfigs` | canonical name inside each JSON element (`ChannelMulticastConfig.channelName`); upsert does `removeAll { fromJson(it)?.channelName == canonical }` | **Yes** — group/port pin, mode, wire format, crypto policy of server 1's `ops` replaced by server 2's `ops`. |
| `xv_crypto_policy_<canonical>` (dynamic key) | `persistCryptoPolicy`, `channelCryptoPolicyFor` | `"xv_crypto_policy_" + canonical` | **Yes** — safety-relevant: a CLEARTEXT `ops` on one server flips the REQUIRED `ops` on another. |
| `channel_server_map` (`PREF_CHANNEL_SERVER`) | `persistChannelServer`, `channelServer`, `removeChannelServer` | Set of `"<host> <canonical>"`, upsert removes any entry with the same canonical suffix | **Yes** — provenance tag holds exactly one host per name; last-writer-wins. (The backlog note calls this "split correctly"; it is split *from* the config store but is still single-valued per name.) |
| `known_channels` (`PREF_KNOWN_CHANNELS`) | `persistedKnownChannels`, `persistKnownChannels`, `removeKnownChannel`, `clearKnownChannels` | flat `Set<String>` of names | **Partial** — two servers offering `ops` dedupe to one row; not data loss on its own, but it is the axis the tables above need. |

Not per-channel, but adjacent and worth noting:

- `primary_channel` (`PREF_PRIMARY_CHANNEL`, `persistPrimaryChannel` /
  `persistedPrimaryChannel`) is a single global "last-joined channel"
  string with no server axis. On reconnect to a *different* server it
  can drive an auto-rejoin to a same-named channel on the wrong server.
  Rekeying this to per-server (last-joined channel **per** server) is a
  natural rider on this migration.
- `mesh_key_vault_sealed` (`PREF_MESH_KEY_VAULT`) is a sealed blob whose
  internal `MeshKeyVault` entries are keyed by channel; if those entries
  are name-keyed they carry the same collision one layer down. Audit
  `MeshKeyVault` serialization as part of this work (out of scope for
  the first cut if it already carries a server axis).

## Target key

Introduce a normalized server axis and key every per-channel store by
`(serverIdentity, canonicalChannelName)`.

- **Server axis:** reuse `ServerIdentity.fromHostname(host).value` — it
  already trims/lowercases, strips scheme/path/port, and unwraps IPv6.
  This is exactly the normalization the multicast derivation uses, so
  the persistence key and the on-wire group derivation stay consistent
  (a channel's stored config keys off the same identity that derives its
  group). Do **not** invent a second normalization.
- **Composite string:** `"${serverIdentity}${canonicalChannel}"`
  (unit-separator `0x1f`) — neither a normalized hostname nor a
  canonical channel name can contain `0x1f`, so the split is
  unambiguous even when the channel name contains spaces (the existing
  `channel_server_map` "host-first, split on space" trick does not
  generalize to a key where both halves are free-form).
- Ad-hoc / peer-discovered / offline channels have **no** server. Use a
  reserved sentinel identity (e.g. `"adhoc"`) so they get their
  own namespace instead of colliding with a real server's channel or
  with each other by name.

## Migration strategy — versioned, read-old-write-new

A one-shot, idempotent upgrade guarded by a schema-version pref.

1. **Version marker.** Add `PREF_PREFS_SCHEMA_VERSION = "prefs_schema_version"`.
   Absent/`0` = legacy (name-keyed). Target = `1`. Bump the constant,
   never reinterpret an old number.
2. **Trigger.** Run `migrateChannelKeysIfNeeded()` once, early in plugin
   startup, before any code reads per-channel state (before mesh startup
   and before `connectMumbleWithDefaults`). Guard with a lock so a
   racing second entry no-ops.
3. **Resolve the legacy server.** For each legacy name-keyed entry, look
   up its provenance in `channel_server_map` (the one place a name→host
   hint exists today). Entries with a known host migrate to
   `(host, channel)`; entries with **no** provenance migrate to the
   ad-hoc sentinel namespace (they were server-agnostic in practice).
4. **Read-old → write-new, then delete old.** Rewrite each affected
   store into the composite-key layout, then remove the legacy key in
   the same `edit()` transaction so the migration is atomic per store
   and re-entry is a no-op.
5. **Set the version** to `1` in the final transaction.
6. **Back-compat read shim (transitional).** New accessors take a
   `serverIdentity`. Keep the old single-arg accessors during the
   transition, delegating to the current/most-recent server so call
   sites migrate incrementally rather than in one giant patch.

This is a forward-only migration. Downgrading to an older XV after
migration leaves the new composite keys unread (old build looks for the
legacy keys, finds none) — acceptable, and the safest failure mode
(operator re-provisions rather than reads someone else's state).

### Why not lazy read-old-write-new per access

A lazy scheme (on read, try new key, fall back to old, rewrite) spreads
the legacy-key knowledge across every accessor and never lets us delete
the old keys with confidence. A single guarded upgrade keeps all the
legacy-format code in one function that can be deleted in a later
release once the floor version has moved past it.

## Risks

- **Data loss on a buggy migration.** A crash mid-migration must not
  destroy legacy state. Mitigate: per-store atomic transactions
  (write-new + delete-old together); never delete a legacy key until its
  new-format equivalent is committed; version bump last.
- **Wrong provenance attribution.** A legacy config whose
  `channel_server_map` entry was itself a collision victim will migrate
  to whichever host won the old last-writer-wins race. Unavoidable —
  the pre-migration data genuinely lost the other server's association.
  Document that migrated single-server installs are unaffected; only
  installs that already hit the collision inherit ambiguity.
- **In-flight channels at upgrade time.** If migration runs while a
  channel is joined/keyed, downstream state (live `MeshLeg`, key vault)
  must re-resolve against the new key. Run migration strictly before
  transport/mesh startup so nothing holds a stale name-keyed handle.
- **Crypto-posture bleed until migrated.** This is the one with a safety
  edge: pre-migration, a CLEARTEXT `ops` can mask a REQUIRED `ops`.
  Migration removes the bleed, but until it ships the mitigation is the
  characterization test plus operator awareness (single-server installs
  are unaffected).
- **`primary_channel` auto-rejoin to wrong server** if that rider is not
  taken: leaving it global means reconnect to a new server may honor a
  same-named channel from the old one.
- **Sealed key vault coupling.** If `MeshKeyVault` entries are
  name-keyed, they need the same rekey or channels re-key incorrectly
  post-migration. Audit before declaring done.

## Test plan

- **Characterization (exists):**
  `XvSettingsMultiServerCollisionTest` pins the current overwrite for
  the config store, the server-tag store, and the crypto-policy store.
  Its inline `IDEAL (post-rekey)` assertions are the acceptance target.
- **Post-rekey coexistence:** two configs for `ops` on `tak.example`
  and `tak2.example` both survive; `channelMulticastConfigFor(server,
  "ops")` returns the right one per server; `channelMulticastConfigs()`
  reports 2. Same for crypto policy (REQUIRED vs CLEARTEXT coexist) and
  provenance (both servers listed for the name).
- **Migration from legacy fixtures:** seed a prefs file in the legacy
  layout (name-keyed config + a `channel_server_map` provenance entry),
  run `migrateChannelKeysIfNeeded()`, assert the entry now lives under
  `(serverIdentity, channel)` and the legacy key is gone.
- **No-provenance path:** legacy config with no `channel_server_map`
  entry migrates to the ad-hoc sentinel namespace, not a real server.
- **Idempotence / re-entrancy:** running the migration twice is a no-op;
  version pref reaches `1` and stays; a second call touches nothing.
- **Server-identity normalization:** `tak.example`,
  `https://tak.example:8089/`, and `TAK.EXAMPLE.` all resolve to the
  same composite key (delegates to `ServerIdentity.fromHostname`).
- **Robolectric, real SharedPreferences** — same harness as the existing
  `XvSettings*Test` classes; the migration is a property of the on-disk
  key layout, so a mocked prefs would not exercise it.
