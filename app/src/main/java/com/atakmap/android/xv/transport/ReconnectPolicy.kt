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
        // Capped curve: 1s, 2s, 4s, 8s, 15s, 30s, 60s. Last value
        // repeats indefinitely (ReconnectingMumbleTransport keeps
        // trying once a minute until the operator tears down or the
        // server comes back).
        val DEFAULT_SCHEDULE: LongArray =
            longArrayOf(1_000L, 2_000L, 4_000L, 8_000L, 15_000L, 30_000L, 60_000L)

        // Faster + tighter cap for AINA SPP. The hardware is local
        // (BR/EDR range), so the operator either walks back into
        // range within a minute or they don't.
        val SPP_SCHEDULE: LongArray =
            longArrayOf(400L, 800L, 1_600L, 3_200L, 6_400L, 30_000L)
    }
}
