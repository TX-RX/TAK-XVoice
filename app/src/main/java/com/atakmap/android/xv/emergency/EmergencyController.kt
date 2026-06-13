package com.atakmap.android.xv.emergency

import android.util.Log

// LMR-style emergency UX:
//   - PRESS:                     schedule a cancel at threshold; arm "fire on
//                                early release"
//   - RELEASE before threshold:  cancel the pending schedule, FIRE
//   - HELD past threshold:       scheduled task fires CANCEL immediately, so
//                                the user gets feedback at the threshold; the
//                                eventual release is a no-op
//
// The cancel-on-long-hold path is what makes this safe for a dedicated
// emergency button: a stuck/snagged button can't keep firing, and the user
// can affirmatively cancel an active beacon by holding the same button — and
// they know it worked the moment they hit the threshold, not when they
// finally release.
class EmergencyController(
    private val dispatcher: EmergencyDispatcher,
    private val clock: Clock = Clock.System,
    private val scheduler: DelayScheduler = HandlerDelayScheduler(),
    private val longPressThresholdMs: Long = DEFAULT_LONG_PRESS_MS,
) {
    @Volatile
    private var pressedAt: Long = 0L

    @Volatile
    private var armed: Boolean = false

    @Volatile
    private var longHoldFired: Boolean = false

    @Volatile
    private var pendingCancel: Any? = null

    fun onEmergencyButton(isDown: Boolean) {
        if (isDown) {
            pressedAt = clock.nowMillis()
            armed = true
            longHoldFired = false
            cancelPending()
            pendingCancel =
                scheduler.schedule(longPressThresholdMs) {
                    onLongHoldThresholdReached()
                }
            Log.i(TAG, "press armed (threshold ${longPressThresholdMs}ms)")
        } else {
            cancelPending()
            val held = clock.nowMillis() - pressedAt
            if (longHoldFired) {
                Log.i(TAG, "release after long-hold ($held ms) — already cancelled, no-op")
                armed = false
                return
            }
            if (!armed) {
                Log.i(TAG, "release with no armed press — ignored")
                return
            }
            armed = false
            if (held >= longPressThresholdMs) {
                // Edge case: scheduled task hasn't run yet but we're past threshold.
                Log.i(TAG, "release at $held ms (>= threshold) — cancel")
                dispatcher.cancelEmergency("XV emergency long-hold (release)")
            } else {
                Log.i(TAG, "release at $held ms — fire")
                dispatcher.firePanic("XV emergency short-press")
            }
        }
    }

    private fun onLongHoldThresholdReached() {
        if (!armed || longHoldFired) return
        longHoldFired = true
        pendingCancel = null
        Log.i(TAG, "long-hold threshold reached — cancel")
        dispatcher.cancelEmergency("XV emergency long-hold (threshold)")
    }

    private fun cancelPending() {
        pendingCancel?.let { scheduler.cancel(it) }
        pendingCancel = null
    }

    companion object {
        private const val TAG = "XvEmergency"
        const val DEFAULT_LONG_PRESS_MS: Long = 1000L
    }
}
