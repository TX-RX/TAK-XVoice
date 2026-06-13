package com.atakmap.android.xv.transport.multicast

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Per-frame AEAD layer for encrypted multicast voice. Wraps a 32-byte
 * symmetric key (the channel key for the current epoch) with
 * ChaCha20-Poly1305.
 *
 * Wire format:
 *
 *     +---------+----------------+----------------------------+
 *     | 1 byte  | 12 bytes       | N bytes                    |
 *     | epoch   | random nonce   | ChaCha20-Poly1305 ct + tag |
 *     +---------+----------------+----------------------------+
 *
 * The epoch byte is cleartext so a receiver with the wrong key for the
 * advertised epoch fast-fails without burning a Poly1305 verification.
 * Nonces are generated via [SecureRandom] (96 bits gives ~2^48 frames
 * before birthday-bound, which is ~89 years at 10ms/frame per key —
 * comfortably more than any rotation interval).
 *
 * AES-GCM was the alternative; ChaCha20-Poly1305 wins on the ARM SoCs
 * we target (no AES-NI hardware) and is available natively on Android
 * API 28+. minSdk 26 means we gate Phase 8 on `Build.VERSION.SDK_INT
 * >= 28` at the integration boundary; this codec itself compiles
 * against the JCE name "ChaCha20-Poly1305" and degrades cleanly when
 * the provider isn't installed.
 *
 * Why no `android.security.keystore`-backed key: the channel key is
 * derived/distributed peer-to-peer over multicast and rotated on
 * membership change. Storing it in the system keystore would block
 * sharing it with peers via [EMC_KEY_OFFER]. The leaf cert / private
 * key (which IS in the TAK CertificateDatabase) authenticates the key
 * exchange itself, not the per-frame codec.
 */
class AeadCodec(
    private val key: ByteArray,
    private val epoch: Int,
    private val rng: SecureRandom = SecureRandom(),
) {
    init {
        require(key.size == KEY_BYTES) {
            "ChaCha20-Poly1305 key must be $KEY_BYTES bytes, got ${key.size}"
        }
        require(epoch in 0..255) {
            "epoch must fit in one byte (0..255), got $epoch"
        }
    }

    /**
     * Encrypt one Opus payload to a self-contained datagram.
     *
     * @param plaintext the Opus-encoded voice frame.
     * @param associatedData optional context bytes that must match on
     *   decrypt. Passing the channelId or sender uid here would bind
     *   each frame to its sender, but the current call sites don't
     *   need it; default empty.
     */
    fun encrypt(
        plaintext: ByteArray,
        associatedData: ByteArray = EMPTY,
    ): ByteArray {
        val nonce = ByteArray(NONCE_BYTES).also { rng.nextBytes(it) }
        val cipher = Cipher.getInstance(CIPHER_NAME)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "ChaCha20"), IvParameterSpec(nonce))
        if (associatedData.isNotEmpty()) cipher.updateAAD(associatedData)
        val ct = cipher.doFinal(plaintext)
        val out = ByteArray(1 + NONCE_BYTES + ct.size)
        out[0] = epoch.toByte()
        System.arraycopy(nonce, 0, out, 1, NONCE_BYTES)
        System.arraycopy(ct, 0, out, 1 + NONCE_BYTES, ct.size)
        return out
    }

    /**
     * Decrypt one datagram produced by [encrypt]. Returns the original
     * plaintext, or throws if the datagram is malformed, the epoch
     * doesn't match this codec's, or the AEAD tag fails verification
     * (wrong key, replay across keys, or tampering).
     *
     * The caller decides what to do with [DecryptException.WrongEpoch]
     * — current epoch + previous epoch are both legitimate during a
     * key rotation grace window; older or future epochs are dropped.
     */
    fun decrypt(
        datagram: ByteArray,
        associatedData: ByteArray = EMPTY,
    ): ByteArray {
        if (datagram.size < HEADER_BYTES + TAG_BYTES) {
            throw DecryptException.Malformed(
                "datagram too short: ${datagram.size} < min ${HEADER_BYTES + TAG_BYTES}",
            )
        }
        val gotEpoch = datagram[0].toInt() and 0xFF
        if (gotEpoch != epoch) {
            throw DecryptException.WrongEpoch(gotEpoch, epoch)
        }
        val nonce = datagram.copyOfRange(1, 1 + NONCE_BYTES)
        val ct = datagram.copyOfRange(1 + NONCE_BYTES, datagram.size)
        val cipher = Cipher.getInstance(CIPHER_NAME)
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "ChaCha20"), IvParameterSpec(nonce))
        if (associatedData.isNotEmpty()) cipher.updateAAD(associatedData)
        return try {
            cipher.doFinal(ct)
        } catch (t: javax.crypto.AEADBadTagException) {
            throw DecryptException.BadTag(t.message ?: "AEAD tag verification failed")
        }
    }

    /**
     * Peek the epoch byte from a datagram without attempting decryption.
     * Used by the receiving side to dispatch to the correct
     * (current/previous) codec from [ChannelKeyRegistry].
     */
    sealed class DecryptException(
        message: String,
    ) : RuntimeException(message) {
        class Malformed(
            message: String,
        ) : DecryptException(message)

        class WrongEpoch(
            val got: Int,
            val expected: Int,
        ) : DecryptException("wrong epoch: got=$got expected=$expected")

        class BadTag(
            message: String,
        ) : DecryptException(message)
    }

    companion object {
        const val KEY_BYTES = 32 // ChaCha20-Poly1305 fixed
        const val NONCE_BYTES = 12 // RFC 7539 / RFC 8439
        const val TAG_BYTES = 16 // Poly1305 tag
        const val HEADER_BYTES = 1 + NONCE_BYTES // epoch + nonce, before ciphertext
        private const val CIPHER_NAME = "ChaCha20-Poly1305"
        private val EMPTY = ByteArray(0)

        /**
         * Read the cleartext epoch byte from a datagram. Caller's
         * responsibility to handle malformed input (length zero).
         */
        fun peekEpoch(datagram: ByteArray): Int {
            require(datagram.isNotEmpty()) { "empty datagram has no epoch" }
            return datagram[0].toInt() and 0xFF
        }

        /** Generate a fresh random 32-byte channel key. */
        fun generateChannelKey(rng: SecureRandom = SecureRandom()): ByteArray = ByteArray(KEY_BYTES).also { rng.nextBytes(it) }
    }
}
