package com.atakmap.android.xv.transport.mumble

// Mumble voice packet (TCP UDPTunnel payload from server to client).
//
// Wire format (Opus codec):
//   byte 0:        type<<5 | target  (type=4 = Opus, target 0..30 = talker,
//                                     31 = server loopback)
//   varint:        session id of the speaker (server→client only)
//   varint:        sequence number
//   varint:        opus header — bit13 = terminator, bits0..12 = payload len
//   <len> bytes:   opus payload
//   optional 12 bytes: 3×float32 LE positional audio
//
// We parse just enough to extract speaker session + opus payload and route
// it to the audio path.
internal object MumbleVoicePacket {
    const val TYPE_OPUS_AUDIO = 4
    const val TYPE_PING = 1

    const val TARGET_NORMAL_TALK = 0
    const val TARGET_SERVER_LOOPBACK = 31

    // Build a client-→-server voice packet payload (the bytes that go inside
    // a UDPTunnel protobuf message). Wire format mirrors `parse()` but omits
    // the speaker-session field — the server fills that in on its own side.
    fun buildOutbound(
        target: Int,
        sequence: Long,
        opusPayload: ByteArray,
        terminator: Boolean,
    ): ByteArray {
        val out = java.io.ByteArrayOutputStream(opusPayload.size + 8)
        val header = ((TYPE_OPUS_AUDIO shl 5) or (target and 0x1F)) and 0xFF
        out.write(header)
        writeVarint(out, sequence)
        val opusHeader = (opusPayload.size and 0x1FFF).toLong() or (if (terminator) 0x2000L else 0L)
        writeVarint(out, opusHeader)
        out.write(opusPayload)
        return out.toByteArray()
    }

    private fun writeVarint(
        out: java.io.OutputStream,
        value: Long,
    ) {
        // Mumble varint encoder. Negative values are unused for sequence /
        // length, so we skip the negative branches the parser handles.
        when {
            value < 0 -> {
                // Per spec: 111101xx + 4-byte signed value, but we don't
                // emit negatives. Fall through to 8-byte encoding for safety.
                out.write(0xF4)
                writeBigEndian64(out, value)
            }
            value < 0x80L -> {
                // 7-bit positive
                out.write(value.toInt() and 0x7F)
            }
            value < 0x4000L -> {
                // 14-bit positive
                out.write((((value shr 8) and 0x3F) or 0x80).toInt())
                out.write((value and 0xFF).toInt())
            }
            value < 0x200000L -> {
                // 21-bit positive
                out.write((((value shr 16) and 0x1F) or 0xC0).toInt())
                out.write(((value shr 8) and 0xFF).toInt())
                out.write((value and 0xFF).toInt())
            }
            value < 0x10000000L -> {
                // 28-bit positive
                out.write((((value shr 24) and 0x0F) or 0xE0).toInt())
                out.write(((value shr 16) and 0xFF).toInt())
                out.write(((value shr 8) and 0xFF).toInt())
                out.write((value and 0xFF).toInt())
            }
            value < 0x100000000L -> {
                // 32-bit positive: 11110xxx + 4 bytes BE
                out.write(0xF0)
                out.write(((value shr 24) and 0xFF).toInt())
                out.write(((value shr 16) and 0xFF).toInt())
                out.write(((value shr 8) and 0xFF).toInt())
                out.write((value and 0xFF).toInt())
            }
            else -> {
                // 64-bit: 111100xx + 8 bytes BE
                out.write(0xF4)
                writeBigEndian64(out, value)
            }
        }
    }

    private fun writeBigEndian64(
        out: java.io.OutputStream,
        value: Long,
    ) {
        for (shift in 56 downTo 0 step 8) {
            out.write(((value ushr shift) and 0xFF).toInt())
        }
    }

    data class Parsed(
        val type: Int,
        val target: Int,
        val session: Long,
        val sequence: Long,
        val opusPayload: ByteArray,
        val terminator: Boolean,
    )

    fun parse(payload: ByteArray): Parsed? {
        if (payload.isEmpty()) return null
        val r = Reader(payload, 0)
        val header = r.readByte().toInt() and 0xFF
        val type = (header shr 5) and 0x07
        val target = header and 0x1F
        if (type != TYPE_OPUS_AUDIO) return null
        val session = r.readVarint()
        val sequence = r.readVarint()
        val opusHeader = r.readVarint()
        val terminator = (opusHeader and (1L shl 13)) != 0L
        val opusLen = (opusHeader and 0x1FFFL).toInt()
        if (opusLen <= 0 || r.pos + opusLen > payload.size) return null
        val opus = payload.copyOfRange(r.pos, r.pos + opusLen)
        return Parsed(type, target, session, sequence, opus, terminator)
    }

    // Mumble varint decoder — see https://www.mumble.info/documentation/development/protocol/voicepacket/
    // Top bits of the first byte select the encoding length.
    private class Reader(
        val buf: ByteArray,
        var pos: Int,
    ) {
        fun readByte(): Byte = buf[pos++]

        fun readVarint(): Long {
            val b0 = readByte().toInt() and 0xFF
            return when {
                b0 and 0x80 == 0x00 -> b0.toLong()
                b0 and 0xC0 == 0x80 -> ((b0 and 0x3F).toLong() shl 8) or (readByte().toLong() and 0xFF)
                b0 and 0xE0 == 0xC0 -> {
                    val b1 = readByte().toLong() and 0xFF
                    val b2 = readByte().toLong() and 0xFF
                    ((b0 and 0x1F).toLong() shl 16) or (b1 shl 8) or b2
                }
                b0 and 0xF0 == 0xE0 -> {
                    val b1 = readByte().toLong() and 0xFF
                    val b2 = readByte().toLong() and 0xFF
                    val b3 = readByte().toLong() and 0xFF
                    ((b0 and 0x0F).toLong() shl 24) or (b1 shl 16) or (b2 shl 8) or b3
                }
                b0 and 0xFC == 0xF0 -> {
                    val b1 = readByte().toLong() and 0xFF
                    val b2 = readByte().toLong() and 0xFF
                    val b3 = readByte().toLong() and 0xFF
                    val b4 = readByte().toLong() and 0xFF
                    (b1 shl 24) or (b2 shl 16) or (b3 shl 8) or b4
                }
                b0 and 0xFC == 0xF4 -> {
                    // 64-bit big-endian
                    var v = 0L
                    for (i in 0 until 8) {
                        v = (v shl 8) or (readByte().toLong() and 0xFF)
                    }
                    v
                }
                b0 and 0xFC == 0xF8 -> -readVarint()
                b0 and 0xFC == 0xFC -> -((b0 and 0x03).toLong())
                else -> 0L
            }
        }
    }
}
