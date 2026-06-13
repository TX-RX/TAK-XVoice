package com.atakmap.android.xv.transport.multicast

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelKeyRegistryTest {
    private val plaintext = "voice frame".toByteArray()
    private val keyA = ByteArray(AeadCodec.KEY_BYTES) { it.toByte() }
    private val keyB = ByteArray(AeadCodec.KEY_BYTES) { (it * 7 + 1).toByte() }
    private val keyC = ByteArray(AeadCodec.KEY_BYTES) { (it xor 0x55).toByte() }

    @Test
    fun `registry starts empty`() {
        val r = ChannelKeyRegistry(channelId = 6)
        assertFalse(r.hasKey())
        assertEquals(ChannelKeyRegistry.NO_EPOCH, r.currentEpoch())
    }

    @Test
    fun `install accepts and remembers the current key`() {
        val r = ChannelKeyRegistry(channelId = 6)
        assertTrue(r.install(epoch = 7, key = keyA))
        assertTrue(r.hasKey())
        assertEquals(7, r.currentEpoch())
    }

    @Test
    fun `installing the same epoch is rejected`() {
        val r = ChannelKeyRegistry(channelId = 6)
        r.install(epoch = 5, key = keyA)
        assertFalse(
            "re-installing the same epoch should noop",
            r.install(epoch = 5, key = keyA),
        )
    }

    @Test
    fun `round-trip via registry encrypt + decrypt`() {
        val r = ChannelKeyRegistry(channelId = 6)
        r.install(epoch = 3, key = keyA)
        val ct = r.encrypt(plaintext)
        val pt = r.decrypt(ct)
        assertNotNull(pt)
        assertArrayEquals(plaintext, pt)
    }

    @Test
    fun `previous epoch frames decrypt during grace window`() {
        val r = ChannelKeyRegistry(channelId = 6)
        r.install(epoch = 3, key = keyA)
        val oldFrame = r.encrypt(plaintext)
        // Rotate forward: previous slot now holds keyA/epoch 3.
        r.install(epoch = 4, key = keyB)
        // The in-flight frame from before the rotation must still decrypt.
        assertArrayEquals(plaintext, r.decrypt(oldFrame))
    }

    @Test
    fun `frames older than previous epoch are silently dropped`() {
        val r = ChannelKeyRegistry(channelId = 6)
        r.install(epoch = 1, key = keyA)
        val ancient = r.encrypt(plaintext)
        r.install(epoch = 2, key = keyB)
        r.install(epoch = 3, key = keyC)
        // After two rotations, epoch 1 is too old.
        assertNull(r.decrypt(ancient))
    }

    @Test
    fun `frames from an unknown future epoch are dropped`() {
        val r = ChannelKeyRegistry(channelId = 6)
        r.install(epoch = 5, key = keyA)
        // Forge a datagram with a future epoch using a different codec.
        val future = AeadCodec(keyB, epoch = 99).encrypt(plaintext)
        assertNull(
            "future epoch frames must be dropped (key not yet installed)",
            r.decrypt(future),
        )
    }

    @Test
    fun `bad tag is reported by decryptDetailed`() {
        val r = ChannelKeyRegistry(channelId = 6)
        r.install(epoch = 0, key = keyA)
        // Forge a frame with the right epoch but wrong key.
        val forged = AeadCodec(keyB, epoch = 0).encrypt(plaintext)
        val result = r.decryptDetailed(forged)
        assertTrue(
            "expected BadTag, got $result",
            result is ChannelKeyRegistry.DecryptResult.BadTag,
        )
        val bt = result as ChannelKeyRegistry.DecryptResult.BadTag
        assertEquals(0, bt.epoch)
    }

    @Test
    fun `unknown epoch is reported distinctly from bad tag`() {
        val r = ChannelKeyRegistry(channelId = 6)
        r.install(epoch = 0, key = keyA)
        val unk = AeadCodec(keyB, epoch = 200).encrypt(plaintext)
        val result = r.decryptDetailed(unk)
        assertTrue(
            "expected UnknownEpoch, got $result",
            result is ChannelKeyRegistry.DecryptResult.UnknownEpoch,
        )
        assertEquals(200, (result as ChannelKeyRegistry.DecryptResult.UnknownEpoch).got)
    }

    @Test
    fun `malformed (empty) datagram returns null + Malformed result`() {
        val r = ChannelKeyRegistry(channelId = 6)
        r.install(epoch = 0, key = keyA)
        assertNull(r.decrypt(ByteArray(0)))
        assertEquals(
            ChannelKeyRegistry.DecryptResult.Malformed,
            r.decryptDetailed(ByteArray(0)),
        )
    }

    @Test
    fun `encrypt without a key is a programming error`() {
        val r = ChannelKeyRegistry(channelId = 6)
        assertThrows(IllegalStateException::class.java) { r.encrypt(plaintext) }
    }

    @Test
    fun `epoch byte wrap is supported (255 then 0)`() {
        val r = ChannelKeyRegistry(channelId = 6)
        r.install(epoch = 255, key = keyA)
        val frame255 = r.encrypt(plaintext)
        r.install(epoch = 0, key = keyB)
        val frame0 = r.encrypt(plaintext)
        // The frame from the wrapped-around-prior-epoch (255) still decrypts
        // during the grace window after wrap; the new (0) one decrypts as
        // current.
        assertArrayEquals(plaintext, r.decrypt(frame255))
        assertArrayEquals(plaintext, r.decrypt(frame0))
    }
}
