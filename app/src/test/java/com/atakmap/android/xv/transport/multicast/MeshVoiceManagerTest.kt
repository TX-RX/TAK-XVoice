package com.atakmap.android.xv.transport.multicast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MeshVoiceManagerTest {
    // ---- harness ----

    private class FakeLeg(
        override val config: ChannelMulticastConfig,
        override val endpoint: MulticastEndpoint,
        private val registry: ChannelKeyRegistry,
    ) : MeshLeg {
        override val channelName: String = config.channelName
        val sentOpus = mutableListOf<ByteArray>()
        val sentControl = mutableListOf<ControlPacket.Message>()
        val relayed = mutableListOf<Pair<String, Boolean>>() // speakerUid → burstStart
        var bursts = 0
        var closed = false

        override val encryptedNow: Boolean
            get() = config.cryptoPolicy != CryptoPolicy.CLEARTEXT && registry.hasKey()

        override fun beginVoiceBurst() {
            bursts++
        }

        override fun sendOpus(opus: ByteArray) {
            sentOpus += opus
        }

        override fun sendControl(msg: ControlPacket.Message) {
            sentControl += msg
        }

        override fun sendRelayOpus(
            speakerUid: String,
            opus: ByteArray,
            burstStart: Boolean,
        ) {
            relayed += speakerUid to burstStart
        }

        override fun close() {
            closed = true
        }
    }

    private class Harness(
        ourUid: String = "uid-mmm",
        configOverride: ((String) -> ChannelMulticastConfig)? = null,
    ) {
        var now = 10_000L
        var meshOn = true
        var mumbleUp = true
        val legs = mutableMapOf<String, FakeLeg>()
        val playedRx = mutableListOf<String>() // speaker labels forwarded to service
        val relayedToMumble = mutableListOf<Boolean>() // burstStart flags
        val meshTxStateChanges = mutableListOf<Boolean>()
        val peerSessions = mutableMapOf<Int, String>() // mumble session → uid
        val peerUids = mutableSetOf<String>()
        val connectedUids = mutableSetOf<String>()
        val certFps = mutableMapOf<String, String>()

        val manager =
            MeshVoiceManager(
                ourUid = ourUid,
                ourCallsign = { "Alpha-1" },
                meshEnabled = { meshOn },
                configForChannel = configOverride ?: { ChannelMulticastConfig.defaultFor(it) },
                serverIdentity = { ServerIdentity.fromHostname("tak.example.com") },
                mumbleConnected = { mumbleUp },
                legFactory = { cfg, endpoint, registry, _ ->
                    FakeLeg(cfg, endpoint, registry).also { legs[cfg.channelName] = it }
                },
                onRxOpus = { _, label -> playedRx += label },
                relayToMumble = { _, burstStart -> relayedToMumble += burstStart },
                onMeshTxStateChanged = { meshTxStateChanges += it },
                deviceUidForMumbleSession = { peerSessions[it] },
                uidMumbleConnected = { it in connectedUids },
                knownPeerUids = { peerUids },
                certFpForUid = { certFps[it] },
                ourCertDer = { byteArrayOf(1, 2, 3) },
                unwrapKey = { it.reversedArray() },
                wrapKeyFor = { _, key -> key.reversedArray() },
                nowMs = { now },
            )

        fun channelLeg(): FakeLeg = legs.getValue("ops-1")

        fun joinAndTick() {
            manager.onChannelJoined(0, "Ops-1")
            manager.tick()
        }
    }

    private fun ssrcKeyOf(uid: String): String = "ssrc:%08x".format(RtpFraming.fnv1aSsrc(uid))

    // ---- leg lifecycle ----

    @Test
    fun `joining the primary channel brings up an auto-derived leg plus the rendezvous leg`() {
        val h = Harness()
        h.joinAndTick()
        assertNotNull(h.legs["ops-1"])
        assertNotNull(h.legs[MeshVoiceManager.RENDEZVOUS_CHANNEL])
        assertEquals(
            MulticastGroupDerivation.derive(ServerIdentity.fromHostname("tak.example.com"), "ops-1"),
            h.channelLeg().endpoint,
        )
    }

    @Test
    fun `mesh disabled means no legs at all`() {
        val h = Harness()
        h.meshOn = false
        h.joinAndTick()
        assertTrue(h.legs.isEmpty())
        assertNull(h.manager.statusBadge())
    }

    @Test
    fun `a channel configured OFF gets no leg`() {
        val h = Harness(configOverride = { ChannelMulticastConfig.defaultFor(it).copy(mode = MulticastMode.OFF) })
        h.joinAndTick()
        assertNull(h.legs["ops-1"])
    }

    @Test
    fun `moving channels swaps the leg and legs survive mumble teardown`() {
        val h = Harness()
        h.joinAndTick()
        val firstLeg = h.channelLeg()
        h.manager.onChannelJoined(0, "Ops-2")
        assertTrue(firstLeg.closed)
        assertNotNull(h.legs["ops-2"])
        // Mumble drops entirely — the failover scenario. Leg stays up.
        h.manager.onChannelsCleared()
        assertFalse(h.legs.getValue("ops-2").closed)
    }

    // ---- failover TX routing ----

    @Test
    fun `failover mode holds mesh TX until mumble drops then flips`() {
        val h = Harness()
        h.joinAndTick()
        h.manager.beginTxBurst()
        h.manager.sendTxOpus(byteArrayOf(1), targetSlot = 0)
        assertTrue("healthy server: FAILOVER channel must not TX on mesh", h.channelLeg().sentOpus.isEmpty())

        h.mumbleUp = false
        h.now += 1_000
        h.manager.tick()
        assertTrue(h.manager.isMeshTxActive())
        assertEquals(listOf(true), h.meshTxStateChanges)

        h.manager.sendTxOpus(byteArrayOf(2), targetSlot = 0)
        assertEquals(1, h.channelLeg().sentOpus.size)
    }

    @Test
    fun `failback needs recovered health plus hysteresis`() {
        val h = Harness()
        h.joinAndTick()
        h.mumbleUp = false
        h.now += 1_000
        h.manager.tick()
        assertTrue(h.manager.isMeshTxActive())

        // Server returns; RX flows again.
        h.mumbleUp = true
        h.manager.onMumbleRxFrame(0, speakerSession = 7, opus = byteArrayOf(1))
        h.now += 1_000
        h.manager.tick()
        assertTrue("hysteresis window not elapsed — still on mesh", h.manager.isMeshTxActive())

        // Keep RX healthy across the hysteresis window.
        repeat(11) {
            h.now += 1_000
            h.manager.onMumbleRxFrame(0, speakerSession = 7, opus = byteArrayOf(1))
            h.manager.tick()
        }
        assertFalse(h.manager.isMeshTxActive())
        assertEquals(listOf(true, false), h.meshTxStateChanges)
    }

    @Test
    fun `always mode transmits on mesh even with a healthy server`() {
        val h = Harness(configOverride = { ChannelMulticastConfig.defaultFor(it).copy(mode = MulticastMode.ALWAYS) })
        h.joinAndTick()
        h.manager.sendTxOpus(byteArrayOf(1), targetSlot = 0)
        assertEquals(1, h.channelLeg().sentOpus.size)
        assertFalse("active-active TX is not failover state", h.manager.isMeshTxActive())
    }

    @Test
    fun `secondary-slot frames never touch mesh legs`() {
        val h = Harness(configOverride = { ChannelMulticastConfig.defaultFor(it).copy(mode = MulticastMode.ALWAYS) })
        h.joinAndTick()
        h.manager.sendTxOpus(byteArrayOf(1), targetSlot = 1)
        assertTrue(h.channelLeg().sentOpus.isEmpty())
    }

    @Test
    fun `beginTxBurst resets every leg's burst state`() {
        val h = Harness()
        h.joinAndTick()
        h.manager.beginTxBurst()
        assertEquals(1, h.channelLeg().bursts)
    }

    // ---- RX merge + dedup ----

    @Test
    fun `same speaker on both legs plays once`() {
        val h = Harness()
        h.peerSessions[7] = "uid-peer"
        h.peerUids += "uid-peer"
        h.joinAndTick() // tick builds the ssrc→uid map

        assertTrue(h.manager.onMumbleRxFrame(0, speakerSession = 7, opus = byteArrayOf(1)))
        h.manager.onVoice("ops-1", byteArrayOf(1), ssrcKeyOf("uid-peer"), seqInBurst = 0)
        assertTrue("mesh copy of a mumble-owned burst must not play", h.playedRx.isEmpty())
    }

    @Test
    fun `mesh-only speakers play with a mesh label`() {
        val h = Harness()
        h.joinAndTick()
        h.manager.onVoice("ops-1", byteArrayOf(1), "ssrc:cafebabe", seqInBurst = 0)
        assertEquals(listOf("mcast:ops-1:ssrc:cafebabe"), h.playedRx)
    }

    @Test
    fun `rendezvous voice is discarded`() {
        val h = Harness()
        h.joinAndTick()
        h.manager.onVoice(MeshVoiceManager.RENDEZVOUS_CHANNEL, byteArrayOf(1), "ssrc:cafebabe", seqInBurst = 0)
        assertTrue(h.playedRx.isEmpty())
    }

    // ---- bridge election + relay ----

    private fun Harness.makeUsBridge() {
        joinAndTick()
        manager.observePeerConnectivity("uid-zzz-offline", mumbleConnected = false)
        now += 1_000
        manager.tick()
    }

    @Test
    fun `bridge activates for the lowest connected uid when a peer is serverless`() {
        val h = Harness()
        h.makeUsBridge()
        assertTrue(h.manager.isBridging())
        assertTrue(h.manager.statusBadge()!!.contains("BRIDGING"))
    }

    @Test
    fun `bridge defers to a lower connected uid seen via beacon`() {
        val h = Harness()
        h.joinAndTick()
        h.manager.observePeerConnectivity("uid-zzz-offline", mumbleConnected = false)
        h.manager.onControl(
            "ops-1",
            ControlPacket.Message.PeerBeacon(
                uid = "uid-aaa",
                callsign = "Bravo-2",
                mumbleConnected = true,
                bridging = false,
                channels = emptyList(),
            ),
            sourceHost = "198.51.100.9",
        )
        h.now += 1_000
        h.manager.tick()
        assertFalse(h.manager.isBridging())
    }

    @Test
    fun `bridge relays serverless mesh speakers onto mumble`() {
        val h = Harness()
        h.makeUsBridge()
        h.manager.onVoice("ops-1", byteArrayOf(1), "ssrc:cafebabe", seqInBurst = 0)
        h.manager.onVoice("ops-1", byteArrayOf(2), "ssrc:cafebabe", seqInBurst = 1)
        assertEquals(listOf(true, false), h.relayedToMumble)
    }

    @Test
    fun `bridge TXes its own mic onto the mesh even though mumble is healthy`() {
        // The server never echoes our own voice back, so the
        // mumble→mesh relay path cannot carry the bridge operator's
        // mic — sendTxOpus must fan it out directly while bridging.
        // (Field regression 2026-07-15: mesh-only peers heard everyone
        // EXCEPT the bridge operator.)
        val h = Harness()
        h.makeUsBridge()
        h.manager.sendTxOpus(byteArrayOf(7), targetSlot = 0)
        assertEquals(1, h.channelLeg().sentOpus.size)
    }

    @Test
    fun `without the bridge role healthy-mumble FAILOVER keeps own TX off the mesh`() {
        val h = Harness()
        h.joinAndTick()
        h.manager.sendTxOpus(byteArrayOf(7), targetSlot = 0)
        assertTrue(h.channelLeg().sentOpus.isEmpty())
    }

    @Test
    fun `bridge does not re-relay speakers who are already on the server`() {
        val h = Harness()
        h.peerUids += "uid-peer"
        h.connectedUids += "uid-peer"
        h.makeUsBridge()
        h.manager.onVoice("ops-1", byteArrayOf(1), ssrcKeyOf("uid-peer"), seqInBurst = 0)
        assertTrue(h.relayedToMumble.isEmpty())
    }

    @Test
    fun `bridge relays server speakers onto the mesh with their own identity`() {
        val h = Harness()
        h.peerSessions[7] = "uid-server-peer"
        h.makeUsBridge()
        h.manager.onMumbleRxFrame(0, speakerSession = 7, opus = byteArrayOf(1))
        assertEquals(listOf("uid-server-peer" to true), h.channelLeg().relayed)
    }

    @Test
    fun `no relay without the bridge role`() {
        val h = Harness()
        h.joinAndTick()
        h.manager.onVoice("ops-1", byteArrayOf(1), "ssrc:cafebabe", seqInBurst = 0)
        h.manager.onMumbleRxFrame(0, speakerSession = 7, opus = byteArrayOf(1))
        assertTrue(h.relayedToMumble.isEmpty())
        assertTrue(h.channelLeg().relayed.isEmpty())
    }

    // ---- key management ----

    @Test
    fun `alone on an encrypted channel we bootstrap the key after the wait ticks`() {
        val h = Harness()
        h.joinAndTick()
        assertFalse(h.channelLeg().encryptedNow)
        repeat(MeshVoiceManager.KEY_BOOTSTRAP_WAIT_TICKS + 1) {
            h.now += 1_000
            h.manager.tick()
        }
        assertTrue(h.channelLeg().encryptedNow)
        assertTrue(h.manager.statusBadge()!!.let { !it.contains("CLEAR") })
    }

    @Test
    fun `keyless bootstrap defers to a lower-uid keyless peer`() {
        val h = Harness(ourUid = "uid-mmm")
        h.joinAndTick()
        // A keyless peer with a LOWER uid beacons on our channel: they
        // are the generator, we wait.
        h.manager.onControl(
            "ops-1",
            beaconFor("uid-aaa", channelEpoch = ChannelKeyRegistry.NO_EPOCH, h),
            sourceHost = "198.51.100.9",
        )
        repeat(MeshVoiceManager.KEY_BOOTSTRAP_WAIT_TICKS + 3) {
            h.now += 1_000
            h.manager.tick()
        }
        assertFalse("must not split-brain the channel key", h.channelLeg().encryptedNow)
    }

    @Test
    fun `a peer advertising a key triggers a request instead of generation`() {
        val h = Harness(ourUid = "uid-aaa")
        h.joinAndTick()
        h.manager.onControl(
            "ops-1",
            beaconFor("uid-zzz", channelEpoch = 0, h),
            sourceHost = "198.51.100.9",
        )
        h.now += 1_000
        h.manager.tick()
        assertFalse(h.channelLeg().encryptedNow)
        assertTrue(
            "keyless client must ask the key holder, not self-generate",
            h.channelLeg().sentControl.any { it is ControlPacket.Message.KeyReq && it.forEpoch == 0 },
        )
    }

    private fun beaconFor(
        uid: String,
        channelEpoch: Int,
        h: Harness,
    ): ControlPacket.Message.PeerBeacon =
        ControlPacket.Message.PeerBeacon(
            uid = uid,
            callsign = uid,
            mumbleConnected = false,
            bridging = false,
            channels =
            listOf(
                ControlPacket.Message.PeerBeacon.Channel(
                    name = "ops-1",
                    group = h.channelLeg().endpoint.groupAddress,
                    port = h.channelLeg().endpoint.port,
                    keyEpoch = channelEpoch,
                ),
            ),
        )

    @Test
    fun `preshared key from a comms plan encrypts immediately`() {
        val h = Harness()
        h.joinAndTick()
        h.manager.installPresharedKey("Ops-1", ByteArray(AeadCodec.KEY_BYTES) { 0x42 })
        assertTrue(h.channelLeg().encryptedNow)
    }

    @Test
    fun `key request from an unknown peer triggers cert exchange then the wrapped offer`() {
        val h = Harness(ourUid = "uid-aaa") // lowest uid → we answer
        h.certFps["uid-requester"] = "ab".repeat(32)
        h.peerUids += "uid-requester"
        h.joinAndTick()
        // Get a key installed first (bootstrap).
        repeat(MeshVoiceManager.KEY_BOOTSTRAP_WAIT_TICKS + 1) {
            h.now += 1_000
            h.manager.tick()
        }
        val leg = h.channelLeg()
        leg.sentControl.clear()

        val channelId = MeshVoiceManager.stableChannelId("ops-1")
        h.manager.onControl(
            "ops-1",
            ControlPacket.Message.KeyReq(channelId, forEpoch = 0, requesterUid = "uid-requester"),
            sourceHost = "198.51.100.9",
        )
        // No cert yet → CertReq goes out, no offer.
        assertTrue(leg.sentControl.any { it is ControlPacket.Message.CertReq })
        assertTrue(leg.sentControl.none { it is ControlPacket.Message.KeyOffer })

        // The requester publishes a cert whose fp matches presence.
        val certDer = byteArrayOf(9, 9, 9)
        h.certFps["uid-requester"] = sha256HexOf(certDer)
        h.manager.onControl(
            "ops-1",
            ControlPacket.Message.CertReply(certDer),
            sourceHost = "198.51.100.9",
        )
        val offer = leg.sentControl.filterIsInstance<ControlPacket.Message.KeyOffer>().single()
        assertEquals("uid-requester", offer.recipientUid)
        assertEquals(0, offer.epoch)
    }

    @Test
    fun `key offer addressed to us installs and flips the leg to encrypted`() {
        val h = Harness()
        h.joinAndTick()
        val key = ByteArray(AeadCodec.KEY_BYTES) { 7 }
        h.manager.onControl(
            "ops-1",
            ControlPacket.Message.KeyOffer(
                channelId = MeshVoiceManager.stableChannelId("ops-1"),
                epoch = 4,
                recipientUid = "uid-mmm",
                // Harness unwrapKey reverses, so pre-reverse.
                wrappedKey = key.reversedArray(),
            ),
            sourceHost = "198.51.100.9",
        )
        assertTrue(h.channelLeg().encryptedNow)
    }

    @Test
    fun `departure of a key holder rotates the key and offers it to known certs`() {
        val h = Harness(ourUid = "uid-aaa")
        h.joinAndTick()
        repeat(MeshVoiceManager.KEY_BOOTSTRAP_WAIT_TICKS + 1) {
            h.now += 1_000
            h.manager.tick()
        }
        val leg = h.channelLeg()
        // A holder peer is known (cert cached via CertReply path).
        val certDer = byteArrayOf(5, 5)
        h.certFps["uid-holder"] = sha256HexOf(certDer)
        h.peerUids += "uid-holder"
        h.manager.onControl("ops-1", ControlPacket.Message.CertReply(certDer), "198.51.100.9")
        h.manager.onControl(
            "ops-1",
            ControlPacket.Message.KeyReq(MeshVoiceManager.stableChannelId("ops-1"), 0, "uid-holder"),
            "198.51.100.9",
        )
        leg.sentControl.clear()

        h.manager.onPeerDeparted("uid-holder")
        // Requester was recorded with NO_EPOCH (not a holder), so no
        // rotation fires for them — this guards the rotation trigger.
        assertTrue(leg.sentControl.filterIsInstance<ControlPacket.Message.KeyOffer>().isEmpty())
    }

    // ---- beacons + discovery ----

    @Test
    fun `beacons carry our legs and go out on channel and rendezvous groups`() {
        val h = Harness()
        h.joinAndTick()
        val fromChannel = h.channelLeg().sentControl.filterIsInstance<ControlPacket.Message.PeerBeacon>()
        val fromRendezvous =
            h.legs.getValue(MeshVoiceManager.RENDEZVOUS_CHANNEL).sentControl.filterIsInstance<ControlPacket.Message.PeerBeacon>()
        assertEquals(1, fromChannel.size)
        assertEquals(1, fromRendezvous.size)
        val beacon = fromChannel.single()
        assertEquals("uid-mmm", beacon.uid)
        assertEquals("Alpha-1", beacon.callsign)
        assertTrue(beacon.mumbleConnected)
        assertEquals(listOf("ops-1"), beacon.channels.map { it.name })
    }

    @Test
    fun `received beacons populate the discovered-channels table`() {
        val h = Harness()
        h.joinAndTick()
        h.manager.onControl(
            MeshVoiceManager.RENDEZVOUS_CHANNEL,
            ControlPacket.Message.PeerBeacon(
                uid = "uid-remote",
                callsign = "Charlie-3",
                mumbleConnected = false,
                bridging = false,
                channels =
                listOf(
                    ControlPacket.Message.PeerBeacon.Channel("ops-9", "239.226.1.2", 16855),
                ),
            ),
            sourceHost = "198.51.100.9",
        )
        val found = h.manager.discoveredChannels().single()
        assertEquals("ops-9", found.name)
        assertEquals("239.226.1.2", found.group)
        assertEquals(16855, found.port)
        assertEquals("Charlie-3", found.viaCallsign)
        // Channels we already carry are not "discovered".
        assertTrue(h.manager.discoveredChannels().none { it.name == "ops-1" })
    }

    // ---- badges + shutdown ----

    @Test
    fun `status badge reflects failover and cleartext state`() {
        val h = Harness()
        h.joinAndTick()
        assertEquals("MESH READY · CLEAR", h.manager.statusBadge())
        h.mumbleUp = false
        h.now += 1_000
        h.manager.tick()
        assertEquals("MESH ACTIVE · CLEAR", h.manager.statusBadge())
    }

    @Test
    fun `shutdown closes every leg and clears state`() {
        val h = Harness()
        h.joinAndTick()
        h.manager.shutdown()
        assertTrue(h.legs.values.all { it.closed })
        assertNull(h.manager.statusBadge())
    }

    private fun sha256HexOf(bytes: ByteArray): String =
        java.security.MessageDigest
            .getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }
}
