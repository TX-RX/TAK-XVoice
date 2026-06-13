package com.atakmap.android.xv.telecom

import android.telecom.CallAudioState
import android.telecom.Connection
import android.telecom.DisconnectCause
import android.util.Log

// One XvConnection represents one active Mumble channel session — it's
// the Telecom-side handle that lets the OS treat XV like a phone call
// for routing/focus purposes while XV continues to run its own audio
// data path under the hood.
//
// The Connection's "audio" is conceptual: Telecom knows there's a
// voice call active, so it manages BT route, audio focus, and media
// pause behavior accordingly. XV's actual audio capture/playback
// (AudioCapture, AudioPlayback, Opus encode/decode, Mumble TCP) runs
// in parallel — Telecom isn't on the data path.
//
// onCallAudioStateChanged tells us which output route the system
// picked (earpiece / speaker / Bluetooth / wired). XV uses that to
// drive its capture/playback device selection (replacing the manual
// BtAudioPolicy.classify() dance).
class XvConnection(
    /** Stable tag describing the channel — used in logs and in the
     *  call's caller-id bundle. Public so XvConnectionService can
     *  distinguish "voice" (group-channel) connections from "→ peer"
     *  / "← peer" private-call connections when deciding whether to
     *  tear down a prior connection on incoming ring. */
    val tag: String,
) : Connection() {
    // Phase E: when this connection backs an incoming VX private call,
    // the temp-channel id, caller session, and caller callsign ride
    // here so onAnswer / onReject (and the AINA Voice Responder MFB
    // tap path) can echo them back to the plugin via the registry.
    // Null for outgoing calls and for plain group-channel calls.
    @Volatile
    var incomingTempChannelId: Int? = null
        private set

    @Volatile
    var incomingCallerSession: Int? = null
        private set

    @Volatile
    var incomingCallerCallsign: String? = null
        private set

    /**
     * Called by [XvConnectionService.onCreateIncomingConnection] right
     * after the [XvConnection] is constructed and before [setRinging].
     * Records the VX private-call context so the answer/reject paths
     * can route the operator's decision back to the plugin.
     */
    fun setIncomingCallContext(
        tempChannelId: Int,
        callerSession: Int,
        callerCallsign: String? = null,
    ) {
        incomingTempChannelId = tempChannelId
        incomingCallerSession = callerSession
        incomingCallerCallsign = callerCallsign
    }

    init {
        // CAPABILITY_HOLD: required for self-managed calls so other
        // VoIP apps know they can preempt us if they need exclusive
        // audio. We don't actually implement local hold — pressing PTT
        // just gates encode/transmit — but the capability flag must be
        // present for Telecom to treat us as well-behaved.
        connectionCapabilities =
            connectionCapabilities or
            CAPABILITY_HOLD or
            CAPABILITY_SUPPORT_HOLD or
            CAPABILITY_MUTE
        // Self-managed calls MUST set audio mode VoIP. Without this
        // Telecom routes through the cellular voice path which fails
        // for non-PSTN audio.
        setAudioModeIsVoip(true)
        // Show "XV: <channel>" as the caller-id wherever Telecom
        // surfaces it (lock screen, system notification panel, watch).
        setCallerDisplayName(tag, android.telecom.TelecomManager.PRESENTATION_ALLOWED)
    }

    /** Move the Telecom call to ACTIVE. Call after the Mumble channel
     *  join completes so Telecom starts arbitrating audio focus and
     *  enforcing call-class background privileges. */
    fun setActiveSession() {
        Log.i(TAG, "[$tag] → ACTIVE")
        setActive()
    }

    /** Transition out of RINGING into ACTIVE without firing the answer
     *  fanout. Used by the custom XvIncomingCallActivity ANSWER path:
     *  Telecom needs the ringing→active transition to dismiss the
     *  system ringtone (RINGING→DISCONNECTED jumps don't), but the
     *  channel-join path will create a fresh outgoing-style ACTIVE
     *  call right after, so this connection should be torn down once
     *  the ringtone stops. Caller does both. */
    fun markRingingAnswered() {
        Log.i(TAG, "[$tag] markRingingAnswered (RINGING → ACTIVE before teardown)")
        setActive()
    }

    // Default-to-speaker. Two-way-radio convention: with no BT headset
    // and no wired headset attached, voice opens on the loudspeaker so
    // the operator hears at field volume — not the tiny earpiece that
    // Android Telecom defaults self-managed VoIP calls to. Applied once,
    // on the first CallAudioState we receive after the Connection
    // becomes ACTIVE, so a user who later taps Earpiece in our UI isn't
    // yanked back to speaker on the next route-change callback. Fires
    // for ALL tags — private-call ("→ peer" / "← peer") AND group-channel
    // "voice" — because the operator-visible complaint was identical in
    // both cases: "I expected speaker, Telecom forced earpiece." Direct
    // AudioManager.setCommunicationDevice() calls from
    // XvVoicePlant.applyNoScoCommDevice can't win this fight: Telecom's
    // CallEndpointController is the source of truth for routing on a
    // self-managed Connection, and setAudioRoute() is the API that
    // moves it.
    @Volatile
    private var defaultRouteApplied: Boolean = false

    // CallAudioState was deprecated in API 34 for the new
    // CallEndpoint API, but the deprecated API works on every
    // supported version (minSdk 26) and the new one only works on
    // 34+. Sticking with CallAudioState for now.
    @Suppress("DEPRECATION")
    override fun onCallAudioStateChanged(state: CallAudioState?) {
        Log.i(TAG, "[$tag] audio route changed: $state")
        maybeApplyDefaultRoute(state)
        ActiveCallRegistry.fanOutRouteChange(state)
    }

    @Suppress("DEPRECATION")
    private fun maybeApplyDefaultRoute(state: CallAudioState?) {
        if (defaultRouteApplied) return
        if (state == null) return
        // Only override when Telecom's initial pick is the earpiece — if
        // the operator already has a BT headset or wired headset, those
        // are the right routes for a private call and we leave them
        // alone.
        if (state.route != CallAudioState.ROUTE_EARPIECE) {
            defaultRouteApplied = true
            Log.i(TAG, "[$tag] default-route: keeping non-earpiece initial route=${state.route}")
            return
        }
        val supportsSpeaker = (state.supportedRouteMask and CallAudioState.ROUTE_SPEAKER) != 0
        if (!supportsSpeaker) {
            Log.i(TAG, "[$tag] default-route: speaker not supported, leaving earpiece")
            defaultRouteApplied = true
            return
        }
        try {
            setAudioRoute(CallAudioState.ROUTE_SPEAKER)
            defaultRouteApplied = true
            Log.i(TAG, "[$tag] default-route: flipped earpiece → speaker")
        } catch (t: Throwable) {
            Log.w(TAG, "[$tag] default-route: setAudioRoute(SPEAKER) threw", t)
        }
    }

    override fun onDisconnect() {
        Log.i(TAG, "[$tag] onDisconnect (system or peer requested)")
        // Telecom (or a higher-priority VoIP app) is preempting our
        // call. Fan out to teardown listeners BEFORE destroy() so they
        // see a consistent "we still have an active call to drain"
        // state — gives XvVoiceService a chance to release SCO + audio
        // focus + drop any in-flight latched TX. Without this the voice
        // plant keeps SCO held and focus claimed even though Telecom
        // believes the call is over, leading to media that never
        // resumes and a SCO link that never releases until the next
        // explicit user action.
        ActiveCallRegistry.fireExternalTeardown()
        setDisconnected(DisconnectCause(DisconnectCause.LOCAL))
        destroy()
        ActiveCallRegistry.unregister(this)
    }

    override fun onAbort() {
        Log.i(TAG, "[$tag] onAbort")
        ActiveCallRegistry.fireExternalTeardown()
        setDisconnected(DisconnectCause(DisconnectCause.LOCAL))
        destroy()
        ActiveCallRegistry.unregister(this)
    }

    override fun onReject() {
        Log.i(TAG, "[$tag] onReject — routing through ACTION_DECLINE broadcast")
        // Route decline through the SAME broadcast pipeline that the
        // activity Decline button + AINA MFB use. Three answer/decline
        // surfaces (system Telecom UI, our XvIncomingCallActivity, AINA
        // MFB) → ONE handler → ONE place where we dismiss the activity,
        // dismiss the notification, fire the registry callback, and tear
        // down. This kills the "still ringing" bug where answering via
        // the system surface left our activity ringtone running because
        // dismissReceiver was never invoked.
        //
        // Don't setDisconnected here — the broadcast handler will call
        // teardownLocal which sets DisconnectCause.LOCAL.
        val temp = incomingTempChannelId
        val caller = incomingCallerSession
        val callsign = incomingCallerCallsign ?: ""
        if (temp != null && caller != null) {
            broadcastDecision(answered = false, tempChannelId = temp, callerSession = caller, callerCallsign = callsign)
        } else {
            // Group-channel call (no incoming-call context) — preserve
            // the legacy direct-teardown behavior.
            ActiveCallRegistry.fireExternalTeardown()
            setDisconnected(DisconnectCause(DisconnectCause.REJECTED))
            destroy()
            ActiveCallRegistry.unregister(this)
        }
    }

    override fun onAnswer() {
        Log.i(TAG, "[$tag] onAnswer — routing through ACTION_ANSWER broadcast")
        // Eagerly transition to ACTIVE so the system stops the ringtone
        // immediately (don't wait for the broadcast round-trip).
        setActive()
        // Then route through the broadcast pipeline so the same handler
        // that runs for our activity's Answer button + AINA MFB also runs
        // here: dismiss our XvIncomingCallActivity (which holds a backstop
        // ringtone), dismiss the notification, engage private-call audio
        // mode, launch XvActiveCallActivity, fire the registry callback to
        // the plugin so it joins the temp Mumble channel.
        val temp = incomingTempChannelId
        val caller = incomingCallerSession
        val callsign = incomingCallerCallsign ?: ""
        if (temp != null && caller != null) {
            broadcastDecision(answered = true, tempChannelId = temp, callerSession = caller, callerCallsign = callsign)
        }
        // Else: group-channel call without incoming context — setActive
        // alone is sufficient; nothing else to coordinate.
    }

    private fun broadcastDecision(
        answered: Boolean,
        tempChannelId: Int,
        callerSession: Int,
        callerCallsign: String,
    ) {
        val ctx = ActiveCallRegistry.serviceContext
        if (ctx == null) {
            Log.w(TAG, "broadcastDecision: serviceContext is null — falling back to direct registry fire")
            // Fallback path — service context isn't available (shouldn't
            // happen during a live call, but be defensive). Fire the
            // registry callback directly so the plugin still joins/leaves
            // the channel; the activity ringtone will time out at 20s.
            if (answered) {
                ActiveCallRegistry.fireIncomingCallAnswered(tempChannelId, callerSession)
            } else {
                ActiveCallRegistry.fireIncomingCallRejected(tempChannelId, callerSession)
                ActiveCallRegistry.fireExternalTeardown()
                setDisconnected(DisconnectCause(DisconnectCause.REJECTED))
                destroy()
                ActiveCallRegistry.unregister(this)
            }
            return
        }
        val action =
            if (answered) {
                "com.atakmap.android.xv.calling.ANSWER"
            } else {
                "com.atakmap.android.xv.calling.DECLINE"
            }
        val intent =
            android.content.Intent(action).apply {
                setPackage(ctx.packageName)
                putExtra("com.atakmap.android.xv.tempChannelId", tempChannelId)
                putExtra("com.atakmap.android.xv.callerSession", callerSession)
                putExtra("com.atakmap.android.xv.callerCallsign", callerCallsign)
            }
        ctx.sendBroadcast(intent)
    }

    override fun onHold() {
        Log.i(TAG, "[$tag] onHold — fanning out to voice plant before flipping state")
        // Telecom is putting our call on hold — almost always because
        // a cellular call grabbed audio focus. Fan out FIRST so the
        // voice plant can:
        //   - end any active TX (release SCO, drop the latched timer)
        //   - leave the Mumble session alive so we can resume on
        //     unhold without a server-side reconnect
        // Then flip the Telecom state. Order matters: setOnHold may
        // synchronously change call state in ways that race with our
        // teardown; do voice-side cleanup before yielding control.
        ActiveCallRegistry.fireHoldStateChanged(true)
        setOnHold()
    }

    override fun onUnhold() {
        Log.i(TAG, "[$tag] onUnhold — restoring Telecom state, voice plant idle until next PTT")
        setActive()
        // Notify after we're ACTIVE again so listeners see a consistent
        // state. We deliberately do NOT auto-resume TX — the operator
        // re-presses PTT when they're ready. Auto-resuming would
        // surprise the operator (the cellular call ended; they may
        // not want to keep transmitting where they left off).
        ActiveCallRegistry.fireHoldStateChanged(false)
    }

    /** Tear down from XV's side (operator left the channel, transport
     *  dropped, etc). Marks the Telecom call disconnected with cause
     *  LOCAL so the OS doesn't try to redial.
     *
     *  Also fires [ActiveCallRegistry.fireExternalTeardown] — the name
     *  is from the Telecom-perspective ("our call ended for a reason
     *  external to Telecom"), but in practice every teardown path needs
     *  the voice-plant unwind the listener performs (release latched TX,
     *  release voice focus, drop private-call audio mode, dismiss the
     *  in-call activity, notify the plugin so it can send CANCEL_CALL
     *  and clear MumbleTransport's private-call state). Without this
     *  fire, the HANG UP button on XvActiveCallActivity tears down the
     *  Telecom call but leaves both peers stuck in the temp channel
     *  with the latched mic still hot.
     *
     *  Listeners are idempotent — safe to call from any teardown path. */
    fun teardownLocal() {
        Log.i(TAG, "[$tag] teardownLocal")
        ActiveCallRegistry.fireExternalTeardown()
        setDisconnected(DisconnectCause(DisconnectCause.LOCAL))
        destroy()
        ActiveCallRegistry.unregister(this)
    }

    companion object {
        private const val TAG = "XvConn"
    }
}
