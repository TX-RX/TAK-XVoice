package com.atakmap.android.xv.presence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-Kotlin coverage for the XV peer presence cache. The registry
 * holds one entry per remote device that publishes `<__xv>` self-CoT;
 * the call-routing layer and the Channel Members picker both consult
 * it. A regression in upsert / TTL / purge semantics presents as
 * either ghost peers surfacing in the picker long after the operator
 * has left the mission OR fresh peers vanishing mid-call. Neither
 * mode is easy to debug from logs.
 */
class XvPresenceRegistryTest {
    private fun presence(
        uid: String,
        lastSeenMs: Long,
        callsign: String? = null,
    ) = XvPresence(
        deviceUid = uid,
        version = "0.1.11",
        capabilities = setOf("direct-call", "emc"),
        certFingerprint = null,
        server = "tak.example.com",
        channels = emptyList(),
        lastSeenMs = lastSeenMs,
        callsign = callsign,
    )

    @Test
    fun `upsert then get returns the entry`() {
        val r = XvPresenceRegistry()
        val p = presence("ANDROID-aaa", 1_000L, callsign = "Alpha")
        r.upsert(p)
        assertEquals(p, r.get("ANDROID-aaa"))
    }

    @Test
    fun `get on unknown uid returns null`() {
        val r = XvPresenceRegistry()
        assertNull(r.get("ANDROID-unknown"))
    }

    @Test
    fun `upsert replaces the previous entry for the same uid`() {
        val r = XvPresenceRegistry()
        r.upsert(presence("ANDROID-aaa", 1_000L, callsign = "Alpha"))
        r.upsert(presence("ANDROID-aaa", 2_000L, callsign = "Alpha-renamed"))
        val got = r.get("ANDROID-aaa")
        assertNotNull(got)
        assertEquals("Alpha-renamed", got!!.callsign)
        assertEquals(2_000L, got.lastSeenMs)
    }

    @Test
    fun `all returns every active entry`() {
        val r = XvPresenceRegistry()
        r.upsert(presence("ANDROID-aaa", 1_000L))
        r.upsert(presence("ANDROID-bbb", 2_000L))
        r.upsert(presence("ANDROID-ccc", 3_000L))
        assertEquals(3, r.all().size)
    }

    @Test
    fun `isFresh returns false for unknown uid`() {
        val r = XvPresenceRegistry()
        assertFalse(r.isFresh("never-heard-of", nowMs = 10_000L))
    }

    @Test
    fun `isFresh — entry just under TTL is still fresh`() {
        val r = XvPresenceRegistry(staleAfterMs = 5_000L)
        r.upsert(presence("ANDROID-aaa", lastSeenMs = 0L))
        // now = 4999 — TTL window is [0, 5000]. Inclusive on the
        // boundary (the production check is `<= staleAfterMs`).
        assertTrue(r.isFresh("ANDROID-aaa", nowMs = 4_999L))
    }

    @Test
    fun `isFresh — entry exactly at TTL boundary is fresh`() {
        val r = XvPresenceRegistry(staleAfterMs = 5_000L)
        r.upsert(presence("ANDROID-aaa", lastSeenMs = 0L))
        assertTrue(r.isFresh("ANDROID-aaa", nowMs = 5_000L))
    }

    @Test
    fun `isFresh — entry one ms past TTL is stale`() {
        val r = XvPresenceRegistry(staleAfterMs = 5_000L)
        r.upsert(presence("ANDROID-aaa", lastSeenMs = 0L))
        assertFalse(r.isFresh("ANDROID-aaa", nowMs = 5_001L))
    }

    @Test
    fun `seenWithin honors the caller-supplied window — broader than the registry TTL`() {
        // Registry's own TTL is 1s but the picker asks for entries
        // within the last 10s. seenWithin uses the caller's window,
        // not the registry's TTL.
        val r = XvPresenceRegistry(staleAfterMs = 1_000L)
        r.upsert(presence("ANDROID-aaa", lastSeenMs = 0L))
        r.upsert(presence("ANDROID-bbb", lastSeenMs = 5_000L))
        // At now=10_000, window=10_000:
        //   aaa: 10_000 - 0 = 10_000 → within window (boundary inclusive)
        //   bbb: 10_000 - 5_000 = 5_000 → within window
        val seen = r.seenWithin(windowMs = 10_000L, nowMs = 10_000L)
        assertEquals(2, seen.size)
    }

