package com.atakmap.android.xv.transport.multicast

/**
 * Per-channel election of the single peer who relays voice between
 * the Mumble server and the multicast group ("the bridge"). Runs on
 * every mesh-enabled client; each client independently reaches the
 * same verdict from the same inputs, so no coordination round-trips
 * are needed and a partition heals itself on the next beacon.
 *
 * Inputs, refreshed continuously:
 *   - [ControlPacket.Message.PeerBeacon]s heard on the channel's
 *     multicast group (peers self-report server connectivity), fed in
 *     via [observePeer]. Beacons are the ONLY production feed —
 *     deliberately. CoT presence crosses network boundaries via the
 *     TAK server, and a candidate we can't reach over multicast can't
 *     relay for us: feeding CoT here made devices defer to lower-uid
 *     candidates on OTHER networks, leaving whole networks bridgeless.
 *     Beacon reachability IS the relay domain, so operator groups on
 *     separate networks each elect their own bridge.
 *   - Our own server connectivity, passed to [evaluate].
 *
 * Rule: the bridge is the **lowest UID among server-connected peers**
 * (including ourselves), and the bridge role only activates while at
 * least one known peer LACKS server connectivity — a fully-connected
 * team needs no relay, and relaying anyway would double every burst
 * (server copy + mesh echo) for everyone, wasting battery and airtime
 * on the constrained mesh.
 *
 * Lowest-uid-wins mirrors [KeyElection]'s convergence trick: a total
 * order with no extra messages. Two clients can disagree for at most
 * one beacon interval after a topology change; the dedup layer
 * absorbs the brief double-relay that overlap can produce.
 *
 * Pure logic: no clocks, no IO. Not thread-safe — confine to the
 * mesh manager's tick thread.
 */
class BridgeElection(
    private val ourUid: String,
    private val peerStaleMs: Long = PEER_STALE_MS,
) {
    private data class PeerView(
        val uid: String,
        val mumbleConnected: Boolean,
        val lastSeenMs: Long,
    )

    private val peers = HashMap<String, PeerView>()

    /**
     * Record a peer sighting from either a mesh beacon or CoT
     * presence. Later observations replace earlier ones wholesale —
     * connectivity is a live fact, not an accumulating history.
     */
    fun observePeer(
        uid: String,
        mumbleConnected: Boolean,
        nowMs: Long,
    ) {
        if (uid == ourUid) return
        peers[uid] = PeerView(uid, mumbleConnected, nowMs)
    }

    /** Explicit departure signal (Mumble UserRemove, presence purge). */
    fun observePeerDeparted(uid: String) {
        peers.remove(uid)
    }

    /**
     * Should WE be relaying right now?
     *
     * @param ourMumbleConnected live server connectivity of this client.
     */
    fun evaluate(
        nowMs: Long,
        ourMumbleConnected: Boolean,
    ): Boolean {
        pruneStale(nowMs)
        if (!ourMumbleConnected) return false
        val anyoneNeedsBridge = peers.values.any { !it.mumbleConnected }
        if (!anyoneNeedsBridge) return false
        val connectedUids =
            peers.values.filter { it.mumbleConnected }.map { it.uid } + ourUid
        return connectedUids.min() == ourUid
    }

    /** True when a fresh peer without server connectivity is known. */
    fun anyPeerOffline(nowMs: Long): Boolean {
        pruneStale(nowMs)
        return peers.values.any { !it.mumbleConnected }
    }

    fun reset() {
        peers.clear()
    }

    /** For tests / diagnostics. */
    internal fun knownPeerCount(): Int = peers.size

    private fun pruneStale(nowMs: Long) {
        peers.entries.removeAll { nowMs - it.value.lastSeenMs > peerStaleMs }
    }

    companion object {
        /**
         * A peer unheard-from for this long stops influencing the
         * election. Three beacon intervals (see the mesh manager's
         * BEACON_INTERVAL_MS = 5 s) plus slack: tolerate two lost
         * beacons on a lossy mesh before re-electing.
         */
        const val PEER_STALE_MS: Long = 17_000
    }
}
