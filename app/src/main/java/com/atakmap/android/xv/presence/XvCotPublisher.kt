package com.atakmap.android.xv.presence

import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.VisibleForTesting
import com.atakmap.android.cot.CotMapComponent
import com.atakmap.coremap.cot.event.CotDetail

/**
 * Publishes the `<__xv>` CoT detail on every outgoing self-CoT via
 * [CotMapComponent.addAdditionalDetail]. The detail rides along with the
 * normal self-position cadence (no separate timer needed; ATAK already
 * broadcasts self-CoT periodically).
 *
 * State that can change at runtime — currently only the active Mumble
 * channel — is updated via [setChannels]; the publisher rebuilds and
 * re-registers the detail. Static fields (version, deviceUid, certFp,
 * server) are baked in at construction.
 */
class XvCotPublisher(
    private val deviceUid: String,
    private val version: String = com.atakmap.android.xv.BuildConfig.VERSION_NAME,
    private val capabilities: Set<String> = setOf("direct-call"),
    private val certFingerprint: String? = null,
    private val server: String? = null,
    // Side-channel for keeping the local registry's "self" entry in sync
    // with what we're publishing to peers. Without this, the channel-
    // members picker and other registry consumers see the local operator
    // as a non-XV peer (the listener intentionally drops self-UID CoT
    // echoes to avoid double-counting, so the registry has no other path
    // to learn about us). Invoked on the publisher's debounce cadence,
    // i.e. at most once per [debounceMs] — fine for a local in-memory
    // upsert.
    private val onSelfPublished: (XvPresence) -> Unit = {},
) {
    @Volatile
    private var currentChannels: List<XvChannel> = emptyList()

    // Phase E: published as `<__xv mumbleSession="N">` so a peer who
    // wants to call us can address a TextMessage directly to our
    // session ID. Updated whenever MumbleSession's ourSessionId
    // changes (set on connect, cleared to null on disconnect). Not
    // baked in at construction because Mumble may reconnect with a
    // different session integer.
    @Volatile
    private var mumbleSession: Int? = null

    // Operator's display callsign — published as `<__xv callsign>` so
    // peers can render a readable name in the call picker instead of
    // the 32-char device UID. Updated whenever ATAK's callsign
    // preference changes (best-effort: re-read at every publish cycle
    // via [latestCallsign]).
    @Volatile
    private var latestCallsign: (() -> String?)? = null

    @Volatile
    private var registered: Boolean = false

    // Debounce repeated rebuilds. On flaky networks the channel-move
    // callback can fire 10×/sec as the server resyncs; without
    // coalescing we'd re-register the CoT detail just as often, which
    // pointlessly thrashes ATAK's CoT pipeline. 1s gives the server
    // enough time to settle while still feeling instant.
    private val handler = Handler(Looper.getMainLooper())
    private val publishToken = Any()
    private val publishRunnable = Runnable { rebuildAndRegister() }
    private val debounceMs: Long = 1_000L

    fun start() {
        rebuildAndRegister()
    }

    fun setChannels(channels: List<XvChannel>) {
        if (channels == currentChannels) return
        currentChannels = channels
        if (registered) schedulePublish()
    }

    private fun schedulePublish() {
        handler.removeCallbacksAndMessages(publishToken)
        handler.postAtTime(
            publishRunnable,
            publishToken,
            android.os.SystemClock.uptimeMillis() + debounceMs,
        )
    }

    /**
     * Update our published Mumble session integer. Pass null when
     * disconnected so peers see the field disappear and fall back to
     * their secondary lookup paths (e.g. callsign roster scan) instead
     * of dispatching TextMessages to a stale session.
     */
    fun setMumbleSession(session: Int?) {
        if (session == mumbleSession) return
        mumbleSession = session
        if (registered) schedulePublish()
    }

    /**
     * Install a supplier for the operator's display callsign. The
     * supplier is called inside [rebuildAndRegister] on every publish
     * so the current ATAK preference is reflected without us needing
     * to subscribe to a settings-change broadcast.
     */
    fun setCallsignSupplier(supplier: () -> String?) {
        latestCallsign = supplier
        if (registered) schedulePublish()
    }

    fun stop() {
        handler.removeCallbacksAndMessages(publishToken)
        try {
            CotMapComponent.getInstance().removeAdditionalDetail(DETAIL_NAME)
        } catch (t: Throwable) {
            Log.w(TAG, "removeAdditionalDetail threw", t)
        }
        registered = false
    }

    @Synchronized
    private fun rebuildAndRegister() {
        val attrs =
            buildPresenceAttributes(
                version = version,
                deviceUid = deviceUid,
                capabilities = capabilities,
                certFp = certFingerprint,
                server = server,
                mumbleSession = mumbleSession,
                callsign = latestCallsign?.invoke(),
            )
        val chans = currentChannels
        val detail = CotDetail(DETAIL_NAME)
        for ((k, v) in attrs) detail.setAttribute(k, v)
        if (chans.isNotEmpty()) {
            val channelsDetail = CotDetail("channels")
            for (ch in chans) {
                val c = CotDetail("ch")
                for ((k, v) in channelToAttributes(ch)) c.setAttribute(k, v)
                channelsDetail.addChild(c)
            }
            detail.addChild(channelsDetail)
        }

        try {
            CotMapComponent.getInstance().addAdditionalDetail(DETAIL_NAME, detail)
            registered = true
            Log.i(TAG, "registered <__xv> detail (channels=${chans.size})")
        } catch (t: Throwable) {
            Log.w(TAG, "addAdditionalDetail threw", t)
        }

        // Mirror what we just told the world into the registry so the
        // local UI treats us as XV. Same shape XvCotListener.buildPresence
        // would assemble from an inbound echo.
        try {
            onSelfPublished(
                XvPresence(
                    deviceUid = deviceUid,
                    version = version,
                    capabilities = capabilities,
                    certFingerprint = certFingerprint,
                    server = server,
                    channels = chans,
                    lastSeenMs = System.currentTimeMillis(),
                    mumbleSession = mumbleSession,
                    callsign = latestCallsign?.invoke()?.takeIf { it.isNotBlank() },
                ),
            )
        } catch (t: Throwable) {
            Log.w(TAG, "onSelfPublished threw", t)
        }
    }

    companion object {
        const val DETAIL_NAME = "__xv"
        private const val TAG = "XvCotPublisher"

        /**
         * Pure-function builder for the attribute map that becomes the
         * `<__xv>` CoT detail. Extracted so XvCotPublisherTest can pin
         * the attribute-shape semantics (which fields are emitted, which
         * are omitted when null/blank, exact value formatting) without
         * standing up the ATAK CotDetail runtime class.
         *
         * Production [rebuildAndRegister] feeds this map into a fresh
         * CotDetail via setAttribute. The reverse direction —
         * [XvCotListener.buildPresence] — consumes the same attribute
         * names; a regression here would surface as XV peers becoming
         * invisible to each other.
         */
        @VisibleForTesting
        internal fun buildPresenceAttributes(
            version: String,
            deviceUid: String,
            capabilities: Set<String>,
            certFp: String?,
            server: String?,
            mumbleSession: Int?,
            callsign: String?,
        ): Map<String, String> {
            val out = LinkedHashMap<String, String>()
            out["ver"] = version
            out["uid"] = deviceUid
            out["caps"] = capabilities.joinToString(",")
            certFp?.let { out["certFp"] = it }
            server?.let { out["server"] = it }
            mumbleSession?.let { out["mumbleSession"] = it.toString() }
            callsign?.takeIf { it.isNotBlank() }?.let { out["callsign"] = it }
            return out
        }

        /**
         * Pure-function builder for the per-channel `<ch …/>` attribute
         * map. `keyEpoch` is omitted when 0 to keep the detail compact
         * for the common case (no channel-key rotation yet).
         */
        @VisibleForTesting
        internal fun channelToAttributes(ch: XvChannel): Map<String, String> {
            val out = LinkedHashMap<String, String>()
            out["name"] = ch.name
            out["id"] = ch.id.toString()
            if (ch.keyEpoch > 0) out["keyEpoch"] = ch.keyEpoch.toString()
            return out
        }
    }
}
