package com.atakmap.android.xv.calling

import android.util.Log
import androidx.annotation.VisibleForTesting
import com.atakmap.android.cot.CotMapComponent
import com.atakmap.coremap.cot.event.CotDetail
import com.atakmap.coremap.cot.event.CotEvent
import com.atakmap.coremap.cot.event.CotPoint
import com.atakmap.coremap.maps.time.CoordinatedTime
import java.security.SecureRandom

/**
 * CoT-based direct-call signaling.
 *
 * Replaces the VX-compat `[TAK MxVx : REQUEST_CALL ]<channelId>` Mumble
 * TextMessage path. CoT is the right layer for "I want to call you":
 *
 *  - independent of Mumble state (calls can be initiated/cancelled even
 *    while Mumble is mid-reconnect — the signal still gets through via
 *    the TAK Server CoT pipeline)
 *  - reuses ATAK's existing authentication (TAK Server TLS) and routing
 *  - parallels how Phase 2 publishes XV presence via `<__xv>` CoT detail
 *  - VX won't see these events (unknown type), and that's intentional;
 *    XV-VX direct-call interop is out of scope. Group-channel interop
 *    is unchanged.
 *
 * Wire format — broadcast CoT event:
 * ```xml
 * <event uid="<callerUid>-xvcall-<token>" type="b-x-xv-call"
 *        time="..." start="..." stale="...">
 *   <point lat="0" lon="0" hae="0" ce="9999999" le="9999999"/>
 *   <detail>
 *     <__xvcall action="REQUEST|CANCEL|REJECT"
 *               channel="TAK PRIVATE - <hex>"
 *               channelId="<int?>"
 *               callerUid="<callerUid>"
 *               calleeUid="<calleeUid>"
 *               callerCallsign="<friendly name>"/>
 *   </detail>
 * </event>
 * ```
 *
 * The `b-x-` prefix is a CoT convention for transient "command" events
 * (not map markers) — ATAK won't render these on the map, and other
 * plugins won't react to them.
 *
 * Addressing is broadcast + filter: every XV peer receives the event,
 * but only the one whose UID matches `calleeUid` acts on it. Simpler
 * than directed CoT (which requires resolving the peer to an ATAK
 * Contact), and free if TAK Server is already filtering by mission.
 */
object XvCallSignals {
    const val EVENT_TYPE = "b-x-xv-call"
    const val DETAIL_NAME = "__xvcall"

    const val ACTION_REQUEST = "REQUEST"
    const val ACTION_CANCEL = "CANCEL"
    const val ACTION_REJECT = "REJECT"

    private const val TAG = "XvCallSignals"
    private val rng = SecureRandom()

    data class Signal(
        val action: String,
        val tempChannelName: String,
        val tempChannelId: Int?,
        val callerUid: String,
        val calleeUid: String,
        val callerCallsign: String,
    )

    fun send(signal: Signal): Boolean =
        try {
            val event = build(signal)
            CotMapComponent.getExternalDispatcher().dispatchToBroadcast(event)
            Log.i(
                TAG,
                "send ${signal.action}: caller=${signal.callerUid} callee=${signal.calleeUid} " +
                    "channel='${signal.tempChannelName}' (id=${signal.tempChannelId})",
            )
            true
        } catch (t: Throwable) {
            Log.w(TAG, "dispatch threw", t)
            false
        }

