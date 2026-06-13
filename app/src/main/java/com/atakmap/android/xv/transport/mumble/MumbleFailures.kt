package com.atakmap.android.xv.transport.mumble

import mumble.MumbleProto

// Typed throwables surfaced via TransportListener.onConnectionFailed so
// the reconnect wrapper can classify each failure. Callers that just
// want a textual "voice broke because X" use Throwable.message; the
// reconnect wrapper switches on the concrete subtype.

/** Auth wall, missing cert, banned, or any other Reject type that
 *  retry can't fix. ReconnectingMumbleTransport will NOT auto-retry
 *  these — the operator has to act (re-enroll, swap callsign,
 *  contact admin). */
class FatalMumbleException(
    val rejectType: MumbleProto.Reject.RejectType,
    reason: String?,
) : RuntimeException(buildMessage(rejectType, reason)) {
    companion object {
        private fun buildMessage(
            type: MumbleProto.Reject.RejectType,
            reason: String?,
        ): String = "Mumble REJECT $type${if (!reason.isNullOrBlank()) ": $reason" else ""}"
    }
}

/** Server kicked our session (UserRemove targeting us, possibly with
 *  ban=true). Reconnecting on a kick just gets us kicked again, so
 *  the wrapper treats this as fatal. */
class SelfKickedException(
    val reason: String?,
    val banned: Boolean,
    val byActorSession: Int,
) : RuntimeException(
    "Mumble session ${if (banned) "banned" else "kicked"}" +
        (reason?.let { ": $it" } ?: "") +
        (if (byActorSession != 0) " (by session $byActorSession)" else ""),
)

/** Server says our chosen username is already in use. The wrapper
 *  retries this with a fresh UUID suffix so we don't collide with
 *  our own ghost session. Carries the original reason text for
 *  logging. */
class UsernameInUseException(
    reason: String?,
) : RuntimeException(reason ?: "username already in use")
