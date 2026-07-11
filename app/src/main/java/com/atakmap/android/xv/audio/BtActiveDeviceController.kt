package com.atakmap.android.xv.audio

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.util.Log
import com.atakmap.android.xv.aina.redactMac

// Best-effort programmatic switch of the ACTIVE HFP device on the local
// Bluetooth adapter, so [AudioManager.setCommunicationDevice] can pin the
// operator's chosen headset instead of whichever HFP device the OS most
// recently marked active.
//
// Background: on API 31+, [AudioManager.getAvailableCommunicationDevices]
// only exposes the currently-ACTIVE HFP device — even when the phone has
// two HFP profiles connected (e.g. an AINA speakermic AND a Shokz OpenMove
// headset). XV's `outputBtOverrideMac` correctly detects the miss and
// falls through, but the operator can't reach the second device for
// voice comm without opening the system media picker manually. This
// controller narrows that gap by trying the sanctioned system API
// programmatically: [BluetoothAdapter.setActiveDevice(BluetoothDevice, int)]
// with `ACTIVE_DEVICE_PHONE_CALL = 1`. That method is `@SystemApi` /
// `@hide` and requires `BLUETOOTH_PRIVILEGED` — signature-only on
// Pixel 14+ / AOSP 14+. Reflection is used because there is no
// public API surface, exactly the same way [AinaA2dpController] gates
// A2DP via reflective `setConnectionPolicy`.
//
// Two expected outcomes in the field:
//   - Pixel 14+ / recent AOSP builds: reflection returns `false`
//     (or a SecurityException gets swallowed as `REFUSED_BY_PLATFORM`).
//     Confirmed by the same platform hardening that makes
//     [AinaA2dpController] fall through to a manual-toggle prompt.
//     XV then leans on the system-media-picker fallback so the operator
//     can pick manually with one tap.
//   - Samsung One UI + some OEMs: reflection can actually succeed
//     because the platform doesn't gate the API as tightly. Logged at
//     INFO so field logs make the difference visible.
//   - API < 28: `setActiveDevice` did not exist. Reflection returns
//     [TrySetActiveResult.NotSupported]; XV skips straight to the
//     manual fallback.
//
// Zero declared permissions. Reflection needs none; a failed reflection
// call surfaces as a plain result, not a runtime crash. The MAC value
// is redacted in every log line per CLAUDE.md sensitive-content rules.
@SuppressLint("MissingPermission")
object BtActiveDeviceController {
    private const val TAG = "XvBtActiveDev"

    // BluetoothAdapter.ACTIVE_DEVICE_PHONE_CALL constant. Public
    // documentation (Android source: BluetoothAdapter.java @SystemApi)
    // pins the value at 1 across every version that has the API. We
    // try to read the constant reflectively first (in case a future
    // release renumbers it) and fall back to the literal — same
    // strategy as [AinaA2dpController]'s inlined policy constants.
    private const val ACTIVE_DEVICE_PHONE_CALL_FALLBACK = 1

    /**
     * Outcome of a single [trySetActive] attempt. Cached per-MAC by
     * [trySetActive] so we don't hammer reflection on every PTT press
     * when the platform has already told us "no."
     */
    sealed class TrySetActiveResult {
        /** Reflection call returned true — the adapter accepted the
         *  switch. Note that the OS still takes some milliseconds to
         *  actually re-enumerate; callers should poll
         *  [AudioManager.availableCommunicationDevices] briefly. */
        object Success : TrySetActiveResult()

        /** Reflection succeeded but the adapter returned false. Almost
         *  always means BLUETOOTH_PRIVILEGED enforcement — the platform
         *  silently refused. Pixel 14+ / AOSP 14+ typical. */
        object RefusedByPlatform : TrySetActiveResult()

        /** `setActiveDevice(BluetoothDevice, int)` not found on the
         *  adapter class. API < 28 or heavily-stripped ROM. */
        object NotSupported : TrySetActiveResult()

        /** Reflection call threw an unexpected exception (typically an
         *  InvocationTargetException wrapping SecurityException on some
         *  OEMs). Cached so we don't keep hitting the same wall. */
        data class Threw(val cause: Throwable) : TrySetActiveResult()
    }

    // Per-MAC cache of the last observed result. Keyed by MAC (case-
    // insensitive canonicalized to upper-case). We cache SUCCESS as
    // well as failures — a device that succeeded once will normally
    // succeed again, and the log noise from re-issuing the call every
    // PTT press adds up. Cache is cleared on process restart, which is
    // fine: platform behaviour doesn't change mid-session.
    private val resultCache: MutableMap<String, TrySetActiveResult> =
        java.util.Collections.synchronizedMap(HashMap())

