package com.atakmap.android.xv.audio

import android.media.AudioDeviceInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pure-Kotlin coverage for ScoLink's BT comm-device selector. Production
 * code adapts AudioDeviceInfo → Candidate; the selector itself is pure.
 * Pinned in tests because picking the wrong BT device → audio routes to
 * the wrong speakermic → silent failure in the field.
 */
class ScoLinkPickerTest {
    private fun sco(mac: String) = ScoLink.Candidate(AudioDeviceInfo.TYPE_BLUETOOTH_SCO, mac)

    private fun a2dp(mac: String) = ScoLink.Candidate(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, mac)

    private fun otherType(mac: String) = ScoLink.Candidate(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, mac)

    @Test
    fun `pinned MAC matched on SCO wins absolutely`() {
        val list =
            listOf(
                sco("AA:11:11:11:11:11"),
                sco("38:B8:EB:31:67:82"), // pinned AINA
                a2dp("38:B8:EB:31:67:82"),
            )
        val pick = ScoLink.pickBtCommDeviceFromCandidates(list, "38:B8:EB:31:67:82")
        assertEquals(AudioDeviceInfo.TYPE_BLUETOOTH_SCO, pick!!.type)
        assertEquals("38:B8:EB:31:67:82", pick.mac)
    }

    @Test
    fun `pinned MAC match is case-insensitive`() {
        // Operators sometimes type MACs in different case via settings;
        // pinned MAC storage might keep the original capitalization.
        val list = listOf(sco("AA:BB:CC:DD:EE:FF"))
        val pick = ScoLink.pickBtCommDeviceFromCandidates(list, "aa:bb:cc:dd:ee:ff")
        assertEquals("AA:BB:CC:DD:EE:FF", pick!!.mac)
    }

    @Test
    fun `pinned MAC falls back to A2DP when the same device has no SCO profile`() {
        // Some BT devices expose only A2DP (Bluetooth speakers, JBL etc.)
        // — the pin should still match if the operator wants that device
        // even though TX won't work without SCO.
        val list = listOf(a2dp("38:B8:EB:31:67:82"))
        val pick = ScoLink.pickBtCommDeviceFromCandidates(list, "38:B8:EB:31:67:82")
        assertEquals(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, pick!!.type)
    }

    @Test
    fun `pinned MAC missing falls through to first-SCO`() {
        val list =
            listOf(
                sco("AA:11:11:11:11:11"),
                a2dp("BB:22:22:22:22:22"),
            )
        val pick = ScoLink.pickBtCommDeviceFromCandidates(list, "ZZ:99:99:99:99:99")
        // Pinned MAC not in list; falls through to first-SCO order.
        assertEquals(AudioDeviceInfo.TYPE_BLUETOOTH_SCO, pick!!.type)
        assertEquals("AA:11:11:11:11:11", pick.mac)
    }

    @Test
    fun `null pinned MAC picks first SCO in iteration order`() {
        val list =
            listOf(
                a2dp("XX:00:00:00:00:00"),
                sco("AA:11:11:11:11:11"),
                sco("BB:22:22:22:22:22"),
            )
        val pick = ScoLink.pickBtCommDeviceFromCandidates(list, null)
        assertEquals("AA:11:11:11:11:11", pick!!.mac)
    }

    @Test
    fun `blank pinned MAC behaves like null`() {
        val list = listOf(sco("AA:11"))
        val pick = ScoLink.pickBtCommDeviceFromCandidates(list, "   ")
        assertEquals("AA:11", pick!!.mac)
    }

    @Test
    fun `no SCO falls through to first A2DP`() {
        val list = listOf(a2dp("AA:11"), a2dp("BB:22"))
        val pick = ScoLink.pickBtCommDeviceFromCandidates(list, null)
        assertEquals(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, pick!!.type)
        assertEquals("AA:11", pick.mac)
    }

    @Test
    fun `empty candidate list returns null`() {
        assertNull(ScoLink.pickBtCommDeviceFromCandidates(emptyList(), null))
        assertNull(ScoLink.pickBtCommDeviceFromCandidates(emptyList(), "AA:BB:CC:DD:EE:FF"))
    }

    @Test
    fun `candidates of unrelated types are ignored even when MAC matches`() {
        // BUILTIN_SPEAKER with a MAC that happens to match (won't ever
        // happen in practice but the selector must be defensive).
        val list = listOf(otherType("38:B8:EB:31:67:82"))
        val pick = ScoLink.pickBtCommDeviceFromCandidates(list, "38:B8:EB:31:67:82")
        assertNull("non-BT candidates must be ignored entirely", pick)
    }

    @Test
    fun `SCO preferred over A2DP when both present for the SAME pinned MAC`() {
        // Device exposes both profiles; SCO must win because TX needs it.
        val list =
            listOf(
                a2dp("38:B8:EB:31:67:82"),
                sco("38:B8:EB:31:67:82"),
            )
        val pick = ScoLink.pickBtCommDeviceFromCandidates(list, "38:B8:EB:31:67:82")
        assertEquals(AudioDeviceInfo.TYPE_BLUETOOTH_SCO, pick!!.type)
    }

    @Test
    fun `SCO-on-different-MAC loses to A2DP-on-pinned-MAC (the pin wins)`() {
        // Operator pinned device X (which only has A2DP). Another device
        // Y is present with SCO. The pin must win — we route to X even
        // though TX won't work, rather than silently going to Y.
        val list =
            listOf(
                sco("YY:99"),
                a2dp("XX:11"),
            )
        val pick = ScoLink.pickBtCommDeviceFromCandidates(list, "XX:11")
        assertEquals("XX:11", pick!!.mac)
        assertEquals(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, pick.type)
    }
}
