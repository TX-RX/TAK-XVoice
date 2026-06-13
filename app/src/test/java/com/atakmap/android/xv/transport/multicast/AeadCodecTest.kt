package com.atakmap.android.xv.transport.multicast

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AeadCodecTest {
    private val key1: ByteArray = AeadCodec.generateChannelKey()
    private val key2: ByteArray = AeadCodec.generateChannelKey()
    private val plaintext = "the quick brown fox jumps over the lazy dog".toByteArray()

    @Test
    fun `round-trip with the right key + epoch returns the original payload`() {
        val codec = AeadCodec(key1, epoch = 7)
        val ct = codec.encrypt(plaintext)
        val got = codec.decrypt(ct)
        assertArrayEquals(plaintext, got)
    }

    @Test
    fun `round-trip preserves binary payloads with embedded nulls`() {
        val codec = AeadCodec(key1, epoch = 0)
        val binary = byteArrayOf(0, 1, 2, 0, 0, 3, -1, -128, 127)
        val ct = codec.encrypt(binary)
        assertArrayEquals(binary, codec.decrypt(ct))
    }

    @Test
    fun `wrong key fails AEAD tag verification`() {
        val sender = AeadCodec(key1, epoch = 5)
        val attacker = AeadCodec(key2, epoch = 5) // same epoch, different key
        val ct = sender.encrypt(plaintext)
        assertThrows(AeadCodec.DecryptException.BadTag::class.java) {
            attacker.decrypt(ct)
        }
    }

    @Test
    fun `wrong epoch is rejected before tag verification`() {
        val sender = AeadCodec(key1, epoch = 5)
        val receiver = AeadCodec(key1, epoch = 6) // same key, advanced epoch
        val ct = sender.encrypt(plaintext)
        val ex =
            assertThrows(AeadCodec.DecryptException.WrongEpoch::class.java) {
                receiver.decrypt(ct)
            }
        assertEquals(5, ex.got)
        assertEquals(6, ex.expected)
    }

    @Test
    fun `tampered ciphertext fails tag verification`() {
        val codec = AeadCodec(key1, epoch = 1)
        val ct = codec.encrypt(plaintext)
        // Flip a bit in the ciphertext (after the cleartext header).
        ct[AeadCodec.HEADER_BYTES + 2] = (ct[AeadCodec.HEADER_BYTES + 2].toInt() xor 0x01).toByte()
        assertThrows(AeadCodec.DecryptException.BadTag::class.java) {
            codec.decrypt(ct)
        }
    }

    @Test
    fun `tampered nonce changes derivation and fails tag verification`() {
        val codec = AeadCodec(key1, epoch = 1)
        val ct = codec.encrypt(plaintext)
        // Flip a bit in the nonce (header byte 1).
        ct[1] = (ct[1].toInt() xor 0x80).toByte()
        assertThrows(AeadCodec.DecryptException.BadTag::class.java) {
            codec.decrypt(ct)
        }
    }

    @Test
    fun `nonces are unique across encryptions even for identical plaintext`() {
        val codec = AeadCodec(key1, epoch = 0)
        // Statistical: 1000 encryptions should never collide on the 96-bit
        // nonce (birthday is at sqrt(2^96) ~= 2^48).
        val seen = HashSet<List<Byte>>()
        repeat(1000) {
            val ct = codec.encrypt(plaintext)
            val nonce = ct.copyOfRange(1, 1 + AeadCodec.NONCE_BYTES).toList()
            assertTrue("nonce reuse at iteration $it", seen.add(nonce))
        }
    }

    @Test
    fun `ciphertext differs across encryptions of the same plaintext`() {
        val codec = AeadCodec(key1, epoch = 0)
        val a = codec.encrypt(plaintext)
        val b = codec.encrypt(plaintext)
        assertNotEquals(
            "two encryptions of the same plaintext under random nonce must differ",
            a.toList(),
            b.toList(),
        )
    }

    @Test
    fun `malformed (too short) datagrams throw Malformed`() {
        val codec = AeadCodec(key1, epoch = 0)
        assertThrows(AeadCodec.DecryptException.Malformed::class.java) {
            codec.decrypt(ByteArray(5)) // less than HEADER + TAG
        }
    }

    @Test
    fun `peekEpoch reads the first byte without attempting decryption`() {
        val codec = AeadCodec(key1, epoch = 42)
        val ct = codec.encrypt(plaintext)
        assertEquals(42, AeadCodec.peekEpoch(ct))
    }

    @Test
    fun `key length other than 32 bytes is rejected at construction`() {
        assertThrows(IllegalArgumentException::class.java) {
            AeadCodec(ByteArray(16), epoch = 0)
        }
    }

    @Test
    fun `epoch outside 0_255 is rejected at construction`() {
        assertThrows(IllegalArgumentException::class.java) {
            AeadCodec(key1, epoch = 256)
        }
        assertThrows(IllegalArgumentException::class.java) {
            AeadCodec(key1, epoch = -1)
        }
    }

    @Test
    fun `associated data is bound to the ciphertext`() {
        val codec = AeadCodec(key1, epoch = 3)
        val ad1 = "channel-6".toByteArray()
        val ad2 = "channel-7".toByteArray()
        val ct = codec.encrypt(plaintext, associatedData = ad1)
        assertArrayEquals(plaintext, codec.decrypt(ct, associatedData = ad1))
        assertThrows(AeadCodec.DecryptException.BadTag::class.java) {
            codec.decrypt(ct, associatedData = ad2)
        }
    }

    @Test
    fun `generateChannelKey returns 32 random bytes`() {
        val k = AeadCodec.generateChannelKey()
        assertEquals(AeadCodec.KEY_BYTES, k.size)
        // Two successive generations should not collide.
        val k2 = AeadCodec.generateChannelKey()
        assertNotEquals(k.toList(), k2.toList())
    }
}
