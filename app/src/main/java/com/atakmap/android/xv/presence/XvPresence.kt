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
    // Bridge election state — populated from the local-only `<__xv_bridge>`
    // CoT, NOT from the global server-bound self-ping. Null = unknown (peer
    // hasn't published a bridge CoT yet, or it has expired).
    val mumbleConnected: Boolean? = null,
    val isBridging: Boolean? = null,
    val bridgeLastSeenMs: Long? = null,
)

data class XvChannel(
    val name: String,
    val id: Int,
    val keyEpoch: Int = 0,
)

/**
 * Per-channel encryption policy. Controls how the plugin handles
 * unencrypted legacy VX users on this channel.
 *
 * - [ENCRYPTED_ONLY]   – (default) strictly reject any unencrypted
 *                        participant. Legacy users are silently ignored.
 * - [PREFER_ENCRYPTION] – normally encrypted. If a legacy VX user is
 *                         detected on this channel, raise a persistent
 *                         notification asking the operator to Accept or
 *                         Reject downgrading to [CLEARTEXT].
 * - [CLEARTEXT]        – VX-compatible mode. Control packets are
 *                        suppressed on the multicast group (preventing
 *                        the "screeching static" that legacy radios hear
 *                        when they try to decode our beacon frames as
 *                        audio). Bridge election uses local-only CoT.
 */
enum class ChannelCryptoPolicy {
    ENCRYPTED_ONLY,
    PREFER_ENCRYPTION,
    CLEARTEXT,
}
