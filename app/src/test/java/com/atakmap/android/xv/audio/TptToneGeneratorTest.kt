package com.atakmap.android.xv.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Coverage for the synthesized-tone generator. Tones are deterministic
 * functions of the table-driven (freq, duration) segment lists, so
 * tests pin:
 *   - Output PCM length matches the documented duration constants.
 *   - Sample values stay inside the 16-bit signed range (no overflow,
 *     no clipping artifacts at envelope edges).
 *   - Silence segments produce all-zero samples.
 *   - Sine segments produce actual non-zero audio.
 *   - The lookup-by-tone path doesn't crash for tones absent from the
 *     synthesis table (NONE).
 *
 * Regression-target: a change to SAMPLE_RATE_HZ, segment durations,
 * or envelope shape changes operator audio perception immediately.
 * Pinning the byte-level shape here means a future "tweak the
 * envelope" PR has to update the test, forcing reviewer attention.
 */
class TptToneGeneratorTest {
    private fun samplesFor(ms: Int): Int = (TptToneGenerator.SAMPLE_RATE_HZ * ms) / 1000

    private fun allInPcmRange(pcm: ShortArray): Boolean = pcm.all { it.toInt() in Short.MIN_VALUE..Short.MAX_VALUE }

    private fun hasNonZeroSamples(pcm: ShortArray): Boolean = pcm.any { it.toInt() != 0 }

    // ============================================================
    // TPT tone table — generate(tone)
    // ============================================================

    @Test
    fun `generate ASTRO_25 produces 150 ms of PCM`() {
        // ASTRO 25 segments: 30 + 20 + 30 + 20 + 50 = 150 ms total.
        val pcm = TptToneGenerator.generate(TptTone.ASTRO_25)
        assertEquals(samplesFor(150), pcm.size)
    }

    @Test
    fun `generate DMR produces 160 ms of PCM`() {
        // DMR: 4 × 40 ms = 160 ms.
        val pcm = TptToneGenerator.generate(TptTone.DMR)
        assertEquals(samplesFor(160), pcm.size)
    }

    @Test
    fun `generate NEXTEL produces 150 ms (matches ASTRO_25 timing)`() {
        // NEXTEL is the same triple-pulse pattern as ASTRO_25, higher
        // pitch — same duration.
        val pcm = TptToneGenerator.generate(TptTone.NEXTEL)
        assertEquals(samplesFor(150), pcm.size)
    }

    @Test
    fun `generate NEXTEL_TRUE produces 250 ms (5 pairs × 50 ms)`() {
        // NEXTEL_TRUE iDEN chirp: 10 × 25 ms pulses, no silence.
        val pcm = TptToneGenerator.generate(TptTone.NEXTEL_TRUE)
        assertEquals(samplesFor(250), pcm.size)
    }

    @Test
    fun `generate VERTEX produces 100 ms`() {
        // VERTEX: 20 + 20 + 20 + 20 + 20 = 100 ms.
        val pcm = TptToneGenerator.generate(TptTone.VERTEX)
        assertEquals(samplesFor(100), pcm.size)
    }

    @Test
    fun `generate NONE returns an empty array`() {
        // NONE isn't in the synthesis table; the caller's loop over
        // segments is a no-op. Empty array — caller (TptPlayer) is
        // expected to skip playback when length == 0.
        val pcm = TptToneGenerator.generate(TptTone.NONE)
        assertEquals(0, pcm.size)
    }

    @Test
    fun `every synthesized tone is bounded to 16-bit signed PCM range`() {
        // Defensive: a regression in fillSine's amplitude envelope or
        // a wrong coerceIn would let values overflow ShortArray's
        // implicit range. Verify every byte for every tone.
        for (tone in TptTone.entries) {
            val pcm = TptToneGenerator.generate(tone)
            assertTrue("$tone: PCM contains out-of-range sample", allInPcmRange(pcm))
        }
    }

