package com.atakmap.android.xv.provisioning

import com.atakmap.android.xv.transport.multicast.AeadCodec
import com.atakmap.android.xv.transport.multicast.ChannelMulticastConfig
import org.json.JSONObject

/**
 * Comms-plan bundle: the canonical provisioning format for multicast
 * channels when there is no TAK server to derive them from (fully
 * offline operation) or when an operator wants to hand a channel set
 * to peers ahead of going dark.
 *
 * SCHEMA v1 IS FROZEN as of Phase A (2026-07-14). Every carrier that
 * ships in Phase C — QR code, text passphrase, NFC NDEF, ATAK Data
 * Package — produces and consumes exactly [toCanonicalJson]'s bytes,
 * so there is one wire format and one ingest path. Schema evolution
 * bumps [SCHEMA_VERSION]; parsers reject versions they don't know
 * (a v2-only plan must fail loudly at import, not half-load).
 *
 * Canonical encoding = UTF-8 JSON with FIXED field order (hand-emitted,
 * not JSONObject.toString(), whose key order is unspecified). Byte
 * stability is load-bearing: the optional Phase C signature and the
 * passphrase-KDF wrapping both operate on these exact bytes.
 *
 * ```json
 * {
 *   "v": 1,
 *   "planId": "<uuid>",
 *   "name": "<operator-visible label>",
 *   "createdAtMs": 1234567890123,
 *   "serverIdentity": "<canonical host, optional>",
 *   "channels": [
 *     {
 *       "displayName": "<picker label>",
 *       "config": { <ChannelMulticastConfig canonical JSON> },
 *       "psk": "<base64url 32 bytes, optional>"
 *     }
 *   ]
 * }
 * ```
 *
 * `psk` is the pre-shared 32-byte channel key enabling
 * [com.atakmap.android.xv.transport.multicast.CryptoPolicy.REQUIRED]
 * offline (no TAK certs → no key election; the plan IS the key
 * distribution). Plans carrying PSKs are secrets — carriers must
 * treat them like credentials, which is why the passphrase carrier
 * wraps the bundle in a KDF-derived AEAD rather than encoding it
 * directly.
 */
data class CommsPlan(
    val planId: String,
    val name: String,
    val createdAtMs: Long,
    /** Canonical server identity for derived channels; null for plans of pinned-only channels. */
    val serverIdentity: String? = null,
    val channels: List<Channel>,
) {
    data class Channel(
        val displayName: String,
        val config: ChannelMulticastConfig,
        /** 32-byte pre-shared channel key, or null for cleartext/derived-key channels. */
        val preSharedKey: ByteArray? = null,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Channel) return false
            return displayName == other.displayName &&
                config == other.config &&
                (
                    (preSharedKey == null && other.preSharedKey == null) ||
                        (preSharedKey != null && other.preSharedKey != null && preSharedKey.contentEquals(other.preSharedKey))
                    )
        }

        override fun hashCode(): Int {
            var h = displayName.hashCode()
            h = 31 * h + config.hashCode()
            h = 31 * h + (preSharedKey?.contentHashCode() ?: 0)
            return h
        }
    }

    /**
     * Structural validity beyond what parsing enforces; null when the
     * plan is importable. Surfaced verbatim in the import UI.
     */
    fun validate(): String? {
        if (planId.isBlank()) return "plan id is blank"
        if (name.isBlank()) return "plan name is blank"
        if (channels.isEmpty()) return "plan carries no channels"
        channels.forEach { ch ->
            if (ch.displayName.isBlank()) return "channel display name is blank"
            ch.config.validate()?.let { return "channel '${ch.displayName}': $it" }
            if (ch.preSharedKey != null && ch.preSharedKey.size != AeadCodec.KEY_BYTES) {
                return "channel '${ch.displayName}': pre-shared key must be ${AeadCodec.KEY_BYTES} bytes"
            }
        }
        return null
    }

    /** Canonical (byte-stable) encoding — see class doc. */
    fun toCanonicalJson(): String {
        val sb = StringBuilder()
        sb.append('{')
        sb.append("\"v\":").append(SCHEMA_VERSION)
        sb.append(",\"planId\":").append(JSONObject.quote(planId))
        sb.append(",\"name\":").append(JSONObject.quote(name))
        sb.append(",\"createdAtMs\":").append(createdAtMs)
        if (serverIdentity != null) {
            sb.append(",\"serverIdentity\":").append(JSONObject.quote(serverIdentity))
        }
        sb.append(",\"channels\":[")
        channels.forEachIndexed { i, ch ->
            if (i > 0) sb.append(',')
            sb.append('{')
            sb.append("\"displayName\":").append(JSONObject.quote(ch.displayName))
            sb.append(",\"config\":").append(ch.config.toJson())
            if (ch.preSharedKey != null) {
                sb.append(",\"psk\":").append(JSONObject.quote(base64Url(ch.preSharedKey)))
            }
            sb.append('}')
        }
        sb.append("]}")
        return sb.toString()
    }

    fun toCanonicalBytes(): ByteArray = toCanonicalJson().toByteArray(Charsets.UTF_8)

    companion object {
        const val SCHEMA_VERSION = 1

        /**
         * Parse a plan from JSON. Throws [IllegalArgumentException]
         * with an operator-readable message on any problem — imports
         * are explicit UI actions, so unlike per-channel config reads
         * a failure here must be loud, not a silent degrade.
         */
        fun fromJson(json: String): CommsPlan {
            val o =
                try {
                    JSONObject(json)
                } catch (e: Exception) {
                    throw IllegalArgumentException("not a comms plan: ${e.message}")
                }
            val v = o.optInt("v", -1)
            require(v == SCHEMA_VERSION) {
                "comms plan schema v$v not supported (this build understands v$SCHEMA_VERSION)"
            }
            val channelsJson = o.optJSONArray("channels") ?: throw IllegalArgumentException("plan has no channels array")
            val channels =
                (0 until channelsJson.length()).map { i ->
                    val c = channelsJson.getJSONObject(i)
                    val cfgJson = c.optJSONObject("config") ?: throw IllegalArgumentException("channel $i has no config")
                    val cfg =
                        ChannelMulticastConfig.fromJson(cfgJson.toString())
                            ?: throw IllegalArgumentException("channel $i config is not parseable by this build")
                    Channel(
                        displayName = c.optString("displayName").ifBlank { cfg.channelName },
                        config = cfg,
                        preSharedKey =
                        c.optString("psk").takeIf { it.isNotBlank() }?.let {
                            try {
                                java.util.Base64.getUrlDecoder().decode(it)
                            } catch (e: IllegalArgumentException) {
                                throw IllegalArgumentException("channel $i psk is not valid base64url")
                            }
                        },
                    )
                }
            val plan =
                CommsPlan(
                    planId = o.optString("planId"),
                    name = o.optString("name"),
                    createdAtMs = o.optLong("createdAtMs", 0L),
                    serverIdentity = o.optString("serverIdentity").takeIf { it.isNotBlank() },
                    channels = channels,
                )
            plan.validate()?.let { throw IllegalArgumentException(it) }
            return plan
        }

        // java.util.Base64 (API 26+, matches minSdk) rather than
        // android.util.Base64 so the canonical encoding is testable on
        // a plain JVM and byte-identical across both runtimes.
        private fun base64Url(bytes: ByteArray): String = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
}
