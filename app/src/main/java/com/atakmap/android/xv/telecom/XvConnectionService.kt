package com.atakmap.android.xv.telecom

import android.annotation.SuppressLint
import android.content.Intent
import android.telecom.Connection
import android.telecom.ConnectionRequest
import android.telecom.ConnectionService
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.util.Log

// Telecom's bind point for XV's self-managed VoIP — and the ONLY place
// in XV that calls TelecomManager.registerPhoneAccount / placeCall.
//
// Why here: the plugin runs inside ATAK's process (UID
// com.atakmap.app.civ), and Telecom's registerPhoneAccount /
// placeCall are gated on MANAGE_OWN_CALLS, a permission that's only
// granted to the UID that declared it in its manifest. Our XV APK
// declares MANAGE_OWN_CALLS, so OUR UID has it — but ATAK's UID
// doesn't, so calls from the plugin's code (running in ATAK's UID)
// fail with SecurityException. This Service runs in OUR APK's process
// (no android:process attribute = our default UID), so calls
// originating here have the permission.
//
// Plugin → Service contract is action-based via startService:
//   ACTION_PLACE_CALL + EXTRA_CHANNEL_TAG: register account if needed,
//                                          then placeCall
//   ACTION_END_CALL: disconnect the active Connection (if any)
//
// The Connection lives in this process for its entire lifetime; the
// plugin never touches it directly.
@SuppressLint("MissingPermission") // MANAGE_OWN_CALLS declared in our manifest, granted to our UID
class XvConnectionService : ConnectionService() {
    @Volatile
    private var phoneAccountRegistered: Boolean = false

    override fun onCreate() {
        super.onCreate()
        registerPhoneAccountIfNeeded()
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        val action = intent?.action
        Log.i(TAG, "onStartCommand action=$action")
        when (action) {
            ACTION_PLACE_CALL -> {
                val tag = intent.getStringExtra(EXTRA_CHANNEL_TAG) ?: "unknown"
                handlePlaceCall(tag)
            }
            ACTION_END_CALL -> {
                val conn = ActiveCallRegistry.activeConnection()
                if (conn == null) {
                    Log.d(TAG, "ACTION_END_CALL: no active connection (already gone)")
                } else {
                    conn.teardownLocal()
                }
            }
            ACTION_NOOP, null -> {
                // No-op start (used to keep the Service warm during a
                // call, since ConnectionService alone doesn't keep the
                // process alive without a foreground notification).
            }
            else -> Log.w(TAG, "unknown action: $action")
        }
        return START_NOT_STICKY
    }

    private fun handlePlaceCall(channelTag: String) {
        registerPhoneAccountIfNeeded()
        val tm = getSystemService(TELECOM_SERVICE) as? TelecomManager
        if (tm == null) {
            Log.w(TAG, "TelecomManager unavailable")
            return
        }
        val uri = XvPhoneAccount.callUri(channelTag)
        val extras = XvPhoneAccount.placeCallExtras(this, channelTag)
        try {
            tm.placeCall(uri, extras)
            Log.i(TAG, "placeCall fired for tag=$channelTag uri=$uri")
        } catch (t: SecurityException) {
            // MANAGE_OWN_CALLS missing OR PhoneAccount registered but
            // not enabled in system Telecom settings. Some OEM ROMs
            // require manual enable for self-managed accounts.
            Log.e(TAG, "placeCall denied — check MANAGE_OWN_CALLS + account enabled", t)
        } catch (t: Throwable) {
            Log.e(TAG, "placeCall threw", t)
        }
    }

    private fun registerPhoneAccountIfNeeded() {
        if (phoneAccountRegistered) return
        XvPhoneAccount.register(this)
        phoneAccountRegistered = true
    }

    override fun onCreateOutgoingConnection(
        connectionManagerPhoneAccount: PhoneAccountHandle?,
        request: ConnectionRequest?,
    ): Connection? {
        val tag = request?.address?.schemeSpecificPart ?: "unknown"
        Log.i(TAG, "onCreateOutgoingConnection tag=$tag account=$connectionManagerPhoneAccount")

        // Tear down any prior PRIVATE call before placing a new one.
        // A new outgoing call's tag is either "voice" (group-channel
        // placeholder for PTT) or "→ peer" (private call). When the new
        // tag is "voice", the prior is usually also "voice" — that's
        // the existing connection-reuse path. When the new tag is
        // "→ peer", a prior "voice" must NOT be torn down here for the
        // same reason as the incoming path: tearing it fires
        // fireExternalTeardown which the plugin's onPrivateCallEnded
        // interprets and sends CANCEL_CALL, which the callee receives
        // and shows on its side. Prior private calls are torn down
        // normally.
        ActiveCallRegistry.activeConnection()?.let { prior ->
            val newIsPrivate = tag.startsWith("→ ") || tag.startsWith("← ")
            val priorIsVoice = prior.tag == "voice"
            if (newIsPrivate && priorIsVoice) {
                Log.i(TAG, "skipping prior 'voice' connection teardown — new private '$tag' takes over")
            } else {
                Log.i(TAG, "tearing down prior connection '${prior.tag}' before placing new '$tag'")
                prior.teardownLocal()
            }
        }

        val conn = XvConnection(tag = tag)
        // Self-managed outgoing calls go directly to ACTIVE: there's no
        // outgoing-call ringing or remote-pickup phase since the
        // "remote party" (Mumble channel) is already there as soon as
        // we joined. The plugin places the call AFTER it's confirmed
        // the channel join, so ACTIVE on creation is correct.
        conn.setActiveSession()
        ActiveCallRegistry.register(conn)
        // H1 fix: do NOT launch XvActiveCallActivity here. The previous
        // code launched from BOTH this path AND
        // XvVoiceService.placeTelecomCallInternal — duplicate launches
        // race with REORDER_TO_FRONT, causing the duration counter to
        // restart and a visible flicker. The service-side path (which
        // handles BOTH the outgoing-call placeCall path AND the
        // answer-incoming reuse-existing-connection path) is now the
        // sole launch authority. The Android 14 BAL concern that
        // motivated launching here is moot now that the service holds a
        // foreground-call type before placing the call.
        return conn
    }

