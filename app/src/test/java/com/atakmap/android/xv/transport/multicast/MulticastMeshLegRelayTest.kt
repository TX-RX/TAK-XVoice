package com.atakmap.android.xv.transport.multicast

import java.net.DatagramPacket
import java.net.InetAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.SocketAddress
import java.net.SocketException
import java.util.concurrent.CountDownLatch
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Coverage for the bridge-echo loopback filter on [MulticastMeshLeg].
 *
 * Relayed datagrams carry the ORIGINAL speaker's SSRC, not the leg's
 * own, so the transport's localSpeakerKey loopback filter misses them
 * when the OS delivers our own multicast back to us. Field repro
 * 2026-07-15: a desktop Mumble speaker's audio was relayed to the mesh
 * by the bridging tablet, looped back, re-ingested as fresh mesh
 * traffic, and relayed back onto Mumble as an echo. The leg must
 * recognize every SSRC it relays as its own on RX.
 */
class MulticastMeshLegRelayTest {
    // Blocks receive() until close(); send() is a no-op so the test
    // never emits real datagrams onto the developer's LAN.
    private class StubSocket : MulticastSocket(0) {
        private val closeLatch = CountDownLatch(1)

        override fun receive(p: DatagramPacket) {
            closeLatch.await()
            throw SocketException("test-stub: socket closed")
        }

        override fun send(p: DatagramPacket) {
            // no-op
        }

        override fun close() {
            closeLatch.countDown()
            try {
                super.close()
            } catch (_: Throwable) {
            }
        }

        override fun joinGroup(
            mcastaddr: SocketAddress?,
            netIf: NetworkInterface?,
        ) {
            // no-op
        }

        @Suppress("DEPRECATION")
        override fun joinGroup(mcastaddr: InetAddress?) {
            // no-op
        }
    }

    private object NullSink : MeshLegSink {
        override fun onVoice(
            channelName: String,
            opus: ByteArray,
            speakerKey: String,
            seqInBurst: Int?,
        ) {
        }

        override fun onControl(
            channelName: String,
            msg: ControlPacket.Message,
            sourceHost: String,
        ) {
        }
    }

    private fun ssrcKeyOf(uid: String): String = "ssrc:%08x".format(RtpFraming.fnv1aSsrc(uid))

    private fun buildLeg(nowMs: () -> Long): MulticastMeshLeg =
        MulticastMeshLeg(
            config = ChannelMulticastConfig.defaultFor("ops-1"),
            endpoint = MulticastEndpoint("239.255.0.1", 16800),
            registry = ChannelKeyRegistry(1),
            ourUid = "uid-bridge",
            context = null,
            sink = NullSink,
            socketFactory = { StubSocket() },
            nowMs = nowMs,
        )

    @Test
    fun `speakers we relay are recognized as our own on RX`() {
        val leg = buildLeg { 10_000L }
        try {
            val desktopKey = ssrcKeyOf("mumble:53")
            assertFalse("not relayed yet — must not filter", leg.isOwnRelaySpeaker(desktopKey))
            leg.sendRelayOpus("mumble:53", byteArrayOf(1), burstStart = true)
            assertTrue("relayed SSRC must be filtered on RX", leg.isOwnRelaySpeaker(desktopKey))
            assertFalse(
                "unrelated speakers must still pass",
                leg.isOwnRelaySpeaker(ssrcKeyOf("uid-someone-else")),
            )
        } finally {
            leg.close()
        }
    }

    @Test
    fun `the own-relay filter expires once we stop relaying the speaker`() {
        // A speaker relayed while server-connected later drops off and
        // transmits DIRECTLY on the mesh with the same uid-derived
        // SSRC. A permanent filter ate those genuine frames (field
        // repro 2026-07-15 16:10: mesh-only device never relayed back
        // to Mumble after having spoken on the server earlier).
        var now = 10_000L
        val leg = buildLeg { now }
        try {
            val key = ssrcKeyOf("uid-tablet")
            leg.sendRelayOpus("uid-tablet", byteArrayOf(1), burstStart = true)
            assertTrue(leg.isOwnRelaySpeaker(key))
            now += MulticastMeshLeg.OWN_RELAY_TTL_MS + 1
            assertFalse(
                "speaker no longer relayed — their direct mesh TX must pass",
                leg.isOwnRelaySpeaker(key),
            )
        } finally {
            leg.close()
        }
    }
}
