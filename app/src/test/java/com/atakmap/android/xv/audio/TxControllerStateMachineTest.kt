package com.atakmap.android.xv.audio

import android.content.Context
import android.media.AudioManager
import androidx.test.core.app.ApplicationProvider
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * State-machine coverage for TxController — drives the production
 * `onPcmFrame` entry through forced states using the @VisibleForTesting
 * seams (`setStateForTest`, `setEncoderForTest`, etc.) instead of
 * standing up the full ScoLink + AudioCapture + TptPlayer pipeline.
 *
 * The seams are the minimum needed to exercise:
 *
 *   - The TPT-overlap ring buffer's RMS gate (the 2026-05-19 screech
 *     fix) end-to-end through `onPcmFrame`, not just the helper in
 *     [TxControllerScreechTest].
 *   - The encoder-reset path on encode failure, end-to-end through
 *     `encodeAndQueueFrame` in state=TRANSMITTING.
 *   - The frame-drop branches in IDLE / PRIMING / ACQUIRING_SCO.
 *
 * Robolectric is required because TxController's constructor pulls
 * `AudioManager` from a real Context; everything else is mocked
 * (relaxed-mode MockK) since the smoke test already proved that
 * approach stands up cleanly.
 */
@RunWith(RobolectricTestRunner::class)
class TxControllerStateMachineTest {
    private lateinit var ctx: Context
    private lateinit var audioManager: AudioManager
    private val sentOpusFrames = mutableListOf<Pair<ByteArray, Int>>()

    @Before
    fun setup() {
        ctx = ApplicationProvider.getApplicationContext()
        audioManager = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        sentOpusFrames.clear()
    }

    /** Minimal TxController with all collaborators mocked relaxed-mode. */
    private fun buildController(opusEncoderFactory: () -> OpusEncoder = { FakeEncoder() }): TxController =
        TxController(
            scoLink = mockk(relaxed = true),
            btPolicy = mockk(relaxed = true),
            tptPlayer = mockk(relaxed = true),
            audioCaptureFactory = { _ -> mockk(relaxed = true) },
            opusEncoderFactory = opusEncoderFactory,
            audioManager = audioManager,
            audioController = mockk(relaxed = true),
            sendOpus = { opus, slot -> sentOpusFrames += opus to slot },
        )

    // ============================================================
    // RMS gate in state=TPT — the central screech fix.
    // ============================================================

    @Test
    fun `state=TPT past skip window — garbage frame is rejected by RMS gate`() {
        val tx = buildController()
        // Past the TPT_RING_SKIP_MS (80ms) window so the gate is active.
        tx.setStateForTest(TxController.State.TPT, tptEnteredAtMs = System.currentTimeMillis() - 200L)

        val garbage = ShortArray(480) { -1 } // rms=1, the field-reported pattern
        tx.onPcmFrameForTest(garbage)

        assertEquals(
            "garbage frame must not enter pre-TX ring",
            0,
            tx.preTxBufferSizeForTest(),
        )
    }

    @Test
    fun `state=TPT past skip window — frames are dropped (ring buffer disabled 2026-05-21)`() {
        // The TPT-overlap ring buffer was disabled outright after a
        // recurring cold-SCO screech bug — flushing those frames into
        // Opus corrupts the encoder regardless of RMS gating. Pinned
        // here as the new contract: state=TPT frames are dropped, no
        // ring buffer accumulates. See the long comment in
        // TxController.onPcmFrame for the rationale and the re-enable
        // checklist.
        val tx = buildController()
        tx.setStateForTest(TxController.State.TPT, tptEnteredAtMs = System.currentTimeMillis() - 200L)

        val speech = ShortArray(480) { i -> (10_000.0 * kotlin.math.sin(i * 0.2)).toInt().toShort() }
        tx.onPcmFrameForTest(speech)

        assertEquals(
            "TPT frames must be dropped, not buffered",
            0,
            tx.preTxBufferSizeForTest(),
        )
    }

    @Test
    fun `state=TPT during skip window — even speech is dropped (TPT bleed defense)`() {
        val tx = buildController()
        // tptEnteredAtMs = now → elapsed = 0, well inside the 80ms skip.
        tx.setStateForTest(TxController.State.TPT, tptEnteredAtMs = System.currentTimeMillis())

        val speech = ShortArray(480) { i -> (10_000.0 * kotlin.math.sin(i * 0.2)).toInt().toShort() }
        tx.onPcmFrameForTest(speech)

        assertEquals(
            "frame within skip window must be dropped, even if loud",
            0,
            tx.preTxBufferSizeForTest(),
        )
    }

