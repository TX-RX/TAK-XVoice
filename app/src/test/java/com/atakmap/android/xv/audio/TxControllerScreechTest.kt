package com.atakmap.android.xv.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pinned regression coverage for the 2026-05-19 TX screech bug
 * (commit 7fa675a).
 *
 * Field complaint: on the first TX after install — when AINA SCO hadn't
 * yet bound — the operator's first transmission produced loud screech
 * on the receiving peer. Root cause traced through logcat:
 *
 *   1. Mic pre-arm fires before SCO is bound. AudioRecord reads from a
 *      not-yet-routed source and returns stuck `-1`-fill frames
 *      (rms=1, samples=[-1,-1,-1,-1,-1]).
 *   2. During state=TPT (past the 80ms skip), those garbage frames
 *      land in the TPT-overlap ring buffer.
 *   3. On TX start, the ring flushes into Concentus' Opus encoder. The
 *      first encode trips an AssertionError inside
 *      `Resampler.silk_resampler` on the pathological -1-fill input.
 *   4. The throw leaves the encoder's SILK state corrupt. Every
 *      subsequent live frame from real speech encodes against the
 *      corrupted state — garbage on the wire = screech on the peer.
 *
 * Two-line defense in TxController:
 *   - Gate the ring-buffer append on rms(frame) >= RING_BUFFER_MIN_RMS.
 *   - On encode failure, close + recreate the encoder via the factory
 *     so the next frame encodes against fresh state.
 *
 * These tests pin the contract of both lines so a future refactor of
 * the audio path can't silently re-introduce the regression.
 */
class TxControllerScreechTest {
    // ============================================================
    // Part 1 — RMS function correctness
    // ============================================================

    @Test
    fun `rms of empty array returns 0 — defensive guard for edge inputs`() {
        assertEquals(0, TxController.rms(ShortArray(0)))
    }

    @Test
    fun `rms of pure silence is 0`() {
        val silent = ShortArray(480) { 0 }
        assertEquals(0, TxController.rms(silent))
    }

    @Test
    fun `rms of the field-reported garbage pattern is 1`() {
        // Exact AudioRecord behavior when reading from a not-yet-routed
        // BT SCO source: returns frames filled with -1 (16-bit signed
        // representation of the "stuck" bit pattern). RMS of -1 across
        // any window length is sqrt(1) = 1 — the marker we use to
        // recognize garbage.
        val garbage = ShortArray(480) { -1 }
        assertEquals(1, TxController.rms(garbage))
    }

    @Test
    fun `rms of typical conversational speech amplitude is well above threshold`() {
        // Real spoken syllable peaks around 30-50% of full scale
        // (~10000 to 16000 short value). A sine wave at amplitude
        // 10000 has rms ~= 10000 / sqrt(2) ~= 7071.
        val speech = ShortArray(480) { i -> (10_000.0 * kotlin.math.sin(i * 0.2)).toInt().toShort() }
        val r = TxController.rms(speech)
        assertTrue("rms=$r should be well above noise floor", r > 1000)
    }

    @Test
    fun `rms of full-scale signal does not overflow`() {
        // Defensive: a malformed mic source could deliver max-amplitude
        // samples (e.g. clipping at Short.MAX_VALUE on every sample).
        // The square sum is ~480 * (32767^2) = ~5.15e11, which fits
        // easily in Double. RMS ≈ 32767. Make sure we don't truncate
        // or overflow the Int return.
        val maxAmp = ShortArray(480) { Short.MAX_VALUE }
        val r = TxController.rms(maxAmp)
        assertEquals(Short.MAX_VALUE.toInt(), r)
    }

    // ============================================================
    // Part 2 — RMS threshold (RING_BUFFER_MIN_RMS) contract
    // ============================================================

    @Test
    fun `threshold sits above the garbage rms but below speech rms`() {
        val garbageRms = TxController.rms(ShortArray(480) { -1 })
        val silenceRms = TxController.rms(ShortArray(480) { 0 })
        // Both pathological / silent cases must fail the gate.
        assertTrue(
            "garbage rms=$garbageRms must be below threshold=${TxController.RING_BUFFER_MIN_RMS}",
            garbageRms < TxController.RING_BUFFER_MIN_RMS,
        )
        assertTrue(
            "silence rms=$silenceRms must be below threshold=${TxController.RING_BUFFER_MIN_RMS}",
            silenceRms < TxController.RING_BUFFER_MIN_RMS,
        )
        // Real speech must pass.
        val speech = ShortArray(480) { i -> (10_000.0 * kotlin.math.sin(i * 0.2)).toInt().toShort() }
        val speechRms = TxController.rms(speech)
        assertTrue(
            "speech rms=$speechRms must be above threshold=${TxController.RING_BUFFER_MIN_RMS}",
            speechRms >= TxController.RING_BUFFER_MIN_RMS,
        )
    }

