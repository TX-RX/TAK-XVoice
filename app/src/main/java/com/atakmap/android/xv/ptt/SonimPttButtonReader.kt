package com.atakmap.android.xv.ptt

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import androidx.core.content.ContextCompat
import com.atakmap.android.xv.audio.PttSource
import com.atakmap.android.xv.util.SonimHardwareButtons

/**
 * PTT reader for the dedicated side PTT button on Sonim ruggedized
 * hardware (XP10 / XP9900 and XP-family peers).
 *
 * Mechanism: the Sonim framework broadcasts
 * `com.sonim.intent.action.PTT_KEY_DOWN` when the operator presses the
 * dedicated side PTT key, and `com.sonim.intent.action.PTT_KEY_UP` on
 * release, provided the operator has mapped the PTT key to the
 * receiving app in Sonim's Settings → Programmable keys surface. No
 * Sonim SDK, license, or partner entitlement is required to receive
 * the broadcast — any app that registers a
 * [android.content.BroadcastReceiver] for the actions gets the events.
 *
 * We register the receiver with `RECEIVER_NOT_EXPORTED` (Android 13+
 * flag) because the intents come from the Sonim framework itself, not
 * from a third-party app targeting us — no need to widen the
 * receiver's exported surface.
 *
 * ---
 *
 * ### MCX / MCPTT carrier firmware (AT&T XP9900)
 *
 * On-device validation (XP9900 AT&T carrier, Android 12, 2026-07-11)
 * showed that the Sonim SDK policy engine on MCX/MCPTT-mode firmware
 * checks for apps registered under
 * `com.mcx.intent.action.CRITICAL_COMMUNICATION_CONTROL_KEY` rather
 * than the classic `com.sonim.intent.action.PTT_KEY_DOWN` / `_UP`
 * actions. The MCX action carries a single `"state"` integer extra
 * (1 = pressed, 0 = released) instead of using separate DOWN / UP
 * action strings. This reader registers for both forms so the PTT
 * button works on both classic Sonim and MCX-mode carrier firmware
 * without requiring a firmware-variant check at startup. The central
 * [com.atakmap.android.xv.audio.PttDispatcher] OR-gate deduplicates
 * edges if a firmware emits both forms for the same press.
 *
 * Prerequisite on AT&T XP9900: operator must go to Settings → System
 * → Buttons (Programmable keys) → PTT key → set to "No Action" before
 * enabling this toggle. AT&T Dispatch Hub (`com.att.dh`) is
 * preinstalled and intercepts the PTT button by default; unsetting it
 * releases the key to the registered broadcast receiver.
 *
 * ---
 *
 * ### On-device validation status
 *
 * Classic Sonim intent path (`PTT_KEY_DOWN` / `PTT_KEY_UP`):
 * pending — no non-carrier XP9900 or XP10 confirmed yet.
 *
 * MCX path (`CRITICAL_COMMUNICATION_CONTROL_KEY` + `state` extra):
 * confirmed working on XP9900 AT&T carrier (Android 12) after
 * setting Programmable keys → PTT key → No Action.
 *
 * Both TODO items from the original PR are resolved: the MCX action
 * string is confirmed; the KeyEvent fallback ([SonimPttForegroundReader])
 * remains registered in parallel for foreground coverage.
 *
 * ---
 *
 * ### Foreground behaviour
 *
 * The receiver is registered on `start()` and unregistered on `stop()`.
 * On qualifying Sonim firmware the PTT_KEY_DOWN / _UP broadcasts are
 * delivered to every registered receiver regardless of app foreground
 * state, so PTT via the side key works while XV is in the background
 * (which is the entire point of the split-process service model this
 * reader lives inside — see [com.atakmap.android.xv.service.XvVoiceService]).
 *
 * Duplicate registrations are guarded internally: repeated `start()`
 * calls are no-ops if we're already listening, and `stop()` is safe to
 * call multiple times (including when we were never started).
 *
 * When the toggle is OFF or a different app owns the PTT mapping, this
 * reader does nothing — the key's normal Sonim-configured behaviour
 * (whatever the operator has set in Settings → Programmable keys) is
 * unaffected. Non-Sonim devices never see this reader instantiated at
 * all (see the gate in
 * [com.atakmap.android.xv.util.SonimHardwareButtons.isSupported]).
 */
