package com.atakmap.android.xv.audio

import android.media.AudioManager
import android.util.Log
import androidx.annotation.VisibleForTesting

/**
 * Coarse-grained TX audio route classification used by the cold-SCO
 * start-of-burst mitigations. Deliberately narrower than
 * [BtAudioMode] / [OutputRoute] — those enums describe policy and
 * user preference; [TxRoute] describes the ONE thing the mitigation
 * gate needs to know: "is this burst going through the BT SCO
 * uplink, where the Pixel AOC modem underruns at cold-start?"
 *
 * - [SCO]      — BT HFP SCO uplink is live. AOC modem is in play; cold
 *                bursts get the extended PRIMING hold + drop-first-N-
 *                frames treatment.
 * - [OFFLOAD]  — Any non-SCO route (built-in mic, wired headset, or
 *                the case where BT is available but we're on A2DP-only
 *                and TX falls back to the built-in mic). No AOC modem
 *                involvement, no mitigation needed.
 *
 * The name "OFFLOAD" is used instead of a bare `NON_SCO` sentinel to
 * make the enum future-friendly: if we later distinguish e.g. built-in
 * vs. wired, both remain non-SCO and stay outside the mitigation gate.
 */
enum class TxRoute {
    SCO,
    OFFLOAD,
}

// Orchestrates the TX side. "Open channel" lifecycle:
//   - On first start() (or explicit channelOpened()): allocate
//     AudioCapture once and hold it for the channel session, with the
//     OS-level mic mute engaged via AudioManager.setMicrophoneMute(true).
//     Frames flow into onPcmFrame but get dropped by state-check while
//     muted; the OS-level mute is what keeps the BT SCO chipset's
//     routing pinned in "active but silent" so subsequent bursts find
//     the mic warm. Without OS-level mute, the chipset detects "no
//     active reader" and goes stale — the failure mode we hit when
//     trying app-level frame-drop alone.
//   - Per-press: ACQUIRING_SCO (if cold) → TPT → TRANSMITTING.
//     setMicrophoneMute(false) on TRANSMITTING; setMicrophoneMute(true)
//     on stop. AudioCapture is NOT torn down between presses.
//   - The BT SCO physical link stays intermittent (acquire on TX/RX,
//     drop after 5s cool-down) — the open-channel session is at the
//     OS audio-session layer, not the physical-link layer, so there's
//     no continuous white noise on the speakermic.
//   - On shutdown / channelClosed(): tear AudioCapture down, restore
//     setMicrophoneMute(false).
class TxController(
    private val scoLink: ScoLink,
    private val btPolicy: BtAudioPolicy,
    private val tptPlayer: TptPlayer,
    private val audioCaptureFactory: ((ShortArray) -> Unit) -> AudioCapture,
    private val opusEncoderFactory: () -> OpusEncoder,
    // AudioManager for OS-level setMicrophoneMute(). The mute is what
    // keeps the BT chipset routing settled while AudioRecord is held
    // open between bursts.
    private val audioManager: AudioManager,
    // Audio focus + MODE_IN_COMMUNICATION owner. enterTx() at TX start
    // makes other media (Spotify, etc.) pause while we're keyed; mode
    // restoration on returnToIdle() puts the system back as we found it.
    // SCO acquisition continues to flow through ScoLink — the
    // setBluetoothScoOn call inside enterTx() is idempotent against our
    // already-up SCO link.
    private val audioController: AudioController,
    // Per-frame send. The Int is the targetSlot — 0 = primary channel,
    // 1 = secondary (VS2). Caller (transport) decides what to do with it;
    // multicast ignores, Mumble routes via VoiceTarget.
    private val sendOpus: (ByteArray, Int) -> Unit,
    // End-of-utterance terminator for the burst. Slot must match the
    // burst's slot or the receiver leaves a stuck talker indicator.
    private val sendTerminator: (Int) -> Unit = { _ -> },
    // Notify the transport about PTT state changes so it can send a
    // UserState to the server. Open-source Mumble clients (Mumla et al)
    // do this on PTT-down / PTT-up so the server's UI / suppression
    // logic knows the client is keying.
    private val onPttStateChanged: (Boolean, Int) -> Unit = { _, _ -> },
    private val tonePreference: () -> TptTone = { TptTone.DEFAULT },
    // Returns true only when XV is actually in a state where transmitted
    // audio would be heard by someone on the given slot. Two reasons
    // can flip this false:
    //   1) Mumble not connected / not in a channel.
    //   2) Server has us suppressed on that slot's channel (OTS
    //      direction enforcement = OUT, or Mumble admin mute).
    // When false, TX is a no-op — no TPT, no SCO, no mic capture; we
    // play the deny tone. Avoids the "tone played but no one heard
    // you" experience.
    private val canTransmit: (slot: Int) -> Boolean = { _ -> true },
    // Returns true if a peer's voice is currently being played out on
    // the local audio path. When true, TxController plays a short
    // interrupt chirp instead of the full TPT — audibly distinct cue
    // that the operator has cut into someone else's transmission.
    private val isRxActive: () -> Boolean = { false },
    // Returns true when XV has an active self-managed Telecom call.
    // When true, TxController skips the manual audioController.enterTx
    // / exitTx focus + mode dance — Telecom's call is already
    // arbitrating audio focus, MODE_IN_COMMUNICATION, and BT routing
    // for us, so duplicating the call would just thrash other media
    // apps (Tidal, Spotify) on every PTT cycle. Returns false when
    // Telecom registration isn't active (older devices, OEM-locked
    // ROMs), in which case the manual fallback path runs.
    private val telecomActive: () -> Boolean = { false },
    // Fired when stopInternal completes its return-to-IDLE bookkeeping.
    // [AudioRouter] subscribes via this hook to flush any deferred
    // hot-attach route change that arrived while TX was in flight —
    // see [isTxActive] for the why.
    private val onIdle: () -> Unit = {},
    // Operator preference: when true, SCO is kept warm for the entire
    // duration the operator is in a Mumble channel (not just for the
    // 5 s post-burst cool-down). Eliminates the 500-1500 ms SCO acquire
    // on the first PTT after a long pause — the difference between
    // "press → talk in 100 ms" and "press → talk in 1.5 s." Cost: media
    // apps stay paused for the entire call instead of just during PTT
    // bursts. Read on every cool-down decision so a runtime toggle
    // takes effect on the next burst without restarting anything.
    private val hotMicMode: () -> Boolean = { false },
) {
    // Lifecycle phases of one TX cycle. Visibility relaxed from `private`
    // to `internal` so TxControllerStateMachineTest can drive transitions
    // directly via `setStateForTest`; not consumed elsewhere in the
    // production module.
    @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
    internal enum class State { IDLE, ACQUIRING_SCO, PRIMING, TPT, TRANSMITTING }

    @Volatile
    private var state: State = State.IDLE

    /**
     * True when we're in any phase of a TX cycle (acquiring SCO, priming
     * mic, playing TPT, transmitting). False only when fully idle. Used
     * by [AudioRouter] to defer hot-attach route changes — swapping the
     * comm device mid-cycle breaks the live capture/playback pipeline,
     * so [AudioRouter.notifyChange] queues the change and [onIdle]
     * flushes it once we're back to State.IDLE.
     */
    fun isTxActive(): Boolean = state != State.IDLE

    @Volatile
    private var capture: AudioCapture? = null

    @Volatile
    private var encoder: OpusEncoder? = null

    @Volatile
    private var holdsSco: Boolean = false

    // Slot of the currently-active TX burst. Set when start() is called,
    // cleared back to 0 in stopInternal(). Each PCM frame and the
    // terminator carry this so the wire layer routes them consistently —
    // a slot mismatch between voice frames and their terminator leaves
    // stuck talker indicators on the receiving side.
    @Volatile
    private var activeSlot: Int = 0

    // Set true by the deny path when the operator presses on a
    // listen-only channel from cold SCO. The deny tone is played in
    // the scoListener's CONNECTED branch so it actually reaches the
    // AINA. Without this defer, the tone fires immediately while
    // SCO is still CONNECTING and gets routed to the phone speaker
    // — operator hears nothing on the speakermic.
    @Volatile
    private var pendingDenyTone: Boolean = false

    private val scoListener =
        object : ScoLink.StateListener {
            override fun onScoStateChanged(s: ScoLink.State) {
                when (s) {
                    ScoLink.State.CONNECTED -> {
                        if (pendingDenyTone) {
                            pendingDenyTone = false
                            Log.i(TAG, "SCO connected — playing deferred deny tone")
                            tptPlayer.playDeny(useScoRoute = true)
                        }
                        synchronized(this@TxController) {
                            if (state == State.ACQUIRING_SCO) startPriming()
                        }
                    }
                    // SUSPENDED is treated identically to DISCONNECTED:
                    // physical link is gone, abandon the burst and let
                    // a future PTT acquire fresh.
                    ScoLink.State.DISCONNECTED, ScoLink.State.SUSPENDED -> {
                        // SCO dropped before it ever connected — give
                        // up on the deferred tone so it doesn't fire
                        // on the next unrelated CONNECTED.
                        pendingDenyTone = false
                        synchronized(this@TxController) {
                            if (state != State.IDLE && holdsSco) {
                                Log.w(TAG, "SCO $s during TX — abandoning")
                                stopInternal()
                            }
                        }
                    }
                    else -> {}
                }
            }
        }

    @Volatile
    private var primingStartMs: Long = 0

    @Volatile
    private var primingFramesObserved: Int = 0

    // Whether this burst faces a COLD BT SCO chipset ramp — i.e. we had
    // to acquire SCO from scratch this burst (currentBurstColdSco).
    // Captured at startPriming() so the gate values are stable through
    // the whole priming window.
    //
    // The cold chipset delivers 300-1000 ms of warmup where frames
    // arrive late and at only noise-floor amplitude (~5-15, which sits
    // above the non-SCO MIC_PRIMING_RMS_THRESHOLD) — the 2026-07-08
    // field repro where onPcmFrame declared "mic ready" on chipset
    // warmup hiss and shipped ~500 ms of near-silent frames. The
    // ramp-tolerant SCO gates (30-frame count, rms>=200, 1500 ms
    // timeout) fix that.
    //
    // CRITICAL (2026-07-13 field repro): this must key off COLD SCO, not
    // "SCO is CONNECTED." When SCO is held WARM across bursts (the whole
    // point of the cool-down tail), the chipset is already settled and
    // there is no ramp — but the old `scoLink.state == CONNECTED` check
    // still forced the 30-frame (~300 ms) cold gate onto every warm
    // burst. With bursty radio keying that ~300 ms + TPT meant the
    // operator released before TX ever started and NOTHING went out
    // (zero frames per burst, observed across a full session). Warm-SCO
    // and non-SCO bursts use the fast gates (5 frames / ~50 ms).
    @Volatile
    private var primingUseColdScoGates: Boolean = false

    // Was this burst started with SCO in a cold state (i.e. we had to
    // acquire SCO from scratch instead of reusing a warm cool-down or
    // RX SCO_HOT link)? Captured at start() BEFORE we transition into
    // ACQUIRING_SCO / PRIMING, because by the time the scoListener drives
    // startPriming() the link reads CONNECTED and the cold vs. warm
    // distinction is otherwise lost from scoLink.state. This is the
    // single source of truth for "this burst faces a cold chipset ramp"
    // and drives: the priming gate selection (primingUseColdScoGates),
    // (a) the extra COLD_SCO_TPT_HOLD_MS TPT hold while the Pixel AOC
    // modem stabilizes, and (b) the silent drop of the first few TX
    // frames past the initial AOC underrun window.
    //
    // Field observation 2026-07-10: cold-SCO first-burst on Pixel 9 Pro
    // showed AOC "[AMixMODEM] uplink underrun for 86 frames" in the
    // 100 ms surrounding the mic-ready → TPT → TX transition. TPT plays
    // OK (dedicated AudioTrack path), but the first ~30-60 ms of live
    // TX frames catch the tail of the modem underrun and encode to
    // garbled audio on the peer. Warm-SCO bursts don't underrun.
    @Volatile
    private var currentBurstColdSco: Boolean = false

    // Frame counter for the drop-first-N-frames-on-cold-SCO mitigation.
    // Incremented on every PCM frame that reaches encodeAndQueueFrame in
    // state=TRANSMITTING. Compared against COLD_SCO_START_DROP_FRAMES via
    // shouldDropStartFrame(). Reset in startTransmitting() (mirrors
    // framesSent). Distinct from framesSent because framesSent only
    // increments on frames that actually reach the wire — we need to
    // count and gate BEFORE the encode step.
    @Volatile
    private var txFrameNumber: Int = 0

    // Wall-clock when state transitioned to TPT. The TPT-overlap ring
    // buffer's "skip the loud first N ms" filter uses this to decide
    // whether to drop or retain each incoming PCM frame. Reset on every
    // startTpt; not meaningful in other states.
    @Volatile
    private var tptStateEnteredAtMs: Long = 0

    // Wall-clock of the last PTT-up. Used to debounce rapid press cycles
    // (the "user is banging on PTT" case): the BT chipset gets cumulatively
    // worse with each allocate/abandon cycle. A 200ms refractory after each
    // stop ignores follow-up presses that arrive too fast for any cycle to
    // complete usefully — preserves chipset health for the next legitimate
    // press.
    @Volatile
    private var lastStopMs: Long = 0

    private val primingTimeoutRunnable =
        Runnable {
            synchronized(this@TxController) {
                if (state != State.PRIMING) return@synchronized
                val elapsed = System.currentTimeMillis() - primingStartMs
                if (primingFramesObserved == 0) {
                    // Zero frames seen — AudioRecord allocated but isn't
                    // producing data at all. That's a real broken-mic
                    // case (alloc OK but read loop stalled, BT stack in
                    // a wedged state, etc). Bonk and abandon — playing
                    // TPT here would tell the operator "you're on the
                    // air" with literally no data path.
                    Log.w(
                        TAG,
                        "PRIMING: timeout after ${elapsed}ms (0 frames seen) — bonking & abandoning (mic dead)",
                    )
                    val useSco = btPolicy.classify() == BtAudioMode.HFP_ONLY
                    tptPlayer.playBonk(useScoRoute = useSco && scoLink.state == ScoLink.State.CONNECTED)
                    stopInternal()
                } else {
                    // Frames ARE flowing, they're just silent. Slow
                    // chipsets (Surface Duo + V1) take 1-2 s after
                    // SCO is up before the mic data path actually
                    // produces non-zero samples — the BT chipset's
                    // bidirectional-mode handshake. Bonking here
                    // teaches the operator "the radio doesn't work"
                    // when in fact the next half-second of audio
                    // would have made it. Proceed: play TPT, start
                    // TX. Mic catches up in flight; first few Opus
                    // frames may be silent (peer hears nothing for
                    // 0.5-1 s) but then real audio flows. Better UX
                    // than bonking + retrying.
                    val txRoute = if (primingUseColdScoGates) TxRoute.SCO else TxRoute.OFFLOAD
                    val holdMs = computePrimingHoldMs(txRoute, currentBurstColdSco, 0L)
                    if (holdMs > 0L) {
                        Log.w(
                            TAG,
                            "PRIMING: timeout after ${elapsed}ms ($primingFramesObserved frames seen, all silent) — " +
                                "chipset still warming, holding ${holdMs}ms for cold-SCO modem before TPT + TX",
                        )
                        scheduleColdScoTptHold(holdMs)
                    } else {
                        Log.w(
                            TAG,
                            "PRIMING: timeout after ${elapsed}ms ($primingFramesObserved frames seen, all silent) — chipset still warming, proceeding to TPT + TX anyway",
                        )
                        startTpt()
                    }
                }
            }
        }

    /**
     * Begin a TX burst. [slot] selects the logical channel: 0 = primary
     * (default group channel), 1 = secondary (VS2). The slot is fixed for
     * the duration of this burst — releasing PTT and pressing the other
     * button starts a fresh burst on the other slot.
     */
    @Synchronized
    fun start(slot: Int = 0) {
        if (state != State.IDLE) {
            Log.i(TAG, "start(slot=$slot) while state=$state — ignoring")
            return
        }
        // Refractory debounce: too-rapid press cycles thrash the BT
        // chipset on cold acquire/abandon (each cycle leaves it
        // slightly worse, and after ~6-8 sub-200ms cycles the mic
        // stops producing real samples entirely). The full
        // START_REFRACTORY_MS guard applies when SCO is NOT already
        // CONNECTED — that's when re-press would force a fresh
        // setCommunicationDevice + readiness-poll cycle. When SCO is
        // already warm (RX SCO_HOT, Hot Mic, or in-flight TX
        // cool-down), the chipset isn't being re-acquired, so a
        // shorter STOP_TO_START_WARM_MIN_MS suffices — just enough to
        // flush the audio buffer and let our own state cleanup land.
        val now = System.currentTimeMillis()
        val scoWarm = scoLink.state == ScoLink.State.CONNECTED
        val refractoryMs = if (scoWarm) STOP_TO_START_WARM_MIN_MS else START_REFRACTORY_MS
        if (lastStopMs > 0 && now - lastStopMs < refractoryMs) {
            Log.i(
                TAG,
                "start(slot=$slot) ignored — within ${refractoryMs}ms refractory after last stop (${now - lastStopMs}ms elapsed, scoWarm=$scoWarm)",
            )
            return
        }
        activeSlot = slot
        val canTx = canTransmit(slot)
        Log.i(TAG, "start(slot=$slot) called — canTransmit=$canTx state=$state")
        if (!canTx) {
            Log.w(TAG, "start(slot=$slot) refused — channel/permission denies TX; playing deny tone")
            // A denied press is still a strong intent signal — the
            // operator meant to TX, just on the wrong slot. Treat it
            // like a real press for SCO purposes: pre-warm the link
            // if it isn't already up so the retry on the correct
            // channel doesn't pay the 1-2s SCO setup latency.
            val isHfp = btPolicy.classify() == BtAudioMode.HFP_ONLY
            if (isHfp && !holdsSco) {
                scoLink.addStateListener(scoListener)
                scoLink.acquire(this)
                holdsSco = true
            }
            if (isHfp && scoLink.state != ScoLink.State.CONNECTED) {
                // Cold SCO — defer the deny tone until the link is
                // actually up so it reaches the AINA speaker rather
                // than the phone speaker.
                pendingDenyTone = true
                Log.i(TAG, "deny tone deferred — waiting for SCO CONNECTED")
            } else {
                tptPlayer.playDeny(useScoRoute = isHfp)
            }
            // Universal cool-down. Re-arm the 5 s release runnable
            // on every press (real or denied) so OUR teardown is
            // the authority — outlasts the system's voice-comm
            // hysteresis and keeps SCO available for an immediate
            // retry on the right slot.
            if (holdsSco) {
                cooldownHandler.removeCallbacks(coolDownReleaseRunnable)
                cooldownHandler.postDelayed(coolDownReleaseRunnable, SCO_COOL_DOWN_MS)
            }
            // Fire the PTT-state-OFF callback so VoicePlant ends
            // the Telecom call that pttDown placed unconditionally.
            // Without this, every denied press leaves a Telecom
            // call active, the system holds MODE_IN_COMMUNICATION,
            // and SCO stays up past our 5 s cool-down (observable
            // as the AINA's purple LED flashing past the expected
            // drop time on a listen-only channel). We deliberately
            // do NOT fire the matching ON callback since we never
            // actually transmitted — listeners (Mumble UserState
            // burst-start) only react to ON, so a lone OFF is safe.
            try {
                onPttStateChanged(false, slot)
            } catch (t: Throwable) {
                Log.w(TAG, "onPttStateChanged(false) on deny path threw", t)
            }
            return
        }
        // A pending cool-down release means the SCO link from a recent
        // burst is still up. Cancel the release and reuse it — no
        // re-warmup. This is the "5s warm window" UX: rapid back-and-
        // forth pushes don't pay SCO setup latency.
        cooldownHandler.removeCallbacks(coolDownReleaseRunnable)

        val useSco = btPolicy.classify() == BtAudioMode.HFP_ONLY
        if (useSco) {
            // SCO already up — either we held it via TX cool-down OR
            // AudioPlayback held it via RX SCO_HOT (the whole point of
            // SCO_HOT is so a quick PTT response after RX doesn't pay
            // setup latency). Acquire to bump the ref count so OUR
            // release path keeps the link alive for our cool-down,
            // then go straight to PRIMING. The previous version of
            // this code only checked `holdsSco`, which is OUR ref —
            // when AudioPlayback was the only holder we fell through
            // to ACQUIRING_SCO and waited the full SCO_WARMUP_MS for
            // a state change that had already happened. Result:
            // 3 s TPT latency on every PTT-after-RX even though SCO
            // was perfectly warm.
            if (scoLink.state == ScoLink.State.CONNECTED) {
                if (!holdsSco) {
                    scoLink.addStateListener(scoListener)
                    scoLink.acquire(this)
                    holdsSco = true
                }
                // Warm-SCO: modem is already stable, no AOC underrun
                // window to work around. Clear the cold flag so the
                // TPT hold and drop-first-N mitigations stay dormant.
                currentBurstColdSco = false
                Log.i(TAG, "SCO already CONNECTED (warm via cool-down or RX SCO_HOT) — direct to PRIMING")
                startPriming()
                return
            }
            // Cold path — link genuinely down. Acquire and wait for
            // the state transition to drive PRIMING via scoListener.
            if (!holdsSco) {
                scoLink.addStateListener(scoListener)
                scoLink.acquire(this)
                holdsSco = true
            }
            // Cold-SCO burst: latch the flag NOW while we still know
            // the pre-acquire state. By the time scoListener drives
            // startPriming(), scoLink.state will read CONNECTED and
            // the cold vs. warm distinction is otherwise erased.
            currentBurstColdSco = true
            state = State.ACQUIRING_SCO
            cooldownHandler.postDelayed({
                synchronized(this@TxController) {
                    if (state == State.ACQUIRING_SCO) {
                        Log.w(TAG, "SCO warmup timeout — proceeding without (will TX on whatever path is up)")
                        startPriming()
                    }
                }
            }, SCO_WARMUP_MS)
        } else {
            // Non-SCO route — but STILL go through PRIMING. Earlier
            // assumption was "no chipset warm-up to wait for" but
            // field testing on Surface Duo 2 shows the audio HAL
            // produces ~1.5s of zero samples after a mode/route
            // transition (e.g. when HFP drops mid-session and the
            // built-in mic takes over). Without PRIMING we played
            // TPT against silence, the operator released the press
            // before the mic actually started capturing, and tried
            // again — repeating the cycle. PRIMING gates TPT on
            // verifiable mic output, so the operator hears the TPT
            // exactly when the mic is ready.
            currentBurstColdSco = false
            startPriming()
        }
    }

    // PRIMING gates TPT on the mic actually producing real samples. The
    // user's complaint that drove this: TPT plays before AudioRecord +
    // BT chipset are returning real audio, so the operator hears the
    // "you can talk now" cue but the system isn't actually capturing —
    // first ~300ms of speech is lost on cold-start or post-thrash bursts.
    // PRIMING allocates AudioCapture, watches the first incoming frames,
    // and only proceeds to TPT once the chipset is producing non-zero
    // PCM. Fallback: a wall-clock timeout plays TPT anyway after a
    // generous bound so we never hang the operator on a hardware
    // failure.
    private fun startPriming() {
        state = State.PRIMING
        primingStartMs = System.currentTimeMillis()
        primingFramesObserved = 0
        // Route-aware gate selection. On BT SCO the chipset takes
        // 300-1000 ms of cold-start warmup during which frames arrive
        // late and/or with only noise-floor amplitude. Non-SCO routes
        // period. Only a COLD SCO acquire faces the chipset ramp; a
        // warm-held SCO link (cool-down tail / RX SCO_HOT) has a settled
        // chipset and must NOT pay the 30-frame cold gate. Capture the
        // decision once so the timeout callback and onPcmFrame use
        // consistent thresholds even if SCO state changes mid-priming.
        primingUseColdScoGates = currentBurstColdSco
        val timeoutMs = primingTimeoutMs(primingUseColdScoGates)
        val scoUp = scoLink.state == ScoLink.State.CONNECTED
        val routeLabel =
            when {
                !scoUp -> "non-sco"
                primingUseColdScoGates -> "sco-cold"
                else -> "sco-warm"
            }
        if (capture == null) {
            val cap =
                audioCaptureFactory { pcm ->
                    onPcmFrame(pcm)
                }
            capture = cap
            cap.start()
            Log.i(
                TAG,
                "PRIMING: mic capture started — waiting for first non-silent frame " +
                    "(route=$routeLabel, timeout=${timeoutMs}ms)",
            )
        } else {
            Log.i(TAG, "PRIMING: mic capture already alive (route=$routeLabel) — waiting for non-silent frame")
        }
        cooldownHandler.removeCallbacks(primingTimeoutRunnable)
        cooldownHandler.postDelayed(primingTimeoutRunnable, timeoutMs)
    }

    // Bounded mic pre-warm tied to AudioPlayback's SCO_HOT window.
    // When AudioPlayback enters SCO_HOT (RX just ended, SCO link
    // held warm for ~8 s — AudioPlayback.scoHoldMs) we proactively allocate AudioCapture so
    // the BT chipset's mic data path can settle. On slow chipsets
    // (Duo+V1, observed) the mic returns silent samples for 1-3 s
    // after a cold SCO setup; if the operator's response press has
    // to wait through that, PRIMING times out and bonks. Pre-warming
    // during the idle window gives the chipset time to wake up
    // BEFORE the operator presses PTT, so PRIMING completes in
    // ~100 ms (we just verify the now-flowing samples are non-silent).
    //
    // Bounded by SCO_HOT (8 s max) — does NOT bring back the
    // historical "stale routing across many bursts" bug, which
    // required AudioRecord to be held open through TX bursts and
    // mode transitions. Here it's only alive during the 8 s window
    // BEFORE TX, then handed off cleanly to the burst.
    // Session-scope mic pre-arm. Distinct from preWarmMic (which is the
    // 8 s SCO_HOT window): this holds AudioCapture open for the entire
    // duration of a connected Mumble session, regardless of route.
    // VoicePlant.setMumbleSessionLive drives it: connect → arm,
    // disconnect → disarm.
    //
    // Why: PRIMING (TxController.startPriming → onPcmFrame's
    // micAlive check) is dominated by the mic-chipset cold-start wait.
    // On Pixel/non-SCO this is ~165 ms; on Surface Duo + AINA V1 it
    // can hit 1-3 s. Keeping AudioRecord open for the whole session
    // means PTT-down sees a hot mic — first non-silent frame arrives
    // in <20 ms — and PRIMING completes within one frame interval.
    // PTT-down → TPT-audible drops from 200-400 ms to ~50 ms.
    //
    // Coexists with [preWarmMic]:
    //  - Session armed AND SCO_HOT preWarmMic(true): preWarmMic
    //    sees capture != null and no-ops.
    //  - Session armed AND SCO_HOT preWarmMic(false): preWarmMic
    //    sees state == IDLE and tries to release; we keep our session
    //    arming via [sessionArmed] flag so preWarmMic respects it.
    @Volatile private var sessionArmed: Boolean = false

    // Operator intent: should the session-mic be armed whenever no other
    // app is recording? Set on armSessionMic, cleared on disarmSessionMic.
    // [sessionArmed] is the ACTUAL state (mic allocated or not); these
    // two can diverge when the yield-callback releases mic to another
    // app while the Mumble session is still live.
    @Volatile private var sessionShouldBeArmed: Boolean = false

    // Registered on the first armSessionMic and torn down on the
    // last disarmSessionMic. Watches the system-wide active-recording
    // list; if another app starts recording while we're holding the
    // warm mic, we release so they can acquire (Google Assistant,
    // dialer, Voice Recorder, etc). When they release, we re-arm.
    //
    // Field complaint 2026-05-21: Assistant couldn't acquire mic
    // because XV was holding AudioRecord for the entire Mumble
    // session. AudioRecord is a hardware-exclusive resource (separate
    // from audio focus), so just listening for FOCUS_LOSS doesn't
    // help — we have to observe other apps' recording state directly.
    @Volatile
    private var recordingCallbackRegistered: Boolean = false

    private val recordingCallback =
        object : AudioManager.AudioRecordingCallback() {
            override fun onRecordingConfigChanged(configs: MutableList<android.media.AudioRecordingConfiguration>?) {
                val others = anyOtherClientInList(configs ?: emptyList())
                synchronized(this@TxController) {
                    if (!sessionShouldBeArmed) return@synchronized
                    if (state != State.IDLE) return@synchronized
                    if (others && sessionArmed) {
                        Log.i(
                            TAG,
                            "another app is recording — yielding warm mic (will re-arm when they release)",
                        )
                        releaseCaptureForYield()
                    } else if (!others && !sessionArmed) {
                        Log.i(TAG, "mic is free again — re-arming warm session mic")
                        allocateCaptureForArm()
                    }
                }
            }
        }

    /** Public AudioRecordingConfiguration doesn't expose clientUid until
     *  API 29 (and even then it's @SystemApi). Match by audio session ID
     *  instead: AudioCapture exposes the AudioRecord's session id while
     *  alive, and the system-wide configurations include
     *  `clientAudioSessionId`. Anything whose session id differs from
     *  ours is another app — including the case where we hold zero
     *  recordings (our session id is 0; every other config differs). */
    private fun anyOtherClientInList(configs: List<android.media.AudioRecordingConfiguration>): Boolean {
        val ourSessionId = capture?.activeSessionId ?: 0
        return configs.any { it.clientAudioSessionId != ourSessionId }
    }

    @Synchronized
    fun armSessionMic() {
        if (sessionShouldBeArmed) {
            Log.d(TAG, "armSessionMic: already armed — no-op")
            return
        }
        sessionShouldBeArmed = true
        ensureRecordingCallbackRegistered()
        if (anyOtherAppRecording()) {
            Log.i(TAG, "armSessionMic: another app is currently recording — deferring arm until they release")
            return
        }
        if (capture != null) {
            // Capture already alive (SCO_HOT pre-warm or mid-burst);
            // claim co-ownership so that path's release sites don't
            // tear it down.
            sessionArmed = true
            Log.i(TAG, "armSessionMic: capture already alive, claiming session ownership")
            return
        }
        if (state != State.IDLE) {
            Log.i(TAG, "armSessionMic: state=$state, deferring allocation to next idle")
            sessionArmed = true
            return
        }
        allocateCaptureForArm()
    }

    @Synchronized
    fun disarmSessionMic() {
        if (!sessionShouldBeArmed) return
        sessionShouldBeArmed = false
        unregisterRecordingCallback()
        if (!sessionArmed) return
        sessionArmed = false
        if (state != State.IDLE) {
            Log.i(TAG, "disarmSessionMic: state=$state, releasing on next idle")
            return
        }
        releaseCaptureForYield()
        Log.i(TAG, "disarmSessionMic: released (Mumble session ended)")
    }

    // INVARIANT: caller holds the this@TxController monitor.
    private fun allocateCaptureForArm() {
        if (capture != null) {
            sessionArmed = true
            return
        }
        try {
            val cap =
                audioCaptureFactory { pcm ->
                    onPcmFrame(pcm)
                }
            capture = cap
            cap.start()
            sessionArmed = true
            Log.i(TAG, "armSessionMic: AudioCapture allocated for Mumble session")
        } catch (t: Throwable) {
            Log.w(TAG, "armSessionMic allocate threw", t)
            capture = null
            sessionArmed = false
        }
    }

    // INVARIANT: caller holds the this@TxController monitor.
    private fun releaseCaptureForYield() {
        val cap = capture ?: run {
            sessionArmed = false
            return
        }
        try {
            cap.stop()
        } catch (t: Throwable) {
            Log.w(TAG, "releaseCaptureForYield: cap.stop threw", t)
        }
        capture = null
        sessionArmed = false
    }

    private fun ensureRecordingCallbackRegistered() {
        if (recordingCallbackRegistered) return
        try {
            audioManager.registerAudioRecordingCallback(recordingCallback, cooldownHandler)
            recordingCallbackRegistered = true
        } catch (t: Throwable) {
            // The AudioRecordingCallback API is API 24+; minSdk is 26
            // so this should always work. Catch defensively in case a
            // sandbox / restricted profile blocks it — we just lose
            // the auto-yield behavior, the warm mic still works.
            Log.w(TAG, "registerAudioRecordingCallback failed — auto-yield disabled", t)
        }
    }

    private fun unregisterRecordingCallback() {
        if (!recordingCallbackRegistered) return
        try {
            audioManager.unregisterAudioRecordingCallback(recordingCallback)
        } catch (t: Throwable) {
            Log.w(TAG, "unregisterAudioRecordingCallback threw", t)
        }
        recordingCallbackRegistered = false
    }

    private fun anyOtherAppRecording(): Boolean =
        try {
            anyOtherClientInList(audioManager.activeRecordingConfigurations)
        } catch (t: Throwable) {
            Log.w(TAG, "activeRecordingConfigurations threw — assuming nobody else is recording", t)
            false
        }

    @Synchronized
    fun preWarmMic(active: Boolean) {
        if (active) {
            // Only pre-warm if we'd actually use the mic. Non-HFP
            // routes don't have the chipset settling problem.
            if (btPolicy.classify() != BtAudioMode.HFP_ONLY) {
                return
            }
            if (capture != null) {
                // TX in flight or another pre-warm already running —
                // nothing to do.
                return
            }
            if (state != State.IDLE) {
                // Mid-burst — capture lifecycle is owned by start/stop.
                return
            }
            try {
                val cap =
                    audioCaptureFactory { pcm ->
                        // Frames during pre-warm flow through onPcmFrame
                        // → state==IDLE → drop. Just keeping the chipset
                        // pumping. As soon as start() flips state to
                        // PRIMING, the same lambda routes them to RMS
                        // checks.
                        onPcmFrame(pcm)
                    }
                capture = cap
                cap.start()
                Log.i(TAG, "preWarmMic: AudioCapture allocated for SCO_HOT settling")
            } catch (t: Throwable) {
                Log.w(TAG, "preWarmMic allocate threw", t)
                capture = null
            }
        } else {
            // Pre-warm window ending. Only release if we're still
            // idle — if a TX is in flight, the burst owns the
            // capture lifecycle and stop() will clean it up. Also
            // hand off (don't release) if the session-scope pre-arm
            // owns the capture — that one only ends on Mumble
            // disconnect, not on SCO_HOT cycle endings.
            if (state != State.IDLE) {
                return
            }
            if (sessionArmed) {
                Log.d(TAG, "preWarmMic: SCO_HOT exit while session-armed — keeping capture warm")
                return
            }
            val cap = capture ?: return
            try {
                cap.stop()
            } catch (t: Throwable) {
                Log.w(TAG, "preWarmMic release: cap.stop threw", t)
            }
            capture = null
            Log.i(TAG, "preWarmMic: released (SCO_HOT exiting)")
        }
    }

    @Synchronized
    fun stop() {
        if (state == State.IDLE) return
        Log.i(TAG, "stop() (state=$state)")
        stopInternal()
    }

    /**
     * Post a delayed [startTpt] to give the cold-SCO Pixel AOC modem
     * time to stabilize. State is flipped to [State.TPT] BEFORE the
     * delay so incoming PCM frames get dropped by the existing
     * state==TPT branch — no risk of re-entering the PRIMING-completion
     * branch and scheduling multiple TPT plays.
     *
     * INVARIANT: caller holds the this@TxController monitor and state
     * is currently PRIMING.
     */
    private fun scheduleColdScoTptHold(holdMs: Long) {
        state = State.TPT
        tptStateEnteredAtMs = System.currentTimeMillis()
        cooldownHandler.postDelayed({
            synchronized(this@TxController) {
                // If the operator released PTT during the hold window,
                // stopInternal already flipped state to IDLE. Bail
                // without playing TPT — matches the existing behavior
                // for late-firing timeout runnables.
                if (state != State.TPT) return@synchronized
                startTpt(alreadyInTptState = true)
            }
        }, holdMs)
    }

    private fun startTpt(alreadyInTptState: Boolean = false) {
        if (!alreadyInTptState) {
            state = State.TPT
            tptStateEnteredAtMs = System.currentTimeMillis()
        }
        // useSco gates on BOTH the BT policy (HFP profile is reachable)
        // AND the actual SCO link state (the physical link is up). The
        // old policy-only gate caused TPT to claim the SCO route even
        // when SCO was still CONNECTING (warmup timeout edge) or had
        // SUSPENDED mid-burst — the AudioTrack was built for
        // USAGE_VOICE_COMMUNICATION with no preferred device and
        // landed on whatever Telecom's CallEndpointController picked
        // (earpiece/speaker during call-setup arbitration). Operator
        // symptom: "tone fired from phone speaker not the speakermic"
        // or "I didn't hear anything." Tighten so SCO route is only
        // claimed when the link is genuinely up; otherwise drop to the
        // non-SCO path which explicitly pins to BUILTIN_SPEAKER.
        val isHfp = btPolicy.classify() == BtAudioMode.HFP_ONLY
        val scoUp = scoLink.state == ScoLink.State.CONNECTED
        val useSco = isHfp && scoUp
        if (isHfp && !scoUp) {
            Log.w(
                TAG,
                "TPT: HFP_ONLY policy but SCO state=${scoLink.state} — falling back to non-SCO route",
            )
        }
        // PRIMING already allocated capture and confirmed the mic is
        // producing real samples (or hit the timeout). On the non-SCO
        // path PRIMING is skipped entirely and we may still need to
        // allocate here.
        if (capture == null) {
            val cap =
                audioCaptureFactory { pcm ->
                    onPcmFrame(pcm)
                }
            capture = cap
            cap.start()
            Log.i(TAG, "TPT: mic capture started (non-SCO route, no PRIMING)")
        }
        // Pick TPT vs interrupt chirp by whether a peer is actively
        // talking right now. Both run through the same TPT state and
        // hand off to startTransmitting on completion.
        val onTptComplete: () -> Unit = {
            synchronized(this@TxController) {
                if (state == State.TPT) startTransmitting()
            }
        }
        if (isRxActive()) {
            Log.i(TAG, "interrupt chirp (peer talking, useSco=$useSco)")
            tptPlayer.playInterrupt(useSco, onTptComplete)
        } else {
            val tone = tonePreference()
            Log.i(TAG, "TPT $tone (useSco=$useSco)")
            tptPlayer.play(tone, useSco, onTptComplete)
        }
    }

    private fun startTransmitting() {
        // Encoder MUST be in place before state flips to TRANSMITTING.
        // (Earlier race where state changed first caused 1-3 frames to
        // log "encoder is null!" and drop on each cold-SCO burst.)
        framesSent = 0
        txFrameNumber = 0
        encoder = opusEncoderFactory()
        state = State.TRANSMITTING
        // Per-PTT focus management is SKIPPED when Telecom owns the
        // voice session — XvVoiceService holds GAIN focus for the
        // entire Telecom call lifetime (which spans rapid PTT cycles
        // via the 8 s end-debounce). Grabbing/abandoning focus on
        // every PTT down/up confuses media apps (Spotify ducks
        // instead of pauses when it sees focus flapping at sub-second
        // cadence) and risks denying our own RX focus request mid-
        // burst. When Telecom is NOT active (registration not yet
        // approved by user), the legacy per-burst path runs as a
        // fallback. Mode flip to MODE_IN_COMMUNICATION matters for
        // HFP routing — without it some chipsets refuse to route SCO
        // mic capture even with SCO physically up.
        if (telecomActive()) {
            Log.i(TAG, "TX: Telecom call active — service-level focus owns this")
        } else if (!audioController.enterTx()) {
            Log.w(TAG, "audioController.enterTx() denied — TX continuing without focus")
        }
        // Defensive — capture should already be alive from startTpt.
        // Allocate now if a non-SCO route somehow skipped startTpt.
        if (capture == null) {
            val cap =
                audioCaptureFactory { pcm ->
                    onPcmFrame(pcm)
                }
            capture = cap
            cap.start()
        }
        Log.i(TAG, "TX: resetting voice sequence (mic already running from TPT prewarm)")
        try {
            onPttStateChanged(true, activeSlot)
        } catch (t: Throwable) {
            Log.w(TAG, "onPttStateChanged(true) threw", t)
        }
        Log.i(TAG, "TX transmitting started — frames will flow now")
    }

    private var framesSent: Long = 0

    // Trailing-frame ring buffer to swallow the PTT release click. The
    // physical button click on the AINA V1/V2 happens at time T; the
    // BLE/SPP "release" event reaches us at T + 15-40ms (radio + thread
    // dispatch). Without this buffer, mic frames covering the click are
    // already encoded and on the wire by the time stop() fires. Holding
    // the most recent N frames (each 10ms) and dropping them on stop
    // means the trailing click never goes out — at the cost of N*10ms
    // of TX latency. 6 frames = 60ms covers typical BLE round-trip with
    // headroom; SPP is faster but the constant matches both. Frames
    // continue flowing in real time during steady transmission — the
    // buffer just lags the wire by 60ms.
    private val pendingTrailing: ArrayDeque<Pair<ByteArray, Int>> = ArrayDeque(TRAILING_FRAMES + 2)

    // ============================================================
    // Test seams (visibility relaxed to `internal`; @VisibleForTesting
    // annotation documents the intent and lets static analysis warn if
    // any production code touches them).
    //
    // The seams here are deliberately minimal: just enough to drive
    // the state machine in a unit test without instantiating a real
    // ScoLink / AudioCapture / TptPlayer pipeline. State-machine
    // coverage lives in TxControllerStateMachineTest; pure-Kotlin
    // coverage of the screech-fix logic lives in
    // TxControllerScreechTest.
    // ============================================================

    @VisibleForTesting
    internal fun setStateForTest(
        s: State,
        tptEnteredAtMs: Long = 0L,
    ) {
        state = s
        tptStateEnteredAtMs = tptEnteredAtMs
    }

    @VisibleForTesting
    internal fun currentStateForTest(): State = state

    @VisibleForTesting
    internal fun setEncoderForTest(enc: OpusEncoder?) {
        encoder = enc
    }

    @VisibleForTesting
    internal fun currentEncoderForTest(): OpusEncoder? = encoder

    @VisibleForTesting
    internal fun onPcmFrameForTest(pcm: ShortArray) {
        onPcmFrame(pcm)
    }

    private fun onPcmFrame(pcm: ShortArray) {
        // PRIMING: gate TPT on the mic actually producing data. The
        // onPcmFrame thread is the AudioCapture read thread; we
        // delegate the state transition to a synchronized block so
        // teardown isn't racing.
        if (state == State.PRIMING) {
            primingFramesObserved++
            val rms = rms(pcm)
            // PRIMING completes as soon as we have evidence the mic is
            // alive — EITHER speech detected (rms >= threshold) OR a
            // small batch of frames has flowed through the capture
            // pipeline (frames >= MIN_FRAMES_TO_CONFIRM_ALIVE). The
            // earlier "wait for non-silent" gate kept the Surface Duo's
            // built-in mic stuck in PRIMING for the full 1.5s timeout
            // because nobody was talking yet when the operator pressed
            // PTT — the operator perceived a long delay before the TPT
            // played, and the radio felt sluggish. Frame-count is a
            // hardware-liveness signal: if AudioCapture's read thread
            // is delivering frames at all, the mic is up and TPT can
            // play; the worst that can happen is the first few ms of
            // speech encode as silence, which is unnoticeable.
            val rmsThreshold = primingRmsThreshold(primingUseColdScoGates)
            val minFrames = primingMinFramesToConfirmAlive(primingUseColdScoGates)
            val micAlive =
                rms >= rmsThreshold ||
                    primingFramesObserved >= minFrames
            if (micAlive) {
                synchronized(this@TxController) {
                    if (state == State.PRIMING) {
                        cooldownHandler.removeCallbacks(primingTimeoutRunnable)
                        val elapsed = System.currentTimeMillis() - primingStartMs
                        val reason =
                            if (rms >= rmsThreshold) {
                                "speech-detected"
                            } else {
                                "frames-confirm-alive"
                            }
                        val route =
                            when {
                                scoLink.state != ScoLink.State.CONNECTED -> "non-sco"
                                primingUseColdScoGates -> "sco-cold"
                                else -> "sco-warm"
                            }
                        val txRoute = if (primingUseColdScoGates) TxRoute.SCO else TxRoute.OFFLOAD
                        val holdMs = computePrimingHoldMs(txRoute, currentBurstColdSco, 0L)
                        if (holdMs > 0L) {
                            Log.i(
                                TAG,
                                "PRIMING: mic ready after ${elapsed}ms ($reason: rms=$rms, " +
                                    "$primingFramesObserved frames observed, route=$route) — " +
                                    "holding ${holdMs}ms for cold-SCO modem stabilization before TPT",
                            )
                            scheduleColdScoTptHold(holdMs)
                        } else {
                            Log.i(
                                TAG,
                                "PRIMING: mic ready after ${elapsed}ms ($reason: rms=$rms, " +
                                    "$primingFramesObserved frames observed, route=$route) — playing TPT",
                            )
                            startTpt()
                        }
                    }
                }
            }
            return
        }
        if (state == State.TPT) {
            // Drop mic frames captured while the permit tone is playing.
            // A TPT-overlap ring buffer that flushed these into the
            // burst was tried and removed 2026-05-21: on cold-SCO
            // PTT-down AudioRecord delivers chunked/irregular frames for
            // the first 300-500 ms, and feeding them to Opus's stateful
            // SILK encoder produced peer-side screech. Operators adapt by
            // waiting for TPT before speaking (the universal LMR
            // convention), so pre-speech capture buys little and the
            // screech was intolerable.
            return
        }
        if (state != State.TRANSMITTING) {
            // IDLE / PRIMING / ACQUIRING_SCO etc — drop. Frames during
            // pre-arm or PRIMING aren't real speech for this burst.
            if (state != State.IDLE) {
                Log.w(TAG, "onPcmFrame called but state=$state — dropping")
            }
            return
        }
        val enc =
            encoder ?: run {
                Log.e(TAG, "onPcmFrame: encoder is null!")
                return
            }
        // Cold-SCO start-of-burst frame drop. On Pixel with BT SCO the
        // AOC modem underruns for ~30 ms after the SCO uplink stabilizes;
        // the first N live frames land in that window and encode to
        // garbled audio on the peer. Silently discard the head of the
        // burst so the encoder sees only frames captured after the
        // underrun clears. Mirrors the TRAILING_FRAMES swallow at
        // TX-stop that eats the PTT release click — same shape, opposite
        // end of the burst.
        //
        // The counter is incremented BEFORE the gate so frame numbering
        // is 1-based and matches the pure helper's contract.
        txFrameNumber++
        if (shouldDropStartFrame(
                frameNumber = txFrameNumber,
                route = if (currentBurstColdSco) TxRoute.SCO else TxRoute.OFFLOAD,
                cold = currentBurstColdSco,
                dropCount = COLD_SCO_START_DROP_FRAMES,
            )
        ) {
            if (txFrameNumber == 1 || txFrameNumber == COLD_SCO_START_DROP_FRAMES) {
                Log.i(
                    TAG,
                    "TX: dropping start frame #$txFrameNumber (cold-SCO AOC underrun window, " +
                        "N=$COLD_SCO_START_DROP_FRAMES)",
                )
            }
            return
        }
        encodeAndQueueFrame(pcm, enc)
    }

    /** Encode a PCM frame and route it through the trailing-frame ring
     *  toward [sendOpus]. Extracted so [startTransmitting] can flush the
     *  TPT-overlap ring through the same path that live frames take —
     *  same opus framing, same trailing-click swallow window, same
     *  per-frame logging. */
    private fun encodeAndQueueFrame(
        pcm: ShortArray,
        enc: OpusEncoder,
    ) {
        val rmsIn = rms(pcm)
        val opus =
            try {
                enc.encode(pcm)
            } catch (t: Throwable) {
                // Concentus throws AssertionError inside Resampler when
                // given pathological input (a stuck DC-offset fill, e.g.).
                // The throw leaves the encoder's SILK state corrupt and
                // every subsequent encode produces garbage on the wire —
                // the source of the field-reported screeching. Recreate
                // the encoder so the very next live frame encodes
                // against a fresh state. Cost: one new Concentus
                // allocation per failure (rare); benefit: the burst
                // recovers within ~10ms instead of staying wedged for
                // the rest of the TX.
                Log.e(TAG, "opus encode failed — resetting encoder to recover", t)
                try {
                    enc.close()
                } catch (_: Throwable) {
                }
                encoder = opusEncoderFactory()
                return
            }
        if (opus.isEmpty()) {
            Log.w(TAG, "opus encoder returned empty frame")
            return
        }
        // Push newest, send oldest once buffer is at capacity.
        pendingTrailing.addLast(opus to rmsIn)
        if (pendingTrailing.size <= TRAILING_FRAMES) {
            return // still filling the trailing window — nothing to send yet
        }
        val (toSend, sendRms) = pendingTrailing.removeFirst()
        try {
            sendOpus(toSend, activeSlot)
            framesSent++
            if (framesSent <= 5L || framesSent % 50 == 0L) {
                Log.i(TAG, "TX frame #$framesSent: opus=${toSend.size}B pcmRms=$sendRms slot=$activeSlot")
            }
        } catch (t: Throwable) {
            Log.e(TAG, "sendOpus threw", t)
        }
    }

    private fun stopInternal() {
        val wasTransmitting = state == State.TRANSMITTING
        val wasTpt = state == State.TPT
        val burstSlot = activeSlot
        // Cancel any pending PRIMING timeout — if the user releases PTT
        // while we're still waiting for the mic to produce real samples,
        // we abandon cleanly without playing TPT or transmitting.
        cooldownHandler.removeCallbacks(primingTimeoutRunnable)
        // Session-arm: keep the capture alive between bursts so the
        // next PTT-down doesn't pay AudioRecord/mic-chipset cold-start
        // latency. Released only on disarmSessionMic (Mumble disconnect)
        // or shutdown(). Without this hand-off, every burst releases
        // the capture and the next press waits 100-300 ms for PRIMING
        // again — defeating the purpose of session-arm entirely.
        if (sessionArmed) {
            Log.d(TAG, "stopInternal: keeping capture warm (sessionArmed)")
        } else {
            capture?.stop()
            capture = null
        }
        encoder?.close()
        encoder = null
        // Let TPT play through on quick screen-PTT taps. Users complained
        // 2026-05-11 that brief on-screen presses truncated the TPT —
        // the prior behavior called tptPlayer.stop() unconditionally on
        // release, cutting the tone mid-play. Now: if release lands
        // during the TPT phase, we keep the AudioTrack alive so the
        // tone finishes; onTptComplete fires later but its synchronized
        // state == TPT guard fails (state is being flipped to IDLE
        // below) so no spurious TX starts. Only TRANSMITTING / IDLE
        // releases stop the player — those paths either already
        // finished the tone or never reached it.
        if (!wasTpt) {
            tptPlayer.stop()
        } else {
            Log.i(TAG, "TX stop during TPT — letting tone play through to completion")
        }
        // Drop the trailing-frame buffer — those are the frames containing
        // the physical PTT release click. See pendingTrailing's docs.
        if (pendingTrailing.isNotEmpty()) {
            Log.i(TAG, "TX stop: dropping ${pendingTrailing.size} trailing frame(s) to swallow release click")
            pendingTrailing.clear()
        }
        if (wasTransmitting) {
            try {
                sendTerminator(burstSlot)
            } catch (t: Throwable) {
                Log.w(TAG, "sendTerminator threw", t)
            }
            try {
                onPttStateChanged(false, burstSlot)
            } catch (t: Throwable) {
                Log.w(TAG, "onPttStateChanged(false) threw", t)
            }
            Log.i(TAG, "TX stopped after $framesSent frames (slot=$burstSlot)")
        }
        // 5-second cool-down: keep SCO warm so a re-press lands instantly.
        // RX's SCO_HOT timer in AudioPlayback handles its own side; this
        // covers the TX-only case (PTT-down, talk, PTT-up, immediate
        // PTT-down again — all on a warm link). After the cool-down
        // expires, release our ref. If RX is still holding (SCO_HOT) the
        // link remains up via the ScoLink ref count.
        if (holdsSco) {
            cooldownHandler.removeCallbacks(coolDownReleaseRunnable)
            cooldownHandler.postDelayed(coolDownReleaseRunnable, SCO_COOL_DOWN_MS)
        }
        activeSlot = 0
        state = State.IDLE
        lastStopMs = System.currentTimeMillis()
        // Drop TX focus + restore mode. exitTx (not returnToIdle)
        // preserves any active RX state and does NOT touch the SCO
        // link — ScoLink owns the physical-link lifetime via its own
        // ref count. Skipped when Telecom owns the audio session —
        // symmetric with startTransmitting. Service-level voice
        // focus held in XvVoiceService spans the whole call.
        if (!telecomActive()) {
            audioController.exitTx()
        }
        try {
            onIdle()
        } catch (t: Throwable) {
            Log.w(TAG, "onIdle hook threw", t)
        }
        // Re-evaluate the recording-yield decision now that state has
        // returned to IDLE. If another app (Assistant, dialer) began
        // recording while we were mid-burst, our recordingCallback
        // ignored that change (state-gated). Now that state is IDLE,
        // we may need to release the warm mic so that other app can
        // acquire on its next AudioRecord attempt.
        if (sessionShouldBeArmed && sessionArmed && anyOtherAppRecording()) {
            Log.i(TAG, "post-burst recheck: another app is recording — yielding warm mic")
            releaseCaptureForYield()
        }
    }

    private val cooldownHandler = android.os.Handler(android.os.Looper.getMainLooper())

    private val coolDownReleaseRunnable: Runnable =
        object : Runnable {
            override fun run() {
                synchronized(this@TxController) {
                    if (state == State.IDLE && holdsSco) {
                        if (hotMicMode()) {
                            // Hot Mic: keep SCO warm so back-to-back
                            // PTT presses skip the 500-1500ms cold start.
                            // BUT enforce an idle ceiling — without one,
                            // SCO stays held for the entire channel
                            // membership, locking the AINA into 16kHz
                            // SCO audio (no A2DP music) and draining
                            // battery. Field test 2026-05-11: SCO held
                            // 300+ seconds after last PTT activity on
                            // Surface Duo 2 with hot-mic on.
                            //
                            // After HOT_MIC_IDLE_RELEASE_MS of true idle
                            // (no PTT down/up in that window), release
                            // SCO. The next press pays one cold-start
                            // cost, then armHotMic re-acquires the link
                            // on stopInternal as normal.
                            val idleMs = System.currentTimeMillis() - lastStopMs
                            if (idleMs >= HOT_MIC_IDLE_RELEASE_MS) {
                                Log.i(
                                    TAG,
                                    "SCO cool-down: hot-mic idle ${idleMs}ms ≥ ${HOT_MIC_IDLE_RELEASE_MS}ms — releasing",
                                )
                                scoLink.release(this@TxController)
                                scoLink.removeStateListener(scoListener)
                                holdsSco = false
                                return@synchronized
                            }
                            Log.d(
                                TAG,
                                "SCO cool-down: hot-mic on, holding warm (idle=${idleMs}ms)",
                            )
                            cooldownHandler.postDelayed(this, SCO_COOL_DOWN_MS)
                            return@synchronized
                        }
                        Log.i(TAG, "SCO cool-down expired — releasing TX ref")
                        scoLink.release(this@TxController)
                        scoLink.removeStateListener(scoListener)
                        holdsSco = false
                    }
                }
            }
        }

    /**
     * Hot Mic Mode: pre-acquire the SCO link so the next PTT skips the
     * 500-1500 ms cold-start. Idempotent — safe to call repeatedly. No
     * effect when Bluetooth isn't the audio path (built-in mic doesn't
     * need SCO at all). Pair with [disarmHotMic] when leaving the
     * channel.
     */
    @Synchronized
    fun armHotMic() {
        if (!hotMicMode()) {
            Log.d(TAG, "armHotMic: hotMicMode pref off — no-op")
            return
        }
        if (holdsSco) {
            Log.d(TAG, "armHotMic: SCO already held")
            return
        }
        val isHfp = btPolicy.classify() == BtAudioMode.HFP_ONLY
        if (!isHfp) {
            Log.i(TAG, "armHotMic: non-HFP route — no SCO needed; nothing to warm")
            return
        }
        Log.i(TAG, "armHotMic: pre-acquiring SCO so first PTT is instant")
        scoLink.addStateListener(scoListener)
        scoLink.acquire(this)
        holdsSco = true
        // Seed the idle clock and start the cool-down poll so an
        // armed-but-never-pressed Hot Mic still respects the
        // HOT_MIC_IDLE_RELEASE_MS ceiling. Without this, the cool-down
        // runnable would never be scheduled (it's only posted from
        // stopInternal) and SCO would stay up for the entire channel
        // membership.
        lastStopMs = System.currentTimeMillis()
        cooldownHandler.removeCallbacks(coolDownReleaseRunnable)
        cooldownHandler.postDelayed(coolDownReleaseRunnable, SCO_COOL_DOWN_MS)
    }

    /**
     * Release the warm-held SCO link from [armHotMic]. Called when the
     * operator leaves the channel, when canTransmit goes false (server
     * suppressed us), or when hotMicMode is toggled off mid-session.
     * Safe regardless of whether [armHotMic] was ever called.
     */
    @Synchronized
    fun disarmHotMic() {
        if (state != State.IDLE) {
            // Mid-burst: defer the release until stopInternal lands so
            // we don't yank SCO out from under a live capture. The
            // cool-down release runnable will see hotMicMode is now
            // false and proceed with the standard release.
            Log.i(TAG, "disarmHotMic: TX in flight — release deferred to stopInternal")
            return
        }
        if (!holdsSco) return
        Log.i(TAG, "disarmHotMic: releasing warm-held SCO")
        cooldownHandler.removeCallbacks(coolDownReleaseRunnable)
        scoLink.release(this)
        scoLink.removeStateListener(scoListener)
        holdsSco = false
    }

    fun shutdown() {
        synchronized(this) {
            if (state != State.IDLE) stopInternal()
        }
        cooldownHandler.removeCallbacks(coolDownReleaseRunnable)
        cooldownHandler.removeCallbacks(primingTimeoutRunnable)
        capture?.let {
            it.stop()
            capture = null
        }
        // Defensive — if any prior code left the system mic in muted
        // state, restore it so phone calls / other apps work after
        // XV unload.
        try {
            audioManager.isMicrophoneMute = false
        } catch (t: Throwable) {
            Log.w(TAG, "shutdown: setMicrophoneMute(false) failed", t)
        }
        if (holdsSco) {
            scoLink.release(this)
            scoLink.removeStateListener(scoListener)
            holdsSco = false
        }
    }

    companion object {
        private const val TAG = "XvTx"

        // How long to wait for SCO to come up before falling back to TX
        // without it. Field measurement shows Pixel 9 Pro + AINA V2 takes
        // ~1500ms typical (matches setCommunicationDevice's 6-poll
        // confirm window). The previous 1500ms cap was on the edge: any
        // burst where SCO took 1501ms had the watchdog fire first,
        // skipping TPT/chirp and starting TX with a not-yet-warm mic
        // (silent audio). Bumped to 3000ms to match ScoLink's own
        // readiness-poll timeout (30 × 100ms) — beyond that, ScoLink
        // itself transitions DISCONNECTED and we abandon via the
        // listener path.
        private const val SCO_WARMUP_MS = 3000L

        // Hold the SCO link for this long after PTT-up before releasing
        // our ref. NOTE this 5 s TX cool-down is intentionally SHORTER
        // than the RX SCO_HOT window (8 s, AudioPlayback.scoHoldMs) and
        // the Telecom end-debounce (8 s, TELECOM_END_DEBOUNCE_MS): the
        // physical SCO link may drop at 5 s while the higher-level call
        // (media pause + focus) lingers to 8 s. They are deliberately
        // not equal — do not "reconcile" them.
        // Exposed (vs. private) so AudioControllerImpl can reuse it as
        // the phone-mode (non-BT) audio-plant hang time — same operator
        // back-and-forth UX whether the route is BT/SCO or phone speaker.
        const val SCO_COOL_DOWN_MS = 5_000L

        // With Hot Mic mode on, the cool-down runnable normally keeps
        // SCO warm indefinitely. This ceiling forces a release after
        // 60s of no PTT activity so the AINA can return to A2DP for
        // music, the headset can sleep, and battery isn't drained by
        // a permanently-open SCO link. The next press pays one cold-
        // start cost, then armHotMic re-acquires.
        private const val HOT_MIC_IDLE_RELEASE_MS = 60_000L

        // Number of mic frames to hold in the trailing buffer before
        // sending. On stop() these queued frames are dropped, swallowing
        // the PTT release click. Each frame is 10ms so 6 frames = 60ms
        // of latency on every TX — comfortably under the threshold the
        // user notices on PTT and enough to cover BLE event round-trip
        // (~15-40ms) plus the click's own audible duration.
        private const val TRAILING_FRAMES = 6

        // PRIMING gates TPT on the mic actually producing samples (not
        // just HAL warmup zeros). Threshold 5 = above the literal-zero
        // failure mode but below typical noise floor (5-15 in a quiet
        // room), so PRIMING completes within ~50-200ms of mic
        // allocation under normal conditions.
        private const val MIC_PRIMING_RMS_THRESHOLD = 5

        /**
         * RMS amplitude of a PCM frame. Pure function — promoted out of
         * the class body into the companion object so tests can call
         * `TxController.rms(...)` directly without instantiating the
         * full controller (which requires ScoLink + BtAudioPolicy +
         * TptPlayer + AudioCapture + ... — a non-trivial mock setup).
         *
         * `internal` so it's visible to same-module unit tests but stays
         * hidden from any other module that links against XV.
         */
        internal fun rms(s: ShortArray): Int {
            if (s.isEmpty()) return 0
            var sum = 0.0
            for (v in s) sum += (v.toDouble() * v.toDouble())
            return kotlin.math.sqrt(sum / s.size).toInt()
        }

        // Frame-count alternative for PRIMING completion: if the mic
        // capture loop has delivered this many frames (regardless of
        // RMS), we know it's alive — proceed to TPT. At ~10ms/frame,
        // 5 frames = 50ms after PRIMING entry. Surface Duo's built-in
        // mic produces silent frames until somebody speaks, so the
        // RMS-only gate stalls PRIMING for the full timeout; the
        // frame-count alternative makes the on-screen PTT respond
        // within ~100-150ms instead of ~1500ms.
        private const val MIC_PRIMING_MIN_FRAMES_ALIVE = 5

        // Upper bound on PRIMING wait time — if no non-silent frame
        // arrives within this window the chipset is verifiably broken
        // (vs slow); we bonk and abandon rather than playing TPT and
        // shipping silence, since "you're on the air" with a dead mic
        // is the worst possible UX. When the mic IS coming online it
        // does so within 100-300ms even on Duo+V1; the 2.5s prior
        // value was tuned for the case where the chipset hadn't been
        // pumping yet and needed multiple seconds to wake up. With
        // pre-warm + the MIC_PRIMING_MIN_FRAMES_ALIVE early-exit, the
        // typical complete-PRIMING wall-time is now 50-150ms — this
        // 500ms cap is just a "mic is genuinely broken" fallback.
        // Field result: on-screen PTT goes from "press → ~2s latency"
        // to "press → ~150ms latency" on Surface Duo built-in mic.
        private const val MIC_PRIMING_TIMEOUT_MS = 500L

        // ---------- BT SCO cold-start PRIMING gates ----------
        //
        // The non-SCO PRIMING values above assume the mic delivers its
        // first frame within one frame period and produces stable
        // amplitude within a handful of frames. BT SCO chipsets do
        // neither on cold start:
        //
        //   * First frame can arrive 300-1000 ms after AudioRecord
        //     start, well past the 500 ms non-SCO timeout — which
        //     produces the "PRIMING: 0 frames seen — bonking (mic
        //     dead)" abort even though the chipset was about to
        //     deliver.
        //   * The first ~30 frames after delivery starts are chipset
        //     ramp-up noise: rms 5-15, well above the non-SCO
        //     MIC_PRIMING_RMS_THRESHOLD of 5, so the RMS gate fires
        //     "mic ready" on hiss instead of speech. TPT plays, TX
        //     starts, and the encoder gets ~500 ms of near-silent
        //     Opus frames before the operator's voice reaches the
        //     wire — peer hears silence, then mid-syllable.
        //
        // These SCO-only gates raise the RMS threshold above the
        // chipset's noise floor, require ~300 ms of stable frame
        // delivery before declaring ready via the frame-count path,
        // and extend the timeout so the chipset has room to warm up
        // before we call it dead. Applied ONLY to cold-SCO bursts:
        // selected at startPriming() from currentBurstColdSco and pinned
        // into primingUseColdScoGates so mid-priming SCO state changes
        // don't split-brain the gate logic. Warm-SCO bursts (settled
        // chipset) deliberately use the fast non-SCO gates.
        //
        // Values calibrated from field capture 2026-07-08 (AINA V2
        // AINA V2 puck + Pixel 9 Pro), where a cold-SCO PTT produced:
        //   - AudioRecord alloc → first frame: ~863 ms
        //   - Frames #1-30 rms range: 1-13 (chipset ramp)
        //   - Frame #60 (~570 ms in): rms 1070 (first real speech)
        //   - Frame #90 (~870 ms in): rms 13 (mic settled, silent)

        // Noise floor of a cold BT SCO chipset commonly reaches
        // ~10-15 within the first 100 ms of delivery. 200 sits well
        // above that but well below normal speech onset (rms 300+
        // per syllable, 1000+ per vowel), so it only fires on
        // actual speech.
        private const val MIC_PRIMING_RMS_THRESHOLD_SCO = 200

        // Wait for ~300 ms of continuous frame delivery before
        // declaring the chipset ready via the frame-count path. 30
        // frames * 10 ms/frame = 300 ms. Field-observed chipset
        // settle time is 300-500 ms; below 300 ms the encoder is
        // still fed ramp-up noise, above 500 ms operators start
        // perceiving TPT latency.
        private const val MIC_PRIMING_MIN_FRAMES_ALIVE_SCO = 30

        // Field-observed cold-SCO first-frame latency was 863 ms,
        // and the previous 500 ms timeout aborted TX with "mic
        // dead" while the chipset was mid-warmup. 1500 ms gives
        // real cold starts room to deliver frames without producing
        // a false-dead abort, while still bounding the wait for a
        // truly wedged chipset.
        private const val MIC_PRIMING_TIMEOUT_MS_SCO = 1500L

        // Minimum wall-clock between a stop() and the next accepted
        // start() on the COLD path (SCO not yet CONNECTED). Inside
        // this window, a fresh PTT-down is dropped with a log line.
        // 200ms is the sweet spot from field testing: long enough to
        // let one PRIMING/TPT/TX/stop cycle drain the chipset cleanly,
        // short enough that intentional rapid back-and-forth (3-4
        // presses/sec) still works.
        private const val START_REFRACTORY_MS = 200L

        // Minimum wall-clock when SCO is already CONNECTED (warm).
        // The chipset isn't being re-acquired, so the only thing the
        // gate protects is audio-buffer flush + our internal state
        // teardown. 50ms covers that without limiting rapid intentional
        // re-keys. Effect: warm-path rapid-tap PTT is ~150ms faster.
        private const val STOP_TO_START_WARM_MIN_MS = 50L

        // ---------- Cold-SCO start-of-burst polish (2026-07-10) ----------
        //
        // Field observation on Pixel 9 Pro + AINA V2: the first TX after
        // app launch, or the first TX after a long idle (SCO cool-down
        // long since expired), consistently produced garbled audio in
        // the first ~30-60 ms of the burst on the receiving peer.
        // Logcat scan of that TX window showed the Pixel's Always-On
        // Compute audio mixer under-running its uplink buffer during
        // the SCO warm-up:
        //
        //   D/AOC: [AMixMODEM] uplink underrun
        //   D/AOC: [AMixMODEM] uplink underrun for 86 frames
        //   D/AOC: [AMixMODEM] CCA rb underrun (0 < 480)
        //
        // 86 dropped frames at the modem's frame size = ~30 ms of
        // dropped audio right at the start of the burst. PRIMING's
        // "mic ready" declaration is correct — the mic IS reading
        // valid audio — but the SCO uplink modem is still stabilizing
        // AFTER that point, so real mic frames land in the underrun
        // window and encode to garbage.
        //
        // Two complementary mitigations, both scoped to cold-SCO only:
        //
        //   1. Extend PRIMING → TPT by [COLD_SCO_TPT_HOLD_MS] on the
        //      cold-SCO path so the modem has time to settle before
        //      the operator hears the go-ahead tone. Warm-SCO and
        //      non-SCO bursts skip the hold entirely — see
        //      [computePrimingHoldMs].
        //
        //   2. Silently drop the first [COLD_SCO_START_DROP_FRAMES] TX
        //      frames past state=TRANSMITTING on cold-SCO bursts.
        //      At 10 ms/frame (480 samples @ 48 kHz) the current N=6 is
        //      60 ms of dropped leading edge,
        //      typically covering a breath or click rather than
        //      intelligible speech. Analogous to the trailing-frame
        //      swallow ([TRAILING_FRAMES]) that eats the PTT release
        //      click at TX-stop — same shape, opposite end of the
        //      burst. See [shouldDropStartFrame].

        // Extra PRIMING → TPT delay on the cold-SCO path.
        //
        // Original tuning 2026-07-08: 200 ms — chosen against the
        // observed AOC "[AMixMODEM] uplink underrun for 86 frames"
        // window (~30 ms of literal underrun + ~150 ms pipeline
        // recovery margin).
        //
        // Field observation 2026-07-11 (TPP validation, Pixel 9 Pro
        // with AINA V1): peer still heard garbled audio ("screech")
        // at the head of the first cold-SCO transmission. 200 ms
        // covers the underrun burst itself but leaves the modem/pipe
        // in a settling phase during the TPT→TX transition — real
        // mic frames land against a still-stabilizing uplink and
        // encode to Opus screech at the peer. Widened to 300 ms to
        // give the pipeline a full 100 ms of additional post-
        // underrun margin. Perceptible latency cost: +100 ms of
        // "waiting for the beep" on a cold burst; warm-SCO bursts
        // (the common case in an active session) are unaffected.
        //
        // Tunable — smaller values leave residual underrun in the
        // TPT-to-TX transition; larger values are perceived as PTT
        // sluggishness.
        internal const val COLD_SCO_TPT_HOLD_MS: Long = 300L

        // Number of leading TX frames to silently discard on the cold-
        // SCO path. Each frame is 10 ms of PCM (480 samples @ 48 kHz —
        // see AudioCapture.frameSamples).
        //
        // Original tuning 2026-07-08: N=3 (30 ms). Covered the
        // literal underrun burst well and left the TPT→speech
        // ramp intact.
        //
        // Field observation 2026-07-11: with the widened 300 ms
        // cold-SCO hold above, the first-frame-post-transition
        // window is quieter but the SILK encoder still catches a
        // few frames of "not-yet-clean" mic before it locks in. N=6
        // = 60 ms of dropped leading edge, still well inside the
        // typical 250-400 ms human reaction time from TPT-audible
        // to first phoneme, so operator speech is not eaten.
        //
        // Set to 0 to disable the drop entirely (useful for A/B
        // comparison or for platforms without the AOC modem behavior).
        internal const val COLD_SCO_START_DROP_FRAMES: Int = 6

        /**
         * Extra time to hold PRIMING → TPT so the SCO uplink modem
         * stabilizes past its cold-start underrun window. Returns
         * [baseMs] for every non-cold-SCO case (warm SCO, non-SCO
         * route entirely, feature disabled via a zero
         * [COLD_SCO_TPT_HOLD_MS]).
         *
         * Pure function — no dependency on TxController state; tests
         * exercise it directly.
         *
         * Semantics:
         *  - cold=false           → baseMs (warm-SCO: modem stable, no hold)
         *  - route != TxRoute.SCO → baseMs (non-SCO: no AOC modem in play)
         *  - COLD_SCO_TPT_HOLD_MS == 0 → baseMs (feature disabled)
         *  - otherwise            → baseMs + COLD_SCO_TPT_HOLD_MS
         */
        internal fun computePrimingHoldMs(
            route: TxRoute,
            cold: Boolean,
            baseMs: Long,
        ): Long {
            if (!cold) return baseMs
            if (route != TxRoute.SCO) return baseMs
            if (COLD_SCO_TPT_HOLD_MS <= 0L) return baseMs
            return baseMs + COLD_SCO_TPT_HOLD_MS
        }

        // ---------- PRIMING gate selection (cold-SCO vs everything else) ----------
        //
        // The ramp-tolerant SCO gates exist ONLY for a cold BT SCO
        // acquire, where the chipset delivers 300-1000 ms of warmup
        // noise. A warm-held SCO link (cool-down tail / RX SCO_HOT) has a
        // settled chipset with no ramp, so it — like a non-SCO route —
        // uses the fast gates. Keying these on `coldSco` (not "SCO is
        // connected") is the 2026-07-13 field fix: warm bursts previously
        // paid the 30-frame (~300 ms) cold gate, and with bursty keying
        // the operator released before TX started and zero frames went
        // out. Pure functions so the selection is unit-tested directly.

        /** Min frames delivered before PRIMING declares the mic alive. */
        internal fun primingMinFramesToConfirmAlive(coldSco: Boolean): Int =
            if (coldSco) MIC_PRIMING_MIN_FRAMES_ALIVE_SCO else MIC_PRIMING_MIN_FRAMES_ALIVE

        /** RMS above which PRIMING treats a frame as real speech. */
        internal fun primingRmsThreshold(coldSco: Boolean): Int =
            if (coldSco) MIC_PRIMING_RMS_THRESHOLD_SCO else MIC_PRIMING_RMS_THRESHOLD

        /** Upper bound on the PRIMING wait before bonking/proceeding. */
        internal fun primingTimeoutMs(coldSco: Boolean): Long =
            if (coldSco) MIC_PRIMING_TIMEOUT_MS_SCO else MIC_PRIMING_TIMEOUT_MS

        /**
         * Should this leading TX frame be silently discarded to skip
         * past the cold-SCO Pixel AOC modem underrun window?
         *
         * [frameNumber] is 1-based (frame #1 is the first PCM frame to
         * reach state=TRANSMITTING for this burst). Returns true only
         * when ALL of the following hold:
         *  - the burst is cold-SCO ([cold] && [route] == [TxRoute.SCO])
         *  - the feature is enabled ([dropCount] > 0)
         *  - this frame falls within the drop window
         *    ([frameNumber] <= [dropCount])
         *
         * Pure function — no dependency on TxController state; tests
         * exercise it directly.
         */
        internal fun shouldDropStartFrame(
            frameNumber: Int,
            route: TxRoute,
            cold: Boolean,
            dropCount: Int,
        ): Boolean {
            if (!cold) return false
            if (route != TxRoute.SCO) return false
            if (dropCount <= 0) return false
            return frameNumber in 1..dropCount
        }
    }
}
