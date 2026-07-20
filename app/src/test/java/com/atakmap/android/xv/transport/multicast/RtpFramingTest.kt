package com.atakmap.android.xv.transport.multicast

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RtpFramingTest {
    @Test
    fun `encode produces 12-byte header plus payload`() {
        val opus = byteArrayOf(0x10, 0x20, 0x30)
        val wire = RtpFraming.encode(SEQ_NUM, TS, SSRC, opus)
        assertEquals(RtpFraming.HEADER_BYTES + opus.size, wire.size)
    }

    @Test
    fun `encode then decode round-trips header fields`() {
        val opus = byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05)
        val wire = RtpFraming.encode(SEQ_NUM, TS, SSRC, opus)
        val (hdr, payload) = RtpFraming.decode(wire)!!
        assertEquals(SEQ_NUM, hdr.sequenceNumber)
        assertEquals(TS, hdr.timestamp)
        assertEquals(SSRC, hdr.ssrc)
        assertEquals(RtpFraming.PAYLOAD_TYPE_OPUS, hdr.payloadType)
        assertEquals(false, hdr.marker)
        assertArrayEquals(opus, payload)
    }

    @Test
    fun `version field is RFC 3550 V=2`() {
        val wire = RtpFraming.encode(0, 0L, 0L, byteArrayOf())
        // V occupies the top 2 bits of byte 0. V=2 → 0b10xx_xxxx
        val v = (wire[0].toInt() shr 6) and 0x3
        assertEquals(2, v)
    }

    @Test
    fun `payload type 111 matches VX`() {
        val wire = RtpFraming.encode(0, 0L, 0L, byteArrayOf())
        val pt = wire[1].toInt() and 0x7F
        assertEquals(111, pt)
    }

    @Test
    fun `marker bit propagates through encode and decode`() {
        val wire = RtpFraming.encode(0, 0L, 0L, byteArrayOf(0x42), marker = true)
        // M is bit 7 of byte 1.
        assertTrue((wire[1].toInt() and 0x80) != 0)
        val (hdr, _) = RtpFraming.decode(wire)!!
        assertEquals(true, hdr.marker)
    }

    @Test
    fun `decode rejects datagrams smaller than the header`() {
        assertNull(RtpFraming.decode(ByteArray(0)))
        assertNull(RtpFraming.decode(ByteArray(RtpFraming.HEADER_BYTES - 1)))
    }

    @Test
    fun `decode rejects RTP version other than 2`() {
        // Hand-build a header with V=1 (invalid for our use).
        val wire = ByteArray(RtpFraming.HEADER_BYTES) { 0 }
        wire[0] = 0x40 // V=01
        assertNull(RtpFraming.decode(wire))
    }

    @Test
    fun `decode accepts and skips extensions`() {
        val wire = RtpFraming.encode(0, 0L, 0L, byteArrayOf(0x05))
        // Set X bit
        wire[0] = (wire[0].toInt() or 0x10).toByte()
        // We need to inject the extension before the payload
        val withExt = ByteArray(wire.size + 4)
        System.arraycopy(wire, 0, withExt, 0, 12)
        // Extension length = 0
        withExt[12] = 0
        withExt[13] = 0
        withExt[14] = 0
        withExt[15] = 0
        System.arraycopy(wire, 12, withExt, 16, wire.size - 12)

        val decoded = RtpFraming.decode(withExt)
        assertTrue(decoded != null)
        assertArrayEquals(byteArrayOf(0x05), decoded!!.second)
    }

    @Test
    fun `decode accepts and skips CSRC list`() {
        val wire = RtpFraming.encode(0, 0L, 0L, byteArrayOf(0x05))
        // Set CC=1
        wire[0] = (wire[0].toInt() or 0x01).toByte()
        val withCsrc = ByteArray(wire.size + 4)
        System.arraycopy(wire, 0, withCsrc, 0, 12)
        // CSRC value
        withCsrc[12] = 1
        withCsrc[13] = 2
        withCsrc[14] = 3
        withCsrc[15] = 4
        System.arraycopy(wire, 12, withCsrc, 16, wire.size - 12)

        val decoded = RtpFraming.decode(withCsrc)
        assertTrue(decoded != null)
        assertArrayEquals(byteArrayOf(0x05), decoded!!.second)
    }

    @Test
    fun `large 32-bit timestamp survives round-trip without sign extension`() {
        val bigTs = 0xFEDC_BA98L
        val wire = RtpFraming.encode(0, bigTs, 0L, byteArrayOf())
        val (hdr, _) = RtpFraming.decode(wire)!!
        assertEquals(bigTs, hdr.timestamp)
    }

    @Test
    fun `large 32-bit ssrc survives round-trip without sign extension`() {
        val bigSsrc = 0x90AB_CDEFL
        val wire = RtpFraming.encode(0, 0L, bigSsrc, byteArrayOf())
        val (hdr, _) = RtpFraming.decode(wire)!!
        assertEquals(bigSsrc, hdr.ssrc)
    }

    @Test
    fun `sequence number wraps at 16 bits`() {
        // Caller is responsible for the wrap; encoder just masks low 16
        // bits implicitly via &0xFF on each byte.
        val seq = 0xFFFF
        val wire = RtpFraming.encode(seq, 0L, 0L, byteArrayOf())
        val (hdr, _) = RtpFraming.decode(wire)!!
        assertEquals(0xFFFF, hdr.sequenceNumber)
    }

    @Test
    fun `fnv1aSsrc is deterministic for same input`() {
        val a = RtpFraming.fnv1aSsrc("hello-uid")
        val b = RtpFraming.fnv1aSsrc("hello-uid")
        assertEquals(a, b)
    }

    @Test
    fun `fnv1aSsrc differs for different inputs`() {
        val a = RtpFraming.fnv1aSsrc("uid-a")
        val b = RtpFraming.fnv1aSsrc("uid-b")
        assertTrue(a != b)
    }

    @Test
    fun `fnv1aSsrc fits in unsigned 32 bits`() {
        val h = RtpFraming.fnv1aSsrc("any-string-here-1234567890")
        assertTrue(h in 0L..0xFFFFFFFFL)
    }

    @Test
    fun `fnv1aSsrc empty string yields offset basis`() {
        // Standard FNV-1a offset basis.
        assertEquals(0x811C9DC5L, RtpFraming.fnv1aSsrc(""))
    }

    @Test
    fun `fnv1aSsrc known vector matches FNV specification`() {
        // FNV-1a 32-bit hash of "a" should be 0xE40C292C per the spec.
        assertEquals(0xE40C292CL, RtpFraming.fnv1aSsrc("a"))
    }

    @Test
    fun `payload bytes are unchanged across round-trip`() {
        // Full Opus payload range — random-ish bytes including high-bit set.
        val opus = ByteArray(80) { ((it * 7 + 13) and 0xFF).toByte() }
        val wire = RtpFraming.encode(42, 1234L, 0xDEADBEEFL, opus)
        val decoded = RtpFraming.decode(wire)!!
        assertArrayEquals(opus, decoded.second)
    }

    companion object {
        private const val SEQ_NUM = 12345
        private const val TS = 987_654L
        private const val SSRC = 0xCAFEBABEL
    }
}
