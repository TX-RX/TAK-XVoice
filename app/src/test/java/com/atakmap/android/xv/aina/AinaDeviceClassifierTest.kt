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
