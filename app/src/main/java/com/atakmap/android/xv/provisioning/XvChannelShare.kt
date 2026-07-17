package com.atakmap.android.xv.provisioning

import android.util.Log
import androidx.annotation.VisibleForTesting
import com.atakmap.android.cot.CotMapComponent
import com.atakmap.coremap.cot.event.CotDetail
import com.atakmap.coremap.cot.event.CotEvent
import com.atakmap.coremap.cot.event.CotPoint
import com.atakmap.coremap.maps.time.CoordinatedTime
import java.security.SecureRandom

/**
 * CoT-based channel sharing — the "join my channel" nudge that replaces
 * the passphrase-locked carrier string for the common case.
 *
 * On the same TAK server a teammate needs **nothing but the channel
 * name**: the multicast group/port is a pure local hash of (server
 * hostname, channel name), and an encrypted channel's key is delivered
 * automatically by the cert-wrapped key election (anchored on cert
 * fingerprints already flowing through CoT presence). So this signal
 * carries only the channel name(s) — never a key — and the recipient
 * derives the endpoint and auto-exchanges the key locally.
 *
 * Mirrors [com.atakmap.android.xv.calling.XvCallSignals]: a broadcast
 * `b-x-...` command event, addressed by a target-UID filter. No
 * passphrase, no string to copy, no key on the wire.
 *
 * Wire format:
 * ```xml
 * <event uid="<sharerUid>-xvshare-<token>" type="b-x-xv-share" ...>
 *   <point .../>
 *   <detail>
 *     <__xvshare sharerUid="<uid>" sharerCallsign="<name>"
 *                targets="<uid> <uid> ..."   (empty = everyone)
 *                serverHost="tak.example.com" (optional, for derivation)
 *                channels="<name>\n<name>"/>
 *   </detail>
 * </event>
 * ```
 */
object XvChannelShare {
    const val EVENT_TYPE = "b-x-xv-share"
    const val DETAIL_NAME = "__xvshare"

    private const val TAG = "XvChannelShare"
    private const val CHANNEL_DELIM = "\n"
    private val rng = SecureRandom()

    data class ShareSignal(
        val sharerUid: String,
        val sharerCallsign: String,
        /** Empty = broadcast to everyone; else only these UIDs act on it. */
        val targetUids: List<String>,
        /** Sharer's server host, so a recipient can derive/label correctly. Null = ad-hoc. */
        val serverHost: String?,
        val channelNames: List<String>,
        /**
         * Outer CoT event uid — the receiver's dedup key. A TAK server
         * can deliver the same event several times (multi-path, and a
         * full backlog replay to a freshly connected client), and each
         * copy must not raise another Join prompt.
         */
        val eventUid: String? = null,
    )

    fun send(signal: ShareSignal): Boolean =
        try {
            CotMapComponent.getExternalDispatcher().dispatchToBroadcast(build(signal))
            Log.i(
                TAG,
                "send: sharer=${signal.sharerUid} targets=${signal.targetUids.size} " +
                    "channels=${signal.channelNames.size}",
            )
            true
        } catch (t: Throwable) {
            Log.w(TAG, "dispatch threw", t)
            false
        }

    private fun build(s: ShareSignal): CotEvent {
        val event = CotEvent()
        val token =
            java.lang.Long
                .toHexString(rng.nextLong())
                .take(12)
        event.setUID("${s.sharerUid}-xvshare-$token")
        event.setType(EVENT_TYPE)
        event.setHow("h-g-i-g-o")
        val now = CoordinatedTime()
        event.setTime(now)
        event.setStart(now)
        // Short stale — a momentary nudge, not durable state. 60s gives a
        // spotty server room to redeliver without a stale share from an old
        // session popping a Join prompt on a device that just joined.
        event.setStale(now.addSeconds(60))
        event.setPoint(CotPoint.ZERO)
        val detail = CotDetail("detail")
        val share = CotDetail(DETAIL_NAME)
        share.setAttribute("sharerUid", s.sharerUid)
        share.setAttribute("sharerCallsign", s.sharerCallsign)
        share.setAttribute("targets", s.targetUids.joinToString(" "))
        s.serverHost?.takeIf { it.isNotBlank() }?.let { share.setAttribute("serverHost", it) }
        share.setAttribute("channels", s.channelNames.joinToString(CHANNEL_DELIM))
        detail.addChild(share)
        event.setDetail(detail)
        return event
    }

