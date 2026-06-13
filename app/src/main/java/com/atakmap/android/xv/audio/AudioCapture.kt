package com.atakmap.android.xv.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.util.Log

// PCM capture for TX. Reads fixed-size frames from AudioRecord and pushes
// them to a callback for downstream encoding.
//
// AudioSource.VOICE_COMMUNICATION:
//   - When SCO is up (ScoLink connected, AINA APTT or similar HFP-only BT
//     selected as comm device): mic comes from the BT speakermic.
//   - When no SCO: mic comes from the phone's built-in voice mic with AEC
//     and noise suppression applied (which is what we want for voice).
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
                    // VOICE_COMMUNICATION pulls in the OEM voice DSP path on
                    // its own — the explicit Android audiofx effects below
                    // are layered on top per route.
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

        // Pick the input device first so routing is settled before the
        // DSP policy decides which Android effects to enable.
        val picked = applyPreferredInputDevice(r)
        configureAudioEffects(r, picked.type, picked.device)

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
                "pickedType=${typeName(picked.type)} preferred=${r.preferredDevice?.productName} " +
                "routed=${routed?.productName}/${routed?.let { typeName(it.type) }})",
        )
    }

    // Returns the AudioDeviceInfo.TYPE_* of the picked input, or
    // TYPE_UNKNOWN if we left routing to the OS default. Knowing the
    // route is what lets configureAudioEffects below pick a sane DSP
    // policy: AINA / Pryme / generic HFP speakermics ship strong
    // vendor DSP and don't want Android stomping on it; the built-in
    // mic does.
    /** Result of [applyPreferredInputDevice] — device type plus optional
     *  AudioDeviceInfo so [configureAudioEffects] can apply per-device
     *  policy (e.g. AGC allowlist by productName). */
    private data class PickedInput(val type: Int, val device: AudioDeviceInfo?)

    private fun applyPreferredInputDevice(r: AudioRecord): PickedInput {
        val ctx = context ?: return PickedInput(AudioDeviceInfo.TYPE_UNKNOWN, null)
        val am =
            ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                ?: return PickedInput(AudioDeviceInfo.TYPE_UNKNOWN, null)
        val inputs =
            try {
                am.getDevices(AudioManager.GET_DEVICES_INPUTS)
            } catch (t: Throwable) {
                Log.w(TAG, "getDevices(INPUTS) failed", t)
                return PickedInput(AudioDeviceInfo.TYPE_UNKNOWN, null)
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
                return PickedInput(AudioDeviceInfo.TYPE_BLUETOOTH_SCO, sco)
            } catch (t: Throwable) {
                Log.w(TAG, "setPreferredDevice(SCO) failed — falling back to default", t)
            }
        }
        // Identify the default route so the DSP policy below has
        // something to decide on. Wired headset mic shows up as
        // TYPE_WIRED_HEADSET; otherwise treat as built-in.
        val wired =
            inputs.firstOrNull {
                it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                    it.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                    it.type == AudioDeviceInfo.TYPE_USB_DEVICE
            }
        if (wired != null) {
            Log.i(TAG, "default input looks wired (${wired.productName})")
            return PickedInput(wired.type, wired)
        }
        return PickedInput(AudioDeviceInfo.TYPE_BUILTIN_MIC, null)
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

    // DSP policy. AEC and NS are enabled universally — the user wants
    // belt-and-suspenders cleanup across both internal and external PTT
    // devices. Even on AINA / Pryme speakermics with vendor DSP, the
    // Android effects layer on top without obvious double-processing
    // artifacts in field testing.
    //
    // AGC is route + device aware (audit M10):
    //   - Built-in / wired: AGC on. Level varies widely with how the
    //     operator holds the phone or where they wear the headset mic.
    //   - BT SCO + KNOWN_GOOD_DSP_DEVICE (AINA APTT family): AGC off.
    //     Vendor DSP already normalizes, and stacking Android's AGC on
    //     top causes audible pump on PTT bursts.
    //   - BT SCO + unknown device: AGC on. Generic headsets without
    //     vendor DSP need the Android-side AGC to avoid the operator
    //     sounding inaudible or clipped depending on how loud they
    //     speak. Was previously "AGC off for ALL BT SCO" which broke
    //     generic HFP headsets — operators on cheap BT earpieces had
    //     to nearly shout.
    private fun configureAudioEffects(
        record: AudioRecord,
        pickedType: Int,
        pickedDevice: AudioDeviceInfo?,
    ) {
        val sessionId = record.audioSessionId
        val isBtSpeakermic = pickedType == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
        val hasKnownGoodDsp = isBtSpeakermic && isKnownGoodBtDspDevice(pickedDevice)
        val aecOn = true
        val nsOn = true
        val agcOn = !hasKnownGoodDsp

        Log.i(
            TAG,
            "DSP policy: route=${typeName(pickedType)} device='${pickedDevice?.productName ?: "?"}' " +
                "knownGoodDsp=$hasKnownGoodDsp AEC=$aecOn NS=$nsOn AGC=$agcOn",
        )

        applyEffect("AEC", AcousticEchoCanceler.isAvailable(), aecOn) {
            AcousticEchoCanceler.create(sessionId)?.also { it.enabled = aecOn }
        }
        applyEffect("NS", NoiseSuppressor.isAvailable(), nsOn) {
            NoiseSuppressor.create(sessionId)?.also { it.enabled = nsOn }
        }
        applyEffect("AGC", AutomaticGainControl.isAvailable(), agcOn) {
            AutomaticGainControl.create(sessionId)?.also { it.enabled = agcOn }
        }
    }

    private inline fun applyEffect(
        tag: String,
        available: Boolean,
        enable: Boolean,
        create: () -> Any?,
    ) {
        if (!available) {
            Log.i(TAG, "$tag not available on this device")
            return
        }
        try {
            val eff = create()
            if (eff == null) {
                Log.w(TAG, "$tag create() returned null")
                return
            }
            Log.i(TAG, "$tag ${if (enable) "ENABLED" else "disabled"}")
        } catch (e: Throwable) {
            Log.w(TAG, "$tag setup failed", e)
        }
    }

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

    /** True when the BT input is from a device whose vendor DSP we
     *  know handles AEC + AGC + NS competently, so Android-side AGC
     *  should be SUPPRESSED to avoid double-processing pump. Default
     *  for unknown BT devices is `false` (treat as generic HFP
     *  headset, AGC ON). Match against productName because the BT
     *  address varies per unit but the model name doesn't. */
    private fun isKnownGoodBtDspDevice(device: AudioDeviceInfo?): Boolean {
        if (device == null) return false
        val name =
            try {
                device.productName?.toString() ?: ""
            } catch (_: Throwable) {
                return false
            }
        return KNOWN_GOOD_BT_DSP_PREFIXES.any { name.startsWith(it, ignoreCase = true) }
    }

    companion object {
        private const val TAG = "XvAudioCapture"

        /** ProductName prefixes for BT speakermics whose vendor DSP
         *  is known to do its own AEC/AGC/NS at firmware level. AGC
         *  is suppressed for these; everything else (generic HFP) gets
         *  Android's AGC enabled. Add new entries as field testing
         *  identifies more devices with their own DSP. Audit M10. */
        private val KNOWN_GOOD_BT_DSP_PREFIXES =
            listOf(
                "APTT", // AINA V1 / V2 family
                "AINA",
            )
    }
}
