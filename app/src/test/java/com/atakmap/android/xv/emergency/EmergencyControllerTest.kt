package com.atakmap.android.xv.emergency

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-Kotlin coverage for the LMR-style emergency-button state machine.
 *
 * The controller is field-critical and intentionally takes Clock +
 * DelayScheduler + EmergencyDispatcher as interfaces so we can drive
 * the timing deterministically without standing up Robolectric. The
 * UX spec it implements:
 *
 *   Short press / release (under threshold) → FIRE
 *   Long hold past threshold → CANCEL at the threshold (not on release)
 *   Release after long-hold cancel → no-op (already cancelled)
 *   Stuck/snagged button → safe, cancel fires automatically
 *   Release with no prior press → ignored
 *
 * Regression-target: a release at exactly the threshold counts as
 * cancel, not fire. The boundary case matters because a real operator
 * pressing-and-holding for "just over a second" intends to cancel a
 * stuck beacon, not start a new one.
 */
class EmergencyControllerTest {
    /** Driveable fake clock. Test code advances `now`. */
    private class FakeClock(var now: Long = 0L) : Clock {
        override fun nowMillis(): Long = now
    }

    /** Driveable fake scheduler. Records scheduled tasks; test fires them
     *  manually to simulate the threshold being reached. */
    private class FakeScheduler : DelayScheduler {
        data class Pending(val delayMs: Long, val task: Runnable)
        var pending: Pending? = null
            private set
        var cancellations: Int = 0
            private set

        override fun schedule(
            delayMillis: Long,
            task: Runnable,
        ): Any {
            pending = Pending(delayMillis, task)
            return task
        }

        override fun cancel(handle: Any) {
            if (pending?.task === handle) pending = null
            cancellations++
        }

        fun fire() {
            pending?.task?.run()
            // Note: production code clears pending when it cancels via
            // cancelPending(); we let the scheduled task itself run
            // here, so the test must mirror whatever the controller
            // would do (no auto-clear).
        }
    }

    private class RecordingDispatcher : EmergencyDispatcher {
        var firePanicCalls = 0
            private set
        var cancelEmergencyCalls = 0
            private set
        var lastReason: String? = null
            private set

        override fun firePanic(reason: String) {
            firePanicCalls++
            lastReason = reason
        }

        override fun cancelEmergency(reason: String) {
            cancelEmergencyCalls++
            lastReason = reason
        }
    }

    private fun build(
        thresholdMs: Long = 1_000L,
    ): Triple<EmergencyController, FakeClock, RecordingDispatcher> {
        val clock = FakeClock()
        val sched = FakeScheduler()
        val disp = RecordingDispatcher()
        val ctl =
            EmergencyController(
                dispatcher = disp,
                clock = clock,
                scheduler = sched,
                longPressThresholdMs = thresholdMs,
            )
        return Triple(ctl, clock, disp).also { _ -> }
    }

    private fun buildWithScheduler(
        thresholdMs: Long = 1_000L,
    ): Quadruple<EmergencyController, FakeClock, FakeScheduler, RecordingDispatcher> {
        val clock = FakeClock()
        val sched = FakeScheduler()
        val disp = RecordingDispatcher()
        val ctl =
            EmergencyController(
                dispatcher = disp,
                clock = clock,
                scheduler = sched,
                longPressThresholdMs = thresholdMs,
            )
        return Quadruple(ctl, clock, sched, disp)
    }

