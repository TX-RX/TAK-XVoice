package com.atakmap.android.xv.ptt

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import androidx.core.content.ContextCompat
import com.atakmap.android.xv.audio.PttSource
import com.atakmap.android.xv.util.SamsungActiveKey

/**
 * PTT reader for the programmable Active Key on Samsung ruggedized
 * hardware (Galaxy Tab Active5, Tab Active4 Pro, Tab Active3, XCover6
 * Pro, XCover7).
 *
 * Mechanism: the Samsung framework broadcasts
 * `com.samsung.android.knox.intent.action.HARD_KEY_REPORT` on every
 * press and release of the side Active Key. The broadcast carries
 * `KEY_CODE` (int; 1015 for the side PTT key) and `KEY_REPORT_TYPE`
 * (1 = press, 2 = release). No Knox SDK, license, or enterprise
 * entitlement is required to RECEIVE the broadcast — any registered
 * receiver in the system gets the event.
 *
 * We register the receiver with `RECEIVER_NOT_EXPORTED` (Android 13+
 * flag) because the intent comes from the Samsung framework itself,
 * not from a third-party app targeting us — no need to widen the
 * receiver's exported surface.
 *
 * ---
 *
 * Foreground behaviour: the receiver is registered on `start()` and
 * unregistered on `stop()`. On Samsung ruggedized firmware the
 * `HARD_KEY_REPORT` broadcast is delivered to every registered
 * receiver regardless of app foreground state, so PTT via the Active
 * Key works while XV is in the background (which is the entire point
 * of the split-process service model this reader lives inside — see
 * [com.atakmap.android.xv.service.XvVoiceService]).
 *
 * Duplicate registrations are guarded internally: repeated `start()`
 * calls are no-ops if we're already listening, and `stop()` is safe to
 * call multiple times (including when we were never started).
 *
 * When the toggle is OFF or a different app is in the foreground, this
 * reader does nothing — the key's normal Samsung-configured behaviour
 * (whatever the operator has set in Settings → Advanced features →
 * Active Key) is unaffected. Non-Samsung devices never see this reader
 * instantiated at all (see the gate in
 * [com.atakmap.android.xv.util.SamsungActiveKey.isSupported]).
 */
class SamsungActiveKeyReader(
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
                if (intent.action != SamsungActiveKey.ACTION_HARD_KEY_REPORT) return
                val keyCode =
                    intent.getIntExtra(
                        SamsungActiveKey.EXTRA_KEY_CODE,
                        -1,
                    )
                if (keyCode != SamsungActiveKey.KEY_CODE_PTT) {
                    // Ignore the Top / Emergency key (KEY_CODE_EMERGENCY
                    // = 1079) and anything else the framework happens to
                    // broadcast. Emergency key routing is out of scope
                    // for this reader — the emergency subsystem
                    // ([com.atakmap.android.xv.emergency]) is wired to
                    // AINA PTTE only.
                    return
                }
                val reportType =
                    intent.getIntExtra(
                        SamsungActiveKey.EXTRA_KEY_REPORT_TYPE,
                        -1,
                    )
                when (reportType) {
                    SamsungActiveKey.KEY_REPORT_TYPE_PRESSED -> {
                        Log.i(TAG, "Samsung Active Key DOWN")
                        try {
                            onEdge(true, PttSource.SAMSUNG_ACTIVE_KEY)
                        } catch (t: Throwable) {
                            Log.w(TAG, "onEdge(down) threw", t)
                        }
                    }
                    SamsungActiveKey.KEY_REPORT_TYPE_RELEASED -> {
                        Log.i(TAG, "Samsung Active Key UP")
                        try {
                            onEdge(false, PttSource.SAMSUNG_ACTIVE_KEY)
                        } catch (t: Throwable) {
                            Log.w(TAG, "onEdge(up) threw", t)
                        }
                    }
                    else -> {
                        Log.d(TAG, "unexpected KEY_REPORT_TYPE=$reportType — ignoring")
                    }
                }
            }
        }

    @Volatile
    private var registered: Boolean = false

    /**
     * Begin listening for `HARD_KEY_REPORT` broadcasts. Idempotent: a
     * second call while already listening is a no-op and logs at INFO
     * so the redundancy is visible in field logs without spamming.
     *
     * Returns `true` if the receiver is now registered (either newly
     * or was already registered), `false` if registration failed
     * (e.g. the runtime rejected the receiver on a non-Samsung device
     * masquerading as one — defence-in-depth against future firmware
     * quirks).
     */
    fun start(): Boolean {
        synchronized(this) {
            if (registered) {
                Log.i(TAG, "start() ignored — receiver already registered")
                return true
            }
            val filter = IntentFilter(SamsungActiveKey.ACTION_HARD_KEY_REPORT)
            return try {
                // NOT_EXPORTED: the broadcast originates from the
                // Samsung framework and is delivered by the system,
                // so we do not need to accept it from third-party
                // apps. Uses ContextCompat so the pre-Android-13
                // fallback path also compiles cleanly.
                ContextCompat.registerReceiver(
                    context,
                    receiver,
                    filter,
                    ContextCompat.RECEIVER_NOT_EXPORTED,
                )
                registered = true
                Log.i(TAG, "Samsung Active Key reader started (HARD_KEY_REPORT listener registered)")
                true
            } catch (t: Throwable) {
                Log.w(TAG, "registerReceiver(HARD_KEY_REPORT) threw", t)
                false
            }
        }
    }

    /**
     * Stop listening. Idempotent — safe to call if we never started,
     * if we already stopped, or if the receiver was already stripped
     * out from under us by a runtime restart. Any in-flight PTT-down
     * edge (Active Key held when the operator toggles the feature
     * off) is the caller's responsibility to release via the
     * dispatcher's [com.atakmap.android.xv.audio.PttDispatcher.forgetSource].
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
            Log.i(TAG, "Samsung Active Key reader stopped")
        }
    }

    /** True while the broadcast receiver is currently registered. */
    fun isRunning(): Boolean = registered

    companion object {
        private const val TAG = "XvSamsungActiveKey"
    }
}
