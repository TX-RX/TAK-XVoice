package com.atakmap.android.xv.audio

import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * Coverage for PttDispatcher — the central PTT input dispatch that all
 * sources (AINA V1 SPP, V2 BLE, HID, settings UI, debug intent) route
 * through. State surface: momentary vs latched, per-slot, with optional
 * timeout + pre-cutoff warning.
 *
 * Robolectric is required because PttDispatcher posts timer callbacks
 * to the main looper via `android.os.Handler`. Tests advance the
 * Robolectric main looper by exact ms to deterministically exercise
 * warn / cutoff edges.
 */
@RunWith(RobolectricTestRunner::class)
class PttDispatcherTest {
    private lateinit var txController: TxController
    private lateinit var statusTones: StatusTones
    private lateinit var tptPlayer: TptPlayer

    private var latchedMode: Boolean = false
    private var momentaryTimeoutSec: Int = 0
    private var latchedTimeoutSec: Int = 0

    private fun buildDispatcher(): PttDispatcher =
        PttDispatcher(
            txController = txController,
            statusTones = statusTones,
            latchedModeEnabled = { latchedMode },
            momentaryTimeoutSec = { momentaryTimeoutSec },
            latchedTimeoutSec = { latchedTimeoutSec },
            tptPlayer = tptPlayer,
        )

    @Before
    fun setup() {
        txController = mockk(relaxed = true)
        statusTones = mockk(relaxed = true)
        tptPlayer = mockk(relaxed = true)
        latchedMode = false
        momentaryTimeoutSec = 0
        latchedTimeoutSec = 0
    }

