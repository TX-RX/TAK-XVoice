package com.atakmap.android.xv.audio

import android.util.Log

// PTTB2-driven microphone-input toggle. Decouples mic-source choice
// from audio-output choice so the operator can run combinations the
// single-cycle output model couldn't express — e.g. listen on the
// AINA speakermic (output) but capture from the phone's built-in
// mic (input) when the AINA's mic element is broken or muffled, or
// when ambient noise on the AINA mic is too high for clean voice.
//
// Two states:
//
//   AUTO        Default. TxController follows its existing BT-policy
//               logic — when BtAudioPolicy.classify() reports
//               HFP_ONLY, SCO is acquired on the speakermic's HFP
//               profile and the operator transmits through it. When
//               no HFP-capable device is present, the phone built-in
//               mic is used. This is the pre-toggle behavior.
//
//   FORCE_PHONE TxController bypasses the SCO acquire branch
//               unconditionally on the next PTT-down. Capture lands
//               on the phone's built-in mic regardless of which BT
//               device is connected for output. Output routing is
//               unaffected — the operator still hears on whichever
//               output the cycler / picker / chain selected.
//
// State is in-memory only (not persisted) — the operator's expected
// mental model is "I tapped a button, now I'm on the phone mic;
// when I reboot the plugin, I'm back to Auto." That avoids the
// surprise of returning to a session with a non-default mic source
// they don't remember setting.
//
// Audio feedback: a bonk on every transition so the operator hears
// the toggle registered. (Different tones per state would be
// clearer but require new TptPlayer signatures — deferred to the
// pre-recorded-WAV iteration that will follow.)
class MicInputToggle(
    private val tptPlayer: TptPlayer,
) {
    enum class Mode { AUTO, FORCE_PHONE }

    @Volatile
    var mode: Mode = Mode.AUTO
        private set

    @Volatile private var pttB2DownAtMs: Long = 0L

    /** TxController consults this on every PTT-down to decide whether
     *  to skip the SCO acquire path. Returns true iff the operator
     *  has toggled FORCE_PHONE. */
    fun shouldForcePhoneMic(): Boolean = mode == Mode.FORCE_PHONE

    fun onPttB2Down() {
        pttB2DownAtMs = System.currentTimeMillis()
    }

    fun onPttB2Up() {
        val held = System.currentTimeMillis() - pttB2DownAtMs
        pttB2DownAtMs = 0L
        if (held >= LONG_PRESS_MS) {
            // Long-press = reset to Auto, regardless of current state.
            // Same "reset gesture" as PTTB1's long-press jump-to-Auto.
            if (mode == Mode.AUTO) {
                Log.i(TAG, "PTTB2 long-press: already AUTO — bonk")
                tptPlayer.playBonk(useScoRoute = false)
                return
            }
            Log.i(TAG, "PTTB2 long-press: $mode → AUTO")
            mode = Mode.AUTO
            tptPlayer.playInterrupt(useScoRoute = false) {}
            return
        }
        // Short press = toggle.
        val newMode =
            when (mode) {
                Mode.AUTO -> Mode.FORCE_PHONE
                Mode.FORCE_PHONE -> Mode.AUTO
            }
        Log.i(TAG, "PTTB2 tap: $mode → $newMode")
        mode = newMode
        tptPlayer.playInterrupt(useScoRoute = false) {}
    }

    companion object {
        private const val TAG = "XvMicInputToggle"
        const val LONG_PRESS_MS = 800L
    }
}
