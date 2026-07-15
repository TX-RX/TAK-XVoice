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
 * PTT reader for the dedicated Emergency / SOS button on Sonim
 * ruggedized hardware (XP10 / XP9900 and XP-family peers).
 *
 * Mechanism: the Sonim / ruggedized-Android framework broadcasts
 * `android.intent.action.SOS.down` when the operator presses the red
 * SOS key, and `android.intent.action.SOS.up` on release. Unlike the
 * PTT_KEY intents (which are Sonim-namespaced), the SOS broadcast
 * reuses the Android-style `.down` / `.up` action convention that
 * multiple ruggedized OEMs (Sonim, Ruggear, other lone-worker OEMs)
 * consistently emit. No Sonim SDK or partner entitlement is required
 * to receive the broadcast — any registered
 * [android.content.BroadcastReceiver] gets the events.
 *
 * The receiver is registered with `RECEIVER_NOT_EXPORTED` because the
 * broadcast originates from the framework, not from a third-party app.
 *
 * ---
 *
 * ### Emergency-alert wiring (AINA-PTTE parity)
 *
 * Press / release edges are delivered to the caller's [onEdge]
 * lambda tagged with [PttSource.SONIM_EMERGENCY]. As of 2026-07-14
 * the caller ([com.atakmap.android.xv.service.VoicePlant.startSonimEmergencyButton])
 * routes those edges into
 * [com.atakmap.android.xv.emergency.EmergencyController.onEmergencyButton]
 * via [com.atakmap.android.xv.service.PlantCallbacks.onEmergencyButton]
 * — the same emergency-dispatch path the AINA PTTE key uses.
 *
 * A short press fires ATAK's Alert Tool via
 * [com.atakmap.android.xv.emergency.AtakEmergencyDispatcher.firePanic];
 * a long-hold cancels. The button does NOT open a Telecom call or
 * transmit voice — the "SOS" chassis label is treated as literal
 * alert semantics, matching AINA PTTE behaviour. The
 * [PttSource.SONIM_EMERGENCY] tag survives on the [onEdge] boundary
 * for distinct log strings ("XvSonimEmergency") and future analytics.
 *
 * ---
 *
 * ### On-device validation status
 *
 * As of 2026-07-10 the XV operator does NOT have a Sonim XP10 on hand
 * for direct validation. The action strings and general "no SDK
 * required" story are grounded in the community references cited in
 * [com.atakmap.android.xv.util.SonimHardwareButtons]. When the
 * operator does obtain an XP10, the on-device validation TODOs are:
 *
 *   - Confirm the exact action strings on both edges. Some ruggedized
 *     firmware emits only a single "SOS" action with a state extra;
 *     if so, extend the receiver like the PTT counterpart.
 *   - Confirm that a press does not also invoke the Sonim SOS app
 *     (which would prompt for lone-worker workflow). If the SOS app
 *     intercepts before our receiver runs, the operator may need to
 *     re-map the SOS button target via Sonim system settings — flag
 *     that in the Settings row help text.
 *   - Confirm distinct log tag "XvSonimEmergency" appears in logcat
 *     on press so on-device debugging can distinguish the two Sonim
 *     buttons.
 *
 * Non-blocking — the code compiles and behaves under the most-likely
 * mapping. The KeyEvent fallback ([SonimEmergencyForegroundReader])
 * is registered in parallel as a second signal source.
 *
 * ---
 *
 * ### Foreground behaviour
 *
 * The receiver is registered on `start()` and unregistered on `stop()`.
 * On qualifying firmware the SOS broadcasts are delivered to every
 * registered receiver regardless of app foreground state, so PTT via
 * the SOS button works while XV is in the background. Duplicate
 * registrations are guarded internally.
 *
 * On non-Sonim devices this reader is never instantiated (see the
 * gate in [com.atakmap.android.xv.util.SonimHardwareButtons.isSupported]).
 */
class SonimEmergencyButtonReader(
    private val context: Context,
    // Fired on key-down with `isDown = true` and on key-up with
    // `isDown = false`. Wired to PttDispatcher.down / up in
    // XvVoiceService alongside the AINA / Pryme readers, tagged
    // PttSource.SONIM_EMERGENCY so the source-based OR-gate and any
    // future emergency-dispatch coupling can distinguish it from the
    // side PTT key.
    private val onEdge: (isDown: Boolean, source: PttSource) -> Unit,
) {
    private val receiver: BroadcastReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(
                ctx: Context,
                intent: Intent,
            ) {
                when (intent.action) {
                    SonimHardwareButtons.ACTION_SOS_DOWN -> {
                        Log.i(TAG, "Sonim EMERGENCY DOWN (broadcast)")
                        try {
                            onEdge(true, PttSource.SONIM_EMERGENCY)
                        } catch (t: Throwable) {
                            Log.w(TAG, "onEdge(down) threw", t)
                        }
                    }
                    SonimHardwareButtons.ACTION_SOS_UP -> {
                        Log.i(TAG, "Sonim EMERGENCY UP (broadcast)")
                        try {
                            onEdge(false, PttSource.SONIM_EMERGENCY)
                        } catch (t: Throwable) {
                            Log.w(TAG, "onEdge(up) threw", t)
                        }
                    }
                    else -> {
                        Log.d(TAG, "unexpected action=${intent.action} — ignoring")
                    }
                }
            }
        }

    @Volatile
    private var registered: Boolean = false

    /**
     * Begin listening for `android.intent.action.SOS.down` / `_up`
     * broadcasts. Idempotent.
     */
    fun start(): Boolean {
        synchronized(this) {
            if (registered) {
                Log.i(TAG, "start() ignored — receiver already registered")
                return true
            }
            val filter =
                IntentFilter().apply {
                    addAction(SonimHardwareButtons.ACTION_SOS_DOWN)
                    addAction(SonimHardwareButtons.ACTION_SOS_UP)
                }
            return try {
                ContextCompat.registerReceiver(
                    context,
                    receiver,
                    filter,
                    ContextCompat.RECEIVER_NOT_EXPORTED,
                )
                registered = true
                Log.i(TAG, "Sonim Emergency reader started (broadcast listener registered)")
                true
            } catch (t: Throwable) {
                Log.w(TAG, "registerReceiver(SOS.down/up) threw", t)
                false
            }
        }
    }

    /**
     * Stop listening. Idempotent. Callers are responsible for calling
     * [com.atakmap.android.xv.audio.PttDispatcher.forgetSource] with
     * [PttSource.SONIM_EMERGENCY] to release any in-flight held state.
     */
    fun stop() {
        synchronized(this) {
            if (!registered) return
            try {
                context.unregisterReceiver(receiver)
            } catch (_: IllegalArgumentException) {
                // Never registered — nothing actionable.
            } catch (t: Throwable) {
                Log.w(TAG, "unregisterReceiver threw", t)
            }
            registered = false
            Log.i(TAG, "Sonim Emergency reader stopped")
        }
    }

    /** True while the broadcast receiver is currently registered. */
    fun isRunning(): Boolean = registered

    companion object {
        // Distinct log tag from SonimPttButtonReader (`XvSonimPtt`) so
        // on-device logcat filtering by tag separates the two Sonim
        // buttons at a glance.
        private const val TAG = "XvSonimEmergency"
    }
}
