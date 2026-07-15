package com.atakmap.android.xv.transport.multicast

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Control-channel datagram codec for the encrypted-multicast key
 * bootstrap. These packets are sent in the clear on the same multicast
 * group as the voice frames. They share the group address so a peer
 * with a single socket can drive both flows; a 1-byte type discriminator
 * tells voice frames apart from control.
 *
 * Voice frames (produced by [AeadCodec]) start with a 1-byte epoch in
 * the range 0..255. Control frames steal the high bit (0x80..0xFF) of
 * that first byte to mark themselves; the low 7 bits then encode the
 * control message type. This keeps voice frames untouched (epochs are
 * tracked as 0..255 elsewhere but only 0..127 are used in practice
 * because we never need more than ~one rotation per minute, and 127
 * bytes is plenty even at minute granularity over the lifetime of any
 * realistic deployment).
 *
 * Wait — that constrains usable epochs to 0..127. Let's NOT do that:
 * instead, control packets carry a magic prefix the voice frames don't
 * (a 4-byte ASCII tag "XVMC") plus a 1-byte type discriminator, and
 * the receiver-side framing test checks for the magic before assuming
 * voice. False-positive collision: a voice frame whose ChaCha20-Poly1305
 * ciphertext starts with bytes 0x58 0x56 0x4D 0x43 — probability 2^-32,
 * one in four billion. Acceptable.
 *
 *
 * Wire formats (all multi-byte numeric fields are big-endian):
 *
 *   Common header (5 bytes):
 *     +---+---+---+---+----+
 *     | X | V | M | C | T  |     T = 1-byte type (Type enum ordinal)
 *     +---+---+---+---+----+
 *
 *   KeyReq (T=0x01): asks holders for the key for `forEpoch`.
 *     +---------+----------------------------+
 *     | header  | 4-byte channelId (BE)      |
 *     +---------+----------------------------+
 *     | 1-byte forEpoch | 4-byte uidLen (BE) |
 *     +-----------------+--------------------+
 *     | uidLen-byte uid (UTF-8)              |
 *     +--------------------------------------+
 *
 *   KeyOffer (T=0x02): unicast (over multicast w/ recipient filter).
 *     +---------+----------------------------+
 *     | header  | 4-byte channelId (BE)      |
 *     +---------+----------------------------+
 *     | 1-byte epoch | 4-byte recipientUidLen|
 *     +--------------+-----------------------+
 *     | recipientUidLen-byte recipientUid    |
 *     +--------------------------------------+
 *     | 4-byte wrappedKeyLen (BE)            |
 *     +--------------------------------------+
 *     | wrappedKeyLen-byte OAEP-wrapped key  |
 *     +--------------------------------------+
 *
 *   CertReq (T=0x03): asks any peer holding the leaf cert matching
 *     `wantedCertFp` to publish it.
 *     +---------+----------------------------+
 *     | header  | 4-byte fpLen (BE)          |
 *     +---------+----------------------------+
 *     | fpLen-byte certFp (lowercase hex)    |
 *     +--------------------------------------+
 *
 *   CertReply (T=0x04): peer publishes its DER cert in response.
 *     +---------+----------------------------+
 *     | header  | 4-byte derLen (BE)         |
 *     +---------+----------------------------+
 *     | derLen-byte X.509 DER cert           |
 *     +--------------------------------------+
 *
 * The recipientUid in KeyOffer is the only "addressing" — multicast
 * delivers the same datagram to everyone, so each recipient checks
 * "is this for me?" by comparing the uid field.
 */
object ControlPacket {
    private const val MAGIC_X: Byte = 0x58
    private const val MAGIC_V: Byte = 0x56
    private const val MAGIC_M: Byte = 0x4D
    private const val MAGIC_C: Byte = 0x43
    private const val HEADER_LEN = 5

    enum class Type(
        val code: Byte,
    ) {
        KeyReq(0x01),
        KeyOffer(0x02),
        CertReq(0x03),
        CertReply(0x04),
        PeerBeacon(0x05),
        ;

        companion object {
            fun fromCode(b: Byte): Type? = entries.firstOrNull { it.code == b }
        }
    }

