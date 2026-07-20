package com.atakmap.android.xv.transport.multicast

import org.json.JSONObject

/**
 * On-the-wire framing for a channel's multicast leg.
 *
 *   XV_NATIVE       — RTP framing ([RtpFraming]) with optional per-frame
 *                     ChaCha20-Poly1305 ([AeadCodec]) on top. Default for
 *                     XV↔XV channels (including every auto-derived
 *                     failover group — only XV computes the derivation,
 *                     so there is no interop cost to encrypting them).
 *   VX_COMPAT — each UDP datagram is one raw Opus frame. No
 *                     framing, no sequence numbers, no encryption;
 *                     speaker identity is implicit in the source IP.
 *                     Matches VX 1.7.0 voice comms / the ATAK VX
 *                     plugin / OpenVLM. Requires an operator-pinned
 *                     group + port (typically 224.0.0.1:5007 or a
 *                     talkgroup in 225.41.1.1–.5) because those
 *                     deployments chose their addresses on their own
 *                     scheme — the XV derivation can't find them.
 */
enum class WireFormat { XV_NATIVE, VX_COMPAT }

/**
 * Per-channel encryption posture for the multicast leg.
 *
 *   REQUIRED  — frames are AEAD-wrapped or not sent; cleartext RX is
 *               dropped. For channels where privacy beats availability.
 *   PREFERRED — encrypt when a channel key is live, fall back to clear
 *               RTP when keying hasn't converged. Degrades to degraded
 *               comms, never to silence. Default.
 *   CLEARTEXT — never encrypt. Forced for [WireFormat.VX_COMPAT]
 *               (the interop format has no crypto layer at all) and
 *               surfaced in the UI as a clear-traffic badge.
 */
enum class CryptoPolicy { REQUIRED, PREFERRED, CLEARTEXT }

/**
 * When the multicast leg of a channel carries traffic.
 *
 *   OFF      — no multicast leg for this channel.
 *   FAILOVER — leg joined for RX whenever on a multicast-capable
 *              network (that's how Mumble-down is survivable at all);
 *              TX fires on multicast only while the failover policy
 *              says Mumble is unhealthy. Default posture.
 *   ALWAYS   — active-active: TX on both Mumble and multicast for every
 *              burst. Zero-gap failover at the cost of doubled TX.
 */
enum class MulticastMode { OFF, FAILOVER, ALWAYS }

/**
 * Operator-visible multicast configuration for one channel. A channel
 * with NO stored config is not "multicast off" — it gets [defaultFor]
 * (auto-derived FAILOVER leg) whenever the global mesh-voice toggle is
 * on. Stored configs are overrides: pinning a group/port, switching to
 * the VX-compat wire format, or forcing the mode.
 *
 * [channelName] is stored canonicalized
 * ([MulticastGroupDerivation.canonicalChannelName]) so lookups can't
 * fork on case or Unicode-encoding differences in operator-typed names.
 */
