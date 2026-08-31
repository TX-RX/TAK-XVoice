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
 * Thread-safety: all public methods are `@Synchronized`. The UDP receive
 * thread (decrypting at ~50/sec) and the key-election tick thread both
 * call into this class and they are NOT the same thread — without
 * synchronisation the `currentKey`/`currentEpoch` field writes by the
 * election thread are a JMM data race vs. the reads in [decrypt].
 * `@Synchronized` on the individual methods is the simplest correct fix
 * (the critical sections are microseconds; contention cost is negligible
 * vs. per-frame Opus decode time).
 *
 * Caller is NOT responsible for serializing access; the registry is
 * thread-safe internally.
 */
class ChannelKeyRegistry(
    private val channelId: Int,
) {
    /** Epoch 0..255; -1 means "no key yet". */
    private var currentEpoch: Int = NO_EPOCH
    private var currentKey: ByteArray? = null

    private var previousEpoch: Int = NO_EPOCH
    private var previousKey: ByteArray? = null

    // Wall-clock (caller-supplied) install time of the current key, used
    // for the 5-day key-lifecycle ceiling (#92 / #95). UNTRACKED_INSTALL
    // means the caller installed without a timestamp — treated as "age
    // unknown", which never counts as expired so a legacy/test install
    // can't trigger a surprise rotation.
    private var currentInstalledAtMs: Long = UNTRACKED_INSTALL

    /**
     * Install a fresh key + epoch. The prior current is rolled to the
     * previous slot (keeping in-flight frames decryptable for the grace
     * window).
     *
     * Q3 — forward-only guard: only epochs that are *ahead* of
     * [currentEpoch] in mod-256 arithmetic are accepted. "Ahead" means
     * the candidate is 1..127 steps forward (wrapping). This rejects
     * replayed or stale KEY_OFFER datagrams that arrived after a newer
     * epoch was already installed, preventing an attacker or a slow
     * peer from rolling the active key backwards.
     *
     * @param installedAtMs caller's wall-clock at install, fed into the
     *   key-age ceiling ([isCurrentKeyExpired]). Defaults to
     *   [UNTRACKED_INSTALL] so callers that don't care about lifecycle
     *   (tests, bootstrap paths) keep the old 2-arg call.
     * @return true if the key was installed; false if [epoch] equals
     *   the existing current epoch (caller already has it) or if
     *   [epoch] is behind [currentEpoch] in the mod-256 ordering.
     */
    @Synchronized
    fun install(
        epoch: Int,
        key: ByteArray,
        installedAtMs: Long = UNTRACKED_INSTALL,
    ): Boolean {
        require(epoch in 0..255) { "epoch must be 0..255, got $epoch" }
        require(key.size == AeadCodec.KEY_BYTES) {
            "key must be ${AeadCodec.KEY_BYTES} bytes, got ${key.size}"
        }
        if (epoch == currentEpoch) return false
        // Mod-256 forward check. When no key is installed yet (currentEpoch
        // == NO_EPOCH = -1) we accept any first epoch unconditionally.
        if (currentEpoch != NO_EPOCH && !isForwardEpoch(currentEpoch, epoch)) return false
        previousEpoch = currentEpoch
        previousKey = currentKey
        currentEpoch = epoch
        currentKey = key
        currentInstalledAtMs = installedAtMs
        return true
    }

    /** Wall-clock this channel's current key was installed, or
     *  [UNTRACKED_INSTALL] when it was installed without a timestamp. */
    fun currentKeyInstalledAtMs(): Long = currentInstalledAtMs

    /**
     * True when the current key is older than [maxAgeMs]. The basis for
     * the 5-day secret-lifetime ceiling: at expiry the mesh manager
     * force-rotates so no channel key outlives the policy window.
     *
     * Returns false when there is no key, when the install was
     * [UNTRACKED_INSTALL] (age unknown — never force a rotation off a
     * value we don't have), or when a clock jump made the install look
     * like the future (defensive: a backward wall-clock step must not
     * instantly expire a live key).
     */
    fun isCurrentKeyExpired(
        nowMs: Long,
        maxAgeMs: Long,
    ): Boolean {
        if (currentKey == null) return false
        if (currentInstalledAtMs == UNTRACKED_INSTALL) return false
        val ageMs = nowMs - currentInstalledAtMs
        if (ageMs < 0) return false
        return ageMs >= maxAgeMs
    }

    /**
     * Drop the previous-epoch grace slot immediately. Normal rotation
     * keeps the old key decryptable for a grace window so nobody clips
     * mid-burst; a *hard* revoke (confirmed compromise) cannot afford
     * that — a device holding the burned key must stop being able to
     * decrypt the instant we rotate. Brief audio loss for a lagging
     * peer beats a live compromised key. No-op when there is no
     * previous slot.
     */
    fun dropPrevious() {
        previousEpoch = NO_EPOCH
        previousKey = null
    }

    /** True iff at least one key has been installed. */
    @Synchronized
    fun hasKey(): Boolean = currentKey != null

    /** The current key's epoch, or -1 if no key is installed yet. */
    @Synchronized
    fun currentEpoch(): Int = currentEpoch

    /**
     * Encrypt a frame under the current key. Throws if no key is yet
     * installed — caller should have checked [hasKey] (we don't
     * silently drop because the call site can't recover useful
     * information from a no-op encrypt).
     */
    @Synchronized
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
    @Synchronized
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
    @Synchronized
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

    /**
     * Returns true when [candidate] is strictly ahead of [current] in
     * mod-256 space — i.e., 1..127 steps forward (wrapping from 255 to
     * 0 counts as +1). Values 128..255 steps ahead are treated as
     * backward (they're more likely a replay than a real 128+ rotation).
     */
    private fun isForwardEpoch(
        current: Int,
        candidate: Int,
    ): Boolean {
        val delta = (candidate - current + 256) and 0xFF
        return delta in 1..127
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

        /** Sentinel install time meaning "age not tracked for this key". */
        const val UNTRACKED_INSTALL: Long = Long.MIN_VALUE
    }
}
