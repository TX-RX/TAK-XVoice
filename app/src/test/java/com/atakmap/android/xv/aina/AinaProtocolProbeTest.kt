package com.atakmap.android.xv.aina

import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Coverage for the V2 AINA SDP-cache-gap probe.
 *
 * The probe lives between [AinaDeviceClassifier] (which only reads the
 * cached UUID list) and the connect flow — when the classifier returns
 * SPP for an APTT device, the probe forces a live SDP refresh + GATT
 * service discovery and resolves to BLE if the V2 vendor UUID shows
 * up on either path. Tests cover the three resolution sources
 * (ACTION_UUID broadcast, GATT discovery, timeout) plus the per-MAC
 * single-flight guarantee.
 *
 * Robolectric required for the real Android Context (for
 * registerReceiver) and ParcelUuid wrapping; mockk for the
 * BluetoothDevice + BluetoothGattCallback edges we want to drive
 * synthetically without standing up a real BLE stack.
 */
@RunWith(RobolectricTestRunner::class)
class AinaProtocolProbeTest {
    private val handler = Handler(Looper.getMainLooper())

    @Test
    fun `ACTION_UUID broadcast carrying the V2 vendor UUID resolves to BLE`() {
        val ctx: Context = RuntimeEnvironment.getApplication()
        val device = mockBondedDevice(MAC_A)
        val probe = AinaProtocolProbe(ctx, timeoutMs = 5_000L, mainHandler = handler)

        val resolved = arrayOfNulls<AinaDeviceInfo.ButtonProtocol>(1)
        val latch = CountDownLatch(1)
        probe.probe(device) {
            resolved[0] = it
            latch.countDown()
        }

        // Simulate the BluetoothDevice.ACTION_UUID broadcast that
        // arrives after fetchUuidsWithSdp completes. The probe filters
        // on EXTRA_DEVICE.address; deliver via the same Context the
        // probe registered against so Robolectric's ShadowApplication
        // routes correctly.
        val broadcast =
            Intent(BluetoothDevice.ACTION_UUID).apply {
                putExtra(BluetoothDevice.EXTRA_DEVICE, device)
                putExtra(
                    BluetoothDevice.EXTRA_UUID,
                    arrayOf<ParcelUuid>(
                        ParcelUuid(AinaBleReader.SERVICE_UUID),
                    ),
                )
            }
        ctx.sendBroadcast(broadcast)
        // Drain main looper so the broadcast is dispatched
        // synchronously to our registered receiver.
        org.robolectric.shadows.ShadowLooper.runUiThreadTasksIncludingDelayedTasks()

        assertTrue("probe should have resolved", latch.await(2, TimeUnit.SECONDS))
        assertEquals(AinaDeviceInfo.ButtonProtocol.BLE, resolved[0])
    }

    @Test
    fun `timeout with no V2 vendor service resolves to SPP`() {
        val ctx: Context = RuntimeEnvironment.getApplication()
        val device = mockBondedDevice(MAC_B)
        val probe = AinaProtocolProbe(ctx, timeoutMs = 50L, mainHandler = handler)

        val resolved = arrayOfNulls<AinaDeviceInfo.ButtonProtocol>(1)
        val latch = CountDownLatch(1)
        probe.probe(device) {
            resolved[0] = it
            latch.countDown()
        }

        // Advance the looper past the timeout — no ACTION_UUID, no
        // GATT discovery callback. Robolectric's idleMainLooper
        // executes the postDelayed timeout task.
        org.robolectric.shadows.ShadowLooper.idleMainLooper(100, TimeUnit.MILLISECONDS)

        assertTrue("probe should have timed out", latch.await(2, TimeUnit.SECONDS))
        assertEquals(AinaDeviceInfo.ButtonProtocol.SPP, resolved[0])
    }

    @Test
    fun `concurrent probes for same MAC coalesce into one in-flight`() {
        val ctx: Context = RuntimeEnvironment.getApplication()
        val device = mockBondedDevice(MAC_C)
        val probe = AinaProtocolProbe(ctx, timeoutMs = 5_000L, mainHandler = handler)

        val resultA = arrayOfNulls<AinaDeviceInfo.ButtonProtocol>(1)
        val resultB = arrayOfNulls<AinaDeviceInfo.ButtonProtocol>(1)
        val latch = CountDownLatch(2)
        probe.probe(device) {
            resultA[0] = it
            latch.countDown()
        }
        // Second probe for the SAME MAC: should attach to the existing
        // in-flight rather than spawn a second SDP fetch / GATT connect.
        probe.probe(device) {
            resultB[0] = it
            latch.countDown()
        }

        // Both callbacks should fire from the single SDP broadcast.
        val broadcast =
            Intent(BluetoothDevice.ACTION_UUID).apply {
                putExtra(BluetoothDevice.EXTRA_DEVICE, device)
                putExtra(
                    BluetoothDevice.EXTRA_UUID,
                    arrayOf<ParcelUuid>(ParcelUuid(AinaBleReader.SERVICE_UUID)),
                )
            }
        ctx.sendBroadcast(broadcast)
        org.robolectric.shadows.ShadowLooper.runUiThreadTasksIncludingDelayedTasks()

        assertTrue("both callbacks should fire", latch.await(2, TimeUnit.SECONDS))
        assertEquals(AinaDeviceInfo.ButtonProtocol.BLE, resultA[0])
        assertEquals(AinaDeviceInfo.ButtonProtocol.BLE, resultB[0])

        // fetchUuidsWithSdp should have been called exactly once across
        // the two probe() calls — that's the single-flight contract.
        verify(exactly = 1) { device.fetchUuidsWithSdp() }
    }

