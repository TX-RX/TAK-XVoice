package com.atakmap.android.xv.transport.multicast

// Pure RTP/Opus framing (RFC 3550 + RFC 7587). The XV bridge needs RTP
// for two reasons:
//   1. OpenMANET-interop: their voice format is `[RTP] || [Opus]` on
//      multicast UDP, payload type 111 (Opus dynamic PT), 48 kHz clock,
//      timestamp increment of 960 per 20 ms frame, SSRC for sender
//      attribution. Matching their wire format means XV bridge frames
//      can land in an OpenMANET node's existing voice path.
//   2. XV-native: even though XV could carry raw Opus on its own
//      multicast group, using RTP everywhere keeps the bridge fan-out
//      simple — same encode path for both XV-native (AEAD-wrapped RTP)
//      and mesh-compat (plaintext RTP) groups. The 12-byte overhead at
//      32 kbps Opus is ~3% bandwidth, trivial.
//
// Minimal RTP header (12 bytes, no extensions, no CSRC list):
//
//   0                   1                   2                   3
//   0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
//  +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
//  |V=2|P|X|  CC   |M|     PT      |       sequence number         |
//  +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
//  |                           timestamp                           |
//  +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
//  |           synchronization source (SSRC) identifier            |
//  +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
//
// V=2 (version), P=0 (no padding), X=0 (no extension), CC=0 (no
// contributing sources), M=0 (no marker on continuous voice). PT=111
// (matches OpenMANET; Opus dynamic payload type per RFC 7587).
object RtpFraming {
    /** RTP header is exactly 12 bytes with the minimal flags we use. */
    const val HEADER_BYTES = 12

    /** Opus dynamic payload type. Matches OpenMANET's choice; commonly
     *  used by RTP-Opus implementations including ffmpeg + GStreamer. */
    const val PAYLOAD_TYPE_OPUS = 111

    /** Opus RTP clock rate per RFC 7587 §3. Always 48 kHz regardless of
     *  the actual encoded bandwidth (Opus is internally adaptive). */
    const val CLOCK_RATE_HZ = 48_000

    /** Per-frame timestamp increment for our standard 20 ms Opus frame.
     *  48 kHz × 20 ms = 960 samples. Receivers use this to detect
     *  reordering and silence gaps. */
    const val TIMESTAMP_INCREMENT_20MS = 960

    /** Decoded RTP header carrier. */
    data class Header(
        val sequenceNumber: Int,
        val timestamp: Long,
        val ssrc: Long,
        val payloadType: Int = PAYLOAD_TYPE_OPUS,
        val marker: Boolean = false,
    )

    /** Encode a header + Opus payload into a wire datagram.
     *
     *  @param sequenceNumber 16-bit wrap-around sequence (caller manages).
     *  @param timestamp 32-bit sample-clock timestamp (caller manages).
     *  @param ssrc 32-bit sender-identifier (typically FNV-1a of our UID).
     *  @param opusPayload variable-length Opus-encoded frame bytes.
     */
    fun encode(
        sequenceNumber: Int,
        timestamp: Long,
        ssrc: Long,
        opusPayload: ByteArray,
        marker: Boolean = false,
        payloadType: Int = PAYLOAD_TYPE_OPUS,
    ): ByteArray {
        val out = ByteArray(HEADER_BYTES + opusPayload.size)
        // Byte 0: V=2 (bits 7-6 = 10), P=0, X=0, CC=0000 → 0x80
        out[0] = 0x80.toByte()
        // Byte 1: M (bit 7) + PT (bits 6-0)
        val markerBit = if (marker) 0x80 else 0x00
        out[1] = (markerBit or (payloadType and 0x7F)).toByte()
        // Bytes 2-3: sequence number big-endian
        out[2] = ((sequenceNumber shr 8) and 0xFF).toByte()
        out[3] = (sequenceNumber and 0xFF).toByte()
        // Bytes 4-7: timestamp big-endian (32-bit)
        out[4] = ((timestamp shr 24) and 0xFF).toByte()
        out[5] = ((timestamp shr 16) and 0xFF).toByte()
        out[6] = ((timestamp shr 8) and 0xFF).toByte()
        out[7] = (timestamp and 0xFF).toByte()
        // Bytes 8-11: SSRC big-endian (32-bit)
        out[8] = ((ssrc shr 24) and 0xFF).toByte()
        out[9] = ((ssrc shr 16) and 0xFF).toByte()
        out[10] = ((ssrc shr 8) and 0xFF).toByte()
        out[11] = (ssrc and 0xFF).toByte()
        // Payload follows
        System.arraycopy(opusPayload, 0, out, HEADER_BYTES, opusPayload.size)
        return out
    }

