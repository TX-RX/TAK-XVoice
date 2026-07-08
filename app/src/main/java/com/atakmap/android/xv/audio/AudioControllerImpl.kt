package com.atakmap.android.xv.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.VisibleForTesting
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Default AudioController implementation. Talks to Android AudioManager
 * directly. The state transitions follow the contract in
 * project_xv_audio_focus_state_machine.md — see that memory for the
 * design and the four-state table.
 *
 * SCO handling: this class does NOT touch SCO at all. The SCO link is
 * owned exclusively by [ScoLink], which is ref-counted across
 * [TxController] (TX bursts + cool-down) and [AudioPlayback] (RX +
 * SCO_HOT). enterTx/exitTx here only manage audio focus + mode; the
 * BT routing is driven by ScoLink.acquire/release as the conversational
 * holders enter and leave their states.
 *
 * Phone-call awareness lives in TelephonyMonitor (separate); this class
 * only reacts to AudioFocus events. When the system grants focus elsewhere
 * (incoming phone call, alarm, navigation prompt that requested
 * exclusive), AudioFocus.LOSS / LOSS_TRANSIENT pulls us into SUSPENDED;
 * GAIN brings us back to IDLE.
 */
@SuppressLint("MissingPermission")
class AudioControllerImpl(
    context: Context,
) : AudioController {
    private val audioManager: AudioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    @Volatile
    private var currentState: AudioState = AudioState.IDLE

    @Volatile
    private var preSuspendState: AudioState = AudioState.IDLE

    // Each RX burst from a peer fires enterRx() once; when the audio plant
    // is SUSPENDED (focus held elsewhere — typically Telecom mid-handoff)
    // every burst logs the same "ignored" line. Throttle to one log per
    // SUSPENDED-period. Cleared in [transitionTo] on any move out of
    // SUSPENDED so the next entry into the state still produces a marker.
    @Volatile
    private var loggedSuspendedDenial: Boolean = false

    @Volatile
    // Track whether MODE_IN_COMMUNICATION is currently engaged by us.
    // Null = we haven't touched the audio mode yet (still at whatever it
    // was when our state was IDLE). Non-null = we've captured the pre-
    // engage mode and are running in IN_COMMUNICATION; restore-to-this
    // on returnToIdle. Without this both enterRx and enterTx would each
    // independently save/restore and clobber each other across the
    // RX → RX_TX → RX transitions.
    private var savedMode: Int? = null

    // Track whether WE enabled speakerphone. Null = we haven't touched
    // it. Non-null = we captured the pre-engage state and turned it on
    // because no BT speakermic was engaged — restore on returnToIdle so
    // we don't strand the operator's phone with speakerphone forced on
    // after the voice session ends.
    private var savedSpeakerphoneOn: Boolean? = null

    // Saved pre-engage STREAM_VOICE_CALL volume. Null = we haven't
    // touched it. On fresh installs / devices that have never received
    // a phone call (notably Surface Duo 2), STREAM_VOICE_CALL is at
    // idx 0-2 of a 1-10 scale → effectively muted. The operator opens
    // XV, hears nothing, has no way to discover the OS hides voice-call
    // volume behind a separate slider. Bump to ~80% of max on engage,
    // restore prior level on idle.
    private var savedVoiceCallVolume: Int? = null

    private val listeners = CopyOnWriteArraySet<AudioStateListener>()

    private var rxFocusRequest: AudioFocusRequest? = null
    private var txFocusRequest: AudioFocusRequest? = null

    // Phone-mode hang time. After TX or RX ends, the audio plant keeps
    // MODE_IN_COMMUNICATION + speakerphone-if-no-SCO + voice-call volume
    // bump for SCO_COOL_DOWN_MS so an immediate re-engage (next PTT or
    // next RX burst within the cooldown) doesn't pay the mode-flip
    // round-trip — same UX as the BT/SCO cooldown in TxController. If
    // we don't re-engage within the window, the runnable fires and
    // restores the prior state. Re-engage from RX or TX cancels the
    // pending release. Suspend takes precedence and forces an immediate
    // release.
    private val phoneModeHandler = Handler(Looper.getMainLooper())
    private val phoneModeReleaseRunnable =
        Runnable {
            Log.i(TAG, "phone-mode cool-down fired — restoring mode/speakerphone/volume")
            applyPhoneModeRestore()
        }

    // Optional ScoLink reference. When wired, system focus loss
    // (incoming phone call, alarm, nav prompt) calls
    // [ScoLink.handleSystemSuspend] so ScoLink can publish SUSPENDED
    // and clean up its surface — instead of us calling stopBluetoothSco
    // directly and breaking ScoLink's invariants. Lazy-set to avoid
    // construction-order coupling: VoicePlant builds AudioControllerImpl
    // before ScoLink and calls [setScoLink] once both are alive.
    @Volatile
    private var scoLink: ScoLink? = null

    /** Wire the ScoLink that owns the SCO surface. Set once at startup. */
    fun setScoLink(link: ScoLink) {
        scoLink = link
    }

    private val focusListener =
        AudioManager.OnAudioFocusChangeListener { focusChange ->
            when (focusChange) {
                AudioManager.AUDIOFOCUS_LOSS,
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK,
                -> {
                    if (currentState == AudioState.RX || currentState == AudioState.TX) {
                        Log.i(TAG, "Audio focus lost ($focusChange) — suspending")
                        suspendInternal()
                    }
                }

                AudioManager.AUDIOFOCUS_GAIN -> {
                    if (currentState == AudioState.SUSPENDED) {
                        Log.i(TAG, "Audio focus regained — returning to IDLE")
                        transitionTo(AudioState.IDLE)
                        // Higher layer is responsible for re-requesting RX/TX.
                    }
                }
            }
        }

    override val state: AudioState
        get() = currentState

    override fun enterRx(): Boolean {
        // Cancel any pending phone-mode release left over from a prior
        // TX/RX cool-down — we're using the audio plant again, the
        // restore would yank MODE_IN_COMMUNICATION + speakerphone mid-
        // engage if it fired while RX is active.
        phoneModeHandler.removeCallbacks(phoneModeReleaseRunnable)
        if (currentState == AudioState.RX || currentState == AudioState.RX_TX) return true
        if (currentState == AudioState.SUSPENDED) {
            if (!loggedSuspendedDenial) {
                Log.w(TAG, "enterRx() called while SUSPENDED — ignored")
                loggedSuspendedDenial = true
            }
            return false
        }
        if (currentState == AudioState.TX) {
            // TX active - transition to RX_TX for full-duplex
            val request = buildRxFocusRequest()
            val granted =
                audioManager.requestAudioFocus(request) ==
                    AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            if (!granted) {
                Log.w(TAG, "RX focus request denied while in TX")
                return false
            }
            rxFocusRequest = request
            transitionTo(AudioState.RX_TX)
            return true
        }
        val request = buildRxFocusRequest()
        val granted =
            audioManager.requestAudioFocus(request) ==
                AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        if (!granted) {
            Log.w(TAG, "RX focus request denied")
            return false
        }
        rxFocusRequest = request
        // Engage MODE_IN_COMMUNICATION so the playback (which now uses
        // USAGE_VOICE_COMMUNICATION unconditionally) routes through the
        // call audio path — earpiece by default, loudspeaker when
        // speakerphone is on, BT SCO when engaged. Without this, RX
        // through USAGE_VOICE_COMMUNICATION in MODE_NORMAL would route
        // to the small earpiece speaker at very low volume tied to
        // STREAM_VOICE_CALL's idle volume (operator feedback 2026-05-11:
        // "volume was really quiet"). Only save if we haven't already
        // captured the pre-engage mode (enterRx + enterTx interleave
        // through RX → RX_TX → RX without clobbering savedMode).
        if (savedMode == null) {
            savedMode = audioManager.mode
        }
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        // SCO is owned by ScoLink; not engaged here. Idle phone-speaker
        // playback routes via the call audio path automatically.
        engageSpeakerphoneIfNoSco()
        bumpVoiceCallVolumeIfLow()
        transitionTo(AudioState.RX)
        return true
    }

    override fun enterTx(): Boolean {
        // Same cool-down cancellation as enterRx — re-engaging the audio
        // plant must invalidate any pending restore so we don't lose
        // MODE_IN_COMMUNICATION mid-burst.
        phoneModeHandler.removeCallbacks(phoneModeReleaseRunnable)
        if (currentState == AudioState.TX || currentState == AudioState.RX_TX) return true
        if (currentState == AudioState.SUSPENDED) {
            if (!loggedSuspendedDenial) {
                Log.w(TAG, "enterTx() called while SUSPENDED — ignored")
                loggedSuspendedDenial = true
            }
            return false
        }
        // DON'T release RX focus — we want full-duplex (hear others while transmitting)
        val request = buildTxFocusRequest()
        val granted =
            audioManager.requestAudioFocus(request) ==
                AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        if (!granted) {
            Log.w(TAG, "TX focus request denied")
            return false
        }
        txFocusRequest = request
        // Same save-once pattern as enterRx — savedMode is captured
        // exactly once on the first transition out of IDLE and restored
        // on returnToIdle. Multiple enterRx/enterTx in either order
        // through RX_TX won't clobber it.
        if (savedMode == null) {
            savedMode = audioManager.mode
        }
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        bumpVoiceCallVolumeIfLow()
        // ScoLink owns SCO; this method must not touch it. The SCO link
        // is reference-counted across TxController + AudioPlayback, with
        // an AINA-pinned comm device + watchdog + system-suspend handling
        // all centralised there. A second startBluetoothSco/isBluetoothScoOn
        // here previously fought ScoLink: BT chipset received two
        // engagements per logical session, comm-device flipped between
        // legacy and explicit-pin modes, and AINA routing became
        // race-dependent (sometimes earpiece, sometimes BT). Routing
        // arrives via TxController.start() / AudioPlayback.beginPlayback()
        // calling scoLink.acquire(); we just set audio mode + focus here.
        // Transition to RX_TX if we were in RX, otherwise TX
        transitionTo(if (currentState == AudioState.RX) AudioState.RX_TX else AudioState.TX)
        return true
    }

    override fun returnToIdle() {
        when (currentState) {
            AudioState.IDLE -> return
            AudioState.RX -> releaseRxFocus()
            AudioState.TX -> releaseTxAndScoAndMode()
            AudioState.RX_TX -> {
                // Release both RX and TX
                releaseTxAndScoAndMode()
                releaseRxFocus()
            }
            AudioState.SUSPENDED -> {
                // We didn't hold focus while suspended; nothing to release.
            }
        }
        transitionTo(AudioState.IDLE)
    }

    override fun exitTx() {
        when (currentState) {
            AudioState.TX -> {
                releaseTxFocusAndMode()
                transitionTo(AudioState.IDLE)
            }
            AudioState.RX_TX -> {
                releaseTxFocusAndMode()
                transitionTo(AudioState.RX)
            }
            else -> {
                // IDLE, RX, SUSPENDED: nothing TX-specific to release.
            }
        }
    }

    // ScoLink owns SCO; this method must not touch it. Releases TX
    // focus + restores audio mode only. The SCO link itself (and the
    // `isBluetoothScoOn` media-routing override) are entirely owned by
    // ScoLink. Touching either from here previously fought ScoLink's
    // ref-counted lifetime — clearing `isBluetoothScoOn` while ScoLink
    // still held the link for SCO_HOT broke routing for the cool-down
    // window; the next acquire would PRIME against a dead surface and
    // generate an unearned bonk tone.
    private fun releaseTxFocusAndMode() {
        // Restore mode only if WE captured it. savedMode==null means the
        // RX path is still active or holding the engagement — in that
        // case we keep MODE_IN_COMMUNICATION until releaseRxFocus runs.
        // Also: only flip back when the OTHER state isn't still
        // engaging mode (RX_TX transitions to RX should not restore).
        if (currentState != AudioState.RX_TX && currentState != AudioState.RX) {
            schedulePhoneModeRelease()
        }
        txFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        txFocusRequest = null
    }

    override fun shutdown() {
        returnToIdle()
        listeners.clear()
    }

    override fun addListener(listener: AudioStateListener) {
        listeners.add(listener)
    }

    override fun removeListener(listener: AudioStateListener) {
        listeners.remove(listener)
    }

    private fun suspendInternal() {
        preSuspendState = currentState
        when (currentState) {
            AudioState.RX -> releaseRxFocusImmediate()
            AudioState.TX -> releaseTxAndScoAndMode()
            AudioState.RX_TX -> {
                releaseTxAndScoAndMode()
                releaseRxFocus()
            }
            else -> {}
        }
        transitionTo(AudioState.SUSPENDED)
    }

    // System-suspend variant of [releaseRxFocus]. The regular teardown
    // path schedules the phone-mode restore SCO_COOL_DOWN_MS in the
    // future so a rapid re-engage lands warm; but on focus-loss we
    // just handed the audio session to whatever the system granted
    // focus to — most commonly Telephony placing MODE_IN_CALL for an
    // incoming phone call. A deferred restore that fires while the
    // phone call is mid-audio blows away MODE_IN_CALL (setting it back
    // to MODE_NORMAL) and clobbers STREAM_VOICE_CALL's volume mid-
    // call — the operator sees "volume buttons regressed / call
    // audio suddenly quiet or mis-routed" a few seconds into the
    // phone call. Match TX's suspend behavior: cancel any pending
    // deferred restore and apply the pre-engage state immediately so
    // whatever the system does next starts from a clean baseline.
    private fun releaseRxFocusImmediate() {
        rxFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        rxFocusRequest = null
        phoneModeHandler.removeCallbacks(phoneModeReleaseRunnable)
        applyPhoneModeRestore()
    }

    // Bring STREAM_VOICE_CALL up to a usable level on engage. On
    // tactical devices that haven't been used as phones (Surface Duo,
    // ruggedized handsets), the call stream's volume index is often
    // 0-2 of max 10, and the operator has no way to discover that the
    // OS hides voice-call volume behind a separate slider until
    // they're already in a call. Bump to 80% on engage, restore prior
    // level on idle. Only bumps if current is below the target so we
    // never overwrite a deliberately-high preference.
    private fun bumpVoiceCallVolumeIfLow() {
        if (savedVoiceCallVolume != null) return // already engaged by us
        try {
            val stream = AudioManager.STREAM_VOICE_CALL
            val current = audioManager.getStreamVolume(stream)
            val max = audioManager.getStreamMaxVolume(stream)
            val target = (max * 0.8).toInt().coerceAtLeast(1)
            savedVoiceCallVolume = current
            if (current < target) {
                audioManager.setStreamVolume(stream, target, 0)
                Log.i(
                    TAG,
                    "STREAM_VOICE_CALL volume bumped $current → $target of $max " +
                        "(prior preserved for restore on idle)",
                )
            } else {
                Log.i(
                    TAG,
                    "STREAM_VOICE_CALL volume already $current/$max (above 80% target) — leaving",
                )
            }
        } catch (t: Throwable) {
            Log.w(TAG, "bumpVoiceCallVolumeIfLow threw", t)
            savedVoiceCallVolume = null
        }
    }

    private fun restoreVoiceCallVolumeIfOwned() {
        val prior = savedVoiceCallVolume ?: return
        savedVoiceCallVolume = null
        try {
            audioManager.setStreamVolume(AudioManager.STREAM_VOICE_CALL, prior, 0)
            Log.i(TAG, "STREAM_VOICE_CALL volume restored to $prior")
        } catch (t: Throwable) {
            Log.w(TAG, "restoreVoiceCallVolumeIfOwned threw", t)
        }
    }

    // When entering RX with no BT speakermic engaged, turn speakerphone
    // ON so audio plays through the loud loudspeaker instead of the
    // tiny earpiece. With USAGE_VOICE_COMMUNICATION + MODE_IN_COMMUNI-
    // CATION + no SCO + speakerphone OFF the OS routes to the earpiece
    // — operator hears the channel at "phone call held to ear" volume,
    // which is way too quiet for tactical use. Speakerphone-on emulates
    // the always-on radio behavior operators expect. If a BT speakermic
    // is engaged later (TxController.start grabs SCO), the BT route
    // wins regardless of this setting. Skip if speakerphone is already
    // on (don't double-save).
    private fun engageSpeakerphoneIfNoSco() {
        val sco = scoLink
        if (sco != null && sco.holdersCount() > 0) return
        if (savedSpeakerphoneOn != null) return // already engaged by us
        try {
            @Suppress("DEPRECATION")
            val prior = audioManager.isSpeakerphoneOn
            savedSpeakerphoneOn = prior
            if (!prior) {
                @Suppress("DEPRECATION")
                audioManager.isSpeakerphoneOn = true
                Log.i(TAG, "speakerphone ON (no SCO; routing RX to loudspeaker)")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "engageSpeakerphoneIfNoSco threw", t)
            savedSpeakerphoneOn = null
        }
    }

    private fun restoreSpeakerphoneIfOwned() {
        val prior = savedSpeakerphoneOn ?: return
        savedSpeakerphoneOn = null
        try {
            @Suppress("DEPRECATION")
            audioManager.isSpeakerphoneOn = prior
            Log.i(TAG, "speakerphone restored to $prior")
        } catch (t: Throwable) {
            Log.w(TAG, "restoreSpeakerphoneIfOwned threw", t)
        }
    }

    // Schedule the phone-mode plant restore for SCO_COOL_DOWN_MS in the
    // future. Cancels any prior pending runnable first so the most
    // recent voice-traffic event sets the cool-down clock. Mirrors the
    // SCO cool-down hold so phone-screen PTT + speaker RX have the same
    // back-and-forth UX as BT speakermic operation: voice ends, plant
    // stays warm for 5s, next press lands without a mode-flip stutter.
    private fun schedulePhoneModeRelease() {
        phoneModeHandler.removeCallbacks(phoneModeReleaseRunnable)
        phoneModeHandler.postDelayed(phoneModeReleaseRunnable, TxController.SCO_COOL_DOWN_MS)
        Log.i(
            TAG,
            "phone-mode cool-down armed for ${TxController.SCO_COOL_DOWN_MS}ms " +
                "(mode/speakerphone/voice-volume restore deferred)",
        )
    }

    // Restore the audio plant: pre-engage mode, speakerphone, voice-call
    // volume. Idempotent — each restore-if-owned helper no-ops when its
    // saved state is already null. Called either from the cool-down
    // runnable or immediately on suspend / shutdown.
    private fun applyPhoneModeRestore() {
        savedMode?.let { audioManager.mode = it }
        savedMode = null
        restoreSpeakerphoneIfOwned()
        restoreVoiceCallVolumeIfOwned()
    }

    private fun releaseRxFocus() {
        rxFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        rxFocusRequest = null
        // Restore audio mode + speakerphone if we owned them. Only when
        // we're truly done (no TX still engaged); the RX_TX→TX path
        // takes care of itself via releaseTxFocusAndMode.
        if (currentState != AudioState.RX_TX && currentState != AudioState.TX) {
            schedulePhoneModeRelease()
        }
    }

    private fun releaseTxAndScoAndMode() {
        // Notify ScoLink that the system has yanked the link. ScoLink
        // marks itself SUSPENDED, cleans up its SCO surface, and
        // publishes SUSPENDED to listeners (TxController + AudioPlayback)
        // so they can choose to retry-acquire or release. Calling
        // stopBluetoothSco directly here previously left ScoLink's
        // holders set populated and state==CONNECTED while the physical
        // link was actually gone — the next acquire skipped CONNECTING
        // and PRIMED against a dead surface, generating an unearned
        // bonk and frustrated operator.
        try {
            scoLink?.handleSystemSuspend()
        } catch (t: Throwable) {
            Log.w(TAG, "scoLink.handleSystemSuspend threw", t)
        }
        // System-driven suspend (focus loss, telephony call) — restore
        // immediately, no cooldown. Cancel any pending phone-mode
        // release that was scheduled by a regular TX/RX exit.
        phoneModeHandler.removeCallbacks(phoneModeReleaseRunnable)
        applyPhoneModeRestore()
        txFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        txFocusRequest = null
    }

    private fun transitionTo(next: AudioState) {
        val prev = currentState
        if (prev == next) return
        currentState = next
        if (prev == AudioState.SUSPENDED) loggedSuspendedDenial = false
        Log.d(TAG, "audio state: $prev -> $next")
        listeners.forEach { it.onStateChanged(prev, next) }
    }

    private fun buildRxFocusRequest(): AudioFocusRequest =
        AudioFocusRequest
            // EXCLUSIVE: tell media apps "no parallel playback, no
            // ducking — pause until I'm done." Plain GAIN_TRANSIENT
            // lets apps choose duck-vs-pause; some (notably Tidal)
            // duck, which causes music to leak onto the AINA via the
            // active SCO link during peer voice. EXCLUSIVE forces
            // pause. Brief enough (just the burst + SCO_HOT cool-down)
            // that the media-session-destroy concern from the TX path
            // doesn't apply.
            .Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
            .setAudioAttributes(
                AudioAttributes
                    .Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            ).setOnAudioFocusChangeListener(focusListener)
            .build()

    private fun buildTxFocusRequest(): AudioFocusRequest =
        AudioFocusRequest
            // GAIN_TRANSIENT_EXCLUSIVE: hard pause for media apps
            // during TX. Operator wants no music bleed onto the AINA
            // during PTT, period. The earlier MAY_DUCK compromise was
            // to keep Tidal alive across rapid press cycles, but the
            // Telecom debounce now keeps the call ACTIVE for several
            // seconds across releases, so the focus thrash that
            // killed Tidal's session no longer applies.
            .Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
            .setAudioAttributes(
                AudioAttributes
                    .Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            ).setOnAudioFocusChangeListener(focusListener)
            .build()

    // Test seams. Real focus-loss dispatch is a system callback delivered
    // through the AudioFocusRequest's registered listener — Robolectric's
    // AudioManager shadow does not deliver those callbacks synchronously
    // through the request round-trip we make in [buildRxFocusRequest] /
    // [buildTxFocusRequest], so the unit test needs a direct hook to
    // exercise the suspend path. Kept package-private via
    // [VisibleForTesting] so production code cannot accidentally reach
    // through and short-circuit the focus-loss contract.

    /** Test hook: dispatch a synthetic focus-loss event to [focusListener]. */
    @VisibleForTesting(otherwise = VisibleForTesting.NONE)
    internal fun simulateFocusLossForTest() {
        focusListener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
    }

    companion object {
        private const val TAG = "XvAudio"
    }
}
