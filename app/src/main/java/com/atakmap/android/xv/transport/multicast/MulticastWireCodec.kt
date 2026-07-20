package com.atakmap.android.xv.transport.multicast

/**
 * Per-channel TX/RX framing between Opus voice frames and multicast
 * datagrams, selected by [WireFormat]. The transport layer stays
 * format-agnostic: it hands raw Opus down and gets datagram bytes back
 * (TX), hands datagram bytes up and gets attributed Opus back (RX).
 *
 * Instances hold per-burst TX state (RTP sequence/timestamp) and are
 * confined to the transport's TX thread + RX thread respectively; the
 * only cross-thread state is the [ChannelKeyRegistry], whose access
 * contract is documented on that class.
 */
sealed interface MulticastWireCodec {
    /**
     * Reset burst-relative TX state. Called on the PTT-down edge — the
     * same edge that resets the Mumble voice sequence — so both legs'
     * sequence numbers stay aligned for cross-leg RX dedup.
     */
    fun beginBurst()

    /**
     * Frame one Opus payload for the wire. Returns null when policy
     * forbids sending (e.g. [CryptoPolicy.REQUIRED] with no channel
     * key yet) — the caller drops the frame and counts it.
     */
    fun encodeTx(opus: ByteArray): ByteArray?

    /**
     * Classify + unwrap one received datagram.
     *
     * @param sourceHost the datagram's source IP literal; identity of
     *   last resort for formats with no in-band speaker id.
     */
    fun decodeRx(
        datagram: ByteArray,
        sourceHost: String,
    ): RxResult

    sealed class RxResult {
        /**
         * A voice frame.
         *
         * @param speakerKey stable per-speaker attribution key, format
         *   `ssrc:<hex8>` (XV native) or `ip:<addr>` (OpenMANET
         *   compat). Distinct keys ⇒ distinct decoder states upstream.
         * @param seqInBurst burst-relative sequence when the wire
         *   carries one (XV native); null for raw-Opus interop frames.
         */
        data class Voice(
            val speakerKey: String,
            val seqInBurst: Int?,
            val opus: ByteArray,
        ) : RxResult() {
            override fun equals(other: Any?): Boolean {
                if (this === other) return true
                if (other !is Voice) return false
                return speakerKey == other.speakerKey &&
                    seqInBurst == other.seqInBurst &&
                    opus.contentEquals(other.opus)
            }

            override fun hashCode(): Int {
                var h = speakerKey.hashCode()
                h = 31 * h + (seqInBurst ?: -1)
                h = 31 * h + opus.contentHashCode()
                return h
            }
        }

        /** An XVMC control-plane message (key exchange, elections). */
        data class Control(
            val message: ControlPacket.Message,
        ) : RxResult()

        /** Not playable; [reason] feeds diagnostics counters. */
        data class Dropped(
            val reason: DropReason,
        ) : RxResult()
    }

    enum class DropReason {
        EMPTY,
        NOT_RTP,
        WRONG_PAYLOAD_TYPE,
        CLEARTEXT_FORBIDDEN,
        UNKNOWN_EPOCH,

        /**
         * An AEAD frame arrived but this receiver holds NO key at all —
         * peers are encrypted and we can't join in. Operator-actionable
         * (import a plan / reconnect to the server), so it must never
         * masquerade as line noise: a keyless device sat "MESH ACTIVE"
         * while deaf, its drops miscounted as NOT_RTP (field repro
         * 2026-07-16, offline restart lost the in-memory channel key).
         */
        ENCRYPTED_NO_KEY,
        BAD_TAG,
        MALFORMED,
    }
}