    /**
     * True if [datagram] is a control packet (carries the XVMC magic
     * prefix). Voice frames do not.
     */
    fun isControl(datagram: ByteArray): Boolean {
        if (datagram.size < HEADER_LEN) return false
        return datagram[0] == MAGIC_X &&
            datagram[1] == MAGIC_V &&
            datagram[2] == MAGIC_M &&
            datagram[3] == MAGIC_C
    }

    /**
     * Parse a datagram into a typed control message. Returns null if
     * the datagram isn't a recognized control packet (voice frame,
     * malformed, unknown type, or truncated payload).
     */
    fun decode(datagram: ByteArray): Message? {
        if (!isControl(datagram)) return null
        val type = Type.fromCode(datagram[4]) ?: return null
        val body = datagram.copyOfRange(HEADER_LEN, datagram.size)
        val buf = ByteBuffer.wrap(body).order(ByteOrder.BIG_ENDIAN)
        return try {
            when (type) {
                Type.KeyReq -> decodeKeyReq(buf)
                Type.KeyOffer -> decodeKeyOffer(buf)
                Type.CertReq -> decodeCertReq(buf)
                Type.CertReply -> decodeCertReply(buf)
                Type.PeerBeacon -> decodePeerBeacon(buf)
            }
        } catch (_: Throwable) {
            null
        }
    }

    /** Encode [msg] as a self-contained datagram. */
    fun encode(msg: Message): ByteArray {
        val body =
            when (msg) {
                is Message.KeyReq -> encodeKeyReq(msg)
                is Message.KeyOffer -> encodeKeyOffer(msg)
                is Message.CertReq -> encodeCertReq(msg)
                is Message.CertReply -> encodeCertReply(msg)
                is Message.PeerBeacon -> encodePeerBeacon(msg)
            }
        val out = ByteArray(HEADER_LEN + body.size)
        out[0] = MAGIC_X
        out[1] = MAGIC_V
        out[2] = MAGIC_M
        out[3] = MAGIC_C
        out[4] = msg.type.code
        System.arraycopy(body, 0, out, HEADER_LEN, body.size)
        return out
    }

    // ---- type-specific encode/decode ----

    private fun decodeKeyReq(buf: ByteBuffer): Message.KeyReq {
        val channelId = buf.int
        val forEpoch = buf.get().toInt() and 0xFF
        val uid = readLengthPrefixedString(buf)
        return Message.KeyReq(channelId, forEpoch, uid)
    }

    private fun encodeKeyReq(msg: Message.KeyReq): ByteArray {
        val uidBytes = msg.requesterUid.toByteArray(Charsets.UTF_8)
        val buf = ByteBuffer.allocate(4 + 1 + 4 + uidBytes.size).order(ByteOrder.BIG_ENDIAN)
        buf.putInt(msg.channelId)
        buf.put(msg.forEpoch.toByte())
        buf.putInt(uidBytes.size)
        buf.put(uidBytes)
        return buf.array()
    }

    private fun decodeKeyOffer(buf: ByteBuffer): Message.KeyOffer {
        val channelId = buf.int
        val epoch = buf.get().toInt() and 0xFF
        val recipientUid = readLengthPrefixedString(buf)
        val wrapped = readLengthPrefixedBytes(buf)
        return Message.KeyOffer(channelId, epoch, recipientUid, wrapped)
    }

    private fun encodeKeyOffer(msg: Message.KeyOffer): ByteArray {
        val uidBytes = msg.recipientUid.toByteArray(Charsets.UTF_8)
        val buf =
            ByteBuffer
                .allocate(4 + 1 + 4 + uidBytes.size + 4 + msg.wrappedKey.size)
                .order(ByteOrder.BIG_ENDIAN)
        buf.putInt(msg.channelId)
        buf.put(msg.epoch.toByte())
        buf.putInt(uidBytes.size)
        buf.put(uidBytes)
        buf.putInt(msg.wrappedKey.size)
        buf.put(msg.wrappedKey)
        return buf.array()
    }

