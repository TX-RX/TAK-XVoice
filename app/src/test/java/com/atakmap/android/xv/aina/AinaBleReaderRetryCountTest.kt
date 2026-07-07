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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Quick-win M6 coverage for [AinaBleReader].
 *
 * The counter was previously only reset in
 * `onConnectionStateChange(STATE_CONNECTED)`. If a drop occurred
 * during the `pendingConfigReadModifyWrite` window (after services
 * discovered but before the CCCD/CONFIG handshake completed) the
 * counter kept growing across retries even though the previous
 * attempt had made measurable progress past STATE_CONNECTED — the
 * BLE stack would prematurely arm autoConnect=true mode. The fix:
 * reset the counter again on `onServicesDiscovered` success so any
 * attempt that gets that far is treated as "real progress."
 *
 * This test bumps the counter via repeated STATE_DISCONNECTED
 * callbacks, then verifies `onServicesDiscovered(SUCCESS)` zeros it
 * out. It also asserts the same call clears `autoConnectArmed` so a
 * mid-reconnect-cycle service-discovery success undoes a premature
 * autoConnect arm.
 */
@RunWith(RobolectricTestRunner::class)
class AinaBleReaderRetryCountTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `onServicesDiscovered success resets directRetryCount and autoConnectArmed`() {
        val gatts = listOf(mockGatt(), mockGatt(), mockGatt(), mockGatt(), mockGatt(), mockGatt())
        var idx = 0
        var lastCallback: BluetoothGattCallback? = null
        val connector =
            AinaBleReader.GattConnector { _, _, _, callback ->
                lastCallback = callback
                gatts.getOrElse(idx++) { gatts.last() }
            }

        val reader =
            AinaBleReader(
                context = context,
                onEvent = { _, _ -> },
                onConnectionState = { /* ignored */ },
                gattConnector = connector,
            )
        reader.connect(mockDevice())
        // connect() calls disconnect() first, stamping the BLE-stack
        // settle timestamp — the first connectGatt is deferred onto
        // the main looper. Robolectric doesn't auto-advance, so
        // flush before reaching for the callback reference.
        org.robolectric.Shadows
            .shadowOf(android.os.Looper.getMainLooper())
            .idleFor(java.time.Duration.ofSeconds(30))
        val cb = checkNotNull(lastCallback)

        // Burn through the direct-retry budget without ever firing a
        // STATE_CONNECTED. Each call to scheduleReconnect() in
        // STATE_DISCONNECTED increments directRetryCount. After
        // MAX_DIRECT_RETRIES (4 in production) the reader arms
        // autoConnect mode.
        repeat(5) {
            cb.onConnectionStateChange(
                gatts[0],
                // status = 133 (GATT_ERROR)
                133,
                BluetoothProfile.STATE_DISCONNECTED,
            )
        }
        // Sanity: the counter actually grew.
        assertNotEquals(
            "test pre-condition: counter must have grown past 0 — got 0",
            0,
            reader.directRetryCountForTest(),
        )
        // And autoConnect should now be armed (the >=MAX_DIRECT_RETRIES branch).
        assertEquals(
            "test pre-condition: autoConnectArmed must be true after the budget is burnt",
            true,
            reader.autoConnectArmedForTest(),
        )

        // Drive a STATE_CONNECTED followed by a successful
        // onServicesDiscovered. STATE_CONNECTED itself resets the
        // counter, so we re-bump it AFTER CONNECTED to isolate the
        // M6 fix — that's the field-occurring shape: counter ticked
        // because of stalls AFTER CONNECTED but BEFORE services
        // settled.
        cb.onConnectionStateChange(
            gatts[0],
            BluetoothGatt.GATT_SUCCESS,
            BluetoothProfile.STATE_CONNECTED,
        )
        assertEquals(
            "STATE_CONNECTED should also reset (legacy invariant)",
            0,
            reader.directRetryCountForTest(),
        )

        // Re-bump to simulate "STATE_CONNECTED reset already
        // happened, now the counter grew again from a mid-discovery
        // disconnect-reconnect cycle BEFORE onServicesDiscovered
        // success." The M6 fix says onServicesDiscovered success
        // must zero this out too.
        repeat(3) {
            cb.onConnectionStateChange(
                gatts[0],
                // status = 133 (GATT_ERROR)
                133,
                BluetoothProfile.STATE_DISCONNECTED,
            )
        }
        assertNotEquals(0, reader.directRetryCountForTest())

        // Now: services discovered success → M6 fix must reset.
        cb.onConnectionStateChange(
            gatts[0],
            BluetoothGatt.GATT_SUCCESS,
            BluetoothProfile.STATE_CONNECTED,
        )
        // The CONNECTED reset above zeros it; re-bump WITHOUT
        // another CONNECTED to keep the test focused on
        // onServicesDiscovered.
        repeat(2) {
            cb.onConnectionStateChange(
                gatts[0],
                // status = 8 (CONN_TIMEOUT)
                8,
                BluetoothProfile.STATE_DISCONNECTED,
            )
        }
        assertNotEquals(0, reader.directRetryCountForTest())

        // M6 fix lives here: onServicesDiscovered(SUCCESS) resets.
        cb.onServicesDiscovered(gatts[0], BluetoothGatt.GATT_SUCCESS)
        assertEquals(
            "onServicesDiscovered(SUCCESS) must reset directRetryCount (M6 fix)",
            0,
            reader.directRetryCountForTest(),
        )
        assertFalse(
            "onServicesDiscovered(SUCCESS) must clear autoConnectArmed (M6 fix)",
            reader.autoConnectArmedForTest(),
        )

        reader.disconnect()
    }

    private fun mockDevice(): BluetoothDevice {
        val dev = mockk<BluetoothDevice>(relaxed = true)
        every { dev.address } returns "AA:BB:CC:DD:EE:FF"
        every { dev.name } returns "APTT V2 (mock)"
        return dev
    }

    private fun mockGatt(): BluetoothGatt {
        val cccd = mockk<BluetoothGattDescriptor>(relaxed = true)
        every { cccd.uuid } returns AinaBleReader.CCCD_UUID
        val ch = mockk<BluetoothGattCharacteristic>(relaxed = true)
        every { ch.uuid } returns AinaBleReader.BUTTON_CHAR_UUID
        every { ch.getDescriptor(AinaBleReader.CCCD_UUID) } returns cccd
        val svc = mockk<BluetoothGattService>(relaxed = true)
        every { svc.getCharacteristic(AinaBleReader.BUTTON_CHAR_UUID) } returns ch
        val gatt = mockk<BluetoothGatt>(relaxed = true)
        every { gatt.getService(AinaBleReader.SERVICE_UUID) } returns svc
        every { gatt.setCharacteristicNotification(ch, true) } returns true
        return gatt
    }
}
