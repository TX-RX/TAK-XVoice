package com.atakmap.android.xv.audio

import kotlin.math.PI
import kotlin.math.sin

// Synthesizes Talk Permit Tone PCM. Tones are described declaratively as
// a list of (frequencyHz, durationMs) segments — frequency 0 means silence.
// All tones are code-generated sine waves; no sampled audio.
//
// Specs are public approximations of LMR-system audible cues; not direct
// copies of any proprietary recording.
object TptToneGenerator {
    const val SAMPLE_RATE_HZ = 48_000

    data class Segment(
        val freqHz: Double,
        val durationMs: Int,
    )

    private val TABLE: Map<TptTone, List<Segment>> =
        mapOf(
            // Motorola P25 / ASTRO 25: triple chirp, third tone held longer.
            TptTone.ASTRO_25 to
                listOf(
                    Segment(910.0, 30),
                    Segment(0.0, 20),
                    Segment(910.0, 30),
                    Segment(0.0, 20),
                    Segment(910.0, 50),
                ),
            // DMR / MOTOTRBO: continuous four-tone "warble," no silence.
            TptTone.DMR to
                listOf(
                    Segment(1569.0, 40),
                    Segment(1046.0, 40),
                    Segment(1569.0, 40),
                    Segment(1317.0, 40),
                ),
            // Nextel DirectConnect (radio-programmed simulation variant):
            // same triple-pulse pattern as ASTRO 25, higher pitch.
            TptTone.NEXTEL to
                listOf(
                    Segment(1800.0, 30),
                    Segment(0.0, 20),
                    Segment(1800.0, 30),
                    Segment(0.0, 20),
                    Segment(1800.0, 50),
                ),
            // Nextel iDEN "true" chirp: rapid alternation between 911 Hz
            // and 1800 Hz (a perfect-fifth interval) pulsed five times,
            // ~25 ms each, no silence between pulses.
            TptTone.NEXTEL_TRUE to
                listOf(
                    Segment(911.0, 25),
                    Segment(1800.0, 25),
                    Segment(911.0, 25),
                    Segment(1800.0, 25),
                    Segment(911.0, 25),
                    Segment(1800.0, 25),
                    Segment(911.0, 25),
                    Segment(1800.0, 25),
                    Segment(911.0, 25),
                    Segment(1800.0, 25),
                ),
            // Vertex Standard: flatter, mechanical triple-beep.
            TptTone.VERTEX to
                listOf(
                    Segment(1050.0, 20),
                    Segment(0.0, 20),
                    Segment(1050.0, 20),
                    Segment(0.0, 20),
                    Segment(1050.0, 20),
                ),
        )

    fun generate(tone: TptTone): ShortArray {
        val segments = TABLE[tone] ?: return ShortArray(0)
        val totalSamples = segments.sumOf { (SAMPLE_RATE_HZ * it.durationMs) / 1000 }
        val out = ShortArray(totalSamples)
        var p = 0
        for (seg in segments) {
            val n = (SAMPLE_RATE_HZ * seg.durationMs) / 1000
            if (seg.freqHz <= 0.0) {
                // Silence — already zero-initialized; just advance.
            } else {
                fillSine(out, p, n, seg.freqHz)
            }
            p += n
        }
        return out
    }

    fun durationMs(tone: TptTone): Long {
        val segments = TABLE[tone] ?: return 0L
        return segments.sumOf { it.durationMs }.toLong()
    }

    // "Bonk" / no-permit tone — played when the operator keys PTT with no
    // live session (disconnected, not in a channel). Low double-thud: two
    // short FLAT low tones stepping down (320 Hz → 240 Hz) with a brief
    // gap — a blunt "buh-BONK" that reads as a firm "denied." Replaces the
    // old single descending pitch-slide, which sounded mushy/weak. Flat
    // tones with a punchy attack read as deliberate; the downward step is
    // the operator-recognizable "no" cue. Stays clearly distinct from the
    // DENY tone (two descending *slides*, higher) so "not connected" and
    // "listen-only here" don't sound alike.
    fun noPermitBonk(): ShortArray {
        val firstMs = 70
        val gapMs = 40
        val secondMs = 130
        val firstN = (SAMPLE_RATE_HZ * firstMs) / 1000
        val gapN = (SAMPLE_RATE_HZ * gapMs) / 1000
        val secondN = (SAMPLE_RATE_HZ * secondMs) / 1000
        val out = ShortArray(firstN + gapN + secondN)
        fillBonkTone(out, 0, firstN, 320.0)
        // gap is already zero-initialized silence
        fillBonkTone(out, firstN + gapN, secondN, 240.0)
        return out
    }

