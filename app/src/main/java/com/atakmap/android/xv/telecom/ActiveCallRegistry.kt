package com.atakmap.android.xv.telecom

import android.content.Context
import android.telecom.CallAudioState
import android.util.Log

// Per-process holder for the live XvConnection. The plugin and the
// XvConnectionService run in DIFFERENT processes (different UIDs), so
// this registry is service-process-only — the plugin never reads it
// directly. The plugin reaches across the process boundary via:
//   1. TelecomManager.placeCall (system API, works from any process)
//   2. Service-targeted Intents for end-call (received by
//      XvConnectionService.onStartCommand)
//
// Inside the service process the registry holds the live XvConnection
// so onStartCommand can find the right one to disconnect, and so route
// listeners (other code in the service process — e.g., audio capture
// once Phase 4 moves it here) can react to onCallAudioStateChanged.
internal object ActiveCallRegistry {
    private const val TAG = "XvCallReg"

    @Volatile
    private var activeConnection: XvConnection? = null

    // Application context, set by XvVoiceService.onCreate. Used by
    // XvConnection to broadcast ANSWER/DECLINE/HANGUP intents from the
    // Telecom-callback thread without needing a Context injected into
    // every Connection. Cleared in XvVoiceService.onDestroy.
    //
    // Non-null while the service is alive — which is the entire window
    // during which a Telecom call can exist (Telecom binds the service
    // before invoking onCreateIncoming/OutgoingConnection).
    @Volatile
    var serviceContext: Context? = null

    private val routeListeners = java.util.concurrent.CopyOnWriteArrayList<(CallAudioState?) -> Unit>()

    // Listeners notified the moment a fresh XvConnection enters the
    // registry. The signal is "the Telecom call now exists, is ACTIVE,
    // and BAL exemptions tied to a live self-managed call are now in
    // force." XvVoiceService uses this to defer launching XvCallActivity
    // for outgoing calls — startActivity from a foreground service
    // without an active Telecom call is denied by Android 14+ BAL.
    private val registrationListeners = java.util.concurrent.CopyOnWriteArrayList<(XvConnection) -> Unit>()

    // Listeners notified when Telecom (or another VoIP app via preempt)
    // tears down our call from the OUTSIDE — i.e. via XvConnection's
    // onDisconnect/onAbort/onReject rather than our own teardownLocal.
    // Used by XvVoiceService to unwind the voice plant (release SCO,
    // drop active TX, abandon focus) when we lose the call we were
    // expecting to keep alive ourselves.
    private val externalTeardownListeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()

    // Listeners notified when Telecom moves us between ACTIVE and HELD
    // — typically because a cellular call (incoming or active) is
    // taking over audio focus. true = entering hold, false = leaving.
    // Self-managed VoIP calls hand audio control back via the same
    // channel, so the listener should be idempotent (the operator may
    // re-press PTT before unhold lands).
    private val holdStateListeners = java.util.concurrent.CopyOnWriteArrayList<(Boolean) -> Unit>()

    @Synchronized
    fun register(connection: XvConnection) {
        if (activeConnection != null) {
            Log.w(TAG, "register: replacing existing activeConnection — caller should have torn down first")
        }
        activeConnection = connection
        for (l in registrationListeners) {
            try {
                l(connection)
            } catch (t: Throwable) {
                Log.w(TAG, "registration listener threw", t)
            }
        }
    }

    fun addRegistrationListener(listener: (XvConnection) -> Unit) {
        registrationListeners.add(listener)
    }

    fun removeRegistrationListener(listener: (XvConnection) -> Unit) {
        registrationListeners.remove(listener)
    }

    @Synchronized
    fun unregister(connection: XvConnection) {
        if (activeConnection === connection) {
            activeConnection = null
        }
    }

    fun activeConnection(): XvConnection? = activeConnection

    fun hasActiveCall(): Boolean = activeConnection != null

    fun fanOutRouteChange(state: CallAudioState?) {
        for (l in routeListeners) {
            try {
                l(state)
            } catch (t: Throwable) {
                Log.w(TAG, "route listener threw", t)
            }
        }
    }

    fun addRouteListener(listener: (CallAudioState?) -> Unit) {
        routeListeners.add(listener)
    }

    fun removeRouteListener(listener: (CallAudioState?) -> Unit) {
        routeListeners.remove(listener)
    }

    fun addExternalTeardownListener(listener: () -> Unit) {
        externalTeardownListeners.add(listener)
    }

    fun removeExternalTeardownListener(listener: () -> Unit) {
        externalTeardownListeners.remove(listener)
    }

    fun fireExternalTeardown() {
        for (l in externalTeardownListeners) {
            try {
                l()
            } catch (t: Throwable) {
                Log.w(TAG, "external teardown listener threw", t)
            }
        }
    }

    fun addHoldStateListener(listener: (Boolean) -> Unit) {
        holdStateListeners.add(listener)
    }

    fun removeHoldStateListener(listener: (Boolean) -> Unit) {
        holdStateListeners.remove(listener)
    }

    fun fireHoldStateChanged(held: Boolean) {
        for (l in holdStateListeners) {
            try {
                l(held)
            } catch (t: Throwable) {
                Log.w(TAG, "hold-state listener threw", t)
            }
        }
    }

    // Phase E: VX direct-call answer/reject decisions. Listener fires
    // in the service process; the service's IXvVoiceListener fanout
    // forwards across the binder to the plugin so it can leave/join
    // the right Mumble channel and send REJECT_CALL.
    private val incomingDecisionListeners =
        java.util.concurrent.CopyOnWriteArrayList<(answered: Boolean, tempChannelId: Int, callerSession: Int) -> Unit>()

    fun addIncomingDecisionListener(listener: (answered: Boolean, tempChannelId: Int, callerSession: Int) -> Unit) {
        incomingDecisionListeners.add(listener)
    }

    fun removeIncomingDecisionListener(listener: (answered: Boolean, tempChannelId: Int, callerSession: Int) -> Unit) {
        incomingDecisionListeners.remove(listener)
    }

    fun fireIncomingCallAnswered(
        tempChannelId: Int,
        callerSession: Int,
    ) {
        for (l in incomingDecisionListeners) {
            try {
                l(true, tempChannelId, callerSession)
            } catch (t: Throwable) {
                Log.w(TAG, "incoming-answer listener threw", t)
            }
        }
    }

    fun fireIncomingCallRejected(
        tempChannelId: Int,
        callerSession: Int,
    ) {
        for (l in incomingDecisionListeners) {
            try {
                l(false, tempChannelId, callerSession)
            } catch (t: Throwable) {
                Log.w(TAG, "incoming-reject listener threw", t)
            }
        }
    }
}
