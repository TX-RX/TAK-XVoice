package com.atakmap.android.xv.audio

import org.concentus.OpusDecoder as ConcentusDecoder

/**
 * Pure-Java Opus decoder backed by Concentus. Slow vs native libopus
 * (~2-5 ms per 20 ms frame on a Pixel 9) but ships without NDK setup.
 *
 * Phase 1 only. The native build path replaces this once the audio
 * pipeline is otherwise validated.
 */
class ConcentusOpusDecoder(
    override val sampleRateHz: Int = DEFAULT_SAMPLE_RATE,
    override val channels: Int = 1,
) : OpusDecoder {
    private val decoder = ConcentusDecoder(sampleRateHz, channels)

    // 120 ms max frame at 48 kHz mono = 5760 samples; size for the largest
    // Opus frame the spec allows.
    private val scratch = ShortArray(MAX_FRAME_SAMPLES * channels)

    override fun decode(opusPayload: ByteArray): ShortArray {
        val samples =
            decoder.decode(
                opusPayload,
                0,
                opusPayload.size,
                scratch,
                0,
                MAX_FRAME_SAMPLES,
                false,
            )
        return scratch.copyOf(samples * channels)
    }

    override fun close() {
        // Concentus decoder has no explicit release; pure JVM. Drop the
        // reference and let GC handle it.
    }

    companion object {
        const val DEFAULT_SAMPLE_RATE = 48_000
        private const val MAX_FRAME_SAMPLES = 5760
    }
}