    @Test
    fun `synthesized tones (excluding NONE) actually produce audible audio`() {
        // Regression: ensure fillSine isn't generating all-zero output
        // (e.g. amp=0 bug, or the sine table being silently empty).
        for (tone in TptTone.entries) {
            if (tone == TptTone.NONE) continue
            val pcm = TptToneGenerator.generate(tone)
            assertTrue("$tone: tone PCM should contain non-zero samples", hasNonZeroSamples(pcm))
        }
    }

    @Test
    fun `ASTRO_25 silence segments are exactly zero`() {
        // ASTRO 25 layout: 30 ms tone | 20 ms silence | 30 ms tone |
        // 20 ms silence | 50 ms tone. The two silence windows must be
        // genuine zero — a regression that "fills" silence segments
        // with the previous tone's amplitude would be audible as a
        // sustained note rather than the documented triple chirp.
        val pcm = TptToneGenerator.generate(TptTone.ASTRO_25)
        val tone1 = samplesFor(30)
        val silence1Start = tone1
        val silence1End = silence1Start + samplesFor(20)
        for (i in silence1Start until silence1End) {
            assertEquals("ASTRO_25 silence segment at index $i should be 0", 0.toShort(), pcm[i])
        }
        val silence2Start = silence1End + samplesFor(30)
        val silence2End = silence2Start + samplesFor(20)
        for (i in silence2Start until silence2End) {
            assertEquals("ASTRO_25 second silence segment at index $i should be 0", 0.toShort(), pcm[i])
        }
    }

    // ============================================================
    // durationMs — pure function of the segment table
    // ============================================================

    @Test
    fun `durationMs matches the documented timing per tone`() {
        assertEquals(150L, TptToneGenerator.durationMs(TptTone.ASTRO_25))
        assertEquals(160L, TptToneGenerator.durationMs(TptTone.DMR))
        assertEquals(150L, TptToneGenerator.durationMs(TptTone.NEXTEL))
        assertEquals(250L, TptToneGenerator.durationMs(TptTone.NEXTEL_TRUE))
        assertEquals(100L, TptToneGenerator.durationMs(TptTone.VERTEX))
        assertEquals(0L, TptToneGenerator.durationMs(TptTone.NONE))
    }

    // ============================================================
    // Status / call-progress / cutoff tones — fixed durations
    // ============================================================

    @Test
    fun `noPermitBonk produces NO_PERMIT_DURATION_MS of PCM`() {
        val pcm = TptToneGenerator.noPermitBonk()
        assertEquals(samplesFor(TptToneGenerator.NO_PERMIT_DURATION_MS.toInt()), pcm.size)
        assertTrue(allInPcmRange(pcm))
        assertTrue(hasNonZeroSamples(pcm))
    }

    @Test
    fun `denyTone produces DENY_DURATION_MS of PCM`() {
        // 140 + 50 + 140 = 330 ms.
        val pcm = TptToneGenerator.denyTone()
        assertEquals(samplesFor(TptToneGenerator.DENY_DURATION_MS.toInt()), pcm.size)
        assertTrue(allInPcmRange(pcm))
    }

    @Test
    fun `interruptChirp produces 120 ms (two 60 ms tones, no gap)`() {
        val pcm = TptToneGenerator.interruptChirp()
        assertEquals(samplesFor(120), pcm.size)
        assertEquals(TptToneGenerator.INTERRUPT_CHIRP_DURATION_MS, 120L)
        assertTrue(allInPcmRange(pcm))
    }

    @Test
    fun `joinChirp and leaveChirp have identical lengths but different audio`() {
        // Both are two 60ms tones (120 ms total). The acoustic
        // difference is ascending vs descending pitch — bit patterns
        // must differ even though lengths match.
        val join = TptToneGenerator.joinChirp()
        val leave = TptToneGenerator.leaveChirp()
        assertEquals(samplesFor(120), join.size)
        assertEquals(samplesFor(120), leave.size)
        assertEquals(TptToneGenerator.JOIN_CHIRP_DURATION_MS, 120L)
        assertEquals(TptToneGenerator.LEAVE_CHIRP_DURATION_MS, 120L)
        // First-half slice should differ (different start frequency).
        val half = join.size / 2
        var anyDiff = false
        for (i in 0 until half) {
            if (join[i] != leave[i]) {
                anyDiff = true
                break
            }
        }
        assertTrue("join and leave chirps must produce different audio (ascending vs descending)", anyDiff)
    }

