package com.atakmap.android.xv.service

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.Process
import android.os.RemoteCallbackList
import android.os.RemoteException
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.atakmap.android.xv.audio.OutputRoute
import com.atakmap.android.xv.audio.TptTone
import com.atakmap.android.xv.calling.CallStyleNotifier
import com.atakmap.android.xv.calling.NotificationChannels

// Foreground service hosting XV's voice plant: AudioRecord,
// AudioTrack, SCO, AINA BLE/SPP readers, Opus codec, PTT state
// machine. Lives in our APK's UID where FOREGROUND_SERVICE_TYPE_-
// MICROPHONE actually grants background-capture privileges. ATAK's
// UID never holds that privilege, which is why the prior in-plugin
// audio code stopped working when ATAK was backgrounded.
//
// Plugin in ATAK's UID binds via the IXvVoice AIDL stub; the service
// fans out TX Opus / state events to the registered listeners.
class XvVoiceService : Service() {
    // RemoteCallbackList handles linkToDeath registration internally:
    // when the binder on the other side of [register] dies (e.g. ATAK
    // process killed by the OOM killer), the listener is automatically
    // pruned. We previously did this by hand in fanOut() by catching
    // RemoteException and removing the listener — that worked for an
    // explicit call failure but missed silent process deaths between
    // calls. RemoteCallbackList notices those too via the death
    // recipient.
    private val listeners = RemoteCallbackList<IXvVoiceListener>()

    @Volatile
    private var plant: VoicePlant? = null

    private fun plant(): VoicePlant =
        plant ?: synchronized(this) {
            plant ?: VoicePlant(context = this, callbacks = plantCallbacks).also {
                plant = it
                Log.i(TAG, "VoicePlant constructed on demand")
            }
        }

    // Caller-UID allowlist for the AIDL surface. Resolved once at
    // onCreate. Any binder call from a UID outside this set throws
    // SecurityException — see assertAuthorizedCaller(). Without this
    // gate, any app on the device could bind XvVoiceService and call
    // pttDown / connectAina / onRxOpus / registerListener (cross-app
    // mic hijack). The service is exported because the plugin lives in
    // ATAK's UID, not ours, and bindService across UIDs requires
    // exported=true; we authenticate the caller in code instead of
    // relying on a signature-permission (XV is TPP-signed, ATAK is
    // Google-Play-signed, so a shared signature isn't available).
    private val authorizedUids = mutableSetOf<Int>()