    private fun advanceMs(ms: Long) {
        shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(ms))
    }

    // ============================================================
    // Momentary mode — down/up edge → start/stop
    // ============================================================

    @Test
    fun `momentary mode — down starts TX on the requested slot`() {
        val d = buildDispatcher()
        d.down(slot = 0)
        verify(exactly = 1) { txController.start(slot = 0) }
        assertFalse("momentary down does not engage latched flag", d.isLatched())
    }

    @Test
    fun `momentary mode — up stops TX`() {
        val d = buildDispatcher()
        d.down(0)
        d.up(0)
        verify(exactly = 1) { txController.stop() }
    }

    @Test
    fun `momentary mode — secondary slot down forwards slot=1`() {
        val d = buildDispatcher()
        d.down(slot = 1)
        verify(exactly = 1) { txController.start(slot = 1) }
    }

    // ============================================================
    // Latched mode — down=engage / down=release toggle
    // ============================================================

    @Test
    fun `latched mode — first down engages, isLatched flips true`() {
        latchedMode = true
        val d = buildDispatcher()
        d.down(0)
        verify(exactly = 1) { txController.start(slot = 0) }
        assertTrue("latched flag must be set after engage", d.isLatched())
    }

    @Test
    fun `latched mode — second down releases (not a fresh start)`() {
        latchedMode = true
        val d = buildDispatcher()
        d.down(0) // engage
        d.down(0) // release
        verify(exactly = 1) { txController.start(slot = 0) }
        verify(exactly = 1) { txController.stop() }
        assertFalse("latched flag must be cleared on second down", d.isLatched())
    }

    @Test
    fun `latched mode — up is a no-op (button release does not end TX)`() {
        latchedMode = true
        val d = buildDispatcher()
        d.down(0)
        d.up(0)
        // No stop() — release only on second down.
        verify(exactly = 0) { txController.stop() }
        assertTrue("up does not break latched state", d.isLatched())
    }

    @Test
    fun `latched mode — remembers slot of the engage press`() {
        latchedMode = true
        val d = buildDispatcher()
        d.down(slot = 1)
        verify(exactly = 1) { txController.start(slot = 1) }
        d.down(slot = 1) // release on the latched slot
        verify(exactly = 1) { txController.stop() }
    }

    // ============================================================
    // Timeouts — cutoff + pre-cutoff warning
    // ============================================================

    @Test
    fun `momentary timeout 0 — never schedules a cutoff`() {
        momentaryTimeoutSec = 0
        val d = buildDispatcher()
        d.down(0)
        advanceMs(60_000L) // a minute should be more than any reasonable cutoff
        // No cutoff fired, so no stop() from the runnable.
        verify(exactly = 0) { txController.stop() }
    }

    @Test
    fun `momentary timeout fires cutoff after the configured window`() {
        momentaryTimeoutSec = 20
        val d = buildDispatcher()
        d.down(0)
        // 14_999 ms elapsed: warn fires at (20_000 - 5_000) = 15_000 ms,
        // so we're 1 ms short. Cutoff is at 20_000 ms.
        advanceMs(14_999L)
        verify(exactly = 0) { statusTones.play(StatusToneKind.WARNING_VOICE_LOST) }
        verify(exactly = 0) { txController.stop() }
        // Cross the warn boundary.
        advanceMs(2L)
        verify(exactly = 1) { statusTones.play(StatusToneKind.WARNING_VOICE_LOST) }
        verify(exactly = 0) { txController.stop() }
        // Cross the cutoff boundary.
        advanceMs(5_000L)
        verify(exactly = 1) { txController.stop() }
        verify(exactly = 1) { tptPlayer.playTimeoutCutoff() }
    }

    @Test
    fun `momentary timeout — up before cutoff cancels both runnables`() {
        momentaryTimeoutSec = 20
        val d = buildDispatcher()
        d.down(0)
        // Release before the warn boundary.
        advanceMs(10_000L)
        d.up(0)
        verify(exactly = 1) { txController.stop() }
        // Advance past the would-be warn + cutoff windows; nothing further fires.
        advanceMs(60_000L)
        verify(exactly = 0) { statusTones.play(StatusToneKind.WARNING_VOICE_LOST) }
        // Total stop() count stays at 1.
        verify(exactly = 1) { txController.stop() }
    }

    @Test
    fun `latched timeout fires cutoff and clears latched flag`() {
        latchedMode = true
        latchedTimeoutSec = 20
        val d = buildDispatcher()
        d.down(0)
        assertTrue(d.isLatched())
        advanceMs(20_001L)
        verify(exactly = 1) { txController.stop() }
        assertFalse("cutoff must clear the latched flag", d.isLatched())
    }

    // ============================================================
    // External force-release (Telecom preemption, focus loss, etc)
    // ============================================================

    @Test
    fun `release stops TX whether or not latched`() {
        // Non-latched momentary TX in flight.
        momentaryTimeoutSec = 20
        val d = buildDispatcher()
        d.down(0)
        d.release()
        verify(exactly = 1) { txController.stop() }
        // No more timer-driven stop() should fire after release cancelled timers.
        advanceMs(60_000L)
        verify(exactly = 1) { txController.stop() }
    }

    @Test
    fun `release clears the latched flag and stops timeout cutoff playback`() {
        latchedMode = true
        latchedTimeoutSec = 20
        val d = buildDispatcher()
        d.down(0)
        assertTrue(d.isLatched())
        d.release()
        assertFalse(d.isLatched())
        verify(exactly = 1) { tptPlayer.stopTimeoutCutoff() }
        verify(exactly = 1) { txController.stop() }
    }

    @Test
    fun `release is idempotent — calling twice is safe`() {
        val d = buildDispatcher()
        d.down(0)
        d.release()
        d.release() // second call must not throw
        // stop() called once per release (TxController itself is no-op at IDLE)
        verify(exactly = 2) { txController.stop() }
    }

    // ============================================================
    // OR-gate — multiple input sources held simultaneously
    // ============================================================

    @Test
    fun `OR-gate — second source down while first held does NOT re-start TX`() {
        val d = buildDispatcher()
        d.down(slot = 0, source = PttSource.AINA_V1)
        d.down(slot = 0, source = PttSource.ON_SCREEN)
        // Only the first down should have started TX. The second is
        // ORed in — it's already on the air; re-starting would re-PRIME
        // the mic and cut the in-flight burst.
        verify(exactly = 1) { txController.start(slot = 0) }
        verify(exactly = 0) { txController.stop() }
    }

    @Test
    fun `OR-gate — releasing first source while second held keeps TX engaged`() {
        val d = buildDispatcher()
        d.down(slot = 0, source = PttSource.AINA_V1)
        d.down(slot = 0, source = PttSource.ON_SCREEN)
        // Release the first source; the second is still held → TX
        // must NOT end. This is the whole point of the OR-gate.
        d.up(slot = 0, source = PttSource.AINA_V1)
        verify(exactly = 0) { txController.stop() }
        // Releasing the second source ends the burst.
        d.up(slot = 0, source = PttSource.ON_SCREEN)
        verify(exactly = 1) { txController.stop() }
    }

    @Test
    fun `OR-gate — releasing all sources via unknown null source ends TX`() {
        // Back-compat path: callers that don't track per-source state
        // (e.g. the call-button auto-release) pass source = null and
        // expect the burst to end. Mirrors the pre-multi-source one-
        // press-at-a-time semantics.
        val d = buildDispatcher()
        d.down(slot = 0, source = PttSource.AINA_V1)
        d.down(slot = 0, source = PttSource.ON_SCREEN)
        d.up(slot = 0) // back-compat overload — clears all
        verify(exactly = 1) { txController.stop() }
    }

    @Test
    fun `OR-gate — forgetSource on the only held source ends TX`() {
        // Mid-burst BT disconnect: the reader's onDisconnected hook
        // forgets its source. With no other source held, the burst
        // must end so the operator isn't left transmitting forever.
        val d = buildDispatcher()
        d.down(slot = 0, source = PttSource.AINA_V1)
        d.forgetSource(PttSource.AINA_V1)
        verify(exactly = 1) { txController.stop() }
    }

    @Test
    fun `OR-gate — forgetSource on one of multiple held sources keeps TX engaged`() {
        // Concurrent burst: the external-button AINA disconnects
        // mid-press while the operator's on-screen PTT is still held.
        // TX must stay engaged on the surviving source.
        val d = buildDispatcher()
        d.down(slot = 0, source = PttSource.AINA_V1)
        d.down(slot = 0, source = PttSource.ON_SCREEN)
        d.forgetSource(PttSource.AINA_V1)
        verify(exactly = 0) { txController.stop() }
        // Releasing the surviving source ends the burst.
        d.up(slot = 0, source = PttSource.ON_SCREEN)
        verify(exactly = 1) { txController.stop() }
    }

    @Test
    fun `OR-gate — same source down twice is idempotent (no double start)`() {
        // The BLE reader can fire spurious down edges on connection
        // resume / characteristic re-read. Two consecutive downs from
        // the same source must not re-PRIME the mic.
        val d = buildDispatcher()
        d.down(slot = 0, source = PttSource.AINA_V2)
        d.down(slot = 0, source = PttSource.AINA_V2)
        verify(exactly = 1) { txController.start(slot = 0) }
    }

    @Test
    fun `OR-gate — latched mode second press from any source toggles off`() {
        // Regression guard: the latched-mode toggle-off path must be
        // evaluated BEFORE the OR-gate wasEmpty check. Otherwise the
        // second press (with held set empty because up() is a no-op
        // in latched mode) looks like a fresh first-down to the gate
        // and mis-routes.
        latchedMode = true
        val d = buildDispatcher()
        d.down(slot = 0, source = PttSource.AINA_V1)
        assertTrue(d.isLatched())
        d.down(slot = 0, source = PttSource.ON_SCREEN) // any source can toggle off
        assertFalse(d.isLatched())
        verify(exactly = 1) { txController.start(slot = 0) }
        verify(exactly = 1) { txController.stop() }
    }

    @Test
    fun `OR-gate — forgetSource clears held bookkeeping even in latched mode`() {
        // In latched mode the burst is held by latchedActive, not by
        // the held-set. forgetSource still needs to clean the entry
        // so a later disconnect/reconnect cycle doesn't leave a stale
        // held source rendering the OR-gate inconsistent.
        latchedMode = true
        val d = buildDispatcher()
        d.down(slot = 0, source = PttSource.AINA_V1)
        d.forgetSource(PttSource.AINA_V1)
        // Burst is still engaged (latched mode owns the engagement
        // state, not the held set).
        assertTrue(d.isLatched())
        verify(exactly = 0) { txController.stop() }
    }
}