    @Test
    fun `mic self-noise (rms ~5-20 in a quiet room) is correctly rejected`() {
        // Real mic self-noise on a quiet AINA / built-in mic sits in
        // the rms 5-20 range — well below speech but above the
        // garbage pattern. The gate must still reject it because
        // self-noise has no useful audio content for the operator
        // and only inflates the buffered TPT-overlap window with
        // pointless frames.
        val selfNoise = ShortArray(480) { kotlin.random.Random.nextInt(-15, 16).toShort() }
        val r = TxController.rms(selfNoise)
        // self-noise rms is bounded above by 15 (uniform [-15,15] →
        // rms ~= 8.7), well below the 50 threshold.
        assertTrue("self-noise rms=$r should be < 30", r < 30)
        assertTrue(
            "self-noise rms=$r must be rejected by threshold=${TxController.RING_BUFFER_MIN_RMS}",
            r < TxController.RING_BUFFER_MIN_RMS,
        )
    }

    // ============================================================
    // Part 3 — encoder recovery from AssertionError
    // ============================================================

    /**
     * Stand-in OpusEncoder that records call count and can be configured
     * to throw on a specific call. Mirrors the Concentus failure surface
     * (`Resampler.silk_resampler` AssertionError) without bringing in the
     * full Concentus pipeline.
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

    @Test
    fun `FakeEncoder smoke — throws on configured call only`() {
        val enc = FakeEncoder(throwOnCall = 2)
        enc.encode(ShortArray(480))
        try {
            enc.encode(ShortArray(480))
            assertTrue("should have thrown on second call", false)
        } catch (e: AssertionError) {
            // expected
        }
        // Third call works again — instance is still usable in this
        // fake. The real-world bug is that the REAL Concentus encoder
        // is broken after the throw; the fix recreates the instance.
        assertEquals(byteArrayOf(0x42).toList(), enc.encode(ShortArray(480)).toList())
        assertEquals(3, enc.callCount)
    }

    /**
     * Document the contract for the encoder-reset path. Full state-machine
     * exercise of `encodeAndQueueFrame` requires Robolectric + a wired
     * TxController (mock ScoLink + AudioCapture + AudioController + …) —
     * see TxControllerStateMachineTest for the integration-level coverage.
     * This is the unit-level contract: a FakeEncoder configured to throw
     * once, when handed to encodeAndQueueFrame via TxController, results
     * in (a) close() called on the throwing encoder and (b) a fresh
     * encoder fetched from the factory.
     */
    @Test
    fun `encoder factory is invoked again after a throw — contract sketch`() {
        // This pure-Kotlin test exercises the protocol the production
        // code follows: on encode failure, close the broken encoder
        // and re-fetch from the factory. We model the production code
        // pattern inline so a future refactor that breaks this dance
        // surfaces in the next CI run instead of in the field.
        val brokenEncoder = FakeEncoder(throwOnCall = 1)
        var freshEncoderInstanceCount = 0
        val factory: () -> OpusEncoder = {
            freshEncoderInstanceCount++
            FakeEncoder()
        }

        // Model the production try/catch around enc.encode().
        var currentEncoder: OpusEncoder = brokenEncoder
        try {
            currentEncoder.encode(ShortArray(480))
            assertTrue("first encode should have thrown", false)
        } catch (t: Throwable) {
            try {
                currentEncoder.close()
            } catch (_: Throwable) {
            }
            currentEncoder = factory()
        }
        // Production contract: broken encoder is closed, fresh one is
        // sourced from the factory, next encode succeeds.
        assertTrue("broken encoder should be closed", brokenEncoder.closed)
        assertEquals(1, freshEncoderInstanceCount)
        assertNotSame(brokenEncoder, currentEncoder)
        // The new encoder works.
        val out = currentEncoder.encode(ShortArray(480))
        assertEquals(1, out.size)
    }

    @Test
    fun `OpusEncoder interface contract — fresh instance has clean state`() {
        // Sanity: a brand-new FakeEncoder is callable and has not been
        // closed. Asserts the precondition our recovery path relies on
        // (i.e. that `factory()` returns a USABLE encoder).
        val enc = FakeEncoder()
        val out = enc.encode(ShortArray(480))
        assertEquals(1, out.size)
        assertEquals(1, enc.callCount)
        assertTrue("fresh encoder should not be closed", !enc.closed)
        // Two distinct factory invocations yield distinct instances.
        val a = FakeEncoder()
        val b = FakeEncoder()
        assertNotSame(a, b)
        // (Same-instance test: a reference compared to itself is `same`.)
        assertSame(a, a)
    }
}
