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
}
