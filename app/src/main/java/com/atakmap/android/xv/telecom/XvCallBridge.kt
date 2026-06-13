package com.atakmap.android.xv.telecom

import android.util.Log
import com.atakmap.android.xv.service.XvVoiceClient

// Plugin-side facade for the Telecom-mediated voice session. Because
// XvConnectionService is gated on BIND_TELECOM_CONNECTION_SERVICE
// (system-only) AND the plugin runs in ATAK's UID without
// MANAGE_OWN_CALLS, all Telecom operations have to happen in our
// APK's UID. This bridge proxies them through XvVoiceClient (which
// already has a binder to XvVoiceService in our UID). The voice
// service in turn calls TelecomManager.placeCall — same process as
// XvConnectionService, so the system-mediated callback chain works.
//
// callActive is the plugin's local approximation of "is there a
// Telecom call right now?" — used to gate manual audio focus calls
// in TxController + AudioPlayback. Flips true on placeCall, false
// on endCall.
class XvCallBridge(
    private val voiceClient: XvVoiceClient,
) {
    @Volatile
    private var callActive: Boolean = false

    fun isCallActive(): Boolean = callActive

    /**
     * Reset the call-active flag from outside. Called by the plugin's
     * `onPrivateCallEnded` listener (which fires when the service-side
     * `externalTeardownListener` notifies that Telecom tore down the
     * call from outside our explicit `endChannelCall` path — peer
     * hangup, system preempt, BAL kill, etc.).
     *
     * L4 fix: without this hook, `callActive` stays true forever after
     * an external teardown — TxController + AudioPlayback consult
     * isCallActive() to decide whether to skip their manual focus
     * fallback, and a stale-true result blocks fallback paths the
     * plant expects to use when no Telecom call is up.
     */
    fun notifyExternallyEnded() {
        if (callActive) {
            callActive = false
            Log.i(TAG, "notifyExternallyEnded — callActive=false")
        }
    }

    fun startChannelCall(channelTag: String): Boolean =
        try {
            voiceClient.ifBound {
                try {
                    it.startChannelCall(channelTag)
                } catch (t: Throwable) {
                    Log.w(TAG, "startChannelCall AIDL threw", t)
                }
            }
            callActive = true
            Log.i(TAG, "startChannelCall queued for tag=$channelTag (callActive=true)")
            true
        } catch (t: Throwable) {
            Log.e(TAG, "startChannelCall threw", t)
            false
        }

    fun endChannelCall() {
        // Idempotent. Teardown converges on this from multiple sites
        // (stopActiveTransport, callBridge.shutdown, onPrivateCallEnded,
        // direct UI Hang Up), so a stale-true → stale-false flip is
        // the only useful work — if callActive is already false the
        // AIDL has been sent and the service side has cleared its
        // Telecom call; re-sending just produces a duplicate log line.
        if (!callActive) return
        callActive = false
        voiceClient.ifBound {
            try {
                it.endChannelCall()
                Log.i(TAG, "endChannelCall sent (callActive=false)")
            } catch (t: Throwable) {
                Log.w(TAG, "endChannelCall AIDL threw", t)
            }
        }
    }

    fun shutdown() {
        endChannelCall()
    }

    companion object {
        private const val TAG = "XvCallBridge"
    }
}