    private fun decodeCertReq(buf: ByteBuffer): Message.CertReq {
        val fp = readLengthPrefixedString(buf)
        return Message.CertReq(fp)
    }

    private fun encodeCertReq(msg: Message.CertReq): ByteArray {
        val fpBytes = msg.wantedCertFp.toByteArray(Charsets.UTF_8)
        val buf = ByteBuffer.allocate(4 + fpBytes.size).order(ByteOrder.BIG_ENDIAN)
        buf.putInt(fpBytes.size)
        buf.put(fpBytes)
        return buf.array()
    }

    private fun decodeCertReply(buf: ByteBuffer): Message.CertReply {
        val der = readLengthPrefixedBytes(buf)
        return Message.CertReply(der)
    }

    private fun encodeCertReply(msg: Message.CertReply): ByteArray {
        val buf = ByteBuffer.allocate(4 + msg.certDer.size).order(ByteOrder.BIG_ENDIAN)
        buf.putInt(msg.certDer.size)
        buf.put(msg.certDer)
        return buf.array()
    }

    private fun decodePeerBeacon(buf: ByteBuffer): Message.PeerBeacon {
        val uid = readLengthPrefixedString(buf)
        val callsign = readLengthPrefixedString(buf)
        val flags = buf.get().toInt() and 0xFF
        val channelCount = buf.int
        require(channelCount in 0..MAX_BEACON_CHANNELS) {
            "beacon channel count $channelCount out of bounds (0..$MAX_BEACON_CHANNELS)"
        }
        val channels =
            (0 until channelCount).map {
                val name = readLengthPrefixedString(buf)
                val group = readLengthPrefixedString(buf)
                val port = buf.int
                val keyEpoch = buf.int
                Message.PeerBeacon.Channel(name, group, port, keyEpoch)
            }
        return Message.PeerBeacon(
            uid = uid,
            callsign = callsign,
            mumbleConnected = (flags and FLAG_MUMBLE_CONNECTED) != 0,
            bridging = (flags and FLAG_BRIDGING) != 0,
            channels = channels,
        )
    }

    private fun encodePeerBeacon(msg: Message.PeerBeacon): ByteArray {
        val uidBytes = msg.uid.toByteArray(Charsets.UTF_8)
        val callsignBytes = msg.callsign.toByteArray(Charsets.UTF_8)
        val channelBytes =
            msg.channels.map { ch ->
                Triple(
                    ch.name.toByteArray(Charsets.UTF_8),
                    ch.group.toByteArray(Charsets.UTF_8),
                    ch,
                )
            }
        val size =
            4 + uidBytes.size + 4 + callsignBytes.size + 1 + 4 +
                channelBytes.sumOf { (n, g, _) -> 4 + n.size + 4 + g.size + 8 }
        val buf = ByteBuffer.allocate(size).order(ByteOrder.BIG_ENDIAN)
        buf.putInt(uidBytes.size)
        buf.put(uidBytes)
        buf.putInt(callsignBytes.size)
        buf.put(callsignBytes)
        var flags = 0
        if (msg.mumbleConnected) flags = flags or FLAG_MUMBLE_CONNECTED
        if (msg.bridging) flags = flags or FLAG_BRIDGING
        buf.put(flags.toByte())
        buf.putInt(channelBytes.size)
        channelBytes.forEach { (n, g, ch) ->
            buf.putInt(n.size)
            buf.put(n)
            buf.putInt(g.size)
            buf.put(g)
            buf.putInt(ch.port)
            buf.putInt(ch.keyEpoch)
        }
        return buf.array()
    }

    private const val FLAG_MUMBLE_CONNECTED = 0x01
    private const val FLAG_BRIDGING = 0x02

    // A beacon advertising more channels than an operator could
    // plausibly have joined is malformed input, not a big deployment.
    private const val MAX_BEACON_CHANNELS = 64

    private fun readLengthPrefixedString(buf: ByteBuffer): String {
        val bytes = readLengthPrefixedBytes(buf)
        return String(bytes, Charsets.UTF_8)
    }

