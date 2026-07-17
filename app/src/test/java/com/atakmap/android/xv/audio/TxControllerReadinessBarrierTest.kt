package com.atakmap.android.xv.audio

import android.content.Context
import android.media.AudioManager
import androidx.test.core.app.ApplicationProvider
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Coverage for the TX readiness barrier (2026-07-17): TPT must not
 * play until EVERY component of the transmit path is verifiably
 * ready — the self-managed Telecom session (ACTIVE + audio route
 * decided), the BT SCO link when the route needs it, and the mic
 * actually delivering non-silent frames.
 *
 * Field capture that motivated this (Galaxy Tab Active5, cold start):
 * PTT-down placed the Telecom call and started AudioRecord in
 * parallel; when the call activated ~1.3 s later the OEM HAL
 * re-provisioned the input path, the in-flight record stalled 2.6 s,
 * and the rest of the burst alternated ~300 ms blocks of real audio
 * and hard digital zeros. The barrier serializes: call settles →
 * capture starts → PRIMING verifies real frames → TPT.
 *
 * Three surfaces under test:
 *   1. ACQUIRING_CALL parking + its release paths (notifyTelecomReady,
 *      notifyTelecomUnavailable, stop-abandon).
 *   2. Cold-burst PRIMING gates on the cold-CALL axis (the cold-SCO
 *      axis is pinned by TxControllerColdScoWarmupTest).
 *   3. The zero-frame rule: literal-silence frames never satisfy the
 *      alive gate on a cold burst (a dead input path delivers exact
 *      zeros; a real mic idles at rms 1-15).
 */
@RunWith(RobolectricTestRunner::class)
class TxControllerReadinessBarrierTest {
    private lateinit var ctx: Context
    private lateinit var audioManager: AudioManager

    // Mutable Telecom-settled flag read by the controller's lambda —
    // tests flip it to simulate the call landing.
    private var telecomSettled: Boolean = false

    @Before
    fun setup() {
        ctx = ApplicationProvider.getApplicationContext()
        audioManager = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        telecomSettled = false
    }

    private fun buildController(): TxController =
        TxController(
            scoLink = mockk(relaxed = true),
            btPolicy = mockk(relaxed = true), // classify() → NONE → non-SCO path
            tptPlayer = mockk(relaxed = true),
            audioCaptureFactory = { _ -> mockk(relaxed = true) },
            opusEncoderFactory = { mockk(relaxed = true) },
            audioManager = audioManager,
            audioController = mockk(relaxed = true),
            sendOpus = { _, _ -> },
            telecomReady = { telecomSettled },
        )

    // ============================================================
    // ACQUIRING_CALL parking and release
    // ============================================================

    @Test
    fun `start with unsettled Telecom parks in ACQUIRING_CALL — no TPT, no priming`() {
        val tx = buildController()

        tx.start(slot = 0)

        assertEquals(
            "fresh Telecom session in flight must hold the burst before PRIMING",
            TxController.State.ACQUIRING_CALL,
            tx.currentStateForTest(),
        )
    }

    @Test
    fun `notifyTelecomReady releases a parked burst into PRIMING`() {
        val tx = buildController()
        tx.start(slot = 0)
        assertEquals(TxController.State.ACQUIRING_CALL, tx.currentStateForTest())

        telecomSettled = true
        tx.notifyTelecomReady()

        assertEquals(
            "route-change fanout must release ACQUIRING_CALL into PRIMING",
            TxController.State.PRIMING,
            tx.currentStateForTest(),
        )
    }

    @Test
    fun `settled Telecom session skips ACQUIRING_CALL entirely — warm re-key stays fast`() {
        telecomSettled = true
        val tx = buildController()

        tx.start(slot = 0)

        assertEquals(
            "warm re-key (call live + routed) must go straight to PRIMING",
            TxController.State.PRIMING,
            tx.currentStateForTest(),
        )
    }

    @Test
    fun `notifyTelecomReady is a no-op outside ACQUIRING_CALL`() {
        val tx = buildController()

        tx.notifyTelecomReady()

        assertEquals(
            "a stray route fanout with no burst waiting must not start a TX cycle",
            TxController.State.IDLE,
            tx.currentStateForTest(),
        )
    }

    @Test
    fun `notifyTelecomUnavailable releases the parked burst without a settled call`() {
        val tx = buildController()
        tx.start(slot = 0)
        assertEquals(TxController.State.ACQUIRING_CALL, tx.currentStateForTest())

        // telecomSettled stays false — Telecom refused the placeCall.
        tx.notifyTelecomUnavailable()

        assertEquals(
            "place-failure must fall back to the legacy no-Telecom path, not dead-air",
            TxController.State.PRIMING,
            tx.currentStateForTest(),
        )
    }

