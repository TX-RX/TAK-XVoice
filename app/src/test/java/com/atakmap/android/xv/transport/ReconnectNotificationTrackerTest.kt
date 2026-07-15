package com.atakmap.android.xv.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// Pins the audio contract that keeps a hotspot-fed device from beeping
// continuously while off-grid: silent for a blip, ONE "voice lost" alert
// once the outage is sustained, a quiet per-attempt "still trying" chirp
// after that, and total silence once the outage outlives the audible
// window — while the ladder itself keeps retrying forever.
class ReconnectNotificationTrackerTest {
    // Fixed epoch base so the time-window assertions read as deltas.
    private val t0 = 1_000_000L

    @Test
    fun `stays silent for failures below the threshold`() {
        val t = ReconnectNotificationTracker(alertAfterAttempts = 3)
        assertFalse("first drop must not beep", t.onAttemptFailed(t0).playAlert)
        assertFalse("second attempt must not beep", t.onAttemptFailed(t0).playAlert)
    }

    @Test
    fun `alerts exactly once on the attempt that reaches the threshold`() {
        val t = ReconnectNotificationTracker(alertAfterAttempts = 3)
        assertFalse(t.onAttemptFailed(t0).playAlert) // 1
        assertFalse(t.onAttemptFailed(t0).playAlert) // 2
        assertTrue("third failed attempt escalates to a single alert", t.onAttemptFailed(t0).playAlert) // 3
    }

    @Test
    fun `does not re-alert on subsequent failures within the same outage`() {
        val t = ReconnectNotificationTracker(alertAfterAttempts = 3)
        repeat(2) { t.onAttemptFailed(t0) }
        assertTrue(t.onAttemptFailed(t0).playAlert) // threshold — the single beep
        // The whole point: a forever-repeating retry ladder must not
        // fire the loud alert on every attempt after the first one.
        repeat(100) {
            assertFalse("no repeat alerts during a sustained outage", t.onAttemptFailed(t0).playAlert)
        }
    }

    @Test
    fun `reset re-arms so a fresh outage can alert again`() {
        val t = ReconnectNotificationTracker(alertAfterAttempts = 3)
        repeat(3) { t.onAttemptFailed(t0) } // reaches the alert
        t.reset() // clean (re)connect
        // Next outage counts from zero and can escalate on its own.
        assertFalse(t.onAttemptFailed(t0).playAlert)
        assertFalse(t.onAttemptFailed(t0).playAlert)
        assertTrue(t.onAttemptFailed(t0).playAlert)
    }

    @Test
    fun `default threshold matches the documented 'two or three reattempts' tolerance`() {
        assertEquals(3, ReconnectNotificationTracker.DEFAULT_ALERT_THRESHOLD)
        val t = ReconnectNotificationTracker()
        assertFalse(t.onAttemptFailed(t0).playAlert)
        assertFalse(t.onAttemptFailed(t0).playAlert)
        assertTrue(t.onAttemptFailed(t0).playAlert)
    }

