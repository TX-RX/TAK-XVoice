package com.atakmap.android.xv.audio

import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * RX playback path. Lazily picks the right AudioTrack profile based on the
 * Bluetooth landscape:
 *   - No BT or A2DP-capable BT  → STREAM_MUSIC / USAGE_MEDIA (Android routes
 *     to A2DP automatically; otherwise speaker / earpiece per AudioRouter).
 *   - HFP-only BT (AINA, Pryme, Sheepdog speakermics) → STREAM_VOICE_CALL /
 *     USAGE_VOICE_COMMUNICATION + SCO link, because HFP is the *only* path
 *     audio can take to those devices' speakers.
 *
 * The SCO link is async (~500-1500ms to come up). To avoid losing the first
 * syllable when transitioning IDLE → SCO, we buffer decoded PCM into a small
 * ring (~4s cap, pendingBufferMaxSamples) during PENDING_SCO and drain it into the AudioTrack the
 * moment SCO connects. The buffer is ONLY active during the profile switch;
 * once Active, frames write straight through with zero added latency.
 *
 * Threading: every public entry point and every callback-triggered
 * private method (SCO state listener, timer coroutines, RouteListener)
 * acquires [lock]. The lock is private — external code can't hold it,
 * which prevents the Kotlin-`synchronized(this)` deadlock-with-foreign-
 * code class of bug. All `state` reads and writes happen inside the
 * lock; the `@Volatile` annotation is kept on `state` only as a
 * publication guarantee for the rare read paths that genuinely don't
 * need the full lock (e.g. logging in shutdown), but every mutation
 * is lock-guarded.
 */
