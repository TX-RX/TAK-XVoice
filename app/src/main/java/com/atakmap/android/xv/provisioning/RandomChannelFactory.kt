package com.atakmap.android.xv.provisioning

import com.atakmap.android.xv.transport.multicast.AeadCodec
import com.atakmap.android.xv.transport.multicast.ChannelMulticastConfig
import java.security.SecureRandom
import java.util.Random

/**
 * The zero-config "create a channel" engine (provisioning path 2): the
 * operator taps one button and gets a ready-to-use, encrypted channel
 * with no IP, port, or key to enter.
 *
 * How it stays config-free: the multicast group + port derive
 * deterministically from the channel *name*
 * ([com.atakmap.android.xv.transport.multicast.MulticastGroupDerivation]),
 * so "generate a channel" reduces to "generate a name" — the endpoint
 * follows for free, and the same name re-derives the same endpoint on
 * every teammate's device. A fresh 32-byte pre-shared key is generated
 * alongside so the channel is encrypted from the first burst; sharing
 * the channel means sharing that key (via a [CommsPlan]), never an
 * address.
 *
 * The name is a call-friendly `Word-NN` (e.g. "Falcon-73") drawn from a
 * curated word list — easy to read aloud over the air and to type if a
 * teammate wants to join by name rather than by shared plan. Collision
 * odds across a small team are low, and even a collision is benign: two
 * channels with the same name simply derive the same group (the
 * derivation's existing property), and the per-channel key keeps
 * unrelated traffic apart.
 *
 * Pure + deterministic under an injected RNG, so the whole generator is
 * unit-tested off-device.
 */
object RandomChannelFactory {
    /**
     * A freshly generated channel: the display [name], its resolved
     * [config] (auto-derived endpoint, encrypted-by-default posture),
     * and the [preSharedKey] that makes it encrypted. The caller
     * persists the config, installs the key into the mesh registry, and
     * joins — then optionally shares all three as a [CommsPlan].
     */
    data class GeneratedChannel(
        val name: String,
        val config: ChannelMulticastConfig,
        val preSharedKey: ByteArray,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is GeneratedChannel) return false
            return name == other.name &&
                config == other.config &&
                preSharedKey.contentEquals(other.preSharedKey)
        }

        override fun hashCode(): Int {
            var h = name.hashCode()
            h = 31 * h + config.hashCode()
            h = 31 * h + preSharedKey.contentHashCode()
            return h
        }
    }

    /**
     * Generate a named, auto-encrypted channel.
     *
     * @param nameRng source for the name pick; injected for tests.
     *   Defaults to a fresh [SecureRandom].
     * @param keyRng source for the 32-byte channel key; defaults to a
     *   fresh [SecureRandom]. Keep this a CSPRNG in production — it is
     *   the channel's only secret.
     */
    fun generate(
        nameRng: Random = SecureRandom(),
        keyRng: SecureRandom = SecureRandom(),
    ): GeneratedChannel {
        val word = WORDS[nameRng.nextInt(WORDS.size)]
        val number = nameRng.nextInt(90) + 10 // 10..99, always two digits
        val name = "$word-$number"
        // defaultFor gives FAILOVER + XV_NATIVE + PREFERRED crypto. With
        // the key below installed, PREFERRED encrypts every frame; if a
        // teammate is missing the key it degrades to clear rather than
        // to silence, which is the right default for a field channel.
        val config = ChannelMulticastConfig.defaultFor(name)
        val key = AeadCodec.generateChannelKey(keyRng)
        return GeneratedChannel(name = name, config = config, preSharedKey = key)
    }

    // Call-friendly, unambiguous over the air. Deliberately avoids the
    // NATO phonetic set (Alpha/Bravo/…) so a generated channel name is
    // never confused with an operator callsign or a placeholder in docs.
    private val WORDS =
        listOf(
            "Falcon", "Raven", "Otter", "Bison", "Cobra", "Lynx",
            "Osprey", "Jaguar", "Marlin", "Badger", "Heron", "Viper",
            "Condor", "Wolf", "Puma", "Kestrel", "Mako", "Ibex",
            "Gecko", "Orca", "Tusk", "Sable", "Drake", "Wren",
        )
}
