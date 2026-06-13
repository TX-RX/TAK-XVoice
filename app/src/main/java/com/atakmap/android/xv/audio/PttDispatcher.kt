package com.atakmap.android.xv.audio

import android.os.Handler
import android.os.Looper
import android.util.Log

// Central PTT dispatch: every input source (AINA V1 SPP, AINA V2 BLE,
// HID/keycode, settings UI button, debug intent) calls down() / up()
// here instead of touching TxController directly. Keeping latch state,
// timeout handling, and pre-cutoff warning in ONE place — versus
// duplicating in every input-driver file — is the "centralize this
// voice feature stuff" the user asked for. Adding a new input source
// is now a one-liner that calls this class; latched-mode toggle and
// timeout config flow through automatically.
//
// The class is callable from any thread (button events come off the
// BLE / SPP / UI threads). The handler runs all timer callbacks on
// the main thread, which matches what the underlying TxController
// expects.
class PttDispatcher(
    private val txController: TxController,
    private val statusTones: StatusTones?,
    // Returns true if latched mode is currently enabled. Read every
    // dispatch — operator can toggle it mid-session.
    private val latchedModeEnabled: () -> Boolean,
    // Returns the configured PTT timeout in seconds for momentary mode
    // (0 = never). Read on each dispatch.
    private val momentaryTimeoutSec: () -> Int,
    // Returns the configured PTT timeout in seconds for latched mode
    // (0 = never).
    private val latchedTimeoutSec: () -> Int,
    // Plays the TX-timeout cutoff tone. Optional: only services
    // that own a TptPlayer wire it; tests pass null. The tone is a
    // one-shot via USAGE_VOICE_COMMUNICATION (same path as TPT,
    // bonk, deny) — the audio policy is in MODE_IN_COMMUNICATION
    // for the active Telecom call so USAGE_MEDIA would be ducked.
    // Mixes with any peer voice on the SCO mixer when SCO is up.
    private val tptPlayer: TptPlayer? = null,
    // Returns true when SCO is the live audio path for TX — i.e. the
    // BT speakermic will hear tones routed via USAGE_VOICE_COMMUNICATION
    // with a BT_SCO device pin. Used to gate the timeout pre-cutoff
    // warning chirp's route: when SCO is up, play it on the speakermic
    // so the operator hears the warning where they're actually
    // listening; when SCO is down, fall back to the phone-speaker
    // path. Defaults to false so existing tests don't have to wire
    // it.
    private val txOnSco: () -> Boolean = { false },
) {
    private val handler = Handler(Looper.getMainLooper())

    @Volatile
    private var latchedActive: Boolean = false

    @Volatile
    private var latchedSlot: Int = 0

    private val warnRunnable =
        Runnable {
            val sco = txOnSco()
            Log.i(TAG, "TX timeout warning ($TX_TIMEOUT_WARN_LEAD_MS ms before cutoff, useScoRoute=$sco)")
            statusTones?.play(StatusToneKind.WARNING_VOICE_LOST, useScoRoute = sco)
        }

    private val cutoffRunnable =
        Runnable {
            Log.w(TAG, "TX timeout — auto-releasing")
            // Play the cutoff tone FIRST (before txController.stop())
            // so it lands on the same voice-comm mixer as the in-flight
            // TX. Self-completes after ~1.2 s. Mixes naturally with
            // any peer voice the operator is currently hearing — both
            // tracks share USAGE_VOICE_COMMUNICATION.
            tptPlayer?.playTimeoutCutoff()
            // Software-release: clear our latched flag and end the TX
            // burst. The operator's PTT button may still be physically
            // down; that's fine — they have to release and press again
            // for a fresh TX cycle (acknowledging the cutoff). down()
            // and up() handle that naturally because they only act on
            // edges, not on held state.
            latchedActive = false
            txController.stop()
        }

    /** PTT button went down on [slot]. Slot 0 = primary (VS1), 1 = secondary (VS2). */
    fun down(slot: Int) {
        if (!latchedModeEnabled()) {
            txController.start(slot = slot)
            scheduleTimeout(latched = false)
            return
        }
        if (latchedActive) {
            Log.i(TAG, "latched PTT: second press → release (was slot=$latchedSlot)")
            latchedActive = false
            cancelTimeout()
            txController.stop()
            return
        }
        Log.i(TAG, "latched PTT: first press → engage (slot=$slot)")
        latchedActive = true
        latchedSlot = slot
        txController.start(slot = slot)
        scheduleTimeout(latched = true)
    }

    /** PTT button released on [slot]. No-op in latched mode. */
    fun up(slot: Int) {
        if (latchedModeEnabled()) return
        cancelTimeout()
        txController.stop()
    }

    /** True while latched mode is engaged and TX is active. */
    fun isLatched(): Boolean = latchedActive

    /**
     * Force-release any active TX — latched OR momentary. Used when an
     * external party tears down the audio session under us (Telecom
     * preemption by an incoming phone call, system audio focus loss,
     * plugin teardown). Previously we gated on `latchedActive`, which
     * left a momentary TX in flight stuck in TRANSMITTING after
     * external teardown — mic data kept streaming into the channel
     * during the cellular call. Always force-stop now; idempotent
     * (TxController.stop is a no-op when state==IDLE).
     */
    fun release() {
        cancelTimeout()
        tptPlayer?.stopTimeoutCutoff()
        latchedActive = false
        txController.stop()
    }

    private fun scheduleTimeout(latched: Boolean) {
        cancelTimeout()
        val timeoutSec = if (latched) latchedTimeoutSec() else momentaryTimeoutSec()
        if (timeoutSec <= 0) return
        val cutoffMs = timeoutSec * 1000L
        val warnMs = (cutoffMs - TX_TIMEOUT_WARN_LEAD_MS).coerceAtLeast(0)
        if (warnMs > 0) handler.postDelayed(warnRunnable, warnMs)
        handler.postDelayed(cutoffRunnable, cutoffMs)
    }

    private fun cancelTimeout() {
        handler.removeCallbacks(warnRunnable)
        handler.removeCallbacks(cutoffRunnable)
    }

    companion object {
        private const val TAG = "XvPttDispatch"

        // Lead time on the pre-cutoff warning chirp. Sized for the new
        // PTT-timeout floor of 20 s (at 5 s lead, the warning lands
        // 15 s into a held PTT — still plenty of reaction time without
        // overlapping the cutoff).
        const val TX_TIMEOUT_WARN_LEAD_MS: Long = 5_000L
    }
}