/**
 * XV-native framing: `[RTP header || Opus]`, optionally AEAD-wrapped
 * (`[epoch || nonce || ct+tag]` around the whole RTP frame) when a
 * channel key is live and policy allows.
 *
 * RX disambiguation between the three things that share the group:
 *   1. XVMC magic → control packet.
 *   2. First byte matches an epoch the key registry knows → AEAD frame
 *      (decrypt, then RTP-decode the plaintext).
 *   3. First byte 0x80 (RTP v2, no padding/ext/CSRC — the only byte-0
 *      our encoder emits) → cleartext RTP.
 * Key epochs are allocated 0..127 by the keying layer, so a cleartext
 * RTP frame (byte 0 = 0x80 = 128) can never collide with a known epoch.
 *
 * TX sequence numbers reset to 0 on [beginBurst] (unusual for general
 * RTP, deliberate for XV↔XV: cross-leg dedup matches Mumble's per-burst
 * sequence, which also resets on the PTT edge). The RTP timestamp keeps
 * running across bursts so receivers still see silence gaps.
 */
class XvNativeWireCodec(
    private val ssrc: Long,
    private val cryptoPolicy: CryptoPolicy,
    private val keyRegistry: ChannelKeyRegistry?,
) : MulticastWireCodec {
    private var seq = 0
    private var timestamp = 0L
    private var burstStart = true

    override fun beginBurst() {
        seq = 0
        burstStart = true
    }

    override fun encodeTx(opus: ByteArray): ByteArray? {
        val rtp =
            RtpFraming.encode(
                sequenceNumber = seq,
                timestamp = timestamp,
                ssrc = ssrc,
                opusPayload = opus,
                marker = burstStart,
            )
        seq = (seq + 1) and 0xFFFF
        timestamp = (timestamp + RtpFraming.TIMESTAMP_INCREMENT_20MS) and 0xFFFFFFFFL
        burstStart = false

        val keyed = keyRegistry?.hasKey() == true
        return when (cryptoPolicy) {
            CryptoPolicy.CLEARTEXT -> rtp
            CryptoPolicy.PREFERRED -> if (keyed) keyRegistry!!.encrypt(rtp) else rtp
            CryptoPolicy.REQUIRED -> if (keyed) keyRegistry!!.encrypt(rtp) else null
        }
    }

    override fun decodeRx(
        datagram: ByteArray,
        sourceHost: String,
    ): MulticastWireCodec.RxResult {
        if (datagram.isEmpty()) return MulticastWireCodec.RxResult.Dropped(MulticastWireCodec.DropReason.EMPTY)

        if (ControlPacket.isControl(datagram)) {
            val msg = ControlPacket.decode(datagram)
            // Magic matched but the body didn't decode: 4-byte magic
            // collision from ciphertext (~2^-32) or a truncated control
            // frame. Either way it isn't voice.
            if (msg != null) return MulticastWireCodec.RxResult.Control(msg)
            return MulticastWireCodec.RxResult.Dropped(MulticastWireCodec.DropReason.MALFORMED)
        }

        val registry = keyRegistry
        if (registry != null && registry.hasKey()) {
            when (val r = registry.decryptDetailed(datagram)) {
                is ChannelKeyRegistry.DecryptResult.Ok -> return decodeRtp(r.plaintext)
                is ChannelKeyRegistry.DecryptResult.BadTag ->
                    return MulticastWireCodec.RxResult.Dropped(MulticastWireCodec.DropReason.BAD_TAG)
                is ChannelKeyRegistry.DecryptResult.UnknownEpoch -> {
                    // Not an epoch we hold a key for — fall through to
                    // the cleartext-RTP branch below (byte 0x80 lands
                    // here by design).
                }
                ChannelKeyRegistry.DecryptResult.Malformed ->
                    return MulticastWireCodec.RxResult.Dropped(MulticastWireCodec.DropReason.MALFORMED)
            }
        }

        if (cryptoPolicy == CryptoPolicy.REQUIRED) {
            return MulticastWireCodec.RxResult.Dropped(
                if (registry?.hasKey() == true) {
                    MulticastWireCodec.DropReason.CLEARTEXT_FORBIDDEN
                } else {
                    // No key yet: everything is undecryptable, cleartext
                    // or not. UNKNOWN_EPOCH tells diagnostics "keying
                    // hasn't converged" instead of implying a peer is
                    // violating policy.
                    MulticastWireCodec.DropReason.UNKNOWN_EPOCH
                },
            )
        }
        // Byte 0 below 0x80 is an epoch byte, never RTP v2: this is an
        // AEAD frame we hold no usable key for. Classify it as the
        // keying gap it is — ENCRYPTED_NO_KEY when we have no key at
        // all (operator must import a plan or re-enroll), UNKNOWN_EPOCH
        // when we're keyed but behind — instead of letting it fall into
        // decodeRtp and miscount as NOT_RTP line noise.
        if ((datagram[0].toInt() and 0xFF) < 0x80) {
            return MulticastWireCodec.RxResult.Dropped(
                if (registry?.hasKey() == true) {
                    MulticastWireCodec.DropReason.UNKNOWN_EPOCH
                } else {
                    MulticastWireCodec.DropReason.ENCRYPTED_NO_KEY
                },
            )
        }
        return decodeRtp(datagram)
    }

    private fun decodeRtp(frame: ByteArray): MulticastWireCodec.RxResult {
        val decoded =
            RtpFraming.decode(frame)
                ?: return MulticastWireCodec.RxResult.Dropped(MulticastWireCodec.DropReason.NOT_RTP)
        val (header, payload) = decoded
        if (header.payloadType != RtpFraming.PAYLOAD_TYPE_OPUS) {
            return MulticastWireCodec.RxResult.Dropped(MulticastWireCodec.DropReason.WRONG_PAYLOAD_TYPE)
        }
        return MulticastWireCodec.RxResult.Voice(
            speakerKey = "ssrc:%08x".format(header.ssrc),
            seqInBurst = header.sequenceNumber,
            opus = payload,
        )
    }
}