    /** Decode a wire datagram into header + Opus payload.
     *
     *  Returns null if the datagram is too short, has an unsupported
     *  RTP version, or carries extensions / CSRC lists we don't emit
     *  (defensive — we want to reject anything that wasn't produced by
     *  [encode] above so the bridge doesn't try to forward malformed
     *  frames into Mumble).
     */
    fun decode(datagram: ByteArray): Pair<Header, ByteArray>? {
        if (datagram.size < HEADER_BYTES) return null
        val v = (datagram[0].toInt() shr 6) and 0x3
        if (v != 2) return null
        val padding = (datagram[0].toInt() and 0x20) != 0
        val extension = (datagram[0].toInt() and 0x10) != 0
        val csrcCount = datagram[0].toInt() and 0x0F

        val marker = (datagram[1].toInt() and 0x80) != 0
        val payloadType = datagram[1].toInt() and 0x7F

        // Accept any dynamic payload type (96-127) to support legacy VX
        if (payloadType < 96 || payloadType > 127) return null

        val seq =
            ((datagram[2].toInt() and 0xFF) shl 8) or
                (datagram[3].toInt() and 0xFF)
        val ts =
            ((datagram[4].toLong() and 0xFF) shl 24) or
                ((datagram[5].toLong() and 0xFF) shl 16) or
                ((datagram[6].toLong() and 0xFF) shl 8) or
                (datagram[7].toLong() and 0xFF)
        val ssrc =
            ((datagram[8].toLong() and 0xFF) shl 24) or
                ((datagram[9].toLong() and 0xFF) shl 16) or
                ((datagram[10].toLong() and 0xFF) shl 8) or
                (datagram[11].toLong() and 0xFF)

        var offset = HEADER_BYTES
        offset += csrcCount * 4
        if (offset > datagram.size) return null

        if (extension) {
            if (offset + 4 > datagram.size) return null
            val extLen = ((datagram[offset + 2].toInt() and 0xFF) shl 8) or (datagram[offset + 3].toInt() and 0xFF)
            offset += 4 + (extLen * 4)
            if (offset > datagram.size) return null
        }

        var payloadSize = datagram.size - offset
        if (padding) {
            if (payloadSize == 0) return null
            val padLen = datagram[datagram.size - 1].toInt() and 0xFF
            if (padLen > payloadSize) return null
            payloadSize -= padLen
        }

        val payload = datagram.copyOfRange(offset, offset + payloadSize)
        return Header(seq, ts, ssrc, payloadType, marker) to payload
    }

    /** FNV-1a 32-bit hash for SSRC derivation from a stable string
     *  (our UID, hostname, etc.). Matches OpenMANET's choice
     *  (`github.com/OpenMANET/openmanetd/internal/comms/rtp/session.go`)
     *  so a node bridging XV ↔ OpenMANET produces the same SSRC for
     *  the same source identifier regardless of which side emitted.
     *
     *  Returns an unsigned 32-bit value in a Long. */
    fun fnv1aSsrc(stableId: String): Long {
        val bytes = stableId.toByteArray(Charsets.UTF_8)
        var h = 0x811C9DC5L // FNV offset basis
        for (b in bytes) {
            h = h xor (b.toLong() and 0xFF)
            h = (h * 0x01000193L) and 0xFFFFFFFFL
        }
        return h
    }
}
