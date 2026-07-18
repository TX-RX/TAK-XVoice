package com.atakmap.android.xv.audio

import android.content.Context
import android.media.AudioManager
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

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

    private fun buildController(
        tptPlayer: TptPlayer = mockk(relaxed = true),
        scoLink: ScoLink = mockk(relaxed = true),
    ): TxController =
        TxController(
            scoLink = scoLink,
            btPolicy = mockk(relaxed = true), // classify() → NONE → non-SCO path
            tptPlayer = tptPlayer,
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
    fun `cold-call priming — quiet completion routes to PROBING, not straight to TPT`() {
        // Priming's job on a cold-call burst is bare liveness (frames
        // flowing); the DSP-readiness verification belongs to PROBING.
        // Quiet frames (the Pixel's suppressed floor reads rms 1-11)
        // must therefore land in PROBING — releasing the tone here is
        // exactly how the first word went out silent on the 2026-07-17
        // capture.
        val tpt = mockk<TptPlayer>(relaxed = true)
        val tx = buildController(tptPlayer = tpt)
        tx.setStateForTest(TxController.State.PRIMING)
        tx.setPrimingGatesForTest(coldSco = false, coldCall = true)

        val noiseFloor = ShortArray(480) { 3 }
        repeat(5) { tx.onPcmFrameForTest(noiseFloor) }

        assertEquals(
            "liveness-only completion on a cold-call burst must enter PROBING",
            TxController.State.PROBING,
            tx.currentStateForTest(),
        )
        verify(exactly = 0) { tpt.play(any(), any(), any()) }
        verify(exactly = 0) { tpt.playInterrupt(any(), any()) }
    }

    @Test
    fun `cold-call priming — hard-zero frames also route to PROBING`() {
        // Samsung's converging DSP delivers literal zeros; the read
        // loop is alive, the path is not. PROBING is what tells those
        // apart — priming just needs to see frames flowing.
        val tx = buildController()
        tx.setStateForTest(TxController.State.PRIMING)
        tx.setPrimingGatesForTest(coldSco = false, coldCall = true)

        val silent = ShortArray(480) { 0 }
        repeat(5) { tx.onPcmFrameForTest(silent) }

        assertEquals(TxController.State.PROBING, tx.currentStateForTest())
    }

    @Test
    fun `cold-SCO priming still requires non-silent frames, then routes to PROBING`() {
        // The non-silent rule stays on the SCO axis: a cold BT chipset
        // that only produces zeros is not ready. Completion then goes
        // to PROBING (2026-07-17: the fixed 300 ms AOC hold was a
        // guessed timer that let a Pixel+puck cold burst ship ~1.3 s of
        // near-silence; the probe measures instead).
        val tx = buildController()
        tx.setStateForTest(TxController.State.PRIMING)
        tx.setPrimingGatesForTest(coldSco = true, coldCall = false)

        val silent = ShortArray(480) { 0 }
        repeat(40) { tx.onPcmFrameForTest(silent) }
        assertEquals(
            "40 zero frames must not satisfy the 30-non-silent cold-SCO gate",
            TxController.State.PRIMING,
            tx.currentStateForTest(),
        )

        val noiseFloor = ShortArray(480) { 3 }
        repeat(30) { tx.onPcmFrameForTest(noiseFloor) }
        assertEquals(
            "30 non-silent frames complete the cold-SCO gate into PROBING",
            TxController.State.PROBING,
            tx.currentStateForTest(),
        )
    }

    @Test
    fun `cold-SCO priming — even genuine speech does NOT skip the probe`() {
        // 2026-07-17 Pixel cold-SCO: priming detected rms 586, yet the
        // TX head still went out near-silent — the BT chipset can burst
        // energy mid-ramp and then go quiet. On SCO the speech shortcut
        // is disabled; the probe is the only trusted readiness proof.
        val tx = buildController()
        tx.setStateForTest(TxController.State.PRIMING)
        tx.setPrimingGatesForTest(coldSco = true, coldCall = false)

        val speech = ShortArray(480) { i -> (10_000.0 * kotlin.math.sin(i * 0.2)).toInt().toShort() }
        tx.onPcmFrameForTest(speech)

        assertEquals(
            "speech during cold-SCO priming must still route to PROBING",
            TxController.State.PROBING,
            tx.currentStateForTest(),
        )
    }

    @Test
    fun `cold-call priming — genuine speech skips the probe entirely`() {
        // A speech-detected completion is its own end-to-end proof: the
        // operator's voice made it THROUGH the platform chain, so
        // probing would only add latency.
        val tx = buildController()
        tx.setStateForTest(TxController.State.PRIMING)
        tx.setPrimingGatesForTest(coldSco = false, coldCall = true)

        // rms well above the cold speech threshold (200).
        val speech = ShortArray(480) { i -> (10_000.0 * kotlin.math.sin(i * 0.2)).toInt().toShort() }
        tx.onPcmFrameForTest(speech)

        assertEquals(
            "speech through the chain proves the path — straight to TPT",
            TxController.State.TPT,
            tx.currentStateForTest(),
        )
    }

    // ============================================================
    // PROBING — measured DSP readiness.
    // ============================================================

    @Test
    fun `PROBING — sustained energy at the heard threshold releases into TPT`() {
        val tx = buildController()
        tx.setStateForTest(TxController.State.PRIMING)
        tx.setPrimingGatesForTest(coldSco = false, coldCall = true)
        val silent = ShortArray(480) { 0 }
        repeat(5) { tx.onPcmFrameForTest(silent) }
        assertEquals(TxController.State.PROBING, tx.currentStateForTest())

        // The probe tick (or any ambient sound) arriving through the
        // capture path — constant amplitude at the threshold gives
        // rms == PROBE_HEARD_RMS_THRESHOLD; the check is >=, sustained
        // for PROBE_HEARD_CONSECUTIVE_FRAMES.
        val heard = ShortArray(480) { TxController.PROBE_HEARD_RMS_THRESHOLD.toShort() }
        repeat(TxController.PROBE_HEARD_CONSECUTIVE_FRAMES - 1) {
            tx.onPcmFrameForTest(heard)
            assertEquals(
                "fewer than the sustained requirement must keep probing",
                TxController.State.PROBING,
                tx.currentStateForTest(),
            )
        }
        tx.onPcmFrameForTest(heard)

        assertEquals(
            "sustained energy through the chain = path verified = TPT",
            TxController.State.TPT,
            tx.currentStateForTest(),
        )
    }

    @Test
    fun `PROBING — a single-frame blip followed by suppression does not release the tone`() {
        // The Samsung false-positive (2026-07-17 17:27 burst): the tick
        // leaked through ONE frame at rms 69 while the DSP was only
        // partially open, then the chain clamped shut again. A blip
        // must reset the sustained counter, not fire the beep.
        val tx = buildController()
        tx.setStateForTest(TxController.State.PRIMING)
        tx.setPrimingGatesForTest(coldSco = false, coldCall = true)
        val silent = ShortArray(480) { 0 }
        repeat(5) { tx.onPcmFrameForTest(silent) }
        assertEquals(TxController.State.PROBING, tx.currentStateForTest())

        val blip = ShortArray(480) { 69 }
        val floor = ShortArray(480) { 2 }
        repeat(4) {
            tx.onPcmFrameForTest(blip)
            tx.onPcmFrameForTest(floor)
        }

        assertEquals(
            "blip-suppress cycles must never satisfy the sustained requirement",
            TxController.State.PROBING,
            tx.currentStateForTest(),
        )
    }

    @Test
    fun `PROBING — suppressed-floor frames do not release the tone`() {
        val tx = buildController()
        tx.setStateForTest(TxController.State.PRIMING)
        tx.setPrimingGatesForTest(coldSco = false, coldCall = true)
        val noiseFloor = ShortArray(480) { 3 }
        repeat(5) { tx.onPcmFrameForTest(noiseFloor) }
        assertEquals(TxController.State.PROBING, tx.currentStateForTest())

        // A closed DSP suppresses the probe tick to the floor — dozens
        // of floor frames must keep holding the tone (the ceiling
        // runnable, not frame count, bounds the wait).
        repeat(100) { tx.onPcmFrameForTest(noiseFloor) }

        assertEquals(TxController.State.PROBING, tx.currentStateForTest())
    }

    @Test
    fun `PROBING — tick routes over SCO whenever the live capture route is SCO`() {
        // 2026-07-17 17:48 field capture: a warm-SCO + cold-call burst
        // captured from the BT puck's mic while the tick played out the
        // phone path (probeUseSco was keyed off the cold-SCO flag) —
        // structural ceiling false-negative. The tick route must follow
        // the LIVE capture route.
        val tpt = mockk<TptPlayer>(relaxed = true)
        val sco = mockk<ScoLink>(relaxed = true)
        every { sco.state } returns ScoLink.State.CONNECTED
        val tx = buildController(tptPlayer = tpt, scoLink = sco)
        tx.setStateForTest(TxController.State.PRIMING)
        tx.setPrimingGatesForTest(coldSco = false, coldCall = true) // warm SCO, fresh call

        val silent = ShortArray(480) { 0 }
        repeat(5) { tx.onPcmFrameForTest(silent) }
        assertEquals(TxController.State.PROBING, tx.currentStateForTest())

        // Run the immediately-posted first tick.
        shadowOf(Looper.getMainLooper()).idle()

        verify { tpt.playProbeTick(useScoRoute = true) }
    }

    @Test
    fun `PROBING ceiling defers the tone by the fallback hold instead of firing immediately`() {
        // A heard probe is proof; a ceiling is ambiguity (AEC-cancelled
        // tick, OS-silenced mic, non-coupling route). The ceiling path
        // must grant the fallback hold before the beep rather than
        // inviting speech into a possibly-still-converging chain.
        val tpt = mockk<TptPlayer>(relaxed = true)
        val tx = buildController(tptPlayer = tpt)
        tx.setStateForTest(TxController.State.PRIMING)
        tx.setPrimingGatesForTest(coldSco = false, coldCall = true)
        val silent = ShortArray(480) { 0 }
        repeat(5) { tx.onPcmFrameForTest(silent) }
        assertEquals(TxController.State.PROBING, tx.currentStateForTest())

        val looper = shadowOf(Looper.getMainLooper())
        looper.idleFor(Duration.ofMillis(TxController.PROBE_CEILING_MS + 50))

        assertEquals(
            "ceiling must flip to TPT state (frames drop during the hold)",
            TxController.State.TPT,
            tx.currentStateForTest(),
        )
        verify(exactly = 0) { tpt.play(any(), any(), any()) }

        looper.idleFor(Duration.ofMillis(TxController.PROBE_CEILING_FALLBACK_HOLD_MS + 50))
        verify(exactly = 1) { tpt.play(any(), any(), any()) }
    }

    @Test
    fun `PTT release during PROBING abandons cleanly to IDLE`() {
        val tx = buildController()
        tx.setStateForTest(TxController.State.PRIMING)
        tx.setPrimingGatesForTest(coldSco = false, coldCall = true)
        val silent = ShortArray(480) { 0 }
        repeat(5) { tx.onPcmFrameForTest(silent) }
        assertEquals(TxController.State.PROBING, tx.currentStateForTest())

        tx.stop()

        assertEquals(TxController.State.IDLE, tx.currentStateForTest())
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