/**
 * ATAK VX interop framing (Standard RTP)
 */
class VxWireCodec(private val ssrc: Long) : MulticastWireCodec {
    private var seq = 0
    private var timestamp = 0L
    private var burstStart = true

    override fun beginBurst() {
        seq = 0
        burstStart = true
    }

    override fun encodeTx(opus: ByteArray): ByteArray? {
        val rtp = RtpFraming.encode(seq, timestamp, ssrc, opus, burstStart)
        seq = (seq + 1) and 0xFFFF
        timestamp = (timestamp + RtpFraming.TIMESTAMP_INCREMENT_20MS) and 0xFFFFFFFFL
        burstStart = false
        return rtp
    }

    override fun decodeRx(
        datagram: ByteArray,
        sourceHost: String,
    ): MulticastWireCodec.RxResult {
        if (datagram.isEmpty()) return MulticastWireCodec.RxResult.Dropped(MulticastWireCodec.DropReason.EMPTY)
        
        // Ignore any XV control packets if they accidentally bleed over.
        if (ControlPacket.isControl(datagram)) {
            return MulticastWireCodec.RxResult.Dropped(MulticastWireCodec.DropReason.MALFORMED)
        }

        val decoded = RtpFraming.decode(datagram)
            ?: return MulticastWireCodec.RxResult.Dropped(MulticastWireCodec.DropReason.NOT_RTP)
            
        val (header, payload) = decoded
        if (header.payloadType != RtpFraming.PAYLOAD_TYPE_OPUS) {
            return MulticastWireCodec.RxResult.Dropped(MulticastWireCodec.DropReason.WRONG_PAYLOAD_TYPE)
        }
        return MulticastWireCodec.RxResult.Voice(
            speakerKey = "ssrc:%08x".format(header.ssrc),
            seqInBurst = header.sequenceNumber,
            opus = payload,
        )
    }

    companion object {
        const val VX_SAMPLE_RATE_HZ = 48_000
        const val VX_CHANNELS = 1
        const val VX_BITRATE_BPS = 12_000
        const val VX_FRAME_MS = 20
        const val VX_FRAME_SAMPLES = 960
        const val VX_FEC = true
        const val VX_DTX = false
    }
}

/** Factory keyed off a channel's config. */
fun ChannelMulticastConfig.newWireCodec(
    ssrc: Long,
    keyRegistry: ChannelKeyRegistry?,
): MulticastWireCodec =
    when (wireFormat) {
        WireFormat.XV_NATIVE -> XvNativeWireCodec(ssrc, cryptoPolicy, keyRegistry)
        WireFormat.VX_COMPAT -> VxWireCodec(ssrc)
    }
