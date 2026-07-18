package com.atakmap.android.xv.aina

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter

/**
 * Helper to detect if a given MAC address corresponds to a device that is currently bonded
 * in the Android OS Bluetooth settings, AND whose protocol is classified as [AinaDeviceInfo.ButtonProtocol.BLE_HID].
 *
 * Used to prevent pairing loops where the OS steals the GATT connection from the app.
 */
object OsBondedBleHidDetector {
    @SuppressLint("MissingPermission")
    fun isOsBondedBleHid(
        mac: String?,
        adapter: BluetoothAdapter? =
            try {
                BluetoothAdapter.getDefaultAdapter()
            } catch (_: SecurityException) {
                null
            },
    ): Boolean {
        if (mac.isNullOrBlank()) return false
        if (adapter == null) return false

        val bonded =
            try {
                adapter.bondedDevices?.find { it.address.equals(mac, ignoreCase = true) }
            } catch (_: SecurityException) {
                null
            } ?: return false

        return AinaDeviceClassifier.classifyButtonProtocol(bonded) == AinaDeviceInfo.ButtonProtocol.BLE_HID
    }
}
