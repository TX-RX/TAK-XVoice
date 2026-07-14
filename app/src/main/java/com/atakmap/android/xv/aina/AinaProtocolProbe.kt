package com.atakmap.android.xv.aina

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Parcelable
import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

// AINA V2 SDP-cache gap mitigator. The AINA APTT v18 spec does not
// publish the V2 vendor service UUID `127FACE1-…` in the Classic
// SDP record — it is only discoverable via a live BLE GATT service
// discovery. For dual-mode V2 hardware whose cache shows only the
// stock SPP/HSP/A2DP/AVRCP/HFP profiles plus the generic LE Battery
// service, [AinaDeviceClassifier.classifyButtonProtocol] falls back
// to SPP (V1 reader). The operator's V2 buttons then go silent.
//
// This probe forces a fresh SDP fetch (Classic) AND a transient BLE
// GATT service-discovery, watches both paths for the V2 vendor UUID,
// and reports back BLE / SPP. The integration in
// [XvMapComponent.connectAinaInternal] / [XvMapComponent.listBondedAinaDevices]
// persists a per-MAC override (via [XvSettings.persistAinaProtocolOverride])
// when the probe observes BLE, so subsequent classifications go
// straight to BLE without re-probing.
//
// Single-flight by MAC: concurrent [probe] calls for the same MAC
// coalesce into one in-flight probe, with all callbacks invoked on
// completion. Bounded 8-second total timeout — picked so a noisy BT
// environment (interference, retries) still resolves before the
// operator gives up on the picker; longer timeouts make the picker
// feel stuck. If neither path observes the vendor UUID within the
// budget, the probe reports SPP (the existing classifier fallback
// — no behavioral downgrade vs. the unbridged path).
//
// All BluetoothDevice / GATT calls are wrapped in try/catch
// (SecurityException) because BLUETOOTH_CONNECT can be revoked at
// runtime; on revocation we report SPP rather than crash.
@SuppressLint("MissingPermission")
class AinaProtocolProbe(
    private val context: Context,
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    private val mainHandler: Handler = Handler(Looper.getMainLooper()),
) {
    // Per-MAC reentrancy guard. Concurrent probes against the same
    // address attach their callback to the existing InFlight entry
    // rather than spawning a second SDP fetch + GATT connect.
    private val inFlight = ConcurrentHashMap<String, InFlight>()

    // MACs already probed-to-completion during this probe's lifetime.
    // Used by [probeOpportunistically] to bound picker-time work to
    // one probe per MAC per plugin run.
    private val completed = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    // Opportunistic probe used by the picker. No-op unless the device
    // is APTT-named, has no persisted override, and hasn't been probed
    // yet this lifetime. Persists a "v2" override on BLE result.
    // The picker keeps showing what the classifier reported until the
    // probe finishes — by design, since we don't want to block the
    // picker render on an 8 s probe.
    fun probeOpportunistically(
        device: BluetoothDevice,
        persistedOverride: (String) -> String?,
        persistOverride: (String, String) -> Unit,
    ) {
        if (!AinaDeviceClassifier.isAinaByName(device)) return
        val mac =
            try {
                device.address?.uppercase()
            } catch (_: SecurityException) {
                null
            } ?: return
        if (!completed.add(mac)) return
        if (persistedOverride(mac) != null) return
        probe(device) { resolved ->
            if (resolved == AinaDeviceInfo.ButtonProtocol.BLE) {
                persistOverride(mac, "v2")
                Log.i(TAG, "override persisted mac=${redactMac(mac)} protocol=BLE")
            }
        }
    }

    fun probe(
        device: BluetoothDevice,
        callback: (AinaDeviceInfo.ButtonProtocol) -> Unit,
    ) {
        val rawMac =
            try {
                device.address
            } catch (_: SecurityException) {
                null
            }
        if (rawMac.isNullOrBlank()) {
            // No address means no single-flight key and no SDP target;
            // just hand back SPP and let the caller proceed.
            callback(AinaDeviceInfo.ButtonProtocol.SPP)
            return
        }
        val mac = rawMac.uppercase()

        // Coalesce against an existing probe for the same MAC.
        val existing = inFlight[mac]
        if (existing != null) {
            existing.attach(callback)
            return
        }

        Log.i(TAG, "probe start mac=${redactMac(mac)}")
        val state = InFlight(mac, device)
        val prev = inFlight.putIfAbsent(mac, state)
        if (prev != null) {
            // Lost the put-race; attach to the winner instead.
            prev.attach(callback)
            return
        }
        state.attach(callback)
        state.start()
    }

    private fun finish(
        state: InFlight,
        proto: AinaDeviceInfo.ButtonProtocol,
        source: String,
    ) {
        if (!state.done.compareAndSet(false, true)) return
        try {
            mainHandler.removeCallbacks(state.timeoutTask)
        } catch (_: Throwable) {
            // ignore
        }
        try {
            context.unregisterReceiver(state.uuidReceiver)
        } catch (_: Throwable) {
            // ignore — receiver may not have been registered if we
            // failed mid-start
        }
        try {
            state.gatt?.disconnect()
        } catch (_: SecurityException) {
            // ignore
        } catch (_: Throwable) {
            // ignore
        }
        try {
            state.gatt?.close()
        } catch (_: SecurityException) {
            // ignore
        } catch (_: Throwable) {
            // ignore
        }
        inFlight.remove(state.mac)
        Log.i(
            TAG,
            "probe result mac=${redactMac(state.mac)} protocol=$proto source=$source",
        )
        val callbacks: List<(AinaDeviceInfo.ButtonProtocol) -> Unit>
        synchronized(state.callbacks) {
            callbacks = state.callbacks.toList()
            state.callbacks.clear()
        }
        for (cb in callbacks) {
            try {
                cb(proto)
            } catch (t: Throwable) {
                Log.w(TAG, "probe callback threw", t)
            }
        }
    }

    private inner class InFlight(
        val mac: String,
        val device: BluetoothDevice,
    ) {
        val done = AtomicBoolean(false)
        val callbacks = mutableListOf<(AinaDeviceInfo.ButtonProtocol) -> Unit>()

        @Volatile var gatt: BluetoothGatt? = null

        val timeoutTask = Runnable { finish(this, AinaDeviceInfo.ButtonProtocol.SPP, "TIMEOUT") }

        val uuidReceiver =
            object : BroadcastReceiver() {
                override fun onReceive(
                    c: Context?,
                    i: Intent?,
                ) {
                    if (done.get()) return
                    if (i?.action != BluetoothDevice.ACTION_UUID) return
                    val incoming: BluetoothDevice? =
                        i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    val incomingMac =
                        try {
                            incoming?.address
                        } catch (_: SecurityException) {
                            null
                        }
                    if (incomingMac == null || !incomingMac.equals(mac, ignoreCase = true)) return
                    val extras: Array<Parcelable>? =
                        @Suppress("DEPRECATION")
                        i.getParcelableArrayExtra(BluetoothDevice.EXTRA_UUID)
                    if (extras != null) {
                        for (p in extras) {
                            val pu = p as? android.os.ParcelUuid ?: continue
                            if (pu.uuid == AinaBleReader.SERVICE_UUID) {
                                finish(this@InFlight, AinaDeviceInfo.ButtonProtocol.BLE, "ACTION_UUID")
                                return
                            }
                        }
                    }
                }
            }

        val gattCallback =
            object : BluetoothGattCallback() {
                override fun onConnectionStateChange(
                    g: BluetoothGatt?,
                    status: Int,
                    newState: Int,
                ) {
                    if (done.get()) return
                    if (newState == BluetoothProfile.STATE_CONNECTED) {
                        try {
                            g?.discoverServices()
                        } catch (_: SecurityException) {
                            // ignore — timeout will close us out
                        } catch (_: Throwable) {
                            // ignore
                        }
                    }
                }

                override fun onServicesDiscovered(
                    g: BluetoothGatt?,
                    status: Int,
                ) {
                    if (done.get()) return
                    val services =
                        try {
                            g?.services
                        } catch (_: SecurityException) {
                            null
                        } catch (_: Throwable) {
                            null
                        }
                            ?: return
                    for (s in services) {
                        if (s.uuid == AinaBleReader.SERVICE_UUID) {
                            finish(this@InFlight, AinaDeviceInfo.ButtonProtocol.BLE, "GATT_DISCOVERY")
                            return
                        }
                    }
                    // Discovery returned without our vendor service —
                    // wait for the timeout to expire so the parallel SDP
                    // path still has a chance to land. We don't close
                    // here; finish() does the cleanup.
                }
            }

        fun attach(cb: (AinaDeviceInfo.ButtonProtocol) -> Unit) {
            synchronized(callbacks) {
                if (done.get()) {
                    // Probe finished between the inFlight lookup and
                    // attach — invoke immediately with SPP as a safe
                    // default. This race is rare (~µs window) and
                    // self-corrects on the next probe.
                    cb(AinaDeviceInfo.ButtonProtocol.SPP)
                    return
                }
                callbacks.add(cb)
            }
        }

        fun start() {
            // Register UUID receiver BEFORE fetchUuidsWithSdp so we
            // don't miss the broadcast on fast OEM stacks. We use
            // RECEIVER_NOT_EXPORTED on API 33+ — the ACTION_UUID
            // broadcast is system-fired so the unexported flag is
            // safe.
            try {
                val filter = IntentFilter(BluetoothDevice.ACTION_UUID)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    context.registerReceiver(uuidReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
                } else {
                    @Suppress("UnspecifiedRegisterReceiverFlag")
                    context.registerReceiver(uuidReceiver, filter)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "probe registerReceiver failed mac=${redactMac(mac)}", t)
                // Continue anyway — GATT path can still resolve.
            }

            // Kick the Classic SDP refresh.
            try {
                device.fetchUuidsWithSdp()
            } catch (_: SecurityException) {
                // ignore — GATT path may still succeed
            } catch (t: Throwable) {
                Log.w(TAG, "probe fetchUuidsWithSdp threw mac=${redactMac(mac)}", t)
            }

            // Open a transient BLE GATT connection for service
            // discovery in parallel. autoConnect=false → direct
            // connect (fast); TRANSPORT_LE so we don't accidentally
            // pick up a Classic ACL.
            try {
                gatt =
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        device.connectGatt(
                            context,
                            false,
                            gattCallback,
                            BluetoothDevice.TRANSPORT_LE,
                        )
                    } else {
                        device.connectGatt(context, false, gattCallback)
                    }
            } catch (_: SecurityException) {
                // ignore — SDP path may still succeed
            } catch (t: Throwable) {
                Log.w(TAG, "probe connectGatt threw mac=${redactMac(mac)}", t)
            }

            // Bounded timeout — if neither path observes the V2
            // service in this window, report SPP and clean up.
            mainHandler.postDelayed(timeoutTask, timeoutMs)
        }
    }

    companion object {
        private const val TAG = "XvAinaProbe"

        // 8 seconds — calibrated against AINA V2 hardware on a
        // Surface Duo (typical SDP refresh: 1.5–3 s; GATT service
        // discovery: 0.5–2 s after connect). The 8 s ceiling gives
        // headroom for noisy 2.4 GHz environments without making the
        // picker feel stuck. Field event 2026-06-20/21 will validate.
        const val DEFAULT_TIMEOUT_MS: Long = 8_000L

        // Logs the first and last octet of a MAC, redacting the
        // middle. e.g. "AA:11:22:33:44:FF" → "AA:XX:XX:XX:XX:FF".
        // Per Agent E's quick-wins: don't dump full MACs to logcat.
        fun redactMac(mac: String?): String {
            if (mac.isNullOrBlank()) return "??:XX:XX:XX:XX:??"
            val parts = mac.split(":")
            if (parts.size != 6) return "??:XX:XX:XX:XX:??"
            val first = parts.first()
            val last = parts.last()
            return "$first:XX:XX:XX:XX:$last"
        }
    }
}