    // One flat tone of the bonk with a punchy-but-click-free envelope.
    // Low tones (240-320 Hz, ~3-4 ms period) need a slightly longer edge
    // than fillSine's ≤2 ms cap or the onset/offset clicks; 4 ms attack /
    // 12 ms release keeps it blunt without a pop. 0.85 peak matches the
    // other voice-comm-stream tones (which need the headroom).
    private fun fillBonkTone(
        dest: ShortArray,
        offset: Int,
        n: Int,
        freqHz: Double,
    ) {
        val attack = (SAMPLE_RATE_HZ * 4) / 1000
        val release = (SAMPLE_RATE_HZ * 12) / 1000
        val angularStep = 2.0 * PI * freqHz / SAMPLE_RATE_HZ
        for (i in 0 until n) {
            var amp = 0.85
            if (i < attack) amp *= i.toDouble() / attack
            val tailStart = n - release
            if (i > tailStart) amp *= (1.0 - (i - tailStart).toDouble() / release).coerceIn(0.0, 1.0)
            val s = sin(angularStep * i) * amp
            dest[offset + i] = (s * Short.MAX_VALUE).toInt().coerceIn(-32768, 32767).toShort()
        }
    }

    // 70 ms + 40 ms gap + 130 ms = 240 ms. Kept in sync with the segment
    // lengths in [noPermitBonk]; TptToneGeneratorTest pins the two together.
    const val NO_PERMIT_DURATION_MS: Long = 240L

    // "DENY" tone — played when the operator presses PTT on a channel
    // they're allowed to enter but suppressed from speaking on (OTS
    // direction OUT, Mumble admin mute). Two stacked descending slides
    // separated by a brief gap — clearly different from the single
    // bonk so the operator can hear "you're listen-only on this
    // channel" vs "you're not in any channel" without looking at the
    // screen.
    fun denyTone(): ShortArray {
        val toneMs = 140
        val gapMs = 50
        val toneN = (SAMPLE_RATE_HZ * toneMs) / 1000
        val gapN = (SAMPLE_RATE_HZ * gapMs) / 1000
        val out = ShortArray(toneN * 2 + gapN)
        val startHz = 380.0
        val endHz = 260.0
        val attack = (SAMPLE_RATE_HZ * 3) / 1000
        val release = (SAMPLE_RATE_HZ * 20) / 1000
        for (rep in 0..1) {
            var phase = 0.0
            val base = rep * (toneN + gapN)
            for (i in 0 until toneN) {
                val frac = i.toDouble() / toneN
                val freq = startHz + (endHz - startHz) * frac
                phase += 2.0 * PI * freq / SAMPLE_RATE_HZ
                var amp = 0.85
                if (i < attack) amp *= i.toDouble() / attack
                val tailStart = toneN - release
                if (i > tailStart) amp *= (1.0 - (i - tailStart).toDouble() / release).coerceIn(0.0, 1.0)
                val s = sin(phase) * amp
                out[base + i] = (s * Short.MAX_VALUE).toInt().coerceIn(-32768, 32767).toShort()
            }
        }
        return out
    }

    const val DENY_DURATION_MS: Long = 330L

    // "Interrupt chirp" played in place of the TPT when the user keys
    // PTT while a peer is actively talking. Two ascending tones (660 →
    // 880 Hz, 60 ms each, no gap) — the same shape as the channel-join
    // chirp but at the louder TPT-grade level (-3 dBFS) so it lands
    // clearly through the peer voice in the same speaker mix. The
    // ascending step-up reads as "permit granted" / "go ahead" rather
    // than the previous flat single tone, which felt more like an
    // alert than a permit cue. Brief enough (~120 ms total) that peer
    // voice is still clearly audible underneath.
    fun interruptChirp(): ShortArray {
        val toneSamples = (SAMPLE_RATE_HZ * 60) / 1000
        val out = ShortArray(toneSamples * 2)
        val peakAmp = 0.71 // ≈ -3 dBFS
        val attack = (SAMPLE_RATE_HZ * 4) / 1000
        val release = (SAMPLE_RATE_HZ * 10) / 1000
        for (toneIdx in 0..1) {
            val freqHz = if (toneIdx == 0) 660.0 else 880.0
            var phase = 0.0
            for (i in 0 until toneSamples) {
                phase += 2.0 * PI * freqHz / SAMPLE_RATE_HZ
                var amp = peakAmp
                if (i < attack) amp *= i.toDouble() / attack
                val tailStart = toneSamples - release
                if (i > tailStart) {
                    amp *= (1.0 - (i - tailStart).toDouble() / release).coerceIn(0.0, 1.0)
                }
                val s = sin(phase) * amp
                out[toneIdx * toneSamples + i] =
                    (s * Short.MAX_VALUE).toInt().coerceIn(-32768, 32767).toShort()
            }
        }
        return out
    }

