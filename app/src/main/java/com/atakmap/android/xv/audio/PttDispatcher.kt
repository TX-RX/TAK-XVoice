package com.atakmap.android.xv.audio

import android.os.Handler
import android.os.Looper
import android.util.Log

// Central PTT dispatch: every input source (AINA V1 SPP, AINA V2 BLE,
// HID/keycode, settings UI button, debug intent, secondary AINA on a
// motorcyclist's handlebar) calls down() / up() here instead of
// touching TxController directly. Keeping latch state, timeout
// handling, pre-cutoff warning, and the multi-source OR-gate in ONE
// place — versus duplicating in every input-driver file — is the
// "centralize this voice feature stuff" the user asked for. Adding a
// new input source is now a one-liner that calls this class; latched-
// mode toggle and timeout config flow through automatically.
//
// OR-gate semantics for the multi-PTT case: TX engages on the FIRST
// down() across any source and remains engaged until the LAST held
// source releases. A second down() while another source is already
// held is recorded as held but does NOT start a fresh TX (which would
// re-PRIME the mic and cut off the in-flight burst). An up() from any
// source clears that source from the held set; the burst only ends
// when the set becomes empty. The owner-source field tracks which
// input started the burst so the TxController's trailing-frame-trim
// (a click-suppression for hardware-button release pops) is applied
// only when the LAST releasing source actually has a trailing click —
// the on-screen PTT card has no physical click and shouldn't lose its
// last frame.
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

    // Set of sources currently holding the PTT down. TX is engaged
    // iff this set is non-empty (momentary mode); in latched mode it
    // tracks held sources for forgetSource() bookkeeping but TX
    // engagement is driven by latchedActive instead.
    private val heldButtons: MutableSet<PttSource> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    // The source that ENGAGED the current burst — used by the trailing-
    // click-trim decision when the burst finally ends. The first
    // down() across any source becomes the burst owner; subsequent
    // down()s add to heldButtons but do not change ownership.
    @Volatile
    private var burstOwnerSource: PttSource? = null

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
            heldButtons.clear()
            burstOwnerSource = null
            txController.stop()
        }

    /** PTT button went down on [slot]. Slot 0 = primary (VS1), 1 = secondary (VS2).
     *  Back-compat overload — assumes the caller didn't supply a source. */
    fun down(slot: Int) {
        down(slot, PttSource.DEFAULT)
    }

    /** PTT button went down on [slot] from [source]. */
    fun down(
        slot: Int,
        source: PttSource,
    ) {
        // Latched-mode toggle-off MUST be evaluated BEFORE the OR-gate
        // wasEmpty check. Otherwise: latched press 1 starts TX and
        // leaves latchedActive=true; up() is a no-op in latched mode
        // so the held set is empty afterwards. Latched press 2 then
        // looks like "first down — start fresh TX" to the OR-gate and
        // mis-routes — the toggle-off path never runs. Hoisting the
        // latched-active check here is the only correct order.
        if (latchedModeEnabled() && latchedActive) {
            Log.i(TAG, "latched PTT: second press → release (was slot=$latchedSlot)")
            latchedActive = false
            heldButtons.clear()
            burstOwnerSource = null
            cancelTimeout()
            txController.stop()
            return
        }
        val wasEmpty = heldButtons.isEmpty()
        heldButtons.add(source)
        if (!wasEmpty && !latchedModeEnabled()) {
            // Concurrent press from a second source — TX is already
            // in flight. Record the held source so up() bookkeeping is
            // correct, but do NOT re-start TX (that would re-PRIME
            // the mic and cut the in-flight burst). Owner doesn't
            // change either — first press wins the burst.
            Log.i(TAG, "PTT held by ${burstOwnerSource ?: "?"}; ignoring concurrent down($source, slot=$slot)")
            return
        }
        if (!latchedModeEnabled()) {
            // Momentary first-down: engage TX, take ownership.
            burstOwnerSource = source
            txController.start(slot = slot)
            scheduleTimeout(latched = false)
            return
        }
        // Latched first-press (we already short-circuited the toggle-
        // off case above).
        Log.i(TAG, "latched PTT: first press → engage (slot=$slot, source=$source)")
        latchedActive = true
        latchedSlot = slot
        burstOwnerSource = source
        txController.start(slot = slot)
        scheduleTimeout(latched = true)
    }

    /** PTT button released on [slot]. No-op in latched mode.
     *  Back-compat overload — clears every held source so callers
     *  unaware of multi-source don't strand a half-pressed entry. */
    fun up(slot: Int) {
        up(slot, source = null)
    }

    /** PTT button released on [slot] from [source]. No-op in latched mode.
     *  Burst only ends when the LAST held source releases — concurrent
     *  presses keep TX engaged until they're all up. If [source] is null,
     *  every held source is cleared (matches the pre-multi-source one-
     *  source-at-a-time semantics for callers that don't track sources). */
    fun up(
        slot: Int,
        source: PttSource?,
    ) {
        if (latchedModeEnabled()) {
            // Latched bookkeeping: drop the source so a forgetSource()
            // from a disconnect path doesn't see a stale entry, but
            // don't change TX state.
            if (source == null) heldButtons.clear() else heldButtons.remove(source)
            return
        }
        if (source == null) {
            heldButtons.clear()
        } else {
            heldButtons.remove(source)
        }
        if (heldButtons.isNotEmpty()) {
            Log.i(TAG, "up(slot=$slot, $source) — others still held: $heldButtons (owner=$burstOwnerSource)")
            return
        }
        // Last source released — end the burst.
        burstOwnerSource = null
        cancelTimeout()
        txController.stop()
    }

    /** True while latched mode is engaged and TX is active. */
    fun isLatched(): Boolean = latchedActive

    /**
     * Drop [source] from the held set without touching TX state.
     * Called from the disconnect path on AINA / Pryme readers so a
     * mid-burst BT disconnect doesn't leave the source in
     * heldButtons forever — which would prevent the OR-gate from
     * ever seeing "no sources held" and the burst would never end.
     * Idempotent: safe to call for a source not in the set.
     */
    fun forgetSource(source: PttSource) {
        if (heldButtons.remove(source)) {
            Log.i(TAG, "forgetSource($source) — held={$heldButtons} owner=$burstOwnerSource")
        }
        if (heldButtons.isEmpty() && burstOwnerSource != null && !latchedActive) {
            // The forgotten source was the only thing keeping the
            // burst alive — end it. Use a synthetic up() to land
            // through the normal release path.
            burstOwnerSource = null
            cancelTimeout()
            txController.stop()
        }
    }

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
        heldButtons.clear()
        burstOwnerSource = null
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
