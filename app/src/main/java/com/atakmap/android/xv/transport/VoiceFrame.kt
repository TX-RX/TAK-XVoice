package com.atakmap.android.xv.transport

/**
 * One Opus-encoded voice frame as it sits on the wire. Both Mumble and
 * Multicast transports produce/consume this exact representation — only
 * the framing layer (Mumble's protobuf voice packet vs raw UDP datagram)
 * differs.
 *
 * @property opusPayload the raw Opus-encoded bytes (not including any
 *           transport-specific framing).
 * @property sampleRateHz Opus sample rate. Mumble uses 48000.
 * @property frameDurationMs typically 20 ms for low-latency PoC use.
 * @property senderId opaque identifier of who sent this frame; semantics
 *           depend on the transport (Mumble session id, multicast source
 *           IP, etc.). May be null for outgoing frames.
 * @property monotonicTimestampMs receive-side timestamp from the device's
 *           monotonic clock; used by the jitter buffer.
 */
data class VoiceFrame(
    val opusPayload: ByteArray,
    val sampleRateHz: Int = 48_000,
    val frameDurationMs: Int = 20,
    val senderId: String? = null,
    val monotonicTimestampMs: Long = 0L,
    // Logical "channel slot" this frame is bound for. 0 = primary
    // (current Mumble channel, default talk). 1 = secondary, routed via
    // a registered VoiceTarget so listeners in a *different* channel hear
    // it without the sender leaving primary. Multicast and other transports
    // ignore slots > 0.
    val targetSlot: Int = 0,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is VoiceFrame) return false
        return opusPayload.contentEquals(other.opusPayload) &&
            sampleRateHz == other.sampleRateHz &&
            frameDurationMs == other.frameDurationMs &&
            senderId == other.senderId &&
            monotonicTimestampMs == other.monotonicTimestampMs &&
            targetSlot == other.targetSlot
    }

    override fun hashCode(): Int {
        var result = opusPayload.contentHashCode()
        result = 31 * result + sampleRateHz
        result = 31 * result + frameDurationMs
        result = 31 * result + (senderId?.hashCode() ?: 0)
        result = 31 * result + monotonicTimestampMs.hashCode()
        result = 31 * result + targetSlot
        return result
    }
}
