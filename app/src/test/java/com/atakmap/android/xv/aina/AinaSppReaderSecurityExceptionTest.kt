package com.atakmap.android.xv.aina

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.mockk
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Quick-win M3 coverage for [AinaSppReader].
 *
 * On Android 12+, a revoked BLUETOOTH_CONNECT runtime permission
 * causes [BluetoothDevice.createRfcommSocketToServiceRecord] to
 * throw [SecurityException]. The previous implementation caught
 * everything as `Throwable` and silently looped forever on the 400 /
 * 800 / 1200 ms backoff — indistinguishable from "AINA hasn't
 * advertised SDP yet". The fix:
 *  - SecurityException on create is classified FATAL,
 *  - fires the `onFatal` callback exactly once,
 *  - does NOT enter the ReconnectPolicy backoff,
 *  - exactly ONE attempt is made (per spec; verified by counter spy).
 *
 * Robolectric runner so the `Handler(Looper.getMainLooper())` field
 * initialiser in the reader doesn't trip on the missing Android
 * looper under plain JUnit.
 */
@RunWith(RobolectricTestRunner::class)
class AinaSppReaderSecurityExceptionTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `SecurityException on RFCOMM create is FATAL and stops after one attempt`() {
        val attemptCount = AtomicInteger(0)
        val fatalLatch = CountDownLatch(1)
        val fatalReason = arrayOfNulls<String>(1)
        val connStates = mutableListOf<Boolean>()

        val factory =
            AinaSppReader.SocketFactory { _, _, _ ->
                // Bumping the counter inside the factory is the
                // exact-one-attempt assertion: every secure +
                // insecure create call routes through here, so this
                // is the source of truth for "how many attempts did
                // the reader actually make."
                attemptCount.incrementAndGet()
                throw SecurityException("BLUETOOTH_CONNECT revoked (simulated)")
            }

        val device = mockBluetoothDevice("AA:BB:CC:DD:EE:FF")

        val reader =
            AinaSppReader(
                context = context,
                onEvent = { _, _ -> },
                onConnectionState = { up -> synchronized(connStates) { connStates.add(up) } },
                onFatal = { reason ->
                    fatalReason[0] = reason
                    fatalLatch.countDown()
                },
                socketFactory = factory,
            )

        reader.connect(device)

        // Fatal path runs synchronously inside the read-loop thread.
        // Give the thread a generous timeout; the assertion will fire
        // immediately on countDown.
        assertTrue(
            "expected onFatal within 2s",
            fatalLatch.await(2, TimeUnit.SECONDS),
        )

        // Exactly ONE create attempt. The fix's whole point is that
        // SecurityException must not be retried — neither inside the
        // 3-attempt burst (no secure-then-insecure shuffle) nor via
        // the ReconnectPolicy outer backoff.
        assertEquals(
            "SecurityException must trigger exactly one attempt — got ${attemptCount.get()}",
            1,
            attemptCount.get(),
        )
        assertNotNull("onFatal reason must be set", fatalReason[0])
        assertTrue(
            "reason should mention BLUETOOTH_CONNECT: '${fatalReason[0]}'",
            fatalReason[0]!!.contains("BLUETOOTH_CONNECT"),
        )

        // Sanity: a brief wait to catch any deferred reconnect from
        // the policy. If the FATAL path leaked into the backoff, more
        // attempts would queue up and the counter would tick beyond
        // 1. 750 ms covers the first SPP_SCHEDULE entry (400 ms)
        // plus a wide margin.
        Thread.sleep(750)
        assertEquals(
            "SecurityException must NOT enter ReconnectPolicy backoff",
            1,
            attemptCount.get(),
        )

        // The connection state should have ended at `false`. We don't
        // care whether the reader emitted intermediate true→false
        // toggles (and on this fatal path it shouldn't), only that
        // the FINAL state is down and that we never reported `true`.
        assertFalse(
            "FATAL path must never emit onConnectionState(true)",
            connStates.contains(true),
        )

        reader.disconnect()
    }

    private fun mockBluetoothDevice(mac: String): BluetoothDevice {
        val dev = mockk<BluetoothDevice>(relaxed = true)
        every { dev.address } returns mac
        every { dev.name } returns "APTT V1 (mock)"
        every { dev.bondState } returns BluetoothDevice.BOND_BONDED
        // Stub the socket methods just in case mockk's relaxed mode
        // doesn't suppress them on this Android stub jar; the real
        // factory bypasses these but defence-in-depth.
        every { dev.createRfcommSocketToServiceRecord(any<UUID>()) } returns
            mockk<BluetoothSocket>(relaxed = true)
        every { dev.createInsecureRfcommSocketToServiceRecord(any<UUID>()) } returns
            mockk<BluetoothSocket>(relaxed = true)
        return dev
    }
}
