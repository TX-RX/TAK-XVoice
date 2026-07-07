package com.atakmap.android.xv.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log

// Plays a one-shot Talk Permit Tone through the same audio path as voice
// (USAGE_VOICE_COMMUNICATION when SCO is engaged so it routes to the BT
// speakermic; USAGE_MEDIA otherwise). Calls back when the tone finishes
// so the TX path can start mic capture only after the tone has played —
// keeps the tone out of the transmitted stream.
class TptPlayer(
    private val sampleRateHz: Int = TptToneGenerator.SAMPLE_RATE_HZ,
    // Optional — supplies AudioManager so we can log/control system audio
    // state at play time (mode, voice-call stream volume, comm device).
    // Without it we play the same way but skip the diagnostic logs.
    private val context: Context? = null,
    // Resolves the operator's chosen output device at tone-play time.
    // When provided and the result is non-null, the non-SCO tone path
    // pins the AudioTrack to that device via setPreferredDevice,
    // bypassing Telecom's CallEndpointController route arbitration.
    // When null or returning null, the tone plays on the default route
    // for its USAGE. Wired to AudioRouter.preferredDevice() from
    // VoicePlant so AUTO/SPEAKER picks loudspeaker, explicit EARPIECE
    // picks earpiece, BT override picks the chosen BT device.
    private val preferredDeviceForTones: (() -> AudioDeviceInfo?)? = null,
) {
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var track: AudioTrack? = null

    @Volatile
    private var pendingComplete: Runnable? = null

    // Separate track for the TX-timeout-cutoff one-shot. Kept distinct
    // from [track] so the existing one-shot stop() in playPcmSession /
    // playBonk / playDeny can't accidentally kill the cutoff tone, and
    // vice versa. The cutoff tone is a fire-and-forget one-shot that
    // self-releases via a wall-clock callback after its duration.
    @Volatile
    private var timeoutCutoffTrack: AudioTrack? = null

    @Volatile
    private var timeoutCutoffRelease: Runnable? = null

    fun play(
        tone: TptTone,
        useScoRoute: Boolean,
        onComplete: () -> Unit,
    ) {
        if (tone == TptTone.NONE) {
            onComplete()
            return
        }
        val pcm = TptToneGenerator.generate(tone)
        if (pcm.isEmpty()) {
            onComplete()
            return
        }
        // Note: tried prepending 150ms of silence to the PCM to warm up
        // the AINA V1's HFP chipset output stage (since the first TPT
        // after a cold SCO is consistently quieter than subsequent
        // ones). The prefix didn't fix the loudness drift — the chipset's
        // AGC settles too quickly for prewarm to have measurable effect.
        // Concluded the gain swing is firmware-level on V1 and not
        // controllable from app-side. V2's behavior is more consistent.
        playPcmSession(
            pcm = pcm,
            useScoRoute = useScoRoute,
            label = "$tone",
            durationMs = TptToneGenerator.durationMs(tone),
            volume = 1.0f,
            onComplete = onComplete,
        )
    }

    // Plays the short interrupt chirp instead of the full TPT — used by
    // TxController when the user keys PTT while a peer is currently
    // talking on RX. Track-level volume is fixed at 1.0 (the chirp
    // PCM itself is at -12 dBFS so peer voice continues to dominate
    // the speaker mix); same SCO/non-SCO routing and tail-guard as TPT.
    fun playInterrupt(
        useScoRoute: Boolean,
        onComplete: () -> Unit,
    ) {
        val pcm = TptToneGenerator.interruptChirp()
        if (pcm.isEmpty()) {
            onComplete()
            return
        }
        playPcmSession(
            pcm = pcm,
            useScoRoute = useScoRoute,
            label = "INTERRUPT_CHIRP",
            durationMs = TptToneGenerator.INTERRUPT_CHIRP_DURATION_MS,
            volume = 1.0f,
            onComplete = onComplete,
        )
    }

    // Plays a status chirp (channel join / leave / warning / call cue).
    // Routing:
    //   - useScoRoute = false (default): play out-loud. Status events
    //     fired from background paths (server reconnect, VS2 retarget)
    //     don't justify engaging SCO purely for a 120-200 ms tone, and
    //     pinning a track to BT_SCO with no live link routes to silence.
    //   - useScoRoute = true: play through the SCO link. Use this when
    //     SCO is already up (mid-TX cues like the timeout pre-cutoff
    //     warning, comms-lost-during-burst) so the operator hears the
    //     tone on the speakermic they're already wearing instead of
    //     out of the phone in their pocket.
    fun playStatus(
        kind: StatusToneKind,
        useScoRoute: Boolean = false,
    ) {
        val pcm =
            when (kind) {
                StatusToneKind.CHANNEL_JOIN -> TptToneGenerator.joinChirp()
                StatusToneKind.CHANNEL_LEAVE -> TptToneGenerator.leaveChirp()
                StatusToneKind.WARNING_VOICE_LOST,
                StatusToneKind.WARNING_CHANNEL_LOST,
                -> TptToneGenerator.warningChirp()
                StatusToneKind.CALL_RINGBACK -> TptToneGenerator.ringbackChirp()
                StatusToneKind.CALL_BUSY -> TptToneGenerator.busyChirp()
            }
        if (pcm.isEmpty()) return
        val durationMs =
            when (kind) {
                StatusToneKind.CHANNEL_JOIN -> TptToneGenerator.JOIN_CHIRP_DURATION_MS
                StatusToneKind.CHANNEL_LEAVE -> TptToneGenerator.LEAVE_CHIRP_DURATION_MS
                StatusToneKind.WARNING_VOICE_LOST,
                StatusToneKind.WARNING_CHANNEL_LOST,
                -> TptToneGenerator.WARNING_CHIRP_DURATION_MS
                StatusToneKind.CALL_RINGBACK -> TptToneGenerator.RINGBACK_CHIRP_DURATION_MS
                StatusToneKind.CALL_BUSY -> TptToneGenerator.BUSY_CHIRP_DURATION_MS
            }
        playPcmSession(
            pcm = pcm,
            useScoRoute = useScoRoute,
            label = "STATUS_${kind.name}",
            durationMs = durationMs,
            volume = 1.0f,
            onComplete = {},
        )
    }

    // Common AudioTrack lifecycle for both TPT and the interrupt chirp.
    // Builds a static-mode track sized to the PCM, plays once, fires
    // onComplete via a marker callback (with SCO_TAIL_GUARD_MS post-
    // marker delay on SCO routes for chipset buffer drain). A wall-
    // clock watchdog at 2× duration + slack is the failsafe.
    //
    // Audio attrs depend on [useScoRoute]:
    //   - SCO route (BT speakermic engaged): USAGE_VOICE_COMMUNICATION
    //     so the tone goes through the SCO link to the speakermic
    //     instead of the phone's loudspeaker.
    //   - Non-SCO route (phone-only): USAGE_ASSISTANCE_SONIFICATION
    //     (STREAM_SYSTEM). The previous USAGE_VOICE_COMMUNICATION on
    //     this path landed the tone on Telecom's chosen call audio
    //     device — which, for self-managed VoIP on Pixel/Android 16,
    //     bounces SPEAKER ↔ EARPIECE during call setup and frequently
    //     lands TPT on the earpiece. ASSISTANCE_SONIFICATION sidesteps
    //     Telecom's CallEndpointController entirely: it's the system-
    //     feedback channel that plays via the loudspeaker even in
    //     MODE_IN_COMMUNICATION, the same way OS button-click and
    //     volume-press confirmation tones do during a call. The
    //     previous USAGE_MEDIA attempt failed because MEDIA *is*
    //     ducked to silence by MODE_IN_COMMUNICATION; SONIFICATION
    //     is not.
    private fun playPcmSession(
        pcm: ShortArray,
        useScoRoute: Boolean,
        label: String,
        durationMs: Long,
        volume: Float,
        onComplete: () -> Unit,
    ) {
        stop()
        // Lift STREAM_VOICE_CALL off the floor before opening the track.
        // Pixel + AINA share absolute-volume sync over HFP, so a stray
        // volume-down press on either side decrements the call stream
        // even when no real telephony call is active. Once it hits 0,
        // the per-track setVolume(1f) below can't recover — TPT and peer
        // voice both go silent until the user manually finds the volume
        // slider. For a PTT app that's a safety failure, so we treat
        // STREAM_VOICE_CALL = 0 as accidental and snap it back to a
        // mid-range floor. Logged loudly so the operator can see what
        // happened in a diagnostic capture.
        ensureVoiceCallAudible(label)
        val attrs =
            AudioAttributes
                .Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        val format =
            AudioFormat
                .Builder()
                .setSampleRate(sampleRateHz)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build()
        // Buffer must fit BOTH the silence preRoll AND the tone PCM AND
        // a matching postRoll of trailing silence. Under-sizing it
        // silently drops the tail in MODE_STATIC — the second write past
        // capacity is a no-op, playback stops early, and (worse) the
        // notificationMarkerPosition set at end-of-buffer never fires
        // because the head position never gets there.
        //
        // The postRoll matters even more than the preRoll: the
        // AudioTrack head-position counter (which the marker fires on)
        // advances as samples are consumed by the DAC internal buffer,
        // NOT when they leave the physical speaker. On Pixel there is
        // typically 10-30 ms of samples in the hardware output pipeline
        // that hasn't reached the driver's audible-output stage yet.
        // Firing the marker at "end of tone PCM" and calling t.stop()
        // there flushes that pipeline — the operator perceives the tone
        // getting truncated. Padding the buffer with SILENCE_POSTROLL_MS
        // of silence means the marker fires on silent samples and the
        // hardware flush eats silence, not tone. Field-observed
        // 2026-07-07 on the built-in speaker.
        val preRollSamples = sampleRateHz * SILENCE_PREROLL_MS / 1000
        val postRollSamples = sampleRateHz * SILENCE_POSTROLL_MS / 1000
        val t =
            try {
                AudioTrack
                    .Builder()
                    .setAudioAttributes(attrs)
                    .setAudioFormat(format)
                    .setBufferSizeInBytes((preRollSamples + pcm.size + postRollSamples) * 2)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build()
            } catch (th: Throwable) {
                Log.e(TAG, "AudioTrack build failed for $label", th)
                onComplete()
                return
            }
        pinDeviceForTone(t, useScoRoute, label)
        // Force a fixed AudioTrack gain so output level doesn't drift
        // between bursts. The AINA chipset's bidirectional-mode switch
        // (which fires when the parallel mic capture starts during TPT
        // prewarm) appears to perturb output gain on some firmwares;
        // pinning the per-track volume takes our half of the gain stack
        // out of the equation. The system stream volume still applies
        // on top.
        try {
            t.setVolume(volume)
        } catch (th: Throwable) {
            Log.w(TAG, "setVolume($volume) failed", th)
        }
        // 50 ms silence pre-roll. On cold BT SCO the AINA's HFP DAC
        // transitions from "off" to "active" when the AudioTrack opens
        // → audible click/pop at the start of the first tone. Writing
        // a short zero buffer first absorbs that transient: the DAC
        // stabilizes on silence, and the actual tone PCM lands on a
        // clean stream. Field complaint 2026-05-21: "there is a bit
        // of a click/pop at the beginning of the audio session
        // opening." 50 ms is short enough not to push back the
        // perceived TPT start (no operator can react that fast) but
        // long enough to cover the worst-case BT chipset settling
        // window measured on the AINA V1.
        val preRoll = ShortArray(preRollSamples)
        val postRoll = ShortArray(postRollSamples)
        // Concatenate preRoll + pcm + postRoll into a single buffer so
        // MODE_STATIC gets exactly one write() call. Some AudioTrack
        // implementations refuse subsequent write() calls after the
        // first for MODE_STATIC — the effective sample count then
        // matched only preRoll+pcm, and the postRoll silence never
        // reached the hardware, so marker firing on a truncated buffer
        // still cut the tone tail.
        val buffered = ShortArray(preRoll.size + pcm.size + postRoll.size)
        System.arraycopy(preRoll, 0, buffered, 0, preRoll.size)
        System.arraycopy(pcm, 0, buffered, preRoll.size, pcm.size)
        System.arraycopy(postRoll, 0, buffered, preRoll.size + pcm.size, postRoll.size)
        try {
            val written = t.write(buffered, 0, buffered.size)
            if (written < buffered.size) {
                Log.w(TAG, "$label short write: wrote $written / ${buffered.size} samples")
            }
            t.play()
        } catch (th: Throwable) {
            Log.e(TAG, "$label write/play failed", th)
            try {
                t.release()
            } catch (_: Throwable) {
            }
            onComplete()
            return
        }
        track = t
        Log.i(
            TAG,
            "playing $label (${pcm.size} tone + ${preRoll.size} preRoll + ${postRoll.size} postRoll " +
                "= ${buffered.size} total samples, marker@${preRoll.size + pcm.size + postRoll.size}, " +
                "useSco=$useScoRoute)",
        )
        logAudioContext("playing $label")
        val finalize: () -> Unit = {
            try {
                t.stop()
            } catch (_: Throwable) {
            }
            try {
                t.release()
            } catch (_: Throwable) {
            }
            if (track === t) track = null
            pendingComplete = null
            if (useScoRoute) {
                mainHandler.postDelayed({ onComplete() }, SCO_TAIL_GUARD_MS)
            } else {
                onComplete()
            }
        }
        val watchdog =
            Runnable {
                Log.w(TAG, "$label marker watchdog fired — finishing via wall clock")
                finalize()
            }
        pendingComplete = watchdog
        mainHandler.postDelayed(watchdog, durationMs * 2 + WATCHDOG_SLACK_MS)
        try {
            t.setPlaybackPositionUpdateListener(
                object : AudioTrack.OnPlaybackPositionUpdateListener {
                    override fun onMarkerReached(track: AudioTrack?) {
                        Log.i(TAG, "$label marker reached — finalizing (playback complete)")
                        mainHandler.post {
                            mainHandler.removeCallbacks(watchdog)
                            finalize()
                        }
                    }

                    override fun onPeriodicNotification(track: AudioTrack?) {}
                },
            )
            // Marker fires at end-of-buffer = preRoll + tone PCM +
            // postRoll. Setting it here (rather than at end-of-tone-PCM)
            // means finalize()'s t.stop() flushes the postRoll silence
            // out of the hardware pipeline, not the tone tail. Paired
            // with the buffer-size expansion above — the two invariants
            // move together.
            t.notificationMarkerPosition = preRoll.size + pcm.size + postRoll.size
        } catch (th: Throwable) {
            Log.w(TAG, "$label marker setup failed; relying on watchdog", th)
        }
    }

    fun playBonk(useScoRoute: Boolean) {
        stop()
        val pcm = TptToneGenerator.noPermitBonk()
        val attrs =
            AudioAttributes
                .Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        val format =
            AudioFormat
                .Builder()
                .setSampleRate(sampleRateHz)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build()
        val t =
            try {
                AudioTrack
                    .Builder()
                    .setAudioAttributes(attrs)
                    .setAudioFormat(format)
                    .setBufferSizeInBytes(pcm.size * 2)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build()
            } catch (th: Throwable) {
                Log.e(TAG, "bonk AudioTrack build failed", th)
                return
            }
        pinDeviceForTone(t, useScoRoute, "BONK")
        try {
            t.write(pcm, 0, pcm.size)
            t.play()
        } catch (th: Throwable) {
            Log.e(TAG, "bonk write/play failed", th)
            try {
                t.release()
            } catch (_: Throwable) {
            }
            return
        }
        track = t
        Log.i(TAG, "playing BONK / no-permit tone")
        val r =
            Runnable {
                try {
                    t.stop()
                } catch (_: Throwable) {
                }
                try {
                    t.release()
                } catch (_: Throwable) {
                }
                if (track === t) track = null
                pendingComplete = null
            }
        pendingComplete = r
        mainHandler.postDelayed(r, TptToneGenerator.NO_PERMIT_DURATION_MS + COMPLETION_SLACK_MS)
    }

    // Stronger reject tone for "you're in the channel but the server
    // says you can't speak here" (OTS direction OUT, Mumble admin
    // mute). Uses the dedicated descending-double-tone generator —
    // sounds clearly different from the single bonk so the operator
    // can tell "you're not in a channel" (single bonk) from "you're
    // listen-only on this channel" (deny tone) without looking.
    //
    // useScoRoute mirrors the TPT/bonk path — when true, plays via
    // USAGE_VOICE_COMMUNICATION so the tone reaches the AINA over
    // SCO. The system's voice-comm hysteresis (which can hold SCO
    // a few extra seconds past the stream close) used to leak SCO
    // past our cool-down on rapid deny presses; the caller now
    // re-arms our 5 s cool-down on every deny press so OUR
    // teardown is the authority and outlasts the system hysteresis.
    fun playDeny(useScoRoute: Boolean) {
        stop()
        val pcm = TptToneGenerator.denyTone()
        val attrs =
            AudioAttributes
                .Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        val format =
            AudioFormat
                .Builder()
                .setSampleRate(sampleRateHz)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build()
        val t =
            try {
                AudioTrack
                    .Builder()
                    .setAudioAttributes(attrs)
                    .setAudioFormat(format)
                    .setBufferSizeInBytes(pcm.size * 2)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build()
            } catch (th: Throwable) {
                Log.e(TAG, "deny AudioTrack build failed", th)
                return
            }
        pinDeviceForTone(t, useScoRoute, "DENY")
        try {
            t.write(pcm, 0, pcm.size)
            t.play()
        } catch (th: Throwable) {
            Log.e(TAG, "deny write/play failed", th)
            try {
                t.release()
            } catch (_: Throwable) {
            }
            return
        }
        track = t
        Log.i(TAG, "playing DENY / listen-only reject tone")
        val r =
            Runnable {
                try {
                    t.stop()
                } catch (_: Throwable) {
                }
                try {
                    t.release()
                } catch (_: Throwable) {
                }
                if (track === t) track = null
                pendingComplete = null
            }
        pendingComplete = r
        mainHandler.postDelayed(r, TptToneGenerator.DENY_DURATION_MS + COMPLETION_SLACK_MS)
    }

    // Snapshot the audio-system state when TPT plays so we can correlate
    // perceived TPT volume drift with system mode / stream volume / comm
    // device changes between bursts. Variance signals to look for:
    //   - audioMode flipping IN_COMMUNICATION ↔ NORMAL between presses
    //   - VOICE_CALL stream volume changing without user input
    //   - communicationDevice swapping between BLUETOOTH_SCO and other
    private fun logAudioContext(reason: String) {
        val ctx = context ?: return
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        val voiceVol =
            try {
                "${am.getStreamVolume(AudioManager.STREAM_VOICE_CALL)}/${am.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL)}"
            } catch (_: Throwable) {
                "?"
            }
        val mediaVol =
            try {
                "${am.getStreamVolume(AudioManager.STREAM_MUSIC)}/${am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)}"
            } catch (_: Throwable) {
                "?"
            }
        val mode =
            try {
                when (am.mode) {
                    AudioManager.MODE_NORMAL -> "NORMAL"
                    AudioManager.MODE_IN_CALL -> "IN_CALL"
                    AudioManager.MODE_IN_COMMUNICATION -> "IN_COMMUNICATION"
                    AudioManager.MODE_RINGTONE -> "RINGTONE"
                    else -> "mode=${am.mode}"
                }
            } catch (_: Throwable) {
                "?"
            }
        val commDev =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                try {
                    am.communicationDevice?.let { "${it.productName}/type=${it.type}" } ?: "null"
                } catch (_: Throwable) {
                    "?"
                }
            } else {
                "n/a (pre-S)"
            }
        Log.i(
            TAG,
            "audio ctx ($reason): mode=$mode VOICE_CALL=$voiceVol MUSIC=$mediaVol commDev=$commDev",
        )
    }

    fun stop() {
        pendingComplete?.let { mainHandler.removeCallbacks(it) }
        pendingComplete = null
        track?.let {
            try {
                it.stop()
            } catch (_: Throwable) {
            }
            try {
                it.release()
            } catch (_: Throwable) {
            }
        }
        track = null
    }

    // TX-timeout cutoff tone. One-shot ~1.2 second sustained sine
    // through USAGE_VOICE_COMMUNICATION — same path as TPT/bonk/deny.
    // Mixes with any peer voice on the SCO mixer (when SCO is up) and
    // routes through the earpiece/speakerphone otherwise; either way
    // it stays audible because the device is in MODE_IN_COMMUNICATION
    // for the active Telecom call and USAGE_MEDIA would be ducked.
    // The earlier wedging from a USAGE_VOICE_COMMUNICATION version
    // was caused by the *infinite loop* (setLoopPoints + -1) holding
    // audio policy resources during SCO teardown — a one-shot is
    // safe.
    //
    // The tone auto-completes via a wall-clock release callback.
    // Caller doesn't have to stop it explicitly; PttDispatcher only
    // calls stopTimeoutCutoff() defensively on plugin teardown.
    fun playTimeoutCutoff() {
        stopTimeoutCutoff()
        val pcm = TptToneGenerator.timeoutCutoffPcm()
        if (pcm.isEmpty()) return
        val attrs =
            AudioAttributes
                .Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        val format =
            AudioFormat
                .Builder()
                .setSampleRate(sampleRateHz)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build()
        val t =
            try {
                AudioTrack
                    .Builder()
                    .setAudioAttributes(attrs)
                    .setAudioFormat(format)
                    .setBufferSizeInBytes(pcm.size * 2)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build()
            } catch (th: Throwable) {
                Log.e(TAG, "timeout cutoff AudioTrack build failed", th)
                return
            }
        // Timeout cutoff fires only mid-burst (TX active → mic > limit
        // seconds). SCO is up in that state, so explicitly pin BT_SCO
        // so the cutoff lands on the speakermic the operator is
        // talking into instead of leaking to the phone in their
        // pocket. The general pinDeviceForTone path defers to Telecom
        // on the SCO route to avoid the regression observed during
        // normal TPT plays — that protection doesn't apply here
        // because there's no active call to fight; the cutoff is the
        // single voice-comm track at this moment.
        pinBtScoForCutoff(t, label = "TIMEOUT_CUTOFF")
        try {
            t.write(pcm, 0, pcm.size)
            t.play()
        } catch (th: Throwable) {
            Log.e(TAG, "timeout cutoff write/play failed", th)
            try {
                t.release()
            } catch (_: Throwable) {
            }
            return
        }
        timeoutCutoffTrack = t
        Log.i(
            TAG,
            "playing TX-timeout cutoff tone (one-shot, ${TptToneGenerator.TIMEOUT_CUTOFF_DURATION_MS}ms, USAGE_VOICE_COMMUNICATION)",
        )
        val r =
            Runnable {
                try {
                    t.stop()
                } catch (_: Throwable) {
                }
                try {
                    t.release()
                } catch (_: Throwable) {
                }
                if (timeoutCutoffTrack === t) timeoutCutoffTrack = null
                timeoutCutoffRelease = null
            }
        timeoutCutoffRelease = r
        mainHandler.postDelayed(r, TptToneGenerator.TIMEOUT_CUTOFF_DURATION_MS + COMPLETION_SLACK_MS)
    }

    fun stopTimeoutCutoff() {
        timeoutCutoffRelease?.let { mainHandler.removeCallbacks(it) }
        timeoutCutoffRelease = null
        val t = timeoutCutoffTrack ?: return
        timeoutCutoffTrack = null
        try {
            t.stop()
        } catch (_: Throwable) {
        }
        try {
            t.release()
        } catch (_: Throwable) {
        }
        Log.i(TAG, "TX-timeout cutoff tone stopped (early)")
    }

    /**
     * Pre-warm the USAGE_MEDIA AudioTrack path with a brief silent burst.
     * Call once at plugin load. The first AudioTrack on a given
     * (usage, content_type) tuple pays a ~150-250ms HAL warmup cost on
     * many devices — that's why the FIRST PTT press has a TPT that gets
     * cut off and subsequent presses sound fine. Priming the HAL ahead
     * of time means the first real TPT plays from a warm pipeline.
     */
    fun primeMediaPath() {
        val format =
            AudioFormat
                .Builder()
                .setSampleRate(sampleRateHz)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build()
        val attrs =
            AudioAttributes
                .Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        val silenceFrames = sampleRateHz / 20 // ~50 ms
        val silence = ShortArray(silenceFrames)
        val t =
            try {
                AudioTrack
                    .Builder()
                    .setAudioAttributes(attrs)
                    .setAudioFormat(format)
                    .setBufferSizeInBytes(silenceFrames * 2)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build()
            } catch (th: Throwable) {
                Log.w(TAG, "primeMediaPath: build failed", th)
                return
            }
        try {
            t.write(silence, 0, silence.size)
            t.play()
        } catch (th: Throwable) {
            Log.w(TAG, "primeMediaPath: play failed", th)
            try {
                t.release()
            } catch (_: Throwable) {
            }
            return
        }
        // Release once the silence has played through — keeps the HAL
        // pipeline warm for the next AudioTrack.
        mainHandler.postDelayed({
            try {
                t.stop()
            } catch (_: Throwable) {
            }
            try {
                t.release()
            } catch (_: Throwable) {
            }
        }, 200)
        Log.i(TAG, "media path primed (silent $silenceFrames-frame burst)")
    }

    /**
     * Pin an AudioTrack's output device so AudioFlinger routes the tone
     * to a deterministic endpoint instead of wherever the system policy
     * happens to choose at play() time.
     *
     * SCO route: NO PIN. Telecom owns the call-audio routing in
     * MODE_IN_COMMUNICATION and is already steering to BT_SCO when
     * we're on the SCO route. Pinning with setPreferredDevice on top
     * of that was observed to silently suppress TPT output on Pixel +
     * AINA (2026-06-04: `setPreferredDevice → ok=true type=7` but the
     * operator heard nothing on the speakermic). The pin is a hint
     * AudioPolicy is free to ignore; under an active self-managed
     * VoIP call it appears to interact badly with Telecom's own
     * BT_SCO routing. Falling back to "let Telecom route it" matches
     * the behavior before the pin was added — when SCO is up, Telecom
     * sends voice-comm there.
     *
     * Non-SCO route: pin to the operator's chosen output. Telecom's
     * self-managed VoIP CallEndpointController bounces SPEAKER ↔
     * EARPIECE during call setup and the tone otherwise lands wherever
     * the comm device happens to be at play() time. Reject BT_SCO
     * results from the picker (the router returns it when an AINA is
     * paired even on the non-SCO test-tone path) and fall back to
     * speaker; pinning to BT_SCO without a live SCO link routes to
     * silence.
     *
     * [playTimeoutCutoff] is the lone SCO-path caller that still pins
     * explicitly — it fires mid-burst when SCO is definitely up and
     * the operator needs the cutoff to land on the speakermic they're
     * speaking into.
     */
    private fun pinDeviceForTone(
        track: AudioTrack,
        useScoRoute: Boolean,
        label: String,
    ) {
        if (useScoRoute) {
            Log.i(TAG, "$label: SCO route — deferring routing to Telecom (no setPreferredDevice)")
            return
        }
        val am = context?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        val outputs = am?.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val speakerFallback =
            outputs?.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
        val preferred =
            try {
                val resolved = preferredDeviceForTones?.invoke()
                if (resolved != null && resolved.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) {
                    Log.i(
                        TAG,
                        "$label: router returned BT_SCO but useScoRoute=false — falling back to speaker",
                    )
                    speakerFallback
                } else {
                    resolved ?: speakerFallback
                }
            } catch (th: Throwable) {
                Log.w(TAG, "$label: preferredDevice resolution threw, falling back to default route", th)
                null
            }
        if (preferred != null) {
            val pinned = track.setPreferredDevice(preferred)
            Log.i(
                TAG,
                "$label: setPreferredDevice(${preferred.productName}, type=${preferred.type}) " +
                    "→ ok=$pinned (useScoRoute=$useScoRoute)",
            )
        } else {
            Log.w(
                TAG,
                "$label: no preferred device resolved — playing on default route (useScoRoute=$useScoRoute)",
            )
        }
    }

    /**
     * Explicit BT_SCO pin used by [playTimeoutCutoff] — the only path
     * that needs to override Telecom-side routing because the operator
     * is mid-burst on a speakermic and the cutoff MUST land there. See
     * [pinDeviceForTone] for why the general path doesn't pin BT_SCO.
     * Logs whether the system actually exposes a BT_SCO endpoint right
     * now; if not, falls back to leaving the track on the default
     * route (Telecom should still be steering to BT_SCO at that point
     * since the burst is live).
     */
    private fun pinBtScoForCutoff(
        track: AudioTrack,
        label: String,
    ) {
        val am = context?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        val outputs = am?.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val sco = outputs?.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
        if (sco == null) {
            Log.w(TAG, "$label: no TYPE_BLUETOOTH_SCO output present — relying on Telecom routing")
            return
        }
        val pinned = track.setPreferredDevice(sco)
        Log.i(
            TAG,
            "$label: setPreferredDevice(${sco.productName}, type=${sco.type}) → ok=$pinned (cutoff)",
        )
    }

    private fun ensureVoiceCallAudible(label: String) {
        val ctx = context ?: return
        val am =
            try {
                ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            } catch (t: Throwable) {
                Log.w(TAG, "ensureVoiceCallAudible: getSystemService threw", t)
                return
            } ?: return
        val current =
            try {
                am.getStreamVolume(AudioManager.STREAM_VOICE_CALL)
            } catch (t: Throwable) {
                Log.w(TAG, "ensureVoiceCallAudible: getStreamVolume threw", t)
                return
            }
        if (current >= VOICE_CALL_MIN_AUDIBLE_INDEX) return
        val max =
            try {
                am.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL)
            } catch (_: Throwable) {
                15
            }
        val target = (max / 2).coerceAtLeast(VOICE_CALL_MIN_AUDIBLE_INDEX)
        try {
            am.setStreamVolume(AudioManager.STREAM_VOICE_CALL, target, 0)
            Log.w(
                TAG,
                "$label: STREAM_VOICE_CALL was $current/$max (BT abs-vol sync likely zeroed it) — " +
                    "snapping to $target/$max to keep PTT audio audible",
            )
        } catch (t: Throwable) {
            Log.w(TAG, "ensureVoiceCallAudible: setStreamVolume threw", t)
        }
    }

    companion object {
        private const val TAG = "XvTpt"

        // Below this index TPT plays out into silence (and peer voice
        // on the same stream is also muted on the BT headset). Pixel +
        // AINA HFP absolute-volume sync regularly drops to 0 if a
        // hardware volume button is bumped while MODE_IN_COMMUNICATION
        // is set; this floor is the safety net. Conservative: still
        // quiet enough that an intentional volume-down keeps having
        // effect once the operator clears it above the floor.
        private const val VOICE_CALL_MIN_AUDIBLE_INDEX = 3

        // Watchdog upper bound for marker-based completion. Marker fires
        // when playback head reaches pcm.size frames; in pathological
        // cases (HAL stall, unplugged output device mid-tone) the marker
        // never fires. 2x duration + 500ms is generous enough for any
        // real-world cold-start while still preventing a stuck TX state.
        private const val WATCHDOG_SLACK_MS = 500L

        // Used by playBonk (no-permit tone) which still uses wall-clock
        // teardown — bonk only fires when XV refuses TX, so cold-start
        // clipping isn't a user-facing concern there.
        private const val COMPLETION_SLACK_MS = 200L

        // Post-marker delay (wall-clock) before onComplete — i.e., before
        // mic capture frames are unlocked through to the wire. Sized to
        // cover the AINA chipset's downstream SCO buffer so the tone
        // tail finishes physically emitting before mic frames start
        // being passed through. V1 was clean at 120ms; V2 still leaked
        // at 200ms; 350ms gives both margin without crossing the
        // user-noticeable PTT-lag threshold. Earlier we tried baking
        // the same delay into the PCM as silence padding, but that made
        // some HFP firmwares disengage their mic input — reverted.
        private const val SCO_TAIL_GUARD_MS: Long = 350L

        /**
         * Silence written to the AudioTrack ahead of every tone PCM.
         * On cold BT SCO, the HFP DAC produces an audible click/pop
         * when the AudioTrack opens and the audio path transitions
         * from "off" to "active." Pre-rolling silence absorbs the
         * transient on a silent buffer; the tone PCM then lands on a
         * stable stream. 50 ms covers worst-case BT chipset settling
         * (measured on AINA V1) without pushing back the perceived
         * TPT start by anything an operator can detect.
         */
        private const val SILENCE_PREROLL_MS: Int = 50

        // Trailing silence appended after the tone PCM so the
        // notification marker fires on silent samples — that way the
        // t.stop() call in finalize() flushes silence out of the
        // hardware output pipeline rather than the tone's last few ms.
        // Matched to the preRoll size so total added latency (before +
        // after) is symmetric.
        private const val SILENCE_POSTROLL_MS: Int = 50
    }
}
