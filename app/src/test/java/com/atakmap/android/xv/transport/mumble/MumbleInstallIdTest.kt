package com.atakmap.android.xv.transport.mumble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-function coverage for the deterministic Mumble username suffix
 * builder. The wire-format invariants pinned here matter because OTS
 * Murmur uses the full `<callsign>---<suffix>` username as the auth
 * principal — a hash drift would split an existing operator's identity
 * in the user table, looking like a "rogue duplicate" to other XV
 * peers and breaking the "---" separator the roster scanner uses to
 * detect XV.
 */
class MumbleInstallIdTest {
    @Test
    fun `shortDeviceHashOf returns exactly 6 lowercase hex chars`() {
        val hash = MumbleInstallId.shortDeviceHashOf("ANDROID-cc22d724c0152f37")
        assertEquals("hash length must be 6", 6, hash.length)
        assertTrue(
            "hash must be lowercase hex: got '$hash'",
            hash.matches(Regex("^[0-9a-f]{6}$")),
        )
    }

    @Test
    fun `shortDeviceHashOf is deterministic — same uid produces same hash`() {
        val uid = "ANDROID-cc22d724c0152f37"
        val a = MumbleInstallId.shortDeviceHashOf(uid)
        val b = MumbleInstallId.shortDeviceHashOf(uid)
        assertEquals("same uid must produce identical hash on repeated calls", a, b)
    }

    @Test
    fun `shortDeviceHashOf differs between distinct uids`() {
        // Two real-shape ATAK device UIDs — what MapView.getDeviceUid()
        // returns. Hash collision at 24 bits is ~2^-12 for ~70 devices
        // per server, so two specific uids almost certainly differ.
        val h1 = MumbleInstallId.shortDeviceHashOf("ANDROID-cc22d724c0152f37")
        val h2 = MumbleInstallId.shortDeviceHashOf("ANDROID-aabbccddeeff0011")
        assertNotEquals("distinct uids must produce distinct hashes", h1, h2)
    }

    @Test
    fun `shortDeviceHashOf of empty string is well-defined and stable`() {
        // SHA-256("") = e3b0c44298fc1c149afbf4c8996fb924... → first 3 bytes
        // hex = "e3b0c4". Pins the exact value so a future refactor that
        // accidentally hashes something other than the raw input
        // (e.g. base64-wrapping) surfaces here.
        assertEquals("e3b0c4", MumbleInstallId.shortDeviceHashOf(""))
    }

    @Test
    fun `shortDeviceHashOf handles non-ASCII uid without throwing`() {
        // Defensive — ATAK shouldn't produce non-ASCII deviceUids in
        // practice (it's SSAID-backed, hex-only), but the byte-array
        // conversion uses UTF-8 implicitly. Verify it works for any
        // input that toByteArray() can encode.
        val hash = MumbleInstallId.shortDeviceHashOf("device-uid-with-emoji-✓-😀")
        assertEquals(6, hash.length)
        assertTrue(hash.matches(Regex("^[0-9a-f]{6}$")))
    }

    @Test
    fun `suffixFor appends slot tag verbatim`() {
        val uid = "ANDROID-cc22d724c0152f37"
        val s1 = MumbleInstallId.suffixFor(uid, "VS1")
        val s2 = MumbleInstallId.suffixFor(uid, "VS2")
        // Both must start with the same 6-char hash (same device, two
        // slots) and end with their respective tags.
        assertEquals(9, s1.length)
        assertEquals(9, s2.length)
        assertEquals(s1.substring(0, 6), s2.substring(0, 6))
        assertTrue("VS1 suffix must end with VS1: '$s1'", s1.endsWith("VS1"))
        assertTrue("VS2 suffix must end with VS2: '$s2'", s2.endsWith("VS2"))
    }

    @Test
    fun `suffixFor — two different devices with the same slot produce different suffixes`() {
        val a = MumbleInstallId.suffixFor("ANDROID-cc22d724c0152f37", "VS1")
        val b = MumbleInstallId.suffixFor("ANDROID-aabbccddeeff0011", "VS1")
        assertNotEquals("distinct devices must not collide on the same slot", a, b)
    }

    @Test
    fun `suffixFor — full pattern matches the documented format`() {
        // Format: `<6hex>VS<n>` per the MumbleInstallId KDoc. The
        // username scanner in MumbleTransport keys off the "---"
        // separator AND off the VS<n> suffix shape, so any deviation
        // is load-bearing.
        val suffix = MumbleInstallId.suffixFor("any-uid", "VS1")
        assertTrue(
            "suffix must match <6hex>VS<digit>: got '$suffix'",
            suffix.matches(Regex("^[0-9a-f]{6}VS[12]$")),
        )
    }
}
