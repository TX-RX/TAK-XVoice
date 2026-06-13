package com.atakmap.android.xv.audio

import org.concentus.OpusApplication
import org.concentus.OpusEncoder as ConcentusEncoder

// Pure-Java Opus encoder via Concentus. Same trade-off as the decoder —
// no NDK setup, ~2-5ms encode per 10ms frame on a Pixel 9. Replace with
// native libopus once the pipeline is otherwise validated.
class ConcentusOpusEncoder(
    override val sampleRateHz: Int = DEFAULT_SAMPLE_RATE,
    override val channels: Int = 1,
    // 10ms at 48kHz = 480 samples — matching Mumla exactly.
    // Lower latency, matches what working Mumla client uses.
    override val frameSamples: Int = 480,
    bitrateBps: Int = 40_000,
) : OpusEncoder {
    private val encoder =
        ConcentusEncoder(sampleRateHz, channels, OpusApplication.OPUS_APPLICATION_VOIP).apply {
            this.bitrate = bitrateBps
            this.complexity = 5
            this.signalType = org.concentus.OpusSignal.OPUS_SIGNAL_VOICE
            // DTX on can produce zero-length "silence" Opus packets that
            // some Mumble servers / clients drop. Force OFF so every
            // frame carries audio data; server gets a real packet at
            // the expected cadence.
            this.useDTX = false
            // Force ~constant bitrate — easier for downstream timing.
            this.useVBR = false
        }

    private val scratch = ByteArray(MAX_PACKET_BYTES)

    override fun encode(pcm: ShortArray): ByteArray {
        val n =
            encoder.encode(
                pcm,
                0,
                frameSamples,
                scratch,
                0,
                scratch.size,
            )
        return scratch.copyOf(n)
    }

    override fun close() {
        // Concentus encoder has no explicit release.
    }

    companion object {
        const val DEFAULT_SAMPLE_RATE = 48_000

        // Mumble voice packet payload cap. Opus frame max for voice is
        // ~1275 bytes at the highest bitrate; 1500 leaves headroom.
        private const val MAX_PACKET_BYTES = 1500
    }
}