data class ChannelMulticastConfig(
    val channelName: String,
    val mode: MulticastMode = MulticastMode.FAILOVER,
    val wireFormat: WireFormat = WireFormat.XV_NATIVE,
    val cryptoPolicy: CryptoPolicy = CryptoPolicy.PREFERRED,
    /** Operator-pinned group address; null ⇒ derive via v1 hash. */
    val pinnedGroup: String? = null,
    /** Operator-pinned UDP port; null ⇒ derive via v1 hash. */
    val pinnedPort: Int? = null,
    /** Optional secondary patch group address for external interop. */
    val patchGroup: String? = null,
    /** Optional secondary patch UDP port. */
    val patchPort: Int? = null,
    /** Wire format for the secondary patch channel. */
    val patchWireFormat: WireFormat = WireFormat.VX_COMPAT,
    /** Crypto policy for the secondary patch channel. */
    val patchCryptoPolicy: CryptoPolicy = CryptoPolicy.CLEARTEXT,
) {
    /**
     * Human-readable reason this config is unusable, or null if valid.
     * UI surfaces the string next to the offending field; transport
     * construction treats non-null as "leg stays down".
     */
    fun validate(): String? {
        if (channelName.isBlank()) return "channel name is blank"
        if ((pinnedGroup == null) != (pinnedPort == null)) {
            return "pinned group and port must be set together"
        }
        if (pinnedPort != null && pinnedPort !in 1024..65535) {
            return "pinned port $pinnedPort outside 1024..65535"
        }
        if (pinnedGroup != null && !isIpv4MulticastAddress(pinnedGroup)) {
            return "pinned group '$pinnedGroup' is not an IPv4 multicast address (224.0.0.1—239.255.255.255)"
        }
        if (wireFormat == WireFormat.VX_COMPAT) {
            if (pinnedGroup == null) {
                return "VX-compat channels need an explicit group + port " +
                    "(the interop deployment chose them; XV can't derive them)"
            }
            if (cryptoPolicy != CryptoPolicy.CLEARTEXT) {
                return "VX-compat wire format has no encryption layer; crypto policy must be CLEARTEXT"
            }
        }
        if ((patchGroup == null) != (patchPort == null)) {
            return "patch group and port must be set together"
        }
        if (patchPort != null && patchPort !in 1024..65535) {
            return "patch port $patchPort outside 1024..65535"
        }
        if (patchGroup != null && !isIpv4MulticastAddress(patchGroup)) {
            return "patch group '$patchGroup' is not an IPv4 multicast address"
        }
        if (patchGroup != null && patchWireFormat == WireFormat.VX_COMPAT && patchCryptoPolicy != CryptoPolicy.CLEARTEXT) {
            return "VX-compat patch format has no encryption layer; patch crypto policy must be CLEARTEXT"
        }
        return null
    }

    /**
     * The endpoint this config resolves to: the operator's pin when
     * present, else the v1 derivation. Callers must have a non-null
     * [validate] handled before asking.
     */
    fun resolveEndpoint(serverIdentity: ServerIdentity): MulticastEndpoint =
        if (pinnedGroup != null && pinnedPort != null) {
            MulticastEndpoint(pinnedGroup, pinnedPort)
        } else {
            MulticastGroupDerivation.derive(serverIdentity, channelName)
        }

    /**
     * Canonical JSON for persistence and for embedding in a comms-plan
     * bundle. Field order is FIXED (schema v1) so the same config
     * always emits byte-identical JSON — comms-plan signatures depend
     * on that. Null pins are omitted entirely.
     */
    fun toJson(): String {
        val sb = StringBuilder()
        sb.append('{')
        sb.append("\"channel\":").append(JSONObject.quote(channelName))
        sb.append(",\"mode\":\"").append(mode.name).append('"')
        sb.append(",\"wireFormat\":\"").append(wireFormat.name).append('"')
        sb.append(",\"cryptoPolicy\":\"").append(cryptoPolicy.name).append('"')
        if (pinnedGroup != null && pinnedPort != null) {
            sb.append(",\"group\":").append(JSONObject.quote(pinnedGroup))
            sb.append(",\"port\":").append(pinnedPort)
        }
        if (patchGroup != null && patchPort != null) {
            sb.append(",\"patchGroup\":").append(JSONObject.quote(patchGroup))
            sb.append(",\"patchPort\":").append(patchPort)
            sb.append(",\"patchWireFormat\":\"").append(patchWireFormat.name).append('"')
            sb.append(",\"patchCryptoPolicy\":\"").append(patchCryptoPolicy.name).append('"')
        }
        sb.append('}')
        return sb.toString()
    }

    companion object {
        /**
         * The zero-config default (operator decision D3, 2026-07-14):
         * every joined Mumble channel gets an auto-derived encrypted
         * failover leg when mesh voice is enabled globally.
         */
        fun defaultFor(channelName: String): ChannelMulticastConfig =
            ChannelMulticastConfig(
                channelName = MulticastGroupDerivation.canonicalChannelName(channelName),
            )

        /**
         * Parse one config from its JSON form. Returns null on any
         * structural problem (missing field, unknown enum value) —
         * a stored config written by a NEWER XV with an enum this
         * build doesn't know must degrade to "no override" rather
         * than crash channel setup.
         */
        fun fromJson(json: String): ChannelMulticastConfig? =
            try {
                val o = JSONObject(json)
                ChannelMulticastConfig(
                    channelName = o.getString("channel"),
                    mode = MulticastMode.valueOf(o.getString("mode")),
                    wireFormat = WireFormat.valueOf(o.getString("wireFormat")),
                    cryptoPolicy = CryptoPolicy.valueOf(o.getString("cryptoPolicy")),
                    pinnedGroup = if (o.has("group")) o.getString("group") else null,
                    pinnedPort = if (o.has("port")) o.getInt("port") else null,
                    patchGroup = if (o.has("patchGroup")) o.getString("patchGroup") else null,
                    patchPort = if (o.has("patchPort")) o.getInt("patchPort") else null,
                    patchWireFormat = if (o.has(
                            "patchWireFormat"
                        )
                    ) {
                        WireFormat.valueOf(o.getString("patchWireFormat"))
                    } else {
                        WireFormat.VX_COMPAT
                    },
                    patchCryptoPolicy = if (o.has(
                            "patchCryptoPolicy"
                        )
                    ) {
                        CryptoPolicy.valueOf(o.getString("patchCryptoPolicy"))
                    } else {
                        CryptoPolicy.CLEARTEXT
                    },
                )
            } catch (_: Exception) {
                null
            }

        private fun isIpv4MulticastAddress(addr: String): Boolean {
            val parts = addr.split(".")
            if (parts.size != 4) return false
            val octets = parts.map { it.toIntOrNull() ?: return false }
            if (octets.any { it !in 0..255 }) return false
            if (octets[0] == 224 && octets[1] == 0 && octets[2] == 0 && octets[3] == 0) return false
            return octets[0] in 224..239
        }
    }
}