    @Test
    fun `PTT release while parked in ACQUIRING_CALL abandons cleanly to IDLE`() {
        val tx = buildController()
        tx.start(slot = 0)
        assertEquals(TxController.State.ACQUIRING_CALL, tx.currentStateForTest())

        tx.stop()

        assertEquals(
            "release during the Telecom wait must abandon without TPT",
            TxController.State.IDLE,
            tx.currentStateForTest(),
        )
    }

    // ============================================================
    // Cold-call PRIMING gates — ramp-tolerant thresholds + the
    // zero-frame rule.
    // ============================================================

    @Test
    fun `cold-call priming — hard-zero frames never satisfy the alive gate`() {
        val tx = buildController()
        tx.setStateForTest(TxController.State.PRIMING)
        tx.setPrimingGatesForTest(coldSco = false, coldCall = true)

        // 40 frames of literal digital silence — more than the 30-frame
        // cold gate. A dead input path (pre-reroute AudioRecord, muted
        // HAL) delivers exactly this; declaring "mic alive" on it is
        // how the first word went out silent on the 2026-07-17 capture.
        val silent = ShortArray(480) { 0 }
        repeat(40) { tx.onPcmFrameForTest(silent) }

        assertEquals(
            "literal-zero frames are evidence of a dead path, not a live mic",
            TxController.State.PRIMING,
            tx.currentStateForTest(),
        )
    }

    @Test
    fun `cold-call priming — noise-floor frames satisfy the alive gate at the cold frame count`() {
        val tx = buildController()
        tx.setStateForTest(TxController.State.PRIMING)
        tx.setPrimingGatesForTest(coldSco = false, coldCall = true)

        // rms ≈ 3: a real mic's idle noise floor — below the cold
        // speech threshold (200) but non-zero, so each frame counts
        // toward the 30-frame alive gate.
        val noiseFloor = ShortArray(480) { 3 }
        repeat(29) {
            tx.onPcmFrameForTest(noiseFloor)
            assertEquals(
                "must still be PRIMING after ${it + 1} noise-floor frames",
                TxController.State.PRIMING,
                tx.currentStateForTest(),
            )
        }
        tx.onPcmFrameForTest(noiseFloor)

        assertEquals(
            "30 non-silent frames = mic verifiably alive on a cold burst",
            TxController.State.TPT,
            tx.currentStateForTest(),
        )
    }

    @Test
    fun `cold-call priming — zero frames do not advance the count toward the alive gate`() {
        val tx = buildController()
        tx.setStateForTest(TxController.State.PRIMING)
        tx.setPrimingGatesForTest(coldSco = false, coldCall = true)

        // Interleave: 20 zeros, 29 noise-floor, 20 zeros. Only the 29
        // non-silent frames count — one short of the 30-frame gate.
        val silent = ShortArray(480) { 0 }
        val noiseFloor = ShortArray(480) { 3 }
        repeat(20) { tx.onPcmFrameForTest(silent) }
        repeat(29) { tx.onPcmFrameForTest(noiseFloor) }
        repeat(20) { tx.onPcmFrameForTest(silent) }
        assertEquals(
            "29 non-silent frames out of 69 total must NOT complete the 30-frame cold gate",
            TxController.State.PRIMING,
            tx.currentStateForTest(),
        )

        // The 30th non-silent frame completes it.
        tx.onPcmFrameForTest(noiseFloor)
        assertEquals(TxController.State.TPT, tx.currentStateForTest())
    }

    @Test
    fun `cold-call priming — speech-level frame completes immediately`() {
        val tx = buildController()
        tx.setStateForTest(TxController.State.PRIMING)
        tx.setPrimingGatesForTest(coldSco = false, coldCall = true)

        // rms well above the cold speech threshold (200) — one frame of
        // actual speech is proof enough regardless of frame count.
        val speech = ShortArray(480) { i -> (10_000.0 * kotlin.math.sin(i * 0.2)).toInt().toShort() }
        tx.onPcmFrameForTest(speech)

        assertEquals(
            "speech-detected must complete cold priming on the first frame",
            TxController.State.TPT,
            tx.currentStateForTest(),
        )
    }

    @Test
    fun `warm priming keeps the any-frame count — silent frames still complete after 5`() {
        // Regression guard for the warm path: Samsung's platform DSP
        // emits exact-zero frames in speech gaps on a HEALTHY settled
        // stream, and the Surface Duo idles silent until speech. Warm
        // bursts must not stall on zeros (2026-07-13 lesson: slow warm
        // gates = operator releases before TX starts).
        val tx = buildController()
        tx.setStateForTest(TxController.State.PRIMING)
        tx.setPrimingGatesForTest(coldSco = false, coldCall = false)

        val silent = ShortArray(480) { 0 }
        repeat(5) { tx.onPcmFrameForTest(silent) }

        assertEquals(
            "warm burst: 5 frames of any amplitude confirm the pipeline is alive",
            TxController.State.TPT,
            tx.currentStateForTest(),
        )
    }
}
