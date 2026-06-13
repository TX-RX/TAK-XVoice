package com.atakmap.android.xv.transport.multicast

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ControlPacketTest {
    @Test
    fun `KeyReq round-trip`() {
        val msg =
            ControlPacket.Message.KeyReq(
                channelId = 6,
                forEpoch = 7,
                requesterUid = "ANDROID-cc22d724c0152f37",
            )
        val bytes = ControlPacket.encode(msg)
        val back = ControlPacket.decode(bytes)
        assertEquals(msg, back)
    }

    @Test
    fun `KeyOffer round-trip`() {
        val wrapped = ByteArray(256) { it.toByte() }
        val msg =
            ControlPacket.Message.KeyOffer(
                channelId = 6,
                epoch = 42,
                recipientUid = "ANDROID-bbb",
                wrappedKey = wrapped,
            )
        val bytes = ControlPacket.encode(msg)
        val back = ControlPacket.decode(bytes) as ControlPacket.Message.KeyOffer
        assertEquals(msg.channelId, back.channelId)
        assertEquals(msg.epoch, back.epoch)
        assertEquals(msg.recipientUid, back.recipientUid)
        assertArrayEquals(msg.wrappedKey, back.wrappedKey)
    }

    @Test
    fun `CertReq round-trip`() {
        val msg = ControlPacket.Message.CertReq(wantedCertFp = "abcd1234".repeat(8))
        val bytes = ControlPacket.encode(msg)
        assertEquals(msg, ControlPacket.decode(bytes))
    }

    @Test
    fun `CertReply round-trip`() {
        val der = ByteArray(1024) { (it xor 0x77).toByte() }
        val msg = ControlPacket.Message.CertReply(certDer = der)
        val bytes = ControlPacket.encode(msg)
        val back = ControlPacket.decode(bytes) as ControlPacket.Message.CertReply
        assertArrayEquals(der, back.certDer)
    }

    @Test
    fun `isControl rejects voice frames`() {
        // Voice frames start with the epoch byte (0..255) — never the
        // ASCII string "XVMC".
        val voice =
            ByteArray(64).apply {
                this[0] = 0x07
                this[1] = 0x00
                this[2] = 0x01
                this[3] = 0x02
            }
        assertFalse(ControlPacket.isControl(voice))
    }

    @Test
    fun `isControl rejects too-short datagrams`() {
        assertFalse(ControlPacket.isControl(ByteArray(0)))
        assertFalse(ControlPacket.isControl(byteArrayOf(0x58, 0x56))) // partial XV
    }

    @Test
    fun `decode returns null on unknown type`() {
        // Trailing 0x7F is an unknown type byte.
        val bytes = byteArrayOf(0x58, 0x56, 0x4D, 0x43, 0x7F)
        assertNull(ControlPacket.decode(bytes))
    }

    @Test
    fun `decode returns null on truncated body`() {
        // KeyReq header is well-formed but body claims uid length 100
        // with zero bytes of payload.
        val msg = ControlPacket.Message.KeyReq(channelId = 1, forEpoch = 0, requesterUid = "x")
        val good = ControlPacket.encode(msg)
        // Truncate to header + first 4 bytes (channelId only — rest missing).
        val truncated = good.copyOfRange(0, 9)
        assertNull(ControlPacket.decode(truncated))
    }

    @Test
    fun `decode rejects payloads with implausible length prefix`() {
        // Header + KeyReq(channelId=1, forEpoch=0, uidLen=99999999)
        val bytes = ByteArray(5 + 4 + 1 + 4)
        bytes[0] = 0x58
        bytes[1] = 0x56
        bytes[2] = 0x4D
        bytes[3] = 0x43
        bytes[4] = 0x01 // KeyReq
        // channelId=1, big-endian
        bytes[8] = 0x01
        // forEpoch=0
        bytes[9] = 0x00
        // uidLen = 0x05F5E0FF (~100M) — way over MAX_FIELD_LEN
        bytes[10] = 0x05
        bytes[11] = 0xF5.toByte()
        bytes[12] = 0xE0.toByte()
        bytes[13] = 0xFF.toByte()
        assertNull(ControlPacket.decode(bytes))
    }

    @Test
    fun `each control type carries the right type code`() {
        val keyReq =
            ControlPacket.encode(ControlPacket.Message.KeyReq(1, 0, "x"))
        val keyOffer =
            ControlPacket.encode(ControlPacket.Message.KeyOffer(1, 0, "x", ByteArray(0)))
        val certReq =
            ControlPacket.encode(ControlPacket.Message.CertReq("fp"))
        val certReply =
            ControlPacket.encode(ControlPacket.Message.CertReply(ByteArray(0)))
        assertEquals(0x01.toByte(), keyReq[4])
        assertEquals(0x02.toByte(), keyOffer[4])
        assertEquals(0x03.toByte(), certReq[4])
        assertEquals(0x04.toByte(), certReply[4])
        // All carry the magic prefix.
        for (b in arrayOf(keyReq, keyOffer, certReq, certReply)) {
            assertTrue("missing magic on ${b.toHex()}", ControlPacket.isControl(b))
        }
    }

    @Test
    fun `large recipientUid in KeyOffer round-trips`() {
        // Edge case: long UIDs (e.g. fully-qualified TAK device names).
        val longUid = "ANDROID-".repeat(8) + "cc22d724c0152f37"
        val msg =
            ControlPacket.Message.KeyOffer(
                channelId = 9999,
                epoch = 200,
                recipientUid = longUid,
                wrappedKey = ByteArray(256) { (it * 3).toByte() },
            )
        val back = ControlPacket.decode(ControlPacket.encode(msg))
        assertEquals(msg, back)
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