    const val INTERRUPT_CHIRP_DURATION_MS: Long = 120L

    // Channel-join status chirp — two ascending beeps (660 → 880 Hz),
    // 60 ms each, no gap. Ascending pitch is the operator-recognizable
    // "you're in" cue (matches the convention used by VX 2.1 and most
    // commercial PTT clients). -9 dBFS so it's audible over background
    // chatter but doesn't dominate the next voice frame.
    fun joinChirp(): ShortArray = twoBeepStatus(660.0, 880.0)

    const val JOIN_CHIRP_DURATION_MS: Long = 120L

    // Channel-leave status chirp — two descending beeps (880 → 660 Hz),
    // 60 ms each. Descending pitch = "you're out" — the inverse of the
    // join cue. Same level as the join chirp.
    fun leaveChirp(): ShortArray = twoBeepStatus(880.0, 660.0)

    const val LEAVE_CHIRP_DURATION_MS: Long = 120L

    private fun twoBeepStatus(
        firstHz: Double,
        secondHz: Double,
    ): ShortArray {
        val perBeepMs = 60
        val n = (SAMPLE_RATE_HZ * perBeepMs * 2) / 1000
        val out = ShortArray(n)
        val half = n / 2
        // -9 dBFS ≈ 0.355
        fillSine(out, 0, half, firstHz, peakAmp = 0.355)
        fillSine(out, half, n - half, secondHz, peakAmp = 0.355)
        return out
    }

    // Warning chirp — two SAME-pitch short beeps at 850 Hz, 50 ms each,
    // 90 ms silence gap. Earlier descending-pulse design read as a
    // "disconnect" cue (operator's words) because triple-descending is
    // a universal "lost link" pattern. Two same-freq pulses are
    // pattern-distinct from every other tone in the system: status
    // chirps are two-tone (ascending/descending), bonk is a slide,
    // deny is a double-slide, interrupt is two ascending, TPT is
    // multi-tone. Same-pitch double-tap reads as "heads up" / "two
    // clicks left" without the disconnect connotation.
    fun warningChirp(): ShortArray {
        val beepMs = 50
        val gapMs = 90
        val perBeep = (SAMPLE_RATE_HZ * beepMs) / 1000
        val perGap = (SAMPLE_RATE_HZ * gapMs) / 1000
        val n = perBeep * 2 + perGap
        val out = ShortArray(n)
        val freq = 850.0
        val amp = 0.80 // 80% — operator-requested for unmistakable cue
        fillSine(out, 0, perBeep, freq, peakAmp = amp)
        fillSine(out, perBeep + perGap, perBeep, freq, peakAmp = amp)
        return out
    }

    const val WARNING_CHIRP_DURATION_MS: Long = 50L * 2 + 90L // 190 ms

    // Phase E call-progress tones. Synthesized to match North American
    // PSTN conventions so operators recognize them instantly:
    //
    //   Ringback: 440 + 480 Hz dual-tone, 2 s on / 4 s off (we render
    //             only the 0.6 s "on" portion here; the orchestrator
    //             schedules repeats every ~3 s for caller feedback).
    //   Busy:     480 + 620 Hz dual-tone, 0.5 s on / 0.5 s off (we
    //             render two full cycles so the operator gets an
    //             unambiguous "couldn't connect" cue without any
    //             scheduling complexity).
    fun ringbackChirp(): ShortArray = dualTone(440.0, 480.0, durationMs = 600, amp = 0.40)

    const val RINGBACK_CHIRP_DURATION_MS: Long = 600L

    fun busyChirp(): ShortArray {
        val onMs = 350
        val offMs = 200
        val perOn = (SAMPLE_RATE_HZ * onMs) / 1000
        val perOff = (SAMPLE_RATE_HZ * offMs) / 1000
        val n = (perOn + perOff) * 2
        val out = ShortArray(n)
        // First pulse
        fillDualTone(out, 0, perOn, 480.0, 620.0, peakAmp = 0.45)
        // Second pulse after silence gap
        fillDualTone(out, perOn + perOff, perOn, 480.0, 620.0, peakAmp = 0.45)
        return out
    }

    const val BUSY_CHIRP_DURATION_MS: Long = (350L + 200L) * 2

    private fun dualTone(
        freqA: Double,
        freqB: Double,
        durationMs: Int,
        amp: Double,
    ): ShortArray {
        val n = (SAMPLE_RATE_HZ * durationMs) / 1000
        val out = ShortArray(n)
        fillDualTone(out, 0, n, freqA, freqB, peakAmp = amp)
        return out
    }

