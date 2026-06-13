package com.atakmap.android.xv.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReconnectPolicyTest {
    @Test
    fun `default schedule walks the documented backoff curve`() {
        val p = ReconnectPolicy()
        val expected = longArrayOf(1_000L, 2_000L, 4_000L, 8_000L, 15_000L, 30_000L, 60_000L)
        for ((i, want) in expected.withIndex()) {
            val got = p.nextDelayMs()
            assertEquals("attempt #${i + 1}", want, got)
        }
    }

    @Test
    fun `last entry repeats forever after schedule exhaustion`() {
        val p = ReconnectPolicy()
        repeat(7) { p.nextDelayMs() }
        // Past the end of the curve — the cap should hold steady at 60s
        // so the wrapper keeps probing once-a-minute rather than
        // refusing to retry.
        repeat(20) {
            assertEquals(60_000L, p.nextDelayMs())
        }
    }

    @Test
    fun `attemptCount tracks calls to nextDelayMs`() {
        val p = ReconnectPolicy()
        assertEquals(0, p.attemptCount())
        p.nextDelayMs()
        assertEquals(1, p.attemptCount())
        p.nextDelayMs()
        p.nextDelayMs()
        assertEquals(3, p.attemptCount())
    }

    @Test
    fun `reset rewinds the curve so success goes back to the start`() {
        val p = ReconnectPolicy()
        repeat(4) { p.nextDelayMs() }
        assertEquals(4, p.attemptCount())
        p.reset()
        assertEquals(0, p.attemptCount())
        // After reset the curve starts over from 1s — i.e. a recovery
        // doesn't penalize the next-time-it-drops case with the long
        // tail of the previous failure.
        assertEquals(1_000L, p.nextDelayMs())
    }

    @Test
    fun `shouldRetry is true only for transient outcomes`() {
        val p = ReconnectPolicy()
        assertTrue(p.shouldRetry(ReconnectPolicy.Outcome.Transient))
        assertFalse(p.shouldRetry(ReconnectPolicy.Outcome.Fatal("WrongUserPW")))
        assertFalse(p.shouldRetry(ReconnectPolicy.Outcome.UserInitiated))
    }

    @Test
    fun `custom schedule is honored`() {
        val p = ReconnectPolicy(schedule = longArrayOf(100L, 200L, 500L))
        assertEquals(100L, p.nextDelayMs())
        assertEquals(200L, p.nextDelayMs())
        assertEquals(500L, p.nextDelayMs())
        // Last entry repeats after exhaustion just like the default.
        assertEquals(500L, p.nextDelayMs())
        assertEquals(500L, p.nextDelayMs())
    }

    @Test
    fun `SPP_SCHEDULE matches the AINA tactical-range tuning`() {
        val p = ReconnectPolicy(schedule = ReconnectPolicy.SPP_SCHEDULE)
        // Tighter than DEFAULT — 400ms first attempt — because the
        // hardware is local: the operator either walks back into BR/EDR
        // range within a minute or they don't, and we want quick
        // recovery from a half-second blip.
        assertEquals(400L, p.nextDelayMs())
        assertEquals(800L, p.nextDelayMs())
        assertEquals(1_600L, p.nextDelayMs())
        assertEquals(3_200L, p.nextDelayMs())
        assertEquals(6_400L, p.nextDelayMs())
        // Steady-state 30s cap (lower than DEFAULT's 60s — local hardware
        // recovery shouldn't take that long; if it does, the device is
        // probably off).
        assertEquals(30_000L, p.nextDelayMs())
        assertEquals(30_000L, p.nextDelayMs())
    }

    @Test
    fun `Fatal Outcome carries operator-facing reason text`() {
        val outcome = ReconnectPolicy.Outcome.Fatal("server kicked us")
        assertEquals("server kicked us", outcome.reason)
    }
}
