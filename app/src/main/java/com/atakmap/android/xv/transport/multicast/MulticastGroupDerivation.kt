package com.atakmap.android.xv.transport.multicast

import java.security.MessageDigest
import java.text.Normalizer

/**
 * Deterministic mapping `(serverIdentity, channelName) → (group, port)`
 * for zero-config multicast failover. Two peers on the same TAK/Mumble
 * server and the same channel independently compute the same multicast
 * group + UDP port with no coordination and no broker, so failover
 * works the moment the Mumble server disappears.
 *
 * v1 specification (version-tagged so the scheme can evolve without
 * forking deployed devices — a v2 would change [VERSION_TAG] and be
 * advertised as a capability, v1 devices keep working among themselves):
 *
 *   H = SHA-256( "xv-mcast-v1|" + serverIdentity + "|" + canon(channelName) )
 *
 *   group = 239.(224 + (H[0] & 0x0F)).H[1].H[2]     — 239.224.0.0/12
 *   port  = 16800 + (be16(H[3..4]) % 100)           — 16800..16899
 *
 * where `canon` is trim + Unicode NFC + lowercase (channel names are
 * operator-typed; "Ops-1" and "ops-1" on two devices must not fork the
 * derivation, and NFC folds composed/decomposed accent encodings).
 *
 * Range choices:
 *   - `239.224.0.0/12` sits inside the IANA organization-local scope
 *     (239/8) but clear of the common `239.255.x.x` SSDP/mDNS zone and
 *     the `239.2.x.x` / `239.42.x.x` ranges used by ATAK SA multicast
 *     and earlier XV prototypes. ~1M usable groups; SHA-256 makes a
 *     same-deployment collision a non-event.
 *   - UDP `16800..16899` is an XV-reserved window above the registered
 *     ports commonly seen on tactical networks and away from Mumble's
 *     64738.
 *   - A group ending `.0.0` is remapped to `.0.1` — some multicast
 *     stacks treat subnet-base-looking groups specially.
 *
 * The channel NAME (not the Mumble numeric channel id) is the input:
 * ids are server-assigned and can change when an admin rebuilds the
 * channel tree, while the name is what operators actually coordinate
 * on. Same reason the identity input is the hostname, not the cert
 * fingerprint — see [ServerIdentity].
 *
 * Why SHA-256 (not String.hashCode): 32-bit hashCode collisions across
 * a channel list are plausible and would land two channels on the same
 * group, where the AEAD layer discards the cross-traffic as "wrong
 * key" — a debugging nightmare. SHA-256 puts that past observability.
 */
object MulticastGroupDerivation {
    /** Canonical input prefix; bump ONLY with a new derivation spec. */
    const val VERSION_TAG = "xv-mcast-v1"

    const val PORT_BASE: Int = 16800
    const val PORT_COUNT: Int = 100

    /**
     * Derive the v1 multicast endpoint for a channel on a server.
     *
     * @param serverIdentity canonical deployment identity — build via
     *   [ServerIdentity.fromHostname].
     * @param channelName the Mumble channel name as displayed;
     *   canonicalized internally, so callers pass it raw.
     */
    fun derive(
        serverIdentity: ServerIdentity,
        channelName: String,
    ): MulticastEndpoint {
        val input = "$VERSION_TAG|${serverIdentity.value}|${canonicalChannelName(channelName)}"
        val d = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        val octet2 = 224 + (d[0].toInt() and 0x0F)
        val octet3 = d[1].toInt() and 0xFF
        var octet4 = d[2].toInt() and 0xFF
        if (octet3 == 0 && octet4 == 0) octet4 = 1
        val portOffset = (((d[3].toInt() and 0xFF) shl 8) or (d[4].toInt() and 0xFF)) % PORT_COUNT
        return MulticastEndpoint(
            groupAddress = "239.$octet2.$octet3.$octet4",
            port = PORT_BASE + portOffset,
        )
    }

    /**
     * The canonical form of a channel name for derivation purposes:
     * trimmed, Unicode-NFC-normalized, locale-neutral lowercase.
     * Exposed so config lookups key channels the same way the
     * derivation does.
     */
    fun canonicalChannelName(name: String): String = Normalizer.normalize(name.trim(), Normalizer.Form.NFC).lowercase()
}

data class MulticastEndpoint(
    val groupAddress: String,
    val port: Int,
)
