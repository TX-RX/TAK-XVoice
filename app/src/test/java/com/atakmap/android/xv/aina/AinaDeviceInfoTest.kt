package com.atakmap.android.xv.aina

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Coverage for the small bit of logic in AinaDeviceInfo: the
 * displayLabel formatter. The label is what the operator sees in
 * Settings → Preferences when picking a speakermic; a wrong format
 * silently degrades the picker UX without surfacing a logcat error.
 */
class AinaDeviceInfoTest {
    @Test
    fun `displayLabel formats name plus protocol when protocol is known`() {
        val info =
            AinaDeviceInfo(
                mac = "AA:BB:CC:DD:EE:FF",
                name = "AINA APTT V1",
                buttonProtocol = AinaDeviceInfo.ButtonProtocol.SPP,
            )
        // Two spaces + bullet + two spaces is the production format
        // (a wide-bullet separator distinguishes it from any name
        // that contains hyphens or em-dashes).
        assertEquals("AINA APTT V1  ·  SPP buttons", info.displayLabel())
    }

    @Test
    fun `displayLabel omits the protocol suffix when protocol is UNKNOWN`() {
        // UNKNOWN's `display` field is the empty string, but we ALSO
        // skip the separator — otherwise the operator would see
        // "Device Name  ·  " trailing whitespace in the picker.
        val info =
            AinaDeviceInfo(
                mac = "AA:BB:CC:DD:EE:FF",
                name = "Mystery Headset",
                buttonProtocol = AinaDeviceInfo.ButtonProtocol.UNKNOWN,
            )
        assertEquals("Mystery Headset", info.displayLabel())
    }

    @Test
    fun `displayLabel renders each documented protocol's display name`() {
        // Pin every protocol's display string. Operators recognize these
        // labels — a drift between source-of-truth (enum.display) and
        // the picker would re-train muscle memory.
        val cases =
            mapOf(
                AinaDeviceInfo.ButtonProtocol.SPP to "AINA V1  ·  SPP buttons",
                AinaDeviceInfo.ButtonProtocol.BLE to "AINA V2  ·  BLE buttons",
                AinaDeviceInfo.ButtonProtocol.BLE_HID to "Pryme BT  ·  BLE HID button (VS1 only)",
                AinaDeviceInfo.ButtonProtocol.AUDIO_ONLY to "Generic HFP  ·  audio only",
            )
        for ((proto, expected) in cases) {
            val nameOnly = expected.substringBefore("  ·  ")
            val info = AinaDeviceInfo(mac = "00:11:22:33:44:55", name = nameOnly, buttonProtocol = proto)
            assertEquals("protocol=$proto", expected, info.displayLabel())
        }
    }

    @Test
    fun `data-class equals treats two entries with identical fields as equal`() {
        val a = AinaDeviceInfo("AA:BB", "device", AinaDeviceInfo.ButtonProtocol.SPP)
        val b = AinaDeviceInfo("AA:BB", "device", AinaDeviceInfo.ButtonProtocol.SPP)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }
}
