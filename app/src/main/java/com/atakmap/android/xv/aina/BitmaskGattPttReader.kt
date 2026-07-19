@file:android.annotation.SuppressLint("WrongConstant")

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
 * Reusable BLE GATT plumbing for PTT buttons that expose their state
 * as a bitmask on one or more notify characteristics inside a vendor
 * service. Handles:
 *
 *   - `connectGatt` with a bounded direct-retry burst then a long-lived
 *     `autoConnect` for out-of-range recovery.
 *   - Service discovery, matching against a list of candidate service
 *     UUIDs (so a single reader class covers hardware revisions that
 *     differ only in advertised UUID).
 *   - CCCD subscription on every notify-capable characteristic in the
 *     matched service (broad subscription — the vendor decides which
 *     characteristic actually carries button state; extras are harmless).
 *   - Edge-triggered PER-BUTTON down/up events derived from bitmask
 *     transitions between successive notifications, so a device that
 *     only reports "current mask" (no separate down/up packets) works
 *     transparently.
 *
 * Instances are configured by [Config] — each supported button family
 * (Pryme BT-PTT-Z, AINA V2, Zello-convention BLE, …) supplies its own
 * candidate service UUIDs, log tag, and mask-decode function. The
 * plumbing itself carries no vendor knowledge.
 *
 * Threading: all GATT callbacks arrive on the Bluetooth binder threads
 * and are handed off to a main-looper Handler for retry scheduling.
 * `onEvent` and `onConnectionState` are invoked from whichever thread
 * delivered the underlying GATT event — call sites that touch UI must
 * marshal to the UI thread themselves. This mirrors [AinaBleReader]'s
 * behavior so the existing `VoicePlant` wiring can bind either reader
 * interchangeably.
 */