    /**
     * Parse an inbound CoT event into a [ShareSignal] when it is an XV
     * channel-share addressed to us (or broadcast). Returns null otherwise.
     */
    fun parse(
        event: CotEvent,
        ourUid: String?,
    ): ShareSignal? {
        if (event.type != EVENT_TYPE) return null
        // Enforce the event's own staleness. A share is a momentary
        // nudge (60 s stale on send) — but a TAK server replays its CoT
        // backlog to a newly connected client, and without this check a
        // device that just enrolled received every share sent that DAY
        // as a burst of Join prompts, each accept re-joining channels
        // (field repro 2026-07-16 23:07: six stale events, delivered
        // three times each, in five seconds).
        val staleAtMs =
            try {
                event.stale?.milliseconds
            } catch (_: Throwable) {
                null
            }
        if (staleAtMs != null && staleAtMs < CoordinatedTime().milliseconds) {
            Log.i(TAG, "ignoring stale share ${event.uid} (server replay)")
            return null
        }
        val detail =
            try {
                event.findDetail(DETAIL_NAME)
            } catch (t: Throwable) {
                Log.w(TAG, "findDetail threw", t)
                null
            } ?: return null
        return parseFromAttributes(
            eventUid = event.uid,
            sharerUidAttr = detail.getAttribute("sharerUid"),
            sharerCallsign = detail.getAttribute("sharerCallsign"),
            targets = detail.getAttribute("targets"),
            serverHost = detail.getAttribute("serverHost"),
            channels = detail.getAttribute("channels"),
            ourUid = ourUid,
        )
    }

    /**
     * Pure parser — the logic of [parse] with CotEvent/CotDetail extraction
     * hoisted out, so tests pin the attribute shape + target-filter without
     * standing up the ATAK CoT classes (which don't resolve under unit tests).
     */
    @VisibleForTesting
    internal fun parseFromAttributes(
        eventUid: String?,
        sharerUidAttr: String?,
        sharerCallsign: String?,
        targets: String?,
        serverHost: String?,
        channels: String?,
        ourUid: String?,
    ): ShareSignal? {
        val channelNames =
            channels
                ?.split(CHANNEL_DELIM)
                ?.map { it.trim() }
                ?.filter { it.isNotBlank() }
                ?: emptyList()
        if (channelNames.isEmpty()) return null

        // Sharer UID — trust the OUTER event UID prefix (TAK Server TLS
        // authenticates that field); fall back to the detail attribute only
        // for diagnostics. Treat the detail value as untrusted.
        val sharerFromOuter =
            eventUid?.substringBefore("-xvshare-")?.takeIf { it.isNotBlank() && it != eventUid }
        val sharerUid =
            sharerFromOuter
                ?: sharerUidAttr?.takeIf { it.isNotBlank() }
                ?: return null

        val targetUids =
            targets
                ?.split(' ', '\t', '\n')
                ?.map { it.trim() }
                ?.filter { it.isNotBlank() }
                ?: emptyList()
        // Empty targets = broadcast to everyone; else only the addressees act.
        if (ourUid != null && targetUids.isNotEmpty() && ourUid !in targetUids) return null
        // Never act on our own share echoed back.
        if (ourUid != null && sharerUid == ourUid) return null

        return ShareSignal(
            sharerUid = sharerUid,
            sharerCallsign = sharerCallsign?.takeIf { it.isNotBlank() } ?: "",
            targetUids = targetUids,
            serverHost = serverHost?.takeIf { it.isNotBlank() },
            channelNames = channelNames,
            eventUid = eventUid?.takeIf { it.isNotBlank() },
        )
    }
}
