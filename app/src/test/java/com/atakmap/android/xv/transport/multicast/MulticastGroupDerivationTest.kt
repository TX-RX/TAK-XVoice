package com.atakmap.android.xv.transport.multicast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MulticastGroupDerivationTest {
    private val serverA = ServerIdentity.fromHostname("tak.example.com")
    private val serverB = ServerIdentity.fromHostname("other.example.net")

    @Test
    fun `derivation is deterministic for the same inputs`() {
        val first = MulticastGroupDerivation.derive(serverA, "Ops-1")
        val second = MulticastGroupDerivation.derive(serverA, "Ops-1")
        assertEquals(first, second)
    }

    @Test
    fun `derived group is inside 239_224_0_0 slash 12`() {
        // /12 on the second octet: 224..239.
        repeat(200) { i ->
            val ep = MulticastGroupDerivation.derive(serverA, "channel-$i")
            val parts = ep.groupAddress.split(".")
            assertEquals("expected 4 octets, got ${ep.groupAddress}", 4, parts.size)
            assertEquals(239, parts[0].toInt())
            assertTrue("octet2 ${parts[1]} outside 224..239", parts[1].toInt() in 224..239)
            assertTrue(parts[2].toInt() in 0..255)
            assertTrue(parts[3].toInt() in 0..255)
        }
    }

    @Test
    fun `derived port stays inside the reserved 16800-16899 window`() {
        repeat(200) { i ->
            val ep = MulticastGroupDerivation.derive(serverA, "channel-$i")
            assertTrue(
                "port ${ep.port} outside window",
                ep.port in MulticastGroupDerivation.PORT_BASE until
                    (MulticastGroupDerivation.PORT_BASE + MulticastGroupDerivation.PORT_COUNT),
            )
        }
    }

    @Test
    fun `channel name is canonicalized - case whitespace and NFC do not fork the derivation`() {
        val base = MulticastGroupDerivation.derive(serverA, "ops-1")
        assertEquals(base, MulticastGroupDerivation.derive(serverA, "OPS-1"))
        assertEquals(base, MulticastGroupDerivation.derive(serverA, "  Ops-1  "))
        // NFC: "é" precomposed (U+00E9) vs decomposed (e + combining
        // acute U+0301). Built from code points so the two inputs
        // verifiably differ pre-normalization regardless of how this
        // source file itself is normalized.
        val precomposed = MulticastGroupDerivation.derive(serverA, "caf" + Char(0xE9))
        val decomposed = MulticastGroupDerivation.derive(serverA, "cafe" + Char(0x301))
        assertEquals(precomposed, decomposed)
    }

    @Test
    fun `canonical aliases Root to lobby - Root Lobby and padded ROOT all canonicalize identically`() {
        // Mumble's Root (channel id 0) IS the lobby. "Root" and "Lobby"
        // must canonicalize to the same value or two devices fork onto
        // different multicast groups and cannot share lobby voice.
        assertEquals(MulticastGroupDerivation.LOBBY_CANONICAL, MulticastGroupDerivation.canonicalChannelName("Root"))
        assertEquals(MulticastGroupDerivation.LOBBY_CANONICAL, MulticastGroupDerivation.canonicalChannelName("Lobby"))
        assertEquals(MulticastGroupDerivation.LOBBY_CANONICAL, MulticastGroupDerivation.canonicalChannelName("  ROOT "))
        assertEquals(
            MulticastGroupDerivation.canonicalChannelName("Root"),
            MulticastGroupDerivation.canonicalChannelName("Lobby"),
        )
    }

    @Test
    fun `canonical leaves the empty string empty - blank guards depend on it`() {
        assertEquals("", MulticastGroupDerivation.canonicalChannelName(""))
        assertEquals("", MulticastGroupDerivation.canonicalChannelName("   "))
    }

    @Test
    fun `canonical does not disturb a non-lobby name`() {
        assertEquals("ops-1", MulticastGroupDerivation.canonicalChannelName("Ops-1"))
        assertEquals("ops-1", MulticastGroupDerivation.canonicalChannelName("  OPS-1 "))
    }

    @Test
    fun `Root and Lobby derive the same endpoint on the same server`() {
        assertEquals(
            "Root and Lobby must land on the same multicast group+port",
            MulticastGroupDerivation.derive(serverA, "Root"),
            MulticastGroupDerivation.derive(serverA, "Lobby"),
        )
        // And padded/mixed-case spellings agree too.
        assertEquals(
            MulticastGroupDerivation.derive(serverA, "  root  "),
            MulticastGroupDerivation.derive(serverA, "LOBBY"),
        )
    }

    @Test
    fun `different channels on the same server differ`() {
        assertNotEquals(
            MulticastGroupDerivation.derive(serverA, "ops-1"),
            MulticastGroupDerivation.derive(serverA, "ops-2"),
        )
    }

    @Test
    fun `same channel on different servers differs`() {
        assertNotEquals(
            "channel 'ops-1' on two different servers must not share a multicast group",
            MulticastGroupDerivation.derive(serverA, "ops-1"),
            MulticastGroupDerivation.derive(serverB, "ops-1"),
        )
    }

    @Test
    fun `version tag is part of the frozen v1 spec`() {
        // Guards against someone "harmlessly" editing VERSION_TAG: a
        // changed tag is a protocol fork — every deployed device would
        // derive different groups. Evolving the scheme means adding a
        // v2, not editing v1.
        assertEquals("xv-mcast-v1", MulticastGroupDerivation.VERSION_TAG)
    }

    @Test
    fun `known-answer vector pins the v1 spec`() {
        // KAT recomputed from the spec: SHA-256("xv-mcast-v1|host|channel"),
        // group = 239.(224+(d0&0x0F)).d1.d2, port = 16800 + (be16(d3,d4) % 100).
        // If this fails, the derivation changed — bump to xv-mcast-v2 instead.
        val ep = MulticastGroupDerivation.derive(ServerIdentity.fromHostname("tak.example.com"), "ops-1")
        val d =
            java.security.MessageDigest
                .getInstance("SHA-256")
                .digest("xv-mcast-v1|tak.example.com|ops-1".toByteArray(Charsets.UTF_8))
        val expectedGroup = "239.${224 + (d[0].toInt() and 0x0F)}.${d[1].toInt() and 0xFF}.${d[2].toInt() and 0xFF}"
        val expectedPort = 16800 + ((((d[3].toInt() and 0xFF) shl 8) or (d[4].toInt() and 0xFF)) % 100)
        assertEquals(expectedGroup, ep.groupAddress)
        assertEquals(expectedPort, ep.port)
    }

    @Test
    fun `distribution is healthy over many combos`() {
        val rng = java.util.Random(0x5eed)
        val seen = HashSet<MulticastEndpoint>()
        val inputs = HashSet<String>()
        repeat(1000) {
            val host = "srv-${rng.nextInt(100)}.example.com"
            val ch = "channel-${rng.nextInt(1000)}"
            inputs.add("$host|$ch")
            seen.add(MulticastGroupDerivation.derive(ServerIdentity.fromHostname(host), ch))
        }
        // 20 bits of group + ~6.6 bits of port entropy; distinct inputs
        // should be nearly collision-free at this scale. Duplicate
        // (host, channel) draws from the bounded RNG ranges legitimately
        // collide, so compare against distinct inputs.
        assertTrue(
            "Expected >=${inputs.size - 5} unique endpoints from ${inputs.size} distinct inputs, got ${seen.size}",
            seen.size >= inputs.size - 5,
        )
    }

    @Test
    fun `deriveFromName is deterministic for the same name`() {
        val a = MulticastGroupDerivation.deriveFromName("TAK-01")
        val b = MulticastGroupDerivation.deriveFromName("TAK-01")
        assertEquals(a, b)
    }

    @Test
    fun `deriveFromName canonicalizes case whitespace and NFC`() {
        val lower = MulticastGroupDerivation.deriveFromName("tak-01")
        val upper = MulticastGroupDerivation.deriveFromName("TAK-01")
        val spaced = MulticastGroupDerivation.deriveFromName("  Tak-01  ")
        val precomposed = MulticastGroupDerivation.deriveFromName("caf" + Char(0xE9))
        val decomposed = MulticastGroupDerivation.deriveFromName("cafe" + Char(0x301))
        assertEquals("case must not affect derivation", lower, upper)
        assertEquals(lower, spaced)
        assertEquals(precomposed, decomposed)
    }

    @Test
    fun `deriveFromName stays inside the local namespace`() {
        val ep = MulticastGroupDerivation.deriveFromName("TAK-01")
        assertTrue(
            "expected 239.42.X.Y, got ${ep.groupAddress}",
            ep.groupAddress.startsWith("239.42."),
        )
        assertTrue(
            "port ${ep.port} outside local window",
            ep.port in MulticastGroupDerivation.LOCAL_PORT_BASE until
                (MulticastGroupDerivation.LOCAL_PORT_BASE + MulticastGroupDerivation.LOCAL_PORT_COUNT),
        )
    }

    @Test
    fun `deriveFromName is disjoint from server-keyed derive`() {
        val local = MulticastGroupDerivation.deriveFromName("TAK-01")
        val serverKeyed = MulticastGroupDerivation.derive(ServerIdentity.fromHostname("tak-01"), "tak-01")
        assertNotEquals(
            "local-name derivation must not collide with server-keyed derivation of same string",
            local,
            serverKeyed,
        )
    }

    @Test
    fun `different channel names produce different endpoints`() {
        val a = MulticastGroupDerivation.deriveFromName("TAK-01")
        val b = MulticastGroupDerivation.deriveFromName("TAK-02")
        assertNotEquals(a, b)
    }
}