    /**
     * Attempt to set [mac] as the active HFP device on the local BT
     * adapter. Result is cached per-MAC; a second call for the same
     * MAC returns the cached outcome and logs at DEBUG.
     *
     * @param context any Context — used only to resolve the
     *                [BluetoothManager] / [BluetoothAdapter]. Not
     *                retained.
     * @param mac     BT MAC address of the target device. Must be a
     *                real bonded device address; unknown MACs surface
     *                as [TrySetActiveResult.Threw] wrapping the
     *                [BluetoothAdapter.getRemoteDevice] exception.
     */
    fun trySetActive(
        context: Context,
        mac: String,
    ): TrySetActiveResult {
        val key = mac.uppercase()
        resultCache[key]?.let { cached ->
            Log.d(TAG, "trySetActive(${redactMac(mac)}) cache hit: ${cached.javaClass.simpleName}")
            return cached
        }
        val result = doTrySetActive(context, mac)
        resultCache[key] = result
        Log.i(TAG, "trySetActive(${redactMac(mac)}) → ${result.javaClass.simpleName}")
        return result
    }

    /**
     * Purge the cached outcome for [mac] so the next [trySetActive]
     * call re-attempts reflection. Used when the platform's active-
     * HFP set changes underneath us (e.g. the operator toggled
     * something from system BT settings) and a previously-refused
     * device might now succeed.
     */
    fun invalidate(mac: String) {
        val key = mac.uppercase()
        if (resultCache.remove(key) != null) {
            Log.i(TAG, "invalidate(${redactMac(mac)})")
        }
    }

    /** Test hook — clears the entire cache. Not for production use. */
    @androidx.annotation.VisibleForTesting
    internal fun clearCacheForTest() {
        resultCache.clear()
    }

    private fun doTrySetActive(
        context: Context,
        mac: String,
    ): TrySetActiveResult {
        val adapter =
            try {
                val bm = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
                bm?.adapter ?: BluetoothAdapter.getDefaultAdapter()
            } catch (t: Throwable) {
                return TrySetActiveResult.Threw(t)
            } ?: return TrySetActiveResult.NotSupported

        val device: BluetoothDevice =
            try {
                adapter.getRemoteDevice(mac)
            } catch (t: Throwable) {
                return TrySetActiveResult.Threw(t)
            }

        val method =
            try {
                adapter.javaClass.getMethod(
                    "setActiveDevice",
                    BluetoothDevice::class.java,
                    Int::class.javaPrimitiveType,
                )
            } catch (_: NoSuchMethodException) {
                return TrySetActiveResult.NotSupported
            } catch (t: Throwable) {
                return TrySetActiveResult.Threw(t)
            }

        val phoneCallProfile = resolveActiveDevicePhoneCallConstant(adapter)
        return try {
            val result = method.invoke(adapter, device, phoneCallProfile)
            if (result is Boolean && !result) {
                TrySetActiveResult.RefusedByPlatform
            } else {
                // Void return / null / non-boolean truthy — treat as
                // success. Same policy as [AinaA2dpController.setConnectionPolicyReflective]:
                // OEM builds that return Void still succeeded.
                TrySetActiveResult.Success
            }
        } catch (t: Throwable) {
            val cause =
                if (t is java.lang.reflect.InvocationTargetException) {
                    t.targetException ?: t
                } else {
                    t
                }
            if (cause is SecurityException) {
                TrySetActiveResult.RefusedByPlatform
            } else {
                TrySetActiveResult.Threw(cause)
            }
        }
    }

    /**
     * Read `BluetoothAdapter.ACTIVE_DEVICE_PHONE_CALL` reflectively so
     * we track any future platform renumber. Falls back to the
     * known-stable literal `1` when the field is missing (older APIs
     * that still have `setActiveDevice` but haven't yet exposed the
     * constant on the public class).
     */
    private fun resolveActiveDevicePhoneCallConstant(adapter: BluetoothAdapter): Int =
        try {
            val f = adapter.javaClass.getField("ACTIVE_DEVICE_PHONE_CALL")
            (f.get(null) as? Int) ?: ACTIVE_DEVICE_PHONE_CALL_FALLBACK
        } catch (_: Throwable) {
            ACTIVE_DEVICE_PHONE_CALL_FALLBACK
        }
}

