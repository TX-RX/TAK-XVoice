package com.atakmap.android.xv.provisioning

import com.atakmap.android.xv.transport.multicast.AeadCodec
import com.atakmap.android.xv.transport.multicast.CryptoPolicy
import com.atakmap.android.xv.transport.multicast.MulticastGroupDerivation
import com.atakmap.android.xv.transport.multicast.MulticastMode
import com.atakmap.android.xv.transport.multicast.ServerIdentity
import com.atakmap.android.xv.transport.multicast.WireFormat
import java.util.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RandomChannelFactoryTest {
    @Test
    fun `generates a valid named channel with a 32-byte key`() {
        val gen = RandomChannelFactory.generate(nameRng = Random(1), keyRng = fixedKeyRng())
        assertTrue("name is Word-NN", gen.name.matches(Regex("[A-Za-z]+-\\d\\d")))
        assertEquals(AeadCodec.KEY_BYTES, gen.preSharedKey.size)
        assertNull("generated config must be valid", gen.config.validate())
    }

    @Test
    fun `default posture is failover, xv-native, encrypted-capable, unpinned`() {
        val gen = RandomChannelFactory.generate(nameRng = Random(1), keyRng = fixedKeyRng())
        assertEquals(MulticastMode.FAILOVER, gen.config.mode)
        assertEquals(WireFormat.XV_NATIVE, gen.config.wireFormat)
        // PREFERRED + the installed key ⇒ encrypted in practice, degrades
        // to clear rather than silence if a peer is missing the key.
        assertEquals(CryptoPolicy.PREFERRED, gen.config.cryptoPolicy)
        assertNull("no manual pin — endpoint derives from the name", gen.config.pinnedGroup)
        assertNull(gen.config.pinnedPort)
    }

    @Test
    fun `config name is canonicalized so lookups cannot fork on spelling`() {
        val gen = RandomChannelFactory.generate(nameRng = Random(2), keyRng = fixedKeyRng())
        assertEquals(MulticastGroupDerivation.canonicalChannelName(gen.name), gen.config.channelName)
    }

    @Test
    fun `endpoint derives deterministically from the generated name`() {
        val gen = RandomChannelFactory.generate(nameRng = Random(3), keyRng = fixedKeyRng())
        val server = ServerIdentity.fromHostname("tak.example.com")
        // The whole point: no pin, yet a concrete endpoint any teammate
        // re-derives from the same name.
        assertEquals(
            MulticastGroupDerivation.derive(server, gen.name),
            gen.config.resolveEndpoint(server),
        )
    }

    @Test
    fun `name generation is deterministic under a fixed rng`() {
        val a = RandomChannelFactory.generate(nameRng = Random(42), keyRng = fixedKeyRng())
        val b = RandomChannelFactory.generate(nameRng = Random(42), keyRng = fixedKeyRng())
        assertEquals(a.name, b.name)
    }

    @Test
    fun `distinct rng seeds tend to produce distinct names`() {
        val names = (0 until 50).map { RandomChannelFactory.generate(nameRng = Random(it.toLong()), keyRng = fixedKeyRng()).name }
        // Not a strict guarantee, but with 24×90 combos, 50 draws should
        // yield mostly-unique names — guards against a stuck generator.
        assertTrue("expected variety in generated names", names.toSet().size >= 40)
    }

    @Test
    fun `each call draws a fresh key`() {
        val rng = java.security.SecureRandom()
        val a = RandomChannelFactory.generate(keyRng = rng)
        val b = RandomChannelFactory.generate(keyRng = rng)
        assertNotEquals(
            a.preSharedKey.toList(),
            b.preSharedKey.toList(),
        )
    }

    @Test
    fun `generated channel round-trips through a comms plan carrier`() {
        val gen = RandomChannelFactory.generate(nameRng = Random(7), keyRng = fixedKeyRng())
        val plan =
            CommsPlan(
                planId = "p1",
                name = "adhoc",
                createdAtMs = 1_750_000_000_000L,
                channels = listOf(CommsPlan.Channel(gen.name, gen.config, gen.preSharedKey)),
            )
        val text = CommsPlanCarrier.encodeLocked(plan, "field-phrase".toCharArray())
        val back = CommsPlanCarrier.decode(text, "field-phrase".toCharArray())
        assertEquals(plan, back)
    }

    // Deterministic 32-byte "key" source so name-focused assertions are
    // stable; real callers pass a SecureRandom.
    private fun fixedKeyRng(): java.security.SecureRandom =
        object : java.security.SecureRandom() {
            override fun nextBytes(bytes: ByteArray) {
                for (i in bytes.indices) bytes[i] = 0x11
            }
        }
}
