package com.atakmap.android.xv.presence

import android.util.Log
import androidx.annotation.VisibleForTesting
import com.atakmap.android.cot.detail.CotDetailHandler
import com.atakmap.android.cot.detail.CotDetailManager
import com.atakmap.android.maps.MapItem
import com.atakmap.comms.CommsMapComponent
import com.atakmap.coremap.cot.event.CotDetail
import com.atakmap.coremap.cot.event.CotEvent

/**
 * Receives `<__xv>` presence details via ATAK's [CotDetailManager]
 * pipeline. The handler updates [XvPresenceRegistry] so the Recent
 * Users picker can show who's been on the air recently.
 *
 * NOTE on API choice: `CommsMapComponent.addOnCotEventListener` is the
 * obvious entry point, but it only fires for events the main pipeline
 * fails to process — standard self-CoT markers (a-f-G-U-C and friends)
 * return SUCCESS from the [com.atakmap.comms.CommsMapComponent.DirectCotProcessor],
 * which short-circuits the listener fan-out. Our `<__xv>` detail rides
 * standard self-CoT, so peers never reach the listener.
 *
 * [CotDetailHandler] is the canonical path for plugins that want to
 * react to a custom detail on otherwise-standard events: ATAK invokes
 * [toItemMetadata] on every inbound CoT that contains a detail with a
 * matching name, regardless of whether the parent event also creates
 * a map marker.
 */
