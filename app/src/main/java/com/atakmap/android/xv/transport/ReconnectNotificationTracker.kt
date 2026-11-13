package com.atakmap.android.xv.transport

// Decides WHAT the operator should HEAR during a reconnect outage — as
// opposed to every call site beeping on every dropped/failed attempt.
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
//   2. Only a *sustained* outage earns the loud WARNING_VOICE_LOST
//      triple-beep, and only ONCE per outage — not a beep per retry.
//   3. While the outage continues, a much lighter retry chirp per
//      attempt tells the operator the radio is still working the problem
//      — the LMR "still trying" cue. It stops after
//      [audibleUntilMs] so an unattended off-grid device goes quiet even
//      though the ladder itself keeps retrying forever.
//   4. A successful reconnect earns a recovery chime, but ONLY if there
//      was an outage to recover from — a first-time connect is silent.
//
// The two audible cues are deliberately different sounds with different
// jobs: WARNING_VOICE_LOST means "you have lost voice, act on it," the
// retry chirp means "still trying, stand by." Stacking both on the same
// attempt would blur that, so [Decision] never sets both.
//
// Pure + synchronized so it can be unit-tested directly (house
// convention) and shared between the transport read thread and the
// reconnect executor without a data race. No Android dependencies —
// callers pass the clock in.
class ReconnectNotificationTracker(
    // Number of consecutive failed attempts (initial drop counts as the
    // first) before the single audible alert fires. Default 3 =
    // "initial drop + two failed reattempts" — inside the operator's
    // stated "at least two or three reattempts" tolerance.
    private val alertAfterAttempts: Int = DEFAULT_ALERT_THRESHOLD,
    // How long into an outage the per-attempt retry chirp keeps playing.
    // Past this the ladder still retries (forever, by design — see
    // ReconnectPolicy.DEFAULT_SCHEDULE) but does so silently; the
    // persistent notification is the only remaining signal. Sized so a
    // device nobody is holding stops making noise, while an operator
    // actively working an outage hears the radio trying for a good while.
    private val audibleUntilMs: Long = DEFAULT_AUDIBLE_UNTIL_MS,
) {
    init {
        // A threshold below 1 would alert on the very first failure,
        // defeating the "brief blips stay silent" contract and making
        // the utility's behavior surprising for any future caller. Fail
        // fast on misconfiguration rather than silently degrading.
        require(alertAfterAttempts >= 1) {
            "alertAfterAttempts must be >= 1, was $alertAfterAttempts"
        }
        require(audibleUntilMs >= 0) {
            "audibleUntilMs must be >= 0, was $audibleUntilMs"
        }
    }

    private var consecutiveFailures: Int = 0
    private var alerted: Boolean = false

    // Wall clock of the first failure in the current outage; the base
    // for the [audibleUntilMs] window. Meaningless while
    // [consecutiveFailures] is 0.
    private var outageStartMs: Long = 0L

    /** What the audio layer should play for one failed attempt. At most
     *  one of the two is ever true — see the class doc. */
    data class Decision(
        val playAlert: Boolean,
        val playChirp: Boolean,
    )

    /**
     * Record a failed connection event — either the initial unexpected
     * disconnect or a subsequent failed reconnect attempt. [nowMs] is
     * the wall clock, injected so the audible-window logic is testable.
     *
     * [Decision.playAlert] is true EXACTLY ONCE per outage: on the
     * attempt that first reaches [alertAfterAttempts].
     * [Decision.playChirp] is true on every other attempt within
     * [audibleUntilMs] of the outage start, and false forever after
     * that — including on attempts that keep failing hours later.
     */
    @Synchronized
    fun onAttemptFailed(nowMs: Long = System.currentTimeMillis()): Decision {
        if (consecutiveFailures == 0) {
            outageStartMs = nowMs
        }
        consecutiveFailures += 1
        val alertNow = !alerted && consecutiveFailures >= alertAfterAttempts
        if (alertNow) {
            alerted = true
        }
        val withinAudibleWindow = (nowMs - outageStartMs) < audibleUntilMs
        return Decision(
            playAlert = alertNow,
            playChirp = !alertNow && withinAudibleWindow,
        )
    }

    /**
     * Record a clean (re)connect. Returns true if this resolves an
     * outage that had already produced at least one failed attempt —
     * i.e. whether the recovery chime is warranted. A first-time
     * connect, or a reconnect after an operator-initiated disconnect,
     * returns false and stays silent: nothing was broken, so nothing
     * was fixed.
     *
     * Re-arms the tracker either way; the next outage counts from zero.
     */
    @Synchronized
    fun onReconnected(): Boolean {
        val recoveredFromOutage = consecutiveFailures > 0
        resetLocked()
        return recoveredFromOutage
    }

    /**
     * Re-arm the tracker on a clean (re)connect — or when a fresh
     * connect is initiated. The next outage starts counting from zero
     * and can alert again.
     */
    @Synchronized
    fun reset() {
        resetLocked()
    }

    // Shared body for reset() and onReconnected(). Kotlin's @Synchronized
    // is reentrant (it's a monitor on `this`), so onReconnected() could
    // legally call reset() directly — but going through the private
    // non-synchronized body keeps the lock discipline obvious to the
    // next reader rather than resting on reentrancy.
    private fun resetLocked() {
        consecutiveFailures = 0
        alerted = false
        outageStartMs = 0L
    }

    companion object {
        const val DEFAULT_ALERT_THRESHOLD: Int = 3

        /** 10 minutes. Past this an outage is presumed unattended (or
         *  the operator has long since got the message) and the retry
         *  chirp would be noise for nobody. Retries themselves do NOT
         *  stop here — only the sound does. */
        const val DEFAULT_AUDIBLE_UNTIL_MS: Long = 10 * 60 * 1000L
    }
}
