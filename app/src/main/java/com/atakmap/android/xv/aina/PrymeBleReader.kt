package com.atakmap.android.xv.aina

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.UUID

/**
 * Custom BLE GATT button reader for Pryme BT-PTT-Z (and similar
 * devices that use the same vendor service).
 *
 * The Pryme PTT button does NOT pair as a generic HID-over-GATT
 * input device — Android's HidHostService never claims it (verified
 * via `dumpsys bluetooth_manager`: only proper HoGP devices like
 * MX Master appear in `mInputDevices`; PTT-Z is bonded but absent).
 * Without HID registration the OS won't dispatch keycodes to a
 * MediaSession. The companion app must connect the GATT link and
 * subscribe to the vendor button-state characteristic itself, the
 * same way [AinaBleReader] does for AINA V2.
 *
 * Service UUID `00420000-8f59-4420-870d-84f3b617e493` was identified
 * by inspecting the production VX plugin (com.atakmap.android.gbr.vx.plugin)
 * — the only custom UUID family in its dex. The two child characteristics
 * `00420001` and `00420002` are subscribed for notifications; raw
 * bytes are logged at INFO so we can iterate the parser once we
 * see real wire-format data.
 *
 * Shared retry strategy with AinaBleReader: a few quick direct retries
 * for transient status=133, then auto-connect for long-term recovery
 * after the device sleeps / leaves range.
 */