    @Test
    fun `probeOpportunistically no-ops for non-APTT device`() {
        val ctx: Context = RuntimeEnvironment.getApplication()
        val device = mockBondedDevice(MAC_A, name = "WH-1000XM4")
        val probe = AinaProtocolProbe(ctx, timeoutMs = 50L, mainHandler = handler)

        probe.probeOpportunistically(device, { null }, { _, _ -> })

        // No SDP fetch should have been kicked off — classifier name
        // gate rejected the device.
        verify(exactly = 0) { device.fetchUuidsWithSdp() }
    }

    @Test
    fun `probeOpportunistically no-ops when override already persisted`() {
        val ctx: Context = RuntimeEnvironment.getApplication()
        val device = mockBondedDevice(MAC_A, name = "APTT V2")
        val probe = AinaProtocolProbe(ctx, timeoutMs = 50L, mainHandler = handler)

        // Persisted override of any value should short-circuit before
        // we touch the radio. The override-clear path (on BOND_NONE)
        // re-opens the gate for legitimate re-probes after a re-pair.
        probe.probeOpportunistically(device, { "v2" }, { _, _ -> })

        verify(exactly = 0) { device.fetchUuidsWithSdp() }
    }

    @Test
    fun `probeOpportunistically persists v2 on BLE result`() {
        val ctx: Context = RuntimeEnvironment.getApplication()
        val device = mockBondedDevice(MAC_A, name = "APTT V2")
        val probe = AinaProtocolProbe(ctx, timeoutMs = 5_000L, mainHandler = handler)

        val persistCalls = mutableListOf<Pair<String, String>>()
        probe.probeOpportunistically(
            device,
            { null },
            { mac, kind -> persistCalls.add(mac to kind) },
        )

        // Trigger BLE resolution.
        val broadcast =
            Intent(BluetoothDevice.ACTION_UUID).apply {
                putExtra(BluetoothDevice.EXTRA_DEVICE, device)
                putExtra(
                    BluetoothDevice.EXTRA_UUID,
                    arrayOf<ParcelUuid>(ParcelUuid(AinaBleReader.SERVICE_UUID)),
                )
            }
        ctx.sendBroadcast(broadcast)
        org.robolectric.shadows.ShadowLooper.runUiThreadTasksIncludingDelayedTasks()

        assertEquals(1, persistCalls.size)
        assertEquals(MAC_A, persistCalls[0].first)
        assertEquals("v2", persistCalls[0].second)
    }

    @Test
    fun `redactMac obscures middle octets`() {
        assertEquals(
            "AA:XX:XX:XX:XX:FF",
            AinaProtocolProbe.redactMac("AA:11:22:33:44:FF"),
        )
        // Bad input shape collapses to a fully-redacted placeholder
        // rather than leaking partial bytes.
        assertEquals("??:XX:XX:XX:XX:??", AinaProtocolProbe.redactMac("not-a-mac"))
        assertEquals("??:XX:XX:XX:XX:??", AinaProtocolProbe.redactMac(null))
    }

    private fun mockBondedDevice(
        mac: String,
        name: String = "APTT316782",
    ): BluetoothDevice {
        val dev = mockk<BluetoothDevice>(relaxed = true)
        every { dev.address } returns mac
        every { dev.name } returns name
        every { dev.uuids } returns null
        every { dev.fetchUuidsWithSdp() } returns true
        // connectGatt returns null is fine — the BLE path doesn't have
        // to succeed for the SDP path to resolve, and the timeout test
        // explicitly doesn't deliver any GATT callback.
        every { dev.connectGatt(any(), any(), any(), any<Int>()) } returns null
        return dev
    }

    companion object {
        private const val MAC_A = "AA:BB:CC:DD:EE:01"
        private const val MAC_B = "AA:BB:CC:DD:EE:02"
        private const val MAC_C = "AA:BB:CC:DD:EE:03"
    }
}