    private data class Quadruple<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)

    // ============================================================
    // FIRE path — short press
    // ============================================================

    @Test
    fun `short press releases below threshold and fires panic`() {
        val (ctl, clock, disp) = build()
        ctl.onEmergencyButton(isDown = true)
        clock.now = 500L
        ctl.onEmergencyButton(isDown = false)
        assertEquals("firePanic must run once", 1, disp.firePanicCalls)
        assertEquals(0, disp.cancelEmergencyCalls)
    }

    @Test
    fun `instant press-release (0 ms held) fires panic`() {
        // Edge case: a flick of the button. Anything below threshold
        // is FIRE, including 0 ms.
        val (ctl, _, disp) = build()
        ctl.onEmergencyButton(isDown = true)
        ctl.onEmergencyButton(isDown = false)
        assertEquals(1, disp.firePanicCalls)
        assertEquals(0, disp.cancelEmergencyCalls)
    }

    // ============================================================
    // CANCEL path — long hold past threshold
    // ============================================================

    @Test
    fun `long-hold threshold task fires CANCEL`() {
        val (ctl, clock, sched, disp) = buildWithScheduler()
        ctl.onEmergencyButton(isDown = true)
        // Scheduler was given the threshold-task; advance the clock and fire it.
        clock.now = 1_000L
        sched.fire()
        assertEquals("cancelEmergency must run when threshold task fires", 1, disp.cancelEmergencyCalls)
        assertEquals("no fire — long hold cancels, not fires", 0, disp.firePanicCalls)
    }

    @Test
    fun `release after long-hold cancel is a no-op`() {
        val (ctl, clock, sched, disp) = buildWithScheduler()
        ctl.onEmergencyButton(isDown = true)
        clock.now = 1_000L
        sched.fire() // long-hold cancel fired
        // Now release.
        clock.now = 2_000L
        ctl.onEmergencyButton(isDown = false)
        // Still exactly one cancel — release after long-hold did NOT
        // fire again.
        assertEquals(1, disp.cancelEmergencyCalls)
        assertEquals(0, disp.firePanicCalls)
    }

    @Test
    fun `release exactly at threshold (after scheduler delay) counts as cancel`() {
        // Edge case: operator holds for exactly the threshold time and
        // releases. The scheduled task may not have fired yet (race
        // between clock and scheduler) — we must still treat this as
        // a cancel, not a fire. The check is `held >= threshold`.
        val (ctl, clock, disp) = build(thresholdMs = 1_000L)
        ctl.onEmergencyButton(isDown = true)
        clock.now = 1_000L
        ctl.onEmergencyButton(isDown = false)
        assertEquals(0, disp.firePanicCalls)
        assertEquals(1, disp.cancelEmergencyCalls)
    }

    @Test
    fun `release one ms before threshold fires panic`() {
        val (ctl, clock, disp) = build(thresholdMs = 1_000L)
        ctl.onEmergencyButton(isDown = true)
        clock.now = 999L
        ctl.onEmergencyButton(isDown = false)
        assertEquals(1, disp.firePanicCalls)
        assertEquals(0, disp.cancelEmergencyCalls)
    }

    // ============================================================
    // Scheduler interaction
    // ============================================================

    @Test
    fun `down schedules a threshold task with the configured delay`() {
        val (_, _, sched, _) = buildWithScheduler(thresholdMs = 750L)
        val (ctl, _, _, _) = buildWithScheduler(thresholdMs = 750L)
        ctl.onEmergencyButton(isDown = true)
        // Re-run with the controller backed by `sched`.
        val (ctl2, _, sched2, _) = buildWithScheduler(thresholdMs = 750L)
        ctl2.onEmergencyButton(isDown = true)
        assertEquals(750L, sched2.pending?.delayMs)
    }

    @Test
    fun `release before threshold cancels the pending scheduler task`() {
        val (ctl, clock, sched, disp) = buildWithScheduler()
        ctl.onEmergencyButton(isDown = true)
        assertEquals(true, sched.pending != null)
        clock.now = 500L
        ctl.onEmergencyButton(isDown = false)
        // After release, pending must be cleared so the scheduled task
        // can't fire later.
        assertNull("scheduler task must be cancelled on early release", sched.pending)
        // And the cancel + fire counts are correct.
        assertEquals(1, disp.firePanicCalls)
        assertEquals(0, disp.cancelEmergencyCalls)
    }

    @Test
    fun `repeated down without release cancels the previous task and re-arms`() {
        // Defensive: a second down without an intervening up shouldn't
        // leave two scheduled tasks racing. The new press cancels the
        // previous schedule and starts fresh.
        val (ctl, _, sched, _) = buildWithScheduler()
        ctl.onEmergencyButton(isDown = true)
        val firstPending = sched.pending
        ctl.onEmergencyButton(isDown = true)
        val secondPending = sched.pending
        assertTrue("second down must reschedule, not skip", firstPending !== secondPending)
        // Cancellation count incremented (production calls cancelPending on every down).
        assertTrue(sched.cancellations >= 1)
    }

    // ============================================================
    // Defensive — release with no prior press
    // ============================================================

    @Test
    fun `release with no armed press is ignored`() {
        val (ctl, _, disp) = build()
        ctl.onEmergencyButton(isDown = false)
        assertEquals(0, disp.firePanicCalls)
        assertEquals(0, disp.cancelEmergencyCalls)
    }

    @Test
    fun `release after a release is ignored (armed flag cleared)`() {
        val (ctl, clock, disp) = build()
        ctl.onEmergencyButton(isDown = true)
        clock.now = 200L
        ctl.onEmergencyButton(isDown = false) // fires
        clock.now = 300L
        ctl.onEmergencyButton(isDown = false) // ignored
        assertEquals(1, disp.firePanicCalls)
        assertEquals(0, disp.cancelEmergencyCalls)
    }

    @Test
    fun `idempotent — long-hold fires CANCEL only once even if scheduler somehow fires twice`() {
        val (ctl, _, sched, disp) = buildWithScheduler()
        ctl.onEmergencyButton(isDown = true)
        sched.fire()
        // Simulate a buggy scheduler firing the same task twice. The
        // controller guards via longHoldFired flag.
        sched.pending?.task?.run() // no-op now that pending was cleared
        assertEquals(1, disp.cancelEmergencyCalls)
    }

    // ============================================================
    // DEFAULT_LONG_PRESS_MS sanity pin
    // ============================================================

    @Test
    fun `DEFAULT_LONG_PRESS_MS is 1 second`() {
        // Pinned to the documented UX value; a future change here
        // affects every operator's muscle memory.
        assertEquals(1_000L, EmergencyController.DEFAULT_LONG_PRESS_MS)
    }

    @Test
    fun `default scheduling uses DEFAULT_LONG_PRESS_MS when no override given`() {
        val sched = FakeScheduler()
        val ctl =
            EmergencyController(
                dispatcher = RecordingDispatcher(),
                clock = FakeClock(),
                scheduler = sched,
                // longPressThresholdMs omitted → uses DEFAULT
            )
        ctl.onEmergencyButton(isDown = true)
        assertEquals(EmergencyController.DEFAULT_LONG_PRESS_MS, sched.pending?.delayMs)
    }
}
