package com.atakmap.android.xv.transport.multicast

import java.security.MessageDigest

/**
 * Short-authentication-string (SAS) for the peer-vouched field-
 * enrollment handshake (#95).
 *
 * When a voucher admits a newcomer to an encrypted channel, the two
 * devices run an ephemeral key exchange over the mesh and then each
 * display a short numeric code derived from BOTH ephemeral public keys.
 * The operators compare the codes ("does yours say 418-207?"); a match
 * proves there is no man-in-the-middle on the exchange, exactly as in
 * ZRTP / Bluetooth numeric comparison.
 *
 * Security model (numeric comparison): a MITM sitting between A and B
 * runs two separate exchanges — (A ↔ M) and (M ↔ B) — so A computes the
 * SAS over (pubA, pubM) while B computes it over (pubM', pubB). The
 * attacker cannot force both codes equal without a second-preimage on
 * the truncated SHA-256, so the humans see two different numbers and
 * abort. Guessing a single collision is a [10^digits] shot per attempt,
 * and a mismatch is visible immediately — there is no offline grinding.
 * The code therefore authenticates the exchange as long as the public
 * keys shown are the ones actually used in the key agreement.
 *
 * The derivation is deterministic and role-symmetric: the two public
 * keys are ordered before hashing, so the voucher and the newcomer
 * compute the same digits without agreeing on who is "A". It depends on
 * nothing but [MessageDigest] (SHA-256), so it is a pure, testable
 * primitive independent of whichever library ultimately provides the
 * ephemeral keypair.
 */
object ShortAuthString {
    /** Default SAS length. Six digits ⇒ a 1-in-1,000,000 forgery shot,
     *  short enough to read aloud over the voice channel. */
    const val DEFAULT_DIGITS: Int = 6

    /**
     * Derive the numeric SAS for a pairing from both parties' ephemeral
     * public keys. Order-independent: `derive(a, b) == derive(b, a)`.
     *
     * @param pubKeyA one party's ephemeral public key bytes.
     * @param pubKeyB the other party's ephemeral public key bytes.
     * @param digits SAS length in decimal digits (1..9).
     * @return a zero-padded decimal string of length [digits].
     */
    fun derive(
        pubKeyA: ByteArray,
        pubKeyB: ByteArray,
        digits: Int = DEFAULT_DIGITS,
    ): String {
        require(digits in 1..9) { "digits must be 1..9, got $digits" }
        require(pubKeyA.isNotEmpty() && pubKeyB.isNotEmpty()) { "public keys must be non-empty" }

        // Order the two keys so both sides hash the same transcript
        // regardless of who plays which role.
        val (lo, hi) = if (compare(pubKeyA, pubKeyB) <= 0) pubKeyA to pubKeyB else pubKeyB to pubKeyA

        val md = MessageDigest.getInstance("SHA-256")
        md.update(DOMAIN)
        md.update(lo)
        md.update(SEPARATOR)
        md.update(hi)
        val digest = md.digest()

        // Fold the leading 8 digest bytes into a non-negative long, then
        // take it mod 10^digits. Eight bytes is ample entropy for a
        // ≤9-digit code and keeps the reduction bias far below the
        // 1-in-10^digits guess probability the model already accepts.
        var acc = 0L
        for (i in 0 until 8) {
            acc = (acc shl 8) or (digest[i].toLong() and 0xFF)
        }
        acc = acc and Long.MAX_VALUE // clear sign so the modulo stays non-negative
        var modulus = 1L
        repeat(digits) { modulus *= 10 }
        val value = acc % modulus
        return value.toString().padStart(digits, '0')
    }

    private val DOMAIN = "xv-sas-v1".toByteArray(Charsets.UTF_8)
    private val SEPARATOR = byteArrayOf(0x1F) // unit separator, unambiguous vs key bytes

    /** Lexicographic unsigned-byte compare. */
    private fun compare(
        a: ByteArray,
        b: ByteArray,
    ): Int {
        val n = minOf(a.size, b.size)
        for (i in 0 until n) {
            val d = (a[i].toInt() and 0xFF) - (b[i].toInt() and 0xFF)
            if (d != 0) return d
        }
        return a.size - b.size
    }
}