    @Test
    fun `seenWithin filters out entries older than window`() {
        val r = XvPresenceRegistry(staleAfterMs = 60_000L)
        r.upsert(presence("ANDROID-fresh", lastSeenMs = 9_500L))
        r.upsert(presence("ANDROID-stale", lastSeenMs = 100L))
        // now=10_000, window=1000:
        //   fresh: 10_000 - 9_500 = 500 → within window
        //   stale: 10_000 - 100 = 9_900 → past window
        val seen = r.seenWithin(windowMs = 1_000L, nowMs = 10_000L)
        assertEquals(1, seen.size)
        assertEquals("ANDROID-fresh", seen.first().deviceUid)
    }

    @Test
    fun `remove drops the entry and returns it`() {
        val r = XvPresenceRegistry()
        val p = presence("ANDROID-aaa", 1_000L)
        r.upsert(p)
        assertEquals(p, r.remove("ANDROID-aaa"))
        assertNull(r.get("ANDROID-aaa"))
    }

    @Test
    fun `remove on unknown uid returns null without throwing`() {
        val r = XvPresenceRegistry()
        assertNull(r.remove("nope"))
    }

    @Test
    fun `clear empties the entire registry`() {
        val r = XvPresenceRegistry()
        r.upsert(presence("ANDROID-aaa", 1_000L))
        r.upsert(presence("ANDROID-bbb", 2_000L))
        r.clear()
        assertEquals(0, r.all().size)
    }

    @Test
    fun `purgeStale drops only entries past staleAfterMs`() {
        val r = XvPresenceRegistry(staleAfterMs = 5_000L)
        r.upsert(presence("ANDROID-stale", lastSeenMs = 0L))
        r.upsert(presence("ANDROID-fresh", lastSeenMs = 4_000L))
        // now=10_000:
        //   stale: 10_000 - 0 = 10_000 > 5_000 → purged
        //   fresh: 10_000 - 4_000 = 6_000 > 5_000 → also purged (>!)
        // Build a different scenario so we have one of each.
        val r2 = XvPresenceRegistry(staleAfterMs = 5_000L)
        r2.upsert(presence("ANDROID-stale", lastSeenMs = 0L))
        r2.upsert(presence("ANDROID-fresh", lastSeenMs = 5_500L))
        val removed = r2.purgeStale(nowMs = 10_000L)
        assertEquals(1, removed)
        assertNull(r2.get("ANDROID-stale"))
        assertNotNull(r2.get("ANDROID-fresh"))
    }

    @Test
    fun `purgeStale on a fresh-only registry removes nothing and returns 0`() {
        val r = XvPresenceRegistry(staleAfterMs = 10_000L)
        r.upsert(presence("ANDROID-a", lastSeenMs = 9_000L))
        r.upsert(presence("ANDROID-b", lastSeenMs = 9_500L))
        assertEquals(0, r.purgeStale(nowMs = 10_000L))
        assertEquals(2, r.all().size)
    }

    @Test
    fun `purgeStale on an empty registry returns 0`() {
        val r = XvPresenceRegistry()
        assertEquals(0, r.purgeStale(nowMs = System.currentTimeMillis()))
    }

    @Test
    fun `default staleAfterMs is 15 minutes`() {
        // Pinned to the documented value; a future change here affects
        // both isFresh and purgeStale semantics across the codebase.
        // 15 minutes = 900_000 ms.
        val r = XvPresenceRegistry()
        // Entry seen 14 minutes ago should still be fresh under default.
        r.upsert(presence("ANDROID-aaa", lastSeenMs = 0L))
        assertTrue(r.isFresh("ANDROID-aaa", nowMs = 14 * 60_000L))
        // 16 minutes ago should be stale.
        assertFalse(r.isFresh("ANDROID-aaa", nowMs = 16 * 60_000L))
    }
}