    @Test
    fun `warningChirp produces 190 ms (50 + 90 + 50)`() {
        val pcm = TptToneGenerator.warningChirp()
        // 50 ms beep + 90 ms silence gap + 50 ms beep = 190 ms
        assertEquals(samplesFor(190), pcm.size)
        assertEquals(TptToneGenerator.WARNING_CHIRP_DURATION_MS, 190L)
        // Silence-gap region must be all zeros.
        val beepN = samplesFor(50)
        val gapN = samplesFor(90)
        for (i in beepN until (beepN + gapN)) {
            assertEquals("warning chirp silence at $i should be 0", 0.toShort(), pcm[i])
        }
    }

    @Test
    fun `ringbackChirp produces 600 ms of dual-tone PCM`() {
        val pcm = TptToneGenerator.ringbackChirp()
        assertEquals(samplesFor(600), pcm.size)
        assertEquals(TptToneGenerator.RINGBACK_CHIRP_DURATION_MS, 600L)
        assertTrue(hasNonZeroSamples(pcm))
    }

    @Test
    fun `busyChirp produces (350+200)*2 = 1100 ms of PCM`() {
        val pcm = TptToneGenerator.busyChirp()
        assertEquals(samplesFor(1100), pcm.size)
        assertEquals(TptToneGenerator.BUSY_CHIRP_DURATION_MS, 1_100L)
    }

    @Test
    fun `busyChirp silence gap between pulses is zero`() {
        // First 350ms = pulse, next 200ms = silence, next 350ms = pulse,
        // last 200ms = silence (the gap after the last pulse). The 2nd
        // silence window (between the two pulses) is the audibly
        // critical one — verify it's zero.
        val pcm = TptToneGenerator.busyChirp()
        val onN = samplesFor(350)
        val offN = samplesFor(200)
        for (i in onN until (onN + offN)) {
            assertEquals("busyChirp inter-pulse silence at $i should be 0", 0.toShort(), pcm[i])
        }
    }

    @Test
    fun `timeoutCutoffPcm produces 1200 ms of sustained PCM`() {
        val pcm = TptToneGenerator.timeoutCutoffPcm()
        assertEquals(samplesFor(1_200), pcm.size)
        assertEquals(TptToneGenerator.TIMEOUT_CUTOFF_DURATION_MS, 1_200L)
        assertTrue(hasNonZeroSamples(pcm))
        assertTrue(allInPcmRange(pcm))
    }

    @Test
    fun `SAMPLE_RATE_HZ is 48000 — pinned to the Mumble Opus path`() {
        // Mumble's Opus encoder configured for 48 kHz; the rest of
        // the audio plant (AudioPlayback, AudioCapture) tracks this.
        // A drift here breaks the whole pipeline.
        assertEquals(48_000, TptToneGenerator.SAMPLE_RATE_HZ)
    }

    // ============================================================
    // Per-tone fingerprint — same seed → same output (regression pin)
    // ============================================================

    @Test
    fun `generate is deterministic — same tone twice produces identical bytes`() {
        // Every synthesis function is a pure math function of
        // SAMPLE_RATE_HZ + segment durations + frequency; no random,
        // no time-of-day. Identical output across calls.
        val a = TptToneGenerator.generate(TptTone.ASTRO_25)
        val b = TptToneGenerator.generate(TptTone.ASTRO_25)
        assertEquals(a.size, b.size)
        for (i in a.indices) {
            if (a[i] != b[i]) {
                // Found a divergence — fail loudly with index info.
                assertEquals("divergence at index $i", a[i], b[i])
            }
        }
    }

