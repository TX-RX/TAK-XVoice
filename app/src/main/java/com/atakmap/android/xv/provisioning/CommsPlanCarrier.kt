package com.atakmap.android.xv.provisioning

import com.atakmap.android.xv.transport.multicast.AeadCodec
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Carrier encodings for [CommsPlan] bundles — the text forms that ride
 * inside a QR code, an NFC NDEF record, an ATAK data package file, or
 * a hand-typed passphrase exchange. All carriers wrap the SAME
 * canonical bytes ([CommsPlan.toCanonicalBytes]); this object is the
 * single encode/decode point so there is exactly one ingest path.
 *
 * Two text forms:
 *
 *   `XVCP1:<base64url(canonical json)>`
 *     Cleartext carrier. For plans WITHOUT pre-shared keys — channel
 *     names + endpoints only. QR and NFC default to this when the
 *     plan carries no secrets.
 *
 *   `XVCPP1:<base64url(salt || aead)>`
 *     Passphrase-locked carrier. PBKDF2-HMAC-SHA256 (16-byte random
 *     salt, [PBKDF2_ITERATIONS] rounds) derives a 32-byte key; the
 *     canonical bytes are then sealed with the same ChaCha20-Poly1305
 *     framing as voice ([AeadCodec], epoch 0). REQUIRED whenever the
 *     plan carries any PSK — a comms plan with keys IS a credential
 *     and never travels in the clear.
 *
 * Version discipline mirrors [CommsPlan.SCHEMA_VERSION]: unknown
 * prefixes fail loudly at import rather than half-parsing.
 */
object CommsPlanCarrier {
    const val CLEAR_PREFIX = "XVCP1:"
    const val LOCKED_PREFIX = "XVCPP1:"

    /** MIME type for NFC NDEF + data-package entries. */
    const val MIME_TYPE = "application/vnd.tak-xvoice.commsplan"

    /** File extension for the ATAK data-package carrier. */
    const val FILE_EXTENSION = ".xvplan"

    const val PBKDF2_ITERATIONS = 200_000
    private const val SALT_BYTES = 16

    /**
     * Cleartext text form. Refused for plans carrying pre-shared keys
     * — use [encodeLocked] for those.
     */
    fun encodeClear(plan: CommsPlan): String {
        require(plan.channels.none { it.preSharedKey != null }) {
            "plan carries pre-shared keys; use the passphrase-locked carrier"
        }
        return CLEAR_PREFIX + base64UrlEncode(plan.toCanonicalBytes())
    }

    /** Passphrase-locked text form. Works for any plan. */
    fun encodeLocked(
        plan: CommsPlan,
        passphrase: CharArray,
        rng: SecureRandom = SecureRandom(),
    ): String {
        require(passphrase.isNotEmpty()) { "passphrase must not be empty" }
        val salt = ByteArray(SALT_BYTES).also { rng.nextBytes(it) }
        val key = deriveKey(passphrase, salt)
        val sealed = AeadCodec(key, epoch = 0, rng = rng).encrypt(plan.toCanonicalBytes())
        return LOCKED_PREFIX + base64UrlEncode(salt + sealed)
    }

    /** True when [text] looks like any XV comms-plan carrier form. */
    fun isCarrierText(text: String): Boolean {
        val t = text.trim()
        return t.startsWith(CLEAR_PREFIX) || t.startsWith(LOCKED_PREFIX)
    }

    /** True when [text] is the passphrase-locked form (UI prompts for one). */
    fun needsPassphrase(text: String): Boolean = text.trim().startsWith(LOCKED_PREFIX)

    /**
     * Decode either carrier form. Throws [IllegalArgumentException]
     * with an operator-readable message on unknown prefix, transport
     * corruption, wrong passphrase, or schema mismatch — imports are
     * explicit UI actions and must fail loudly.
     */
    fun decode(
        text: String,
        passphrase: CharArray? = null,
    ): CommsPlan {
        val t = text.trim()
        return when {
            t.startsWith(CLEAR_PREFIX) ->
                CommsPlan.fromJson(String(base64UrlDecode(t.removePrefix(CLEAR_PREFIX)), Charsets.UTF_8))
            t.startsWith(LOCKED_PREFIX) -> {
                requireNotNull(passphrase) { "this comms plan is passphrase-locked" }
                val blob = base64UrlDecode(t.removePrefix(LOCKED_PREFIX))
                require(blob.size > SALT_BYTES + AeadCodec.HEADER_BYTES + AeadCodec.TAG_BYTES) {
                    "comms plan payload is truncated"
                }
                val salt = blob.copyOfRange(0, SALT_BYTES)
                val sealed = blob.copyOfRange(SALT_BYTES, blob.size)
                val key = deriveKey(passphrase, salt)
                val canonical =
                    try {
                        AeadCodec(key, epoch = 0).decrypt(sealed)
                    } catch (_: AeadCodec.DecryptException) {
                        throw IllegalArgumentException("wrong passphrase (or corrupted comms plan)")
                    }
                CommsPlan.fromJson(String(canonical, Charsets.UTF_8))
            }
            else -> throw IllegalArgumentException("not an XV comms plan (unknown prefix)")
        }
    }

    private fun deriveKey(
        passphrase: CharArray,
        salt: ByteArray,
    ): ByteArray {
        val spec = PBEKeySpec(passphrase, salt, PBKDF2_ITERATIONS, AeadCodec.KEY_BYTES * 8)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    private fun base64UrlEncode(bytes: ByteArray): String =
        java.util.Base64
            .getUrlEncoder()
            .withoutPadding()
            .encodeToString(bytes)

    private fun base64UrlDecode(text: String): ByteArray =
        try {
            java.util.Base64.getUrlDecoder().decode(text)
        } catch (_: IllegalArgumentException) {
            throw IllegalArgumentException("comms plan payload is not valid base64url")
        }
}
