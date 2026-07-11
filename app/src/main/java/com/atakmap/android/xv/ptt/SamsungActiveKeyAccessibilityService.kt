package com.atakmap.android.xv.ptt

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import com.atakmap.android.xv.util.SamsungActiveKey

/**
 * Accessibility service that intercepts the Samsung Active Key
 * (keyCode 1015) so PTT can fire while ATAK is in the background on
 * Samsung ruggedized hardware (Tab Active5, XCover6 Pro / 7,
 * Tab Active4 Pro, Tab Active3).
 *
 * ---
 *
 * Why an Accessibility service for background PTT:
 *
 * The foreground path ([SamsungActiveKeyForegroundReader]) works only
 * while ATAK holds the top window — `InputDispatcher` delivers
 * hardware-key `KeyEvent`s to the focused activity, not to arbitrary
 * background services.  The broadcast path ([SamsungActiveKeyReader])
 * is the ideal background channel, but on-device validation 2026-07-10
 * confirmed that SM-X308U firmware does not emit the `HARD_KEY_REPORT`
 * broadcast for KEY_CODE == 1015.
 *
 * An `AccessibilityService` with `flagRequestFilterKeyEvents` receives
 * `onKeyEvent` for every hardware key press system-wide, regardless of
 * which app is in the foreground.  That is exactly the privilege PTT
 * needs.
 *
 * ---
 *
 * Tight scoping — what this service does NOT do:
 *
 * - `onAccessibilityEvent` body is intentionally empty — XV subscribes
 *   to **zero** UI events (`accessibilityEventTypes = typeNone` in the
 *   descriptor XML).  The service body never reads screen content,
 *   typed text, viewed windows, or any other accessibility data.
 * - `canRetrieveWindowContent = false` in the XML descriptor.
 * - Only `flagRequestFilterKeyEvents` is declared — the narrowest flag
 *   set that grants `onKeyEvent`.
 * - Only KEY_CODE_PTT (1015) is ever acted on in `onKeyEvent`; every
 *   other keycode returns `false` (not consumed).
 * - Device gate: `SamsungActiveKey.isSupported(this)` is checked on
 *   every call; if the service is somehow enabled on unsupported
 *   hardware it silently no-ops.
 *
 * ---
 *
 * Binding + security:
 *
 * The service is registered with `android:permission =
 * "android.permission.BIND_ACCESSIBILITY_SERVICE"` in the manifest,
 * so only the system accessibility framework can bind it.  This is the
 * same pattern used by `XvConnectionService`'s
 * `BIND_TELECOM_CONNECTION_SERVICE` binding.  `exported = true` is
 * required by the framework (the system binder crosses UIDs); the
 * permission gate is the actual enforcement mechanism.
 *
 * ---
 *
 * Operator consent:
 *
 * This service cannot be enabled programmatically — the operator must
 * navigate Settings → Accessibility → Installed Services → XV → Enable
 * and accept the system-generated dialog.  The Settings row in XV's
 * settings drawer deep-links to the system Accessibility settings page
 * to reduce friction.  The row is only visible on Samsung ruggedized
 * hardware where `SamsungActiveKey.isSupported()` returns true.
 *
 * ---
 *
 * Deduplication with the foreground path:
 *
 * When the accessibility service is enabled AND ATAK is in the
 * foreground, both this service and [SamsungActiveKeyForegroundReader]
 * fire for the same key press.  [com.atakmap.android.xv.audio.PttDispatcher]'s
 * OR-gate (source-tagged dedup) collapses the duplicate edges cleanly:
 * a second `down(SAMSUNG_ACTIVE_KEY)` while the source is already held
 * is ignored; a second `up` while already released is also a no-op.
 * No changes are needed to the dispatcher.
 */
class SamsungActiveKeyAccessibilityService : AccessibilityService() {

    // Internal held-state mirrors SamsungActiveKeyForegroundReader's
    // guard: drop a stray UP that arrives without a matching DOWN (e.g.
    // the key was pressed before the service was ready) and drop a
    // duplicate DOWN while already held (shouldn't happen via
    // onKeyEvent, but belt-and-suspenders given the OR-gate and the
    // lack of guarantees in onKeyEvent delivery ordering).
    @Volatile
    private var held: Boolean = false

