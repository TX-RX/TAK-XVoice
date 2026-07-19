package com.atakmap.android.xv.transport

// Backoff state machine for transports that need to recover from
// transient drops without retrying into a wall on permanent failures.
// Used by ReconnectingMumbleTransport (Mumble TLS) and AinaSppReader
// (Bluetooth Classic SPP); both share the same shape — capped
// exponential backoff with a hard cutoff for fatal outcomes.
//
// Not thread-safe by itself; callers serialize via a single Handler /
// retry thread (which is the natural shape for both call sites).
class ReconnectPolicy(
    // Sequence of delays applied in order; the last entry repeats
    // forever (until cancelled by the caller). Tuned for "the AINA
    // came back into range" + "wifi handed off" timescales — sub-
    // second isn't useful (radio takes that long to come back), and
    // beyond a minute we're better off telling the operator and
    // letting them decide.
    private val schedule: LongArray = DEFAULT_SCHEDULE,
) {
    @Volatile
    private var attempt: Int = 0

    /** Next delay to wait before the upcoming connect attempt. Increments
     *  the internal attempt counter; pair with [reset] on success. */
    fun nextDelayMs(): Long {
        val idx = attempt.coerceAtMost(schedule.lastIndex)
        attempt += 1
        return schedule[idx]
    }

    fun attemptCount(): Int = attempt

    fun reset() {
        attempt = 0
    }

    /** A transport-defined classification of the most recent drop or
     *  failure. Carries reason text so the UI can render *why* without
     *  rebuilding it from logs. */
    sealed class Outcome {
        // Network blip, server bounce, TLS handshake hiccup. Retry.
        object Transient : Outcome()

        // Auth wall, NoCertificate, ban, self-kick. Don't retry; the
        // operator has to act.
        data class Fatal(
            val reason: String,
        ) : Outcome()

        // Operator hit Disconnect, plugin tearing down, etc. Don't retry
        // and don't surface anything to the UI.
        object UserInitiated : Outcome()
    }

    fun shouldRetry(outcome: Outcome): Boolean =
        when (outcome) {
            Outcome.Transient -> true
            is Outcome.Fatal -> false
            Outcome.UserInitiated -> false
        }

    companion object {
        // Capped curve with a dormant tail: 1s, 2s, 4s, 8s, 15s, 30s,
        // 60s, then 2m, then 5m — and 5m repeats FOREVER. The fast front
        // matches "the AINA came back" / "wifi handed off" timescales;
        // the 2m/5m tail is the auto-slowdown for an unattended device
        // that's genuinely off-grid (e.g. a hotspot-fed unit thrown in a
        // vehicle trunk). Retrying a dead uplink once a minute forever
        // wastes battery and radio for no one; stretching to 5m keeps a
        // background heartbeat without the drain. An operator who wants
        // back immediately collapses the wait with a PTT press
        // (ReconnectingMumbleTransport.retryNow).
        //
        // The ladder never stops on its own, by explicit operator
        // policy: a real LMR radio never gives up trying to reach the
        // repeater, and XV must not either. Silence because we quit is
        // operationally worse than silence because we can't get through
        // — an operator who keys up expecting the radio to have been
        // trying, and finds it dormant instead, has been lied to by the
        // device. The only ways out of the ladder are an explicit
        // operator disconnect, a Fatal outcome (auth wall — retrying
        // walks into the same wall), or the auto-reconnect toggle. An
        // earlier revision auto-paused here after ~10 attempts; it was
        // removed rather than tuned, so don't reintroduce a "give up
        // after N" constant.
        val DEFAULT_SCHEDULE: LongArray =
            longArrayOf(1_000L, 2_000L, 4_000L, 8_000L, 15_000L, 30_000L, 60_000L, 120_000L, 300_000L)

        // Faster + tighter cap for AINA SPP. The hardware is local
        // (BR/EDR range), so the operator either walks back into
        // range within a minute or they don't.
        val SPP_SCHEDULE: LongArray =
            longArrayOf(400L, 800L, 1_600L, 3_200L, 6_400L, 30_000L)
    }
}
