package com.atakmap.android.xv.telecom

import android.util.Log
import com.atakmap.android.xv.service.XvVoiceClient

// Plugin-side facade for ENDING the Telecom-mediated voice session.
// XvConnectionService is gated on BIND_TELECOM_CONNECTION_SERVICE
// (system-only) AND the plugin runs in ATAK's UID without
// MANAGE_OWN_CALLS, so all Telecom operations must happen in our APK's
// UID. This bridge proxies the end-call through XvVoiceClient (which
// holds a binder to XvVoiceService in our UID); the service in turn
// tears down the XvConnection it owns.
//
// Placement is NOT proxied here — outgoing calls originate directly in
// XvVoiceService.placeTelecomCallInternal (group PTT) or via
// XvVoiceClient.startChannelCall (private calls). This bridge is the
// end-call side only.
//
// History: this class used to carry a plugin-side `callActive`
// approximation flag plus a `startChannelCall` proxy and an
// `isCallActive()` reader. That machinery was superseded by
// ActiveCallRegistry.hasActiveCall() (the authoritative service-process
// signal that TxController/AudioPlayback actually consult) and became
// dead — `startChannelCall` had no callers, so `callActive` was never
// set true, which in turn made `endChannelCall()`'s old
// `if (!callActive) return` guard a permanent no-op that silently broke
// the in-app "End Call" bar. The flag/proxy/reader were removed and
// `endChannelCall()` now fires unconditionally.
class XvCallBridge(
    private val voiceClient: XvVoiceClient,
) {
    /**
     * End the active Telecom call. Fires unconditionally — the
     * service-side teardown is idempotent (`teardownLocal()` no-ops when
     * no XvConnection is registered), so a redundant end is harmless.
     * Teardown converges here from several sites (stopActiveTransport,
     * the in-app End Call bar, onPrivateCallEnded, direct UI Hang Up);
     * every one of them needs the Telecom Connection released so audio
     * focus + BT routing hand back to normal media.
     */
    fun endChannelCall() {
        voiceClient.ifBound {
            try {
                it.endChannelCall()
                Log.i(TAG, "endChannelCall sent")
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
