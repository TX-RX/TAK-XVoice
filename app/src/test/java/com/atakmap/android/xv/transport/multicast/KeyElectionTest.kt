package com.atakmap.android.xv.transport.multicast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyElectionTest {
    private val key = ByteArray(AeadCodec.KEY_BYTES) { it.toByte() }

    private fun freshElection(
        uid: String,
        channelId: Int = 6,
    ): Pair<KeyElection, ChannelKeyRegistry> {
        val r = ChannelKeyRegistry(channelId)
        return KeyElection(ourUid = uid, channelId = channelId, registry = r) to r
    }

    @Test
    fun `alone with no key, idle on first tick (no auto-generate)`() {
        val (e, _) = freshElection("ANDROID-aaa")
        assertEquals(KeyElection.Action.Idle, e.tick())
    }

    @Test
    fun `solo with a key, idle ticks`() {
        val (e, r) = freshElection("ANDROID-aaa")
        r.install(epoch = 1, key = key)
        assertEquals(KeyElection.Action.Idle, e.tick())
    }

    @Test
    fun `joining peer with no key requests highest epoch from peers`() {
        val (e, _) = freshElection("ANDROID-zzz")
        e.observePeerEpoch("ANDROID-aaa", peerEpoch = 4, hadCurrentKey = true)
        e.observePeerEpoch("ANDROID-bbb", peerEpoch = 5, hadCurrentKey = true)
        val action = e.tick()
        assertTrue(action is KeyElection.Action.RequestKey)
        assertEquals(5, (action as KeyElection.Action.RequestKey).forEpoch)
    }

    @Test
    fun `peer advertising a higher epoch causes us to request upgrade`() {
        val (e, r) = freshElection("ANDROID-zzz")
        r.install(epoch = 4, key = key)
        e.observePeerEpoch("ANDROID-aaa", peerEpoch = 6, hadCurrentKey = true)
        val action = e.tick()
        assertTrue(action is KeyElection.Action.RequestKey)
        assertEquals(6, (action as KeyElection.Action.RequestKey).forEpoch)
    }

    @Test
    fun `peer at our epoch leaves us idle`() {
        val (e, r) = freshElection("ANDROID-aaa")
        r.install(epoch = 4, key = key)
        e.observePeerEpoch("ANDROID-bbb", peerEpoch = 4, hadCurrentKey = true)
        assertEquals(KeyElection.Action.Idle, e.tick())
    }

    @Test
    fun `lowest-uid holder answers KEY_REQ - others stay quiet`() {
        // Three peers all hold epoch 4. UID order: aaa < bbb < ccc.
        val (eAaa, rAaa) = freshElection("ANDROID-aaa")
        rAaa.install(epoch = 4, key = key)
        eAaa.observePeerEpoch("ANDROID-bbb", peerEpoch = 4, hadCurrentKey = true)
        eAaa.observePeerEpoch("ANDROID-ccc", peerEpoch = 4, hadCurrentKey = true)

        val (eBbb, rBbb) = freshElection("ANDROID-bbb")
        rBbb.install(epoch = 4, key = key)
        eBbb.observePeerEpoch("ANDROID-aaa", peerEpoch = 4, hadCurrentKey = true)
        eBbb.observePeerEpoch("ANDROID-ccc", peerEpoch = 4, hadCurrentKey = true)

        // ddd shows up looking for the key.
        assertTrue(eAaa.shouldAnswerKeyRequest("ANDROID-ddd", forEpoch = 4))
        assertFalse(eBbb.shouldAnswerKeyRequest("ANDROID-ddd", forEpoch = 4))
    }

    @Test
    fun `request for an epoch we no longer hold gets no answer`() {
        val (e, r) = freshElection("ANDROID-aaa")
        r.install(epoch = 4, key = key)
        r.install(epoch = 5, key = key)
        // Even though previous epoch is still in our registry, we don't
        // answer requests for it — only the current is offered.
        assertFalse(e.shouldAnswerKeyRequest("ANDROID-zzz", forEpoch = 4))
        assertTrue(e.shouldAnswerKeyRequest("ANDROID-zzz", forEpoch = 5))
    }

    @Test
    fun `key-holder departure triggers rotation on lowest-uid survivor`() {
        // aaa and bbb both hold epoch 4. ccc is the departed holder.
        val (eAaa, rAaa) = freshElection("ANDROID-aaa")
        rAaa.install(epoch = 4, key = key)
        eAaa.observePeerEpoch("ANDROID-bbb", peerEpoch = 4, hadCurrentKey = true)

        // ccc was here, now leaves. Election sees: surviving holders are
        // self (aaa) + bbb. Lowest = aaa, so aaa rotates.
        val action = eAaa.onKeyHolderDeparted()
        assertTrue(action is KeyElection.Action.RotateKey)
        assertEquals(5, (action as KeyElection.Action.RotateKey).nextEpoch)
    }

    @Test
    fun `key-holder departure does NOT trigger rotation on higher-uid survivor`() {
        val (eBbb, rBbb) = freshElection("ANDROID-bbb")
        rBbb.install(epoch = 4, key = key)
        eBbb.observePeerEpoch("ANDROID-aaa", peerEpoch = 4, hadCurrentKey = true)
        // aaa is lower-uid, so bbb defers.
        assertEquals(KeyElection.Action.Idle, eBbb.onKeyHolderDeparted())
    }

    @Test
    fun `epoch wraps mod 256 on rotation`() {
        val (e, r) = freshElection("ANDROID-aaa")
        r.install(epoch = 255, key = key)
        // No surviving peer holders → we rotate ourselves.
        val action = e.onKeyHolderDeparted()
        assertTrue(action is KeyElection.Action.RotateKey)
        assertEquals(0, (action as KeyElection.Action.RotateKey).nextEpoch)
    }

    @Test
    fun `stale peers are pruned after PEER_STALE_TICKS`() {
        val (e, r) = freshElection("ANDROID-aaa")
        r.install(epoch = 4, key = key)
        e.observePeerEpoch("ANDROID-zzz", peerEpoch = 9, hadCurrentKey = true)
        // First tick: still fresh, peer's epoch 9 wins, we request.
        val first = e.tick()
        assertTrue(first is KeyElection.Action.RequestKey)
        // Drive forward without re-observing the peer.
        repeat(KeyElection.PEER_STALE_TICKS.toInt() + 1) { e.tick() }
        // Peer should be pruned by now; we go back to idle (no peers).
        assertEquals(0, e.knownPeerCount())
        assertEquals(KeyElection.Action.Idle, e.tick())
    }

    @Test
    fun `convergence_3 nodes joining simultaneously settle on the same epoch`() {
        // Three peers all start with no key. Bootstrap: someone has to
        // generate. In our model, the integration layer triggers
        // generateInitialKey on whichever node first joins the channel
        // (the model treats simultaneous joins as a tie broken by uid).
        // This test asserts that once one peer publishes a key, the
        // others converge on it within a few ticks.
        val (eA, rA) = freshElection("ANDROID-aaa")
        val (eB, rB) = freshElection("ANDROID-bbb")
        val (eC, rC) = freshElection("ANDROID-ccc")

        // aaa generates the initial key (simulated).
        rA.install(epoch = 0, key = key)

        // bbb and ccc see aaa's heartbeat advertising epoch 0.
        eB.observePeerEpoch("ANDROID-aaa", peerEpoch = 0, hadCurrentKey = true)
        eC.observePeerEpoch("ANDROID-aaa", peerEpoch = 0, hadCurrentKey = true)

        // First tick on bbb: it should ask for the key.
        val bbbAction = eB.tick()
        assertTrue(bbbAction is KeyElection.Action.RequestKey)
        assertEquals(0, (bbbAction as KeyElection.Action.RequestKey).forEpoch)

        // aaa receives bbb's request and (being the only holder) answers.
        eA.observePeerEpoch("ANDROID-bbb", peerEpoch = ChannelKeyRegistry.NO_EPOCH, hadCurrentKey = false)
        assertTrue(eA.shouldAnswerKeyRequest("ANDROID-bbb", forEpoch = 0))

        // bbb installs the key and ccc does the same. Convergence: all
        // three end at epoch 0.
        rB.install(epoch = 0, key = key)
        rC.install(epoch = 0, key = key)
        assertEquals(0, rA.currentEpoch())
        assertEquals(0, rB.currentEpoch())
        assertEquals(0, rC.currentEpoch())
    }
}
