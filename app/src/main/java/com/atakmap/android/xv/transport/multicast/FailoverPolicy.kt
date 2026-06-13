package com.atakmap.android.xv.transport.multicast

/**
 * Decides per-frame which leg (Mumble or multicast) to TX on, given
 * recent transport health observations. Pure-logic state machine —
 * the actual TX dispatch lives in `FailoverTransport`, which feeds
 * timestamps and connection state in and reads [active] back out.
 *
 * Rules captured here (from the Phase 8 design):
 *
 *   Failover (Mumble → multicast):
 *     - When `mumbleConnected` becomes false, flip immediately to
 *       multicast. There's no point in being conservative — if the
 *       TCP control socket is dead, the UDP_TUNNEL is already dead
 *       too.
 *
 *   Failback (multicast → Mumble):
 *     - Require ALL of:
 *         (a) mumbleConnected == true
 *         (b) recent Mumble RX traffic — a Ping ack or VoiceFrame in
 *             the last RX_HEALTH_WINDOW_MS. TCP-connected says
 *             nothing about voice routing being healthy.
 *         (c) HEALTHY_HYSTERESIS_MS of continuous health since the
 *             health condition first became true. Avoids thrash if
 *             Mumble bounces.
 *         (d) Inter-burst gap: no TX frame sent in the last
 *             INTER_BURST_GAP_MS. Switching mid-PTT breaks the audio
 *             stream.
 *
 *   Boundary glitch mitigation:
 *     - On any flip, the integration layer emits a 20-40ms silence
 *       frame on the new transport (so playback's silence-timer
 *       doesn't think the speaker dropped) and resets the Opus
 *       encoder. This class signals "flipped" via the return of
 *       [evaluate] so the caller knows to do that work.
 */
class FailoverPolicy(
    private val rxHealthWindowMs: Long = RX_HEALTH_WINDOW_MS,
    private val healthyHysteresisMs: Long = HEALTHY_HYSTERESIS_MS,
    private val interBurstGapMs: Long = INTER_BURST_GAP_MS,
) {
    private var current: Leg = Leg.MUMBLE
    private var lastTxFrameMs: Long = Long.MIN_VALUE
    private var lastMumbleRxMs: Long = Long.MIN_VALUE
    private var mumbleHealthyStartMs: Long = Long.MIN_VALUE
    private var lastMumbleConnected: Boolean = true

    /** Which transport is the current TX target. */
    fun active(): Leg = current

    /** Note that we sent a frame at [nowMs] (used for inter-burst gap). */
    fun observeTxFrame(nowMs: Long) {
        lastTxFrameMs = nowMs
    }

    /**
     * Note that we received a frame from Mumble at [nowMs]. Used for
     * the "Mumble RX healthy" health check.
     */
    fun observeMumbleRx(nowMs: Long) {
        lastMumbleRxMs = nowMs
    }

    /**
     * Update the policy with the current observed state of the Mumble
     * leg. Returns the result: did we flip, and if so to what.
     */
    fun evaluate(
        nowMs: Long,
        mumbleConnected: Boolean,
    ): Decision {
        // Track when Mumble first became "healthy" (connected + recent RX).
        val healthy = mumbleConnected && (nowMs - lastMumbleRxMs) <= rxHealthWindowMs
        if (healthy) {
            if (mumbleHealthyStartMs == Long.MIN_VALUE || !lastMumbleConnected) {
                mumbleHealthyStartMs = nowMs
            }
        } else {
            mumbleHealthyStartMs = Long.MIN_VALUE
        }
        lastMumbleConnected = mumbleConnected

        return when (current) {
            Leg.MUMBLE -> evaluateOnMumble(nowMs, mumbleConnected)
            Leg.MULTICAST -> evaluateOnMulticast(nowMs, healthy)
        }
    }

    private fun evaluateOnMumble(
        nowMs: Long,
        mumbleConnected: Boolean,
    ): Decision =
        if (!mumbleConnected) {
            current = Leg.MULTICAST
            Decision.FlippedTo(Leg.MULTICAST, reason = Reason.MUMBLE_DISCONNECTED)
        } else {
            Decision.NoChange(current)
        }

    private fun evaluateOnMulticast(
        nowMs: Long,
        healthy: Boolean,
    ): Decision {
        if (!healthy) return Decision.NoChange(current)
        val healthyFor = nowMs - mumbleHealthyStartMs
        if (healthyFor < healthyHysteresisMs) return Decision.NoChange(current)
        // Healthy long enough — but check inter-burst gap.
        val timeSinceLastTx = nowMs - lastTxFrameMs
        if (lastTxFrameMs != Long.MIN_VALUE && timeSinceLastTx < interBurstGapMs) {
            return Decision.NoChange(current)
        }
        current = Leg.MUMBLE
        return Decision.FlippedTo(Leg.MUMBLE, reason = Reason.MUMBLE_RECOVERED)
    }

    enum class Leg { MUMBLE, MULTICAST }

    sealed class Decision {
        abstract val active: Leg

        /** Status quo this tick. */
        data class NoChange(
            override val active: Leg,
        ) : Decision()

        /** Active leg flipped. Caller emits a silence frame + resets encoder. */
        data class FlippedTo(
            override val active: Leg,
            val reason: Reason,
        ) : Decision()
    }

    enum class Reason {
        MUMBLE_DISCONNECTED, // failover: Mumble TCP dropped
        MUMBLE_RECOVERED, // failback: Mumble healthy for hysteresis window
    }

    companion object {
        /**
         * Mumble RX is "healthy" if we've seen any frame (voice or
         * Ping ack) within this window. Tactical chosen at 3s — a
         * Mumble keep-alive Ping is sent every 5s by default but RX
         * traffic from peers fills the gap. Three seconds is short
         * enough to detect a quietly-broken TCP path quickly while
         * tolerating one missed Ping cycle.
         */
        const val RX_HEALTH_WINDOW_MS: Long = 3_000

        /**
         * Mumble must remain healthy for this long before we consider
         * failback. 10s avoids thrash if Mumble bounces during a
         * flaky-WAN moment.
         */
        const val HEALTHY_HYSTERESIS_MS: Long = 10_000

        /**
         * Don't fail back if we sent a frame within this window — the
         * operator is mid-burst. 200ms ≈ 10 Opus frames, so we'd flip
         * during a normal pause-between-words; tactical is closer to
         * 50ms but we err toward "don't switch mid-utterance".
         */
        const val INTER_BURST_GAP_MS: Long = 200
    }
}