    @Test
    fun `threshold of one alerts immediately on the first failure`() {
        val t = ReconnectNotificationTracker(alertAfterAttempts = 1)
        assertTrue(t.onAttemptFailed(t0).playAlert)
        assertFalse(t.onAttemptFailed(t0).playAlert)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects a threshold below one`() {
        // A pure reusable utility should fail fast on misconfiguration
        // rather than silently alerting on the first failure.
        ReconnectNotificationTracker(alertAfterAttempts = 0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects a negative audible window`() {
        ReconnectNotificationTracker(audibleUntilMs = -1L)
    }

    // ---- retry chirp ----

    @Test
    fun `chirps on pre-threshold attempts so the operator hears it trying`() {
        val t = ReconnectNotificationTracker(alertAfterAttempts = 3)
        // Attempts 1 and 2 are silent as far as the ALERT goes, but they
        // are the ones an operator most wants a "still trying" cue for.
        assertTrue("first drop should chirp", t.onAttemptFailed(t0).playChirp)
        assertTrue("second attempt should chirp", t.onAttemptFailed(t0 + 1_000).playChirp)
    }

    @Test
    fun `never stacks the alert and the chirp on the same attempt`() {
        val t = ReconnectNotificationTracker(alertAfterAttempts = 3)
        t.onAttemptFailed(t0)
        t.onAttemptFailed(t0 + 1_000)
        val atThreshold = t.onAttemptFailed(t0 + 3_000)
        assertTrue(atThreshold.playAlert)
        assertFalse("the loud alert already carries this attempt", atThreshold.playChirp)
    }

    @Test
    fun `keeps chirping after the alert while the outage is young`() {
        val t = ReconnectNotificationTracker(alertAfterAttempts = 3)
        repeat(3) { t.onAttemptFailed(t0) } // burn through to the alert
        assertTrue("post-alert attempts still chirp", t.onAttemptFailed(t0 + 10_000).playChirp)
        assertTrue(t.onAttemptFailed(t0 + 60_000).playChirp)
    }

    @Test
    fun `goes silent once the outage outlives the audible window`() {
        val window = 10 * 60 * 1000L
        val t = ReconnectNotificationTracker(alertAfterAttempts = 3, audibleUntilMs = window)
        repeat(3) { t.onAttemptFailed(t0) }
        assertTrue("just inside the window still chirps", t.onAttemptFailed(t0 + window - 1).playChirp)
        // At and past the window the device goes quiet — the ladder is
        // still retrying, it just stops making noise for nobody.
        assertFalse("at the window boundary", t.onAttemptFailed(t0 + window).playChirp)
        assertFalse("hours later", t.onAttemptFailed(t0 + 5 * 60 * 60 * 1000L).playChirp)
    }

    @Test
    fun `audible window is measured from the outage start, not the last attempt`() {
        val window = 10 * 60 * 1000L
        val t = ReconnectNotificationTracker(alertAfterAttempts = 3, audibleUntilMs = window)
        t.onAttemptFailed(t0) // outage starts here
        // A long dormant-tail gap must not "refresh" the window — the
        // clock runs from the drop, so a device that's been down for an
        // hour stays quiet no matter how sparse its attempts are.
        assertFalse(t.onAttemptFailed(t0 + window + 1).playChirp)
    }

    @Test
    fun `reset restarts the audible window for the next outage`() {
        val window = 10 * 60 * 1000L
        val t = ReconnectNotificationTracker(alertAfterAttempts = 3, audibleUntilMs = window)
        t.onAttemptFailed(t0)
        assertFalse(t.onAttemptFailed(t0 + window).playChirp) // gone quiet
        t.reset() // reconnected
        // A brand-new outage an hour later is audible again from its own
        // start — staleness must not carry across outages.
        assertTrue(t.onAttemptFailed(t0 + 60 * 60 * 1000L).playChirp)
    }

    @Test
    fun `default audible window is ten minutes`() {
        assertEquals(10 * 60 * 1000L, ReconnectNotificationTracker.DEFAULT_AUDIBLE_UNTIL_MS)
    }

    // ---- recovery chime ----

    @Test
    fun `recovery chime fires when reconnecting after a real outage`() {
        val t = ReconnectNotificationTracker(alertAfterAttempts = 3)
        t.onAttemptFailed(t0)
        assertTrue("we were down and now we're up", t.onReconnected())
    }

    @Test
    fun `no recovery chime on a first-time connect`() {
        val t = ReconnectNotificationTracker(alertAfterAttempts = 3)
        // Nothing was broken, so nothing was fixed — normal startup must
        // not chime at the operator.
        assertFalse(t.onReconnected())
    }

    @Test
    fun `no recovery chime twice for one outage`() {
        val t = ReconnectNotificationTracker(alertAfterAttempts = 3)
        t.onAttemptFailed(t0)
        assertTrue(t.onReconnected())
        // A duplicate onConnected (the transport can re-fire it on a
        // channel re-sync) must not re-chime.
        assertFalse(t.onReconnected())
    }

    @Test
    fun `recovery chime fires even for a blip that never reached the alert`() {
        val t = ReconnectNotificationTracker(alertAfterAttempts = 3)
        t.onAttemptFailed(t0) // one silent failure, healed immediately
        // The drop was real even though it never earned the loud alert;
        // the chime is the "you're back" confirmation for it.
        assertTrue(t.onReconnected())
    }

    @Test
    fun `onReconnected re-arms the tracker like reset`() {
        val t = ReconnectNotificationTracker(alertAfterAttempts = 3)
        repeat(3) { t.onAttemptFailed(t0) } // alerted
        t.onReconnected()
        // Fresh outage escalates on its own schedule again.
        assertFalse(t.onAttemptFailed(t0).playAlert)
        assertFalse(t.onAttemptFailed(t0).playAlert)
        assertTrue(t.onAttemptFailed(t0).playAlert)
    }
}
