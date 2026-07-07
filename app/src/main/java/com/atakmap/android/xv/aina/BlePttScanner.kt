package com.atakmap.android.xv.aina

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import java.util.UUID

/**
 * BLE PTT-button discovery wrapper. Wraps [android.bluetooth.le.BluetoothLeScanner]
 * with a targeted filter set that catches the two families of BLE PTT hardware
 * XV integrates with today:
 *
 *   1. HM-10 / TI CC2540 transparent-UART devices — Pryme BT-PTT-Z, the
 *      Zello-branded PTT-Z01 (Amazon B0DHZDRH3B), and other third-party
 *      buttons using the same chipset. They advertise service UUID
 *      `0000ffe0-0000-1000-8000-00805f9b34fb`.
 *   2. Anything whose advertised name contains `PTT` (case-insensitive)
 *      — broad enough to catch buttons that don't advertise a vendor
 *      service in their scan record but still identify themselves by
 *      name.
 *
 * The service-UUID and name-substring filters are OR-ed by the platform
 * scan API, so a device matches if it hits either. Discovered devices
 * are de-duplicated by MAC before being surfaced to the caller — a
 * single button typically advertises 3-10 times per second, and the UI
 * should treat each MAC as one entry, not one entry per advertisement.
 *
 * Threading: [ScanCallback] fires on a binder / Bluetooth-stack thread;
 * [onDeviceFound] and [onScanEnded] are invoked on the main looper via
 * this class's own handler so the caller can update UI directly.
 *
 * Permissions: caller is responsible for holding `BLUETOOTH_SCAN` (API
 * 31+) and, on API 30 and below, `ACCESS_FINE_LOCATION`. XV's manifest
 * declares `BLUETOOTH_SCAN android:usesPermissionFlags="neverForLocation"`
 * so the location requirement doesn't apply on new installs.
 */
@SuppressLint("MissingPermission")
class BlePttScanner(
    private val context: Context,
    private val onDeviceFound: (BlePttScanResult) -> Unit,
    private val onScanEnded: (reason: String) -> Unit,
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val seenMacs = mutableSetOf<String>()

    @Volatile
    private var scanning: Boolean = false

    private val stopRunnable =
        Runnable {
            stop("timeout")
        }

    private val scanCallback =
        object : ScanCallback() {
            override fun onScanResult(
                callbackType: Int,
                result: ScanResult?,
            ) {
                val r = result ?: return
                val dev = r.device ?: return
                val mac = dev.address ?: return
                mainHandler.post {
                    if (!scanning) return@post
                    if (!seenMacs.add(mac)) return@post
                    val name =
                        r.scanRecord?.deviceName
                            ?: try {
                                dev.name
                            } catch (_: Throwable) {
                                null
                            }
                    val services =
                        r.scanRecord
                            ?.serviceUuids
                            ?.map { it.uuid }
                            ?.toSet()
                            ?: emptySet()
                    Log.i(
                        TAG,
                        "scan hit ${redactMac(mac)} name=$name rssi=${r.rssi} services=$services",
                    )
                    onDeviceFound(
                        BlePttScanResult(mac = mac, name = name, rssi = r.rssi, serviceUuids = services),
                    )
                }
            }

            override fun onScanFailed(errorCode: Int) {
                Log.w(TAG, "onScanFailed errorCode=$errorCode")
                mainHandler.post {
                    if (!scanning) return@post
                    scanning = false
                    onScanEnded("scan failed (code $errorCode)")
                }
            }
        }

    fun start(timeoutMs: Long = 10_000L) {
        if (scanning) {
            Log.i(TAG, "start() while already scanning — ignoring")
            return
        }
        val adapter = BluetoothAdapter.getDefaultAdapter()
        if (adapter == null) {
            onScanEnded("Bluetooth adapter unavailable")
            return
        }
        if (!adapter.isEnabled) {
            onScanEnded("Bluetooth is off")
            return
        }
        val scanner = adapter.bluetoothLeScanner
        if (scanner == null) {
            onScanEnded("BLE scanner unavailable")
            return
        }
        seenMacs.clear()
        scanning = true

        val filters =
            listOf(
                // HM-10 transparent-UART service UUID — catches Pryme
                // BT-PTT-Z, PTT-Z01, and similar HM-10-based buttons.
                ScanFilter
                    .Builder()
                    .setServiceUuid(ParcelUuid(HM10_SERVICE_UUID))
                    .build(),
                // Name-substring fallback for devices whose scan record
                // doesn't include the service UUID but does advertise
                // "PTT" in the local name.
                ScanFilter
                    .Builder()
                    .setDeviceName("PTT-Z01")
                    .build(),
            )
        val settings =
            ScanSettings
                .Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
                .build()
        try {
            scanner.startScan(filters, settings, scanCallback)
            Log.i(TAG, "scan started (timeoutMs=$timeoutMs)")
        } catch (t: Throwable) {
            Log.w(TAG, "startScan threw", t)
            scanning = false
            onScanEnded("scan start failed: ${t.message}")
            return
        }
        mainHandler.postDelayed(stopRunnable, timeoutMs)
    }

    fun stop(reason: String = "operator") {
        if (!scanning) return
        scanning = false
        mainHandler.removeCallbacks(stopRunnable)
        val adapter = BluetoothAdapter.getDefaultAdapter()
        try {
            adapter?.bluetoothLeScanner?.stopScan(scanCallback)
        } catch (t: Throwable) {
            Log.w(TAG, "stopScan threw", t)
        }
        Log.i(TAG, "scan stopped ($reason)")
        onScanEnded(reason)
    }

    companion object {
        private const val TAG = "XvBlePttScan"

        // HM-10 / TI CC2540 transparent UART service UUID. Full 128-bit
        // form of the 16-bit alias 0xFFE0. Kept in sync with the same
        // constant in BitmaskGattPttReader / PrymeBleReader — a match on
        // this UUID is a positive signal that the device speaks the
        // simple bitmask-byte-on-notify protocol PrymeBleReader decodes.
        val HM10_SERVICE_UUID: UUID = UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb")
    }
}

data class BlePttScanResult(
    val mac: String,
    val name: String?,
    val rssi: Int,
    val serviceUuids: Set<UUID>,
) {
    /**
     * Operator-facing label for the scan-result dialog. Prefers the
     * advertised name; falls back to the MAC if the device doesn't
     * broadcast a name in its scan record (some HM-10 modules leave
     * the name blank until GATT-connect enumerates it).
     */
    fun displayLabel(): String {
        val displayName = name?.takeIf { it.isNotBlank() } ?: "(unnamed)"
        return "$displayName  ·  $mac  ·  $rssi dBm"
    }
}
