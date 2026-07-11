package com.atakmap.android.xv.aina

import android.bluetooth.BluetoothDevice
import android.os.ParcelUuid
import io.mockk.every
import io.mockk.mockk
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Coverage for the pure speakermic-detection + button-protocol
 * classifier extracted from XvMapComponent during the L5+L6 split.
 *
 * These were inline private fns before extraction — completely
 * untestable. The extraction is justified primarily by getting this
 * coverage; the classifier decides which devices appear in the
 * Settings picker and which reader (SPP / BLE / BLE_HID) the
 * service-side AINA path attaches.
 *
 * Robolectric runner so [ParcelUuid] (a real android.os class) gives
 * back the wrapped UUID; under plain JUnit it returns null and every
 * UUID-based assertion silently degrades to UNKNOWN.
 */
@RunWith(RobolectricTestRunner::class)
class AinaDeviceClassifierTest {
    @Test
    fun `AINA name prefix matches plausible speakermic`() {
        val dev = devWith(name = "APTT V2", uuids = null)
        assertTrue(AinaDeviceClassifier.isPlausibleSpeakermic(dev))
        assertTrue(AinaDeviceClassifier.isAinaByName(dev))
    }

    @Test
    fun `lowercased APTT name still matches AINA`() {
        // device.name comes from the OS; we don't assume it's
        // canonical-cased.
        val dev = devWith(name = "aptt v1 pro", uuids = null)
        assertTrue(AinaDeviceClassifier.isAinaByName(dev))
    }

    @Test
    fun `Pryme keywords match plausible speakermic`() {
        for (n in listOf("Pryme BT", "BT-PTT button", "PTT-Z")) {
            val dev = devWith(name = n, uuids = null)
            assertTrue("expected $n to match Pryme", AinaDeviceClassifier.isPrymeByName(dev))
            assertTrue(AinaDeviceClassifier.isPlausibleSpeakermic(dev))
        }
    }

    @Test
    fun `generic Bluetooth headset is not plausible`() {
        val dev = devWith(name = "WH-1000XM4", uuids = null)
        assertFalse(AinaDeviceClassifier.isPlausibleSpeakermic(dev))
        assertFalse(AinaDeviceClassifier.isAinaByName(dev))
        assertFalse(AinaDeviceClassifier.isPrymeByName(dev))
    }

    @Test
    fun `device with null name and null UUIDs is not plausible`() {
        val dev = devWith(name = null, uuids = null)
        assertFalse(AinaDeviceClassifier.isPlausibleSpeakermic(dev))
    }

    @Test
    fun `AINA V2 vendor UUID classifies as BLE even without name match`() {
        val dev =
            devWith(
                name = "WeirdName",
                uuids = arrayOf(ParcelUuid(AinaBleReader.SERVICE_UUID)),
            )
        assertEquals(
            AinaDeviceInfo.ButtonProtocol.BLE,
            AinaDeviceClassifier.classifyButtonProtocol(dev),
        )
        assertTrue(AinaDeviceClassifier.isPlausibleSpeakermic(dev))
    }

    @Test
    fun `Pryme vendor UUID classifies as BLE_HID`() {
        val dev =
            devWith(
                name = null,
                uuids = arrayOf(ParcelUuid(AinaDeviceClassifier.PRYME_VENDOR_UUID)),
            )
        assertEquals(
            AinaDeviceInfo.ButtonProtocol.BLE_HID,
            AinaDeviceClassifier.classifyButtonProtocol(dev),
        )
    }

    @Test
    fun `HM10 module UUID classifies as BLE_HID`() {
        val dev =
            devWith(
                name = null,
                uuids = arrayOf(ParcelUuid(AinaDeviceClassifier.HM10_SERVICE_UUID)),
            )
        assertEquals(
            AinaDeviceInfo.ButtonProtocol.BLE_HID,
            AinaDeviceClassifier.classifyButtonProtocol(dev),
        )
    }

    @Test
    fun `dual-mode AINA with V2 UUID prefers BLE over SPP`() {
        // V2 vendor service is the decisive signal — name-based AINA
        // detection would have classified this as SPP otherwise. The
        // UUID-precedence rule is what enables the V2 reader to win
        // for dual-mode hardware.
        val dev =
            devWith(
                name = "APTT V2",
                uuids =
                arrayOf(
                    ParcelUuid(AinaBleReader.SERVICE_UUID),
                    ParcelUuid(UUID.fromString("00001101-0000-1000-8000-00805f9b34fb")), // SPP
                ),
            )
        assertEquals(
            AinaDeviceInfo.ButtonProtocol.BLE,
            AinaDeviceClassifier.classifyButtonProtocol(dev),
        )
    }

