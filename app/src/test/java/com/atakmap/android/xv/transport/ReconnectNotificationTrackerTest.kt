package com.atakmap.android.xv.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// Pins the escalation contract that keeps a hotspot-fed device from
// beeping continuously while off-grid: silent until the outage has
// persisted past the threshold, then a single "voice lost" alert, then
// silent again until a reconnect re-arms the tracker.
class ReconnectNotificationTrackerTest {
    @Test
    fun `stays silent for failures below the threshold`() {
        val t = ReconnectNotificationTracker(alertAfterAttempts = 3)
        assertFalse("first drop must not beep", t.onAttemptFailed())
        assertFalse("second attempt must not beep", t.onAttemptFailed())
    }

    @Test
    fun `alerts exactly once on the attempt that reaches the threshold`() {
        val t = ReconnectNotificationTracker(alertAfterAttempts = 3)
        assertFalse(t.onAttemptFailed()) // 1
        assertFalse(t.onAttemptFailed()) // 2
        assertTrue("third failed attempt escalates to a single alert", t.onAttemptFailed()) // 3
    }

    @Test
    fun `does not re-alert on subsequent failures within the same outage`() {
        val t = ReconnectNotificationTracker(alertAfterAttempts = 3)
        repeat(2) { t.onAttemptFailed() }
        assertTrue(t.onAttemptFailed()) // threshold — the single beep
        // The whole point: a forever-repeating retry ladder must not
        // beep on every attempt after the first alert.
        repeat(100) {
            assertFalse("no repeat beeps during a sustained outage", t.onAttemptFailed())
        }
    }

    @Test
    fun `reset re-arms so a fresh outage can alert again`() {
        val t = ReconnectNotificationTracker(alertAfterAttempts = 3)
        repeat(3) { t.onAttemptFailed() } // reaches the alert
        t.reset() // clean (re)connect
        // Next outage counts from zero and can escalate on its own.
        assertFalse(t.onAttemptFailed())
        assertFalse(t.onAttemptFailed())
        assertTrue(t.onAttemptFailed())
    }

    @Test
    fun `default threshold matches the documented 'two or three reattempts' tolerance`() {
        assertEquals(3, ReconnectNotificationTracker.DEFAULT_ALERT_THRESHOLD)
        val t = ReconnectNotificationTracker()
        assertFalse(t.onAttemptFailed())
        assertFalse(t.onAttemptFailed())
        assertTrue(t.onAttemptFailed())
    }

    @Test
    fun `threshold of one alerts immediately on the first failure`() {
        val t = ReconnectNotificationTracker(alertAfterAttempts = 1)
        assertTrue(t.onAttemptFailed())
        assertFalse(t.onAttemptFailed())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects a threshold below one`() {
        // A pure reusable utility should fail fast on misconfiguration
        // rather than silently alerting on the first failure.
        ReconnectNotificationTracker(alertAfterAttempts = 0)
    }
}
