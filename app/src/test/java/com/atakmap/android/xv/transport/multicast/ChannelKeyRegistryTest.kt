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

    // ---- key-age ceiling (#92 / #95: 5-day lifecycle) ----

    private val fiveDaysMs = 5L * 24 * 60 * 60 * 1000

    @Test
    fun `untracked install is never expired`() {
        val r = ChannelKeyRegistry(channelId = 6)
        r.install(epoch = 1, key = keyA) // no installedAtMs
        assertEquals(ChannelKeyRegistry.UNTRACKED_INSTALL, r.currentKeyInstalledAtMs())
        assertFalse(
            "an age-untracked key must never force a rotation",
            r.isCurrentKeyExpired(nowMs = Long.MAX_VALUE / 2, maxAgeMs = fiveDaysMs),
        )
    }

    @Test
    fun `keyless registry is never expired`() {
        val r = ChannelKeyRegistry(channelId = 6)
        assertFalse(r.isCurrentKeyExpired(nowMs = 10_000, maxAgeMs = fiveDaysMs))
    }

    @Test
    fun `tracked key is not expired before the ceiling and is at or after it`() {
        val r = ChannelKeyRegistry(channelId = 6)
        val t0 = 1_000_000L
        r.install(epoch = 1, key = keyA, installedAtMs = t0)
        assertEquals(t0, r.currentKeyInstalledAtMs())
        assertFalse(r.isCurrentKeyExpired(nowMs = t0 + fiveDaysMs - 1, maxAgeMs = fiveDaysMs))
        assertTrue(r.isCurrentKeyExpired(nowMs = t0 + fiveDaysMs, maxAgeMs = fiveDaysMs))
        assertTrue(r.isCurrentKeyExpired(nowMs = t0 + fiveDaysMs + 1, maxAgeMs = fiveDaysMs))
    }

    @Test
    fun `backward clock jump does not expire a live key`() {
        val r = ChannelKeyRegistry(channelId = 6)
        val t0 = 5_000_000L
        r.install(epoch = 1, key = keyA, installedAtMs = t0)
        assertFalse(
            "a wall-clock step backwards must not instantly expire the key",
            r.isCurrentKeyExpired(nowMs = t0 - 100_000, maxAgeMs = fiveDaysMs),
        )
    }

    @Test
    fun `rotation refreshes the install time`() {
        val r = ChannelKeyRegistry(channelId = 6)
        r.install(epoch = 1, key = keyA, installedAtMs = 1_000L)
        r.install(epoch = 2, key = keyB, installedAtMs = 2_000L)
        assertEquals(2_000L, r.currentKeyInstalledAtMs())
    }

    // ---- hard revoke ----

    @Test
    fun `dropPrevious hard-revokes the old epoch immediately`() {
        val r = ChannelKeyRegistry(channelId = 6)
        r.install(epoch = 3, key = keyA)
        val oldFrame = r.encrypt(plaintext)
        r.install(epoch = 4, key = keyB)
        // Soft grace: the old frame still decrypts...
        assertArrayEquals(plaintext, r.decrypt(oldFrame))
        // ...until we hard-revoke, after which the burned epoch is gone.
        r.dropPrevious()
        assertNull(
            "hard revoke must stop the previous epoch decrypting",
            r.decrypt(oldFrame),
        )
        // Current key is untouched.
        assertArrayEquals(plaintext, r.decrypt(r.encrypt(plaintext)))
    }

    @Test
    fun `dropPrevious on a fresh registry is a safe no-op`() {
        val r = ChannelKeyRegistry(channelId = 6)
        r.install(epoch = 1, key = keyA)
        r.dropPrevious()
        assertArrayEquals(plaintext, r.decrypt(r.encrypt(plaintext)))
    }

    @Test
    fun `backward epoch is rejected by the forward-only guard`() {
        val r = ChannelKeyRegistry(channelId = 6)
        r.install(epoch = 10, key = keyA)
        // Epoch 9 is behind 10 — must be rejected.
        assertFalse(
            "epoch behind current must be rejected",
            r.install(epoch = 9, key = keyB),
        )
        // Epoch 10 still current.
        assertEquals(10, r.currentEpoch())
    }

    @Test
    fun `epoch 128 steps ahead is treated as backward (replay guard)`() {
        val r = ChannelKeyRegistry(channelId = 6)
        r.install(epoch = 0, key = keyA)
        // Delta = 128 — ambiguous; the guard rejects as a replay.
        assertFalse(
            "epoch 128 steps ahead must be rejected as possible replay",
            r.install(epoch = 128, key = keyB),
        )
        assertEquals(0, r.currentEpoch())
    }

    @Test
    fun `first install always accepted regardless of epoch value`() {
        val r = ChannelKeyRegistry(channelId = 6)
        // No currentEpoch yet — any epoch must be accepted.
        assertTrue(r.install(epoch = 200, key = keyA))
        assertEquals(200, r.currentEpoch())
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
