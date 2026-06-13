package com.atakmap.android.xv.audio

/**
 * Opus payload → PCM samples. Stateful: a single decoder instance is
 * tied to one stream of frames from one peer. Create a new decoder for
 * each new sender or session.
 *
 * Implementations: ConcentusOpusDecoder (Phase 1, pure-Java). A native
 * implementation (Humla / wrapped-opus) replaces this in a later phase.
 */
interface OpusDecoder {
    val sampleRateHz: Int

    val channels: Int

    /**
     * Decode one Opus payload. Returns the PCM samples (16-bit signed).
     * Samples are interleaved if [channels] > 1, but XV uses mono so
     * output is one sample per element.
     *
     * Throws if the payload is malformed.
     */
    fun decode(opusPayload: ByteArray): ShortArray

    /** Release any native resources. Decoder unusable after this. */
    fun close()
}
