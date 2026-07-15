package com.atakmap.android.xv.ptt

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import com.atakmap.android.xv.service.XvVoiceService
import com.atakmap.android.xv.util.SamsungActiveKey
import com.atakmap.android.xv.util.SonimHardwareButtons

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

    // Independent held-state for the Sonim PTT (KEYCODE_PTT / 228)
    // background path. Same rationale as [held] above but tracked
    // separately because the Samsung Active Key and Sonim PTT are
    // different physical buttons on different chassis; conflating
    // their state would cause a Samsung Active Key press to mask a
    // Sonim PTT press or vice-versa on a hypothetical dual-hardware
    // scenario.
    @Volatile
    private var sonimPttHeld: Boolean = false

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
     * Release any in-flight PTT when the accessibility feedback is
     * interrupted. XV holds no window content, but it does track
     * per-button held state ([held] / [sonimPttHeld]). If the service
     * is interrupted (another accessibility service takes over, or the
     * OS tears feedback down) while a key is physically held, we may
     * never see the matching key-up — which would strand a down edge in
     * the dispatcher (TX stuck engaged) and make the next press look
     * like a stuck "already held" duplicate. Mirror the UP edge for
     * whichever button was held, then clear local state.
     */
    override fun onInterrupt() {
        releaseHeldPtt(reason = "onInterrupt")
    }

    /**
     * Also release on unbind — disabling the service (the common case)
     * routes through here rather than [onInterrupt], and a key held at
     * that moment would otherwise strand TX.
     */
    override fun onUnbind(intent: android.content.Intent?): Boolean {
        releaseHeldPtt(reason = "onUnbind")
        return super.onUnbind(intent)
    }

    /**
     * Fire a synthetic UP edge for any button currently held, then clear
     * the held flags. Safe to call when nothing is held (no-op).
     */
    private fun releaseHeldPtt(reason: String) {
        if (held) {
            held = false
            Log.i(TAG, "$reason — releasing held Samsung Active Key PTT")
            dispatchEdge(isDown = false)
        }
        if (sonimPttHeld) {
            sonimPttHeld = false
            Log.i(TAG, "$reason — releasing held Sonim PTT")
            try {
                XvVoiceService.deliverSonimPttEdge(false)
            } catch (t: Throwable) {
                Log.w(TAG, "$reason deliverSonimPttEdge(up) threw", t)
            }
        }
    }

    /**
     * Intercept hardware key events. Acts on:
     *
     *   - Samsung Active Key (keyCode 1015) when Samsung ruggedized
     *     hardware is detected — the original driver for this service.
     *   - Sonim KEYCODE_PTT (228) when Sonim ruggedized hardware is
     *     detected — added 2026-07-14 so the Sonim XP10 physical PTT
     *     button works with ATAK in the background / screen off, same
     *     coverage the Samsung Active Key already gets.
     *
     * Every other keycode returns `false` (not consumed) so normal
     * system key routing is unaffected. On non-Samsung, non-Sonim
     * hardware both branches skip and the service is a no-op regardless
     * of whether the operator has enabled it.
     */
    // Guard-clause early returns (device gates + held-state dedup) read
    // more clearly than the nested-conditional equivalent; detekt's
    // ReturnCount default of 2 is too strict for a key-event dispatcher.
    @Suppress("ReturnCount")
    override fun onKeyEvent(event: KeyEvent): Boolean {
        val keyCode = event.keyCode

        // Cheap keyCode fast-path. onKeyEvent is invoked for EVERY
        // hardware key system-wide, so reject anything that isn't one of
        // the two keys we handle BEFORE touching the device gates — those
        // read Build / PackageManager and shouldn't run on every volume
        // or navigation press.
        if (keyCode != SonimHardwareButtons.PTT_KEY_CODE_ALT2 &&
            keyCode != SamsungActiveKey.KEY_CODE_PTT
        ) {
            return false
        }

        // Sonim PTT branch — device-gated to Sonim XP10 (XP9900 / XP10
        // model prefix) so a Samsung-only device can't accidentally
        // consume KEYCODE_PTT that some other app might be using. The
        // keyCode is already known to be PTT_KEY_CODE_ALT2 here.
        if (keyCode == SonimHardwareButtons.PTT_KEY_CODE_ALT2) {
            return if (SonimHardwareButtons.isSupported(this)) handleSonimPtt(event) else false
        }

        // Samsung Active Key branch (keyCode == KEY_CODE_PTT) —
        // belt-and-suspenders device gate.
        if (!SamsungActiveKey.isSupported(this)) return false

        return when (SamsungActiveKey.handleKeyEvent(keyCode, event.action, event.repeatCount)) {
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
     * Sonim PTT (KEYCODE_PTT / 228) branch of [onKeyEvent]. Dedups
     * against the foreground KeyEvent path via [sonimPttHeld] so a
     * physically-held key doesn't spam duplicate down edges through
     * the dispatcher's OR-gate.
     */
    @Suppress("ReturnCount")
    private fun handleSonimPtt(event: KeyEvent): Boolean {
        return when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (event.repeatCount != 0) {
                    // Auto-repeat — swallow silently. First DOWN
                    // already fired.
                    return true
                }
                if (sonimPttHeld) {
                    Log.d(TAG, "Sonim PTT DOWN (accessibility) — already held; dropping duplicate")
                    return true
                }
                sonimPttHeld = true
                Log.i(TAG, "Sonim PTT DOWN (accessibility background path)")
                try {
                    XvVoiceService.deliverSonimPttEdge(true)
                } catch (t: Throwable) {
                    Log.w(TAG, "deliverSonimPttEdge(down) threw", t)
                }
                true
            }
            KeyEvent.ACTION_UP -> {
                if (!sonimPttHeld) {
                    Log.d(TAG, "Sonim PTT UP (accessibility) — not held; dropping")
                    return true
                }
                sonimPttHeld = false
                Log.i(TAG, "Sonim PTT UP (accessibility background path)")
                try {
                    XvVoiceService.deliverSonimPttEdge(false)
                } catch (t: Throwable) {
                    Log.w(TAG, "deliverSonimPttEdge(up) threw", t)
                }
                true
            }
            else -> false
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
