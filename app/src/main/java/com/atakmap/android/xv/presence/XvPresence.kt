package com.atakmap.android.xv.presence

/**
 * One entry per XV-running peer we've seen via CoT. Built from a
 * `<__xv>` detail on the peer's self-CoT. Cached in [XvPresenceRegistry]
 * keyed by ATAK device UID.
 */
data class XvPresence(
    val deviceUid: String,
    val version: String,
    val capabilities: Set<String>,
    val certFingerprint: String?,
    val server: String?,
    val channels: List<XvChannel>,
    val lastSeenMs: Long,
    // Phase E: peer's currently-active Mumble session integer, used
    // to address a TextMessage directly to them. Null on peers that
    // either don't publish the field (older XV, VX) or aren't connected
    // to Mumble right now. When null, callers fall back to "scan the
    // joined channel's user roster by callsign."
    val mumbleSession: Int? = null,
    // Operator's display callsign as published in `<__xv callsign>`.
    // Null when the peer hasn't bumped to the callsign schema yet —
    // call UI falls back to the (less-friendly) deviceUid in that case.
    val callsign: String? = null,
)

data class XvChannel(
    val name: String,
    val id: Int,
    val keyEpoch: Int = 0,
)