@SuppressLint("MissingPermission")
open class BitmaskGattPttReader(
    private val context: Context,
    private val config: Config,
    private val onEvent: (AinaButton, isDown: Boolean) -> Unit,
    private val onConnectionState: (Boolean) -> Unit = {},
) {
    /**
     * Static per-vendor configuration passed to the reader at
     * construction. Everything vendor-specific lives here so the base
     * class stays generic.
     *
     * @param tag Log tag (grep-friendly per-vendor prefix, e.g.
     *   `XvPrymeBle` or `XvZelloBle`).
     * @param candidateServiceUuids Service UUIDs to try in priority
     *   order during service discovery. First match wins. Multiple
     *   entries let a single reader cover hardware revisions that
     *   changed UUIDs without changing wire format.
     * @param decodeMask Called on every incoming notification with the
     *   raw notification payload's first byte (masked to unsigned 8
     *   bits). Returns the set of buttons the vendor considers
     *   currently held. The base class diffs against the previous
     *   result to fire per-button down/up events.
     *
     *   A vendor that reports only "any button held / none held" (like
     *   the current Pryme heuristic) can return `setOf(PTT)` for any
     *   non-zero mask and `emptySet()` for zero.
     *
     *   A vendor that packs multiple buttons into one byte (like
     *   Zello's 0x01/0x02/0x04/0x08/0x10 bitmap) can decode each bit
     *   independently and return a multi-element set.
     */
    data class Config(
        val tag: String,
        val candidateServiceUuids: List<UUID>,
        val decodeMask: (mask: Int) -> Set<AinaButton>,
    )

    private val handler = Handler(Looper.getMainLooper())
    private var gatt: BluetoothGatt? = null
    private var targetDevice: BluetoothDevice? = null

    @Volatile
    private var intentionalDisconnect: Boolean = false

    // Sticky "reader is being torn down for good" flag. Set by
    // [dispose] before the owner (VoicePlant) drops its reference.
    // Guards against late callbacks from the OS's binder queue — the
    // stale GATT callback closure can still be live for a few ms
    // after we've closed the gatt handle, and if VoicePlant has
    // already swapped in a fresh reader those late events would fire
    // into the plugin with a stale [PttSource] identity. Any
    // callback dispatch path (connection state, button events) short-
    // circuits when this is true.
    @Volatile
    private var disposed: Boolean = false

    private var directRetryCount: Int = 0
    private var autoConnectArmed: Boolean = false

    // Previous "buttons currently held" set, used to derive
    // edge-triggered down/up events. Starts empty (nothing held);
    // first notification with a non-empty decode = down events for
    // each button in the new set. Reset on disconnect so a
    // reconnect doesn't spuriously fire an "up" for whatever was
    // held at the moment the link dropped.
    @Volatile
    private var lastHeld: Set<AinaButton> = emptySet()

    private val retryRunnable =
        Runnable {
            val device = targetDevice ?: return@Runnable
            if (intentionalDisconnect) return@Runnable
            connectInternal(device, useAutoConnect = autoConnectArmed)
        }

    fun connect(device: BluetoothDevice) {
        if (disposed) {
            Log.w(config.tag, "connect() called on disposed reader — ignoring")
            return
        }
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
        lastHeld = emptySet()
        Log.i(
            config.tag,
            "Connecting to ${device.address} " +
                "(autoConnect=$useAutoConnect, attempt=${directRetryCount + 1})",
        )
        gatt = device.connectGatt(context, useAutoConnect, callback, BluetoothDevice.TRANSPORT_LE)
    }

    fun disconnect() {
        intentionalDisconnect = true
        targetDevice = null
        handler.removeCallbacks(retryRunnable)
        closeGatt()
        dispatchConnectionState(false)
    }

    /**
     * Permanent teardown. Marks the reader disposed so any late
     * callback still queued behind the OS's binder short-circuits
     * before it can fire back into the plugin with a stale identity.
     * Callers (VoicePlant on external-button swap / plugin shutdown)
     * should invoke this BEFORE nulling out their reference — after
     * dispose, [connect] is a no-op and every callback dispatch path
     * is muted. Idempotent.
     */
    fun dispose() {
        disposed = true
        disconnect()
    }

    /**
     * Single choke point for connection-state fan-out so [disposed]
     * gates every path — direct call from [disconnect] as well as
     * the GATT-callback path.
     */
    private fun dispatchConnectionState(up: Boolean) {
        if (disposed) return
        onConnectionState(up)
    }

    /**
     * Single choke point for button-event fan-out so [disposed]
     * gates late notifications too.
     */
    private fun dispatchEvent(
        button: AinaButton,
        isDown: Boolean,
    ) {
        if (disposed) return
        onEvent(button, isDown)
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
            config.tag,
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
                        Log.i(config.tag, "Connected (status=$status), discovering services")
                        directRetryCount = 0
                        autoConnectArmed = false
                        dispatchConnectionState(true)
                        g.discoverServices()
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        Log.i(config.tag, "Disconnected (status=$status)")
                        lastHeld = emptySet()
                        // Two paths can land here in the same
                        // millisecond during a controlled teardown:
                        //   1. [disconnect] already fired the false
                        //      callback synchronously.
                        //   2. The OS then delivers its async
                        //      STATE_DISCONNECTED echo via this
                        //      callback.
                        // Gate on [intentionalDisconnect] so the
                        // async echo is a no-op — it would otherwise
                        // fire a duplicate `up=false` into VoicePlant
                        // and log-spam the field capture (see the
                        // 2026-07-10 Pryme reconnect trace).
                        // Real disconnects (link drop, power-off,
                        // out-of-range) still fall through to both
                        // the callback AND scheduleReconnect.
                        when (classifyDisconnect(intentionalDisconnect, disposed)) {
                            DisconnectAction.NOTIFY_AND_RECONNECT -> {
                                dispatchConnectionState(false)
                                scheduleReconnect("status=$status")
                            }
                            DisconnectAction.SUPPRESS_INTENTIONAL,
                            DisconnectAction.SUPPRESS_DISPOSED,
                            -> Unit
                        }
                    }
                }
            }

            override fun onServicesDiscovered(
                g: BluetoothGatt,
                status: Int,
            ) {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    Log.w(config.tag, "Service discovery failed: $status")
                    return
                }
                // One-time enumeration of every service + characteristic
                // so bring-up on new hardware has a paper trail. Cheap
                // and only fires once per (re)connect.
                for (svc in g.services) {
                    Log.i(config.tag, "service: ${svc.uuid}")
                    for (ch in svc.characteristics) {
                        val props = describeProps(ch.properties)
                        Log.i(config.tag, "  char: ${ch.uuid} [$props]")
                    }
                }
                val service =
                    config.candidateServiceUuids
                        .asSequence()
                        .mapNotNull { uuid ->
                            g.getService(uuid)?.also {
                                Log.i(config.tag, "matched service $uuid")
                            }
                        }.firstOrNull()
                if (service == null) {
                    Log.w(
                        config.tag,
                        "no known service on device " +
                            "(tried ${config.candidateServiceUuids}) — bond may be wrong protocol",
                    )
                    return
                }
                var subscribed = 0
                for (ch in service.characteristics) {
                    if ((ch.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY) == 0) continue
                    if (!g.setCharacteristicNotification(ch, true)) {
                        Log.w(config.tag, "setCharacteristicNotification(true) failed for ${ch.uuid}")
                        continue
                    }
                    val cccd = ch.getDescriptor(CCCD_UUID)
                    if (cccd == null) {
                        Log.w(config.tag, "CCCD missing on ${ch.uuid} — cannot enable notifications")
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
                        Log.i(config.tag, "subscribed to notifications on ${ch.uuid}")
                    } else {
                        Log.w(config.tag, "writeDescriptor(enable) failed for ${ch.uuid}")
                    }
                }
                if (subscribed == 0) {
                    Log.w(config.tag, "no notify-capable characteristics in matched service — protocol mismatch")
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
                Log.i(config.tag, "notify ${ch.uuid}: ${bytes.toHexString()}")
                if (bytes.isEmpty()) return
                val mask = bytes[0].toInt() and 0xFF
                val nowHeld = config.decodeMask(mask)
                val prev = lastHeld
                val edges = diffEdges(prev, nowHeld) ?: return
                lastHeld = nowHeld
                for (btn in edges.down) dispatchEvent(btn, true)
                for (btn in edges.up) dispatchEvent(btn, false)
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

    /**
     * Pure computation of down/up edges between two "held" sets.
     * Returns `null` when there is nothing to fire (identical sets),
     * so callers can early-return without allocating an [Edges].
     *
     * Extracted as a top-level companion function so unit tests can
     * pin the diff behavior without instantiating the reader (which
     * requires an Android [Context] and a live GATT stack).
     */
    data class Edges(
        val down: List<AinaButton>,
        val up: List<AinaButton>,
    )

    /**
     * What to do when the OS delivers `STATE_DISCONNECTED`.
     *
     * Split out from [onConnectionStateChange] as a pure decision
     * function so unit tests can pin the state table without an
     * Android GATT stack.
     */
    enum class DisconnectAction {
        /** Real link drop — fire the callback and schedule reconnect. */
        NOTIFY_AND_RECONNECT,

        /** Controlled teardown — [disconnect] already fired the callback synchronously; the OS's async echo is redundant. */
        SUPPRESS_INTENTIONAL,

        /** Reader has been [dispose]d — the owner has moved on, do not fire back. */
        SUPPRESS_DISPOSED,
    }

    companion object {
        private const val MAX_DIRECT_RETRIES = 4
        private const val INITIAL_RETRY_DELAY_MS = 2_000L
        private const val MAX_DIRECT_RETRY_DELAY_MS = 16_000L
        private const val AUTO_CONNECT_DELAY_MS = 5_000L

        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        fun diffEdges(
            prev: Set<AinaButton>,
            now: Set<AinaButton>,
        ): Edges? {
            if (prev == now) return null
            val down = now.filter { it !in prev }
            val up = prev.filter { it !in now }
            return Edges(down = down, up = up)
        }

        /**
         * Decision table for `STATE_DISCONNECTED` handling.
         * `disposed` beats `intentional` — a disposed reader should
         * never fire back into the plugin under any circumstances.
         */
        fun classifyDisconnect(
            intentional: Boolean,
            disposed: Boolean,
        ): DisconnectAction =
            when {
                disposed -> DisconnectAction.SUPPRESS_DISPOSED
                intentional -> DisconnectAction.SUPPRESS_INTENTIONAL
                else -> DisconnectAction.NOTIFY_AND_RECONNECT
            }
    }
}
