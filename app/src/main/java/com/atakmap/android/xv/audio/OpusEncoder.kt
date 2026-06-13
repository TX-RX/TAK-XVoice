package com.atakmap.android.xv.audio

// Mirror of OpusDecoder for the TX side. PCM samples → Opus payload.
// Stateful: a single encoder instance is tied to one outbound stream.
interface OpusEncoder {
    val sampleRateHz: Int

    val channels: Int

    // Frame size the encoder expects in samples (per channel). Mumble
    // typically uses 10ms frames at 48kHz = 480 samples.
    val frameSamples: Int

    fun encode(pcm: ShortArray): ByteArray

    fun close()
}
