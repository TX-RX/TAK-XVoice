package com.atakmap.android.xv.audio

import android.content.Context
import android.media.AudioAttributes
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale

// PTTB1-driven audio-output cycler for multi-BT setups. Use case:
// operator wearing an AINA speakermic + paired car BT for audio
// playback while in vehicle. Steps out of the car for traffic
// direction (or jumps to earpiece-style privacy for people in the
// car) and wants to hand off audio to the speakermic on their body
// without unpairing anything in Settings. One tap on PTTB1 advances
// the AUDIO DEVICE override through the connected BT audio devices
// + back to "Auto" (= follow PTT input / no override). A long press
// jumps straight to Auto so the operator can recover from cycle
// confusion without re-tapping through every option.
//
// Each advance announces the new target via Android TTS so the
// operator gets eyes-up confirmation: "Local speaker" when on Auto
// (= the AINA speakermic / built-in output), or the BT device's
// product name when on a specific remote BT output.
//
// Why TTS over pre-recorded WAVs: avoids shipping a binary asset per
// supported language AND it scales to arbitrarily-named car BT
// devices ("Sync Bluetooth", "F-150", whatever the operator paired)
// without us pre-baking the names. The first-invocation latency
// problem is mitigated by pre-warming on construction — by the time
// the operator taps PTTB1 for the first time, the TTS engine has
// fully initialized.
//
// Why USAGE_VOICE_COMMUNICATION on the TTS audio attributes: this
// puts the announcement on the same audio policy track as TPT /
// peer voice, so it lands on whichever comm device was just set by
// the override. Without the explicit usage, TTS defaults to
// USAGE_ASSISTANCE_ACCESSIBILITY which routes to STREAM_MUSIC and
// misses the comm path entirely — the operator wouldn't hear it
// through the AINA in MODE_IN_COMMUNICATION.
class AudioOutputCycler(
    private val context: Context,
    private val router: AudioRouter,
    // Callback to apply the new override. Wired to
    // VoicePlant.setOutputBtOverride so the comm-device change and
    // the SCO-state recompute happen on the same path the UI picker
    // uses — no parallel mechanism.
    private val setOverride: (String?) -> Unit,
    // Plays the "nothing to cycle to" bonk when there's only one
    // effective audio output target (e.g. only the AINA speakermic
    // is paired — toggling Auto↔AINA-MAC both land on the same
    // physical device, no actual route change happens).
    private val tptPlayer: TptPlayer? = null,
) {
    @Volatile private var tts: TextToSpeech? = null

    @Volatile private var ttsReady: Boolean = false

    @Volatile private var pttB1DownAtMs: Long = 0L

    // Tracks the long-press deadline so that if the operator HELDS
    // PTTB1 past the threshold we can fire the jump-to-Auto exactly
    // once on the release edge — without waiting for the release we'd
    // miss the cycle UX of "see what's selected at release time."
    private val mainHandler = Handler(Looper.getMainLooper())

    fun start() {
        if (tts != null) return
        try {
            tts =
                TextToSpeech(context) { status ->
                    if (status != TextToSpeech.SUCCESS) {
                        Log.w(TAG, "TTS init failed status=$status — cycler will be silent")
                        return@TextToSpeech
                    }
                    val engine = tts ?: return@TextToSpeech
                    try {
                        engine.language = Locale.US
                    } catch (t: Throwable) {
                        Log.w(TAG, "TTS setLanguage threw", t)
                    }
                    try {
                        engine.setAudioAttributes(
                            AudioAttributes
                                .Builder()
                                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                .build(),
                        )
                    } catch (t: Throwable) {
                        Log.w(TAG, "TTS setAudioAttributes threw", t)
                    }
                    ttsReady = true
                    Log.i(TAG, "TTS ready — output cycler announcements armed")
                    // Pre-warm with an empty speak so the FIRST real
                    // announcement isn't swallowed by Android's
                    // lazy-load-on-first-utterance behavior. Mute via
                    // QUEUE_FLUSH + empty string — the engine still
                    // initializes its synthesis path without making
                    // a sound.
                    try {
                        engine.speak("", TextToSpeech.QUEUE_FLUSH, null, "xv-cycler-warmup")
                    } catch (t: Throwable) {
                        Log.w(TAG, "TTS warmup speak threw", t)
                    }
                }
        } catch (t: Throwable) {
            Log.w(TAG, "TextToSpeech construction threw — cycler will be silent", t)
            tts = null
        }
    }

    fun stop() {
        ttsReady = false
        mainHandler.removeCallbacksAndMessages(null)
        try {
            tts?.stop()
            tts?.shutdown()
        } catch (_: Throwable) {
        }
        tts = null
    }

    /** PTTB1 went down. Records the press time so [onPttB1Up] can
     *  decide tap-vs-long-press at release. */
    fun onPttB1Down() {
        pttB1DownAtMs = System.currentTimeMillis()
    }

    /** PTTB1 released. If the press was longer than [LONG_PRESS_MS],
     *  jump to Auto (= no override). Otherwise advance one step
     *  through the available BT outputs. Both branches announce
     *  the new target via TTS. */
    fun onPttB1Up() {
        val held = System.currentTimeMillis() - pttB1DownAtMs
        pttB1DownAtMs = 0L
        if (held >= LONG_PRESS_MS) {
            // Long-press: jump to Auto regardless of cycle. If Auto
            // is already the effective state AND there's only one
            // effective option, the operator gets the same bonk +
            // "no other audio device" hint as a tap would — avoids
            // the surprise of "I held the button and nothing
            // happened, with no audible feedback."
            Log.i(TAG, "PTTB1 long press (${held}ms) → jump to Auto (local speaker)")
            val outputs = router.availableBtOutputs()
            val currentResolved =
                router.outputBtOverrideMac ?: router.preferredBtMacHint
            val targetResolved = router.preferredBtMacHint
            val onlyOneEffective =
                outputs.isEmpty() ||
                    (outputs.size == 1 && outputs.first().address.equals(targetResolved, ignoreCase = true))
            val alreadyOnTarget =
                if (currentResolved == null) {
                    targetResolved == null
                } else {
                    currentResolved.equals(targetResolved, ignoreCase = true)
                }
            if (onlyOneEffective && alreadyOnTarget) {
                tptPlayer?.playBonk(useScoRoute = false)
                mainHandler.removeCallbacksAndMessages(null)
                mainHandler.postDelayed({ speak(ANNOUNCE_NO_OTHER) }, ANNOUNCE_AFTER_BONK_MS)
                return
            }
            applyAndAnnounce(targetMac = null, label = ANNOUNCE_LOCAL)
        } else {
            advance()
        }
    }

    private fun advance() {
        // Build the cycle as DISTINCT EFFECTIVE OUTPUT TARGETS, not
        // raw override values. The override has a quirk that breaks
        // the naive cycle: `null` (= Auto, follow hint) and the
        // AINA-MAC override both resolve to the AINA when AINA is
        // the current PTT-input pick — so toggling between them is
        // a no-op the operator perceives as "the button isn't doing
        // anything." Dedupe by collapsing all entries that share a
        // target MAC into one. With only the AINA paired, the cycle
        // is just [AINA] — size 1 — and we bonk instead.
        val outputs = router.availableBtOutputs()
        val hint = router.preferredBtMacHint
        val current = router.outputBtOverrideMac
        // The Auto entry effectively targets the BT hint (= the AINA
        // pick) when there's one; if there's no hint, Auto resolves
        // to the built-in route and we still represent it as a
        // distinct cycle position so the operator can switch off any
        // BT-override pin.
        val autoTargetMac = hint
        val effectiveTargets = mutableListOf<EffectiveTarget>()
        // Auto first (so the long-press / fresh cycle lands on it
        // first naturally). Only include if the resulting target
        // differs from any BT output that's also in the list — the
        // dedupe pass below collapses those collisions.
        effectiveTargets += EffectiveTarget(overrideMac = null, resolvedMac = autoTargetMac, label = ANNOUNCE_LOCAL)
        for (o in outputs) {
            val label =
                o.displayName.ifBlank { ANNOUNCE_REMOTE_GENERIC }
            effectiveTargets += EffectiveTarget(overrideMac = o.address, resolvedMac = o.address, label = label)
        }
        val distinct = dedupeByResolvedMac(effectiveTargets)
        if (distinct.size <= 1) {
            Log.i(TAG, "PTTB1 tap: only one effective output (${distinct.firstOrNull()?.label}) — bonk, no cycle")
            tptPlayer?.playBonk(useScoRoute = false)
            // Brief TTS hint so the operator hears WHY nothing
            // happened — silence-after-bonk is confusing the first
            // time it occurs.
            mainHandler.removeCallbacksAndMessages(null)
            mainHandler.postDelayed({ speak(ANNOUNCE_NO_OTHER) }, ANNOUNCE_AFTER_BONK_MS)
            return
        }
        // Locate the current position by resolved-MAC equivalence so
        // a stale override that matches Auto's resolved MAC still
        // lands on the right index.
        val currentResolved =
            if (current.isNullOrBlank()) autoTargetMac else current
        val currentIdx =
            distinct.indexOfFirst {
                if (it.resolvedMac == null) currentResolved == null else it.resolvedMac.equals(currentResolved, ignoreCase = true)
            }.coerceAtLeast(0)
        val nextIdx = (currentIdx + 1) % distinct.size
        val next = distinct[nextIdx]
        Log.i(
            TAG,
            "PTTB1 tap: cycle $currentIdx→$nextIdx target=${next.overrideMac ?: "Auto"} " +
                "resolved=${next.resolvedMac} label=${next.label}",
        )
        applyAndAnnounce(targetMac = next.overrideMac, label = next.label)
    }

    /** Keep the FIRST occurrence of each resolved MAC so the cycle
     *  order matches the operator's mental model: Auto first, then
     *  the picker's bonded order. A second entry resolving to the
     *  same physical output is dropped silently. */
    private fun dedupeByResolvedMac(entries: List<EffectiveTarget>): List<EffectiveTarget> {
        val seen = mutableSetOf<String>()
        val out = mutableListOf<EffectiveTarget>()
        for (e in entries) {
            // Null-resolved = no BT routing target (built-in path).
            // Use a sentinel string so null collisions still dedupe.
            val key = e.resolvedMac?.uppercase() ?: "<built-in>"
            if (seen.add(key)) out += e
        }
        return out
    }

    private data class EffectiveTarget(
        val overrideMac: String?,
        val resolvedMac: String?,
        val label: String,
    )

    private fun applyAndAnnounce(
        targetMac: String?,
        label: String,
    ) {
        try {
            setOverride(targetMac)
        } catch (t: Throwable) {
            Log.w(TAG, "setOverride threw", t)
        }
        // Small delay before announcing so the comm-device swap can
        // settle. setCommunicationDevice is synchronous but the
        // routing-policy update can lag 50-100ms on some chipsets —
        // an immediate TTS speak can land on the OLD device. 150ms
        // is below the operator's reaction threshold and comfortably
        // past the routing window.
        mainHandler.removeCallbacksAndMessages(null)
        mainHandler.postDelayed({ speak(label) }, ANNOUNCE_DELAY_MS)
    }

    private fun speak(text: String) {
        if (!ttsReady) {
            Log.w(TAG, "speak('$text') skipped — TTS not ready")
            return
        }
        val engine = tts ?: return
        try {
            engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "xv-cycler-${System.nanoTime()}")
        } catch (t: Throwable) {
            Log.w(TAG, "TTS speak threw", t)
        }
    }

    companion object {
        private const val TAG = "XvOutputCycler"

        // Threshold for tap-vs-long-press. Sized so a deliberate
        // "hold the button to reset" gesture is clearly distinct
        // from a quick cycle tap; 800 ms matches the AINA APTT
        // spec's own long-press window for PTT-derived gestures.
        const val LONG_PRESS_MS = 800L

        // Wait between override-apply and TTS speak — see
        // [applyAndAnnounce] for why.
        private const val ANNOUNCE_DELAY_MS = 150L

        // Announcement strings. "Local speaker" for the
        // AINA-speakermic / built-in fallback case (= no override);
        // device-specific product name otherwise. Generic fallback
        // when the BT device has no product name (rare — some car
        // kits report blank). "No other audio device" is the bonk
        // case — only one effective output is paired, cycle is a
        // no-op.
        private const val ANNOUNCE_LOCAL = "Local speaker"
        private const val ANNOUNCE_REMOTE_GENERIC = "Remote speaker"
        private const val ANNOUNCE_NO_OTHER = "No other audio device"

        // Delay between the bonk tone and the TTS hint. Bonk runs
        // ~120ms; we want the TTS to start AFTER the tone clears so
        // the operator hears both unambiguously.
        private const val ANNOUNCE_AFTER_BONK_MS = 250L
    }
}
