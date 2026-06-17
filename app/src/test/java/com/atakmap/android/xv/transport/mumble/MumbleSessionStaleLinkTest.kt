package com.atakmap.android.xv.transport.mumble

import com.atakmap.android.xv.transport.VxCompat
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Stale-link watchdog coverage for [MumbleSession].
 *
 * Background (2026-06 handoff hardening): on Wi-Fi -> cell handoff the
 * TCP socket can be silently wedged — TLS is up, kernel still has the
 * fd, but no bytes flow either way. Without a ping watchdog the read
 * side only surfaces this when SO_TIMEOUT fires (~20s post-fix, was
 * 30s) — and SO_TIMEOUT only fires AFTER a read attempt itself reaches
 * its deadline, so the practical detection window pre-fix was 30-50s.
 *
 * Fix layered two changes:
 *   - PING_INTERVAL_MS 15_000 -> 8_000 (faster cadence)
 *   - STALE_LINK_TIMEOUT_MS 35_000 -> 18_000 (tighter ceiling)
 *
 * This test uses the test-only seam [setLastServerActivityForTest] +
 * [isLinkStaleForTest] to drive a fake wall clock without standing up
 * a real socket or read thread — Robolectric isn't needed because the
 * watchdog logic is a pure comparison once the inputs are pinned.
 */
class MumbleSessionStaleLinkTest {
    private fun session(): MumbleSession =
        MumbleSession(
            host = "test.example.com",
            port = 64738,
            listener = mockk<MumbleSession.Listener>(relaxed = true),
            takServerHost = "test.example.com",
            slotSuffix = "VS1",
            vxCompat = VxCompat.OFF,
            deviceUid = "ANDROID-test-uid",
        )

    // ============================================================
    // #3, #4 — watchdog ceiling tightened to 18s, ping cadence 8s,
    // socket SO_TIMEOUT 20s. Verify the constants ARE the field-fix
    // values so a future regression that bumps them back up is caught.
    // ============================================================

    @Test
    fun `STALE_LINK_TIMEOUT_MS is 18s — 2026-06 handoff hardening`() {
        assertEquals(
            "watchdog ceiling regressed — must stay at 18s for the field event handoff window",
            18_000L,
            MumbleSession.staleLinkTimeoutMsForTest(),
        )
    }

    @Test
    fun `PING_INTERVAL_MS is 8s — 2026-06 handoff hardening`() {
        assertEquals(
            "ping cadence regressed — must stay at 8s to keep the watchdog catching wedged links inside ~20s",
            8_000L,
            MumbleSession.pingIntervalMsForTest(),
        )
    }

    @Test
    fun `SOCKET_SO_TIMEOUT_MS is 20s — 2026-06 handoff hardening`() {
        assertEquals(
            "socket SO_TIMEOUT regressed — must stay at 20s so the read side surfaces dead links faster than the prior 30s",
            20_000,
            MumbleSession.socketSoTimeoutMsForTest(),
        )
    }

    @Test
    fun `watchdog stale check is symmetric — 2x ping cadence + grace covers the ceiling`() {
        // Document the relationship the operator brief cited: the
        // watchdog must fire inside ~2× ping cadence + grace. With 8s
        // cadence and 18s ceiling, that's (16 + 2 = 18) — the smallest
        // value that's strictly greater than 2× cadence so a single
        // missed ping plus a round-trip slack doesn't false-positive.
        val ping = MumbleSession.pingIntervalMsForTest()
        val stale = MumbleSession.staleLinkTimeoutMsForTest()
        assertTrue(
            "ceiling must be > 2× ping cadence so a single missed ping doesn't trigger the watchdog",
            stale > 2L * ping,
        )
        assertTrue(
            "ceiling must stay tight — bound at 3× ping cadence so true dead-link detection stays inside ~24s",
            stale <= 3L * ping,
        )
    }

    // ============================================================
    // Fake-clock test — 18s gap fires stale, 17s gap does not
    // ============================================================

    @Test
    fun `no server activity for 18s — link reads stale`() {
        val s = session()
        val baseMs = 1_000_000_000L
        s.setLastServerActivityForTest(baseMs)
        // 18s + 1ms gap is past the ceiling — the watchdog fires and
        // closes the socket in the production loop.
        val nowMs = baseMs + 18_001L
        assertTrue(
            "18s+ since last server byte must read stale (would close socket in startPing loop)",
            s.isLinkStaleForTest(nowMs),
        )
    }

    @Test
    fun `no server activity for 17s — link reads fresh`() {
        val s = session()
        val baseMs = 1_000_000_000L
        s.setLastServerActivityForTest(baseMs)
        val nowMs = baseMs + 17_000L
        assertFalse(
            "under-threshold gap must not read stale — would false-positive on a slow but live link",
            s.isLinkStaleForTest(nowMs),
        )
    }

    @Test
    fun `link with no activity timestamp at all does not read stale`() {
        val s = session()
        // lastServerActivityMs == 0L (initial value) — a freshly-built
        // session that hasn't reached the read loop yet must NOT be
        // flagged stale. The production guard
        // (`lastServerActivityMs > 0`) is what protects this.
        val nowMs = System.currentTimeMillis()
        assertFalse(
            "an unprimed session must NOT read stale — would crash a fresh connect",
            s.isLinkStaleForTest(nowMs),
        )
    }

    @Test
    fun `recent activity inside ping cadence does not read stale`() {
        val s = session()
        val baseMs = 1_000_000_000L
        s.setLastServerActivityForTest(baseMs)
        // 5s — well inside one ping cadence (8s). Healthy link.
        val nowMs = baseMs + 5_000L
        assertFalse(
            "fresh activity inside one ping cadence must NOT read stale",
            s.isLinkStaleForTest(nowMs),
        )
    }
}
