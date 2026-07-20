package com.atakmap.android.xv.transport

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.atakmap.android.xv.transport.multicast.VxWireCodec
import io.mockk.every
import io.mockk.mockk
import java.net.DatagramPacket
import java.net.MulticastSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Coverage for [MulticastTransport.notifyNetworkSwap] — the bug fix
 * (2026-06-15) that closes the now-stale UDP socket on a Wi-Fi -> cell
 * handoff and re-enters the receive loop on a fresh interface.
 *
 * Without the fix, the original socket stays bound to the now-down
 * interface (wlan0 after a swap) and `MulticastSocket.receive()`
 * blocks forever because UDP has no per-receive timeout. The visible
 * symptom: voice silently freezes after a handoff; nothing in the
 * transport logs anything because no exception fires.
 *
 * Test driven via an injected socket factory so we can substitute a
 * stub [MulticastSocket] whose `receive()` blocks on a latch the test
 * controls, and `close()` releases the latch with a SocketException —
 * exactly the production behavior. We then verify that
 * notifyNetworkSwap closes the original socket, exits its loop
 * iteration, and asks the factory for a second fresh socket.
 */
@RunWith(RobolectricTestRunner::class)
class MulticastTransportSwapTest {

    /**
     * Stub MulticastSocket whose receive() blocks on a latch until
     * close() is called. close() releases the latch so the receive
     * call returns by throwing a SocketException — same path the
     * production code takes when the OS closes the socket out from
     * under a blocked receive().
     *
     * The parent ctor is called with the bound-to-port the test
     * passes (0 = OS-assigned). Even if the underlying socket bind
     * fails for any reason (unlikely on a developer box, possible on
     * a sandboxed CI runner), [bindException] surfaces it to the
     * test so the failure mode is "couldn't even start" rather than
     * "silently blocked forever".
     */
    private class StubMulticastSocket : MulticastSocket {
        val closeLatch: CountDownLatch = CountDownLatch(1)
        val receiveEntered: CountDownLatch = CountDownLatch(1)

        @Volatile
        private var closed: Boolean = false

        constructor(port: Int) : super(port)

        override fun receive(p: DatagramPacket) {
            receiveEntered.countDown()
            // Block until close() is called by the swap path.
            closeLatch.await()
            throw java.net.SocketException("test-stub: socket closed")
        }

        override fun close() {
            closeLatch.countDown()
            closed = true
            try {
                super.close()
            } catch (_: Throwable) {
                // Robolectric / test environment may or may not have
                // a real native socket behind us; ignore parent-side
                // close errors because the contract under test is the
                // observable closed state of THIS stub.
            }
        }

        override fun isClosed(): Boolean = closed || super.isClosed()

        // Production calls the (SocketAddress, NetworkInterface) form;
        // no-op so we don't actually touch the OS interface table.
        override fun joinGroup(
            mcastaddr: java.net.SocketAddress?,
            netIf: java.net.NetworkInterface?,
        ) {
            // no-op
        }

        @Suppress("DEPRECATION")
        override fun joinGroup(mcastaddr: java.net.InetAddress?) {
            // no-op
        }
    }

    private fun build(socketFactory: (Int) -> MulticastSocket): MulticastTransport {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        return MulticastTransport(
            config = TransportConfig.Multicast(
                groupAddress = "239.255.0.1",
                port = 0, // OS-assigned port — the stub ignores it.
                networkInterfaceName = null,
                channelLabel = "test",
            ),
            context = ctx,
            txCodec = VxWireCodec(12345L),
            rxCodec = VxWireCodec(12345L),
            socketFactory = socketFactory,
        )
    }