class XvCotListener(
    private val ourUid: String?,
    private val registry: XvPresenceRegistry,
    // A teammate shared channel(s) with us via a b-x-xv-share CoT nudge.
    // Fired only for shares addressed to us (or broadcast); the plugin
    // raises a Join prompt. No-op by default.
    private val onChannelShare: (com.atakmap.android.xv.provisioning.XvChannelShare.ShareSignal) -> Unit = {},
) {
    private val handler: CotDetailHandler =
        object : CotDetailHandler(
            setOf(
                XvCotPublisher.DETAIL_NAME,
                com.atakmap.android.xv.provisioning.XvChannelShare.DETAIL_NAME,
            ),
        ) {
            override fun toItemMetadata(
                item: MapItem?,
                event: CotEvent?,
                detail: CotDetail?,
            ): CommsMapComponent.ImportResult {
                if (event == null || detail == null) return CommsMapComponent.ImportResult.IGNORE
                return when (detail.elementName) {
                    XvCotPublisher.DETAIL_NAME -> handlePresence(event, detail)
                    com.atakmap.android.xv.provisioning.XvChannelShare.DETAIL_NAME -> handleShare(event)
                    else -> CommsMapComponent.ImportResult.IGNORE
                }
            }

            override fun toCotDetail(
                item: MapItem?,
                event: CotEvent?,
                detail: CotDetail?,
            ): Boolean {
                // We only consume inbound details — never derive
                // outbound detail content from a map item. Return
                // false so ATAK doesn't expect us to fill in.
                return false
            }
        }

    @Volatile
    private var registered: Boolean = false

    fun start() {
        try {
            CotDetailManager.getInstance().registerHandler(handler)
            registered = true
            Log.i(
                TAG,
                "CotDetailHandler registered for [${XvCotPublisher.DETAIL_NAME}] (ourUid=$ourUid)",
            )
        } catch (t: Throwable) {
            Log.w(TAG, "registerHandler threw", t)
        }
    }

    fun stop() {
        if (!registered) return
        try {
            CotDetailManager.getInstance().unregisterHandler(handler)
        } catch (t: Throwable) {
            Log.w(TAG, "unregisterHandler threw", t)
        }
        registered = false
    }

    private fun handleShare(event: CotEvent): CommsMapComponent.ImportResult {
        val signal =
            try {
                com.atakmap.android.xv.provisioning.XvChannelShare.parse(event, ourUid)
            } catch (t: Throwable) {
                Log.w(TAG, "channel-share parse threw", t)
                null
            } ?: return CommsMapComponent.ImportResult.IGNORE
        Log.i(TAG, "channel share from ${signal.sharerUid}: ${signal.channelNames.size} channel(s)")
        onChannelShare(signal)
        return CommsMapComponent.ImportResult.SUCCESS
    }

    private fun handlePresence(
        event: CotEvent,
        xv: CotDetail,
    ): CommsMapComponent.ImportResult {
        // CRITICAL: bind to the OUTER event.uid only. ATAK signs CoT
        // events end-to-end via TAK Server TLS; the outer event.uid is
        // therefore authenticated. The `<__xv uid>` attribute is set
        // by the publisher and would let any peer spoof identity by
        // claiming `uid="victim"` in their own CoT — calls would route
        // to the spoofer. Ignore the inner attribute entirely.
        val uid = event.uid?.takeIf { it.isNotBlank() } ?: return CommsMapComponent.ImportResult.IGNORE
        // Skip echoes of our own self-CoT bouncing back through TAK Server.
        if (ourUid != null && uid == ourUid) return CommsMapComponent.ImportResult.SUCCESS

        val channels = mutableListOf<XvChannel>()
        try {
            val channelsDetail = xv.getFirstChildByName(0, "channels")
            if (channelsDetail != null) {
                for (ch in channelsDetail.getChildrenByName("ch")) {
                    val name = ch.getAttribute("name") ?: continue
                    val id = ch.getAttribute("id")?.toIntOrNull() ?: continue
                    val ke = ch.getAttribute("keyEpoch")?.toIntOrNull() ?: 0
                    channels.add(XvChannel(name, id, ke))
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "channels parse threw", t)
        }

        val presence =
            buildPresence(
                outerEventUid = uid,
                ver = xv.getAttribute("ver"),
                capsCsv = xv.getAttribute("caps"),
                certFp = xv.getAttribute("certFp"),
                server = xv.getAttribute("server"),
                mumbleSessionStr = xv.getAttribute("mumbleSession"),
                callsign = xv.getAttribute("callsign"),
                channels = channels,
                nowMs = System.currentTimeMillis(),
            )
        val isNew = registry.get(uid) == null
        registry.upsert(presence)
        if (isNew) {
            Log.i(
                TAG,
                "XV peer joined: uid=${presence.deviceUid} callsign=${presence.callsign} " +
                    "ver=${presence.version} caps=${presence.capabilities} " +
                    "server=${presence.server} channels=${presence.channels.size}",
            )
        }
        return CommsMapComponent.ImportResult.SUCCESS
    }

    companion object {
        private const val TAG = "XvCotListener"

        /**
         * Pure-function builder for the [XvPresence] entry from raw
         * `<__xv>` attributes. Extracted so XvCotListenerTest can pin
         * the attribute-shape semantics without standing up the ATAK
         * CotEvent / CotDetail runtime classes.
         *
         * The outer event UID is taken on trust here — production
         * code is responsible for the security-critical "use only the
         * TLS-authenticated outer UID" check before calling this.
         */
        @VisibleForTesting
        internal fun buildPresence(
            outerEventUid: String,
            ver: String?,
            capsCsv: String?,
            certFp: String?,
            server: String?,
            mumbleSessionStr: String?,
            callsign: String?,
            channels: List<XvChannel>,
            nowMs: Long,
        ): XvPresence =
            XvPresence(
                deviceUid = outerEventUid,
                version = ver ?: "",
                capabilities =
                capsCsv
                    ?.split(",")
                    ?.map { it.trim() }
                    ?.filter { it.isNotEmpty() }
                    ?.toSet()
                    ?: emptySet(),
                certFingerprint = certFp?.takeIf { it.isNotBlank() },
                server = server?.takeIf { it.isNotBlank() },
                channels = channels,
                lastSeenMs = nowMs,
                mumbleSession = mumbleSessionStr?.toIntOrNull(),
                callsign = callsign?.takeIf { it.isNotBlank() },
            )
    }
}
