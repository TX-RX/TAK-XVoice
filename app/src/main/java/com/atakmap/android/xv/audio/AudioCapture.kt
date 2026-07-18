package com.atakmap.android.xv.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioRouting
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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
// Self-healing (2026-07-17): the platform re-provisions the input path
// when a self-managed Telecom call activates or the route otherwise
// changes mid-capture (BT SCO up/down, wired attach). An AudioRecord
// opened before such a reroute is invalidated in place — the observed
// failure on the Galaxy Tab Active5 was a 2.6 s blocked read() at the
// route change, then a stream alternating ~300 ms blocks of real audio
// and hard digital zeros for the rest of the burst. Two watchers guard
// against that:
//   1. An [AudioRouting.OnRoutingChangedListener] on the record — any
//      routed-device change while running triggers an in-place restart
//      (fresh AudioRecord, same read loop, session id re-published).
//   2. A stall watchdog on [callbackHandler] — if the read loop stops
//      delivering frames for ~1.5 s with no routing callback (not every
//      OEM fires one), the same restart path runs.
// Restarts are bounded ([MAX_INPLACE_RESTARTS]) so a genuinely wedged
// HAL degrades to onCaptureError instead of a restart loop.
//
// Threading: a dedicated read thread blocks in AudioRecord.read; frames
// are delivered to onFrame on that thread. Caller is responsible for
// marshalling if the consumer isn't thread-safe. Restarts execute ON the
// read thread (the watchers only request them and unblock the read), so
// the record field is never swapped under a concurrent read.
// File-backed diagnostic mirror for capture-lifecycle events — same
// rationale as TxController's txDiag: the Sonim XP9900's logcat
// filtering swallows XV app tags, and the capture self-heal events are
// exactly what a first-transmission post-mortem needs. No-throw no-op
// before DiagnosticLogger.init(); messages carry no MACs (addresses
// are redacted upstream of any logging here).
private fun capDiag(
    message: String,
    severity: Char = 'I',
) {
    com.atakmap.android.xv.util.DiagnosticLogger.event(tag = "XvAudioCapture", severity = severity, message = message)
}

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
    // historical callsites that don't route through VoicePlant. Also
    // fires on every in-place restart (the fresh record gets a fresh
    // session id) so the playback side never binds to a dead session.
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

    // ---- self-heal state --------------------------------------------

    // Set by the routing listener / stall watchdog; consumed by the read
    // loop, which performs the actual restart on its own thread.
    @Volatile
    private var restartRequested: Boolean = false

    @Volatile
    private var restartCount: Int = 0

    // Device id the record was routed to the last time we looked. Used
    // to ignore no-op routing callbacks (some OEMs re-fire the listener
    // with an unchanged device).
    @Volatile
    private var lastRoutedDeviceId: Int = -1

    // Total frames delivered across the capture's lifetime (survives
    // in-place restarts). Read by the stall watchdog.
    @Volatile
    private var totalFrames: Long = 0

    private var stallCheckLastFrames: Long = -1
    private var stallStrikes: Int = 0

    // Handler for the routing listener + stall watchdog. Main looper —
    // both callbacks are tiny (flag flip + record.stop()).
    private val callbackHandler = Handler(Looper.getMainLooper())

    private val routingListener =
        AudioRouting.OnRoutingChangedListener { router ->
            if (!running) return@OnRoutingChangedListener
            val dev =
                try {
                    router.routedDevice
                } catch (t: Throwable) {
                    null
                }
            val id = dev?.id ?: -1
            if (id == lastRoutedDeviceId) return@OnRoutingChangedListener
            Log.w(
                TAG,
                "input routing changed mid-capture → ${dev?.productName}/${dev?.let { typeName(it.type) }} " +
                    "(was device id=$lastRoutedDeviceId) — restarting AudioRecord in place",
            )
            capDiag("routing changed mid-capture → ${dev?.productName}/${dev?.let { typeName(it.type) }} — restart", 'W')
            lastRoutedDeviceId = id
            requestRestart()
        }

    private val stallCheckRunnable =
        object : Runnable {
            override fun run() {
                if (!running) return
                val frames = totalFrames
                if (frames == stallCheckLastFrames && !restartRequested) {
                    stallStrikes++
                    if (stallStrikes >= STALL_STRIKES_TO_RESTART) {
                        Log.w(
                            TAG,
                            "read loop stalled (no frames for ~${stallStrikes * STALL_CHECK_INTERVAL_MS}ms " +
                                "at frame #$frames) — forcing in-place restart",
                        )
                        capDiag("read loop stalled at frame #$frames — restart", 'W')
                        stallStrikes = 0
                        requestRestart()
                    }
                } else {
                    stallStrikes = 0
                }
                stallCheckLastFrames = frames
                callbackHandler.postDelayed(this, STALL_CHECK_INTERVAL_MS)
            }
        }

    /** Ask the read thread to swap in a fresh AudioRecord. Safe from any
     *  thread; stop() on the old record unblocks a read in flight. */
    private fun requestRestart() {
        if (!running) return
        restartRequested = true
        try {
            record?.stop()
        } catch (_: Throwable) {
        }
    }

    fun start() {
        if (running) return
        // Fresh capture session — reset the self-heal bookkeeping so a
        // reused instance (stop() → start()) gets a full restart budget
        // and clean stall counters. Today TxController allocates a new
        // AudioCapture per session via the factory, but the API permits
        // reuse and a silently pre-exhausted budget would disable the
        // self-heal exactly when a reconnect path needs it.
        restartCount = 0
        totalFrames = 0
        restartRequested = false
        val r = buildRecord() ?: return

        record = r
        running = true
        if (!startRecordingOrFail(r)) return
        installWatchers(r)
        thread =
            Thread({ runReadLoop() }, "xv-mic-capture").also { it.start() }
        publishSessionId(r)
        logStarted(r, restart = false)
    }

    /** Allocate + validate an AudioRecord. Returns null (after firing
     *  onCaptureError) on any failure. Shared by [start] and the
     *  in-place restart path. */
    private fun buildRecord(): AudioRecord? {
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
                return null
            } catch (t: Throwable) {
                Log.e(TAG, "AudioRecord build failed", t)
                onCaptureError("AudioRecord init failed: ${t.message ?: t.javaClass.simpleName}")
                return null
            }
        if (r.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord not initialized (state=${r.state}) — likely permission or audio-routing issue")
            r.release()
            onCaptureError(
                "AudioRecord init failed (state=${r.state}) — check RECORD_AUDIO permission and BT routing",
            )
            return null
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
        applyPreferredInputDevice(r)
        return r
    }

    private fun startRecordingOrFail(r: AudioRecord): Boolean {
        try {
            r.startRecording()
        } catch (t: SecurityException) {
            Log.e(TAG, "AudioRecord.startRecording threw SecurityException — RECORD_AUDIO permission revoked", t)
            onCaptureError("RECORD_AUDIO permission revoked — re-grant in system Settings")
            stop()
            return false
        } catch (t: Throwable) {
            Log.e(TAG, "AudioRecord.startRecording threw", t)
            onCaptureError("AudioRecord startRecording failed: ${t.message ?: t.javaClass.simpleName}")
            stop()
            return false
        }
        return true
    }

    private fun installWatchers(r: AudioRecord) {
        lastRoutedDeviceId =
            try {
                r.routedDevice?.id ?: -1
            } catch (t: Throwable) {
                -1
            }
        try {
            r.addOnRoutingChangedListener(routingListener, callbackHandler)
        } catch (t: Throwable) {
            Log.w(TAG, "addOnRoutingChangedListener failed — reroute self-heal disabled", t)
        }
        stallCheckLastFrames = -1
        stallStrikes = 0
        callbackHandler.removeCallbacks(stallCheckRunnable)
        callbackHandler.postDelayed(stallCheckRunnable, STALL_CHECK_INTERVAL_MS)
    }

    private fun removeWatchers(r: AudioRecord?) {
        callbackHandler.removeCallbacks(stallCheckRunnable)
        try {
            r?.removeOnRoutingChangedListener(routingListener)
        } catch (_: Throwable) {
        }
    }

    private fun publishSessionId(r: AudioRecord) {
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
    }

    private fun logStarted(
        r: AudioRecord,
        restart: Boolean,
    ) {
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
        val verb = if (restart) "restarted in place (#$restartCount)" else "started"
        Log.i(
            TAG,
            "AudioRecord $verb (rate=$sampleRateHz frame=$frameSamples " +
                "preferred=${r.preferredDevice?.productName} " +
                "routed=${routed?.productName}/${routed?.let { typeName(it.type) }})",
        )
        capDiag("AudioRecord $verb routed=${routed?.productName}/${routed?.let { typeName(it.type) }}")
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
            Log.i(TAG, "  device: ${d.productName} type=${typeName(d.type)} address=${redactAddress(d.address)}")
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
        restartRequested = false
        removeWatchers(record)
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

    /** Executes an in-place restart ON the read thread: release the old
     *  record, build + start a fresh one, rearm the watchers, republish
     *  the session id. Returns the fresh record, or null when the
     *  restart budget is exhausted / the rebuild failed (read loop
     *  exits; [stop] semantics apply via the error callback). */
    private fun performInPlaceRestart(old: AudioRecord): AudioRecord? {
        restartRequested = false
        if (restartCount >= MAX_INPLACE_RESTARTS) {
            Log.e(
                TAG,
                "in-place restart budget exhausted ($restartCount/$MAX_INPLACE_RESTARTS) — giving up on this capture",
            )
            capDiag("restart budget exhausted ($restartCount) — capture abandoned", 'E')
            onCaptureError("mic input path keeps dropping (restarted $restartCount times) — check BT/audio state")
            return null
        }
        restartCount++
        removeWatchers(old)
        try {
            old.stop()
        } catch (_: Throwable) {
        }
        try {
            old.release()
        } catch (_: Throwable) {
        }
        // Small settle pause: the HAL that just invalidated the old
        // stream may reject an immediate re-open with the same config.
        SystemClock.sleep(RESTART_SETTLE_MS)
        if (!running) return null
        val fresh = buildRecord() ?: return null
        // Re-check AFTER buildRecord() (tens of ms): an external stop()
        // — PTT release, yield, disarm — can land in that window. If it
        // did, do NOT start the fresh record or publish its session id;
        // release it and bail. Without this, stop() has already set
        // running=false + record=null + session=null, and we would then
        // start a record nobody owns.
        if (!running) {
            try {
                fresh.release()
            } catch (_: Throwable) {
            }
            return null
        }
        record = fresh
        try {
            fresh.startRecording()
        } catch (t: Throwable) {
            Log.e(TAG, "in-place restart: startRecording threw", t)
            onCaptureError("AudioRecord restart failed: ${t.message ?: t.javaClass.simpleName}")
            return null
        }
        // Final re-check: stop() can land DURING startRecording() itself.
        // If the capture was torn down out from under us, the fresh
        // record is now RECORDING with no read thread to service it and
        // the while(running) loop is about to exit skipping the epilogue
        // — a leaked mic handle (privacy indicator stuck, other apps
        // blocked, stale playback session). Tear it down here. Guarded
        // try/catch makes this idempotent against a concurrent stop()
        // that may also be releasing the same record (2026-07-17
        // forensics — found independently by all five investigators).
        if (!running) {
            Log.w(TAG, "in-place restart raced stop() — tearing down fresh record")
            capDiag("restart raced stop() — fresh record torn down", 'W')
            try {
                fresh.stop()
            } catch (_: Throwable) {
            }
            try {
                fresh.release()
            } catch (_: Throwable) {
            }
            if (record === fresh) record = null
            try {
                onSessionIdChanged(null)
            } catch (_: Throwable) {
            }
            return null
        }
        installWatchers(fresh)
        publishSessionId(fresh)
        logStarted(fresh, restart = true)
        return fresh
    }

    private fun runReadLoop() {
        val buf = ShortArray(frameSamples)
        var r = record ?: return
        Log.i(TAG, "AudioCapture read loop started: frameSamples=$frameSamples sampleRate=$sampleRateHz")
        var frameCount = 0
        while (running) {
            if (restartRequested) {
                r = performInPlaceRestart(r) ?: break
                continue
            }
            val n =
                try {
                    r.read(buf, 0, buf.size, AudioRecord.READ_BLOCKING)
                } catch (t: Throwable) {
                    if (running && !restartRequested) Log.w(TAG, "AudioRecord.read threw", t)
                    -1
                }
            if (n <= 0) {
                // A watcher-initiated stop lands here (stop() on the old
                // record makes the blocked read return an error) — swap
                // in the fresh record and keep the loop alive. A read
                // failure with no restart pending is treated the same
                // way once: a dead record is exactly what the self-heal
                // exists for. The restart budget bounds both cases.
                if (running) {
                    if (!restartRequested) {
                        Log.w(TAG, "AudioRecord.read returned $n (frame #$frameCount) — attempting in-place restart")
                    }
                    restartRequested = true
                    r = performInPlaceRestart(r) ?: break
                    continue
                }
                break
            }
            frameCount++
            totalFrames++

            // Diagnostic: compute RMS and show first few samples
            var sumSq = 0.0
            for (i in 0 until n) {
                val v = buf[i].toDouble()
                sumSq += v * v
            }
            val rms = kotlin.math.sqrt(sumSq / n).toInt()

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
        // Epilogue: the loop can exit while the capture is still
        // nominally running — restart budget exhausted, a restart
        // rebuild/startRecording failure, or a fatal read error with no
        // restart pending. Without this, the AudioRecord outlives its
        // read thread (native mic handle held, activeSessionId stale)
        // and the stall watchdog keeps firing restart requests nothing
        // will ever service. Tear down in place so the next capture
        // attempt starts from a clean slate. A normal stop() flips
        // `running` false before we get here and does its own teardown,
        // so this path is skipped then; the guarded try/catch teardown
        // is idempotent against a stop() racing us anyway.
        if (running) {
            Log.w(TAG, "read loop exiting while capture still marked running — cleaning up in place")
            capDiag("read loop died — in-place cleanup", 'W')
            running = false
            removeWatchers(record)
            record?.let {
                try {
                    it.stop()
                } catch (_: Throwable) {
                }
                try {
                    it.release()
                } catch (_: Throwable) {
                }
            }
            record = null
            try {
                onSessionIdChanged(null)
            } catch (t: Throwable) {
                Log.w(TAG, "onSessionIdChanged(loop-death cleanup) threw", t)
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

    // Field logs get pulled and shared during debugging, and a BT SCO
    // input's address IS the operator's device MAC — sensitive per the
    // repo's content rules. Mask MAC-shaped addresses down to the last
    // octet (enough to tell two pucks apart, mirroring the platform's
    // own BluetoothGatt redaction); pass through non-MAC addresses
    // ("bottom", "back") which are just mic placement labels.
    private fun redactAddress(addr: String?): String {
        if (addr.isNullOrEmpty()) return ""
        return if (MAC_SHAPED.matches(addr)) "XX:XX:XX:XX:XX:${addr.takeLast(2)}" else addr
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

    companion object {
        private const val TAG = "XvAudioCapture"

        private val MAC_SHAPED = Regex("^([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}$")

        // Ceiling on in-place restarts per capture lifetime. Three
        // covers the realistic cascade (Telecom activates → SCO comes
        // up → operator flips route) with one to spare; past that the
        // HAL is wedged and the operator needs the error, not another
        // silent retry.
        private const val MAX_INPLACE_RESTARTS = 3

        // Stall watchdog cadence and strike count: ~2 × 750 ms of zero
        // frame delivery forces a restart. Comfortably above any normal
        // read jitter (frames are 10 ms), comfortably below the 2.6 s
        // stall observed in the field.
        private const val STALL_CHECK_INTERVAL_MS = 750L
        private const val STALL_STRIKES_TO_RESTART = 2

        // Pause between releasing a dead record and re-opening. Gives
        // the HAL's route re-provisioning a beat to finish so the fresh
        // open lands on the post-reroute path.
        private const val RESTART_SETTLE_MS = 50L
    }
}
