package com.atakmap.android.xv.transport.multicast

import java.security.MessageDigest

/**
 * Deterministic mapping `(serverCertFp, channelId) → (groupAddress, port)` for
 * Phase 8's encrypted multicast failover. Two peers sharing the same TAK server
 * (same cert fingerprint) and the same Mumble channel id will compute the same
 * group + port without coordination, so failover doesn't depend on a
 * distributed broker.
 *
 * Why SHA-256 of `serverCertFp || channelId` (not String.hashCode):
 *   - `String.hashCode` is 32-bit and ART-stability-dependent. Two channels
 *     colliding on hashCode would silently land on the same multicast group
 *     and the AEAD layer would throw the audio out as "wrong key" — a
 *     debugging nightmare. SHA-256 puts the collision probability past where
 *     anyone will ever notice.
 *   - Including `serverCertFp` (not just the hostname) means cross-server
 *     channels with the same name don't collide.
 *
 * Layout: take the low 24 bits of the SHA-256 digest. Bytes 0..1 form the
 * last two octets of `239.42.X.Y` (the 239.42/16 admin-scoped range — IETF
 * reserves 239/8 for organization-local multicast); byte 2 mod 0xFFF chooses
 * a port in `6000..10095`.
 */
object MulticastGroupDerivation {
    /**
     * Derive the multicast group address (e.g. "239.42.123.45") and UDP port
     * for a given (server cert fingerprint, channel id) pair. Used for
     * Mumble-primary channels where the group is keyed to the server identity.
     *
     * @param serverCertFp lowercase hex SHA-256 of the server's leaf cert.
     *   We require it lowercase (not enforced — caller's responsibility) so
     *   case differences in the fp don't fork derivations.
     * @param channelId the Mumble channel numeric id, treated as a 32-bit
     *   value. Stable across reconnects because Mumble assigns ids per
     *   server, not per session.
     */
    fun derive(
        serverCertFp: String,
        channelId: Int,
    ): MulticastEndpoint {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(serverCertFp.toByteArray(Charsets.UTF_8))
        // Big-endian channelId so byte order is platform-independent.
        md.update(
            byteArrayOf(
                (channelId ushr 24 and 0xFF).toByte(),
                (channelId ushr 16 and 0xFF).toByte(),
                (channelId ushr 8 and 0xFF).toByte(),
                (channelId and 0xFF).toByte(),
            ),
        )
        val digest = md.digest()

        val octet3 = digest[0].toInt() and 0xFF
        val octet4 = digest[1].toInt() and 0xFF
        val portOffset = digest[2].toInt() and 0xFFF
        return MulticastEndpoint(
            groupAddress = "239.42.$octet3.$octet4",
            port = PORT_BASE + portOffset,
        )
    }

    /**
     * Derive the multicast group address and UDP port for a **local channel**
     * identified only by its name. Used when there is no TAK server cert
     * fingerprint — e.g. a user-created local channel such as "TAK-01".
     *
     * The derivation uses a fixed domain-separator ("xv.local.ch:") prepended
     * to the normalized (lowercase, trimmed) channel name. This ensures the
     * local-channel namespace is disjoint from the server-keyed namespace
     * produced by [derive] — an attacker-controlled server cert fingerprint
     * cannot be crafted to collide with a named local channel.
     *
     * Group/port are fully deterministic: two devices that agree on a channel
     * name compute the same endpoint without any out-of-band coordination.
     * Encryption credentials (if enabled) still need to be shared separately
     * via the `.xvchannel` artifact.
     *
     * @param channelName the canonical channel name (e.g. "TAK-01"). Case is
     *   normalized to lowercase before derivation so "tak-01" and "TAK-01"
     *   resolve to the same group.
     */
    fun deriveFromName(channelName: String): MulticastEndpoint {
        val normalizedName = channelName.trim().lowercase()
        val md = MessageDigest.getInstance("SHA-256")
        // Domain-separator makes the local-channel namespace disjoint from
        // the server-cert-keyed namespace used by [derive].
        md.update("xv.local.ch:$normalizedName".toByteArray(Charsets.UTF_8))
        val digest = md.digest()

        val octet3 = digest[0].toInt() and 0xFF
        val octet4 = digest[1].toInt() and 0xFF
        val portOffset = digest[2].toInt() and 0xFFF
        return MulticastEndpoint(
            groupAddress = "239.42.$octet3.$octet4",
            port = PORT_BASE + portOffset,
        )
    }

    const val PORT_BASE: Int = 6000
}

data class MulticastEndpoint(
    val groupAddress: String,
    val port: Int,
)