/**
 * Pure decision function for the target-routing state machine in
 * [ScoLink]. Extracted so unit tests can pin every branch without
 * standing up BluetoothAdapter / AudioManager / a live Context.
 *
 * The "effective target" MAC is `overrideMac ?: hintMac`. The state
 * machine treats both the same way — reflection nudge + switcher
 * fallback — but the caller distinguishes them for toast wording
 * (override vs. primary-speakermic-hint messaging). The extension to
 * cover the hint case landed 2026-07-11 for the Auto + primary-AINA +
 * secondary-BT-headset field scenario where the OS's active HFP peer
 * is not the AINA hint and XV was silently falling through to whichever
 * device the OS gave us in `getAvailableCommunicationDevices`.
 *
 * Given the current state — override MAC, hint MAC, currently-available
 * comm-device MACs, last [BtActiveDeviceController.trySetActive] outcome
 * for the effective target, and the switcher-launch cooldown — return
 * which action the caller should take next.
 *
 * The caller (production ScoLink) then executes the action:
 *   - [OverrideRoutingAction.PIN_DIRECT]: pin the effective target MAC
 *     directly; it's already in the available list.
 *   - [OverrideRoutingAction.TRY_REFLECTION]: call
 *     [BtActiveDeviceController.trySetActive] against the effective
 *     target. On SUCCESS the caller polls the available list briefly
 *     and re-runs this decision.
 *   - [OverrideRoutingAction.LAUNCH_SWITCHER]: open the system output
 *     switcher panel (cooldown observed by the caller before invoking
 *     this — the decision has already checked it) and fall back to the
 *     hint / auto chain for the current SCO acquire.
 *   - [OverrideRoutingAction.PIN_HINT]: skip the switcher (either
 *     no effective target to try, or cooldown not yet expired) and
 *     pin whatever the hint / auto chain resolves to. Legacy behaviour.
 */
enum class OverrideRoutingAction {
    PIN_DIRECT,
    TRY_REFLECTION,
    LAUNCH_SWITCHER,
    PIN_HINT,
}

/**
 * See [OverrideRoutingAction] KDoc.
 *
 * @param overrideMac         The operator's explicit "Audio device"
 *                            override MAC. Null / blank → no explicit
 *                            override; caller should consult the hint.
 * @param hintMac             The AINA hint MAC (primary speakermic
 *                            picked from the AINA dropdown, or null
 *                            when no AINA is paired). Used as the
 *                            effective target when [overrideMac] is
 *                            null / blank. Null / blank hint AND null
 *                            override → PIN_HINT (nothing to nudge).
 * @param availableMacs       Set of MACs currently in
 *                            [android.media.AudioManager.getAvailableCommunicationDevices].
 *                            Compared case-insensitively via caller
 *                            upper-casing before the call.
 * @param reflectionCached    Previous [BtActiveDeviceController.trySetActive]
 *                            outcome for the effective target MAC, or
 *                            null if we haven't tried yet. Used to
 *                            decide whether reflection is worth
 *                            attempting again.
 * @param lastSwitcherLaunchMs Timestamp of the last switcher launch
 *                            (ms since boot), or 0L if none this
 *                            session. Cooldown is shared across both
 *                            the override-target and hint-target paths
 *                            — one switcher panel per 30 s regardless
 *                            of which target triggered it.
 * @param nowMs               Current timestamp (ms since boot).
 * @param switcherCooldownMs  Minimum spacing between switcher launches.
 *                            Prevents focus-pull spam when the operator
 *                            hammers PTT while the target is out of
 *                            range.
 */
fun decideOverrideAction(
    overrideMac: String?,
    hintMac: String?,
    availableMacs: Set<String>,
    reflectionCached: BtActiveDeviceController.TrySetActiveResult?,
    lastSwitcherLaunchMs: Long,
    nowMs: Long,
    switcherCooldownMs: Long,
): OverrideRoutingAction {
    // Effective target: explicit override wins; otherwise the hint MAC
    // set by connectAinaInternal. Null / blank on both means there's
    // nothing to nudge — the caller's existing hint / auto pick stands.
    val target =
        when {
            !overrideMac.isNullOrBlank() -> overrideMac
            !hintMac.isNullOrBlank() -> hintMac
            else -> return OverrideRoutingAction.PIN_HINT
        }
    val targetUpper = target.uppercase()
    if (availableMacs.contains(targetUpper)) return OverrideRoutingAction.PIN_DIRECT
    // Target present but not currently a comm device. If we've never
    // tried reflection for this target, try it — on Samsung / older
    // builds it may succeed and the OS re-enumerates the target in.
    if (reflectionCached == null) return OverrideRoutingAction.TRY_REFLECTION
    // Reflection already resolved. If it succeeded but the target
    // still isn't in the available set, we've hit a race or a platform
    // that accepts the API call without honoring it — fall through to
    // the switcher so the operator can complete the switch manually.
    if (reflectionCached is BtActiveDeviceController.TrySetActiveResult.Success) {
        return OverrideRoutingAction.LAUNCH_SWITCHER
    }
    // Reflection failed. Launch the switcher, but only if we're past
    // the per-session cooldown — otherwise the operator gets a panel
    // yanked over ATAK on every PTT press, which is worse than sitting
    // on the hint / auto pick.
    val cooldownExpired = (nowMs - lastSwitcherLaunchMs) >= switcherCooldownMs
    return if (cooldownExpired) {
        OverrideRoutingAction.LAUNCH_SWITCHER
    } else {
        OverrideRoutingAction.PIN_HINT
    }
}
