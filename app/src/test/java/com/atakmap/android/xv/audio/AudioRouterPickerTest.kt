package com.atakmap.android.xv.audio

import android.media.AudioDeviceInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pure-Kotlin coverage for AudioRouter's output-device selector.
 * The selector decides where peer voice and TPT tones get played.
 * A regression here is the "audio came out of the wrong speaker"
 * field bug — visible to operators, invisible in logs.
 */
class AudioRouterPickerTest {
    private fun bt(
        mac: String,
        sco: Boolean = true,
    ): AudioRouter.Companion.DeviceCandidate {
        val type = if (sco) AudioDeviceInfo.TYPE_BLUETOOTH_SCO else AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
        return AudioRouter.Companion.DeviceCandidate(type, mac)
    }

    private fun wired(type: Int = AudioDeviceInfo.TYPE_WIRED_HEADSET): AudioRouter.Companion.DeviceCandidate =
        AudioRouter.Companion.DeviceCandidate(type, "")

    private val speaker = AudioRouter.Companion.DeviceCandidate(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, "")
    private val earpiece = AudioRouter.Companion.DeviceCandidate(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE, "")

    // ============================================================
    // Priority chain — BT > wired > internal
    // ============================================================

    @Test
    fun `Bluetooth wins over wired and internal`() {
        val pick =
            AudioRouter.pickPreferredDeviceFromCandidates(
                outputs = listOf(speaker, wired(), bt("AA:11")),
                route = OutputRoute.AUTO,
                overrideMac = null,
                preferredBtHintMac = null,
            )
        assertEquals("AA:11", pick!!.address)
    }

    @Test
    fun `wired wins over internal when no Bluetooth present`() {
        val pick =
            AudioRouter.pickPreferredDeviceFromCandidates(
                outputs = listOf(speaker, wired()),
                route = OutputRoute.AUTO,
                overrideMac = null,
                preferredBtHintMac = null,
            )
        assertEquals(AudioDeviceInfo.TYPE_WIRED_HEADSET, pick!!.type)
    }

    @Test
    fun `internal speaker is the default when no external device present`() {
        val pick =
            AudioRouter.pickPreferredDeviceFromCandidates(
                outputs = listOf(speaker, earpiece),
                route = OutputRoute.AUTO,
                overrideMac = null,
                preferredBtHintMac = null,
            )
        assertEquals(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, pick!!.type)
    }

    @Test
    fun `EARPIECE route preference flips internal to earpiece-first`() {
        val pick =
            AudioRouter.pickPreferredDeviceFromCandidates(
                outputs = listOf(speaker, earpiece),
                route = OutputRoute.EARPIECE,
                overrideMac = null,
                preferredBtHintMac = null,
            )
        assertEquals(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE, pick!!.type)
    }

    @Test
    fun `EARPIECE route preference falls back to speaker when earpiece absent`() {
        val pick =
            AudioRouter.pickPreferredDeviceFromCandidates(
                outputs = listOf(speaker),
                route = OutputRoute.EARPIECE,
                overrideMac = null,
                preferredBtHintMac = null,
            )
        assertEquals(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, pick!!.type)
    }

    @Test
    fun `SPEAKER route falls back to earpiece when speaker absent`() {
        // Some devices (Surface Duo book mode, certain industrial
        // handhelds) report no speaker. Earpiece is the only path.
        val pick =
            AudioRouter.pickPreferredDeviceFromCandidates(
                outputs = listOf(earpiece),
                route = OutputRoute.SPEAKER,
                overrideMac = null,
                preferredBtHintMac = null,
            )
        assertEquals(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE, pick!!.type)
    }

    @Test
    fun `empty candidate list returns null`() {
        assertNull(
            AudioRouter.pickPreferredDeviceFromCandidates(
                outputs = emptyList(),
                route = OutputRoute.AUTO,
                overrideMac = null,
                preferredBtHintMac = null,
            ),
        )
    }

    // ============================================================
    // BT override — operator-explicit pick wins everything
    // ============================================================

    @Test
    fun `BT override matches an output and wins over other BT`() {
        val pick =
            AudioRouter.pickPreferredDeviceFromCandidates(
                outputs = listOf(bt("AA:11"), bt("38:B8:EB:31:67:82")),
                route = OutputRoute.AUTO,
                overrideMac = "38:B8:EB:31:67:82",
                preferredBtHintMac = null,
            )
        assertEquals("38:B8:EB:31:67:82", pick!!.address)
    }

    @Test
    fun `BT override that isn't currently present falls through to priority chain`() {
        // Preserved-but-absent override drops to the regular chain so
        // the operator hears SOMETHING (rather than silence) while the
        // chosen device is offline.
        val pick =
            AudioRouter.pickPreferredDeviceFromCandidates(
                outputs = listOf(bt("AA:11"), speaker),
                route = OutputRoute.AUTO,
                overrideMac = "ZZ:99",
                preferredBtHintMac = null,
            )
        assertEquals("AA:11", pick!!.address)
    }

    // ============================================================
    // BT hint — picks specific BT when multiple are present
    // ============================================================

    @Test
    fun `BT hint match wins over first-found when multiple BT present`() {
        // AINA + AirPods both connected — without the hint, iteration
        // order would route non-deterministically. The hint pins.
        val pick =
            AudioRouter.pickPreferredDeviceFromCandidates(
                outputs = listOf(bt("AirPods-AA"), bt("AINA-38")),
                route = OutputRoute.AUTO,
                overrideMac = null,
                preferredBtHintMac = "AINA-38",
            )
        assertEquals("AINA-38", pick!!.address)
    }

    @Test
    fun `BT hint missing falls through to first BT in list`() {
        val pick =
            AudioRouter.pickPreferredDeviceFromCandidates(
                outputs = listOf(bt("FIRST"), bt("SECOND")),
                route = OutputRoute.AUTO,
                overrideMac = null,
                preferredBtHintMac = "NOT_PRESENT",
            )
        assertEquals("FIRST", pick!!.address)
    }

    @Test
    fun `BT override wins over BT hint when both set and override matches`() {
        val pick =
            AudioRouter.pickPreferredDeviceFromCandidates(
                outputs = listOf(bt("HINT"), bt("OVERRIDE")),
                route = OutputRoute.AUTO,
                overrideMac = "OVERRIDE",
                preferredBtHintMac = "HINT",
            )
        assertEquals(
            "explicit operator BT override must win over implicit AINA picker hint",
            "OVERRIDE",
            pick!!.address,
        )
    }

    // ============================================================
    // Wired variants
    // ============================================================

    @Test
    fun `each wired type is recognized`() {
        for (type in listOf(
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_USB_HEADSET,
        )) {
            val pick =
                AudioRouter.pickPreferredDeviceFromCandidates(
                    outputs = listOf(speaker, wired(type)),
                    route = OutputRoute.AUTO,
                    overrideMac = null,
                    preferredBtHintMac = null,
                )
            assertEquals(type, pick!!.type)
        }
    }

    // ============================================================
    // A2DP-only BT counts as Bluetooth for routing
    // ============================================================

    @Test
    fun `A2DP-only BT is treated as Bluetooth (wins over wired & internal)`() {
        val pick =
            AudioRouter.pickPreferredDeviceFromCandidates(
                outputs = listOf(wired(), speaker, bt("AA:11", sco = false)),
                route = OutputRoute.AUTO,
                overrideMac = null,
                preferredBtHintMac = null,
            )
        assertEquals(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, pick!!.type)
    }
}