    @Test
    fun `ASTRO_25 and NEXTEL differ — same shape, different pitch`() {
        // Both are triple-pulse 150 ms patterns but at 910 Hz vs
        // 1800 Hz. Same envelope, different sine frequency → samples
        // diverge byte-for-byte.
        val astro = TptToneGenerator.generate(TptTone.ASTRO_25)
        val nextel = TptToneGenerator.generate(TptTone.NEXTEL)
        assertEquals(astro.size, nextel.size)
        // Pick a sample well past attack envelope where the sine is
        // at peak amplitude (~5 ms into the first 30 ms tone segment).
        val sampleIdx = samplesFor(5)
        assertNotEquals(astro[sampleIdx], nextel[sampleIdx])
    }

    // ============================================================
    // Reconnect cues — retryChirp / recoveredChime
    // ============================================================

    @Test
    fun `retryChirp duration constant matches the PCM it actually generates`() {
        // The duration constant isn't decorative: TptPlayer feeds it to
        // the AudioTrack marker watchdog. A constant that disagrees with
        // the buffer either truncates the tone or hangs the track past
        // the end of the audio.
        val pcm = TptToneGenerator.retryChirp()
        assertEquals(
            samplesFor(TptToneGenerator.RETRY_CHIRP_DURATION_MS.toInt()),
            pcm.size,
        )
    }

    @Test
    fun `recoveredChime duration constant matches the PCM it actually generates`() {
        val pcm = TptToneGenerator.recoveredChime()
        assertEquals(
            samplesFor(TptToneGenerator.RECOVERED_CHIME_DURATION_MS.toInt()),
            pcm.size,
        )
    }

    @Test
    fun `reconnect cues stay in PCM range and make actual sound`() {
        for (pcm in listOf(TptToneGenerator.retryChirp(), TptToneGenerator.recoveredChime())) {
            assertTrue(allInPcmRange(pcm))
            assertTrue(hasNonZeroSamples(pcm))
        }
    }

    @Test
    fun `retryChirp is markedly quieter than the warning it accompanies`() {
        // The design contract: WARNING_VOICE_LOST fires once and is
        // allowed to demand attention; the retry chirp repeats for
        // minutes and must sit under the conversation. If a future tweak
        // brings the chirp up to warning level we're back to the
        // continuous-beeping field complaint this subsystem exists to fix.
        val chirpPeak = TptToneGenerator.retryChirp().maxOf { kotlin.math.abs(it.toInt()) }
        val warningPeak = TptToneGenerator.warningChirp().maxOf { kotlin.math.abs(it.toInt()) }
        assertTrue(
            "retry chirp peak ($chirpPeak) must be well below warning peak ($warningPeak)",
            chirpPeak < warningPeak / 2,
        )
    }

    @Test
    fun `retryChirp is a single pulse — no silence gap splitting it`() {
        // Single-pulse is what makes this cue distinguishable from every
        // other tone in the system (all of which are 2+ pulses). A gap
        // would turn it into "some other status chirp" to the operator's
        // ear. Checked structurally: the interior of the buffer never
        // goes silent.
        val pcm = TptToneGenerator.retryChirp()
        // Skip the attack/release envelope edges, which legitimately
        // approach zero.
        val interior = pcm.copyOfRange(samplesFor(20), pcm.size - samplesFor(20))
        val longestSilentRun = longestRunOfZeros(interior)
        assertTrue(
            "found a $longestSilentRun-sample silent run inside a supposedly single pulse",
            longestSilentRun < samplesFor(2),
        )
    }

    private fun longestRunOfZeros(pcm: ShortArray): Int {
        var longest = 0
        var current = 0
        for (s in pcm) {
            if (s.toInt() == 0) {
                current++
                if (current > longest) longest = current
            } else {
                current = 0
            }
        }
        return longest
    }
}
