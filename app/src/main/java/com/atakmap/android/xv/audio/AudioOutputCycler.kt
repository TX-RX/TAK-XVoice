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
            Log.i(TAG, "PTTB1 long press (${held}ms) → jump to Auto (local speaker)")
            applyAndAnnounce(targetMac = null, label = ANNOUNCE_LOCAL)
        } else {
            advance()
        }
    }

    private fun advance() {
        val outputs = router.availableBtOutputs()
        val cycle: List<String?> = listOf(null) + outputs.map { it.address }
        val current = router.outputBtOverrideMac
        val currentIdx =
            cycle.indexOfFirst {
                if (it == null) current == null else it.equals(current, ignoreCase = true)
            }.coerceAtLeast(0)
        val nextIdx = (currentIdx + 1) % cycle.size
        val nextMac = cycle[nextIdx]
        val label =
            if (nextMac == null) {
                ANNOUNCE_LOCAL
            } else {
                outputs
                    .firstOrNull { it.address.equals(nextMac, ignoreCase = true) }
                    ?.displayName
                    ?.ifBlank { ANNOUNCE_REMOTE_GENERIC }
                    ?: ANNOUNCE_REMOTE_GENERIC
            }
        Log.i(TAG, "PTTB1 tap: cycle $currentIdx→$nextIdx mac=$nextMac label=$label")
        applyAndAnnounce(targetMac = nextMac, label = label)
    }

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
        // kits report blank).
        private const val ANNOUNCE_LOCAL = "Local speaker"
        private const val ANNOUNCE_REMOTE_GENERIC = "Remote speaker"
    }
}
