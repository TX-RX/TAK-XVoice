package com.atakmap.android.xv.telecom

import android.content.Context
import android.telecom.CallAudioState
import android.util.Log
import androidx.annotation.VisibleForTesting

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

    /**
     * Post-teardown grace window (milliseconds) after our own Telecom
     * call is unregistered — see [withinRecentCallGrace] for the full
     * rationale. Sized to cover the observed ~1.4 s window on Pixel 9
     * Pro / API 35 during which `AudioManager.getMode()` still reports
     * `MODE_IN_COMMUNICATION` after the call ends, plus a safety
     * margin for slower devices (Samsung Tab5, Sonim ruggedized).
     */
    const val RECENT_OWN_CALL_GRACE_MS: Long = 3_000L

    @Volatile
    private var activeConnection: XvConnection? = null

    // Wall-clock (elapsedRealtime) timestamp of the most recent
    // transition from "own call active" → "own call ended". Zero
    // means we have never had an own call in this process lifetime
    // (or the registry has never been unregistered from). Read by
    // [withinRecentCallGrace] to paper over a race between our own
    // call ending and AudioManager.getMode() catching up.
    //
    // Field observation 2026-07-11 (Pixel 9 Pro / API 35): after
    // XvVoiceService tore down its self-managed Telecom call, the
    // registry unregistered synchronously (hasActiveCall() → false)
    // but AudioManager.getMode() kept reporting MODE_IN_COMMUNICATION
    // for ~1.4 s while the audio HAL wound down to MODE_NORMAL. In
    // that window, PttCellularGate saw IN_COMMUNICATION + no own
    // call → CALL_STATE_OFFHOOK → BLOCK_CELLULAR_CALL and blocked
    // legitimate operator PTT presses with the "Cellular call
    // active" toast. The grace window causes the gate to treat that
    // stale IN_COMMUNICATION reading as still-ours for a bounded
    // interval after teardown, restoring correct behavior.
    @Volatile
    private var lastOwnCallEndedAtMs: Long = 0L

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
            // Stamp the moment our own call transitioned to ended so
            // [withinRecentCallGrace] can hide the ~1.4 s
            // MODE_IN_COMMUNICATION tail from the cellular gate. Use
            // elapsedRealtime for monotonicity; PttCellularGate reads
            // the same clock via [VoicePlant.nowMsProvider], so the
            // two are directly comparable.
            lastOwnCallEndedAtMs = android.os.SystemClock.elapsedRealtime()
        }
    }

    fun activeConnection(): XvConnection? = activeConnection

    fun hasActiveCall(): Boolean = activeConnection != null

    /**
     * True when the caller's [nowMs] is within [graceMs] of the last
     * own-call teardown timestamp — i.e. we recently had a self-
     * managed Telecom call and the audio HAL's `MODE_IN_COMMUNICATION`
     * → `MODE_NORMAL` transition may still be in flight. The
     * `PttCellularGate` provider ORs this into its `hasActiveCall`
     * signal so a stale `MODE_IN_COMMUNICATION` reading during that
     * tail is still resolved as OUR call (IDLE) rather than
     * misclassified as an external cellular call (OFFHOOK).
     *
     * Returns false if we have never had an own call in this process
     * lifetime (sentinel zero — no prior teardown to base the grace
     * on). This preserves the block-all-sources policy for external
     * calls that come in before XV has ever placed its own call.
     *
     * Comparison is inclusive at both boundaries — exactly-at-
     * teardown and exactly-at-grace-expiry both count as "within" —
     * so a caller passing `nowMs == lastOwnCallEndedAtMs + graceMs`
     * still sees `true`. Wallclock skew is not a concern because
     * both endpoints come from `SystemClock.elapsedRealtime()`.
     */
    fun withinRecentCallGrace(
        nowMs: Long,
        graceMs: Long,
    ): Boolean {
        val endedAt = lastOwnCallEndedAtMs
        if (endedAt <= 0L) return false
        val elapsed = nowMs - endedAt
        // elapsed < 0 can happen if a caller passes a nowMs sample
        // taken before the unregister() stamp landed (thread ordering
        // on the volatile field). Treat as "within" — we know a
        // teardown occurred and the caller's read raced it.
        if (elapsed < 0L) return true
        return elapsed <= graceMs
    }

    /**
     * Test-only: force the "last own-call ended at" timestamp to a
     * deterministic value so the grace-window unit test can drive
     * boundary cases without instantiating a real
     * [android.telecom.Connection] (which requires Robolectric).
     * Zero clears the stamp — restoring the "never had an own call"
     * state.
     */
    @VisibleForTesting
    fun setLastOwnCallEndedAtMsForTest(value: Long) {
        lastOwnCallEndedAtMs = value
    }

    /**
     * Test-only: read the last-teardown timestamp so tests can
     * assert the [unregister] side effect landed. Kept separate from
     * the setter so production callers cannot accidentally reach
     * for it — the grace decision must go through
     * [withinRecentCallGrace].
     */
    @VisibleForTesting
    fun lastOwnCallEndedAtMsForTest(): Long = lastOwnCallEndedAtMs

    /**
     * True when an own call has been unregistered in this process at
     * least once — i.e. [unregister] has stamped
     * [lastOwnCallEndedAtMs] non-zero. False on a fresh process where
     * no call has ever been placed and torn down.
     *
     * Callers: the pre-[android.telecom.TelecomManager.placeCall]
     * ghost-purge guard in
     * [com.atakmap.android.xv.service.XvVoiceService.placeTelecomCallInternal]
     * uses this to distinguish "very first PTT press in a fresh
     * process (fresh-process purge already happened in onCreate)" from
     * "we've placed and torn down at least one call already; a fresh
     * placeCall may collide with a Telecom-side ghost TC@N." See
     * [com.atakmap.android.xv.service.XvVoiceService.shouldGhostPurgeBeforePlaceCall]
     * for the exact decision.
     *
     * Deliberately not derivable from [hasActiveCall]: a call that's
     * currently active in the registry would satisfy "has ever
     * existed" too, but the ghost-purge check dominates on
     * `hasActiveCall == true` via a separate early-return (the reuse
     * path). This reader captures ONLY the "ended-in-the-past"
     * signal, keeping the guard's two conditions orthogonal.
     */
    fun hasHadOwnCallInProcess(): Boolean = lastOwnCallEndedAtMs > 0L

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