@SuppressLint("MissingPermission")
class PrymeBleReader(
    private val context: Context,
    private val onEvent: (AinaButton, isDown: Boolean) -> Unit,
    private val onConnectionState: (Boolean) -> Unit = {},
) {
    private val handler = Handler(Looper.getMainLooper())
    private var gatt: BluetoothGatt? = null
    private var targetDevice: BluetoothDevice? = null

    @Volatile
    private var intentionalDisconnect: Boolean = false

    private var directRetryCount: Int = 0
    private var autoConnectArmed: Boolean = false

    // Last raw mask seen on the button characteristic; used to derive
    // edge-triggered AinaButton.PTT down/up events without the Pryme
    // sending separate down/up packets. Initialized to 0 (no buttons
    // pressed); first non-zero notification = PTT down, next 0 = up.
    @Volatile
    private var lastMask: Int = 0

    private val retryRunnable =
        Runnable {
            val device = targetDevice ?: return@Runnable
            if (intentionalDisconnect) return@Runnable
            connectInternal(device, useAutoConnect = autoConnectArmed)
        }

    fun connect(device: BluetoothDevice) {
        disconnect()
        intentionalDisconnect = false
        targetDevice = device
        directRetryCount = 0
        autoConnectArmed = false
        connectInternal(device, useAutoConnect = false)
    }

    private fun connectInternal(
        device: BluetoothDevice,
        useAutoConnect: Boolean,
    ) {
        lastMask = 0
        Log.i(
            TAG,
            "Connecting to Pryme at ${device.address} " +
                "(autoConnect=$useAutoConnect, attempt=${directRetryCount + 1})",
        )
        gatt = device.connectGatt(context, useAutoConnect, callback, BluetoothDevice.TRANSPORT_LE)
    }

    fun disconnect() {
        intentionalDisconnect = true
        targetDevice = null
        handler.removeCallbacks(retryRunnable)
        closeGatt()
        onConnectionState(false)
    }

    private fun closeGatt() {
        gatt?.let {
            try {
                it.disconnect()
            } catch (_: Throwable) {
            }
            try {
                it.close()
            } catch (_: Throwable) {
            }
        }
        gatt = null
    }

    private fun scheduleReconnect(reason: String) {
        if (intentionalDisconnect) return
        val device = targetDevice ?: return
        closeGatt()
        if (directRetryCount >= MAX_DIRECT_RETRIES) {
            autoConnectArmed = true
        }
        val delayMs =
            if (autoConnectArmed) {
                AUTO_CONNECT_DELAY_MS
            } else {
                val base = INITIAL_RETRY_DELAY_MS shl directRetryCount.coerceAtMost(4)
                base.coerceAtMost(MAX_DIRECT_RETRY_DELAY_MS)
            }
        directRetryCount++
        Log.i(
            TAG,
            "Scheduling reconnect to ${device.address} in ${delayMs}ms " +
                "($reason, autoConnect=$autoConnectArmed)",
        )
        handler.removeCallbacks(retryRunnable)
        handler.postDelayed(retryRunnable, delayMs)
    }

    private val callback =
        object : BluetoothGattCallback() {
            override fun onConnectionStateChange(
                g: BluetoothGatt,
                status: Int,
                newState: Int,
            ) {
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        Log.i(TAG, "Connected (status=$status), discovering services")
                        directRetryCount = 0
                        autoConnectArmed = false
                        onConnectionState(true)
                        g.discoverServices()
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        Log.i(TAG, "Disconnected (status=$status)")
                        lastMask = 0
                        onConnectionState(false)
                        if (!intentionalDisconnect) {
                            scheduleReconnect("status=$status")
                        }
                    }
                }
            }

            override fun onServicesDiscovered(
                g: BluetoothGatt,
                status: Int,
            ) {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    Log.w(TAG, "Service discovery failed: $status")
                    return
                }
                // One-time enumeration of every service + characteristic
                // so we can confirm the vendor service UUID matches what
                // the device actually exposes. Cheap and only fires once
                // per (re)connect.
                for (svc in g.services) {
                    Log.i(TAG, "service: ${svc.uuid}")
                    for (ch in svc.characteristics) {
                        val props = describeProps(ch.properties)
                        Log.i(TAG, "  char: ${ch.uuid} [$props]")
                    }
                }
                val service =
                    KNOWN_SERVICE_UUIDS
                        .asSequence()
                        .mapNotNull { uuid ->
                            g.getService(uuid)?.also {
                                Log.i(TAG, "matched Pryme service $uuid")
                            }
                        }.firstOrNull()
                if (service == null) {
                    Log.w(
                        TAG,
                        "no known Pryme service on device " +
                            "(tried $KNOWN_SERVICE_UUIDS) — bond may be wrong protocol",
                    )
                    return
                }
                // Subscribe to every notify-capable characteristic in
                // the service. We don't yet know which one carries the
                // button mask vs. which is config / battery / firmware,
                // so subscribe broadly and let the logged raw-byte
                // dumps tell us. Once the wire format is confirmed,
                // narrow this to the one button characteristic.
                var subscribed = 0
                for (ch in service.characteristics) {
                    if ((ch.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY) == 0) continue
                    if (!g.setCharacteristicNotification(ch, true)) {
                        Log.w(TAG, "setCharacteristicNotification(true) failed for ${ch.uuid}")
                        continue
                    }
                    val cccd = ch.getDescriptor(CCCD_UUID)
                    if (cccd == null) {
                        Log.w(TAG, "CCCD missing on ${ch.uuid} — cannot enable notifications")
                        continue
                    }
                    val enableValue = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    val ok =
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            g.writeDescriptor(cccd, enableValue) == BluetoothGatt.GATT_SUCCESS
                        } else {
                            @Suppress("DEPRECATION")
                            cccd.value = enableValue
                            @Suppress("DEPRECATION")
                            g.writeDescriptor(cccd)
                        }
                    if (ok) {
                        subscribed++
                        Log.i(TAG, "subscribed to notifications on ${ch.uuid}")
                    } else {
                        Log.w(TAG, "writeDescriptor(enable) failed for ${ch.uuid}")
                    }
                }
                if (subscribed == 0) {
                    Log.w(TAG, "no notify-capable characteristics in Pryme service — protocol mismatch")
                }
            }

            @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
            override fun onCharacteristicChanged(
                g: BluetoothGatt,
                ch: BluetoothGattCharacteristic,
            ) {
                handleNotification(ch, ch.value)
            }

            override fun onCharacteristicChanged(
                g: BluetoothGatt,
                ch: BluetoothGattCharacteristic,
                value: ByteArray,
            ) {
                handleNotification(ch, value)
            }

            private fun handleNotification(
                ch: BluetoothGattCharacteristic,
                bytes: ByteArray?,
            ) {
                if (bytes == null) return
                Log.i(TAG, "notify ${ch.uuid}: ${bytes.toHexString()}")
                if (bytes.isEmpty()) return
                // Heuristic edge-trigger: any non-zero first byte = some
                // button held; zero = all released. Single-button Pryme
                // devices only need PTT semantics, so this maps any
                // press to AinaButton.PTT down/up regardless of which
                // bit is set. Refine once the protocol is documented.
                val mask = bytes[0].toInt() and 0xFF
                val prev = lastMask
                lastMask = mask
                if (prev == 0 && mask != 0) {
                    onEvent(AinaButton.PTT, true)
                } else if (prev != 0 && mask == 0) {
                    onEvent(AinaButton.PTT, false)
                }
            }
        }

    private fun describeProps(p: Int): String {
        val parts = mutableListOf<String>()
        if (p and BluetoothGattCharacteristic.PROPERTY_READ != 0) parts += "READ"
        if (p and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) parts += "WRITE"
        if (p and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) parts += "WRITE_NR"
        if (p and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) parts += "NOTIFY"
        if (p and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) parts += "INDICATE"
        return parts.joinToString(",")
    }

    private fun ByteArray.toHexString(): String = joinToString(" ") { "%02x".format(it.toInt() and 0xff) }

    companion object {
        private const val TAG = "XvPrymeBle"

        private const val MAX_DIRECT_RETRIES = 4
        private const val INITIAL_RETRY_DELAY_MS = 2_000L
        private const val MAX_DIRECT_RETRY_DELAY_MS = 16_000L
        private const val AUTO_CONNECT_DELAY_MS = 5_000L

        // Service UUIDs we know carry Pryme button data, tried in
        // order. The HM-10 / TI CC2540 transparent UART service
        // (0000ffe0-…) is the actual one used by Pryme BT-PTT-Z
        // hardware as verified by on-device service discovery: their
        // unit advertises only Generic Access (1800), Generic Attribute
        // (1801), and ffe0 with characteristic ffe1 [WRITE,NOTIFY].
        // The 00420000-8f59-… UUID was extracted from VX 2.1.0's dex
        // but doesn't match the units we have on hand — kept as a
        // fallback in case a different Pryme firmware revision uses
        // it. Add new UUIDs to this list as new hardware is tested.
        private val HM10_SERVICE_UUID: UUID = UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb")
        private val PRYME_VENDOR_UUID: UUID = UUID.fromString("00420000-8f59-4420-870d-84f3b617e493")
        private val KNOWN_SERVICE_UUIDS: List<UUID> = listOf(HM10_SERVICE_UUID, PRYME_VENDOR_UUID)
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}