class SonimPttButtonReader(
    private val context: Context,
    // Fired on key-down with `isDown = true` and on key-up with
    // `isDown = false`. Wired to PttDispatcher.down / up in
    // XvVoiceService alongside the AINA / Pryme readers.
    private val onEdge: (isDown: Boolean, source: PttSource) -> Unit,
) {
    private val receiver: BroadcastReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(
                ctx: Context,
                intent: Intent,
            ) {
                when (intent.action) {
                    SonimHardwareButtons.ACTION_PTT_KEY_DOWN -> {
                        Log.i(TAG, "Sonim PTT DOWN (broadcast, classic)")
                        try {
                            onEdge(true, PttSource.SONIM_PTT)
                        } catch (t: Throwable) {
                            Log.w(TAG, "onEdge(down) threw", t)
                        }
                    }
                    SonimHardwareButtons.ACTION_PTT_KEY_UP -> {
                        Log.i(TAG, "Sonim PTT UP (broadcast, classic)")
                        try {
                            onEdge(false, PttSource.SONIM_PTT)
                        } catch (t: Throwable) {
                            Log.w(TAG, "onEdge(up) threw", t)
                        }
                    }
                    SonimHardwareButtons.ACTION_MCX_KEY -> {
                        // MCX / MCPTT carrier firmware (e.g. AT&T XP9900):
                        // single action with a "state" integer extra.
                        // state=1 → pressed, state=0 → released.
                        // Missing or unknown state values are ignored with
                        // a warning so a malformed broadcast doesn't strand
                        // the OR-gate in a stuck-down condition.
                        val state = intent.getIntExtra(SonimHardwareButtons.MCX_EXTRA_STATE, -1)
                        when (state) {
                            SonimHardwareButtons.MCX_STATE_PRESSED -> {
                                Log.i(TAG, "Sonim PTT DOWN (broadcast, MCX state=1)")
                                try {
                                    onEdge(true, PttSource.SONIM_PTT)
                                } catch (t: Throwable) {
                                    Log.w(TAG, "onEdge(down/MCX) threw", t)
                                }
                            }
                            SonimHardwareButtons.MCX_STATE_RELEASED -> {
                                Log.i(TAG, "Sonim PTT UP (broadcast, MCX state=0)")
                                try {
                                    onEdge(false, PttSource.SONIM_PTT)
                                } catch (t: Throwable) {
                                    Log.w(TAG, "onEdge(up/MCX) threw", t)
                                }
                            }
                            else -> {
                                Log.w(TAG, "MCX_KEY broadcast — unknown state=$state — ignoring")
                            }
                        }
                    }
                    else -> {
                        // Ignore anything else the filter happens to
                        // deliver — defensive; the filter only subscribes
                        // to the three PTT actions.
                        Log.d(TAG, "unexpected action=${intent.action} — ignoring")
                    }
                }
            }
        }

    @Volatile
    private var registered: Boolean = false

    /**
     * Begin listening for Sonim PTT broadcasts on all supported action
     * strings: the classic `com.sonim.intent.action.PTT_KEY_DOWN/_UP`
     * pair and the MCX / MCPTT carrier firmware action
     * `com.mcx.intent.action.CRITICAL_COMMUNICATION_CONTROL_KEY`.
     * Idempotent: a second call while already listening is a no-op.
     *
     * Returns `true` if the receiver is now registered (either newly
     * or was already registered), `false` if registration failed
     * (e.g. the runtime rejected the receiver — defence-in-depth
     * against future firmware quirks).
     */
    fun start(): Boolean {
        synchronized(this) {
            if (registered) {
                Log.i(TAG, "start() ignored — receiver already registered")
                return true
            }
            val filter =
                IntentFilter().apply {
                    addAction(SonimHardwareButtons.ACTION_PTT_KEY_DOWN)
                    addAction(SonimHardwareButtons.ACTION_PTT_KEY_UP)
                    // MCX / MCPTT carrier firmware (e.g. AT&T XP9900):
                    // fires this single action with a "state" extra
                    // instead of separate _DOWN / _UP actions.
                    addAction(SonimHardwareButtons.ACTION_MCX_KEY)
                }
            return try {
                // NOT_EXPORTED: the broadcast originates from the Sonim
                // framework and is delivered by the system, so we do
                // not need to accept it from third-party apps. Uses
                // ContextCompat so the pre-Android-13 fallback path
                // also compiles cleanly.
                ContextCompat.registerReceiver(
                    context,
                    receiver,
                    filter,
                    ContextCompat.RECEIVER_NOT_EXPORTED,
                )
                registered = true
                Log.i(TAG, "Sonim PTT reader started (classic + MCX broadcast listener registered)")
                true
            } catch (t: Throwable) {
                Log.w(TAG, "registerReceiver(PTT_KEY_DOWN/UP + MCX_KEY) threw", t)
                false
            }
        }
    }

    /**
     * Stop listening. Idempotent — safe to call if we never started,
     * if we already stopped, or if the receiver was already stripped
     * out from under us by a runtime restart. Any in-flight PTT-down
     * edge (PTT held when the operator toggles the feature off) is the
     * caller's responsibility to release via the dispatcher's
     * [com.atakmap.android.xv.audio.PttDispatcher.forgetSource].
     */
    fun stop() {
        synchronized(this) {
            if (!registered) return
            try {
                context.unregisterReceiver(receiver)
            } catch (_: IllegalArgumentException) {
                // Never registered, or already unregistered — nothing
                // actionable. Matches the swallow pattern used by
                // XvVoiceService for the other broadcast receivers.
            } catch (t: Throwable) {
                Log.w(TAG, "unregisterReceiver threw", t)
            }
            registered = false
            Log.i(TAG, "Sonim PTT reader stopped")
        }
    }

    /** True while the broadcast receiver is currently registered. */
    fun isRunning(): Boolean = registered

    companion object {
        private const val TAG = "XvSonimPtt"
    }
}
