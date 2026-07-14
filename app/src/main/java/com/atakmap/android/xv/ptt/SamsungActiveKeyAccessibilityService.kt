package com.atakmap.android.xv.ptt

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import com.atakmap.android.xv.service.XvVoiceService
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
 * - `onAccessibilityEvent` body is intentionally empty — all dispatched
 *   events are discarded.  The descriptor uses `typeWindowStateChanged`
 *   (the minimum AAPT accepts; `typeNone`/`0` is rejected at build time)
 *   but the service body never reads screen content, typed text, viewed
 *   windows, or any other accessibility data.
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
     * Empty implementation — XV discards every event unconditionally.
     * The method must be present because `AccessibilityService` declares
     * it `abstract`. The service descriptor subscribes to
     * `typeWindowStateChanged` (the minimum AAPT accepts; `typeNone`/`0`
     * is rejected at build time), but no event data is used here.
     */
    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        // Intentionally empty.  typeWindowStateChanged in
        // xv_accessibility_service.xml is the nominal subscription required
        // by AAPT; all dispatched events are discarded.
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
     * Deliver a PTT edge directly into the running [XvVoiceService]'s
     * PttDispatcher via [XvVoiceService.deliverSamsungActiveKeyEdge].
     *
     * This service is declared with no `android:process` attribute, so
     * it runs in XV's APK process — the SAME process as XvVoiceService.
     * The edge is therefore an in-process method call, not IPC.
     *
     * Earlier revisions re-broadcast `HARD_KEY_REPORT` for
     * [SamsungActiveKeyReader] to pick up. That reader is only
     * registered while the *foreground* Active Key toggle is enabled
     * (`setSamsungActiveKeyEnabled(true)` → `startSamsungActiveKey()`),
     * so an operator who turned on ONLY this accessibility service got
     * no PTT — the broadcasts had no registered receiver. Delivering
     * straight to the voice service removes that hidden dependency:
     * background PTT works whenever the voice service is live,
     * regardless of the foreground toggle. Dedup with the foreground
     * KeyEvent path is still handled by the dispatcher's OR-gate (both
     * paths tag [com.atakmap.android.xv.audio.PttSource.SAMSUNG_ACTIVE_KEY]).
     *
     * No-op — logged, not thrown — when the voice service isn't running,
     * which is exactly when there is no session to transmit into.
     */
    private fun dispatchEdge(isDown: Boolean) {
        try {
            XvVoiceService.deliverSamsungActiveKeyEdge(isDown)
        } catch (t: Throwable) {
            Log.w(TAG, "dispatchEdge(isDown=$isDown) deliver threw", t)
        }
    }

    companion object {
        private const val TAG = "XvSamsungActiveKeyA11y"

        /**
         * Component name string used to check whether this service is
         * currently enabled via
         * [android.view.accessibility.AccessibilityManager.getEnabledAccessibilityServiceList].
         * Format matches [android.content.ComponentName.flattenToString].
         *
         * Uses [com.atakmap.android.xv.BuildConfig.APPLICATION_ID] (the
         * APK's runtime package name, `com.atakmap.android.xv.plugin`)
         * rather than the source namespace (`com.atakmap.android.xv`) so
         * that the string matches what [android.view.accessibility.AccessibilityManager]
         * actually returns for enabled services.
         */
        val COMPONENT_NAME: String =
            android.content.ComponentName(
                com.atakmap.android.xv.BuildConfig.APPLICATION_ID,
                SamsungActiveKeyAccessibilityService::class.java.name,
            ).flattenToString()
    }
}
