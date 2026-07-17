package com.atakmap.android.xv.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log

// PCM capture for TX. Reads fixed-size frames from AudioRecord and pushes
// them to a callback for downstream encoding.
//
// AudioSource.VOICE_COMMUNICATION:
//   - When SCO is up (ScoLink connected, AINA APTT or similar HFP-only BT
//     selected as comm device): mic comes from the BT speakermic.
//   - When no SCO: mic comes from the phone's built-in voice mic.
//   - Either way, the platform's tuned voice DSP (AEC + NS + AGC) runs on
//     the capture path automatically — that is the whole reason this
//     source is used instead of AudioSource.MIC. We deliberately do NOT
//     layer an app-owned audiofx AEC/NS/AGC stage on top of it; see the
//     comment at [start] for why that was the source of the garbled
//     start-of-transmission audio.
//
// Threading: a dedicated read thread blocks in AudioRecord.read; frames
// are delivered to onFrame on that thread. Caller is responsible for
// marshalling if the consumer isn't thread-safe.
@SuppressLint("MissingPermission")
class AudioCapture(
    private val context: Context? = null,
    private val sampleRateHz: Int = 48_000,
    channels: Int = 1,
    private val frameSamples: Int = 480, // 10ms @ 48kHz (matching Mumla)
    private val onFrame: (ShortArray) -> Unit,
    // Fires when AudioRecord allocation / startRecording fails. Reason
    // is a short operator-actionable token (e.g. "RECORD_AUDIO permission
    // revoked", "AudioRecord init failed (state=0)"). Default no-op for
    // backwards compatibility with callsites that don't yet surface the
    // failure to the UI. TxController wires this to a clear logcat
    // marker for field-debug; future plumbing can route to a Toast via
    // the AIDL listener. Audit H5.
    private val onCaptureError: (reason: String) -> Unit = {},
    // Fires with the OS-assigned AudioRecord session id after
    // startRecording() succeeds, and again with null when capture stops
    // or fails after allocation. VoicePlant stores the current value so
    // AudioPlayback can attach its AudioTrack to the same session via
    // AudioTrack.Builder.setSessionId(id). Capture uses AudioSource
    // .VOICE_COMMUNICATION, so the platform's own voice DSP (AEC + NS +
    // AGC) runs against this session; keeping capture and playback on one
    // shared session id gives that platform effect chain a coherent
    // uplink/downlink pair — the peer's voice played out on the shared
    // session is what the platform AEC subtracts from the mic input, so
    // the co-located speakermic case still works without any app-owned
    // effect. Default no-op keeps the constructor usable in tests /
    // historical callsites that don't route through VoicePlant.
    private val onSessionIdChanged: (Int?) -> Unit = {},
) {
    private val channelMask =
        if (channels == 1) AudioFormat.CHANNEL_IN_MONO else AudioFormat.CHANNEL_IN_STEREO

    private val minBufferBytes =
        AudioRecord
            .getMinBufferSize(sampleRateHz, channelMask, AudioFormat.ENCODING_PCM_16BIT)
            .coerceAtLeast(frameSamples * 2 * 8) // ≥ 8 frames for 10ms (more headroom)

    @Volatile
    private var record: AudioRecord? = null

    /** AudioRecord audio-session id while the capture is alive, or 0 when
     *  inactive. Exposed so TxController can filter system-wide
     *  AudioRecordingConfiguration listings: any config whose
     *  clientAudioSessionId differs from ours belongs to another app
     *  (Google Assistant, dialer, voice recorder, …) — TxController
     *  yields the warm mic when it sees one. */
    val activeSessionId: Int
        get() = record?.audioSessionId ?: 0

    @Volatile
    private var thread: Thread? = null

    @Volatile
    private var running: Boolean = false

    fun start() {
        if (running) return
        val r =
            try {
                AudioRecord
                    .Builder()
                    // VOICE_COMMUNICATION pulls in the OEM voice DSP path
                    // (AEC + NS + AGC) on its own. That single platform
                    // chain is the entire pre-processing story now — no
                    // app-owned audiofx effects are layered on top.
                    .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
                    .setAudioFormat(
                        AudioFormat
                            .Builder()
                            .setSampleRate(sampleRateHz)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setChannelMask(channelMask)
                            .build(),
                    ).setBufferSizeInBytes(minBufferBytes)
                    .build()
            } catch (t: SecurityException) {
                // Most common cause: RECORD_AUDIO was granted at install
                // time but later revoked by the operator in system
                // Settings → Apps → Permissions. AudioRecord constructor
                // throws SecurityException. Previously logged as a
                // generic "AudioRecord build failed" — operator hit a
                // silent PTT bonk with no log signature. Audit H5.
                Log.e(TAG, "AudioRecord build threw SecurityException — RECORD_AUDIO permission revoked", t)
                onCaptureError("RECORD_AUDIO permission revoked — re-grant in system Settings")
                return
            } catch (t: Throwable) {
                Log.e(TAG, "AudioRecord build failed", t)
                onCaptureError("AudioRecord init failed: ${t.message ?: t.javaClass.simpleName}")
                return
            }
        if (r.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord not initialized (state=${r.state}) — likely permission or audio-routing issue")
            r.release()
            onCaptureError(
                "AudioRecord init failed (state=${r.state}) — check RECORD_AUDIO permission and BT routing",
            )
            return
        }

        // Pick the input device so routing is settled and logged. No
        // app-level audiofx effects are attached here on purpose:
        // AudioSource.VOICE_COMMUNICATION already routes through the
        // platform's tuned voice DSP (AEC + NS + AGC). The plugin used to
        // create a SECOND, app-owned AcousticEchoCanceler / NoiseSuppressor
        // / AutomaticGainControl on the same session and enable them per
        // route. That layer was the root cause of the "first couple
        // seconds are garbled" bug two ways over: (1) every app effect is
        // an adaptive stage that restarts its convergence on each fresh
        // AudioRecord, so a new burst spent its first ~1-2 s with the
        // app AEC/AGC still settling on top of the already-converged
        // platform chain, and (2) the effect handles were never retained,
        // so the GC finalized and released them at nondeterministic times,
        // tearing the DSP down mid-stream ("sometimes the first one is
        // completely garbled"). Trusting the single platform chain removes
        // both failure modes and the double-processing entirely.
        val pickedType = applyPreferredInputDevice(r)

        record = r
        running = true
        try {
            r.startRecording()
        } catch (t: SecurityException) {
            Log.e(TAG, "AudioRecord.startRecording threw SecurityException — RECORD_AUDIO permission revoked", t)
            onCaptureError("RECORD_AUDIO permission revoked — re-grant in system Settings")
            stop()
            return
        } catch (t: Throwable) {
            Log.e(TAG, "AudioRecord.startRecording threw", t)
            onCaptureError("AudioRecord startRecording failed: ${t.message ?: t.javaClass.simpleName}")
            stop()
            return
        }
        thread =
            Thread({ runReadLoop() }, "xv-mic-capture").also { it.start() }
        // Bubble the OS-assigned capture session id up to the plant so
        // AudioPlayback can bind its AudioTrack to the same session
        // (setSessionId on AudioTrack). Capture + playback sharing one
        // session keeps the platform VOICE_COMMUNICATION voice DSP chain
        // operating on a coherent uplink/downlink pair, which is what lets
        // the platform AEC subtract the peer's played-out voice from the
        // mic. Session ids are not sensitive per CLAUDE.md — log
        // unredacted for field debug.
        val sid = r.audioSessionId
        Log.i(TAG, "capture session id=$sid — shared with playback")
        try {
            onSessionIdChanged(sid)
        } catch (t: Throwable) {
            Log.w(TAG, "onSessionIdChanged(start) threw", t)
        }
        // The actually-routed input device may differ from the requested
        // preferredDevice — the OS picks routing per its policy. Log the
        // routed device so silent-mic bugs are diagnosable: if we asked
        // for BLUETOOTH_SCO but routedDevice is BUILTIN_MIC, the SCO mic
        // wasn't available at start time and the OS fell back to phone
        // mic (which on a phone in MODE_IN_COMMUNICATION may itself be
        // suppressed, producing silent capture).
        val routed =
            try {
                r.routedDevice
            } catch (t: Throwable) {
                null
            }
        Log.i(
            TAG,
            "AudioRecord started (rate=$sampleRateHz frame=$frameSamples " +
                "pickedType=${typeName(pickedType)} preferred=${r.preferredDevice?.productName} " +
                "routed=${routed?.productName}/${routed?.let { typeName(it.type) }})",
        )
    }

    // Returns the AudioDeviceInfo.TYPE_* of the picked input, or
    // TYPE_UNKNOWN if we left routing to the OS default. Purely
    // informational now — the route is logged for field debug, but no DSP
    // policy branches on it any more: the platform voice DSP handles every
    // route uniformly, so there is nothing per-device to decide.
    private fun applyPreferredInputDevice(r: AudioRecord): Int {
        val ctx = context ?: return AudioDeviceInfo.TYPE_UNKNOWN
        val am =
            ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                ?: return AudioDeviceInfo.TYPE_UNKNOWN
        val inputs =
            try {
                am.getDevices(AudioManager.GET_DEVICES_INPUTS)
            } catch (t: Throwable) {
                Log.w(TAG, "getDevices(INPUTS) failed", t)
                return AudioDeviceInfo.TYPE_UNKNOWN
            }
        Log.i(TAG, "available input devices: ${inputs.size}")
        for (d in inputs) {
            Log.i(TAG, "  device: ${d.productName} type=${typeName(d.type)} address=${d.address}")
        }
        // BT SCO is the only "BT input" that exists at the OS level — the
        // earlier A2DP fallback was nonsense (A2DP is an output profile).
        // If SCO isn't up, leave the OS to pick (built-in / wired).
        val sco = inputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
        if (sco != null) {
            Log.i(TAG, "preferring BT SCO input: ${sco.productName}")
            try {
                r.preferredDevice = sco
                return AudioDeviceInfo.TYPE_BLUETOOTH_SCO
            } catch (t: Throwable) {
                Log.w(TAG, "setPreferredDevice(SCO) failed — falling back to default", t)
            }
        }
        // Identify the default route purely for the field-debug log.
        // Wired headset mic shows up as TYPE_WIRED_HEADSET; otherwise
        // treat as built-in.
        val wired =
            inputs.firstOrNull {
                it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                    it.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                    it.type == AudioDeviceInfo.TYPE_USB_DEVICE
            }
        if (wired != null) {
            Log.i(TAG, "default input looks wired (${wired.productName})")
            return wired.type
        }
        return AudioDeviceInfo.TYPE_BUILTIN_MIC
    }

    fun stop() {
        running = false
        thread?.interrupt()
        thread = null
        record?.let {
            try {
                it.stop()
            } catch (_: IllegalStateException) {
            }
            try {
                it.release()
            } catch (_: Throwable) {
            }
        }
        record = null
        // Clear the plant's cached session id so a subsequent RX before
        // the next TX doesn't try to attach an AudioTrack to a released
        // AudioRecord session. Idempotent — plant just holds null.
        try {
            onSessionIdChanged(null)
        } catch (t: Throwable) {
            Log.w(TAG, "onSessionIdChanged(stop) threw", t)
        }
        Log.i(TAG, "AudioRecord stopped")
    }

    private fun runReadLoop() {
        val buf = ShortArray(frameSamples)
        val r = record ?: return
        Log.i(TAG, "AudioCapture read loop started: frameSamples=$frameSamples sampleRate=$sampleRateHz")
        var frameCount = 0
        var lastRms = 0
        while (running) {
            val n =
                try {
                    r.read(buf, 0, buf.size, AudioRecord.READ_BLOCKING)
                } catch (t: Throwable) {
                    if (running) Log.w(TAG, "AudioRecord.read threw", t)
                    -1
                }
            if (n <= 0) {
                if (frameCount == 0) {
                    Log.w(TAG, "AudioRecord.read returned $n on first read — no audio?")
                }
                break
            }
            frameCount++

            // Diagnostic: compute RMS and show first few samples
            var sumSq = 0.0
            for (i in 0 until n) {
                val v = buf[i].toDouble()
                sumSq += v * v
            }
            val rms = kotlin.math.sqrt(sumSq / n).toInt()
            lastRms = rms

            // Log first few frames and then every 30th frame
            if (frameCount <= 3 || frameCount % 30 == 0) {
                val firstSamples = buf.take(5).joinToString(",")
                Log.i(TAG, "AudioCapture frame #$frameCount: n=$n rms=$rms samples=[$firstSamples]")
            }

            if (n < frameSamples) {
                // Partial frame — let the encoder pad / handle. For Opus
                // we want fixed-size frames, so pad with zeros and pass on.
                val out = ShortArray(frameSamples)
                System.arraycopy(buf, 0, out, 0, n)
                onFrame(out)
            } else {
                onFrame(buf.copyOf(n))
            }
        }
    }

    // NOTE: there is deliberately no configureAudioEffects() here any
    // more. Capture relies entirely on the platform voice DSP pulled in
    // by AudioSource.VOICE_COMMUNICATION (see the class header and
    // [start]). The previous app-owned AEC/NS/AGC layer — and its
    // per-device AGC allowlist — was removed because its per-session
    // re-convergence and unretained (GC-released) effect handles were the
    // root cause of the garbled start-of-transmission audio.

    private fun typeName(type: Int): String =
        when (type) {
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "BLUETOOTH_SCO"
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "BLUETOOTH_A2DP"
            AudioDeviceInfo.TYPE_BUILTIN_MIC -> "BUILTIN_MIC"
            AudioDeviceInfo.TYPE_WIRED_HEADSET -> "WIRED_HEADSET"
            AudioDeviceInfo.TYPE_USB_HEADSET -> "USB_HEADSET"
            AudioDeviceInfo.TYPE_USB_DEVICE -> "USB_DEVICE"
            AudioDeviceInfo.TYPE_UNKNOWN -> "UNKNOWN"
            else -> "type=$type"
        }

    companion object {
        private const val TAG = "XvAudioCapture"
    }
}
