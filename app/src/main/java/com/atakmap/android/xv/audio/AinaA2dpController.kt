package com.atakmap.android.xv.audio

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.util.Log

// Programmatic A2DP forbid for the AINA speakermic, so media apps stop
// routing audio to it (Spotify, YouTube, ATAK alerts, etc). HFP profile
// is left alone — XV still uses SCO for TX and RX-on-HFP-only paths.
//
// Why reflection: BluetoothA2dp.setConnectionPolicy(device, policy) and
// the older setPriority(device, priority) are @SystemApi / @hide. They
// exist on every modern AOSP / OEM build and are reachable via reflection
// from a regular app. Pixel Android 14+ removed the per-profile UI
// toggles in system Bluetooth settings, so this is the only practical
// path to disable A2DP on a specific device without unpairing.
//
// The controller tracks every device it forbade so [stop] can restore
// them on plugin unload — leaving a device permanently A2DP-forbidden
// after XV exits would be a hostile side effect.
@SuppressLint("MissingPermission")
class AinaA2dpController(
    private val context: Context,
) {
    private val bluetoothAdapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()

    @Volatile
    private var a2dpProxy: Any? = null // BluetoothA2dp at runtime; declared as Any so we don't import the @hide-but-public class

    // MACs we have forbidden during this plugin lifetime. Used to
    // restore them on stop / unload. ConcurrentHashMap.newKeySet
    // would be cleaner, but a synchronized HashSet keeps lookup +
    // mutation atomic with the proxy state.
    private val forbiddenMacs: MutableSet<String> = java.util.Collections.synchronizedSet(HashSet())

    enum class ForbidResult {
        OK,
        REFLECTION_FAILED,
        NO_PROXY,
        NO_DEVICE,
    }

    fun start() {
        bluetoothAdapter?.getProfileProxy(
            context,
            object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(
                    profile: Int,
                    proxy: BluetoothProfile,
                ) {
                    if (profile == BluetoothProfile.A2DP) {
                        a2dpProxy = proxy
                        Log.i(TAG, "A2DP profile proxy connected")
                    }
                }

                override fun onServiceDisconnected(profile: Int) {
                    if (profile == BluetoothProfile.A2DP) {
                        a2dpProxy = null
                    }
                }
            },
            BluetoothProfile.A2DP,
        )
    }

    fun stop() {
        // Best-effort restore of every device we forbade.
        val snapshot = synchronized(forbiddenMacs) { forbiddenMacs.toList() }
        for (mac in snapshot) {
            val dev =
                try {
                    bluetoothAdapter?.getRemoteDevice(mac)
                } catch (t: Throwable) {
                    Log.w(TAG, "stop: could not resolve $mac to restore", t)
                    null
                }
            if (dev != null) allow(dev)
        }
        a2dpProxy?.let { proxy ->
            try {
                bluetoothAdapter?.closeProfileProxy(BluetoothProfile.A2DP, proxy as BluetoothProfile)
            } catch (t: Throwable) {
                Log.w(TAG, "closeProfileProxy threw", t)
            }
        }
        a2dpProxy = null
    }

    /**
     * Disable A2DP on [device]. Media apps will route to phone speaker
     * (or other connected media sinks) instead of the AINA. HFP / SCO
     * remain available for voice TX/RX. Also disconnects any active
     * A2DP session so currently-playing music stops routing here
     * immediately.
     */
    fun forbid(device: BluetoothDevice): ForbidResult {
        val proxy = a2dpProxy ?: return ForbidResult.NO_PROXY
        val ok = setConnectionPolicyReflective(proxy, device, allowed = false)
        if (!ok) return ForbidResult.REFLECTION_FAILED
        // Also tear down any in-progress A2DP connection so music routing
        // flips RIGHT NOW, not on the next reconnect cycle. If this call
        // fails it's not fatal — connection policy alone will prevent
        // future A2DP routing; existing routing just persists until the
        // device is reconnected.
        disconnectReflective(proxy, device)
        forbiddenMacs.add(device.address)
        Log.i(TAG, "forbid OK ${device.address}")
        return ForbidResult.OK
    }

    /**
     * Re-enable A2DP on [device]. Counterpart to [forbid].
     */
    fun allow(device: BluetoothDevice) {
        val proxy = a2dpProxy
        if (proxy == null) {
            Log.w(TAG, "allow: no A2DP proxy (will not restore ${device.address} until reconnect)")
            forbiddenMacs.remove(device.address)
            return
        }
        // Always drop the MAC from the tracking set: if reflection just
        // failed for this device it'll fail the same way on every retry
        // (platform privilege gate), so leaving the entry in place only
        // causes stop() to repeat the same failing call and emit a
        // duplicate log line for the same teardown.
        forbiddenMacs.remove(device.address)
        val ok = setConnectionPolicyReflective(proxy, device, allowed = true)
        if (ok) {
            Log.i(TAG, "allow OK ${device.address}")
        }
        // Failure path already logged inside setConnectionPolicyReflective.
    }

    // Tries setConnectionPolicy first (Android 10+), then setPriority
    // as fallback for older builds. Returns true on the first method
    // that succeeds; false if neither method exists or both throw.
    private fun setConnectionPolicyReflective(
        proxy: Any,
        device: BluetoothDevice,
        allowed: Boolean,
    ): Boolean {
        val cls = proxy.javaClass
        // setConnectionPolicy(BluetoothDevice, int) — present from
        // Android Q onwards. CONNECTION_POLICY_ALLOWED = 100,
        // CONNECTION_POLICY_FORBIDDEN = -1 (yes, negative one — see
        // BluetoothProfile.CONNECTION_POLICY_FORBIDDEN constant).
        try {
            val m = cls.getMethod("setConnectionPolicy", BluetoothDevice::class.java, Int::class.javaPrimitiveType)
            val policy = if (allowed) CONNECTION_POLICY_ALLOWED else CONNECTION_POLICY_FORBIDDEN
            val result = m.invoke(proxy, device, policy)
            Log.d(TAG, "setConnectionPolicy(${device.address}, $policy) = $result")
            // On Android 13+ Pixel/AOSP, BLUETOOTH_PRIVILEGED is signature-
            // protected and the platform silently returns false instead of
            // throwing SecurityException for non-system callers. Treat a
            // returned Boolean=false as failure so the caller knows the
            // policy didn't actually land. OEMs that return Void / null
            // (older builds, some non-AOSP forks) still count as success.
            if (result is Boolean && !result) {
                Log.w(
                    TAG,
                    "setConnectionPolicy returned false for ${device.address} — " +
                        "platform refused (BLUETOOTH_PRIVILEGED required on Android 13+). " +
                        "Operator must disable 'Media audio' on this device in BT settings.",
                )
                return false
            }
            return true
        } catch (_: NoSuchMethodException) {
            // Fall through to setPriority on older builds.
        } catch (t: Throwable) {
            logReflectionFailure("setConnectionPolicy", device, t)
            // Don't fall through on a real failure — surface it so the
            // user sees the Toast and can intervene manually.
            return false
        }
        try {
            val m = cls.getMethod("setPriority", BluetoothDevice::class.java, Int::class.javaPrimitiveType)
            val priority = if (allowed) PRIORITY_ON else PRIORITY_OFF
            val result = m.invoke(proxy, device, priority)
            Log.d(TAG, "setPriority(${device.address}, $priority) = $result")
            if (result is Boolean && !result) {
                Log.w(
                    TAG,
                    "setPriority returned false for ${device.address} — platform refused",
                )
                return false
            }
            return true
        } catch (t: Throwable) {
            logReflectionFailure("setPriority", device, t)
            return false
        }
    }

    // BLUETOOTH_PRIVILEGED is signature-protected on Pixel / AOSP 14+,
    // so on a non-system app this reflection ALWAYS throws. Logging the
    // 30-frame InvocationTargetException stack on every plugin teardown
    // is noise; reduce to a single debug line for the expected case and
    // a one-line warning for anything else (so a genuinely unexpected
    // failure mode still surfaces).
    private fun logReflectionFailure(
        method: String,
        device: BluetoothDevice,
        t: Throwable,
    ) {
        val cause = if (t is java.lang.reflect.InvocationTargetException) t.targetException ?: t else t
        if (cause is SecurityException) {
            Log.d(TAG, "$method denied for ${device.address}: ${cause.message}")
        } else {
            Log.w(TAG, "$method reflection failed for ${device.address}: ${cause.javaClass.simpleName}: ${cause.message}")
        }
    }

    private fun disconnectReflective(
        proxy: Any,
        device: BluetoothDevice,
    ) {
        try {
            val m = proxy.javaClass.getMethod("disconnect", BluetoothDevice::class.java)
            m.invoke(proxy, device)
        } catch (t: Throwable) {
            // Disconnect is best-effort; if the platform refuses, the
            // policy change still prevents future A2DP routing.
            Log.d(TAG, "A2DP disconnect reflective call threw (non-fatal)", t)
        }
    }

    companion object {
        private const val TAG = "XvAinaA2dp"

        // BluetoothProfile.CONNECTION_POLICY_ALLOWED / FORBIDDEN are
        // public constants on Android Q+ but the enum values are stable
        // and worth inlining so we can target older SDKs without an
        // SDK_INT gate around the import.
        private const val CONNECTION_POLICY_ALLOWED = 100
        private const val CONNECTION_POLICY_FORBIDDEN = -1

        // Pre-Q fallback: setPriority constants. PRIORITY_ON = 100,
        // PRIORITY_OFF = 0 (matches the @hide values).
        private const val PRIORITY_ON = 100
        private const val PRIORITY_OFF = 0
    }
}
