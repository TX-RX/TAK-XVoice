package com.atakmap.android.xv.presence

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Cache of XV peer presence info, keyed by ATAK device UID. Thread-safe;
 * receivers (CoT listener) and consumers (UI / call-routing logic) may
 * touch it from any thread.
 *
 * TTL: a peer entry is considered "stale" after [staleAfterMs] without
 * a fresh CoT update. ATAK self-CoT typically broadcasts every 30-60s
 * depending on user settings; 5 minutes is generous enough to ride
 * through one missed update without flapping.
 */
class XvPresenceRegistry(
    // 15 minutes — matches the Recent Users picker window. Entries older
    // than this are purged on each enumeration, so the picker never has
    // to filter manually.
    private val staleAfterMs: Long = 15 * 60_000L,
) {
    private val byUid = ConcurrentHashMap<String, XvPresence>()

    // Observers notified after every upsert (both normal presence and bridge
    // state). CopyOnWriteArrayList: reads are lock-free, writes (add/remove)
    // are rare. Callbacks run on whatever thread called upsert/upsertBridgeState.
    private val listeners = CopyOnWriteArrayList<(XvPresence) -> Unit>()

    fun addListener(fn: (XvPresence) -> Unit) {
        listeners.add(fn)
    }

    fun removeListener(fn: (XvPresence) -> Unit) {
        listeners.remove(fn)
    }

    fun upsert(p: XvPresence) {
        byUid[p.deviceUid] = p
        listeners.forEach { it(p) }
    }

    fun get(uid: String): XvPresence? = byUid[uid]

    fun all(): Collection<XvPresence> = byUid.values

    /** True when we've seen this UID's `<__xv>` detail recently enough to
     *  trust they're still online and XV-callable. */
    fun isFresh(
        uid: String,
        nowMs: Long = System.currentTimeMillis(),
    ): Boolean {
        val p = byUid[uid] ?: return false
        return nowMs - p.lastSeenMs <= staleAfterMs
    }

    /** Return entries last seen within [windowMs]. Used by the Recent
     *  Users picker, which wants a custom window (e.g. 15 min) longer
     *  than the registry's overall stale TTL. The registry still purges
     *  on its own TTL so an entry surfaced here is always live. */
    fun seenWithin(
        windowMs: Long,
        nowMs: Long = System.currentTimeMillis(),
    ): List<XvPresence> = byUid.values.filter { nowMs - it.lastSeenMs <= windowMs }

    fun remove(uid: String): XvPresence? = byUid.remove(uid)

    fun clear() = byUid.clear()

    /**
     * Update only the bridge-election fields from a local `<__xv_bridge>` CoT
     * event. Does nothing if the peer isn't already in the registry (bridge
     * CoT may arrive before the full presence CoT on first contact; we skip
     * rather than create a skeleton entry with unknown capabilities).
     */
    fun upsertBridgeState(
        uid: String,
        mumbleConnected: Boolean,
        isBridging: Boolean,
        nowMs: Long,
    ) {
        val updated = byUid.computeIfPresent(uid) { _, existing ->
            existing.copy(
                mumbleConnected = mumbleConnected,
                isBridging = isBridging,
                bridgeLastSeenMs = nowMs,
            )
        }
        updated?.let { listeners.forEach { cb -> cb(it) } }
    }

    /**
     * Drop entries we haven't heard from within [staleAfterMs]. Called
     * before each enumeration of the registry so the call-peer picker
     * doesn't surface ghost contacts whose ATAK closed without sending
     * an offline CoT. Returns the number of entries removed for logging.
     */
    fun purgeStale(nowMs: Long = System.currentTimeMillis()): Int {
        var removed = 0
        val it = byUid.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            if (nowMs - e.value.lastSeenMs > staleAfterMs) {
                it.remove()
                removed++
            }
        }
        return removed
    }
}
