package com.atakmap.android.xv.presence

import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.VisibleForTesting
import com.atakmap.android.cot.CotMapComponent
import com.atakmap.coremap.cot.event.CotDetail
import com.atakmap.coremap.cot.event.CotEvent
import com.atakmap.coremap.cot.event.CotPoint
import com.atakmap.coremap.maps.time.CoordinatedTime

/**
 * Publishes bridge election state as a standalone CoT event dispatched
 * to the **local network only** — never forwarded upstream to the TAK Server.
 *
 * This is deliberately separate from [XvCotPublisher], which appends
 * `<__xv>` to the device's standard self-ping (which does go to the server).
 * Bridge election is a purely local MANET concern: nodes on disconnected
 * segments each elect their own bridge independently. Leaking election
 * state to the server would be meaningless at best, confusing at worst.
 *
 * Election logic: the peer with the lowest UID among those advertising
 * `mumbleConnected=true` on a given channel assumes the bridge role.
 * [XvCotListener] extends to parse [DETAIL_NAME] as well.
 *
 * Broadcasting cadence: immediate on state change, then every [PERIOD_MS].
 */
class XvBridgeCotPublisher(
    private val deviceUid: String,
    private val version: String = com.atakmap.android.xv.BuildConfig.VERSION_NAME,
) {
    @Volatile private var currentChannels: List<XvChannel> = emptyList()

    @Volatile private var mumbleSession: Int? = null

    @Volatile private var latestCallsign: (() -> String?)? = null

    @Volatile private var isBridging: Boolean = false

    @Volatile private var started: Boolean = false

    private val handler = Handler(Looper.getMainLooper())
    private val publishToken = Any()
    private val publishRunnable = Runnable { doPublishAndReschedule() }

    fun start() {
        started = true
        schedulePublish(immediate = true)
    }

    fun stop() {
        started = false
        handler.removeCallbacksAndMessages(publishToken)
    }

    fun setChannels(channels: List<XvChannel>) {
        if (channels == currentChannels) return
        currentChannels = channels
        if (started) schedulePublish(immediate = true)
    }

    fun setMumbleSession(session: Int?) {
        if (session == mumbleSession) return
        mumbleSession = session
        if (started) schedulePublish(immediate = true)
    }

    fun setCallsignSupplier(supplier: () -> String?) {
        latestCallsign = supplier
    }

    fun setBridging(bridging: Boolean) {
        if (isBridging == bridging) return
        isBridging = bridging
        if (started) schedulePublish(immediate = true)
    }

    private fun schedulePublish(immediate: Boolean) {
        handler.removeCallbacksAndMessages(publishToken)
        val delayMs = if (immediate) IMMEDIATE_DELAY_MS else PERIOD_MS
        handler.postAtTime(
            publishRunnable,
            publishToken,
            android.os.SystemClock.uptimeMillis() + delayMs,
        )
    }

    @Synchronized
    private fun doPublishAndReschedule() {
        if (!started) return
        val event = build(
            deviceUid = deviceUid,
            version = version,
            callsign = latestCallsign?.invoke(),
            mumbleSession = mumbleSession,
            isBridging = isBridging,
            channels = currentChannels,
        )
        try {
            // dispatchToBroadcast sends over the local mesh (SA multicast)
            // without routing the event to any connected TAK Server,
            // which is exactly what we want for bridge election.
            CotMapComponent.getExternalDispatcher().dispatchToBroadcast(event)
            Log.d(TAG, "bridge CoT dispatched (bridging=$isBridging, channels=${currentChannels.size})")
        } catch (t: Throwable) {
            Log.w(TAG, "bridge CoT dispatch failed", t)
        }
        // Reschedule periodic heartbeat
        schedulePublish(immediate = false)
    }

    companion object {
        const val DETAIL_NAME = "__xv_bridge"
        private const val TAG = "XvBridgeCotPublisher"
        private const val IMMEDIATE_DELAY_MS = 500L
        private const val PERIOD_MS = 10_000L // 10 s heartbeat

        @VisibleForTesting
        internal fun build(
            deviceUid: String,
            version: String,
            callsign: String?,
            mumbleSession: Int?,
            isBridging: Boolean,
            channels: List<XvChannel>,
        ): CotEvent {
            val event = CotEvent()
            // Use a derived UID so this doesn't overwrite the operator's map marker.
            event.uid = "$deviceUid-xv-bridge"
            // b-m-p-s-m = broadcast machine-generated situational message
            event.type = "b-m-p-s-m"
            event.how = "m-g"

            val now = CoordinatedTime()
            event.time = now
            event.start = now
            // TTL: 30 s. If we miss 3 heartbeats, peers consider us offline.
            event.stale = CoordinatedTime(now.milliseconds + 30_000L)
            event.setPoint(CotPoint.ZERO)

            val root = CotDetail("detail")
            val xvb = CotDetail(DETAIL_NAME)
            // uid carries the *operator* UID so the election comparator can
            // compare against the map of known peers, not the derived bridge UID.
            xvb.setAttribute("uid", deviceUid)
            xvb.setAttribute("ver", version)
            callsign?.takeIf { it.isNotBlank() }?.let { xvb.setAttribute("callsign", it) }
            xvb.setAttribute("mumbleConnected", (mumbleSession != null).toString())
            mumbleSession?.let { xvb.setAttribute("mumbleSession", it.toString()) }
            xvb.setAttribute("bridging", isBridging.toString())

            if (channels.isNotEmpty()) {
                val chans = CotDetail("channels")
                for (ch in channels) {
                    val c = CotDetail("ch")
                    c.setAttribute("name", ch.name)
                    c.setAttribute("id", ch.id.toString())
                    if (ch.keyEpoch > 0) c.setAttribute("keyEpoch", ch.keyEpoch.toString())
                    chans.addChild(c)
                }
                xvb.addChild(chans)
            }

            root.addChild(xvb)
            event.detail = root
            return event
        }
    }
}
