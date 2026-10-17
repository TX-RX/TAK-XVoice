package com.atakmap.android.xv.audio

import android.util.Log

// Status-tone kinds that XV plays in response to non-voice events
// (channel join/leave, link state, etc). Distinct from TptTone (the
// per-PTT permit cue) and the interrupt chirp (peer-talking cut-in).
enum class StatusToneKind {
    CHANNEL_JOIN,
    CHANNEL_LEAVE,

    // "Voice Interface Lost" — the transport (Mumble or multicast) has
    // disconnected unexpectedly. Triple descending beep — universal
    // alert pattern.
    WARNING_VOICE_LOST,

    // "Channel Disconnected" — a channel join failed or a previously
    // joined channel became unreachable (server-side ban, network
    // partition, channel deleted).
    WARNING_CHANNEL_LOST,

    // Phase E: caller-side ringback while waiting for the callee to
    // answer a direct call. Plays on the caller's local audio path
    // (system Telecom UI handles callee-side ring tone separately).
    // Looped on a 3s timer until the call resolves.
    CALL_RINGBACK,

    // Phase E: brief "call could not be completed" busy beep — fires
    // when the callee declines, the call times out, or the server
    // refuses the temp channel.
    CALL_BUSY,

    // "Still trying" — one quiet blip per failed reconnect attempt while
    // the outage is young. Gated by ReconnectNotificationTracker, which
    // stops it after the audible window even though the ladder keeps
    // retrying. Deliberately NOT subject to the shared status cooldown
    // (see DEFAULT_PER_KIND_COOLDOWN_MS).
    RECONNECT_RETRY,

    // "You're back" — fires once when a reconnect succeeds after a real
    // outage. Not played for a first-time connect.
    RECONNECT_RECOVERED,
}

// Plays status tones with a per-kind cooldown so flapping events
// (server reconnect storms, repeated joinChannel calls) don't blast
// the operator with back-to-back beeps. Each kind has its own clock
// — a JOIN doesn't suppress a subsequent LEAVE.
//
// Disabled by [enabled] returning false; the lambda is read on every
// call so live preference toggles take effect immediately. Ditto the
// cooldown ms — keeps configuration in one place at the call site.
class StatusTones(
    private val tptPlayer: TptPlayer,
    private val enabled: () -> Boolean = { true },
    private val cooldownMs: () -> Long = { DEFAULT_COOLDOWN_MS },
    // Per-kind cooldown overrides for kinds whose cadence is already
    // governed by a caller-side state machine, where the shared cooldown
    // would silently eat legitimate plays. Falls back to [cooldownMs] for
    // any kind not listed.
    private val perKindCooldownMs: Map<StatusToneKind, Long> = DEFAULT_PER_KIND_COOLDOWN_MS,
) {
    private val lastPlayedMs = java.util.EnumMap<StatusToneKind, Long>(StatusToneKind::class.java)

    /**
     * Play a status tone, optionally forcing the SCO route. Set
     * [useScoRoute] to true when SCO is already up at the call site
     * (mid-TX events) so the tone lands on the operator's speakermic
     * instead of leaking to the phone speaker — the latter is the
     * default because most status events fire when SCO is down and
     * pinning to BT_SCO would route to silence.
     */
    @Synchronized
    fun play(
        kind: StatusToneKind,
        useScoRoute: Boolean = false,
    ) {
        if (!enabled()) return
        val now = System.currentTimeMillis()
        val last = lastPlayedMs[kind] ?: 0L
        val window = perKindCooldownMs[kind] ?: cooldownMs()
        if (last > 0 && now - last < window) {
            Log.d(TAG, "status $kind suppressed — within ${window}ms cooldown (${now - last}ms since last)")
            return
        }
        lastPlayedMs[kind] = now
        Log.i(TAG, "status tone $kind (useScoRoute=$useScoRoute)")
        tptPlayer.playStatus(kind, useScoRoute = useScoRoute)
    }

    companion object {
        private const val TAG = "XvStatusTones"

        // Matches the VX 2.1 default — 5 s between same-kind status tones.
        // Long enough to absorb a server reconnect cycle (which often
        // re-emits joinChannel for every existing membership) without
        // beeping the operator's ears off.
        const val DEFAULT_COOLDOWN_MS: Long = 5_000L

        // RECONNECT_RETRY opts out of the shared cooldown. Its cadence is
        // the reconnect ladder's, whose first gaps (1s, 2s, 4s) are all
        // inside the 5 s window — the generic cooldown would mute exactly
        // the early attempts an operator most wants to hear, and the
        // effect would look like a dropped tone rather than a policy.
        // ReconnectNotificationTracker is the sole authority on when this
        // chirp plays and when it stops.
        val DEFAULT_PER_KIND_COOLDOWN_MS: Map<StatusToneKind, Long> =
            mapOf(StatusToneKind.RECONNECT_RETRY to 0L)
    }
}
