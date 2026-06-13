package com.atakmap.android.xv.transport.multicast

/**
 * Distributed key-agreement state machine for one channel. The wire
 * layer (sending/receiving multicast UDP) lives elsewhere; this is the
 * decision logic — given what peers we've heard from and what epochs
 * they advertise, what should we do on the next tick?
 *
 * Convergence model (per the Phase 8 plan):
 *   - On every tick, look at the highest epoch any peer in our channel
 *     is advertising. If we don't have it, ask for it (KEY_REQ).
 *   - If we ARE the lowest-uid peer who has the highest epoch, we
 *     answer KEY_REQ from peers that are stuck at lower epochs.
 *   - When a key holder leaves, the surviving lowest-uid peer rotates
 *     and broadcasts a KEY_OFFER bumping the epoch by one.
 *
 * Why "lowest uid wins": gives a total order on responders without an
 * explicit election. Two peers joining simultaneously may both see a
 * gap and respond, but their replies are idempotent (carry the same
 * key + epoch), so the worst case is a couple of duplicate datagrams,
 * not a split-brain key.
 *
 * NOT covered here: actually wrapping/unwrapping the OAEP envelopes,
 * the multicast IO, or the cert exchange. This class produces
 * [Action]s; the integration layer turns them into wire bytes.
 */
class KeyElection(
    private val ourUid: String,
    private val channelId: Int,
    private val registry: ChannelKeyRegistry,
) {
    /**
     * One peer's view as of the latest heartbeat we received from
     * them. `lastSeenTickIndex` is bumped on each fresh advertisement;
     * peers that go silent for [PEER_STALE_TICKS] are pruned.
     */
    private data class PeerView(
        val uid: String,
        val advertisedEpoch: Int,
        val lastSeenTickIndex: Long,
        val hadCurrentKeyAtLastTick: Boolean,
    )

    private val peers = mutableMapOf<String, PeerView>()
    private var tickIndex: Long = 0L

    /**
     * Note that we've heard a peer advertise their current epoch (via
     * a KEY_OFFER, voice-frame epoch byte, or explicit heartbeat). The
     * `hadCurrentKey` flag tells us whether they're a "key holder" —
     * if THEY had the current key at this tick, then their disappearance
     * later triggers a rotation.
     */
    fun observePeerEpoch(
        peerUid: String,
        peerEpoch: Int,
        hadCurrentKey: Boolean,
    ) {
        peers[peerUid] = PeerView(peerUid, peerEpoch, tickIndex, hadCurrentKey)
    }

    /**
     * Mark a peer as departed (Mumble UserRemove or explicit signal).
     * Returns true if the peer was a key-holder at the time of departure
     * — caller can chain into [onKeyHolderDeparted] when this is true.
     */
    fun observePeerDeparted(peerUid: String): Boolean {
        val view = peers.remove(peerUid) ?: return false
        return view.hadCurrentKeyAtLastTick
    }

    /**
     * Advance one election tick. Returns the action (if any) the
     * integration layer should take on the wire.
     *
     * Caller is expected to invoke this at a steady cadence (~every
     * 2s per the design); slower cadence still converges, just more
     * slowly.
     */
    fun tick(): Action {
        tickIndex++
        pruneStalePeers()

        val highestPeerEpoch =
            peers.values.maxOfOrNull { it.advertisedEpoch } ?: ChannelKeyRegistry.NO_EPOCH

        // Bootstrap: no key, no peers — we either generate (we're alone
        // and ready to talk, so prepare a key) OR wait for someone to
        // arrive. We choose to NOT auto-generate; the integration layer
        // signals "start the channel" via [generateInitialKey] which
        // produces a Key Offer. This keeps the election deterministic
        // and avoids two clients on the same channel both auto-
        // generating different keys at the same instant.
        if (!registry.hasKey() && peers.isEmpty()) {
            return Action.Idle
        }

        // We have no key but peers exist → ask whoever holds the
        // highest epoch.
        if (!registry.hasKey() && highestPeerEpoch != ChannelKeyRegistry.NO_EPOCH) {
            return Action.RequestKey(forEpoch = highestPeerEpoch)
        }

        // We have a key but a peer claims a higher epoch → we're stale,
        // ask for the higher one.
        val ourEpoch = registry.currentEpoch()
        if (highestPeerEpoch > ourEpoch && highestPeerEpoch != ChannelKeyRegistry.NO_EPOCH) {
            return Action.RequestKey(forEpoch = highestPeerEpoch)
        }

        return Action.Idle
    }

    /**
     * Decide whether to respond to a peer's key request. Returns true
     * if we should send the OAEP-wrapped key for [forEpoch] to
     * [requesterUid].
     *
     * "Lowest uid wins": only the lowest-uid peer (by lexicographic
     * compare) who currently holds [forEpoch] responds. Others stay
     * quiet to avoid response storms.
     */
    fun shouldAnswerKeyRequest(
        requesterUid: String,
        forEpoch: Int,
    ): Boolean {
        if (registry.currentEpoch() != forEpoch) return false
        // Find all peers who claim to have this epoch.
        val holders =
            peers.values
                .filter { it.advertisedEpoch == forEpoch && it.uid != requesterUid }
                .map { it.uid } + listOf(ourUid)
        val lowestHolder = holders.min()
        return lowestHolder == ourUid
    }

    /**
     * A peer who held the current key has departed. Compute whether we
     * should generate a new key now and broadcast it.
     *
     * Returns the action: either [Action.RotateKey] (we're the lowest-
     * uid surviving holder, so it's our turn) or [Action.Idle] (someone
     * lower-uid will rotate, just wait).
     */
    fun onKeyHolderDeparted(): Action {
        // After a holder leaves, peers contains only surviving peers.
        // We rotate iff WE are the lowest-uid surviving holder.
        val survivingHolders =
            peers.values
                .filter { it.hadCurrentKeyAtLastTick && it.advertisedEpoch == registry.currentEpoch() }
                .map { it.uid } + listOf(ourUid)
        val lowestHolder = survivingHolders.min()
        return if (lowestHolder == ourUid) {
            Action.RotateKey(nextEpoch = nextEpoch(registry.currentEpoch()))
        } else {
            Action.Idle
        }
    }

    /**
     * Compute the next epoch byte after [current], wrapping mod 256 (the
     * wire epoch is one byte; see [AeadCodec]).
     */
    private fun nextEpoch(current: Int): Int =
        if (current == ChannelKeyRegistry.NO_EPOCH) {
            0
        } else {
            (current + 1) and 0xFF
        }

    private fun pruneStalePeers() {
        val cutoff = tickIndex - PEER_STALE_TICKS
        peers.entries.removeIf { it.value.lastSeenTickIndex < cutoff }
    }

    /** For tests / diagnostics. */
    internal fun knownPeerCount(): Int = peers.size

    sealed class Action {
        /** Do nothing this tick. */
        data object Idle : Action()

        /** Send a KEY_REQ on the multicast group asking for [forEpoch]. */
        data class RequestKey(
            val forEpoch: Int,
        ) : Action()

        /**
         * Generate a new key, install it locally at [nextEpoch], then
         * broadcast a KEY_OFFER. Caller produces the OAEP wrap per
         * recipient using their TAK enrollment cert public key.
         */
        data class RotateKey(
            val nextEpoch: Int,
        ) : Action()
    }

    companion object {
        /**
         * Peer is forgotten after this many ticks without a fresh
         * advertisement. Five ticks at 2s each = 10s of silence ≈ Mumble
         * UserRemove latency + network fudge. Tunable.
         */
        const val PEER_STALE_TICKS: Long = 5
    }
}
