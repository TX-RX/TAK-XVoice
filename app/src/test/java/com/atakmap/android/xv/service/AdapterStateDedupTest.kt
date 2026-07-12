package com.atakmap.android.xv.service

import android.bluetooth.BluetoothAdapter
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-function coverage for the STATE_TURNING_OFF / STATE_OFF dedup
 * guard extracted from [XvVoiceService.btAdapterStateReceiver].
 *
 * Field bug 2026-07-11 (Samsung Tab5): the same
 * BluetoothAdapter STATE_TURNING_OFF broadcast arrived twice ~116 ms
 * apart, driving two identical releaseAllBtSourcedPtt() calls and two
 * identical WARN lines. Samsung's BT stack is known to double-fire
 * ACTION_STATE_CHANGED for STATE_TURNING_OFF on some builds. The
 * cascade itself is idempotent, so this is a log-noise / operator-
 * triage bug rather than a correctness bug — but the guard is worth
 * pinning so the log surface stays clean.
 *
 * The receiver stores the last observed state + the last time it
 * reacted, and asks [XvVoiceService.shouldReactToAdapterState] whether
 * the new event deserves another cascade. This test exercises the
 * four documented cases from the KDoc.
 */
class AdapterStateDedupTest {
    // Chosen to comfortably bracket the observed 116 ms double-fire
    // gap on Samsung Tab5 while not swallowing a genuine second BT-off
    // event (operators do not toggle BT off twice inside half a second).
    private val thresholdMs: Long = 500L

    /**
     * First observation of any state after service start — no prior
     * state is remembered (sentinel is [BluetoothAdapter.ERROR]). The
     * cascade MUST fire so the initial BT-off is handled.
     */
    @Test
    fun `first observation of any state — react`() {
        val decision =
            XvVoiceService.shouldReactToAdapterState(
                newState = BluetoothAdapter.STATE_TURNING_OFF,
                lastState = BluetoothAdapter.ERROR,
                nowMs = 10_000L,
                lastReactedMs = 0L,
                thresholdMs = thresholdMs,
            )
        assertTrue("first observation must react", decision)
    }

    /**
     * The 2026-07-11 Samsung Tab5 field case in miniature: the same
     * STATE_TURNING_OFF broadcast arrives again 116 ms after the first.
     * The dedup guard must suppress the second reaction so the
     * operator sees exactly one WARN log line and the cascade runs
     * exactly once.
     */
    @Test
    fun `same state within threshold — dedup`() {
        val decision =
            XvVoiceService.shouldReactToAdapterState(
                newState = BluetoothAdapter.STATE_TURNING_OFF,
                lastState = BluetoothAdapter.STATE_TURNING_OFF,
                nowMs = 10_116L,
                lastReactedMs = 10_000L,
                thresholdMs = thresholdMs,
            )
        assertFalse("duplicate within threshold must be dedupped", decision)
    }

    /**
     * A genuinely separate STATE_TURNING_OFF (operator toggled BT off,
     * back on, then off again several seconds later) should NOT be
     * dedupped. Threshold-exceeded repeats are treated as real re-fires.
     */
    @Test
    fun `same state after threshold — react`() {
        val decision =
            XvVoiceService.shouldReactToAdapterState(
                newState = BluetoothAdapter.STATE_TURNING_OFF,
                lastState = BluetoothAdapter.STATE_TURNING_OFF,
                nowMs = 15_000L,
                lastReactedMs = 10_000L,
                thresholdMs = thresholdMs,
            )
        assertTrue("threshold-exceeded re-fire must react", decision)
    }

    /**
     * A real state transition (STATE_TURNING_OFF → STATE_OFF is the
     * normal case) must always react regardless of timing. The two
     * states carry different semantics for downstream cleanup and
     * both deserve their own cascade attempt — the cascade is
     * idempotent, so a redundant call across the transition is
     * harmless while a missed one on the transition edge is not.
     */
    @Test
    fun `different state within threshold — react`() {
        val decision =
            XvVoiceService.shouldReactToAdapterState(
                newState = BluetoothAdapter.STATE_OFF,
                lastState = BluetoothAdapter.STATE_TURNING_OFF,
                nowMs = 10_050L,
                lastReactedMs = 10_000L,
                thresholdMs = thresholdMs,
            )
        assertTrue("state transition must always react", decision)
    }

    /**
     * Boundary case: the delta is exactly the threshold. The guard is
     * inclusive on "after threshold" so an event landing right at the
     * boundary is treated as a real re-fire. Matters for platforms
     * whose double-fire gap could drift close to whatever value we
     * chose — the boundary must land on the safe side (react rather
     * than dedup, so a real event is not silently dropped).
     */
    @Test
    fun `same state at threshold boundary — react`() {
        val decision =
            XvVoiceService.shouldReactToAdapterState(
                newState = BluetoothAdapter.STATE_TURNING_OFF,
                lastState = BluetoothAdapter.STATE_TURNING_OFF,
                nowMs = 10_500L,
                lastReactedMs = 10_000L,
                thresholdMs = thresholdMs,
            )
        assertTrue("delta == threshold must react (react rather than drop)", decision)
    }
}
