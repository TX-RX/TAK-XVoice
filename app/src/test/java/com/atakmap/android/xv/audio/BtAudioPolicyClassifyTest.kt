package com.atakmap.android.xv.audio

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure-Kotlin coverage for BtAudioPolicy.classify(). Production code
 * pulls the inputs from `headsetProxy.connectedDevices`,
 * `audioManager.getDevices(GET_DEVICES_OUTPUTS)`, and the ACL-broadcast
 * `aclDisconnected` set. The classifier itself is pure, extracted as
 * [BtAudioPolicy.classifyFromInputs] so tests can pin every branch.
 *
 * Field-bug payoff: classify is consulted on every RX/TX entry to
 * decide which audio profile to build. A wrong verdict ("A2DP available"
 * when SCO is actually live, or vice versa) routes audio to the wrong
 * stream → no audio, or worse: TPT bleed via wrong stream.
 */
class BtAudioPolicyClassifyTest {
    @Test
    fun `HFP profile reports a device — classify HFP_ONLY`() {
        val mode =
            BtAudioPolicy.classifyFromInputs(
                hfpProxyMacs = listOf("AA:BB:CC:DD:EE:FF"),
                aclDisconnectedMacs = emptySet(),
                hasScoOutput = true,
                hasA2dpOutput = true,
            )
        assertEquals(BtAudioMode.HFP_ONLY, mode)
    }

    @Test
    fun `HFP profile reports a device but it's ACL-disconnected — fall through`() {
        // The proxy lags ~30-60s when a speakermic powers off. The ACL
        // broadcast tells us "actually gone." Production code uses the
        // set to filter; we exercise that here. With no other signal,
        // verdict is NONE.
        val mode =
            BtAudioPolicy.classifyFromInputs(
                hfpProxyMacs = listOf("AA:BB:CC:DD:EE:FF"),
                aclDisconnectedMacs = setOf("AA:BB:CC:DD:EE:FF"),
                hasScoOutput = false,
                hasA2dpOutput = false,
            )
        assertEquals(BtAudioMode.NONE, mode)
    }

    @Test
    fun `proxy empty but audio HAL exposes SCO endpoint — HFP_ONLY (Surface Duo fallback)`() {
        // Documented in production: BluetoothHeadset proxy never
        // reflects the connected AINA on some OEM stacks. The audio
        // HAL is more authoritative — if SCO endpoint exists, we
        // know SCO is reachable.
        val mode =
            BtAudioPolicy.classifyFromInputs(
                hfpProxyMacs = emptyList(),
                aclDisconnectedMacs = emptySet(),
                hasScoOutput = true,
                hasA2dpOutput = false,
            )
        assertEquals(BtAudioMode.HFP_ONLY, mode)
    }

    @Test
    fun `proxy empty and no SCO endpoint but A2DP present — A2DP_AVAILABLE`() {
        // Pure-A2DP device (Bluetooth speaker, JBL, car kit without
        // HFP). Mic stays on built-in, RX routes via STREAM_MUSIC.
        val mode =
            BtAudioPolicy.classifyFromInputs(
                hfpProxyMacs = emptyList(),
                aclDisconnectedMacs = emptySet(),
                hasScoOutput = false,
                hasA2dpOutput = true,
            )
        assertEquals(BtAudioMode.A2DP_AVAILABLE, mode)
    }

    @Test
    fun `nothing connected — NONE`() {
        val mode =
            BtAudioPolicy.classifyFromInputs(
                hfpProxyMacs = emptyList(),
                aclDisconnectedMacs = emptySet(),
                hasScoOutput = false,
                hasA2dpOutput = false,
            )
        assertEquals(BtAudioMode.NONE, mode)
    }

    @Test
    fun `multiple HFP devices — all live, classify HFP_ONLY`() {
        // Two HFP devices both reported by proxy, neither in
        // aclDisconnected → HFP_ONLY (the routing layer picks which
        // one via ScoLink.pickBtCommDeviceFromCandidates).
        val mode =
            BtAudioPolicy.classifyFromInputs(
                hfpProxyMacs = listOf("AA:11", "BB:22"),
                aclDisconnectedMacs = emptySet(),
                hasScoOutput = true,
                hasA2dpOutput = false,
            )
        assertEquals(BtAudioMode.HFP_ONLY, mode)
    }

    @Test
    fun `some HFP devices ACL-disconnected but at least one live — HFP_ONLY`() {
        // Mixed scenario: prior device powered off (ACL gone), new
        // device freshly connected. Production code filters out the
        // stale one and uses the fresh one's verdict.
        val mode =
            BtAudioPolicy.classifyFromInputs(
                hfpProxyMacs = listOf("OLD:STALE:MAC", "NEW:LIVE:MAC"),
                aclDisconnectedMacs = setOf("OLD:STALE:MAC"),
                hasScoOutput = true,
                hasA2dpOutput = false,
            )
        assertEquals(BtAudioMode.HFP_ONLY, mode)
    }

    @Test
    fun `HFP precedes A2DP — both present together, classify HFP_ONLY`() {
        // Most BT speakermics expose BOTH profiles. HFP wins because
        // TX needs SCO; A2DP is moot for the same device.
        val mode =
            BtAudioPolicy.classifyFromInputs(
                hfpProxyMacs = listOf("AA:11"),
                aclDisconnectedMacs = emptySet(),
                hasScoOutput = true,
                hasA2dpOutput = true,
            )
        assertEquals(BtAudioMode.HFP_ONLY, mode)
    }

    @Test
    fun `SCO endpoint precedes A2DP endpoint when proxy empty`() {
        // Edge case: proxy is empty, audio HAL shows BOTH endpoints
        // present (some OEMs). SCO wins — same reasoning as above.
        val mode =
            BtAudioPolicy.classifyFromInputs(
                hfpProxyMacs = emptyList(),
                aclDisconnectedMacs = emptySet(),
                hasScoOutput = true,
                hasA2dpOutput = true,
            )
        assertEquals(BtAudioMode.HFP_ONLY, mode)
    }
}