    @Test
    fun `notifyNetworkSwap closes the current socket and rebuilds on a fresh one`() {
        val sockets = java.util.Collections.synchronizedList(mutableListOf<StubMulticastSocket>())
        val factoryCalls = AtomicInteger(0)
        val factory: (Int) -> MulticastSocket = { port ->
            factoryCalls.incrementAndGet()
            StubMulticastSocket(port).also { sockets.add(it) }
        }

        val transport = build(factory)
        val listener = mockk<TransportListener>(relaxed = true)
        val onConnectedLatch = CountDownLatch(1)
        val capturingListener = object : TransportListener {
            override fun onConnected() {
                onConnectedLatch.countDown()
                listener.onConnected()
            }
            override fun onDisconnected(reason: String?) = listener.onDisconnected(reason)
            override fun onConnectionFailed(error: Throwable) = listener.onConnectionFailed(error)
            override fun onVoiceFrame(frame: VoiceFrame) = listener.onVoiceFrame(frame)
            override fun onPeerStartedTalking(peerId: String) = listener.onPeerStartedTalking(peerId)
            override fun onPeerStoppedTalking(peerId: String) = listener.onPeerStoppedTalking(peerId)
            override fun onSecondaryFailed(error: Throwable) = listener.onSecondaryFailed(error)
        }

        transport.connect(capturingListener)

        // Wait for connect to finish setup AND the receive loop to
        // block in receive(). The onConnected latch + the stub's
        // receiveEntered latch together prove we're in the
        // production "happy path" before the swap fires.
        assertTrue(
            "receive loop must report onConnected (or fail loudly) before the swap fires — " +
                "if this trips, the production runReceiveLoop is throwing on socket bind or joinGroup",
            onConnectedLatch.await(5, TimeUnit.SECONDS),
        )
        assertTrue(
            "receive loop must reach receive() on the first socket before the swap fires",
            sockets.isNotEmpty() && sockets[0].receiveEntered.await(5, TimeUnit.SECONDS),
        )
        val originalSocket = sockets[0]
        assertEquals("first connect must build exactly one socket", 1, factoryCalls.get())

        // Trigger the swap. Production behavior: close the orphan
        // socket so the blocked receive() unwinds, then rebuild on
        // a fresh thread + fresh socket.
        transport.notifyNetworkSwap()

        // Original socket must be observably closed (the swap path's
        // socket?.close() call is what unblocks the production
        // receive loop).
        assertTrue(
            "original socket must be closed by the swap path",
            originalSocket.isClosed,
        )

        // Wait for the fresh receive thread to enter its receive()
        // call on the SECOND stub — proves the swap path re-entered
        // runReceiveLoop on a fresh thread + fresh socket.
        val freshArrived = waitFor(5_000) {
            sockets.size >= 2 && sockets[1].receiveEntered.await(50, TimeUnit.MILLISECONDS)
        }
        assertTrue(
            "swap path must rebuild a fresh socket and enter receive() on it",
            freshArrived,
        )
        assertEquals(
            "swap path must call the socket factory a second time",
            2,
            factoryCalls.get(),
        )
        assertNotSame(
            "fresh socket must NOT be the same instance as the original",
            originalSocket,
            sockets[1],
        )

        // Clean up: tear the transport down so the test doesn't leak
        // a blocked receive thread into the next test.
        transport.disconnect()
    }

    @Test
    fun `notifyNetworkSwap is a no-op when no listener is installed`() {
        val factoryCalls = AtomicInteger(0)
        val factory: (Int) -> MulticastSocket = { port ->
            factoryCalls.incrementAndGet()
            StubMulticastSocket(port)
        }
        val transport = build(factory)
        // No connect() — listener is null.
        transport.notifyNetworkSwap()
        assertEquals(
            "swap without a prior connect must NOT spawn a receive loop",
            0,
            factoryCalls.get(),
        )
    }

    private fun waitFor(timeoutMs: Long, predicate: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (predicate()) return true
            Thread.sleep(20)
        }
        return predicate()
    }

    @Suppress("unused")
    private val mockkBootstrapWarmup = mockk<Any>(relaxed = true).also { every { it.toString() } returns "warmup" }
}
