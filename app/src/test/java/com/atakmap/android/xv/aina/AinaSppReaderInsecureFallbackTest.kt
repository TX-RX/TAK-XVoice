package com.atakmap.android.xv.aina

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.mockk
import java.io.IOException
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Quick-win M4 coverage for [AinaSppReader].
 *
 * The previous secure→insecure fallback only flipped on attempt 3,
 * which meant a device whose `createRfcommSocketToServiceRecord`
 * itself fails (SDP record unreachable) burnt attempts 1 + 2 against
 * a doomed secure path before the insecure fallback even ran. After
 * burst exhaustion, the [ReconnectPolicy] outer schedule restarts
 * secure-first, so a device with a permanently busted SDP record
 * never got the insecure path. The fix: an "SDP failed" `IOException`
 * on attempt 1 immediately flips the burst to insecure so attempt 2
 * uses [BluetoothDevice.createInsecureRfcommSocketToServiceRecord].
 *
 * The spy factory records the call order so the test can assert
 * "secure then insecure within the same burst" by exact sequence,
 * not just by counts.
 */
@RunWith(RobolectricTestRunner::class)
class AinaSppReaderInsecureFallbackTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `SDP failure on secure attempt flips to insecure inside same burst`() {
        val callOrder = java.util.Collections.synchronizedList(mutableListOf<String>())
        val successLatch = CountDownLatch(1)

        // Fake socket whose connect() succeeds and whose input
        // stream blocks indefinitely (mimics a real RFCOMM session
        // sitting idle waiting for button frames). The block lets
        // the test verify the success path without driving the pump
        // loop.
        val happySocket = mockk<BluetoothSocket>(relaxed = true)
        every { happySocket.connect() } answers { /* succeed */ }
        every { happySocket.inputStream } returns BlockingInputStream(successLatch)
        every { happySocket.close() } answers { /* no-op */ }

        val factory =
            AinaSppReader.SocketFactory { _, _, insecure ->
                val label = if (insecure) "INSECURE" else "SECURE"
                callOrder.add(label)
                if (!insecure) {
                    // Mimic the most common AINA-V1 first-attempt
                    // failure surface — Android's RFCOMM SDP lookup
                    // throws "service discovery failed" when the
                    // peer hasn't advertised the SPP record yet.
                    throw IOException("service discovery failed (SDP)")
                }
                happySocket
            }

        val device = mockBluetoothDevice("AA:BB:CC:DD:EE:FF")

        val reader =
            AinaSppReader(
                context = context,
                onEvent = { _, _ -> },
                onConnectionState = { up -> if (up) successLatch.countDown() },
                socketFactory = factory,
            )
        reader.connect(device)

        // Wait for the connection-up callback that the insecure
        // attempt produced. If the fix regresses, attempt 2 would
        // still be SECURE and would also throw — successLatch would
        // never fire and the test would time out here.
        assertTrue(
            "expected onConnectionState(true) within 2s",
            successLatch.await(2, TimeUnit.SECONDS),
        )

        // Exact sequence: first call is SECURE (which throws SDP),
        // second call is INSECURE (which succeeds). Any extra
        // SECURE calls between would prove the burst-flip didn't
        // take effect.
        synchronized(callOrder) {
            assertTrue(
                "expected at least 2 attempts, got $callOrder",
                callOrder.size >= 2,
            )
            assertEquals(
                "attempt 1 must be SECURE (was: ${callOrder[0]})",
                "SECURE",
                callOrder[0],
            )
            assertEquals(
                "attempt 2 must be INSECURE (was: ${callOrder[1]}) — burst flip didn't fire",
                "INSECURE",
                callOrder[1],
            )
        }

        reader.disconnect()
    }

    private fun mockBluetoothDevice(mac: String): BluetoothDevice {
        val dev = mockk<BluetoothDevice>(relaxed = true)
        every { dev.address } returns mac
        every { dev.name } returns "APTT V1 (mock)"
        every { dev.bondState } returns BluetoothDevice.BOND_BONDED
        every { dev.createRfcommSocketToServiceRecord(any<UUID>()) } returns
            mockk<BluetoothSocket>(relaxed = true)
        every { dev.createInsecureRfcommSocketToServiceRecord(any<UUID>()) } returns
            mockk<BluetoothSocket>(relaxed = true)
        return dev
    }

    /**
     * Blocks read() until the latch counts down (which the
     * connection-up callback does), then signals EOF so the read
     * loop exits cleanly and the test can run its assertions
     * without leaking threads.
     */
    private class BlockingInputStream(
        private val releaseGate: CountDownLatch,
    ) : InputStream() {
        override fun read(): Int {
            // Wait up to 2s — well past the test assertion window.
            // Returning -1 then lets the read pump exit cleanly.
            releaseGate.await(2, TimeUnit.SECONDS)
            return -1
        }
    }
}
