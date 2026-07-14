package com.atakmap.android.xv.transport

// Decides WHEN a reconnecting transport should surface an audible
// "voice interface lost" alert to the operator — as opposed to every
// call site beeping on every dropped/failed attempt.
//
// The problem this solves (field report 2026-07): a device fed from a
// phone hotspot (e.g. tossed in a vehicle trunk) that loses its uplink
// beeps continuously, because the old code played WARNING_VOICE_LOST on
// the initial drop AND on every failed reconnect attempt of the
// forever-repeating backoff ladder.
//
// The desired UX:
//   1. A brief blip that the reconnect ladder heals within a couple of
//      attempts should make NO sound — the amber "reconnecting…" status
//      indicator already tells an attentive operator what's happening.
//   2. Only a *sustained* outage earns an audible alert, and only ONCE
//      per outage — not a beep per retry.
//   3. A successful (re)connect re-arms the tracker so the next outage
//      starts the count fresh.
//
// Pure + synchronized so it can be unit-tested directly (house
// convention) and shared between the transport read thread and the
// reconnect executor without a data race. No Android dependencies.
class ReconnectNotificationTracker(
    // Number of consecutive failed attempts (initial drop counts as the
    // first) before the single audible alert fires. Default 3 =
    // "initial drop + two failed reattempts" — inside the operator's
    // stated "at least two or three reattempts" tolerance.
    private val alertAfterAttempts: Int = DEFAULT_ALERT_THRESHOLD,
) {
    init {
        // A threshold below 1 would alert on the very first failure,
        // defeating the "brief blips stay silent" contract and making
        // the utility's behavior surprising for any future caller. Fail
        // fast on misconfiguration rather than silently degrading.
        require(alertAfterAttempts >= 1) {
            "alertAfterAttempts must be >= 1, was $alertAfterAttempts"
        }
    }

    private var consecutiveFailures: Int = 0
    private var alerted: Boolean = false

    /**
     * Record a failed connection event — either the initial unexpected
     * disconnect or a subsequent failed reconnect attempt. Returns true
     * EXACTLY ONCE per outage: on the attempt that first reaches
     * [alertAfterAttempts]. Every call before the threshold, and every
     * call after the single alert, returns false so the operator hears
     * one triple-beep for a sustained outage instead of a beep per retry.
     */
    @Synchronized
    fun onAttemptFailed(): Boolean {
        consecutiveFailures += 1
        if (!alerted && consecutiveFailures >= alertAfterAttempts) {
            alerted = true
            return true
        }
        return false
    }

    /**
     * Re-arm the tracker on a clean (re)connect — or when a fresh
     * connect is initiated. The next outage starts counting from zero
     * and can alert again.
     */
    @Synchronized
    fun reset() {
        consecutiveFailures = 0
        alerted = false
    }

    companion object {
        const val DEFAULT_ALERT_THRESHOLD: Int = 3
    }
}