    @Test
    fun `AINA name without V2 UUID falls back to SPP`() {
        val dev = devWith(name = "APTT V1", uuids = emptyArray())
        assertEquals(
            AinaDeviceInfo.ButtonProtocol.SPP,
            AinaDeviceClassifier.classifyButtonProtocol(dev),
        )
    }

    @Test
    fun `unrecognized device classifies as UNKNOWN`() {
        val dev = devWith(name = "Random Headset", uuids = emptyArray())
        assertEquals(
            AinaDeviceInfo.ButtonProtocol.UNKNOWN,
            AinaDeviceClassifier.classifyButtonProtocol(dev),
        )
    }

    @Test
    fun `protocolOrder puts SPP first and UNKNOWN last`() {
        val ordered =
            listOf(
                AinaDeviceInfo.ButtonProtocol.UNKNOWN,
                AinaDeviceInfo.ButtonProtocol.AUDIO_ONLY,
                AinaDeviceInfo.ButtonProtocol.BLE_HID,
                AinaDeviceInfo.ButtonProtocol.BLE,
                AinaDeviceInfo.ButtonProtocol.SPP,
            ).sortedBy(AinaDeviceClassifier::protocolOrder)
        assertEquals(
            listOf(
                AinaDeviceInfo.ButtonProtocol.SPP,
                AinaDeviceInfo.ButtonProtocol.BLE,
                AinaDeviceInfo.ButtonProtocol.BLE_HID,
                AinaDeviceInfo.ButtonProtocol.AUDIO_ONLY,
                AinaDeviceInfo.ButtonProtocol.UNKNOWN,
            ),
            ordered,
        )
    }

    // ============================================================
    // rankForPicker — startup-UX device ordering
    // (feat/bt-device-startup-ux)
    // ============================================================

    private fun info(
        name: String,
        proto: AinaDeviceInfo.ButtonProtocol,
        available: Boolean,
    ): AinaDeviceInfo =
        AinaDeviceInfo(
            mac = "AA:BB:CC:DD:EE:${name.hashCode().and(0xFF).toString(16).padStart(2, '0')}",
            name = name,
            buttonProtocol = proto,
            available = available,
        )

    @Test
    fun `rankForPicker puts available devices ahead of unavailable regardless of protocol`() {
        // The field bug being fixed: startup UX auto-selected a device
        // that was bonded but powered off, even though a live SPP AINA
        // was in the picker. Ranking must promote AVAILABLE ahead of
        // protocol precedence.
        val unavailableSpp = info("APTT-off", AinaDeviceInfo.ButtonProtocol.SPP, available = false)
        val availableBleHid = info("Pryme-live", AinaDeviceInfo.ButtonProtocol.BLE_HID, available = true)
        val ranked = AinaDeviceClassifier.rankForPicker(listOf(unavailableSpp, availableBleHid))
        assertEquals(
            "available BLE-HID must beat unavailable SPP so autoConnectAina picks a live device",
            "Pryme-live",
            ranked.first().name,
        )
        assertEquals("APTT-off", ranked.last().name)
    }

    @Test
    fun `rankForPicker preserves protocol order within the available tier`() {
        // SPP > BLE > BLE_HID within the same availability tier —
        // the pre-change ordering, unchanged.
        val ble = info("V2-live", AinaDeviceInfo.ButtonProtocol.BLE, available = true)
        val spp = info("V1-live", AinaDeviceInfo.ButtonProtocol.SPP, available = true)
        val bleHid = info("Puck-live", AinaDeviceInfo.ButtonProtocol.BLE_HID, available = true)
        val ranked = AinaDeviceClassifier.rankForPicker(listOf(ble, bleHid, spp))
        assertEquals(
            listOf("V1-live", "V2-live", "Puck-live"),
            ranked.map { it.name },
        )
    }

    @Test
    fun `rankForPicker preserves protocol order within the unavailable tier`() {
        // Unavailable devices still sort by protocol beneath the
        // available block — an operator scanning the greyed rows sees
        // a familiar order.
        val ble = info("V2-off", AinaDeviceInfo.ButtonProtocol.BLE, available = false)
        val spp = info("V1-off", AinaDeviceInfo.ButtonProtocol.SPP, available = false)
        val ranked = AinaDeviceClassifier.rankForPicker(listOf(ble, spp))
        assertEquals(listOf("V1-off", "V2-off"), ranked.map { it.name })
    }

    @Test
    fun `rankForPicker breaks ties by lowercase name`() {
        // Same protocol, same availability — name is the tiebreaker so
        // the picker order is stable across refreshes.
        val a = info("bravo", AinaDeviceInfo.ButtonProtocol.SPP, available = true)
        val b = info("Alpha", AinaDeviceInfo.ButtonProtocol.SPP, available = true)
        val ranked = AinaDeviceClassifier.rankForPicker(listOf(a, b))
        assertEquals(listOf("Alpha", "bravo"), ranked.map { it.name })
    }

