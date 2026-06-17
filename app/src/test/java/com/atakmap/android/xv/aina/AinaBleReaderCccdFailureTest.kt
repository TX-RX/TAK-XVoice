package com.atakmap.android.xv.aina

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothProfile
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * Quick-win M5 coverage for [AinaBleReader].
 *
 * Previously, a non-zero status on the CCCD descriptor write set
 * `pendingConfigReadModifyWrite = false` and returned — but
 * `setCharacteristicNotification(true)` had already been applied
 * above, so the device was left half-configured: notifications might
 * or might not flow, and there was no retry. The fix: schedule a
 * full reconnect (close GATT + reopen) so the next attempt starts
 * from a clean baseline.
 *
 * The test drives the GATT callback directly via the captured
 * connector reference, mocks just enough of the service / descriptor
 * tree to reach the CCCD write, then injects status=133 on
 * onDescriptorWrite. Asserts:
 *  - gatt.disconnect() is called as part of closeGatt(),
 *  - a fresh connectGatt is invoked through the connector after the
 *    BLE-stack settle delay.
 */
@RunWith(RobolectricTestRunner::class)
class AinaBleReaderCccdFailureTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `CCCD write failure schedules a full reconnect`() {
        val connectCount = AtomicInteger(0)
        val capturedCallbacks = mutableListOf<BluetoothGattCallback>()
        val firstGatt = mockBluetoothGatt(withCccd = true)
        val secondGatt = mockBluetoothGatt(withCccd = true)
        val gatts = listOf(firstGatt, secondGatt)

        val connector =
            AinaBleReader.GattConnector { _, _, _, callback ->
                val idx = connectCount.getAndIncrement()
                capturedCallbacks.add(callback)
                gatts.getOrNull(idx) ?: gatts.last()
            }

        val device = mockBluetoothDevice("AA:BB:CC:DD:EE:FF")
        val reader =
            AinaBleReader(
                context = context,
                onEvent = { _, _ -> },
                onConnectionState = { /* ignored for this assertion */ },
                gattConnector = connector,
            )

        reader.connect(device)
        // connect() calls disconnect() first which stamps the
        // BLE-stack-settle close timestamp, so the first
        // connectGatt is deferred onto the main looper. Robolectric
        // doesn't auto-advance — flush so the runnable fires before
        // we assert on the connect count.
        shadowOf(android.os.Looper.getMainLooper())
            .idleFor(java.time.Duration.ofSeconds(30))
        assertTrue(
            "expected first connectGatt to have fired (got ${connectCount.get()})",
            connectCount.get() >= 1,
        )
        val callback = capturedCallbacks.first()

        // Drive: STATE_CONNECTED → services discovered → CCCD write
        // fires (mocked), then we inject the failed onDescriptorWrite
        // status. The mocked gatt's writeDescriptor is a no-op, so
        // the reader's progression is fully controlled by us.
        callback.onConnectionStateChange(
            firstGatt,
            BluetoothGatt.GATT_SUCCESS,
            BluetoothProfile.STATE_CONNECTED,
        )
        callback.onServicesDiscovered(firstGatt, BluetoothGatt.GATT_SUCCESS)

        // CCCD write returns status=133 (GATT_ERROR). Pre-fix this
        // would have logged and silently exited with a half-
        // configured device. Post-fix it must schedule a reconnect.
        val cccdDescriptor =
            firstGatt
                .getService(AinaBleReader.SERVICE_UUID)!!
                .getCharacteristic(AinaBleReader.BUTTON_CHAR_UUID)!!
                .getDescriptor(AinaBleReader.CCCD_UUID)!!
        callback.onDescriptorWrite(firstGatt, cccdDescriptor, 133)

        // closeGatt() must have torn down the first handle.
        verify(atLeast = 1) { firstGatt.disconnect() }
        verify(atLeast = 1) { firstGatt.close() }

        // Flush the main looper to advance any postDelayed reconnect.
        // The reader's first reconnect delay is 2000 ms (initial
        // retry) and then 120 ms BLE-stack settle layered over it —
        // idle() walks them all.
        shadowOf(android.os.Looper.getMainLooper())
            .idleFor(java.time.Duration.ofSeconds(30))

        // Second connectGatt must have been invoked → fresh GATT
        // handle in play.
        assertTrue(
            "expected a fresh connectGatt after CCCD failure (got ${connectCount.get()})",
            connectCount.get() >= 2,
        )
        // Sanity: the second connector call should have produced the
        // second mock gatt — proves a new handle, not a re-use of
        // the wedged first one.
        assertSame(
            "second connectGatt should hand out a fresh BluetoothGatt instance",
            secondGatt,
            gatts[1],
        )

        reader.disconnect()
    }

    private fun mockBluetoothDevice(mac: String): BluetoothDevice {
        val dev = mockk<BluetoothDevice>(relaxed = true)
        every { dev.address } returns mac
        every { dev.name } returns "APTT V2 (mock)"
        return dev
    }

    private fun mockBluetoothGatt(withCccd: Boolean): BluetoothGatt {
        val cccdDescriptor = mockk<BluetoothGattDescriptor>(relaxed = true)
        every { cccdDescriptor.uuid } returns AinaBleReader.CCCD_UUID

        val buttonCh = mockk<BluetoothGattCharacteristic>(relaxed = true)
        every { buttonCh.uuid } returns AinaBleReader.BUTTON_CHAR_UUID
        every { buttonCh.getDescriptor(AinaBleReader.CCCD_UUID) } returns
            if (withCccd) cccdDescriptor else null

        val service = mockk<BluetoothGattService>(relaxed = true)
        every { service.getCharacteristic(AinaBleReader.BUTTON_CHAR_UUID) } returns buttonCh

        val gatt = mockk<BluetoothGatt>(relaxed = true)
        every { gatt.getService(AinaBleReader.SERVICE_UUID) } returns service
        every { gatt.setCharacteristicNotification(buttonCh, true) } returns true
        return gatt
    }
}
