package com.atakmap.android.xv.transport.multicast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BridgeElectionTest {
    @Test
    fun `no bridge when every known peer reaches the server`() {
        val e = BridgeElection(ourUid = "aaa")
        e.observePeer("bbb", mumbleConnected = true, nowMs = 1_000)
        assertFalse(e.evaluate(nowMs = 1_100, ourMumbleConnected = true))
    }

    @Test
    fun `lowest connected uid bridges when a peer is offline`() {
        val e = BridgeElection(ourUid = "aaa")
        e.observePeer("bbb", mumbleConnected = true, nowMs = 1_000)
        e.observePeer("ccc", mumbleConnected = false, nowMs = 1_000)
        assertTrue(e.evaluate(nowMs = 1_100, ourMumbleConnected = true))
    }

    @Test
    fun `a lower-uid connected peer wins the election over us`() {
        val e = BridgeElection(ourUid = "mmm")
        e.observePeer("aaa", mumbleConnected = true, nowMs = 1_000)
        e.observePeer("zzz", mumbleConnected = false, nowMs = 1_000)
        assertFalse(e.evaluate(nowMs = 1_100, ourMumbleConnected = true))
    }

    @Test
    fun `disconnected clients never bridge`() {
        val e = BridgeElection(ourUid = "aaa")
        e.observePeer("zzz", mumbleConnected = false, nowMs = 1_000)
        assertFalse(e.evaluate(nowMs = 1_100, ourMumbleConnected = false))
    }

    @Test
    fun `role fails over when the elected bridge goes silent`() {
        val e = BridgeElection(ourUid = "mmm", peerStaleMs = 5_000)
        e.observePeer("aaa", mumbleConnected = true, nowMs = 1_000)
        e.observePeer("zzz", mumbleConnected = false, nowMs = 1_000)
        assertFalse(e.evaluate(nowMs = 1_100, ourMumbleConnected = true))
        // aaa stops beaconing; zzz keeps beaconing offline.
        e.observePeer("zzz", mumbleConnected = false, nowMs = 8_000)
        assertTrue(e.evaluate(nowMs = 8_100, ourMumbleConnected = true))
    }

    @Test
    fun `explicit departure removes a peer immediately`() {
        val e = BridgeElection(ourUid = "mmm")
        e.observePeer("aaa", mumbleConnected = true, nowMs = 1_000)
        e.observePeer("zzz", mumbleConnected = false, nowMs = 1_000)
        e.observePeerDeparted("aaa")
        assertTrue(e.evaluate(nowMs = 1_100, ourMumbleConnected = true))
        assertEquals(1, e.knownPeerCount())
    }

    @Test
    fun `connectivity updates replace earlier sightings`() {
        val e = BridgeElection(ourUid = "aaa")
        e.observePeer("bbb", mumbleConnected = false, nowMs = 1_000)
        assertTrue(e.evaluate(nowMs = 1_100, ourMumbleConnected = true))
        // bbb reconnects to the server — nobody needs a bridge now.
        e.observePeer("bbb", mumbleConnected = true, nowMs = 2_000)
        assertFalse(e.evaluate(nowMs = 2_100, ourMumbleConnected = true))
    }

    @Test
    fun `after partition merge exactly one bridge survives`() {
        // Pins IMMEDIATE lowest-UID convergence on a partition merge:
        // when two self-elected islands hear each other's beacons, exactly
        // one bridge survives on the very next evaluate() tick, via the
        // lowest-UID tie-break — NOT gated on any stale timeout. The 17s
        // PEER_STALE_MS is the unrelated failover-on-silence bound (it only
        // governs how long a vanished peer keeps influencing the election),
        // and must not delay merge convergence.
        val a = BridgeElection(ourUid = "aaa")
        val z = BridgeElection(ourUid = "zzz")
        // pre-merge: each island has an offline peer, so each self-elects
        a.observePeer("aaa-off", mumbleConnected = false, nowMs = 1_000)
        z.observePeer("zzz-off", mumbleConnected = false, nowMs = 1_000)
        assertTrue(a.evaluate(nowMs = 1_100, ourMumbleConnected = true))
        assertTrue(z.evaluate(nowMs = 1_100, ourMumbleConnected = true))
        // merge: beacons cross both ways
        a.observePeer("zzz", mumbleConnected = true, nowMs = 2_000)
        a.observePeer("zzz-off", mumbleConnected = false, nowMs = 2_000)
        z.observePeer("aaa", mumbleConnected = true, nowMs = 2_000)
        z.observePeer("aaa-off", mumbleConnected = false, nowMs = 2_000)
        // exactly one survives immediately (same tick, no stale wait)
        val aBridges = a.evaluate(nowMs = 2_100, ourMumbleConnected = true)
        val zBridges = z.evaluate(nowMs = 2_100, ourMumbleConnected = true)
        assertTrue(aBridges)
        assertFalse(zBridges)
    }

    @Test
    fun `multi-network islands elect bridges independently`() {
        val e = BridgeElection(ourUid = "mmm")
        // We hear a disconnected peer on our island (zzz) via multicast beacon.
        e.observePeer("zzz", mumbleConnected = false, nowMs = 1_000)

        // We DO NOT hear 'aaa' (a lower-UID connected peer) because they are on a
        // separate mesh island and we no longer feed CoT (which crosses boundaries)
        // into the election.

        // Since we are the lowest UID on OUR island that has server connectivity,
        // we should elect ourselves and activate the bridge.
        assertTrue(e.evaluate(nowMs = 1_100, ourMumbleConnected = true))
    }
}