    /**
     * Empty implementation — XV subscribes to zero UI events.
     * The method must be present because `AccessibilityService` declares
     * it `abstract`, but the service descriptor's `typeNone` flag means
     * the system will never invoke it with real event data.
     */
    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        // Intentionally empty.  typeNone in xv_accessibility_service.xml
        // means no UI events are delivered here.
    }

    /**
     * Empty implementation — required by the abstract class contract.
     * XV never acquires window content, so there is nothing to release.
     */
    override fun onInterrupt() {
        // No-op.  We hold no resources that need releasing on interrupt.
    }

    /**
     * Intercept hardware key events.  Only KEY_CODE_PTT (1015) is acted
     * on; everything else returns `false` (not consumed) so normal
     * system key routing is unaffected.
     *
     * Device gate: returns `false` immediately on unsupported hardware
     * so a misconfigured install on a non-Samsung device has zero effect.
     */
    override fun onKeyEvent(event: KeyEvent): Boolean {
        // Belt-and-suspenders device gate — the Settings row is hidden
        // on unsupported hardware so the operator can't even enable the
        // service from the XV UI, but guard here as well in case they
        // somehow enable it via raw accessibility settings.
        if (!SamsungActiveKey.isSupported(this)) return false

        return when (SamsungActiveKey.handleKeyEvent(event.keyCode, event.action, event.repeatCount)) {
            SamsungActiveKey.FallbackAction.PTT_DOWN -> {
                if (held) {
                    Log.d(TAG, "Samsung Active Key DOWN (accessibility) — already held; dropping duplicate")
                    return true
                }
                held = true
                Log.i(TAG, "Samsung Active Key DOWN (accessibility background path)")
                dispatchEdge(isDown = true)
                true
            }
            SamsungActiveKey.FallbackAction.PTT_UP -> {
                if (!held) {
                    Log.d(TAG, "Samsung Active Key UP (accessibility) — not held; dropping")
                    return true
                }
                held = false
                Log.i(TAG, "Samsung Active Key UP (accessibility background path)")
                dispatchEdge(isDown = false)
                true
            }
            SamsungActiveKey.FallbackAction.IGNORE -> false
        }
    }

    /**
     * Fire a PTT edge via a local broadcast that [XvVoiceService] picks
     * up through its `SamsungActiveKeyReader` broadcast receiver.
     *
     * The broadcast is sent with `sendBroadcast` to the same process
     * (XV's APK UID) so the service separation is preserved — the
     * accessibility service lives in XV's UID, as does XvVoiceService.
     * The receiver in XvVoiceService is registered with
     * `RECEIVER_NOT_EXPORTED`, which is intentional — only the Samsung
     * framework and now this in-process service need to deliver to it.
     * Using the existing `HARD_KEY_REPORT` action with the existing
     * extras format means zero changes to the broadcast reader or the
     * voice service's PTT dispatch logic.
     */
    private fun dispatchEdge(isDown: Boolean) {
        try {
            val intent =
                android.content.Intent(SamsungActiveKey.ACTION_HARD_KEY_REPORT).apply {
                    putExtra(SamsungActiveKey.EXTRA_KEY_CODE, SamsungActiveKey.KEY_CODE_PTT)
                    putExtra(
                        SamsungActiveKey.EXTRA_KEY_REPORT_TYPE,
                        if (isDown) {
                            SamsungActiveKey.KEY_REPORT_TYPE_PRESSED
                        } else {
                            SamsungActiveKey.KEY_REPORT_TYPE_RELEASED
                        },
                    )
                    // Restrict to our own UID so the in-process broadcast
                    // doesn't escape to other apps on the device.
                    `package` = packageName
                }
            sendBroadcast(intent)
        } catch (t: Throwable) {
            Log.w(TAG, "dispatchEdge(isDown=$isDown) sendBroadcast threw", t)
        }
    }

    companion object {
        private const val TAG = "XvSamsungActiveKeyA11y"

        /**
         * Component name string used to check whether this service is
         * currently enabled via
         * [android.view.accessibility.AccessibilityManager.getEnabledAccessibilityServiceList].
         * Format matches [android.content.ComponentName.flattenToString].
         */
        const val COMPONENT_NAME = "com.atakmap.android.xv/.ptt.SamsungActiveKeyAccessibilityService"
    }
}
