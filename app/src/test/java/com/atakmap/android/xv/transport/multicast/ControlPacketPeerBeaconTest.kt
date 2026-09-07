package com.atakmap.android.xv.transport.multicast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ControlPacketPeerBeaconTest {
    private fun sample(): ControlPacket.Message.PeerBeacon =
        ControlPacket.Message.PeerBeacon(
            uid = "uid-alpha",
            callsign = "Alpha-1",
            mumbleConnected = true,
            bridging = false,
            channels =
            listOf(
                ControlPacket.Message.PeerBeacon.Channel("ops-1", "239.226.1.2", 16855, keyEpoch = 3, keyFp = 0x1234ABCD),
                ControlPacket.Message.PeerBeacon.Channel("bravo", "224.0.0.1", 5007),
            ),
        )

    @Test
    fun `peer beacon round-trips`() {
        val wire = ControlPacket.encode(sample())
        assertTrue(ControlPacket.isControl(wire))
        assertEquals(sample(), ControlPacket.decode(wire))
    }

    @Test
    fun `flags round-trip independently`() {
        val bridging = sample().copy(mumbleConnected = false, bridging = true)
        assertEquals(bridging, ControlPacket.decode(ControlPacket.encode(bridging)))
    }

    @Test
    fun `zero channels is a valid beacon`() {
        val empty = sample().copy(channels = emptyList())
        assertEquals(empty, ControlPacket.decode(ControlPacket.encode(empty)))
    }

    @Test
    fun `speaker name announcement round-trips`() {
        val msg =
            ControlPacket.Message.SpeakerName(
                channelId = 0x1234,
                speakerKey = "ssrc:00c0ffee",
                name = "Desktop-Dan",
            )
        val wire = ControlPacket.encode(msg)
        assertTrue(ControlPacket.isControl(wire))
        assertEquals(msg, ControlPacket.decode(wire))
    }

    @Test
    fun `truncated beacon decodes to null not a crash`() {
        val wire = ControlPacket.encode(sample())
        assertNull(ControlPacket.decode(wire.copyOfRange(0, wire.size - 3)))
    }

    @Test
    fun `absurd channel count is rejected as malformed`() {
        val wire = ControlPacket.encode(sample().copy(channels = emptyList())).toMutableList()
        // Body layout: uid, callsign, flags, channelCount (BE int).
        // Corrupt the count field to a huge value.
        val countOffset = wire.size - 4
        wire[countOffset] = 0x7F
        assertNull(ControlPacket.decode(wire.toByteArray()))
    }
}
