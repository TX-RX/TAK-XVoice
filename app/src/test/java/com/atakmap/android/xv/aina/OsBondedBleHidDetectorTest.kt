package com.atakmap.android.xv.aina

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OsBondedBleHidDetectorTest {

    private fun mockBondedDevice(
        mac: String,
        name: String,
    ): BluetoothDevice {
        val dev = mockk<BluetoothDevice>(relaxed = true)
        every { dev.address } returns mac
        every { dev.name } returns name
        every { dev.uuids } returns null
        return dev
    }

    private fun mockAdapter(vararg bonded: BluetoothDevice): BluetoothAdapter {
        val adapter = mockk<BluetoothAdapter>()
        every { adapter.bondedDevices } returns bonded.toSet()
        return adapter
    }

    @Test
    fun `returns false for null MAC`() {
        assertFalse(OsBondedBleHidDetector.isOsBondedBleHid(null, mockAdapter()))
    }

    @Test
    fun `returns false for blank MAC`() {
        assertFalse(OsBondedBleHidDetector.isOsBondedBleHid("   ", mockAdapter()))
    }

    @Test
    fun `returns false for null adapter`() {
        assertFalse(OsBondedBleHidDetector.isOsBondedBleHid("00:11:22:33:44:55", null))
    }

    @Test
    fun `returns false for unbonded device`() {
        // Device exists in the world but is not in bondedDevices
        val adapter = mockAdapter(mockBondedDevice("AA:BB:CC:DD:EE:FF", "PTT-Z"))
        assertFalse(OsBondedBleHidDetector.isOsBondedBleHid("00:11:22:33:44:55", adapter))
    }

    @Test
    fun `returns false for bonded device that is NOT BLE_HID`() {
        // A generic AINA speakermic (not a BLE_HID puck)
        val device = mockBondedDevice("11:22:33:44:55:66", "AINA PTT Voice Responder")
        val adapter = mockAdapter(device)

        // classifyButtonProtocol will resolve this to SPP or BLE, not BLE_HID
        assertFalse(OsBondedBleHidDetector.isOsBondedBleHid("11:22:33:44:55:66", adapter))
    }

    @Test
    fun `returns false for generic media button`() {
        // A generic Amazon remote, not Pryme/HM10
        val device = mockBondedDevice("AA:BB:CC:DD:EE:FF", "Bluetooth Media Remote")
        val adapter = mockAdapter(device)

        // classifyButtonProtocol returns UNKNOWN for generic media buttons
        assertFalse(OsBondedBleHidDetector.isOsBondedBleHid("AA:BB:CC:DD:EE:FF", adapter))
    }

    @Test
    fun `returns true for bonded BLE_HID device`() {
        // A Bluetooth button
        val device = mockBondedDevice("99:88:77:66:55:44", "PTT-Z")
        val adapter = mockAdapter(device)

        // classifyButtonProtocol returns BLE_HID for PTT-Z
        assertTrue(OsBondedBleHidDetector.isOsBondedBleHid("99:88:77:66:55:44", adapter))
    }

    @Test
    fun `matches MAC case-insensitively`() {
        val device = mockBondedDevice("aa:bb:cc:dd:ee:ff", "PTT-Z")
        val adapter = mockAdapter(device)

        assertTrue(OsBondedBleHidDetector.isOsBondedBleHid("AA:BB:CC:DD:EE:FF", adapter))
    }
}
