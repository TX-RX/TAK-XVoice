package com.atakmap.android.xv.transport.multicast

import java.security.KeyPair
import java.security.KeyPairGenerator
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class TakCertCryptoBoxTest {
    private val rsa: KeyPair by lazy {
        KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    }
    private val attacker: KeyPair by lazy {
        KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    }

    @Test
    fun `wrap then unwrap recovers the channel key`() {
        val key = AeadCodec.generateChannelKey()
        val wrapped = TakCertCryptoBox.wrap(key, rsa.public)
        val recovered = TakCertCryptoBox.unwrap(wrapped, rsa.private)
        assertArrayEquals(key, recovered)
    }

    @Test
    fun `wrap output is non-deterministic (OAEP randomness)`() {
        val key = AeadCodec.generateChannelKey()
        val a = TakCertCryptoBox.wrap(key, rsa.public)
        val b = TakCertCryptoBox.wrap(key, rsa.public)
        assertNotEquals(
            "OAEP wrap with the same key + plaintext must produce different ciphertexts",
            a.toList(),
            b.toList(),
        )
    }

    @Test
    fun `unwrap with the wrong private key fails`() {
        val key = AeadCodec.generateChannelKey()
        val wrapped = TakCertCryptoBox.wrap(key, rsa.public)
        assertThrows(Exception::class.java) {
            TakCertCryptoBox.unwrap(wrapped, attacker.private)
        }
    }

    @Test
    fun `unwrapChannelKey rejects payloads that are not 32 bytes`() {
        // Wrap a 16-byte payload via the internal wrap (bypassing the
        // length precondition on wrapChannelKey), then assert
        // unwrapChannelKey's length check fires.
        val wrapped = TakCertCryptoBox.wrap(ByteArray(16) { it.toByte() }, rsa.public)
        try {
            TakCertCryptoBox.unwrapChannelKey(wrapped, rsa.private)
            org.junit.Assert.fail("expected IllegalArgumentException for length mismatch")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }
}