    private fun build(s: Signal): CotEvent {
        val event = CotEvent()
        // UID encodes the caller so receivers can attribute the signal
        // to a specific operator even without parsing the detail body.
        // The random suffix lets a caller send REQUEST then CANCEL
        // without UID collision (each emission is a distinct CoT
        // event from ATAK's perspective).
        val token =
            java.lang.Long
                .toHexString(rng.nextLong())
                .take(12)
        event.setUID("${s.callerUid}-xvcall-$token")
        event.setType(EVENT_TYPE)
        event.setHow("h-g-i-g-o")
        val now = CoordinatedTime()
        event.setTime(now)
        event.setStart(now)
        // Short stale — these are momentary signals. 30s gives lossy
        // multicast / spotty servers some room to redeliver without
        // letting stale REJECT_CALLs from yesterday's session linger
        // on a receiver that just joined the mission.
        event.setStale(now.addSeconds(30))
        // Point is irrelevant — these are command events, not markers.
        // Use ZERO sentinel; ATAK skips map rendering when type starts
        // with "b-x-".
        event.setPoint(CotPoint.ZERO)
        val detail = CotDetail("detail")
        val xvcall = CotDetail(DETAIL_NAME)
        xvcall.setAttribute("action", s.action)
        xvcall.setAttribute("channel", s.tempChannelName)
        s.tempChannelId?.let { xvcall.setAttribute("channelId", it.toString()) }
        xvcall.setAttribute("callerUid", s.callerUid)
        xvcall.setAttribute("calleeUid", s.calleeUid)
        xvcall.setAttribute("callerCallsign", s.callerCallsign)
        detail.addChild(xvcall)
        event.setDetail(detail)
        return event
    }

    /**
     * Parse an inbound CoT event into a [Signal] when applicable.
     * Returns null for events that aren't XV call signals or are not
     * addressed to [ourUid]. Filtering here keeps the broadcast model
     * straightforward — every peer reads, only the addressee reacts.
     */
    fun parse(
        event: CotEvent,
        ourUid: String?,
    ): Signal? {
        if (event.type != EVENT_TYPE) return null
        val detail =
            try {
                event.findDetail(DETAIL_NAME)
            } catch (t: Throwable) {
                Log.w(TAG, "findDetail threw", t)
                null
            } ?: return null
        return parseFromAttributes(
            eventUid = event.uid,
            action = detail.getAttribute("action"),
            channel = detail.getAttribute("channel"),
            channelId = detail.getAttribute("channelId"),
            callerUidAttr = detail.getAttribute("callerUid"),
            calleeUidAttr = detail.getAttribute("calleeUid"),
            callerCallsign = detail.getAttribute("callerCallsign"),
            ourUid = ourUid,
        )
    }

    /**
     * Pure-function parser — the logic of [parse] with the CotEvent /
     * CotDetail extraction hoisted out. Tests pin the attribute-shape
     * and callee-filter semantics directly without standing up the
     * ATAK CoT classes (which don't resolve under unit tests).
     *
     * Inputs are the raw nullable attribute values that would have been
     * pulled from the CotDetail — same nullability story (blank → null
     * is treated as absent) so the test pins production behavior 1:1.
     */
    @VisibleForTesting
    internal fun parseFromAttributes(
        eventUid: String?,
        action: String?,
        channel: String?,
        channelId: String?,
        callerUidAttr: String?,
        calleeUidAttr: String?,
        callerCallsign: String?,
        ourUid: String?,
    ): Signal? {
        val a = action?.takeIf { it.isNotBlank() } ?: return null
        val ch = channel?.takeIf { it.isNotBlank() } ?: return null
        val cid = channelId?.toIntOrNull()
        // Caller UID — trust the OUTER event UID prefix as the
        // authentic source (TAK Server TLS authenticates that field),
        // falling back to the detail attribute for diagnostic
        // logging. Treat the detail value as untrusted.
        val callerFromOuter =
            eventUid?.substringBefore("-xvcall-")?.takeIf { it.isNotBlank() && it != eventUid }
        val callerUid =
            callerFromOuter
                ?: callerUidAttr?.takeIf { it.isNotBlank() }
                ?: return null
        val calleeUid = calleeUidAttr?.takeIf { it.isNotBlank() } ?: return null
        if (ourUid != null && calleeUid != ourUid) return null
        return Signal(
            action = a,
            tempChannelName = ch,
            tempChannelId = cid,
            callerUid = callerUid,
            calleeUid = calleeUid,
            callerCallsign = callerCallsign ?: "",
        )
    }
}
