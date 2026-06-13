package com.atakmap.android.xv.transport.mumble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Coverage for the multi-TAK-server picker logic. The auto, exact-host,
 * and substring-pattern selectors are pure functions that take the
 * host list as a parameter, so tests pin the selection behavior
 * directly — no Robolectric, no mocking of `TAKServerListener`.
 *
 * Production callsites:
 *   - `connectMumbleWithDefaults` → `pickPreferred(persistedPreferredTakHost())`
 *     → `selectByExactHost(all(), pref)`
 *   - debug intent `MUMBLE_CONNECT --es takPattern "..."` → `pick(pattern)`
 *     → `selectByPatternOrAuto(all(), pattern)`
 *
 * Field-relevant cases pinned:
 *   - The H19 multi-server bug shape: with two enrolled OTS instances,
 *     selectByExactHost(preferredHost) deterministically returns the
 *     pinned one regardless of array order.
 *   - Stale pref shape: operator pinned ots-A, later unenrolled it →
 *     selectByExactHost falls back to selectAuto without throwing or
 *     clearing the pref.
 *   - First-connected-wins for the auto path.
 */
class TakServerDiscoveryTest {
    private fun host(
        description: String,
        host: String,
        connected: Boolean,
        port: Int = 8089,
    ) = TakServerDiscovery.TakHost(
        description = description,
        host = host,
        takPort = port,
        connected = connected,
        raw = null,
    )

    // ============================================================
    // selectAuto — first connected → first configured → null
    // ============================================================

    @Test
    fun `selectAuto on empty list returns null`() {
        assertNull(TakServerDiscovery.selectAuto(emptyList()))
    }

    @Test
    fun `selectAuto picks the only host when single-server`() {
        val only = host("Texas OTS", "tak.example.com", connected = true)
        assertEquals(only, TakServerDiscovery.selectAuto(listOf(only)))
    }

    @Test
    fun `selectAuto picks first connected when multiple are configured`() {
        val a = host("A", "a.example.com", connected = false)
        val b = host("B", "b.example.com", connected = true)
        val c = host("C", "c.example.com", connected = true)
        // First *connected* in iteration order — that's `b`, not `c`,
        // even though both are connected.
        assertEquals(b, TakServerDiscovery.selectAuto(listOf(a, b, c)))
    }

    @Test
    fun `selectAuto falls back to first configured when none are connected`() {
        val a = host("A", "a.example.com", connected = false)
        val b = host("B", "b.example.com", connected = false)
        assertEquals(a, TakServerDiscovery.selectAuto(listOf(a, b)))
    }

    // ============================================================
    // selectByExactHost — pinned by H19 (operator-chosen pref)
    // ============================================================

    @Test
    fun `selectByExactHost with null pref delegates to selectAuto`() {
        val a = host("A", "a.example.com", connected = false)
        val b = host("B", "b.example.com", connected = true)
        assertEquals(b, TakServerDiscovery.selectByExactHost(listOf(a, b), null))
    }

    @Test
    fun `selectByExactHost with blank pref delegates to selectAuto`() {
        val a = host("A", "a.example.com", connected = false)
        val b = host("B", "b.example.com", connected = true)
        // Trim of whitespace would still be blank under isNullOrBlank.
        assertEquals(b, TakServerDiscovery.selectByExactHost(listOf(a, b), "   "))
    }

    @Test
    fun `selectByExactHost matches case-insensitively`() {
        val a = host("Texas", "tak.example.com", connected = true)
        val b = host("Other", "other.example.com", connected = true)
        // Operator may have typed "TAK.TEXAS-TAK.COM" or similar.
        assertEquals(a, TakServerDiscovery.selectByExactHost(listOf(a, b), "TAK.TEXAS-TAK.COM"))
    }

    @Test
    fun `selectByExactHost honors the pref when it matches a not-currently-connected host`() {
        // H19 case: operator pinned ots-A but ots-B is the connected
        // one right now. We must still return A — that's the whole
        // point of the pref.
        val a = host("OTS-A", "ots-a.example.com", connected = false)
        val b = host("OTS-B", "ots-b.example.com", connected = true)
        assertEquals(a, TakServerDiscovery.selectByExactHost(listOf(a, b), "ots-a.example.com"))
    }

    @Test
    fun `selectByExactHost falls back to selectAuto on stale pref (host unenrolled)`() {
        // Operator pinned ots-X then unenrolled it. Pref still says
        // "ots-x" but it's not in the list. Behavior: silent fallback
        // to auto, no exception. Operator may be mid-reconfig so we
        // don't proactively clear the pref.
        val a = host("OTS-A", "ots-a.example.com", connected = true)
        assertEquals(a, TakServerDiscovery.selectByExactHost(listOf(a), "ots-x.example.com"))
    }

    @Test
    fun `selectByExactHost on empty list returns null even with pref`() {
        assertNull(TakServerDiscovery.selectByExactHost(emptyList(), "anything"))
    }

    // ============================================================
    // selectByPatternOrAuto — used by the debug-bus MUMBLE_CONNECT path
    // ============================================================

    @Test
    fun `selectByPatternOrAuto with null pattern delegates to selectAuto`() {
        val a = host("A", "a.example.com", connected = true)
        assertEquals(a, TakServerDiscovery.selectByPatternOrAuto(listOf(a), null))
    }

    @Test
    fun `selectByPatternOrAuto matches a substring in the host`() {
        val a = host("Texas", "tak.example.com", connected = false)
        val b = host("Other", "other.example.com", connected = true)
        assertEquals(a, TakServerDiscovery.selectByPatternOrAuto(listOf(a, b), "texas"))
    }

    @Test
    fun `selectByPatternOrAuto matches a substring in the description`() {
        val a = host("Texas OTS Primary", "ots-tx.example.com", connected = false)
        val b = host("California Backup", "ots-ca.example.com", connected = true)
        assertEquals(a, TakServerDiscovery.selectByPatternOrAuto(listOf(a, b), "primary"))
    }

    @Test
    fun `selectByPatternOrAuto with no-match pattern returns null (not auto-fallback)`() {
        // Important behavior: if the operator typed a SPECIFIC pattern,
        // we don't fall back to auto — that would mask an "I asked for
        // X but you gave me Y" bug. The debug intent path returns null
        // and logs the miss; the caller (DebugReceiver) surfaces it.
        val a = host("A", "a.example.com", connected = true)
        assertNull(TakServerDiscovery.selectByPatternOrAuto(listOf(a), "nonexistent"))
    }

    @Test
    fun `selectByPatternOrAuto on empty list returns null`() {
        assertNull(TakServerDiscovery.selectByPatternOrAuto(emptyList(), "anything"))
    }

    @Test
    fun `selectByPatternOrAuto matches case-insensitively against both fields`() {
        val a = host("CASE-SENSITIVE-Description", "MIXED-Case-Host.com", connected = true)
        // Pattern in lowercase, hits description.
        assertEquals(a, TakServerDiscovery.selectByPatternOrAuto(listOf(a), "case-sensitive"))
        // Pattern in uppercase, hits host.
        assertEquals(a, TakServerDiscovery.selectByPatternOrAuto(listOf(a), "MIXED"))
    }
}