    private fun peerCallsignFromTag(tag: String): String? {
        val outgoing = tag.removePrefix("→ ").let { if (it != tag) it else null }
        val incoming = tag.removePrefix("← ").let { if (it != tag) it else null }
        return outgoing ?: incoming
    }

    // launchActiveCallActivity removed in the Notification.CallStyle
    // migration. The ongoing-call surface is now posted from
    // XvVoiceService.placeTelecomCallInternal via
    // CallStyleNotifier.postActive, which is the single launch
    // authority for the in-call UI (H1 fix made it sole authority
    // already; this drops the dead helper).

    override fun onCreateOutgoingConnectionFailed(
        connectionManagerPhoneAccount: PhoneAccountHandle?,
        request: ConnectionRequest?,
    ) {
        Log.w(
            TAG,
            "onCreateOutgoingConnectionFailed account=$connectionManagerPhoneAccount address=${request?.address}",
        )
    }

    override fun onCreateIncomingConnection(
        connectionManagerPhoneAccount: PhoneAccountHandle?,
        request: ConnectionRequest?,
    ): Connection? {
        // Phase E: VX-style private-call accept. The plugin received a
        // REQUEST_CALL TextMessage and called notifyIncomingCall on the
        // service, which in turn invoked TelecomManager.addNewIncoming-
        // Call with extras carrying the temp channel id + caller
        // session. Recover those here and stash them on the connection
        // so onAnswer / onReject can echo them back to the plugin.
        val inner =
            request?.extras?.getBundle(TelecomManager.EXTRA_INCOMING_CALL_EXTRAS)
        val callerCallsign =
            inner?.getString(XvPhoneAccount.EXTRA_CALLER_CALLSIGN) ?: "Unknown"
        val tempChannelId =
            inner?.getInt(XvPhoneAccount.EXTRA_TEMP_CHANNEL_ID, -1) ?: -1
        val callerSession =
            inner?.getInt(XvPhoneAccount.EXTRA_CALLER_SESSION, -1) ?: -1

        if (tempChannelId < 0) {
            Log.w(
                TAG,
                "onCreateIncomingConnection: missing tempChannelId in extras " +
                    "(callerCallsign='$callerCallsign') — refusing",
            )
            return Connection.createFailedConnection(
                android.telecom.DisconnectCause(android.telecom.DisconnectCause.ERROR),
            )
        }
        // callerSession is allowed to be -1: CoT-based call signaling
        // doesn't carry a Mumble session id (the caller is identified
        // by their CoT device UID via XvCallSignals.callerUid). The
        // session field is preserved on the connection for legacy
        // VX-style incoming paths, but the answer/reject flow doesn't
        // depend on it — both go through CoT signaling now.

        Log.i(
            TAG,
            "onCreateIncomingConnection callerCallsign='$callerCallsign' " +
                "tempChannelId=$tempChannelId callerSession=$callerSession",
        )

        // Tear down any prior PRIVATE call so the ring takes over
        // cleanly. Skip group-channel "voice" tags — tearing those
        // down fires fireExternalTeardown which the plugin's
        // onPrivateCallEnded listener interprets as "our private call
        // ended" and sends a spurious CANCEL_CALL to the (new) caller,
        // dismissing the outgoing call screen on the originator's side.
        // Observed 2026-05-11 Pixel→Surface call: Surface had a residual
        // "voice" connection from group PTT, REQUEST_CALL arrived, we
        // tore down the prior, plugin sent CANCEL_CALL, Pixel's call
        // screen vanished. The group-channel connection's own 5s
        // end-debounce clears it shortly; Telecom can hold a RINGING +
        // ACTIVE call in parallel until the user answers.
        ActiveCallRegistry.activeConnection()?.let { prior ->
            if (prior.tag == "voice") {
                Log.i(TAG, "skipping prior 'voice' connection teardown — will clear via its own debounce")
            } else {
                Log.i(TAG, "tearing down prior private connection '${prior.tag}' before ringing")
                prior.teardownLocal()
            }
        }

        val conn = XvConnection(tag = "← $callerCallsign")
        conn.setIncomingCallContext(
            tempChannelId = tempChannelId,
            callerSession = callerSession,
            callerCallsign = callerCallsign,
        )
        // setRinging fires the system incoming-call UI: lock-screen
        // ANSWER/DECLINE buttons, ringtone, full-screen heads-up. Telecom
        // calls back via onAnswer / onReject when the operator chooses.
        conn.setRinging()
        ActiveCallRegistry.register(conn)
        return conn
    }

    companion object {
        private const val TAG = "XvConnSvc"

        const val ACTION_PLACE_CALL = "com.atakmap.android.xv.telecom.PLACE_CALL"
        const val ACTION_END_CALL = "com.atakmap.android.xv.telecom.END_CALL"
        const val ACTION_NOOP = "com.atakmap.android.xv.telecom.NOOP"

        const val EXTRA_CHANNEL_TAG = "channel_tag"
    }
}