    private fun readLengthPrefixedBytes(buf: ByteBuffer): ByteArray {
        val len = buf.int
        // Cap at 64 KiB so a malformed length doesn't cause an OOM.
        require(len in 0..MAX_FIELD_LEN) { "length $len out of bounds (0..$MAX_FIELD_LEN)" }
        require(buf.remaining() >= len) { "truncated payload: need $len bytes, have ${buf.remaining()}" }
        val out = ByteArray(len)
        buf.get(out)
        return out
    }

    private const val MAX_FIELD_LEN = 64 * 1024

    sealed class Message {
        abstract val type: Type

        /**
         * "Anyone holding key for (channel, epoch), please send me an
         * OAEP-wrapped copy. My uid is requesterUid."
         */
        data class KeyReq(
            val channelId: Int,
            val forEpoch: Int,
            val requesterUid: String,
        ) : Message() {
            override val type = Type.KeyReq
        }

        /**
         * "Here is the channel key for (channel, epoch), wrapped to
         * recipientUid's enrollment cert public key."
         */
        data class KeyOffer(
            val channelId: Int,
            val epoch: Int,
            val recipientUid: String,
            val wrappedKey: ByteArray,
        ) : Message() {
            override val type = Type.KeyOffer

            // ByteArray equals/hashCode override so data-class semantics work
            // sensibly in tests.
            override fun equals(other: Any?): Boolean {
                if (this === other) return true
                if (other !is KeyOffer) return false
                return channelId == other.channelId &&
                    epoch == other.epoch &&
                    recipientUid == other.recipientUid &&
                    wrappedKey.contentEquals(other.wrappedKey)
            }

            override fun hashCode(): Int {
                var h = channelId
                h = 31 * h + epoch
                h = 31 * h + recipientUid.hashCode()
                h = 31 * h + wrappedKey.contentHashCode()
                return h
            }
        }

        /**
         * "Anyone holding the leaf cert with this fingerprint, please
         * publish your DER bytes." Used by joiners to learn the public
         * key of peers they want to send KeyOffer to.
         */
        data class CertReq(
            val wantedCertFp: String,
        ) : Message() {
            override val type = Type.CertReq
        }

        /**
         * "Here is my DER-encoded leaf cert." Recipients verify the
         * cert chains to TAK CA AND that SHA-256 of certDer equals the
         * registry's cert fingerprint binding for the publishing uid.
         */
        data class CertReply(
            val certDer: ByteArray,
        ) : Message() {
            override val type = Type.CertReply

            override fun equals(other: Any?): Boolean {
                if (this === other) return true
                if (other !is CertReply) return false
                return certDer.contentEquals(other.certDer)
            }

            override fun hashCode(): Int = certDer.contentHashCode()
        }

        /**
         * Periodic peer announcement, doing double duty:
         *
         *   1. Offline discovery — [channels] advertises the multicast
         *      channels this peer has live (name + endpoint) so a peer
         *      with NO server profile and NO comms plan can still find
         *      the net. Broadcast on the well-known rendezvous group
         *      as well as each joined channel group.
         *   2. Bridge election input — [mumbleConnected] + [bridging]
         *      tell [BridgeElection] who can reach the server and who
         *      currently claims the relay role.
         *
         * Beacons are cleartext by design: they carry no keys and no
         * traffic, and an offline joiner by definition can't decrypt
         * yet. Group/channel names are operator-chosen labels only.
         */
        data class PeerBeacon(
            val uid: String,
            val callsign: String,
            val mumbleConnected: Boolean,
            val bridging: Boolean,
            val channels: List<Channel>,
        ) : Message() {
            override val type = Type.PeerBeacon

            /**
             * @property keyEpoch the sender's current key epoch on this
             *   channel, or [ChannelKeyRegistry.NO_EPOCH] (-1) when they
             *   hold no key. Feeds the key election: a keyless joiner
             *   learns whom to ask, and simultaneous keyless starts
             *   converge on one generator instead of splitting the key.
             */
            data class Channel(
                val name: String,
                val group: String,
                val port: Int,
                val keyEpoch: Int = ChannelKeyRegistry.NO_EPOCH,
            )
        }
    }
}
