package com.atakmap.android.xv.transport.multicast

/**
 * Per-channel symmetric-key store with epoch tolerance. Holds the
 * current key + the previous one so frames in flight at rotation time
 * still decrypt within a grace window.
 *
 * Epoch arithmetic is mod-256 because the epoch byte on the wire is one
 * byte (see [AeadCodec] format). Wrap-around: bumping epoch 255 by one
 * lands on 0; the registry treats that as a valid forward step and
 * still treats 255 as the "previous" epoch for one rotation cycle.
 *
 * Failure semantics for [decrypt]:
 *   - Wrong epoch (older than previous, or impossibly future) → silent
 *     drop (returns null). Drop is silent because in steady state we
 *     expect occasional out-of-order frames from a peer that hasn't
 *     yet seen our rotation; logging would spam.
 *   - Wrong key for the advertised epoch → returns null and a log
 *     emission caller-side; this is a real anomaly worth surfacing.
 *
 * Caller is responsible for serializing access; the registry is not
 * thread-safe internally because the multicast RX thread is the only
 * decrypt site and the key-rotation flow is single-thread.
 */
class ChannelKeyRegistry(
    private val channelId: Int,
) {
    /** Epoch 0..255; -1 means "no key yet". */
    private var currentEpoch: Int = NO_EPOCH
    private var currentKey: ByteArray? = null

    private var previousEpoch: Int = NO_EPOCH
    private var previousKey: ByteArray? = null

    /**
     * Install a fresh key + epoch. The prior current is rolled to the
     * previous slot (keeping in-flight frames decryptable for the grace
     * window). Caller's responsibility to ensure [epoch] is the
     * intended forward step (mod 256); the registry does not validate
     * monotonicity beyond rejecting an exact resubmission of the
     * already-current epoch (returns false in that case).
     *
     * @return true if the key was installed; false if [epoch] equals
     *   the existing current epoch (caller already has it).
     */
    fun install(
        epoch: Int,
        key: ByteArray,
    ): Boolean {
        require(epoch in 0..255) { "epoch must be 0..255, got $epoch" }
        require(key.size == AeadCodec.KEY_BYTES) {
            "key must be ${AeadCodec.KEY_BYTES} bytes, got ${key.size}"
        }
        if (epoch == currentEpoch) return false
        previousEpoch = currentEpoch
        previousKey = currentKey
        currentEpoch = epoch
        currentKey = key
        return true
    }

    /** True iff at least one key has been installed. */
    fun hasKey(): Boolean = currentKey != null

    /** The current key's epoch, or -1 if no key is installed yet. */
    fun currentEpoch(): Int = currentEpoch

    /**
     * Encrypt a frame under the current key. Throws if no key is yet
     * installed — caller should have checked [hasKey] (we don't
     * silently drop because the call site can't recover useful
     * information from a no-op encrypt).
     */
    fun encrypt(plaintext: ByteArray): ByteArray {
        val key = currentKey ?: error("no key installed for channel $channelId")
        return AeadCodec(key, currentEpoch).encrypt(plaintext)
    }

    /**
     * Try to decrypt [datagram] using whichever stored key matches its
     * cleartext epoch. Returns the plaintext on success, or null on:
     *   - empty/malformed datagram
     *   - epoch we don't have a key for
     *   - bad AEAD tag (wrong key for that epoch).
     */
    fun decrypt(datagram: ByteArray): ByteArray? {
        if (datagram.isEmpty()) return null
        val gotEpoch = AeadCodec.peekEpoch(datagram)
        val keyForEpoch = keyFor(gotEpoch) ?: return null
        return try {
            AeadCodec(keyForEpoch, gotEpoch).decrypt(datagram)
        } catch (_: AeadCodec.DecryptException) {
            null
        }
    }

    /**
     * Same as [decrypt] but reports *why* the call failed. Useful for
     * the integration layer to distinguish "we'll never decrypt this,
     * drop quietly" (UnknownEpoch) from "key mismatch, this is bad"
     * (BadTag).
     */
    fun decryptDetailed(datagram: ByteArray): DecryptResult {
        if (datagram.isEmpty()) return DecryptResult.Malformed
        val gotEpoch = AeadCodec.peekEpoch(datagram)
        val keyForEpoch = keyFor(gotEpoch) ?: return DecryptResult.UnknownEpoch(gotEpoch)
        return try {
            DecryptResult.Ok(AeadCodec(keyForEpoch, gotEpoch).decrypt(datagram), gotEpoch)
        } catch (e: AeadCodec.DecryptException.BadTag) {
            DecryptResult.BadTag(gotEpoch, e.message ?: "")
        } catch (e: AeadCodec.DecryptException) {
            // Defensive: peek matched but Cipher rejected the structure.
            DecryptResult.Malformed
        }
    }

    private fun keyFor(epoch: Int): ByteArray? =
        when (epoch) {
            currentEpoch -> currentKey
            previousEpoch -> previousKey
            else -> null
        }

    sealed class DecryptResult {
        data class Ok(
            val plaintext: ByteArray,
            val epoch: Int,
        ) : DecryptResult()

        data class UnknownEpoch(
            val got: Int,
        ) : DecryptResult()

        data class BadTag(
            val epoch: Int,
            val message: String,
        ) : DecryptResult()

        data object Malformed : DecryptResult()
    }

    companion object {
        const val NO_EPOCH: Int = -1
    }
}
