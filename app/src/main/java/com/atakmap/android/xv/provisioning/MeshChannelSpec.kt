package com.atakmap.android.xv.provisioning

import com.atakmap.android.xv.transport.multicast.ChannelMulticastConfig
import com.atakmap.android.xv.transport.multicast.CryptoPolicy
import com.atakmap.android.xv.transport.multicast.MulticastGroupDerivation
import com.atakmap.android.xv.transport.multicast.MulticastMode
import com.atakmap.android.xv.transport.multicast.WireFormat

/**
 * Turns operator-typed strings from the "configure a channel manually"
 * form (provisioning path 3) into a validated [ChannelMulticastConfig].
 * This is the escape hatch from the zero-config default: name a channel
 * yourself, and — only if you need to interoperate with an external
 * system (OpenMANET, an ATAK VX talkgroup) — pin its group/port, wire
 * format, and crypto policy.
 *
 * The parsing + cross-field validation (port range, group/port must be
 * pinned together, OpenMANET-compat demands a pin + cleartext) is the
 * error-prone part, so it lives here as pure logic the UI just feeds
 * strings into. [build] never throws — it returns either a config or an
 * operator-readable [Result.error].
 *
 * Leaving group + port blank keeps the deterministic derivation (no
 * manual pinning — the whole point), so the common "I just want to name
 * it" case still needs zero address entry.
 */
object MeshChannelSpec {
    data class Result(
        val config: ChannelMulticastConfig?,
        val error: String?,
        /** Generate + install a fresh pre-shared key for this channel? True for encrypted postures. */
        val autoKey: Boolean,
    ) {
        companion object {
            fun ok(
                config: ChannelMulticastConfig,
                autoKey: Boolean,
            ) = Result(config, null, autoKey)

            fun err(message: String) = Result(null, message, false)
        }
    }

    /**
     * @param name channel name (required). Blank ⇒ error.
     * @param group pinned multicast group, or null/blank to derive.
     * @param port pinned UDP port as text, or null/blank to derive.
     * @param wireFormat XV-native or OpenMANET-compat.
     * @param cryptoPolicy encryption posture. CLEARTEXT ⇒ no key generated.
     */
    fun build(
        name: String,
        group: String?,
        port: String?,
        wireFormat: WireFormat,
        cryptoPolicy: CryptoPolicy,
    ): Result {
        if (name.isBlank()) return Result.err("Channel name is required.")

        val groupTrim = group?.trim().orEmpty()
        val portTrim = port?.trim().orEmpty()
        val pinGroup = groupTrim.isNotEmpty()
        val pinPort = portTrim.isNotEmpty()
        if (pinGroup != pinPort) {
            return Result.err("Pin the group and port together, or leave both blank to derive them automatically.")
        }

        var pinnedGroup: String? = null
        var pinnedPort: Int? = null
        if (pinGroup) {
            val parsedPort =
                portTrim.toIntOrNull()
                    ?: return Result.err("Port “$portTrim” is not a number.")
            pinnedGroup = groupTrim
            pinnedPort = parsedPort
        }

        val config =
            ChannelMulticastConfig(
                // Canonicalized like ChannelMulticastConfig.defaultFor so
                // config lookups and the derivation can't fork on case /
                // Unicode-encoding differences in an operator-typed name.
                channelName = MulticastGroupDerivation.canonicalChannelName(name),
                mode = MulticastMode.FAILOVER,
                wireFormat = wireFormat,
                cryptoPolicy = cryptoPolicy,
                pinnedGroup = pinnedGroup,
                pinnedPort = pinnedPort,
            )
        // Delegate cross-field rules (port range, class-D group,
        // OpenMANET-compat needs a pin + cleartext) to the config itself,
        // so this stays the one source of truth.
        config.validate()?.let { return Result.err(it) }

        return Result.ok(config, autoKey = cryptoPolicy != CryptoPolicy.CLEARTEXT)
    }
}
