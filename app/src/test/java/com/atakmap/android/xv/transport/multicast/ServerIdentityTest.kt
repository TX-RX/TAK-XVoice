package com.atakmap.android.xv.transport.multicast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ServerIdentityTest {
    @Test
    fun `hostname is lowercased and trimmed`() {
        assertEquals("tak.example.com", ServerIdentity.fromHostname("  TAK.Example.COM ").value)
    }

    @Test
    fun `scheme port path and trailing dot are stripped`() {
        assertEquals("tak.example.com", ServerIdentity.fromHostname("ssl://tak.example.com:64738").value)
        assertEquals("tak.example.com", ServerIdentity.fromHostname("https://tak.example.com/Marti/api").value)
        assertEquals("tak.example.com", ServerIdentity.fromHostname("tak.example.com.").value)
        assertEquals("tak.example.com", ServerIdentity.fromHostname("tak.example.com:8089").value)
    }

    @Test
    fun `ipv4 literal with port strips the port`() {
        assertEquals("192.0.2.10", ServerIdentity.fromHostname("192.0.2.10:64738").value)
    }

    @Test
    fun `bracketed ipv6 with port unwraps to the bare address`() {
        assertEquals("2001:db8::1", ServerIdentity.fromHostname("[2001:db8::1]:64738").value)
    }

    @Test
    fun `bare ipv6 is left intact`() {
        assertEquals("2001:db8::1", ServerIdentity.fromHostname("2001:db8::1").value)
    }

    @Test
    fun `equivalent operator spellings converge on one identity`() {
        // The property the derivation depends on: however the host got
        // written across a team's devices, the identity bytes match.
        val spellings =
            listOf(
                "tak.example.com",
                "TAK.EXAMPLE.COM",
                "ssl://tak.example.com:64738",
                " tak.example.com. ",
            )
        val identities = spellings.map { ServerIdentity.fromHostname(it).value }.toSet()
        assertEquals(setOf("tak.example.com"), identities)
    }

    @Test
    fun `blank host is rejected`() {
        assertThrows(IllegalArgumentException::class.java) { ServerIdentity.fromHostname("   ") }
        assertThrows(IllegalArgumentException::class.java) { ServerIdentity.fromHostname("https://") }
    }

    @Test
    fun `cert fingerprint normalizes case and separators`() {
        // 2 + 29 + 1 = 32 bytes → 64 hex chars.
        val fp = "AB:CD:" + "12".repeat(29) + ":EF"
        val hex = ("abcd" + "12".repeat(29) + "ef")
        assertEquals(hex, ServerIdentity.fromCertFingerprint(fp).value)
        assertEquals(hex, ServerIdentity.fromCertFingerprint(hex.uppercase()).value)
    }

    @Test
    fun `cert fingerprint rejects non-sha256 input`() {
        assertThrows(IllegalArgumentException::class.java) { ServerIdentity.fromCertFingerprint("abcd") }
        assertThrows(IllegalArgumentException::class.java) { ServerIdentity.fromCertFingerprint("g".repeat(64)) }
    }
}
