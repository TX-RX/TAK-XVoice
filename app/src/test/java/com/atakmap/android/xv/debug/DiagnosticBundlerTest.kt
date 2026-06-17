package com.atakmap.android.xv.debug

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure unit tests for [redact]. No Android / Robolectric — the redaction
 * function is a `String -> String` so we hit it directly.
 *
 * Coverage anchors:
 *  - BT MAC is masked to first + last octet.
 *  - Private (RFC1918) + multicast IPv4 is NOT redacted.
 *  - Public IPv4 IS redacted.
 *  - Lines with no sensitive tokens are passed through verbatim.
 */
class DiagnosticBundlerTest {
    @Test
    fun btMacRedaction_preservesFirstAndLastOctet() {
        val input = "AINA paired with AA:BB:CC:DD:EE:FF on classic"
        val out = redact(input)
        assertEquals("AINA paired with AA:XX:XX:XX:XX:FF on classic", out)
    }

    @Test
    fun btMacRedaction_handlesLowercaseHex() {
        val input = "scan hit 0a:1b:2c:3d:4e:5f"
        val out = redact(input)
        assertEquals("scan hit 0a:XX:XX:XX:XX:5f", out)
    }

    @Test
    fun btMacRedaction_redactsMultipleMacsOnTheSameLine() {
        val input = "pair AA:11:22:33:44:BB then CC:55:66:77:88:DD"
        val out = redact(input)
        assertEquals("pair AA:XX:XX:XX:XX:BB then CC:XX:XX:XX:XX:DD", out)
    }

    @Test
    fun privateIpv4_isNotRedacted() {
        // All three RFC1918 ranges. Operators need these to see which LAN
        // the transport is bound to.
        val input =
            "tcp open to 192.168.1.42, lease from 10.0.0.1, neighbour 172.20.5.6"
        val out = redact(input)
        assertEquals(input, out)
    }

    @Test
    fun multicastIpv4_isNotRedacted() {
        // Both the link-local (224.0.0.0/8) and admin-scoped (239.0.0.0/8)
        // multicast ranges. XV's MulticastTransport uses 239.x; keep it
        // legible so we can spot misconfigured groups in a diag report.
        val input = "joined 239.0.1.7:6001 (control 224.0.0.251)"
        val out = redact(input)
        assertEquals(input, out)
    }

    @Test
    fun publicIpv4_isRedacted() {
        val input = "POST https://203.0.113.42:443 — TAK server reachable"
        val out = redact(input)
        assertFalse("public IP must be removed", out.contains("203.0.113.42"))
        assertTrue("redaction marker must be present", out.contains("[REDACTED-IP]"))
        // Surrounding context preserved.
        assertTrue(out.contains("POST https://"))
        assertTrue(out.contains("TAK server reachable"))
    }

    @Test
    fun publicIpv4_isRedacted_evenWithMultipleOnSameLine() {
        val input = "upstream=8.8.8.8 dns=1.1.1.1 fallback=192.168.0.1"
        val out = redact(input)
        assertFalse(out.contains("8.8.8.8"))
        assertFalse(out.contains("1.1.1.1"))
        // The private gateway stays.
        assertTrue(out.contains("192.168.0.1"))
    }

    @Test
    fun noSensitiveTokens_isPassThrough() {
        val input = "12-06 11:32:08.123  1234  4567 I XvVoiceService: PTT down → priming"
        val out = redact(input)
        assertEquals(input, out)
    }

    @Test
    fun emptyInput_isEmptyOutput() {
        assertEquals("", redact(""))
    }

    @Test
    fun nearMissOctet_isNotMistakenForIp() {
        // 999.999.999.999 doesn't fit IPv4 octet bounds; redactor must
        // leave it alone so we don't accidentally rewrite version strings
        // / scratch byte values.
        val input = "build 0.1.16 — see ticket 999.999.999.999"
        val out = redact(input)
        assertEquals(input, out)
    }

    @Test
    fun mixedLine_macAndPublicIp_bothRedacted() {
        val input = "AINA AA:11:22:33:44:BB egress 8.8.4.4"
        val out = redact(input)
        // MAC masked.
        assertTrue(out.contains("AA:XX:XX:XX:XX:BB"))
        assertFalse(out.contains("AA:11:22:33:44:BB"))
        // Public IP replaced.
        assertTrue(out.contains("[REDACTED-IP]"))
        assertFalse(out.contains("8.8.4.4"))
        // And the result is different from the input.
        assertNotEquals(input, out)
    }
}