    // ============================================================
    // pickPrimary — auto-connect for the PRIMARY speakermic slot
    // (fix/primary-picks-speakermic-over-button)
    // ============================================================

    @Test
    fun `pickPrimary skips available BLE_HID puck when speakermic is off`() {
        // The 2026-07-10 field report: bonded Pryme puck (BLE_HID) is
        // always marked available=true because HID pucks don't appear
        // in AudioManager.getAvailableCommunicationDevices, so the old
        // firstOrNull { available } auto-picked the puck as PRIMARY
        // whenever the operator's AINA was powered off at plugin load.
        // pickPrimary must filter BLE_HID out entirely — the puck is
        // the SECONDARY slot's problem, not primary's.
        val puckLive = info("Pryme-live", AinaDeviceInfo.ButtonProtocol.BLE_HID, available = true)
        val ainaOff = info("APTT-off", AinaDeviceInfo.ButtonProtocol.SPP, available = false)
        val ranked = AinaDeviceClassifier.rankForPicker(listOf(puckLive, ainaOff))
        assertEquals(
            "no available speakermic → pickPrimary returns null instead of the button-only puck",
            null,
            AinaDeviceClassifier.pickPrimary(ranked),
        )
    }

    @Test
    fun `pickPrimary picks the available speakermic when both puck and AINA are live`() {
        val puck = info("Pryme-live", AinaDeviceInfo.ButtonProtocol.BLE_HID, available = true)
        val aina = info("APTT-live", AinaDeviceInfo.ButtonProtocol.BLE, available = true)
        val ranked = AinaDeviceClassifier.rankForPicker(listOf(puck, aina))
        assertEquals(
            "speakermic beats button-only puck for primary",
            "APTT-live",
            AinaDeviceClassifier.pickPrimary(ranked)?.name,
        )
    }

    @Test
    fun `pickPrimary prefers SPP over BLE when both are available`() {
        // Within the eligible-for-primary tier, the picker's protocol
        // order (SPP → BLE → AUDIO_ONLY) must round-trip through
        // pickPrimary. This is the historical AINA V1 preference —
        // when both a V1 and a V2 are on, take V1 for auto-pick.
        val v2 = info("APTT-V2", AinaDeviceInfo.ButtonProtocol.BLE, available = true)
        val v1 = info("APTT-V1", AinaDeviceInfo.ButtonProtocol.SPP, available = true)
        val ranked = AinaDeviceClassifier.rankForPicker(listOf(v2, v1))
        assertEquals("APTT-V1", AinaDeviceClassifier.pickPrimary(ranked)?.name)
    }

    @Test
    fun `pickPrimary returns null when all candidates are unavailable`() {
        // No live speakermic and no live puck — nothing to connect. The
        // caller must NOT fall back to an unavailable device just to
        // satisfy an old MAC hint (that was the original 2026-07-08 bug
        // this whole auto-pick path was fixing).
        val ainaOff = info("APTT-off", AinaDeviceInfo.ButtonProtocol.SPP, available = false)
        val puckOff = info("Pryme-off", AinaDeviceInfo.ButtonProtocol.BLE_HID, available = false)
        val ranked = AinaDeviceClassifier.rankForPicker(listOf(ainaOff, puckOff))
        assertEquals(null, AinaDeviceClassifier.pickPrimary(ranked))
    }

    @Test
    fun `pickPrimary accepts an available AUDIO_ONLY device`() {
        // Speakermic with an unrecognized button protocol still routes
        // its audio; XV falls back to on-screen PTT. Not our happy path
        // but it's a legitimate primary — must not be filtered out.
        val audioOnly = info("Generic-HFP", AinaDeviceInfo.ButtonProtocol.AUDIO_ONLY, available = true)
        val ranked = AinaDeviceClassifier.rankForPicker(listOf(audioOnly))
        assertEquals("Generic-HFP", AinaDeviceClassifier.pickPrimary(ranked)?.name)
    }

    @Test
    fun `device that throws on name access is treated as nameless`() {
        // Some OEM stacks throw SecurityException on getName() if
        // BLUETOOTH_CONNECT was revoked at runtime — the classifier
        // should swallow it rather than propagating to the picker.
        val dev = mockk<BluetoothDevice>(relaxed = true)
        every { dev.name } throws SecurityException("revoked")
        every { dev.uuids } returns null
        assertFalse(AinaDeviceClassifier.isAinaByName(dev))
        assertFalse(AinaDeviceClassifier.isPrymeByName(dev))
        assertFalse(AinaDeviceClassifier.isPlausibleSpeakermic(dev))
    }

    private fun devWith(
        name: String?,
        uuids: Array<ParcelUuid>?,
    ): BluetoothDevice {
        val dev = mockk<BluetoothDevice>(relaxed = true)
        every { dev.name } returns name
        every { dev.uuids } returns uuids
        return dev
    }
}