    @Test
    fun `state=TPT — ring buffer stays empty no matter how many frames arrive`() {
        // Companion to the disabled-buffer change. Even under sustained
        // TPT-state frame delivery, the buffer remains empty.
        val tx = buildController()
        tx.setStateForTest(TxController.State.TPT, tptEnteredAtMs = System.currentTimeMillis() - 200L)
        val speech = ShortArray(480) { 1_000 }
        repeat(15) { tx.onPcmFrameForTest(speech) }
        assertEquals(0, tx.preTxBufferSizeForTest())
    }

    // ============================================================
    // Encoder-reset path in state=TRANSMITTING — the second half of
    // the screech defense.
    // ============================================================

    @Test
    fun `state=TRANSMITTING — encoder failure triggers close + factory recall`() {
        val brokenEncoder = FakeEncoder(throwOnCall = 1)
        var factoryCalls = 0
        val factory: () -> OpusEncoder = {
            factoryCalls++
            if (factoryCalls == 1) brokenEncoder else FakeEncoder()
        }
        val tx = buildController(opusEncoderFactory = factory)
        tx.setStateForTest(TxController.State.TRANSMITTING)
        tx.setEncoderForTest(factory())
        // factory has been called once now for the initial encoder.
        assertEquals(1, factoryCalls)

        // The throw inside onPcmFrame's encode() should trigger the
        // recovery path: close the broken encoder, fetch a fresh one
        // from the factory.
        tx.onPcmFrameForTest(ShortArray(480) { 1_000 })

        assertTrue("broken encoder must be close()'d on encode failure", brokenEncoder.closed)
        assertEquals("factory must be invoked again to source a fresh encoder", 2, factoryCalls)
        assertNotSame(
            "current encoder must NOT be the broken instance",
            brokenEncoder,
            tx.currentEncoderForTest(),
        )
    }

    @Test
    fun `state=TRANSMITTING — next frame after reset encodes against the fresh encoder`() {
        val brokenEncoder = FakeEncoder(throwOnCall = 1)
        val freshEncoder = FakeEncoder()
        var factoryCalls = 0
        val factory: () -> OpusEncoder = {
            factoryCalls++
            if (factoryCalls == 1) brokenEncoder else freshEncoder
        }
        val tx = buildController(opusEncoderFactory = factory)
        tx.setStateForTest(TxController.State.TRANSMITTING)
        tx.setEncoderForTest(factory())

        // Trigger the throw → recovery.
        tx.onPcmFrameForTest(ShortArray(480) { 1_000 })
        assertSame("after recovery, encoder field points at the fresh instance", freshEncoder, tx.currentEncoderForTest())

        // Subsequent frames should encode against the fresh encoder.
        // Fire enough frames that we exceed TRAILING_FRAMES (6) and start
        // pushing on the wire.
        repeat(10) { tx.onPcmFrameForTest(ShortArray(480) { 1_000 }) }
        // freshEncoder should have seen all 10 of those calls.
        assertEquals(10, freshEncoder.callCount)
        // The broken encoder was never called again after its failure
        // (would indicate the reset didn't take).
        assertEquals(1, brokenEncoder.callCount)
    }

    @Test
    fun `state=TRANSMITTING — trailing buffer holds first frames before wire emission`() {
        val tx = buildController()
        tx.setStateForTest(TxController.State.TRANSMITTING)
        tx.setEncoderForTest(FakeEncoder())

        // TRAILING_FRAMES = 6: the first 6 frames fill the trailing
        // ring and don't go on the wire until the 7th frame pushes
        // the oldest out.
        repeat(6) { tx.onPcmFrameForTest(ShortArray(480) { 1_000 }) }
        assertEquals("first 6 frames stay in trailing buffer", 0, sentOpusFrames.size)

        tx.onPcmFrameForTest(ShortArray(480) { 1_000 })
        assertEquals("7th frame pushes the 1st frame out to sendOpus", 1, sentOpusFrames.size)
    }

    // ============================================================
    // Drop branches — frames in non-active states must be no-ops.
    // ============================================================

    @Test
    fun `state=IDLE — onPcmFrame is a silent no-op`() {
        val tx = buildController()
        // Default state after construction is IDLE; setStateForTest just
        // makes the precondition explicit.
        tx.setStateForTest(TxController.State.IDLE)

        tx.onPcmFrameForTest(ShortArray(480) { 1_000 })

        assertEquals(0, tx.preTxBufferSizeForTest())
        assertEquals(0, sentOpusFrames.size)
        assertNull("encoder stays unallocated", tx.currentEncoderForTest())
    }

