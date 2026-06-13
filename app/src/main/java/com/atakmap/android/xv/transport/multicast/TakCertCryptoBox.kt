package com.atakmap.android.xv.transport.multicast

import java.security.PrivateKey
import java.security.PublicKey
import java.security.cert.X509Certificate
import javax.crypto.Cipher
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource

/**
 * RSA-OAEP-SHA256 wrap/unwrap of a 32-byte channel key against a peer's
 * TAK enrollment cert. The channel key is the symmetric secret used by
 * [AeadCodec] for per-frame voice encryption; this layer is the
 * envelope that gets it from the key generator to each authorized
 * recipient over multicast (in `EMC_KEY_OFFER` datagrams).
 *
 * Why RSA-OAEP, not ECDH:
 *   - The TAK CertificateDatabase already provisions an RSA leaf cert
 *     per device. Adding ECDH would require either provisioning a
 *     second key (against the "permissions mirror Mumble" goal) or
 *     deriving an ephemeral one — losing the cert-pinned identity
 *     binding that makes recipients verifiable.
 *   - RSA-OAEP at the channel-key delivery rate (handful of wraps per
 *     join, plus on rotation) is well within budget. The voice path
 *     itself is symmetric ChaCha20-Poly1305 — no per-frame RSA.
 *
 * SHA-256 (not SHA-1) for both the OAEP digest and the MGF1 hash.
 * Older OAEP libraries default to SHA-1 for one or the other; we pin
 * both explicitly so peers don't disagree on parameters and silently
 * fail to unwrap.
 */
object TakCertCryptoBox {
    private const val TRANSFORMATION = "RSA/ECB/OAEPWithSHA-256AndMGF1Padding"

    /**
     * Wrap [channelKey] (must be 32 bytes — the channel symmetric key)
     * for delivery to the holder of [recipientCert]'s private key.
     */
    fun wrapChannelKey(
        channelKey: ByteArray,
        recipientCert: X509Certificate,
    ): ByteArray {
        require(channelKey.size == AeadCodec.KEY_BYTES) {
            "channelKey must be ${AeadCodec.KEY_BYTES} bytes, got ${channelKey.size}"
        }
        return wrap(channelKey, recipientCert.publicKey)
    }

    /** Internal wrap; exposed for test injection of a non-cert PublicKey. */
    internal fun wrap(
        plaintext: ByteArray,
        publicKey: PublicKey,
    ): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, publicKey, oaepSpec())
        return cipher.doFinal(plaintext)
    }

    /**
     * Reverse of [wrapChannelKey]. Returns the 32-byte channel key.
     * Throws on tampering, key mismatch, or malformed input.
     */
    fun unwrapChannelKey(
        wrapped: ByteArray,
        ourPrivateKey: PrivateKey,
    ): ByteArray {
        val out = unwrap(wrapped, ourPrivateKey)
        require(out.size == AeadCodec.KEY_BYTES) {
            "unwrapped key length ${out.size} != expected ${AeadCodec.KEY_BYTES}"
        }
        return out
    }

    /** Internal unwrap; exposed for tests. */
    internal fun unwrap(
        wrapped: ByteArray,
        privateKey: PrivateKey,
    ): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, privateKey, oaepSpec())
        return cipher.doFinal(wrapped)
    }

    private fun oaepSpec(): OAEPParameterSpec =
        OAEPParameterSpec(
            "SHA-256",
            "MGF1",
            java.security.spec.MGF1ParameterSpec.SHA256,
            PSource.PSpecified.DEFAULT,
        )
}
