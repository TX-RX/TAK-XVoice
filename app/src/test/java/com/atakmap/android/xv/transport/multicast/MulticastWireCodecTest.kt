package com.atakmap.android.xv.transport.multicast

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MulticastWireCodecTest {
    private val ssrc = 0xDEADBEEFL
    private val opus = byteArrayOf(0x11, 0x22, 0x33, 0x44, 0x55)

    private fun registryWithKey(
        epoch: Int = 3,
        key: ByteArray = ByteArray(AeadCodec.KEY_BYTES) { 0x42 },
    ): ChannelKeyRegistry =
        ChannelKeyRegistry(channelId = 7).apply {
            install(epoch, key)
        }

    private fun voice(result: MulticastWireCodec.RxResult): MulticastWireCodec.RxResult.Voice {
        assertTrue("expected Voice, got $result", result is MulticastWireCodec.RxResult.Voice)
        return result as MulticastWireCodec.RxResult.Voice
    }

    private fun dropReason(result: MulticastWireCodec.RxResult): MulticastWireCodec.DropReason {
        assertTrue("expected Dropped, got $result", result is MulticastWireCodec.RxResult.Dropped)
        return (result as MulticastWireCodec.RxResult.Dropped).reason
    }

    // ---- XV native, cleartext path ----

    @Test
    fun `cleartext frame round-trips with ssrc attribution and burst sequence`() {
        val tx = XvNativeWireCodec(ssrc, CryptoPolicy.CLEARTEXT, keyRegistry = null)
        val rx = XvNativeWireCodec(ssrc = 1L, cryptoPolicy = CryptoPolicy.PREFERRED, keyRegistry = null)
        tx.beginBurst()
        val datagram = tx.encodeTx(opus)
        assertNotNull(datagram)

        val v = voice(rx.decodeRx(datagram!!, sourceHost = "198.51.100.7"))
        assertEquals("ssrc:deadbeef", v.speakerKey)
        assertEquals(0, v.seqInBurst)
        assertArrayEquals(opus, v.opus)
    }

    @Test
    fun `sequence resets on beginBurst but the timestamp keeps running`() {
        val tx = XvNativeWireCodec(ssrc, CryptoPolicy.CLEARTEXT, keyRegistry = null)
        tx.beginBurst()
        val first = RtpFraming.decode(tx.encodeTx(opus)!!)!!.first
        val second = RtpFraming.decode(tx.encodeTx(opus)!!)!!.first
        tx.beginBurst()
        val third = RtpFraming.decode(tx.encodeTx(opus)!!)!!.first

        assertEquals(0, first.sequenceNumber)
        assertEquals(1, second.sequenceNumber)
        assertEquals(0, third.sequenceNumber)

        // Marker flags the PTT-down edge of each burst, nothing else.
        assertTrue(first.marker)
        assertTrue(!second.marker)
        assertTrue(third.marker)

        // Timestamp is NOT burst-relative — receivers see silence gaps.
        assertEquals(first.timestamp + RtpFraming.TIMESTAMP_INCREMENT_20MS, second.timestamp)
        assertEquals(second.timestamp + RtpFraming.TIMESTAMP_INCREMENT_20MS, third.timestamp)
    }

    // ---- XV native, encrypted path ----

    @Test
    fun `preferred policy encrypts when a key is live and the peer decrypts it`() {
        val key = ByteArray(AeadCodec.KEY_BYTES) { 0x42 }
        val tx = XvNativeWireCodec(ssrc, CryptoPolicy.PREFERRED, registryWithKey(epoch = 3, key = key))
        val rx = XvNativeWireCodec(ssrc = 1L, cryptoPolicy = CryptoPolicy.PREFERRED, keyRegistry = registryWithKey(epoch = 3, key = key))
        tx.beginBurst()
        val datagram = tx.encodeTx(opus)!!

        // On the wire it is an AEAD frame (epoch byte), not RTP (0x80).
        assertEquals(3, datagram[0].toInt() and 0xFF)

        val v = voice(rx.decodeRx(datagram, sourceHost = "198.51.100.7"))
        assertEquals("ssrc:deadbeef", v.speakerKey)
        assertEquals(0, v.seqInBurst)
        assertArrayEquals(opus, v.opus)
    }

    @Test
    fun `preferred policy degrades to cleartext rtp while keying has not converged`() {
        val tx = XvNativeWireCodec(ssrc, CryptoPolicy.PREFERRED, keyRegistry = ChannelKeyRegistry(channelId = 7))
        tx.beginBurst()
        val datagram = tx.encodeTx(opus)!!
        assertEquals(0x80, datagram[0].toInt() and 0xFF)
        assertNotNull(RtpFraming.decode(datagram))
    }

    @Test
    fun `required policy refuses to send until a key is live`() {
        val tx = XvNativeWireCodec(ssrc, CryptoPolicy.REQUIRED, keyRegistry = ChannelKeyRegistry(channelId = 7))
        tx.beginBurst()
        assertNull(tx.encodeTx(opus))
    }

    @Test
    fun `keyed receiver decrypts frames from the previous epoch during rotation`() {
        val oldKey = ByteArray(AeadCodec.KEY_BYTES) { 0x42 }
        val tx = XvNativeWireCodec(ssrc, CryptoPolicy.PREFERRED, registryWithKey(epoch = 3, key = oldKey))
        tx.beginBurst()
        val datagram = tx.encodeTx(opus)!!

        val rxRegistry = registryWithKey(epoch = 3, key = oldKey)
        rxRegistry.install(4, ByteArray(AeadCodec.KEY_BYTES) { 0x24 })
        val rx = XvNativeWireCodec(ssrc = 1L, cryptoPolicy = CryptoPolicy.PREFERRED, keyRegistry = rxRegistry)
        val v = voice(rx.decodeRx(datagram, sourceHost = "198.51.100.7"))
        assertArrayEquals(opus, v.opus)
    }

    @Test
    fun `wrong key for a known epoch drops as bad tag`() {
        val tx = XvNativeWireCodec(ssrc, CryptoPolicy.PREFERRED, registryWithKey(epoch = 3, key = ByteArray(AeadCodec.KEY_BYTES) { 0x42 }))
        tx.beginBurst()
        val datagram = tx.encodeTx(opus)!!

        val rx =
            XvNativeWireCodec(
                ssrc = 1L,
                cryptoPolicy = CryptoPolicy.PREFERRED,
                keyRegistry = registryWithKey(epoch = 3, key = ByteArray(AeadCodec.KEY_BYTES) { 0x24 }),
            )
        assertEquals(
            MulticastWireCodec.DropReason.BAD_TAG,
            dropReason(rx.decodeRx(datagram, sourceHost = "198.51.100.7")),
        )
    }

    @Test
    fun `keyed receiver still accepts cleartext rtp - byte 0x80 never collides with an epoch`() {
        val tx = XvNativeWireCodec(ssrc, CryptoPolicy.CLEARTEXT, keyRegistry = null)
        tx.beginBurst()
        val datagram = tx.encodeTx(opus)!!

        val rx = XvNativeWireCodec(ssrc = 1L, cryptoPolicy = CryptoPolicy.PREFERRED, keyRegistry = registryWithKey(epoch = 3))
        val v = voice(rx.decodeRx(datagram, sourceHost = "198.51.100.7"))
        assertArrayEquals(opus, v.opus)
    }

    // ---- XV native, REQUIRED receive posture ----

    @Test
    fun `required policy with a live key drops cleartext as forbidden`() {
        val tx = XvNativeWireCodec(ssrc, CryptoPolicy.CLEARTEXT, keyRegistry = null)
        tx.beginBurst()
        val datagram = tx.encodeTx(opus)!!

        val rx = XvNativeWireCodec(ssrc = 1L, cryptoPolicy = CryptoPolicy.REQUIRED, keyRegistry = registryWithKey())
        assertEquals(
            MulticastWireCodec.DropReason.CLEARTEXT_FORBIDDEN,
            dropReason(rx.decodeRx(datagram, sourceHost = "198.51.100.7")),
        )
    }

    @Test
    fun `required policy without a key reports unconverged keying not a policy violation`() {
        val tx = XvNativeWireCodec(ssrc, CryptoPolicy.CLEARTEXT, keyRegistry = null)
        tx.beginBurst()
        val datagram = tx.encodeTx(opus)!!

        val rx = XvNativeWireCodec(ssrc = 1L, cryptoPolicy = CryptoPolicy.REQUIRED, keyRegistry = null)
        assertEquals(
            MulticastWireCodec.DropReason.UNKNOWN_EPOCH,
            dropReason(rx.decodeRx(datagram, sourceHost = "198.51.100.7")),
        )
    }

    // ---- XV native, non-voice datagrams ----

    @Test
    fun `control packets surface as Control not voice`() {
        val rx = XvNativeWireCodec(ssrc, CryptoPolicy.PREFERRED, keyRegistry = null)
        val msg = ControlPacket.Message.KeyReq(channelId = 7, forEpoch = 3, requesterUid = "uid-alpha")
        val result = rx.decodeRx(ControlPacket.encode(msg), sourceHost = "198.51.100.7")
        assertTrue(result is MulticastWireCodec.RxResult.Control)
        assertEquals(msg, (result as MulticastWireCodec.RxResult.Control).message)
    }

    @Test
    fun `truncated control packet drops as malformed`() {
        val rx = XvNativeWireCodec(ssrc, CryptoPolicy.PREFERRED, keyRegistry = null)
        // XVMC magic + KeyReq type byte, body missing.
        val truncated = byteArrayOf(0x58, 0x56, 0x4D, 0x43, 0x01)
        assertEquals(
            MulticastWireCodec.DropReason.MALFORMED,
            dropReason(rx.decodeRx(truncated, sourceHost = "198.51.100.7")),
        )
    }

    @Test
    fun `empty and non-rtp datagrams drop with distinct reasons`() {
        val rx = XvNativeWireCodec(ssrc, CryptoPolicy.PREFERRED, keyRegistry = null)
        assertEquals(
            MulticastWireCodec.DropReason.EMPTY,
            dropReason(rx.decodeRx(ByteArray(0), sourceHost = "198.51.100.7")),
        )
        // Byte 0 = 0x80 claims RTP v2 but the datagram is too short for
        // an RTP header — genuine line noise, not a keying gap.
        assertEquals(
            MulticastWireCodec.DropReason.NOT_RTP,
            dropReason(rx.decodeRx(byteArrayOf(0x80.toByte(), 0x01, 0x02), sourceHost = "198.51.100.7")),
        )
    }

    @Test
    fun `keyless receiver classifies peers' encrypted frames as encrypted-no-key not line noise`() {
        // The 2026-07-16 field state: device restarted offline, lost its
        // in-memory key, peers still encrypt. Every frame was miscounted
        // as NOT_RTP and the operator saw nothing actionable.
        val key = ByteArray(AeadCodec.KEY_BYTES) { 0x42 }
        val tx = XvNativeWireCodec(ssrc, CryptoPolicy.PREFERRED, registryWithKey(epoch = 3, key = key))
        tx.beginBurst()
        val datagram = tx.encodeTx(opus)!!

        val keyless = XvNativeWireCodec(ssrc = 1L, cryptoPolicy = CryptoPolicy.PREFERRED, keyRegistry = null)
        assertEquals(
            MulticastWireCodec.DropReason.ENCRYPTED_NO_KEY,
            dropReason(keyless.decodeRx(datagram, sourceHost = "198.51.100.7")),
        )

        val emptyRegistry = XvNativeWireCodec(ssrc = 1L, cryptoPolicy = CryptoPolicy.PREFERRED, keyRegistry = ChannelKeyRegistry(7))
        assertEquals(
            MulticastWireCodec.DropReason.ENCRYPTED_NO_KEY,
            dropReason(emptyRegistry.decodeRx(datagram, sourceHost = "198.51.100.7")),
        )
    }

    @Test
    fun `keyed receiver classifies an unknown epoch as unknown-epoch not line noise`() {
        val tx = XvNativeWireCodec(ssrc, CryptoPolicy.PREFERRED, registryWithKey(epoch = 9, key = ByteArray(AeadCodec.KEY_BYTES) { 0x42 }))
        tx.beginBurst()
        val datagram = tx.encodeTx(opus)!!

        // Keyed, but for a different epoch than the frame carries.
        val rx = XvNativeWireCodec(ssrc = 1L, cryptoPolicy = CryptoPolicy.PREFERRED, keyRegistry = registryWithKey(epoch = 3))
        assertEquals(
            MulticastWireCodec.DropReason.UNKNOWN_EPOCH,
            dropReason(rx.decodeRx(datagram, sourceHost = "198.51.100.7")),
        )
    }

    @Test
    fun `rtp with a foreign payload type is dropped`() {
        val rx = XvNativeWireCodec(ssrc, CryptoPolicy.PREFERRED, keyRegistry = null)
        val datagram =
            RtpFraming.encode(
                sequenceNumber = 0,
                timestamp = 0L,
                ssrc = ssrc,
                opusPayload = opus,
                payloadType = 96,
            )
        assertEquals(
            MulticastWireCodec.DropReason.WRONG_PAYLOAD_TYPE,
            dropReason(rx.decodeRx(datagram, sourceHost = "198.51.100.7")),
        )
    }

    // ---- VX compat ----

    @Test
    fun `vx rx drops empties and stray xv control traffic`() {
        val codec = VxWireCodec(12345L)
        assertEquals(
            MulticastWireCodec.DropReason.EMPTY,
            dropReason(codec.decodeRx(ByteArray(0), sourceHost = "198.51.100.7")),
        )
        val control = ControlPacket.encode(ControlPacket.Message.CertReq(wantedCertFp = "ab".repeat(32)))
        assertEquals(
            MulticastWireCodec.DropReason.MALFORMED,
            dropReason(codec.decodeRx(control, sourceHost = "198.51.100.7")),
        )
    }

    // ---- factory ----

    @Test
    fun `wire codec factory follows the config's wire format`() {
        val native = ChannelMulticastConfig.defaultFor("ops-1").newWireCodec(ssrc, keyRegistry = null)
        assertTrue(native is XvNativeWireCodec)

        val compat =
            ChannelMulticastConfig(
                channelName = "mesh-ptt",
                wireFormat = WireFormat.VX_COMPAT,
                cryptoPolicy = CryptoPolicy.CLEARTEXT,
                pinnedGroup = "224.0.0.1",
                pinnedPort = 5007,
            ).newWireCodec(ssrc, keyRegistry = null)
        assertTrue(compat is VxWireCodec)
    }
}
