package com.atakmap.android.xv.transport.multicast

/**
 * Canonical server-identity string for the multicast group derivation
 * ([MulticastGroupDerivation]). Two devices must arrive at byte-identical
 * identity strings for the same deployment or they derive different
 * multicast groups and never hear each other — so all normalization
 * lives here, in one place, instead of at call sites.
 *
 * The v1 derivation uses the **server hostname** (operator decision
 * 2026-07-14), not the TLS cert fingerprint: a cert renewal would
 * silently fork the derivation mid-deployment (devices holding the old
 * cert derive different groups than devices that re-enrolled), which is
 * exactly the kind of invisible field break the deterministic scheme
 * exists to avoid. The hostname survives rotation. The residual risk —
 * two unrelated deployments sharing a hostname AND a channel name AND
 * an L2 network — is accepted; [fromCertFingerprint] is kept for a
 * future derivation version if that trade ever reverses.
 */
data class ServerIdentity(
    val value: String,
) {
    init {
        require(value.isNotBlank()) { "server identity must not be blank" }
    }

    companion object {
        /**
         * Build from a server hostname / address as an operator (or a
         * TAK server profile) might have written it. Normalization:
         * trim, lowercase, strip a `scheme://` prefix, strip any path,
         * strip a `:port` suffix, strip a trailing FQDN dot.
         *
         * IPv6: a bracketed `[addr]:port` form unwraps to the bare
         * address. An unbracketed string with multiple colons is
         * treated as a bare IPv6 address and left intact (stripping
         * after the last colon would corrupt it).
         */
        fun fromHostname(host: String): ServerIdentity {
            var h = host.trim().lowercase()
            val schemeIdx = h.indexOf("://")
            if (schemeIdx >= 0) h = h.substring(schemeIdx + 3)
            h = h.substringBefore('/')
            h =
                when {
                    h.startsWith("[") -> h.substringAfter('[').substringBefore(']')
                    h.count { it == ':' } == 1 -> h.substringBefore(':')
                    else -> h // bare IPv6 (2+ colons) or plain host (0 colons)
                }
            h = h.trimEnd('.')
            require(h.isNotBlank()) { "no hostname in '$host'" }
            return ServerIdentity(h)
        }

        /**
         * Build from a lowercase-hex SHA-256 cert fingerprint. Unused
         * by the v1 derivation (see class doc) but kept as the
         * alternative identity root for a future `xv-mcast-v2`.
         */
        fun fromCertFingerprint(fp: String): ServerIdentity {
            val cleaned = fp.trim().lowercase().replace(":", "").replace(" ", "")
            require(cleaned.matches(Regex("[0-9a-f]{64}"))) {
                "expected 64 hex chars of SHA-256 fingerprint"
            }
            return ServerIdentity(cleaned)
        }
    }
}