    /** CallStyle (API 31+) / NotificationCompat (API <= 30) wrapper for
     *  the incoming-ring + ongoing-call surfaces. Replaces the legacy
     *  XvIncomingCallActivity / XvActiveCallActivity full-screen
     *  activities; Answer/Decline/Hangup taps post the same broadcasts
     *  the prior activities used, so incomingDecisionReceiver +
     *  activeCallReceiver continue to handle them unchanged. */
    private val callStyleNotifier: CallStyleNotifier by lazy { CallStyleNotifier(this) }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "onCreate (XV voice plant starting in pid=${Process.myPid()} uid=${Process.myUid()})")
        resolveAuthorizedUids()
        NotificationChannels.ensureAll(this)
        // Eagerly construct the voice plant: AudioCapture/AudioTrack/SCO
        // initialization is non-trivial, and the first PTT press
        // shouldn't pay that cost. The plugin's audio plant has been
        // de-fanged (no longer reaches AudioRecord), so there's no
        // double-grab race.
        plant()
        // Claim volume-key routing for STREAM_VOICE_CALL so the operator
        // can adjust XV's audio level even when there's no active TX/RX.
        setupVolumeKeyCapture()
        // Expose this service as the broadcast Context for XvConnection's
        // answer/decline path. XvConnection runs on Telecom's binder
        // thread and has no Context of its own; this gives it one
        // without injecting Context through the Connection constructor.
        // Cleared in onDestroy.
        com.atakmap.android.xv.telecom.ActiveCallRegistry.serviceContext = this
        com.atakmap.android.xv.telecom.ActiveCallRegistry
            .addExternalTeardownListener(externalTeardownListener)
        com.atakmap.android.xv.telecom.ActiveCallRegistry
            .addHoldStateListener(holdStateListener)
        com.atakmap.android.xv.telecom.ActiveCallRegistry
            .addIncomingDecisionListener(incomingDecisionListener)
        // Phase E: receive ANSWER/DECLINE taps from the system's
        // Notification.CallStyle surface (the OS-rendered incoming-call
        // notification with Answer / Decline buttons). Routed back into
        // the SAME ActiveCallRegistry decision-fanout so the system UI,
        // Telecom's onAnswer/onReject path, and the AINA MFB tap path
        // all converge here.
        val incomingFilter =
            android.content.IntentFilter().apply {
                addAction(CallStyleNotifier.ACTION_ANSWER)
                addAction(CallStyleNotifier.ACTION_DECLINE)
            }
        try {
            ContextCompat.registerReceiver(
                this,
                incomingDecisionReceiver,
                incomingFilter,
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
        } catch (t: Throwable) {
            Log.w(TAG, "registerReceiver(incomingDecisionReceiver) threw", t)
        }

        // Phase E: ongoing-call CallStyle action broadcasts (hang up,
        // mute, speaker). The Hang Up button on the system's CallStyle
        // notification posts ACTION_HANGUP_REQUESTED; mute/speaker
        // remain available for any in-process surface that wants to
        // toggle them via broadcast (kept for AINA MFB / debug use).
        val activeFilter =
            android.content.IntentFilter().apply {
                addAction(CallStyleNotifier.ACTION_HANGUP_REQUESTED)
                addAction(CallStyleNotifier.ACTION_SET_MUTED)
                addAction(CallStyleNotifier.ACTION_SET_SPEAKER)
                addAction(CallStyleNotifier.ACTION_SET_AUDIO_ROUTE)
            }
        try {
            ContextCompat.registerReceiver(
                this,
                activeCallReceiver,
                activeFilter,
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
        } catch (t: Throwable) {
            Log.w(TAG, "registerReceiver(activeCallReceiver) threw", t)
        }

        // Field bug 2026-07-08: operator holds an AINA V2 mid-TX, then
        // turns Bluetooth OFF on the phone. The individual reader's
        // BLE `onConnectionStateChange(STATE_DISCONNECTED)` MAY arrive
        // late (or on some OEM stacks not at all) when the OS is
        // tearing the BT stack down wholesale — so the reader-level
        // release path in [VoicePlant] can miss the edge. Register a
        // plugin-lifetime `BluetoothAdapter.ACTION_STATE_CHANGED`
        // listener as belt-and-suspenders. On `STATE_TURNING_OFF` (fires
        // slightly before `STATE_OFF`, giving us a window before the
        // GATT / RFCOMM handles have all torn themselves down), cascade
        // a forgetSource across every BT-sourced PttSource so any
        // in-flight burst can terminate cleanly and the operator has to
        // re-key on a working transport (screen PTT / phone mic) to
        // speak again. Idempotent when nothing is held.
        val btAdapterFilter =
            android.content.IntentFilter(android.bluetooth.BluetoothAdapter.ACTION_STATE_CHANGED)
        try {
            ContextCompat.registerReceiver(
                this,
                btAdapterStateReceiver,
                btAdapterFilter,
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
        } catch (t: Throwable) {
            Log.w(TAG, "registerReceiver(btAdapterStateReceiver) threw", t)
        }
    }

    // Fires on `BluetoothAdapter.ACTION_STATE_CHANGED`. On
    // `STATE_TURNING_OFF` we cascade a PTT release across all BT-sourced
    // inputs — see the registration site in onCreate for the field bug
    // this closes.
    private val btAdapterStateReceiver =
        object : android.content.BroadcastReceiver() {
            override fun onReceive(
                c: Context,
                intent: Intent,
            ) {
                if (intent.action != android.bluetooth.BluetoothAdapter.ACTION_STATE_CHANGED) return
                val state =
                    intent.getIntExtra(
                        android.bluetooth.BluetoothAdapter.EXTRA_STATE,
                        android.bluetooth.BluetoothAdapter.ERROR,
                    )
                if (state != android.bluetooth.BluetoothAdapter.STATE_TURNING_OFF &&
                    state != android.bluetooth.BluetoothAdapter.STATE_OFF
                ) {
                    return
                }
                Log.w(
                    TAG,
                    "BluetoothAdapter state=$state — cascading BT-sourced PTT release",
                )
                try {
                    plant?.releaseAllBtSourcedPtt()
                } catch (t: Throwable) {
                    Log.w(TAG, "releaseAllBtSourcedPtt threw", t)
                }
            }
        }

    private val activeCallReceiver =
        object : android.content.BroadcastReceiver() {
            override fun onReceive(
                c: Context,
                intent: Intent,
            ) {
                when (intent.action) {
                    CallStyleNotifier.ACTION_HANGUP_REQUESTED -> {
                        Log.i(TAG, "active-call HANGUP requested")
                        // Standard end-call path. Tearing down the
                        // Telecom Connection drives onDisconnect →
                        // ActiveCallRegistry.fireExternalTeardown which
                        // unwinds the voice plant + Mumble channel.
                        com.atakmap.android.xv.telecom.ActiveCallRegistry
                            .activeConnection()
                            ?.teardownLocal()
                        // Dismiss the active CallStyle notification —
                        // the user already tapped Hang Up, the call is
                        // ending; redundant on the natural teardown
                        // path (externalTeardownListener also calls
                        // dismissAll) but harmless and prevents the
                        // shade entry from lingering during the
                        // 5-second Telecom debounce window.
                        callStyleNotifier.dismissAll()
                    }
                    CallStyleNotifier.ACTION_SET_MUTED -> {
                        val on =
                            intent.getBooleanExtra(
                                CallStyleNotifier.EXTRA_MUTED,
                                false,
                            )
                        Log.i(TAG, "active-call mute=$on")
                        try {
                            @Suppress("DEPRECATION")
                            voiceAudioManager.isMicrophoneMute = on
                        } catch (t: Throwable) {
                            Log.w(TAG, "mute toggle threw", t)
                        }
                    }
                    CallStyleNotifier.ACTION_SET_SPEAKER -> {
                        val on =
                            intent.getBooleanExtra(
                                CallStyleNotifier.EXTRA_SPEAKER_ON,
                                true,
                            )
                        Log.i(TAG, "active-call speakerOn=$on")
                        try {
                            @Suppress("DEPRECATION")
                            voiceAudioManager.isSpeakerphoneOn = on
                        } catch (t: Throwable) {
                            Log.w(TAG, "speaker toggle threw", t)
                        }
                    }
                    CallStyleNotifier.ACTION_SET_AUDIO_ROUTE -> {
                        val route =
                            intent.getIntExtra(
                                CallStyleNotifier.EXTRA_AUDIO_ROUTE,
                                android.telecom.CallAudioState.ROUTE_EARPIECE,
                            )
                        Log.i(TAG, "active-call route=$route")
                        // Three-pronged route enforcement (one wasn't
                        // enough — the first two-prong attempt left audio
                        // on the speaker even after the operator tapped
                        // Earpiece because VoicePlant's own
                        // `applyNoScoCommDeviceForCurrentRoute()` kept
                        // re-asserting `setCommunicationDevice(SPEAKER)`
                        // from its router state):
                        //
                        //   1. Tell Telecom via Connection.setAudioRoute()
                        //      so the system call-audio state reflects
                        //      the operator's pick (BT arbitration,
                        //      system call-bar indicator).
                        //
                        //   2. Update VoicePlant's outputRoute preference
                        //      via `plant.setOutputRoute()` — this is
                        //      what `applyNoScoCommDeviceForCurrentRoute`
                        //      reads to pick BUILTIN_EARPIECE vs
                        //      BUILTIN_SPEAKER. Without this update,
                        //      the plant would keep re-routing to
                        //      whatever its router last had (default
                        //      SPEAKER) on every TX/RX engagement.
                        //
                        //   3. Drive AudioManager.isSpeakerphoneOn as
                        //      belt-and-suspenders for the pre-S
                        //      fallback path.
                        try {
                            val conn =
                                com.atakmap.android.xv.telecom.ActiveCallRegistry
                                    .activeConnection()
                            if (conn != null) {
                                @Suppress("DEPRECATION")
                                conn.setAudioRoute(route)
                            } else {
                                Log.w(TAG, "set-route: no active connection — ignoring")
                            }
                            val plantRoute =
                                when (route) {
                                    android.telecom.CallAudioState.ROUTE_EARPIECE ->
                                        com.atakmap.android.xv.audio.OutputRoute.EARPIECE
                                    else ->
                                        com.atakmap.android.xv.audio.OutputRoute.SPEAKER
                                }
                            plant?.setOutputRoute(plantRoute)
                            val wantSpeaker =
                                route == android.telecom.CallAudioState.ROUTE_SPEAKER
                            @Suppress("DEPRECATION")
                            voiceAudioManager.isSpeakerphoneOn = wantSpeaker
                            Log.i(
                                TAG,
                                "set-route: plant=$plantRoute speakerphone=$wantSpeaker (route=$route)",
                            )
                        } catch (t: Throwable) {
                            Log.w(TAG, "set-route threw", t)
                        }
                    }
                }
            }
        }

    /** Extract the peer callsign from a Telecom call tag. Group-channel
     *  tags ("REACT", "Bravo", etc.) return null; private-call tags
     *  ("→ alice", "← alice") return "alice". */
    private fun peerCallsignFromTag(tag: String): String? {
        val outgoing = tag.removePrefix("→ ").let { if (it != tag) it else null }
        val incoming = tag.removePrefix("← ").let { if (it != tag) it else null }
        return outgoing ?: incoming
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        Log.i(TAG, "onStartCommand action=${intent?.action} flags=$flags startId=$startId")
        startForegroundIfNeeded()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder {
        Log.i(TAG, "onBind action=${intent?.action}")
        // A binder is here — cancel any pending orphan-grace self-stop.
        // Covers the cold-start case where onBind is the first event
        // after onCreate; harmless when no timer was scheduled.
        telecomHandler.removeCallbacks(orphanSelfStopRunnable)
        // Make sure we're in the foreground BEFORE the plugin starts
        // exercising the binder. AudioRecord allocations made before
        // foreground promotion would still be denied.
        startForegroundIfNeeded()
        return binder
    }

    override fun onUnbind(intent: Intent?): Boolean {
        // All clients (currently just the plugin in ATAK's process) have
        // gone away. Don't tear down immediately — Android frequently
        // kills + restarts process pairs under memory pressure, and a
        // hot rebind within seconds is normal. Schedule an orphan-grace
        // self-stop and let onRebind cancel it if the plugin comes back.
        //
        // The graceful close path (XvMapComponent.onDestroyImpl →
        // voiceClient.stop → service teardown + stopService) runs ahead
        // of onUnbind in normal exits and already shuts the service
        // down, so this timer only fires when ATAK died ungracefully
        // (force-stop, OOM-killer, crash) — exactly the case where the
        // service is otherwise orphaned with an open mic.
        Log.w(TAG, "onUnbind action=${intent?.action} — scheduling orphan-grace self-stop in ${ORPHAN_GRACE_MS}ms")
        telecomHandler.removeCallbacks(orphanSelfStopRunnable)
        telecomHandler.postDelayed(orphanSelfStopRunnable, ORPHAN_GRACE_MS)
        // Return true so onRebind fires on the next bind instead of
        // onBind (lets us distinguish reconnect from cold-start in the
        // logs and short-circuit re-init work).
        return true
    }

    override fun onRebind(intent: Intent?) {
        super.onRebind(intent)
        Log.i(TAG, "onRebind action=${intent?.action} — plugin reattached, cancelling orphan-grace self-stop")
        telecomHandler.removeCallbacks(orphanSelfStopRunnable)
    }

    // Fires ORPHAN_GRACE_MS after the last client unbinds with no
    // intervening rebind. At that point the plugin is well and truly
    // gone (not just bouncing through a process restart) and the
    // service has no useful work left — there's nobody to fan voice
    // events to and nobody to drive PTT. stopSelf() invokes our
    // onDestroy chain which runs the existing teardown (releases
    // AudioRecord, AudioTrack, SCO, Telecom Connection, mutes mic
    // reset, etc.) so the mic is released cleanly instead of staying
    // open forever in the orphaned foreground service.
    private val orphanSelfStopRunnable =
        Runnable {
            Log.w(TAG, "orphan-grace expired — no plugin rebind, stopping XvVoiceService")
            try {
                stopSelf()
            } catch (t: Throwable) {
                Log.w(TAG, "stopSelf threw", t)
            }
        }

    override fun onDestroy() {
        Log.i(TAG, "onDestroy — releasing voice plant")
        releaseVolumeKeyCapture()
        telecomHandler.removeCallbacks(pendingEndRunnable)
        // Cancel the watchdog BEFORE removing externalTeardownListener
        // — once the listener is removed, the teardownLocal below
        // can't reach our cancel-via-listener path, so do it directly.
        telecomHandler.removeCallbacks(callIdleWatchdogRunnable)
        // Cancel the orphan-grace self-stop too — onDestroy reaches us
        // either via stopSelf (the orphan path that scheduled the
        // runnable; the runnable's own stopSelf is what got us here)
        // or via the normal teardown chain (orphan timer was never
        // scheduled, removeCallbacks is a no-op). Either way, no stale
        // runnable should outlive the service.
        telecomHandler.removeCallbacks(orphanSelfStopRunnable)
        com.atakmap.android.xv.telecom.ActiveCallRegistry
            .removeExternalTeardownListener(externalTeardownListener)
        com.atakmap.android.xv.telecom.ActiveCallRegistry
            .removeHoldStateListener(holdStateListener)
        com.atakmap.android.xv.telecom.ActiveCallRegistry
            .removeIncomingDecisionListener(incomingDecisionListener)
        try {
            unregisterReceiver(incomingDecisionReceiver)
        } catch (_: Throwable) {
            // already unregistered, or never registered (e.g. early
            // crash in onCreate) — nothing actionable.
        }
        try {
            unregisterReceiver(activeCallReceiver)
        } catch (_: Throwable) {
            // C2 fix: prior code only unregistered incomingDecisionReceiver;
            // activeCallReceiver leaked, fired IntentReceiverLeaked WARN
            // every service teardown and crashed on strict OEM builds.
        }
        try {
            unregisterReceiver(btAdapterStateReceiver)
        } catch (_: Throwable) {
            // Same rationale as activeCallReceiver above — always try,
            // swallow "not registered" so an early-crash teardown path
            // doesn't add its own noise.
        }
        com.atakmap.android.xv.telecom.ActiveCallRegistry.serviceContext = null
        // Make sure the CallStyle ring + active-call notifications
        // aren't left dangling after the service shuts down.
        callStyleNotifier.dismissAll()
        // Force-end any active call right now (no debounce on shutdown)
        com.atakmap.android.xv.telecom.ActiveCallRegistry
            .activeConnection()
            ?.teardownLocal()
        releaseVoiceFocus()
        try {
            plant?.shutdown()
        } catch (t: Throwable) {
            Log.w(TAG, "plant.shutdown threw", t)
        }
        plant = null
        listeners.kill()
        super.onDestroy()
    }

    // Fired when XvConnection.onDisconnect/onAbort/onReject runs — i.e.
    // Telecom (or another VoIP app via preempt) tore down our call from
    // the OUTSIDE. We're not the originator of the teardown, so the
    // pending-end-debounce path won't fire; we have to unwind the voice
    // plant ourselves: cancel any pending debounced end, drop in-flight
    // TX (latched mode would otherwise stay engaged with no place to
    // send audio), and abandon the voice focus we held. Plant lifecycle
    // (SCO release, AudioPlayback teardown) follows naturally from
    // pttDispatcher.release() and the focus drop.
    private val externalTeardownListener: () -> Unit = {
        Log.w(TAG, "Telecom externally tore down our call — unwinding voice plant")
        telecomHandler.removeCallbacks(pendingEndRunnable)
        // Call really ended — kill the idle watchdog too. Covers all
        // teardown paths (pendingEndRunnable, AIDL endChannelCall,
        // XvConnection.onDisconnect/onAbort/onReject, watchdog itself
        // firing teardownLocal) since they all converge on
        // teardownLocal → fireExternalTeardown → this listener.
        telecomHandler.removeCallbacks(callIdleWatchdogRunnable)
        try {
            plant?.releaseActiveTx()
        } catch (t: Throwable) {
            Log.w(TAG, "plant.releaseActiveTx threw", t)
        }
        releaseVoiceFocus()
        disengagePrivateCallAudioMode()
        // Drop the FOREGROUND_SERVICE_TYPE_PHONE_CALL we acquired at
        // call placement back to mic|connectedDevice. Holding the
        // phoneCall type past the call's actual lifetime risks the
        // system revoking permission for type mismatch on the next
        // long-running mic operation.
        demoteFromPhoneCallForeground()
        // M3 fix: peer-side CANCEL_CALL during ringing tears down the
        // Telecom connection here — both the incoming-ring CallStyle
        // notification (posted via placeIncomingTelecomCallInternal)
        // and the active-call CallStyle notification need to be
        // cleared. dismissAll is idempotent so the common-case where
        // only one of the two is up is fine.
        try {
            callStyleNotifier.dismissAll()
        } catch (t: Throwable) {
            Log.w(TAG, "callStyleNotifier.dismissAll on external teardown threw", t)
        }
        // Reset the once-only decision guard so the next incoming call
        // can be answered/declined cleanly.
        incomingDecisionConsumed.set(false)
        // Tell the plugin to clear MumbleTransport's private-call
        // state (clears the VoiceTarget, respins VS2) AND, if we
        // were the caller, send CANCEL_CALL to the peer. The plugin
        // owns MumbleTransport so it has to do this; the service just
        // signals "Telecom call is over" and lets the plugin reconcile.
        fanOut { it.onPrivateCallEnded() }
    }

    // C4 fix: once-only guard for the incoming-call decision pipeline.
    // The same answer/decline can arrive from THREE surfaces in any
    // order: (1) Telecom system UI tap → XvConnection.onAnswer/onReject
    // → broadcasts ACTION_ANSWER/DECLINE; (2) the CallStyle
    // notification's Answer / Decline button → broadcasts the same;
    // (3) AINA Voice Responder MFB tap → broadcasts the same. All three
    // converge on incomingDecisionReceiver below. Without this guard,
    // two surfaces firing within the same few-ms window double-invoke
    // fireIncomingCallAnswered → plugin's joinChannelForSlot fires
    // twice → log noise + potential UI flicker. Reset on next
    // incoming call (placeIncomingTelecomCallInternal).
    private val incomingDecisionConsumed =
        java.util.concurrent.atomic
            .AtomicBoolean(false)

    // Saved state from BEFORE we engaged private-call audio mode, so
    // we can restore on call end. Null when no private call is active.
    private var preCallLatchedMode: Boolean? = null
    private var preCallSpeakerphone: Boolean? = null
    private var preCallLatchedTimeoutSec: Int? = null

    /**
     * Engage call-style audio for the duration of a private call:
     *   - latched (PTT-up doesn't stop TX)
     *   - speakerphone on
     *   - latched timeout disabled (calls shouldn't auto-cut at 3 min)
     *   - external PTT gated off (phone behavior — only Hang Up matters)
     *
     * Does NOT engage the mic — call sites pass [autoEngageMic=true]
     * for incoming-answer (we accepted, mic should open immediately)
     * and [autoEngageMic=false] for outgoing (mic stays cold until
     * the callee picks up; engagePrivateCallMic fires on accept).
     * That matches phone UX — calling out doesn't keep your mic hot
     * during the ringing phase.
     *
     * All saved state restored on call end. Idempotent.
     */
    private fun engagePrivateCallAudioMode(autoEngageMic: Boolean) {
        if (preCallLatchedMode != null) return // already engaged
        val p = plant
        if (p == null) {
            Log.w(TAG, "engagePrivateCallAudioMode: no plant — skipping")
            return
        }
        preCallLatchedMode = p.latchedMode()
        preCallLatchedTimeoutSec = p.latchedTimeoutSec()
        preCallSpeakerphone =
            try {
                @Suppress("DEPRECATION")
                voiceAudioManager.isSpeakerphoneOn
            } catch (_: Throwable) {
                false
            }
        Log.i(
            TAG,
            "engagePrivateCallAudioMode: saving latched=$preCallLatchedMode " +
                "latchedTimeoutSec=$preCallLatchedTimeoutSec " +
                "speaker=$preCallSpeakerphone, applying latched=true timeout=0 speaker=true + auto-pttDown",
        )
        try {
            p.setLatchedMode(true)
        } catch (t: Throwable) {
            Log.w(TAG, "setLatchedMode(true) threw", t)
        }
        // Disable the latched timeout — phone calls should never
        // auto-cut at the 3-minute latched-timeout boundary. 0 = off
        // per VoicePlant.setLatchedTimeoutSec semantics.
        try {
            p.setLatchedTimeoutSec(0)
        } catch (t: Throwable) {
            Log.w(TAG, "setLatchedTimeoutSec(0) threw", t)
        }
        try {
            @Suppress("DEPRECATION")
            voiceAudioManager.isSpeakerphoneOn = true
        } catch (t: Throwable) {
            Log.w(TAG, "isSpeakerphoneOn=true threw", t)
        }
        // Mark the plant as "in a private call" — external PTT events
        // are silently dropped from this point until call end. Phone
        // behavior, not radio: the operator's button presses must not
        // disrupt the always-open mic.
        try {
            p.setPrivateCallActive(true)
        } catch (t: Throwable) {
            Log.w(TAG, "setPrivateCallActive(true) threw", t)
        }
        // Pre-warm the playback AudioTrack so the first inbound peer
        // frame doesn't pay the 50-150 ms hardware startup latency.
        // The track sits idle until playPcm is called; allocating it
        // up-front saves the operator perceptible "first syllable lag."
        try {
            p.warmupCallPlayback()
        } catch (t: Throwable) {
            Log.w(TAG, "warmupCallPlayback threw", t)
        }
        // Mic engagement is conditional. Incoming-answer side: open
        // immediately because we (operator) just accepted the call.
        // Outgoing side: stay cold until engagePrivateCallMic() fires
        // (driven by the callee actually joining the temp channel).
        if (autoEngageMic) {
            engagePrivateCallMicInternal()
        } else {
            Log.i(TAG, "engagePrivateCallAudioMode: deferring mic — waiting for callee accept")
        }
    }

    /**
     * Open the mic for an active private call. For outgoing calls,
     * wait until the callee picks up before invoking — the transport
     * fires onPeerAcceptedCall when the callee joins the temp
     * channel and the plugin routes that here. Idempotent — calling
     * twice is a no-op (the underlying pttDispatcher.down handles
     * already-active TX).
     */
    fun engagePrivateCallMic() {
        // Public method that AIDL exposes. Just delegates to the
        // internal helper which uses the For-Private-Call entry point
        // (bypasses the privateCallActive gate that suppresses normal
        // PTT presses during a call).
        engagePrivateCallMicInternal()
    }

    private fun engagePrivateCallMicInternal() {
        val p =
            plant ?: run {
                Log.w(TAG, "engagePrivateCallMic: no plant — skipping")
                return
            }
        try {
            p.pttDownForPrivateCall(0)
            Log.i(TAG, "engagePrivateCallMic: pttDown(0) fired — mic now hot")
        } catch (t: Throwable) {
            Log.w(TAG, "pttDownForPrivateCall threw", t)
        }
    }

    /** Restore pre-call latched + speakerphone state + force-stop TX.
     *  No-op if engage was never called for this call. */
    private fun disengagePrivateCallAudioMode() {
        val savedLatched = preCallLatchedMode
        val savedTimeout = preCallLatchedTimeoutSec
        val savedSpeaker = preCallSpeakerphone
        preCallLatchedMode = null
        preCallLatchedTimeoutSec = null
        preCallSpeakerphone = null
        if (savedLatched == null) return
        Log.i(
            TAG,
            "disengagePrivateCallAudioMode: restoring latched=$savedLatched " +
                "timeoutSec=$savedTimeout speaker=$savedSpeaker + force-release TX",
        )
        // Stop the auto-engaged TX BEFORE flipping latched off (otherwise
        // the now-unlatched TX would keep running until the next pttUp,
        // which isn't coming — there was no operator press to release).
        try {
            plant?.releaseActiveTx()
        } catch (t: Throwable) {
            Log.w(TAG, "releaseActiveTx threw", t)
        }
        // Clear the in-call gate so external PTT works normally again.
        try {
            plant?.setPrivateCallActive(false)
        } catch (t: Throwable) {
            Log.w(TAG, "setPrivateCallActive(false) threw", t)
        }
        try {
            plant?.setLatchedMode(savedLatched)
        } catch (t: Throwable) {
            Log.w(TAG, "restore setLatchedMode threw", t)
        }
        if (savedTimeout != null) {
            try {
                plant?.setLatchedTimeoutSec(savedTimeout)
            } catch (t: Throwable) {
                Log.w(TAG, "restore setLatchedTimeoutSec threw", t)
            }
        }
        if (savedSpeaker != null) {
            try {
                @Suppress("DEPRECATION")
                voiceAudioManager.isSpeakerphoneOn = savedSpeaker
            } catch (t: Throwable) {
                Log.w(TAG, "restore isSpeakerphoneOn threw", t)
            }
        }
    }

    // Fired when Telecom moves our call between ACTIVE and HELD —
    // the canonical signal for "a cellular call (incoming or outgoing)
    // is taking over the audio path." We don't tear the Mumble session
    // down (the channel stays joined so we can resume), but we DO need
    // to drop any active TX immediately so we're not still sending
    // mic audio while the cellular call has the SCO link, and we
    // release voice focus so the cellular call's media owns it cleanly.
    //
    // On unhold we deliberately stay idle — operator presses PTT when
    // they're ready. Auto-resuming a latched TX would surprise the
    // operator if they answered a quick call and didn't intend to keep
    // talking on the channel.
    // Phase E: Telecom delivered the operator's answer/reject decision
    // for an incoming VX private call. Forward across the binder so
    // the plugin can either join the temp Mumble channel (answer) or
    // send a REJECT_CALL TextMessage (reject). The plugin owns the
    // Mumble socket — service can't reach it directly.
    private val incomingDecisionListener:
        (answered: Boolean, tempChannelId: Int, callerSession: Int) -> Unit = { answered, tempChannelId, callerSession ->
            Log.i(
                TAG,
                "incoming-call decision: answered=$answered tempChannelId=$tempChannelId " +
                    "callerSession=$callerSession — fanning out to plugin",
            )
            // The decision came from EITHER Telecom's onAnswer/onReject OR
            // from the CallStyle notification's Answer/Decline button.
            // Either way, dismiss the incoming-ring notification — the
            // call is decided. (Active-call notification, if reached,
            // will be re-posted by postActive in placeTelecomCallInternal.)
            callStyleNotifier.dismissAll()
            fanOut {
                if (answered) {
                    it.onIncomingCallAnswered(tempChannelId, callerSession)
                } else {
                    it.onIncomingCallRejected(tempChannelId, callerSession)
                }
            }
        }

    // Phase E: receives ANSWER/DECLINE taps from the system's
    // Notification.CallStyle surface (Answer / Decline buttons on the
    // OS-rendered incoming-call notification) and routes them through
    // the same ActiveCallRegistry decision path Telecom uses, so the
    // surfaces (Telecom system UI vs. CallStyle notification vs. AINA
    // MFB) converge on one plugin-side listener. Whichever surface the
    // operator used, the result is identical: leave-current/join-temp
    // on answer, send REJECT_CALL on decline.
    private val incomingDecisionReceiver =
        object : android.content.BroadcastReceiver() {
            override fun onReceive(
                context: Context,
                intent: Intent,
            ) {
                val temp =
                    intent.getIntExtra(
                        CallStyleNotifier.EXTRA_TEMP_CHANNEL_ID,
                        -1,
                    )
                val caller =
                    intent.getIntExtra(
                        CallStyleNotifier.EXTRA_CALLER_SESSION,
                        -1,
                    )
                if (temp < 0) {
                    Log.w(TAG, "incoming-decision receiver: missing tempChannelId — ignoring")
                    return
                }
                // caller can be -1 when the call signal arrived via
                // CoT (XvCallSignals) — CoT doesn't carry Mumble
                // session ids. Only require tempChannelId; downstream
                // join/reject paths work without callerSession.
                // C4 fix: same call's decision can arrive from up to three
                // surfaces (Telecom UI, our activity, AINA MFB) in quick
                // succession. Process the FIRST one and silently drop the
                // rest. The flag is reset whenever a new incoming call is
                // posted (placeIncomingTelecomCallInternal) AND on
                // external teardown.
                if (!incomingDecisionConsumed.compareAndSet(false, true)) {
                    Log.i(TAG, "incoming-decision: already consumed, dropping ${intent.action}")
                    return
                }
                val callsign =
                    intent
                        .getStringExtra(
                            CallStyleNotifier.EXTRA_CALLER_CALLSIGN,
                        ).orEmpty()
                        .ifBlank { "Unknown" }
                // Clear the RINGING gate either way — answer transitions
                // to ACTIVE (which engagePrivateCallAudioMode sets
                // privateCallActive=true for), decline tears the call
                // down entirely. From here PTT-style buttons stop being
                // call-answer triggers.
                try {
                    plant?.setCallRinging(false)
                } catch (t: Throwable) {
                    Log.w(TAG, "setCallRinging(false) threw", t)
                }
                when (intent.action) {
                    CallStyleNotifier.ACTION_ANSWER -> {
                        Log.i(TAG, "incoming-decision receiver: ANSWER tempChannelId=$temp callsign='$callsign'")
                        // Mark the existing ringing connection ACTIVE
                        // (dismisses the system ringtone) but DO NOT
                        // tear it down — the connection is what drives
                        // the system's "in call" surface and the
                        // active-call CallStyle notification we post.
                        // Earlier teardown-after-answer left no Telecom
                        // presence so the active surface never showed
                        // and the operator had no Hang Up button.
                        val conn =
                            com.atakmap.android.xv.telecom.ActiveCallRegistry
                                .activeConnection()
                        if (conn != null) {
                            conn.markRingingAnswered()
                        } else {
                            Log.w(TAG, "ANSWER: no activeConnection — ringtone may persist")
                        }
                        // Bring up the in-call surface + private-call
                        // audio mode (latched + speakerphone, AINA
                        // pinning) on the same path the outgoing PTT
                        // flow uses. Tag = "← <callsign>" so
                        // peerCallsignFromTag classifies it as a
                        // private call and posts the ongoing-call
                        // CallStyle notification with a Hang Up action
                        // instead of leaving the operator with only
                        // the system Telecom UI.
                        placeTelecomCallInternal("← $callsign")
                        com.atakmap.android.xv.telecom.ActiveCallRegistry
                            .fireIncomingCallAnswered(temp, caller)
                    }
                    CallStyleNotifier.ACTION_DECLINE -> {
                        Log.i(TAG, "incoming-decision receiver: DECLINE tempChannelId=$temp")
                        com.atakmap.android.xv.telecom.ActiveCallRegistry
                            .activeConnection()
                            ?.teardownLocal()
                        com.atakmap.android.xv.telecom.ActiveCallRegistry
                            .fireIncomingCallRejected(temp, caller)
                    }
                    else -> Log.w(TAG, "unknown incoming-decision action: ${intent.action}")
                }
            }
        }

    private val holdStateListener: (Boolean) -> Unit = { held ->
        if (held) {
            Log.w(TAG, "Telecom hold (cellular call?) — dropping active TX, abandoning focus")
            try {
                plant?.releaseActiveTx()
            } catch (t: Throwable) {
                Log.w(TAG, "plant.releaseActiveTx on hold threw", t)
            }
            releaseVoiceFocus()
        } else {
            Log.i(TAG, "Telecom unhold — voice plant idle, awaiting operator PTT")
        }
    }

    private val plantCallbacks =
        object : VoicePlant.Callbacks {
            override fun onTxOpus(
                slot: Int,
                opus: ByteArray,
            ) {
                fanOut { it.onTxOpus(slot, opus) }
            }

            override fun onTxTerminator(slot: Int) {
                fanOut { it.onTxTerminator(slot) }
            }

            override fun onPttStateChanged(
                transmitting: Boolean,
                slot: Int,
            ) {
                fanOut { it.onPttStateChanged(transmitting, slot) }
                // Audio activity → keep the call-idle watchdog at bay.
                // Fires for both TX-on and TX-off; both are legitimate
                // signs the call is in active use.
                resetCallIdleWatchdog()
            }

            override fun onAinaConnectionChanged(connected: Boolean) {
                fanOut { it.onAinaConnectionChanged(connected) }
            }

            override fun onRxActivity() {
                fanOut { it.onRxActivity() }
                // RX frame arrived — call is being used to listen.
                // Reset the call-idle watchdog so a long listen-only
                // session (operator monitoring channel) doesn't trip it.
                resetCallIdleWatchdog()
            }

            override fun onAudioStateText(text: String) {
                fanOut { it.onAudioStateText(text) }
            }

            override fun onEmergencyButton(down: Boolean) {
                fanOut { it.onEmergencyButton(down) }
            }

            override fun onAudioRouteChanged(label: String) {
                fanOut { it.onAudioRouteChanged(label) }
            }

            override fun onCaptureError(reason: String) {
                fanOut { it.onCaptureError(reason) }
            }

            override fun onPttBlockedByCellularCall(reason: String) {
                fanOut { it.onPttBlockedByCellularCall(reason) }
            }

            override fun onPlaceTelecomCall(tag: String) {
                placeTelecomCallInternal(tag)
            }

            override fun onEndTelecomCall() {
                scheduleEndTelecomCall()
            }

            override fun onCallButtonTapped() {
                // AINA Voice Responder MFB tap. Decide based on current
                // Telecom call state and reuse the existing broadcast
                // pipeline so the answer / hangup paths are identical
                // to the on-screen buttons (incoming-ring activity gets
                // dismissed, in-call activity finishes, etc.).
                val conn =
                    com.atakmap.android.xv.telecom.ActiveCallRegistry
                        .activeConnection()
                val state = conn?.state
                Log.i(TAG, "MFB tap: connection state=$state")
                when (state) {
                    android.telecom.Connection.STATE_RINGING -> {
                        val temp = conn.incomingTempChannelId ?: -1
                        val caller = conn.incomingCallerSession ?: -1
                        val callsign = conn.incomingCallerCallsign ?: ""
                        if (temp < 0 || caller < 0) {
                            Log.w(TAG, "MFB ANSWER: connection missing extras — ignoring")
                            return
                        }
                        Log.i(TAG, "MFB ANSWER: tempChannelId=$temp callsign='$callsign'")
                        sendBroadcast(
                            Intent(CallStyleNotifier.ACTION_ANSWER)
                                .apply {
                                    setPackage(packageName)
                                    putExtra(
                                        CallStyleNotifier.EXTRA_TEMP_CHANNEL_ID,
                                        temp,
                                    )
                                    putExtra(
                                        CallStyleNotifier.EXTRA_CALLER_SESSION,
                                        caller,
                                    )
                                    putExtra(
                                        CallStyleNotifier.EXTRA_CALLER_CALLSIGN,
                                        callsign,
                                    )
                                },
                        )
                    }
                    android.telecom.Connection.STATE_ACTIVE -> {
                        Log.i(TAG, "MFB HANGUP: broadcasting hangup request")
                        sendBroadcast(
                            Intent(CallStyleNotifier.ACTION_HANGUP_REQUESTED)
                                .apply { setPackage(packageName) },
                        )
                    }
                    else -> Log.i(TAG, "MFB tap with no actionable call (state=$state) — ignored")
                }
            }
        }

    // Telecom debounce. Fast PTT cycles (down → up → down within ~200 ms)
    // would place + disconnect calls faster than the system can settle,
    // and a fresh placeCall while the prior call is still in
    // DISCONNECTING state throws "Cannot place call: there is another
    // call connecting." The debounce delays the end-call by
    // TELECOM_END_DEBOUNCE_MS; if a new place arrives in that window
    // we just cancel the pending end and reuse the existing active
    // Connection (no churn). Idle for the full debounce → call ends,
    // media resumes.
    private val telecomHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val pendingEndRunnable =
        Runnable {
            Log.i(TAG, "telecom end-debounce fired — tearing down connection + releasing voice focus")
            com.atakmap.android.xv.telecom.ActiveCallRegistry
                .activeConnection()
                ?.teardownLocal()
            releaseVoiceFocus()
        }

    // Defense-in-depth watchdog for a live Telecom call. The 8s
    // pendingEndRunnable above is activity-gated in the happy path:
    // PTT-up schedules it, next PTT-down cancels it. But that chain
    // assumes every PTT-down has a matched onPttStateChanged(false)
    // that reaches scheduleEndTelecomCall. If a code path ever places
    // a call without firing a matching end (Moto 2026-05-16: setMode
    // IN_COMMUNICATION held for 3h36m with no audio activity in
    // between), the Connection stays ACTIVE forever and Telecom keeps
    // SCO bound. This watchdog bounds that worst case: any
    // CALL_IDLE_MAX_MS gap with NO TX state change and NO RX frame
    // forces a teardown.
    //
    // Activity reset hooks: plantCallbacks.onPttStateChanged and
    // plantCallbacks.onRxActivity reset it on every audio event.
    // During a real conversation (chatty PTT or continuous RX) it
    // never fires. Only fires when the call is alive but the audio
    // pipeline has been silent for the full window.
    private val callIdleWatchdogRunnable =
        Runnable {
            val conn =
                com.atakmap.android.xv.telecom.ActiveCallRegistry
                    .activeConnection()
            if (conn == null) {
                // Already torn down; nothing to do. Shouldn't happen
                // because the externalTeardownListener cancels us, but
                // belt-and-suspenders.
                return@Runnable
            }
            Log.w(
                TAG,
                "call-idle watchdog fired (${CALL_IDLE_MAX_MS}ms with no TX/RX) — " +
                    "force-tearing-down connection to release SCO + voice focus",
            )
            // Same cleanup as pendingEndRunnable. teardownLocal fires
            // externalTeardownListener which does the rest (releaseActiveTx,
            // disengagePrivateCallAudioMode, dismissNotifications,
            // demoteFromPhoneCallForeground).
            conn.teardownLocal()
            releaseVoiceFocus()
        }

    /** Reset the call-idle watchdog. Called on every TX state change
     *  and every RX frame so a live, conversing call never trips it. */
    private fun resetCallIdleWatchdog() {
        telecomHandler.removeCallbacks(callIdleWatchdogRunnable)
        if (com.atakmap.android.xv.telecom.ActiveCallRegistry
                .hasActiveCall()
        ) {
            telecomHandler.postDelayed(callIdleWatchdogRunnable, CALL_IDLE_MAX_MS)
        }
    }

    // Volume-key target session. While this MediaSession is active,
    // Android routes the operator's volume-up/down key presses to
    // STREAM_VOICE_CALL — the same stream our peer playback + TPT
    // play through. Without it, vol keys default to STREAM_MUSIC or
    // STREAM_RING when no Telecom call is active, so the operator
    // can't adjust XV's volume ahead of incoming traffic. Field
    // complaint 2026-05-19: "the volume buttons do not work as
    // expected, and need to adjust the volume, even when there is
    // no traffic."
    //
    // Why MediaSession + STATE_PLAYING (instead of dispatching key
    // events ourselves): Zello, Discord, Teams, and most VoIP apps
    // do this. It's the documented Android pattern for "I'm a voice
    // app, give me volume control even when idle." Requires no
    // accessibility service, no special permissions, survives across
    // backgrounding and lock screen.
    //
    // setPlaybackToLocal(USAGE_VOICE_COMMUNICATION) is the magic
    // that maps vol-keys to STREAM_VOICE_CALL. STATE_PLAYING tells
    // Android we're an active media source eligible to capture
    // hardware key routing. The position/speed are dummies — there's
    // no actual playback timeline associated with the session.
    @Volatile
    private var volumeMediaSession: MediaSession? = null

    private fun setupVolumeKeyCapture() {
        if (volumeMediaSession != null) return
        try {
            val session = MediaSession(this, "XvVolumeKeys")
            session.setPlaybackToLocal(
                AudioAttributes
                    .Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            // STATE_PLAYING is required for the session to be eligible
            // for volume-key routing. We don't actually play anything;
            // setActions(0) so the system doesn't try to render any
            // transport-control surface for the session.
            session.setPlaybackState(
                PlaybackState
                    .Builder()
                    .setActions(0L)
                    .setState(PlaybackState.STATE_PLAYING, 0L, 1.0f)
                    .build(),
            )
            session.isActive = true
            volumeMediaSession = session
            Log.i(TAG, "MediaSession active for vol-key → STREAM_VOICE_CALL routing")
        } catch (t: Throwable) {
            Log.w(
                TAG,
                "MediaSession setup failed — vol keys may not adjust STREAM_VOICE_CALL when idle",
                t,
            )
        }
    }

    private fun releaseVolumeKeyCapture() {
        val s = volumeMediaSession ?: return
        volumeMediaSession = null
        try {
            s.isActive = false
            s.release()
            Log.i(TAG, "MediaSession released")
        } catch (t: Throwable) {
            Log.w(TAG, "MediaSession release threw", t)
        }
    }

    // Service-level voice focus. Held for the entire Telecom call
    // lifetime (placeCall → debounced teardown). This is the focus
    // claim that pauses Spotify/Tidal/podcasts during a voice burst.
    //
    // We use AUDIOFOCUS_GAIN_TRANSIENT (not GAIN). The two differ in
    // what other focus holders receive:
    //   - GAIN dispatches AUDIOFOCUS_LOSS (-1) to others. That's
    //     PERMANENT loss — media apps (correctly) don't auto-resume
    //     when we abandon, because LOSS means "I'm taking over for
    //     good." Operator's complaint that drove this: music never
    //     came back after the call ended.
    //   - GAIN_TRANSIENT dispatches AUDIOFOCUS_LOSS_TRANSIENT (-2).
    //     Media apps pause cleanly while we hold focus, and auto-
    //     resume once we abandon. Matches the phone-call UX every
    //     other VoIP app uses (Discord, Zoom, etc.).
    //
    // Earlier per-PTT GAIN_TRANSIENT thrashed too fast (acquired and
    // released on every press) and left media apps in a perpetual
    // ducking state. The current design is GAIN_TRANSIENT held at
    // the SERVICE level for the full Telecom-call lifetime (with the
    // 5 s end-debounce smoothing rapid press cycles into one
    // continuous focus claim) — so media apps see one clean pause
    // and one clean resume.
    private val voiceAudioManager: AudioManager by lazy {
        getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }

    @Volatile
    private var voiceFocusRequest: AudioFocusRequest? = null

    private val voiceFocusListener =
        AudioManager.OnAudioFocusChangeListener { /* state already managed via Telecom */ }

    private fun acquireVoiceFocus() {
        if (voiceFocusRequest != null) return
        val request =
            AudioFocusRequest
                .Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(
                    AudioAttributes
                        .Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                ).setOnAudioFocusChangeListener(voiceFocusListener)
                .build()
        val granted =
            voiceAudioManager.requestAudioFocus(request) ==
                AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        if (granted) {
            voiceFocusRequest = request
            Log.i(TAG, "voice focus acquired (GAIN_TRANSIENT, USAGE_VOICE_COMMUNICATION) — media will pause + auto-resume")
        } else {
            Log.w(TAG, "voice focus denied — media may not pause")
        }
    }

    private fun releaseVoiceFocus() {
        val req = voiceFocusRequest ?: return
        voiceFocusRequest = null
        try {
            voiceAudioManager.abandonAudioFocusRequest(req)
            Log.i(TAG, "voice focus released")
        } catch (t: Throwable) {
            Log.w(TAG, "abandonAudioFocusRequest threw", t)
        }
    }

    private fun placeTelecomCallInternal(tag: String) {
        // If an end was scheduled, cancel it — we want to keep the
        // existing call alive instead of placing a fresh one.
        telecomHandler.removeCallbacks(pendingEndRunnable)
        // Fresh PTT-down is activity; reset the call-idle watchdog
        // so a placeCall right at the edge of the window doesn't trip
        // it before plantCallbacks can fire.
        resetCallIdleWatchdog()

        // Grab voice focus FIRST, before placeCall returns. Media
        // apps see the focus loss synchronously and start their
        // pause animation in parallel with our SCO warmup, so the
        // operator hears the TPT against silence instead of music.
        acquireVoiceFocus()

        // Phase E: private-call tag → show the system CallStyle
        // ongoing-call notification with Hang Up action. The arrow
        // prefix indicates direction:
        //   "← <name>" = INCOMING (we just accepted) → mic on now
        //   "→ <name>" = OUTGOING (waiting for callee accept) →
        //                mic stays cold until engagePrivateCallMic()
        //                fires from the transport's
        //                onPeerAcceptedCall hook.
        val peerCallsign = peerCallsignFromTag(tag)
        if (peerCallsign != null) {
            val isIncomingAnswer = tag.startsWith("← ")
            engagePrivateCallAudioMode(autoEngageMic = isIncomingAnswer)
            // BAL fix: promote the foreground service to include
            // FOREGROUND_SERVICE_TYPE_PHONE_CALL before launching the
            // in-call activity. Without this, Android 14's BAL_BLOCK
            // refuses the startActivity inside CallStyleNotifier.postActive
            // (observed 2026-05-11 Surface→Pixel call: heads-up shown but
            // no XvCallActivity, no Mute / route / Hang Up controls).
            promoteToPhoneCallForeground()
            callStyleNotifier.postActive(peerCallsign, isIncoming = isIncomingAnswer)
            scheduleActiveCallActivityLaunch(peerCallsign)
        }

        // If a Connection is already alive, just leave it in ACTIVE
        // state. No need to placeCall again — Telecom would reject
        // anyway with "another call connecting" if the prior call's
        // teardown hasn't fully settled.
        val existing =
            com.atakmap.android.xv.telecom.ActiveCallRegistry
                .activeConnection()
        if (existing != null) {
            Log.i(TAG, "placeTelecomCallInternal tag=$tag — reusing existing connection")
            try {
                existing.setActiveSession()
            } catch (t: Throwable) {
                Log.w(TAG, "setActiveSession on existing threw", t)
            }
            return
        }

        Log.i(TAG, "placeTelecomCallInternal tag=$tag")
        com.atakmap.android.xv.telecom.XvPhoneAccount
            .register(this)
        val tm =
            getSystemService(Context.TELECOM_SERVICE) as? android.telecom.TelecomManager
        if (tm == null) {
            Log.w(TAG, "TelecomManager unavailable")
            return
        }
        val uri =
            com.atakmap.android.xv.telecom.XvPhoneAccount
                .callUri(tag)
        val extras =
            com.atakmap.android.xv.telecom.XvPhoneAccount
                .placeCallExtras(this, tag)
        try {
            tm.placeCall(uri, extras)
            Log.i(TAG, "placeCall OK for tag=$tag (from PTT-down)")
        } catch (t: Throwable) {
            Log.e(TAG, "placeCall threw", t)
        }
    }

    private fun scheduleEndTelecomCall() {
        telecomHandler.removeCallbacks(pendingEndRunnable)
        telecomHandler.postDelayed(pendingEndRunnable, TELECOM_END_DEBOUNCE_MS)
    }

    /**
     * Launch XvCallActivity for an active private call, deferring until
     * the Telecom Connection lands in the registry if it hasn't yet.
     *
     * Background: Android 14+ BAL only grants the foreground-service
     * `startActivity` exemption when the service has an *active*
     * self-managed phone call attached. For outgoing calls, the
     * Connection is created by Telecom inside `tm.placeCall(...)` and
     * arrives via `XvConnectionService.onCreateOutgoingConnection`
     * *after* this method runs — so launching the activity here would
     * be denied. Hooking into `ActiveCallRegistry.register` defers the
     * launch to the moment the Connection exists and is ACTIVE, which
     * is when the BAL exemption is actually in force.
     *
     * For incoming-answer, a RINGING Connection is already in the
     * registry; we launch immediately.
     *
     * The listener is removed on first fire (one-shot) and also on
     * external teardown so a never-arrived Connection doesn't leave it
     * dangling across calls.
     */
    private fun scheduleActiveCallActivityLaunch(peerCallsign: String) {
        val existing =
            com.atakmap.android.xv.telecom.ActiveCallRegistry
                .activeConnection()
        if (existing != null) {
            callStyleNotifier.launchActiveCallActivity(peerCallsign)
            return
        }
        Log.i(TAG, "scheduleActiveCallActivityLaunch: deferring until Connection is registered")
        val listener =
            java.util.concurrent.atomic
                .AtomicReference<((com.atakmap.android.xv.telecom.XvConnection) -> Unit)?>()
        val oneShot: (com.atakmap.android.xv.telecom.XvConnection) -> Unit = { _ ->
            val self = listener.get()
            if (self != null) {
                com.atakmap.android.xv.telecom.ActiveCallRegistry
                    .removeRegistrationListener(self)
                listener.set(null)
            }
            callStyleNotifier.launchActiveCallActivity(peerCallsign)
        }
        listener.set(oneShot)
        com.atakmap.android.xv.telecom.ActiveCallRegistry
            .addRegistrationListener(oneShot)
    }

    /**
     * Phase E: register an INCOMING call with Telecom so the system
     * shows its standard ring UI (lock-screen ANSWER/DECLINE,
     * ringtone, full-screen heads-up). The temp channel id and caller
     * session ride along in extras so XvConnection can echo them
     * back via the listener fanout when the operator answers/rejects.
     *
     * No debounce here — incoming calls are operator-driven decisions,
     * not the rapid PTT cycling that motivates the outgoing-call
     * debounce path.
     */
    private fun placeIncomingTelecomCallInternal(
        callerCallsign: String,
        tempChannelId: Int,
        callerSession: Int,
    ) {
        Log.i(
            TAG,
            "placeIncomingTelecomCallInternal callerCallsign='$callerCallsign' " +
                "tempChannelId=$tempChannelId callerSession=$callerSession",
        )
        // Reset the once-only decision guard for THIS new call. Without
        // this, a fresh REQUEST_CALL after a previous one's decision
        // would be silently dropped because the previous call's flag
        // was set to true.
        incomingDecisionConsumed.set(false)
        // Mark RINGING so PTT-style buttons on Bluetooth devices route
        // to "answer the call" instead of trying to TX. Cleared in the
        // answer/decline broadcast receiver below.
        try {
            plant?.setCallRinging(true)
        } catch (t: Throwable) {
            Log.w(TAG, "setCallRinging(true) threw", t)
        }
        com.atakmap.android.xv.telecom.XvPhoneAccount
            .register(this)
        val tm = getSystemService(Context.TELECOM_SERVICE) as? android.telecom.TelecomManager
        if (tm == null) {
            Log.w(TAG, "TelecomManager unavailable")
            return
        }
        val handle =
            com.atakmap.android.xv.telecom.XvPhoneAccount
                .handle(this)
        val extras =
            com.atakmap.android.xv.telecom.XvPhoneAccount.incomingCallExtras(
                callerCallsign = callerCallsign,
                tempChannelId = tempChannelId,
                callerSession = callerSession,
            )
        try {
            tm.addNewIncomingCall(handle, extras)
            Log.i(TAG, "addNewIncomingCall OK for callerSession=$callerSession")
        } catch (t: Throwable) {
            Log.e(TAG, "addNewIncomingCall threw", t)
        }
        // Post the system Notification.CallStyle incoming-call surface.
        // On API 31+ this is the OS-rendered full-screen ring + heads-up
        // with system Answer / Decline buttons — replaces the legacy
        // XvIncomingCallActivity. The HIGH-importance xv_call_incoming
        // channel triggers the full-screen surface automatically; no
        // setFullScreenIntent (and no USE_FULL_SCREEN_INTENT permission)
        // is required.
        callStyleNotifier.postIncoming(
            callerCallsign = callerCallsign,
            tempChannelId = tempChannelId,
            callerSession = callerSession,
        )
    }

    private inline fun fanOut(crossinline call: (IXvVoiceListener) -> Unit) {
        // beginBroadcast/finishBroadcast must be paired AND
        // single-threaded: RemoteCallbackList's mBroadcastCount guard
        // throws IllegalStateException("beginBroadcast() called while
        // already in a broadcast") if a second thread enters before the
        // first finishBroadcast lands. We hit this in the wild: the
        // audio thread fires onRxActivity at frame rate while a binder
        // thread (e.g. AIDL invocation or Telecom-driven teardown) tries
        // to fanOut at the same time, both blow up, and the
        // externalTeardownListener's fanOut { onPrivateCallEnded } gets
        // lost — leaving both phones stuck in the call's temp channel.
        //
        // Serialize on `listeners` itself. The lock is re-entrant
        // (synchronized's intrinsic lock allows nested entry from the
        // same thread) so a listener callback that synchronously
        // triggers another fanOut on the same thread still works.
        synchronized(listeners) {
            val n = listeners.beginBroadcast()
            try {
                for (i in 0 until n) {
                    val l = listeners.getBroadcastItem(i)
                    try {
                        call(l)
                    } catch (_: RemoteException) {
                        // RemoteCallbackList will prune on death-recipient
                        // fire; transient RemoteException on a single call
                        // doesn't necessarily mean the binder is dead.
                    } catch (t: Throwable) {
                        Log.w(TAG, "listener threw", t)
                    }
                }
            } finally {
                listeners.finishBroadcast()
            }
        }
    }

    private fun resolveAuthorizedUids() {
        // Always allow our own UID and the system (Telecom, ActivityManager
        // helpers) — the system never makes IXvVoice calls today, but we
        // keep it on the allowlist to avoid future surprise denials.
        authorizedUids += Process.myUid()
        authorizedUids += Process.SYSTEM_UID

        // ATAK package variants. All known plugin-host packages.
        // com.atakmap.app.civ — Civ ATAK (Play Store + SDK)
        // com.atakmap.app    — military / non-Civ ATAK builds
        val pm = packageManager
        for (pkg in arrayOf("com.atakmap.app.civ", "com.atakmap.app")) {
            try {
                val uid = pm.getPackageUid(pkg, 0)
                authorizedUids += uid
                Log.i(TAG, "auth: allow $pkg uid=$uid")
            } catch (_: PackageManager.NameNotFoundException) {
                // Not installed; harmless.
            } catch (t: Throwable) {
                Log.w(TAG, "auth: getPackageUid($pkg) threw", t)
            }
        }
        Log.i(TAG, "auth: ${authorizedUids.size} UID(s) allowed")
    }

    private fun assertAuthorizedCaller() {
        val callingUid = Binder.getCallingUid()
        if (callingUid in authorizedUids) return
        val callerName =
            try {
                packageManager.getNameForUid(callingUid) ?: "uid=$callingUid"
            } catch (_: Throwable) {
                "uid=$callingUid"
            }
        Log.w(TAG, "auth: REJECT binder call from $callerName (uid=$callingUid)")
        throw SecurityException("XvVoice binder access denied for $callerName")
    }

    private fun startForegroundIfNeeded() {
        startForegroundWithTypes(includePhoneCall = false)
    }

    /**
     * Promote the foreground service to include FOREGROUND_SERVICE_TYPE_PHONE_CALL
     * for the duration of a private call. Phone-call typed foreground
     * services are exempt from Android 14+ BAL (Background Activity
     * Launch) restrictions, so our call-screen activity launch from
     * `CallStyleNotifier.postActive → startActivity(XvCallActivity)`
     * is permitted. Without this swap, BAL_BLOCK fires and the
     * operator sees only a heads-up notification with no Mute / route /
     * Hang Up controls available.
     */
    private fun promoteToPhoneCallForeground() {
        startForegroundWithTypes(includePhoneCall = true)
    }

    /** Drop back to the default microphone|connectedDevice types when
     *  the call ends. Phone-call type would otherwise persist and
     *  Android could revoke the type-mismatched permission. */
    private fun demoteFromPhoneCallForeground() {
        startForegroundWithTypes(includePhoneCall = false)
    }

    private fun startForegroundWithTypes(includePhoneCall: Boolean) {
        val notification = buildOngoingNotification()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                var types =
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                if (includePhoneCall) {
                    types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL
                }
                startForeground(NOTIFICATION_ID, notification, types)
                Log.i(TAG, "startForeground types=0x${types.toString(16)} phoneCall=$includePhoneCall")
            } else {
                @Suppress("DEPRECATION")
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "startForeground failed — background-PTT will not work", t)
        }
    }

    private fun buildOngoingNotification(): Notification =
        NotificationCompat
            .Builder(this, NotificationChannels.SERVICE)
            .setContentTitle("XV voice")
            .setContentText("Tap to open — AINA + Mumble session running")
            .setSmallIcon(com.atakmap.android.xv.R.mipmap.ic_launcher)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(buildOpenXvPendingIntent())
            .build()

    /**
     * ContentIntent for the ongoing notification: tapping the body fires
     * XvTool.SHOW_XV (the same AtakBroadcast action the toolbar button
     * uses), which XvMapComponent's showReceiver routes into
     * XvDropDownReceiver.show(). DropDownReceiver.show() bringing the
     * ATAK UI to the foreground is built-in behavior — the notification
     * doesn't need to launch ATAKActivity separately.
     *
     * Caveat: if ATAK's process has been killed entirely, no plugin is
     * loaded and the broadcast goes nowhere. In normal operation, ATAK
     * is alive whenever this service is running (the plugin is what
     * binds to this service), so the broadcast path is reliable for
     * the common case. Lock-screen taps trigger the system unlock
     * prompt first, then land on the XV panel.
     */
    private fun buildOpenXvPendingIntent(): android.app.PendingIntent {
        // Action mirrors XvTool.SHOW_XV. Inlined here so the service
        // doesn't pull in the plugin/UI module on its classpath — the
        // service is supposed to be reachable across the IPC boundary
        // without dragging plugin internals along.
        val showXv = Intent(ACTION_SHOW_XV).setPackage(packageName)
        return android.app.PendingIntent.getBroadcast(
            this,
            REQUEST_OPEN_XV,
            showXv,
            android.app.PendingIntent.FLAG_IMMUTABLE or
                android.app.PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private val binder =
        object : IXvVoice.Stub() {
            override fun getApiVersion(): Int {
                // Not gated by assertAuthorizedCaller — version probing
                // happens before any privileged call and shouldn't itself
                // require allowlisted UID. Reading an int is harmless.
                return AIDL_API_VERSION
            }

            override fun registerListener(listener: IXvVoiceListener?) {
                assertAuthorizedCaller()
                if (listener == null) return
                listeners.register(listener)
                Log.i(TAG, "registerListener — total ${listeners.registeredCallbackCount}")
            }

            override fun unregisterListener(listener: IXvVoiceListener?) {
                assertAuthorizedCaller()
                if (listener == null) return
                listeners.unregister(listener)
                Log.i(TAG, "unregisterListener — total ${listeners.registeredCallbackCount}")
            }

            override fun pttDown(slot: Int) {
                assertAuthorizedCaller()
                plant().pttDown(slot)
            }

            override fun pttUp(slot: Int) {
                assertAuthorizedCaller()
                plant().pttUp(slot)
            }

            override fun setLatchedMode(enabled: Boolean) {
                assertAuthorizedCaller()
                plant().setLatchedMode(enabled)
            }

            override fun setPttTimeoutSec(seconds: Int) {
                assertAuthorizedCaller()
                plant().setPttTimeoutSec(seconds)
            }

            override fun setLatchedTimeoutSec(seconds: Int) {
                assertAuthorizedCaller()
                plant().setLatchedTimeoutSec(seconds)
            }

            override fun setStatusTonesEnabled(enabled: Boolean) {
                assertAuthorizedCaller()
                plant().setStatusTonesEnabled(enabled)
            }

            override fun setTptTone(name: String?) {
                assertAuthorizedCaller()
                plant().setTptTone(TptTone.fromName(name))
            }

            override fun setOutputRoute(name: String?) {
                assertAuthorizedCaller()
                plant().setOutputRoute(OutputRoute.fromName(name))
            }

            override fun getAudioRouteLabel(): String {
                assertAuthorizedCaller()
                return plant?.audioRouteLabel() ?: "Auto"
            }

            override fun setOutputBtOverride(mac: String?) {
                assertAuthorizedCaller()
                plant().setOutputBtOverride(mac)
            }

            override fun connectAina(
                mac: String?,
                name: String?,
                kind: String?,
            ) {
                assertAuthorizedCaller()
                plant().connectAina(mac, name, kind)
            }

            override fun disconnectAina() {
                assertAuthorizedCaller()
                plant().disconnectAina()
            }

            override fun disconnectAinaReaderOnly() {
                assertAuthorizedCaller()
                plant().disconnectAinaReaderOnly()
            }

            override fun isAinaConnected(): Boolean {
                assertAuthorizedCaller()
                return plant().isAinaConnected()
            }

            override fun connectExternalButton(
                mac: String?,
                name: String?,
                kind: String?,
            ) {
                assertAuthorizedCaller()
                plant().connectExternalButton(mac, name, kind)
            }

            override fun disconnectExternalButton() {
                assertAuthorizedCaller()
                plant().disconnectExternalButton()
            }

            override fun isExternalButtonConnected(): Boolean {
                assertAuthorizedCaller()
                return plant().isExternalButtonConnected()
            }

            override fun setSamsungActiveKeyEnabled(enabled: Boolean) {
                assertAuthorizedCaller()
                if (enabled) {
                    plant().startSamsungActiveKey()
                } else {
                    plant().stopSamsungActiveKey()
                }
            }

            override fun isSamsungActiveKeyRunning(): Boolean {
                assertAuthorizedCaller()
                return plant().isSamsungActiveKeyRunning()
            }

            // Foreground-KeyEvent fallback edge dispatch. The plugin's
            // SamsungActiveKeyForegroundReader owns the OnKeyListener
            // attached to the MapView (the KeyEvent path only reaches
            // the top activity); it forwards each filtered edge here so
            // the service's PttDispatcher — the single source of truth
            // for TX state — sees the SAMSUNG_ACTIVE_KEY-tagged edge in
            // the correct process. Same authorization gate as the other
            // PTT paths.
            override fun notifySamsungActiveKeyEdge(isDown: Boolean) {
                assertAuthorizedCaller()
                if (isDown) {
                    plant().pttDown(0, com.atakmap.android.xv.audio.PttSource.SAMSUNG_ACTIVE_KEY)
                } else {
                    plant().pttUp(0, com.atakmap.android.xv.audio.PttSource.SAMSUNG_ACTIVE_KEY)
                }
            }

            override fun setMumbleSessionState(connectedAndInChannel: Boolean) {
                assertAuthorizedCaller()
                plant().setMumbleSessionLive(connectedAndInChannel)
            }

            override fun notifyTransportLost() {
                assertAuthorizedCaller()
                plant().handleTransportLost()
            }

            override fun setCanSpeakOnSlot(
                slot: Int,
                canSpeak: Boolean,
            ) {
                assertAuthorizedCaller()
                plant().setCanSpeakOnSlot(slot, canSpeak)
            }

            override fun notifyChannelMoved(slot: Int) {
                assertAuthorizedCaller()
                plant().notifyChannelMoved(slot)
            }

            override fun onRxOpus(
                slot: Int,
                opus: ByteArray?,
                speakerName: String?,
            ) {
                assertAuthorizedCaller()
                if (opus == null) return
                plant().onRxOpus(slot, opus, speakerName)
            }

            override fun playTptPreview() {
                assertAuthorizedCaller()
                plant().playTptPreview()
            }

            override fun startChannelCall(channelTag: String?) {
                assertAuthorizedCaller()
                placeTelecomCallInternal(channelTag ?: "channel")
            }

            override fun endChannelCall() {
                assertAuthorizedCaller()
                Log.i(TAG, "endChannelCall via AIDL")
                telecomHandler.removeCallbacks(pendingEndRunnable)
                com.atakmap.android.xv.telecom.ActiveCallRegistry
                    .activeConnection()
                    ?.teardownLocal()
                releaseVoiceFocus()
            }

            override fun setHotMicEnabled(enabled: Boolean) {
                assertAuthorizedCaller()
                plant().setHotMicEnabled(enabled)
            }

            override fun notifyIncomingCall(
                callerCallsign: String?,
                tempChannelId: Int,
                callerSession: Int,
            ) {
                assertAuthorizedCaller()
                placeIncomingTelecomCallInternal(
                    callerCallsign = callerCallsign ?: "Unknown",
                    tempChannelId = tempChannelId,
                    callerSession = callerSession,
                )
            }

            override fun engagePrivateCallMic() {
                assertAuthorizedCaller()
                this@XvVoiceService.engagePrivateCallMic()
            }

            override fun teardown() {
                assertAuthorizedCaller()
                plant?.shutdown()
                plant = null
            }
        }

    companion object {
        private const val TAG = "XvVoiceSvc"

        // AIDL contract version. Bump on every breaking schema change
        // (method removed, signature changed, semantics changed).
        // Plugin reads this via IXvVoice.getApiVersion() at bind time
        // and refuses to issue calls if it sees a version it doesn't
        // recognize. Adding a new method to the end of the AIDL is
        // backwards-compatible and does not require a bump (older
        // plugins simply won't call the new method).
        // v1 → v2: Phase E added notifyIncomingCall + onIncomingCallAnswered
        // / onIncomingCallRejected listener callbacks for VX-style direct
        // calls. Stale plugins built against v1 still work for everything
        // except direct-call origination/reception.
        // v2 → v3: added engagePrivateCallMic() — outgoing calls
        // defer mic engagement until the callee actually accepts.
        // Older plugins built against v2 still work for everything
        // except that one hook (their outgoing-call mic stays hot
        // from the moment of dial — same behavior as before, no
        // breakage).
        // v3 → v4: added disconnectAinaReaderOnly() — needed by the
        // primary AINA button-kind change path so the operator can
        // flip button-protocol on an already-connected speakermic
        // without churning the audio route. Stale plugins built
        // against v3 fall back to full disconnectAina + reconnect on
        // kind changes (audio route hint clears momentarily) — no
        // functional breakage.
        // Additive-since-v4 (no version bump): notifySamsungActiveKeyEdge
        // for the foreground-KeyEvent fallback on Tab Active5 firmware
        // that doesn't emit HARD_KEY_REPORT. Older plugins built
        // against v4 simply won't call it — the broadcast path is
        // still their only Samsung Active Key route.
        private const val AIDL_API_VERSION = 4

        // Channel ids for the incoming-ring + active-call CallStyle
        // notifications live in NotificationChannels.kt. The service's
        // own foreground status notification uses NotificationChannels
        // .SERVICE — the legacy "xv_voice_session" channel was
        // retired in the CallStyle migration; users who had it
        // previously will continue to see the new SERVICE channel
        // after the next service start (Android's channel registry
        // is per-channel-id, so adding the new one is sufficient).
        private const val NOTIFICATION_ID = 4711

        // Mirrors com.atakmap.android.xv.plugin.XvTool.SHOW_XV — the
        // AtakBroadcast action that XvMapComponent registers for and
        // routes into XvDropDownReceiver.show(). Inlined to keep the
        // service classpath independent of the plugin/UI module.
        private const val ACTION_SHOW_XV = "com.atakmap.android.xv.SHOW_XV"

        // requestCode for the ongoing-notification ContentIntent. Any
        // stable per-service-instance int works; the constant just
        // makes the PendingIntent.update flag deterministic.
        private const val REQUEST_OPEN_XV = 2001

        // Holds the Telecom call ACTIVE for 8s after PTT-up so media
        // (Tidal, Spotify) stays paused for the full quiet window
        // before resuming — matches the SCO_HOT window on the RX side
        // (AudioPlayback.scoHoldMs) and TxController.SCO_COOL_DOWN_MS.
        // Operator's expectation: "music stays paused until I'm done
        // talking, then quietly resumes after a few seconds." Also
        // absorbs rapid PTT cycles (Telecom needs 200-500ms to settle
        // a disconnect, so the long debounce subsumes the rapid-press
        // protection too).
        private const val TELECOM_END_DEBOUNCE_MS: Long = 8_000L

        // Hard ceiling on Telecom-call lifetime in the absence of any
        // audio activity. Every TX state change and every RX frame
        // resets this; only fires when the call is alive but the audio
        // pipeline has been silent for the full window. Set well above
        // the longest natural pause in a real radio conversation
        // (60-90s of think-time, between-speaker silence) so the
        // watchdog only bites on actual leaks. Field-observed leak
        // 2026-05-16 (Moto): 3h36m of MODE_IN_COMMUNICATION + voice
        // focus held with zero audio activity until an incoming phone
        // call forced telecom to break the lock; with this watchdog
        // the same scenario tears down 2 minutes after the last frame.
        private const val CALL_IDLE_MAX_MS: Long = 120_000L

        // Grace window between onUnbind (plugin client departed) and
        // self-stop. Android frequently kills and immediately restarts
        // process pairs under memory pressure — the plugin can come
        // back in 1-3 s through onRebind. 30 s is well past that
        // window while still short enough that a genuinely-killed ATAK
        // (force-stop, OOM, crash) doesn't leave the foreground
        // microphone service running open-mic for minutes/hours.
        // onBind/onRebind cancels the timer; onDestroy also cancels
        // (the timer's own stopSelf is what got us there).
        private const val ORPHAN_GRACE_MS: Long = 30_000L
    }
}