    @Test
    fun `state=ACQUIRING_SCO — onPcmFrame drops with warning, no wire activity`() {
        val tx = buildController()
        tx.setStateForTest(TxController.State.ACQUIRING_SCO)

        tx.onPcmFrameForTest(ShortArray(480) { 1_000 })

        assertEquals(0, tx.preTxBufferSizeForTest())
        assertEquals(0, sentOpusFrames.size)
    }

    // ============================================================
    // PRIMING completion — mic-alive detection via RMS OR frame count.
    // ============================================================

    @Test
    fun `state=PRIMING — speech-amplitude frame completes priming and transitions to TPT`() {
        val tx = buildController()
        tx.setStateForTest(TxController.State.PRIMING)

        // RMS above MIC_PRIMING_RMS_THRESHOLD (=5) — speech-detected path.
        val speech = ShortArray(480) { i -> (10_000.0 * kotlin.math.sin(i * 0.2)).toInt().toShort() }
        tx.onPcmFrameForTest(speech)

        // PRIMING → TPT triggered by startTpt(); no SCO setup required.
        assertEquals(
            "speech-detected mic-alive must transition PRIMING → TPT",
            TxController.State.TPT,
            tx.currentStateForTest(),
        )
    }

    @Test
    fun `state=PRIMING — silent frames complete priming after MIC_PRIMING_MIN_FRAMES_ALIVE`() {
        val tx = buildController()
        tx.setStateForTest(TxController.State.PRIMING)

        // Surface Duo's built-in mic produces silent (rms=0) frames until
        // the operator actually speaks; PRIMING used to stall the full
        // 1.5 s timeout in that case, making the radio feel sluggish.
        // The frames-confirm-alive alternative completes PRIMING when
        // the capture pipeline has delivered MIN_FRAMES (=5) regardless
        // of RMS.
        val silent = ShortArray(480) { 0 }
        repeat(4) {
            tx.onPcmFrameForTest(silent)
            assertEquals(
                "after $it silent frames, must still be PRIMING",
                TxController.State.PRIMING,
                tx.currentStateForTest(),
            )
        }
        // 5th silent frame trips the frames-confirm-alive threshold.
        tx.onPcmFrameForTest(silent)
        assertEquals(
            "5 silent frames must transition PRIMING → TPT via frames-confirm-alive",
            TxController.State.TPT,
            tx.currentStateForTest(),
        )
    }

    @Test
    fun `state=PRIMING — single silent frame does not complete priming`() {
        val tx = buildController()
        tx.setStateForTest(TxController.State.PRIMING)
        val silent = ShortArray(480) { 0 }

        tx.onPcmFrameForTest(silent)

        assertEquals(
            "1 silent frame is not enough to confirm mic-alive",
            TxController.State.PRIMING,
            tx.currentStateForTest(),
        )
    }

    @Test
    fun `state=PRIMING — exactly-threshold RMS frame completes priming on first try`() {
        val tx = buildController()
        tx.setStateForTest(TxController.State.PRIMING)
        // MIC_PRIMING_RMS_THRESHOLD = 5. A constant-amplitude-5 frame
        // produces rms = 5 — exactly at threshold. The check is `>=`
        // so this must satisfy speech-detected and trip immediately.
        val borderline = ShortArray(480) { 5 }

        tx.onPcmFrameForTest(borderline)

        assertEquals(
            "rms=threshold (=5) must satisfy the >= check",
            TxController.State.TPT,
            tx.currentStateForTest(),
        )
    }

    /**
     * Stand-in OpusEncoder. Mirrors the Concentus failure surface
     * (Resampler AssertionError on pathological input) without bringing
     * in the full Concentus pipeline. Defined here (not shared with
     * TxControllerScreechTest) so the two test files stay independent
     * — easier to delete or refactor one without dragging the other.
     */
    private class FakeEncoder(
        val throwOnCall: Int = -1,
        val throwable: Throwable = AssertionError("simulated Resampler.silk_resampler"),
    ) : OpusEncoder {
        override val sampleRateHz: Int = 48_000
        override val channels: Int = 1
        override val frameSamples: Int = 480

        var callCount = 0
            private set
        var closed = false
            private set

        override fun encode(pcm: ShortArray): ByteArray {
            callCount++
            if (callCount == throwOnCall) throw throwable
            return byteArrayOf(0x42)
        }

        override fun close() {
            closed = true
        }
    }
}
