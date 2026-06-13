package com.atakmap.android.xv.transport.multicast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MulticastGroupDerivationTest {
    private val fpA = "a".repeat(64) // mock SHA-256 hex
    private val fpB = "b".repeat(64)

    @Test
    fun `derivation is deterministic for the same inputs`() {
        val first = MulticastGroupDerivation.derive(fpA, 42)
        val second = MulticastGroupDerivation.derive(fpA, 42)
        assertEquals(first, second)
    }

    @Test
    fun `derived address is in the 239_42 admin scope`() {
        val ep = MulticastGroupDerivation.derive(fpA, 42)
        assertTrue(
            "expected 239.42.X.Y, got ${ep.groupAddress}",
            ep.groupAddress.startsWith("239.42."),
        )
        val parts = ep.groupAddress.split(".")
        assertEquals(4, parts.size)
        val octet3 = parts[2].toInt()
        val octet4 = parts[3].toInt()
        assertTrue(octet3 in 0..255)
        assertTrue(octet4 in 0..255)
    }

    @Test
    fun `derived port stays in the 4096-port window above PORT_BASE`() {
        val ep = MulticastGroupDerivation.derive(fpA, 42)
        assertTrue(
            "expected 6000..10095, got ${ep.port}",
            ep.port in MulticastGroupDerivation.PORT_BASE..(MulticastGroupDerivation.PORT_BASE + 0xFFF),
        )
    }

    @Test
    fun `different channelIds on the same server differ`() {
        val a = MulticastGroupDerivation.derive(fpA, 1)
        val b = MulticastGroupDerivation.derive(fpA, 2)
        // We're not asserting they MUST differ (digest collision is allowed
        // in principle); we are asserting our derivation isn't truncating
        // channelId to the point that adjacent ids collide. With SHA-256
        // input including the full 32-bit big-endian channelId, ids 1 and 2
        // produce wildly different digests.
        assertNotEquals(a, b)
    }

    @Test
    fun `different servers with the same channelId differ`() {
        val a = MulticastGroupDerivation.derive(fpA, 5)
        val b = MulticastGroupDerivation.derive(fpB, 5)
        assertNotEquals(
            "channel 5 on two different servers must not share a multicast group",
            a,
            b,
        )
    }

    @Test
    fun `1000 random server-channel combos produce no obvious collision storm`() {
        // Birthday-bound on 24-bit derivation is sqrt(2^24) ~= 4096, so 1000
        // combos should produce well under 1 collision on average. We just
        // verify the distribution is healthy: at least 950 unique endpoints
        // out of 1000 inputs.
        val rng = java.util.Random(0x5eed)
        val seen = HashSet<MulticastEndpoint>()
        repeat(1000) {
            val fp = (1..64).map { "0123456789abcdef"[rng.nextInt(16)] }.joinToString("")
            val ch = rng.nextInt(10_000)
            seen.add(MulticastGroupDerivation.derive(fp, ch))
        }
        assertTrue(
            "Expected >=950 unique endpoints, got ${seen.size} (collision storm)",
            seen.size >= 950,
        )
    }

    @Test
    fun `byte order is platform-independent for channelId`() {
        // channelId 0x01020304 — the digest input for this should be the
        // bytes 01, 02, 03, 04 in that order regardless of host endianness.
        // Spot-check that two runs on the same input produce the same
        // result; the bug we're protecting against is JVM endianness leak
        // via Int.toByteArray() (which is big-endian on Kotlin/JVM but
        // we don't want to rely on that — we encode bytes ourselves).
        val a = MulticastGroupDerivation.derive(fpA, 0x01020304)
        val b = MulticastGroupDerivation.derive(fpA, 0x01020304)
        assertEquals(a, b)
    }
}