    private fun fillDualTone(
        dest: ShortArray,
        offset: Int,
        count: Int,
        freqA: Double,
        freqB: Double,
        peakAmp: Double,
    ) {
        // Mix two sines at half-amplitude each so the combined peak
        // doesn't clip. PSTN dual-tone signals are summed, not
        // modulated, so the spectrogram has two clean carriers — that
        // recognition is what makes them feel like a phone tone.
        val perTone = peakAmp * 0.5
        for (i in 0 until count) {
            val t = i.toDouble() / SAMPLE_RATE_HZ
            val a = sin(2 * PI * freqA * t)
            val b = sin(2 * PI * freqB * t)
            val v = ((a + b) * perTone * Short.MAX_VALUE).toInt().coerceIn(-32768, 32767)
            dest[offset + i] = v.toShort()
        }
    }

    // TX-timeout cutoff tone. One-shot ~1.2 second 500 Hz sustained
    // sine with short attack/release. Plays through the same
    // USAGE_MEDIA path as the warning chirp and channel join/leave
    // chirps — keeping it on the media path (not USAGE_VOICE_COMMUNI-
    // CATION) is critical: an early infinite-loop voice-comm version
    // confused the audio policy when SCO released for TX teardown
    // and broke RX playback for the rest of the session. The media
    // path is independent of the voice/SCO state machine so it can't
    // wedge it.
    //
    // Long-enough envelope on both ends so the tone is unmissable
    // without being a hard click. Volume matches the warning chirp
    // (-9 dBFS) since it's the same class of attention-getter, just
    // with sustained shape rather than triple pulse.
    fun timeoutCutoffPcm(): ShortArray {
        val durationMs = 1_200
        val n = (SAMPLE_RATE_HZ * durationMs) / 1000
        val out = ShortArray(n)
        val freqHz = 500.0
        val attack = (SAMPLE_RATE_HZ * 25) / 1000 // 25 ms — soft attack
        val release = (SAMPLE_RATE_HZ * 80) / 1000 // 80 ms — soft tail
        val amp = 0.80 // 80% — matches warning chirp; voice-stream needs the headroom
        val angularStep = 2.0 * PI * freqHz / SAMPLE_RATE_HZ
        for (i in 0 until n) {
            var a = amp
            if (i < attack) a *= i.toDouble() / attack
            val tailStart = n - release
            if (i > tailStart) a *= (1.0 - (i - tailStart).toDouble() / release).coerceIn(0.0, 1.0)
            val s = sin(angularStep * i) * a
            out[i] = (s * Short.MAX_VALUE).toInt().coerceIn(-32768, 32767).toShort()
        }
        return out
    }

    const val TIMEOUT_CUTOFF_DURATION_MS: Long = 1_200L

    // Fills a slice of [out] with a sine at [freqHz] using a short
    // attack/release envelope to avoid pops at segment boundaries. For
    // very short segments (≤ 30ms) the envelope auto-shrinks so the tone
    // still has audible body.
    //
    // Default peak amplitude is 0.85 (≈ -1.4 dBFS). TPT tones now route
    // via USAGE_VOICE_COMMUNICATION on STREAM_VOICE_CALL, which has a
    // separate volume slider from STREAM_MUSIC and is typically lower
    // by default. Pushing the source close to full-scale gives the
    // operator audible TPT without forcing them to crank the in-call
    // volume slider every session. Status chirps that pass an explicit
    // peakAmp (e.g. join/leave at 0.355) override this default.
    private fun fillSine(
        out: ShortArray,
        offset: Int,
        n: Int,
        freqHz: Double,
        peakAmp: Double = 0.85,
    ) {
        val angularStep = 2.0 * PI * freqHz / SAMPLE_RATE_HZ
        val attack = (n / 8).coerceAtMost(SAMPLE_RATE_HZ * 2 / 1000) // ≤ 2ms
        val release = (n / 4).coerceAtMost(SAMPLE_RATE_HZ * 4 / 1000) // ≤ 4ms
        for (i in 0 until n) {
            var amp = peakAmp
            if (attack > 0 && i < attack) amp *= i.toDouble() / attack
            val tailStart = n - release
            if (release > 0 && i > tailStart) {
                amp *= (1.0 - (i - tailStart).toDouble() / release).coerceIn(0.0, 1.0)
            }
            val s = sin(angularStep * i) * amp
            out[offset + i] = (s * Short.MAX_VALUE).toInt().coerceIn(-32768, 32767).toShort()
        }
    }
}
