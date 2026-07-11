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
 * ### On-device validation status
 *
 * As of 2026-07-10 the XV operator does NOT have a Sonim XP10 on hand
 * for direct validation. The action strings, keycodes, and general
 * "no SDK required" story are grounded in the Sonim / Android
 * community references cited in
 * [com.atakmap.android.xv.util.SonimHardwareButtons]. When the
 * operator does obtain an XP10, the on-device validation TODOs are:
 *
 *   - Confirm the exact action strings (`_DOWN` / `_UP`) fire on both
 *     edges. Some Sonim firmwares emit only a single "PTT_KEY" action
 *     with a `state` extra rather than two distinct actions; if that
 *     turns out to be the case, extend the receiver to consult
 *     `intent.getIntExtra("state", -1)` (0/1) inside a single
 *     `onReceive` branch.
 *   - Confirm the operator DOES need to visit Settings → Programmable
 *     keys and assign PTT to XV, versus the intents being emitted
 *     unconditionally. If the intents are unconditional the toggle
 *     works out-of-the-box; if they're gated on a Sonim system
 *     setting, the Settings row help text should say so.
 *
 * Both TODOs are non-blocking — the code compiles and behaves
 * correctly under the most-likely mapping. The KeyEvent fallback
 * ([SonimPttForegroundReader]) is registered in parallel so the
 * operator always has at least one working path if one of the two
 * turns out to be firmware-quirked.
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
                        Log.i(TAG, "Sonim PTT DOWN (broadcast)")
                        try {
                            onEdge(true, PttSource.SONIM_PTT)
                        } catch (t: Throwable) {
                            Log.w(TAG, "onEdge(down) threw", t)
                        }
                    }
                    SonimHardwareButtons.ACTION_PTT_KEY_UP -> {
                        Log.i(TAG, "Sonim PTT UP (broadcast)")
                        try {
                            onEdge(false, PttSource.SONIM_PTT)
                        } catch (t: Throwable) {
                            Log.w(TAG, "onEdge(up) threw", t)
                        }
                    }
                    else -> {
                        // Ignore anything else the filter happens to
                        // deliver — defensive; the filter above only
                        // subscribes to the two actions.
                        Log.d(TAG, "unexpected action=${intent.action} — ignoring")
                    }
                }
            }
        }

    @Volatile
    private var registered: Boolean = false

    /**
     * Begin listening for `com.sonim.intent.action.PTT_KEY_DOWN/_UP`
     * broadcasts. Idempotent: a second call while already listening is
     * a no-op and logs at INFO so the redundancy is visible in field
     * logs without spamming.
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
                Log.i(TAG, "Sonim PTT reader started (broadcast listener registered)")
                true
            } catch (t: Throwable) {
                Log.w(TAG, "registerReceiver(PTT_KEY_DOWN/UP) threw", t)
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
