package com.atakmap.android.xv.audio

import android.os.Handler
import android.os.Looper
import android.util.Log

// PTTB1-driven audio-output cycler. Each short press advances
// `outputBtOverrideMac` through the currently-CONNECTED BT audio
// outputs plus Auto, skipping any cycle entries that would resolve
// to the same physical device the cycle already lands on (Auto + an
// explicit MAC override that points at the AINA = one effective
// target, not two). When the deduped cycle has only one effective
// option, the press plays a bonk — there's nothing to switch to —
// and the operator gets immediate audible feedback that the press
// registered but the routing didn't change.
//
// A long press (>= LONG_PRESS_MS) jumps to Auto regardless of cycle
// position. With only one effective option AND already on Auto, a
// long press also bonks — same "you pressed it, but nothing
// changed" semantics as the no-op tap.
//
// Audio feedback is intentionally NON-SPEECH: a short interrupt
// chirp on a successful route change, a bonk on the no-op. The
// prior implementation used Android TextToSpeech for spoken
// "Local speaker / Remote speaker" announcements; init-from-a-
// service combined with the empty-string warmup speak call wedged
// the XV foreground-service process under live mic-focus
// conditions, producing a TTS-client-process-death → service-
// restart → AINA-flap → Mumble-reconnect loop in the field
// (observed 2026-06-19 19:52 in the regression trace). The tone-
// only path uses the same TptPlayer pipeline that already drives
// TPT / deny / bonk; no new audio focus management, no service-
// lifetime binding to a system TTS engine, no risk of repeating
// that failure mode.
class AudioOutputCycler(
    private val router: AudioRouter,
    private val setOverride: (String?) -> Unit,
    // Used for both the "no-op" bonk and the "route changed" chirp.
    // Required (not optional) so we never silently fall through to
    // no audio feedback at all — silent presses are the worst UX
    // outcome and the operator would think the button is broken.
    private val tptPlayer: TptPlayer,
) {
    @Volatile private var pttB1DownAtMs: Long = 0L

    // Used for deferring the chirp until after the comm-device swap
    // has settled, mirroring the 150 ms safety window from v1.
    private val mainHandler = Handler(Looper.getMainLooper())

    fun stop() {
        mainHandler.removeCallbacksAndMessages(null)
    }

    fun onPttB1Down() {
        pttB1DownAtMs = System.currentTimeMillis()
    }

    fun onPttB1Up() {
        val held = System.currentTimeMillis() - pttB1DownAtMs
        pttB1DownAtMs = 0L
        if (held >= LONG_PRESS_MS) {
            Log.i(TAG, "PTTB1 long-press (${held}ms) → jump to Auto")
            jumpToAuto()
        } else {
            advance()
        }
    }

    private fun advance() {
        val distinct = buildDistinctCycle()
        if (distinct.size <= 1) {
            Log.i(TAG, "PTTB1 tap: only ${distinct.size} effective output target — bonk")
            tptPlayer.playBonk(useScoRoute = false)
            return
        }
        val current = router.outputBtOverrideMac
        val hint = router.preferredBtMacHint
        val currentResolved = current ?: hint
        val currentIdx =
            distinct
                .indexOfFirst {
                    if (it.resolvedMac == null) currentResolved == null else it.resolvedMac.equals(currentResolved, ignoreCase = true)
                }.coerceAtLeast(0)
        val nextIdx = (currentIdx + 1) % distinct.size
        val next = distinct[nextIdx]
        Log.i(
            TAG,
            "PTTB1 tap: cycle $currentIdx→$nextIdx target=${next.overrideMac ?: "Auto"} resolved=${next.resolvedMac}",
        )
        apply(next.overrideMac)
    }

    private fun jumpToAuto() {
        val distinct = buildDistinctCycle()
        if (distinct.size <= 1) {
            // Only one effective option AND that one option IS Auto
            // (= we're already there) → no-op.
            tptPlayer.playBonk(useScoRoute = false)
            return
        }
        apply(targetMac = null)
    }

    private fun apply(targetMac: String?) {
        try {
            setOverride(targetMac)
        } catch (t: Throwable) {
            Log.w(TAG, "setOverride threw", t)
        }
        // Defer the "route changed" chirp 150 ms so the comm-device
        // routing settles before the chirp plays — without the
        // delay, the chirp can land on the OLD device.
        mainHandler.removeCallbacksAndMessages(null)
        mainHandler.postDelayed(
            { tptPlayer.playInterrupt(useScoRoute = false) {} },
            CHIRP_DELAY_MS,
        )
    }

    /** Build the deduped cycle: Auto + each connected BT output, with
     *  entries that resolve to the same physical device collapsed
     *  into one. Order: Auto first (so a fresh cycle / power-up
     *  state lands here naturally), then BT outputs in
     *  router-reported order. */
    private fun buildDistinctCycle(): List<EffectiveTarget> {
        val outputs = router.availableBtOutputs()
        val hint = router.preferredBtMacHint
        val raw = mutableListOf<EffectiveTarget>()
        raw += EffectiveTarget(overrideMac = null, resolvedMac = hint)
        for (o in outputs) {
            raw += EffectiveTarget(overrideMac = o.address, resolvedMac = o.address)
        }
        val seen = mutableSetOf<String>()
        val out = mutableListOf<EffectiveTarget>()
        for (e in raw) {
            val key = e.resolvedMac?.uppercase() ?: "<built-in>"
            if (seen.add(key)) out += e
        }
        return out
    }

    private data class EffectiveTarget(
        val overrideMac: String?,
        val resolvedMac: String?,
    )

    companion object {
        private const val TAG = "XvOutputCycler"

        // Threshold for tap-vs-long-press. Matches AINA APTT spec's
        // long-press window.
        const val LONG_PRESS_MS = 800L

        // Delay between override-apply and route-change chirp. See
        // [apply] for why.
        private const val CHIRP_DELAY_MS = 150L
    }
}
