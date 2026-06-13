package com.atakmap.android.xv.transport.mumble

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Wire-format tests for Mumble voice packets — both the build path (we
 * emit; server parses) and the parse path (server emits; we decode).
 *
 * Mumble's varint encoding is the bit-tricky surface here. A regression
 * in either direction is impossible to debug from a field log (the
 * Opus payload arrives at the wrong session/sequence; the server
 * silently drops or mis-routes). Pin the encoder lengths + canonical
 * decode shapes here so wire drift gets caught at CI time.
 */
class MumbleVoicePacketTest {
    @Test
    fun `outbound packet header encodes type=4 and target=0 for normal talk`() {
        val opus = byteArrayOf(0x01, 0x02, 0x03)
        val bytes =
            MumbleVoicePacket.buildOutbound(
                target = MumbleVoicePacket.TARGET_NORMAL_TALK,
                sequence = 1L,
                opusPayload = opus,
                terminator = false,
            )
        // First byte: (type<<5) | (target & 0x1F) = (4<<5)|0 = 0x80.
        assertEquals(0x80.toByte(), bytes[0])
    }

    @Test
    fun `outbound packet preserves target id in low 5 bits`() {
        val opus = byteArrayOf(0x00)
        val bytes =
            MumbleVoicePacket.buildOutbound(
                target = 7,
                sequence = 1L,
                opusPayload = opus,
                terminator = false,
            )
        assertEquals(0x87.toByte(), bytes[0])
    }

    @Test
    fun `varint small sequence encodes in one byte`() {
        // seq=1 → header(1) + varint(1 byte) + opus-header-varint(1) + opus(3) = 6 bytes
        val opus = byteArrayOf(0x01, 0x02, 0x03)
        val bytes = MumbleVoicePacket.buildOutbound(0, 1L, opus, false)
        assertEquals(6, bytes.size)
    }

    @Test
    fun `varint at 14-bit boundary encodes in two bytes`() {
        // seq=0x80 — first value past the 7-bit range — should grow to 2 bytes.
        val opus = byteArrayOf(0x01)
        val bytes = MumbleVoicePacket.buildOutbound(0, 0x80L, opus, false)
        // header(1) + varint(2) + opus-header-varint(1) + opus(1) = 5 bytes
        assertEquals(5, bytes.size)
        // Second byte (start of varint) should have the 0x80 marker.
        assertEquals(0x80.toByte(), bytes[1])
    }

    @Test
    fun `terminator flag is encoded in opus-header bit13`() {
        val opus = byteArrayOf(0x01)
        val withTerminator = MumbleVoicePacket.buildOutbound(0, 1L, opus, terminator = true)
        val without = MumbleVoicePacket.buildOutbound(0, 1L, opus, terminator = false)
        // For a 1-byte Opus payload, the no-terminator opus-header is
        // value=1 (fits in 1-byte varint). With terminator it's
        // 1 | 0x2000 = 0x2001 — past the 7-bit range, so the varint
        // grows to 2 bytes. Net: the terminator path is exactly 1
        // byte longer.
        assertEquals(without.size + 1, withTerminator.size)
        // And the round-trip survives an internal-shape parse. We
        // build the full server-shape frame (session prepended) and
        // verify parse() recovers the terminator bit.
        val serverShape =
            byteArrayOf(0x80.toByte(), 0x01) + withTerminator.copyOfRange(1, withTerminator.size)
        val parsed = MumbleVoicePacket.parse(serverShape)
        assertNotNull(parsed)
        assertTrue(parsed!!.terminator)
    }

    @Test
    fun `parse rejects empty payload`() {
        assertNull(MumbleVoicePacket.parse(ByteArray(0)))
    }

    @Test
    fun `parse rejects non-Opus packet types`() {
        // type=1 (ping) in the high bits. Voice path should ignore.
        val bytes = byteArrayOf((1 shl 5).toByte(), 0, 0, 0)
        assertNull(MumbleVoicePacket.parse(bytes))
    }

    @Test
    fun `parse extracts session sequence and opus payload from server-shaped frame`() {
        // Build a frame in the SERVER → CLIENT shape:
        //   header(type=4, target=0) + session-varint + sequence-varint
        //   + opus-header-varint + opus
        // Use small values so everything fits in 1-byte varints.
        val opus = byteArrayOf(0x11, 0x22, 0x33, 0x44)
        val frame =
            byteArrayOf(
                0x80.toByte(), // type=4, target=0
                0x05, // session=5 (1-byte varint)
                0x07, // sequence=7
                opus.size.toByte(), // opus-header (terminator=false, len=4)
            ) + opus
        val parsed = MumbleVoicePacket.parse(frame)
        assertNotNull("frame should parse", parsed)
        parsed!!
        assertEquals(MumbleVoicePacket.TYPE_OPUS_AUDIO, parsed.type)
        assertEquals(0, parsed.target)
        assertEquals(5L, parsed.session)
        assertEquals(7L, parsed.sequence)
        assertEquals(false, parsed.terminator)
        assertArrayEquals(opus, parsed.opusPayload)
    }

    @Test
    fun `parse detects the terminator bit`() {
        val opus = byteArrayOf(0x42)
        // opus-header: bit13 set, length=1 → 0x2001 → 14-bit varint =
        // 0x80|((0x2001>>8)&0x3F) , 0x2001&0xFF
        //   high = 0x80 | (0x20 & 0x3F) = 0x80 | 0x20 = 0xA0
        //   low  = 0x01
        val frame =
            byteArrayOf(
                0x80.toByte(),
                0x01, // session
                0x01, // sequence
                0xA0.toByte(), // opus-header high
                0x01, // opus-header low
            ) + opus
        val parsed = MumbleVoicePacket.parse(frame)
        assertNotNull(parsed)
        assertEquals(true, parsed!!.terminator)
        assertArrayEquals(opus, parsed.opusPayload)
    }

    @Test
    fun `parse rejects frames whose declared opus length exceeds buffer`() {
        // Claimed length 100 but only 4 bytes of payload follow — parser
        // must return null instead of attempting an out-of-bounds copy.
        val frame =
            byteArrayOf(
                0x80.toByte(),
                0x01, // session
                0x01, // sequence
                100, // opus-header: terminator=false, len=100
                0x01,
                0x02,
                0x03,
                0x04,
            )
        assertNull(MumbleVoicePacket.parse(frame))
    }

    @Test
    fun `parse rejects frames whose declared opus length is zero or negative`() {
        // Zero-length Opus frame is meaningless on the wire — even a
        // terminator frame carries at least one Opus byte. Reject so
        // downstream decoders don't see an empty ByteArray.
        val frame = byteArrayOf(0x80.toByte(), 0x01, 0x01, 0x00)
        assertNull(MumbleVoicePacket.parse(frame))
    }
}