class AudioPlayback(
    private val controller: AudioController,
    private val sampleRateHz: Int = 48_000,
    channels: Int = 1,
    private val idleTimeoutMs: Long = 600,
    // After AudioTrack stops, hold SCO for this long so a follow-up
    // transmission within the window has zero SCO-setup latency. Only
    // applies when the route was using SCO; non-SCO routes go straight
    // to IDLE.
    private val scoHoldMs: Long = 8_000,
    private val router: AudioRouter? = null,
    private val btPolicy: BtAudioPolicy? = null,
    private val scoLink: ScoLink? = null,
    // ~4 seconds @ 48 kHz mono. Sized to absorb the cold-start SCO
    // setup window (1-2 s typical) plus a margin so the first one or
    // two peer messages don't get dropped while we wait for SCO.
    // Earlier value was 2 s which was right at the edge for back-to-
    // back messages: the user reported "missing several full messages
    // at the beginning" of an RX session. Bumped to 4 s.
    private val pendingBufferMaxSamples: Int = 48_000 * 4,
    // Notified on every incoming peer voice frame. TxController uses
    // this to keep its mic capture pre-warmed and pre-routed (chipset
    // in duplex mode) while a peer is talking, so the user's response
    // press doesn't pay the AudioRecord allocation cost on its first
    // frame. Decoupled via lambda so AudioPlayback doesn't depend on
    // TxController directly.
    private val onRxActivity: () -> Unit = {},
    // Returns true when an XV self-managed Telecom call is active. In
    // that case Telecom is already handling audio focus + media-pause
    // arbitration, so we skip the manual controller.enterRx() request.
    // Matches TxController's gating — keeps Tidal/Spotify well-behaved
    // across rapid RX bursts. When false, the manual focus path runs.
    private val telecomActive: () -> Boolean = { false },
    // Fires when AudioPlayback enters / exits the SCO_HOT hold state
    // — i.e. when the SCO link will stay warm for ~8 s (scoHoldMs) after RX
    // completes. TxController subscribes via VoicePlant and uses the
    // edge to allocate AudioCapture during the hold so the BT
    // chipset's mic data path has time to settle before the operator
    // presses PTT to respond. Without this, slow chipsets (Duo+V1)
    // produce 2-3 BONKs in a row before PRIMING finally observes
    // non-silent samples. Active=true on enter, false on exit.
    private val onScoHotChanged: (active: Boolean) -> Unit = {},
    // Returns the OS-assigned AudioRecord session id currently in use
    // by AudioCapture, or null when no capture is active. When non-null,
    // AudioTrack.Builder.setSessionId(id) is called so the RX playback
    // path shares a session with the mic path — that shared session id
    // gives Android's AcousticEchoCanceler (attached to the capture
    // session in AudioCapture.configureAudioEffects) a real downlink
    // reference signal to subtract from the mic input. Without shared
    // session ids, AEC has no visibility into what's coming out of the
    // speaker and can't suppress a peer's voice echoing back through
    // the operator's speakermic. Limitation: the very first RX before
    // any TX still races (no capture session exists yet); the field
    // bug is warm back-and-forth, which this covers.
    private val captureSessionIdProvider: () -> Int? = { null },
) : AudioRouter.RouteListener {
    private val channelMask =
        if (channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO

    private val minBufferBytes =
        AudioTrack
            .getMinBufferSize(sampleRateHz, channelMask, AudioFormat.ENCODING_PCM_16BIT)
            .coerceAtLeast(sampleRateHz * 2 * channels / 5) // ≥ 200 ms

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Single private monitor guarding all state transitions. Replaces
    // the prior `@Synchronized` (i.e. `synchronized(this)`) pattern so
    // external code can't accidentally acquire our lock and deadlock
    // against an internal callback.
    private val lock = Any()

    private enum class State {
        // No track, no focus, no SCO.
        IDLE,

        // SCO link starting; PCM buffered into pendingBuffer, will drain
        // on SCO_AUDIO_STATE_CONNECTED.
        PENDING_SCO,

        // Track playing, focus held, SCO up if route requires it.
        ACTIVE,

        // Track stopped + focus released after idleTimeoutMs of silence,
        // BUT SCO link kept hot. New frames re-enter ACTIVE without SCO
        // setup latency. After scoHoldMs more silence → IDLE.
        SCO_HOT,
    }

    @Volatile
    private var state: State = State.IDLE

    @Volatile
    private var track: AudioTrack? = null

    @Volatile
    private var trackUsesSco: Boolean = false

    @Volatile
    private var idleResetJob: Job? = null

    @Volatile
    private var scoReleaseJob: Job? = null

    private val pendingBuffer = ArrayDeque<ShortArray>()
    private var pendingSampleCount = 0

    // Wall-clock time the first RX frame entered PENDING_SCO. Cleared
    // when we transition out of PENDING_SCO. Used purely for logging:
    // (onScoConnected timestamp − this) is the SCO-setup latency the
    // listener actually paid for the cold burst.
    @Volatile
    private var pendingSinceMs: Long = 0L

    // Throttles the "RX focus denied — playing anyway" lines. Without
    // this, every peer RX burst during a SUSPENDED period (Telecom
    // mid-handoff, focus held elsewhere) logs at WARN — and an active
    // tactical channel produces many bursts per minute. Logs once per
    // run of denials and re-arms after a successful enterRx().
    @Volatile
    private var lastFocusDenied: Boolean = false

    private val scoListener =
        object : ScoLink.StateListener {
            override fun onScoStateChanged(state: ScoLink.State) {
                when (state) {
                    ScoLink.State.CONNECTED -> onScoConnected()
                    ScoLink.State.DISCONNECTED -> onScoDisconnected()
                    // SUSPENDED = system tore the link down (incoming
                    // phone call, alarm, etc). The physical surface is
                    // gone; treat exactly like DISCONNECTED so we
                    // abandon any in-flight playback cleanly.
                    ScoLink.State.SUSPENDED -> onScoDisconnected()
                    ScoLink.State.CONNECTING -> { /* no-op */ }
                }
            }
        }

    /**
     * Push one decoded PCM frame to the audio path. Picks the right
     * AudioTrack profile on first frame, buffers during SCO setup if the
     * route is HFP-only BT, and writes directly once active.
     */
    /** Notify that our session just moved to a different channel.
     *  AudioPlayback drops incoming RX frames for the next
     *  [CHANNEL_MOVE_DEBOUNCE_MS] to suppress any in-flight audio from
     *  the old channel that was already on the wire. Called by
     *  VoicePlant from MumbleTransport's channel-change callback.
     *  Audit L4. */
    fun notifyChannelMoved() {
        lastChannelMoveMs = android.os.SystemClock.elapsedRealtime()
    }

    @Volatile
    private var lastChannelMoveMs: Long = 0L

    fun playPcm(samples: ShortArray) {
        if (samples.isEmpty()) return
        // L4: drop frames that arrive within CHANNEL_MOVE_DEBOUNCE_MS
        // of a channel-move signal. The server can have an in-flight
        // peer-voice frame from the old channel queued at the moment
        // we leave; without this gate, the operator briefly hears
        // a few hundred ms of audio from the channel they just left.
        // The debounce window is short enough that a real in-channel
        // peer who started talking at the move boundary loses at
        // most one syllable.
        val sinceMove = android.os.SystemClock.elapsedRealtime() - lastChannelMoveMs
        if (lastChannelMoveMs > 0L && sinceMove < CHANNEL_MOVE_DEBOUNCE_MS) {
            // Don't log per-frame — debounce window covers ~10 frames at
            // 10ms each; logging would flood. The next non-dropped
            // frame implicitly signals the debounce is over.
            return
        }
        // Tell the TX side a peer is talking right now. TxController
        // uses this to keep its AudioRecord allocated and pre-routed so
        // the user's response press doesn't pay first-frame chipset
        // duplex-transition latency. Throwaway-safe — the lambda is
        // a no-op by default. Fired OUTSIDE [lock] so the callback can
        // call back into TxController without nested-lock concerns.
        try {
            onRxActivity()
        } catch (t: Throwable) {
            Log.w(TAG, "onRxActivity callback threw", t)
        }
        synchronized(lock) {
            when (state) {
                State.IDLE -> beginPlayback(samples)
                State.PENDING_SCO -> bufferDuringScoSetup(samples)
                State.ACTIVE -> writeToTrack(samples)
                State.SCO_HOT -> resumeFromScoHot(samples)
            }
            // Don't arm the idle timer in PENDING_SCO — SCO setup latency
            // can exceed idleTimeoutMs on a short transmission, which
            // would tear down the in-flight setup. ScoLink has its own
            // readiness timeout (~3s) that fires onDisconnected if SCO
            // truly doesn't come up.
            if (state != State.PENDING_SCO) {
                rearmIdleTimer()
            }
            rearmScoReleaseTimer()
        }
    }

    // Returns true while a peer's audio is being played out (or buffered
    // on the way to playback). Used by TxController to pick the
    // interrupt-chirp tone over the regular TPT when the user keys PTT
    // while someone else is talking — an audible cue that they've cut
    // in. PENDING_SCO counts because a peer started transmitting; we
    // just haven't routed yet. SCO_HOT does not count: track is gone
    // and no peer audio is currently playing, even though SCO is held
    // warm in case more arrives.
    fun isActive(): Boolean =
        synchronized(lock) {
            state == State.ACTIVE || state == State.PENDING_SCO
        }

    /**
     * Pre-warm the AudioTrack for an upcoming call. Called when the
     * caller's Telecom Connection goes ACTIVE during a private call,
     * BEFORE the first peer frame arrives. Builds the AudioTrack on
     * the no-SCO path (the common case for phone-style calls) so the
     * first inbound frame plays out immediately rather than paying the
     * 50-150 ms AudioTrack hardware-startup cost.
     *
     * Skipped for the HFP-only-BT route — SCO setup dwarfs AudioTrack
     * startup, and the existing PENDING_SCO buffer covers it.
     *
     * Idempotent: a second call while already ACTIVE is a no-op.
     */
    fun warmupForCall() {
        synchronized(lock) {
            if (state != State.IDLE) {
                Log.d(TAG, "warmupForCall: state=$state — skipping")
                return
            }
            val btMode = btPolicy?.classify() ?: BtAudioMode.NONE
            if (btMode == BtAudioMode.HFP_ONLY) {
                // SCO path; pre-warm doesn't help (the buffer drain
                // amortizes startup latency already).
                Log.d(TAG, "warmupForCall: HFP-only route — skipping (SCO buffer handles cold-start)")
                return
            }
            startTrack(useSco = false)
            if (track == null) {
                Log.w(TAG, "warmupForCall: AudioTrack build failed")
                return
            }
            state = State.ACTIVE
            // Don't arm the idle timer here — call-active mode keeps
            // the track alive until the Telecom call ends. The first
            // real frame will rearm it via playPcm.
            Log.i(TAG, "warmupForCall: AudioTrack pre-allocated for call (no-SCO route)")
        }
    }

    fun shutdown() {
        idleResetJob?.cancel()
        idleResetJob = null
        scoReleaseJob?.cancel()
        scoReleaseJob = null
        synchronized(lock) {
            teardown()
        }
        scope.cancel()
    }

    // ---- AudioRouter.RouteListener ----

    override fun onPreferredDeviceChanged(device: AudioDeviceInfo?) {
        synchronized(lock) {
            // Hot BT removal — operator powered off / walked the
            // speakermic out of range. The current AudioTrack may be
            // bound to STREAM_VOICE_CALL/USAGE_VOICE_COMMUNICATION
            // because we built it for the SCO path; with SCO gone, that
            // track now routes either to the phone earpiece (whisper-
            // quiet) or nowhere audible at all. setPreferredDevice on a
            // still-SCO-attributed track won't change the routing —
            // Android picks the device pool by attributes, not by the
            // preferred-device hint.
            //
            // Tear down the SCO-bound track + release SCO when the
            // route flipped off BT. The next inbound frame re-enters
            // beginPlayback, BtAudioPolicy.classify() returns NONE, and
            // a fresh STREAM_MUSIC track is built that routes to
            // speaker / wired / earpiece per AudioRouter.
            val nowOffBt = device == null || device.type !in BT_OUTPUT_TYPES
            val scoBoundTrack = trackUsesSco && track != null
            val scoHotHeld = state == State.SCO_HOT
            if (nowOffBt && (scoBoundTrack || scoHotHeld)) {
                Log.i(
                    TAG,
                    "preferred device changed to ${device?.type}; SCO/track stale (state=$state) — tearing down",
                )
                teardown()
                return
            }
            // Operator-driven internal route flip (SPEAKER ↔ EARPIECE)
            // mid-playback. setCommunicationDevice on its own moves the
            // policy state but the live AudioTrack stays pinned to the
            // device it was originally routed to (verified 2026-05-11
            // on Surface Duo 2: setCommunicationDevice(EARPIECE)
            // returned ok=true but RX continued out the speaker). The
            // only reliable fix on USAGE_VOICE_COMMUNICATION tracks is
            // to drop the AudioTrack so the next inbound frame builds a
            // fresh one against the now-current comm device.
            val internalRouteFlip =
                device != null &&
                    !trackUsesSco &&
                    track != null &&
                    state == State.ACTIVE &&
                    (
                        device.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE ||
                            device.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
                        ) &&
                    track?.routedDevice?.type != device.type
            if (internalRouteFlip) {
                Log.i(
                    TAG,
                    "preferred device changed to ${device?.type} (track on ${track?.routedDevice?.type}); " +
                        "rebuilding track for new internal route",
                )
                teardown()
                return
            }
            // Same-class device swap (e.g. one BT speakermic to another,
            // or wired headset hotplug while non-SCO) — nudge the track
            // via setPreferredDevice and let it keep playing.
            applyPreferredDevice(track, device)
        }
    }

    // ---- Internals ----

    private fun beginPlayback(first: ShortArray) {
        // Skip the per-RX focus request when XvVoiceService already
        // holds the long-lived voice focus for this Telecom call.
        // Two requests from the same UID with overlapping usage flags
        // collide — the system denies the transient one, and worse
        // generates audible glitches because the focus stack ping-pongs
        // on every burst. The service-level GAIN focus is sufficient.
        // When Telecom isn't active (no PhoneAccount, fallback path),
        // request focus locally as belt-and-suspenders. NEVER drop
        // the frame on denial — peer voice is mission audio.
        if (!telecomActive()) {
            if (!controller.enterRx()) {
                if (!lastFocusDenied) {
                    Log.w(TAG, "RX focus denied — playing anyway, voice traffic must not drop")
                    lastFocusDenied = true
                }
            } else {
                lastFocusDenied = false
            }
        }
        // Demand-based SCO. HFP-only BT routes need SCO before audio can
        // flow; non-SCO routes (A2DP, speaker, wired) bypass it entirely.
        // Cold-start incoming voice gets buffered into pendingBuffer for
        // up to ~4s while SCO comes up; once ACTIVE, frames pass through
        // with no added latency. After idleTimeoutMs of silence we drop
        // to SCO_HOT, holding SCO for scoHoldMs (8s) so a follow-up
        // transmission resumes instantly. After that → IDLE.
        val btMode = btPolicy?.classify() ?: BtAudioMode.NONE
        val link = scoLink
        if (btMode == BtAudioMode.HFP_ONLY && link != null) {
            // Need SCO before audio can flow to the BT speakermic. Buffer
            // PCM until SCO state CONNECTED, then drain.
            //
            // Order matters: acquire BEFORE addStateListener. ScoLink's
            // addStateListener immediately fires the current state to the
            // new subscriber — a feature so a late subscriber knows SCO
            // is already up. But if SCO is DISCONNECTED at that moment,
            // the immediate-fire lands as onScoDisconnected with our
            // state already PENDING_SCO, which trips the "SCO dropped
            // during playback" branch and tears the just-set-up session
            // back down to IDLE. By acquiring first the link transitions
            // DISCONNECTED → CONNECTING; addStateListener then fires
            // CONNECTING (no-op in our handler) instead of DISCONNECTED.
            // Net effect: the first ~200-500ms of every cold RX burst
            // now actually buffers + drains instead of being dropped.
            state = State.PENDING_SCO
            pendingBuffer.clear()
            pendingSampleCount = 0
            pendingSinceMs = System.currentTimeMillis()
            appendPending(first)
            Log.i(TAG, "HFP-only BT route — engaging SCO before playback (cold burst, frame 1 buffered)")
            link.acquire(this)
            link.addStateListener(scoListener)
        } else {
            // No SCO needed — STREAM_MUSIC routes via the system to A2DP /
            // speaker / wired / earpiece per AudioRouter preferred device.
            startTrack(useSco = false)
            // Pre-roll silence (~80ms) is helpful for one-shot radio bursts
            // because it absorbs AudioTrack startup latency so the operator
            // hears the full transmission. But in an active Telecom call
            // we want fully realtime audio — accept the lost startup
            // syllable rather than add 80ms of latency to every frame
            // path. Per operator request 2026-05-11: "calls need to be
            // fully realtime and are not a time to use the pre-call
            // warmup buffer."
            if (!telecomActive()) {
                val silence = ShortArray(SILENCE_PREROLL_SAMPLES)
                track?.write(silence, 0, silence.size, AudioTrack.WRITE_BLOCKING)
            }
            track?.write(first, 0, first.size, AudioTrack.WRITE_BLOCKING)
            state = State.ACTIVE
        }
    }

    private fun bufferDuringScoSetup(samples: ShortArray) {
        appendPending(samples)
    }

    private fun resumeFromScoHot(samples: ShortArray) {
        // SCO is still up from a recent transmission. Build a new
        // STREAM_VOICE_CALL track — no buffer, no setup wait.
        // Per-RX focus request mirrors beginPlayback: skip when the
        // service-level voice focus already covers us, and never
        // drop the frame on denial — that turned a focus collision
        // into hundreds of dropped frames per second during latched
        // / full-duplex use.
        if (!telecomActive()) {
            if (!controller.enterRx()) {
                if (!lastFocusDenied) {
                    Log.w(TAG, "SCO_HOT resume: RX focus denied — playing anyway")
                    lastFocusDenied = true
                }
            } else {
                lastFocusDenied = false
            }
        }
        startTrack(useSco = true)
        if (track == null) {
            Log.w(TAG, "SCO_HOT resume: track build failed")
            return
        }
        state = State.ACTIVE
        writeToTrack(samples)
        Log.i(TAG, "resumed from SCO_HOT — no setup latency")
    }

    private fun appendPending(s: ShortArray) {
        pendingBuffer.addLast(s)
        pendingSampleCount += s.size
        while (pendingSampleCount > pendingBufferMaxSamples && pendingBuffer.isNotEmpty()) {
            val dropped = pendingBuffer.removeFirst()
            pendingSampleCount -= dropped.size
        }
    }

    private fun writeToTrack(s: ShortArray) {
        try {
            // WRITE_BLOCKING (not NON_BLOCKING) so post-drain catch-up
            // doesn't drop frames. Scenario: during a long peer burst on
            // a cold-SCO start, the SCO+drain phase holds the AudioPlayback
            // monitor for ~1.5s. Frames arriving during that window queue
            // on the monitor. Once the drain finishes (state = ACTIVE) and
            // the lock releases, the queued playPcm calls all run back-to-
            // back at CPU speed. With WRITE_NON_BLOCKING and a ~200ms
            // AudioTrack buffer that's already full from the drain tail,
            // the very first queued frame fits; the rest silently fall on
            // the floor. WRITE_BLOCKING converts that loss into latency:
            // each write waits for 20ms of room, the Mumble decoder thread
            // back-pressures naturally to peer's real-time rate, and the
            // listener hears the full message (just shifted later by the
            // SCO setup time).
            track?.write(s, 0, s.size, AudioTrack.WRITE_BLOCKING)
        } catch (e: IllegalStateException) {
            Log.w(TAG, "AudioTrack.write failed; track in bad state", e)
        }
    }

    private fun onScoConnected() {
        synchronized(lock) {
            if (state != State.PENDING_SCO) {
                Log.i(TAG, "SCO connected but state=$state — ignoring")
                return
            }
            val setupMs = if (pendingSinceMs > 0) System.currentTimeMillis() - pendingSinceMs else -1
            val bufferedMs = (pendingSampleCount * 1000L) / sampleRateHz
            Log.i(
                TAG,
                "SCO connected after ${setupMs}ms — flushing ${pendingBuffer.size} buffered frames " +
                    "($pendingSampleCount samples ≈ ${bufferedMs}ms of audio)",
            )
            pendingSinceMs = 0L
            val drainStart = System.currentTimeMillis()
            startTrack(useSco = true)
            val t =
                track ?: run {
                    Log.w(TAG, "SCO connected but AudioTrack failed to start")
                    teardown()
                    return
                }
            // Drain in big writes; AudioTrack's internal buffer absorbs
            // them and plays at native rate — listener hears the full
            // transmission with the SCO-setup latency absorbed up front.
            while (pendingBuffer.isNotEmpty()) {
                val chunk = pendingBuffer.removeFirst()
                try {
                    t.write(chunk, 0, chunk.size, AudioTrack.WRITE_BLOCKING)
                } catch (e: IllegalStateException) {
                    Log.w(TAG, "drain write failed", e)
                    break
                }
            }
            val drainMs = System.currentTimeMillis() - drainStart
            Log.i(TAG, "buffer drained in ${drainMs}ms — SCO+drain total ${setupMs + drainMs}ms")
            pendingSampleCount = 0
            state = State.ACTIVE
            // Now that we're playing, arm the idle timer — until now we
            // suppressed it so SCO setup latency wouldn't trigger
            // teardown.
            rearmIdleTimer()
        }
    }

    private fun onScoDisconnected() {
        synchronized(lock) {
            // External SCO drop, or our own teardown. If we were mid-
            // playback on the SCO path, we have to abandon —
            // STREAM_VOICE_CALL without SCO is silent. Drop to IDLE;
            // the next frame will re-evaluate the route and may choose
            // a non-SCO path.
            if (state == State.PENDING_SCO || (state == State.ACTIVE && trackUsesSco)) {
                Log.w(TAG, "SCO link disconnected during playback — tearing down")
                teardown()
            }
        }
    }

    private fun startTrack(useSco: Boolean) {
        // ALWAYS USAGE_VOICE_COMMUNICATION regardless of SCO state.
        // Operator feedback (2026-05-11): without SCO, the prior code
        // fell back to USAGE_MEDIA — which routed RX through STREAM_MUSIC,
        // tied volume to the media slider (often low/muted on tactical
        // devices), and made the system show "media controls" instead of
        // call controls. Voice apps (Discord, Teams, Signal) always run
        // their RX on the voice-comm path so hardware volume keys + audio
        // mode + ducking arbitration all behave as call audio. We match
        // that. CONTENT_TYPE_SPEECH stays. The `useSco` parameter still
        // drives whether we acquire the SCO link upstream — it just no
        // longer changes the AudioTrack's stream classification.
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
                .setChannelMask(channelMask)
                .build()
        // If AudioCapture has an active session, bind the AudioTrack to
        // the same session id so Android's AcousticEchoCanceler (attached
        // to the capture session in AudioCapture.configureAudioEffects)
        // has a real downlink reference signal to subtract from the mic
        // input. First RX before any TX still races (captureSessionId
        // == null); on any RX burst that follows a PTT press, AEC is
        // now looking at the same session pair. Session ids are not
        // sensitive per CLAUDE.md — log unredacted for field debug.
        val captureSessionId =
            try {
                captureSessionIdProvider()
            } catch (t: Throwable) {
                Log.w(TAG, "captureSessionIdProvider threw", t)
                null
            }
        val newTrack =
            try {
                val b =
                    AudioTrack
                        .Builder()
                        .setAudioAttributes(attrs)
                        .setAudioFormat(format)
                        .setBufferSizeInBytes(minBufferBytes)
                        .setTransferMode(AudioTrack.MODE_STREAM)
                if (captureSessionId != null && captureSessionId != 0) {
                    b.setSessionId(captureSessionId)
                    Log.i(TAG, "AudioTrack using capture session id=$captureSessionId for AEC linkage")
                } else {
                    Log.i(TAG, "AudioTrack built with fresh session (no capture session yet — first RX or post-stop)")
                }
                b.build()
            } catch (t: Throwable) {
                Log.e(TAG, "AudioTrack build failed (useSco=$useSco)", t)
                return
            }
        applyPreferredDevice(newTrack, router?.preferredDevice())
        try {
            newTrack.play()
        } catch (e: IllegalStateException) {
            Log.w(TAG, "AudioTrack.play failed", e)
            try {
                newTrack.release()
            } catch (_: Throwable) {
            }
            return
        }
        track = newTrack
        trackUsesSco = useSco
        Log.i(TAG, "AudioTrack started (useSco=$useSco)")
    }

    private fun applyPreferredDevice(
        t: AudioTrack?,
        device: AudioDeviceInfo?,
    ) {
        val tr = t ?: return
        try {
            tr.preferredDevice = device
        } catch (t2: Throwable) {
            Log.w(TAG, "setPreferredDevice failed", t2)
        }
    }

    private fun onIdleTimerFired() {
        synchronized(lock) {
            when (state) {
                State.IDLE, State.SCO_HOT -> return // long timer handles SCO_HOT
                State.PENDING_SCO -> {
                    // SCO never connected and user stopped talking.
                    // Discard buffer and tear down the half-engaged SCO.
                    Log.i(TAG, "PENDING_SCO idle-timeout — abandoning SCO setup")
                    teardown()
                }
                State.ACTIVE -> {
                    if (trackUsesSco) {
                        // Drop the AudioTrack + focus, but keep SCO hot
                        // for a follow-up transmission.
                        enterScoHot()
                    } else {
                        teardown()
                    }
                }
            }
        }
    }

    private fun onScoReleaseTimerFired() {
        synchronized(lock) {
            if (state != State.SCO_HOT) return
            Log.i(TAG, "SCO hold expired — releasing SCO")
            teardown()
        }
    }

    private fun enterScoHot() {
        Log.i(TAG, "entering SCO_HOT (track released, focus + SCO held ${scoHoldMs}ms)")
        val t = track
        track = null
        if (t != null) {
            try {
                if (t.playState == AudioTrack.PLAYSTATE_PLAYING) t.stop()
            } catch (_: IllegalStateException) {
            }
            try {
                t.release()
            } catch (_: Throwable) {
            }
        }
        // Hold audio focus through the SCO_HOT window (8s, scoHoldMs). If we
        // released focus here, media apps would un-pause but SCO is
        // still up + mode is IN_COMMUNICATION → music routes through
        // SCO to the AINA. Keeping focus held means media stays
        // paused for the full quiet window. Focus is finally released
        // when SCO actually goes down (in teardown).
        state = State.SCO_HOT
        // Tell TxController to allocate AudioCapture now so the BT
        // chipset's mic data path settles during the 8 s hold. Slow
        // chipsets (Duo+V1) need 1-3 s of mic-pumping before they
        // stop returning silent samples; doing that BEFORE PTT-down
        // means the operator's response press finishes PRIMING in
        // ~100 ms instead of timing out at 1.5 s.
        try {
            onScoHotChanged(true)
        } catch (t2: Throwable) {
            Log.w(TAG, "onScoHotChanged(true) threw", t2)
        }
    }

    private fun teardown() {
        // If we were holding SCO_HOT, drop the pre-warm before
        // releasing the link — TxController needs to release its
        // pre-warmed AudioCapture so the next allocation starts
        // fresh against whatever route is current.
        if (state == State.SCO_HOT) {
            try {
                onScoHotChanged(false)
            } catch (t2: Throwable) {
                Log.w(TAG, "onScoHotChanged(false) threw", t2)
            }
        }
        val t = track
        track = null
        if (t != null) {
            try {
                if (t.playState == AudioTrack.PLAYSTATE_PLAYING) t.stop()
            } catch (_: IllegalStateException) {
            }
            try {
                t.release()
            } catch (_: Throwable) {
            }
        }
        if (trackUsesSco) {
            scoLink?.release(this)
            scoLink?.removeStateListener(scoListener)
        }
        trackUsesSco = false
        pendingBuffer.clear()
        pendingSampleCount = 0
        pendingSinceMs = 0L
        if (state != State.IDLE) {
            if (!telecomActive()) controller.returnToIdle()
        }
        state = State.IDLE
    }

    private fun rearmIdleTimer() {
        idleResetJob?.cancel()
        idleResetJob =
            scope.launch {
                delay(idleTimeoutMs)
                onIdleTimerFired()
            }
    }

    private fun rearmScoReleaseTimer() {
        scoReleaseJob?.cancel()
        scoReleaseJob =
            scope.launch {
                delay(scoHoldMs)
                onScoReleaseTimerFired()
            }
    }

    companion object {
        private const val TAG = "XvAudioPlayback"

        private val BT_OUTPUT_TYPES =
            setOf(
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            )

        // Pre-roll silence in ms × 48 kHz mono frames. AudioTrack
        // MODE_STREAM has roughly 50-150ms of startup latency between
        // play() and audible output; a freshly-built track with no
        // pre-roll consumes the first real voice frame during that
        // ramp-up window and the listener hears the burst start
        // mid-syllable. 100ms is enough on every device tested.
        private const val SILENCE_PREROLL_MS = 100
        private const val SAMPLE_RATE_HZ = 48_000
        private const val SILENCE_PREROLL_SAMPLES =
            SAMPLE_RATE_HZ * SILENCE_PREROLL_MS / 1000

        // L4: window after a channel-move event during which incoming
        // RX frames are dropped on the floor. The server can have an
        // in-flight peer-voice frame from the old channel already
        // queued at the moment we leave — without this gate the
        // operator hears ~100 ms of the old channel after the move.
        // Short enough that a real in-channel peer who started
        // talking at the move boundary loses at most one syllable.
        private const val CHANNEL_MOVE_DEBOUNCE_MS: Long = 100L
    }
}
