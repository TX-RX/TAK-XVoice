package com.atakmap.android.xv.ptt

import android.util.Log
import android.view.View
import com.atakmap.android.maps.MapView
import com.atakmap.android.xv.audio.PttSource
import com.atakmap.android.xv.util.SonimHardwareButtons

/**
 * Foreground-KeyEvent fallback path for the Sonim dedicated
 * Emergency / SOS button.
 *
 * Companion to [SonimEmergencyButtonReader] (the broadcast path).
 * Both coexist and dedupe via
 * [com.atakmap.android.xv.audio.PttDispatcher]'s source-based OR-gate
 * — a duplicate down edge for [PttSource.SONIM_EMERGENCY] while it's
 * already held is ignored. The listener also short-circuits back-to-
 * back downs for its own state.
 *
 * The keycode is `KEYCODE_SOS` (1079), the Android platform integer
 * used across ruggedized OEMs for the dedicated red SOS key. See
 * [com.atakmap.android.xv.util.SonimHardwareButtons.SOS_KEY_CODE] for
 * the citation.
 *
 * ### Distinct source, plain-PTT behaviour for now
 *
 * Presses fire under [PttSource.SONIM_EMERGENCY], not
 * [PttSource.SONIM_PTT] — the tag distinction is what makes a future
 * "promote to CoT emergency broadcast" change a local edit. For this
 * PR, both the emergency and PTT paths key slot 0 through the normal
 * dispatcher.
 *
 * Distinct log tag `XvSonimEmergencyFg` from the PTT foreground
 * reader (`XvSonimPttFg`) so on-device logcat filtering distinguishes
 * the two Sonim buttons at a glance.
 *
 * ### Foreground-only
 *
 * Same trade-off as [SonimPttForegroundReader]:  InputDispatcher
 * routes KeyEvents to the top activity only. The broadcast path is
 * the backgrounded-safe route for the SOS button — see
 * [SonimEmergencyButtonReader].
 */
class SonimEmergencyForegroundReader(
    private val onEdge: (isDown: Boolean, source: PttSource) -> Unit,
) {
    @Volatile
    private var held: Boolean = false

    @Volatile
    private var attached: Boolean = false

    internal val listener =
        View.OnKeyListener { _, keyCode, event ->
            when (SonimHardwareButtons.handleEmergencyKeyEvent(keyCode, event.action, event.repeatCount)) {
                SonimHardwareButtons.FallbackAction.PTT_DOWN -> {
                    if (held) {
                        Log.d(TAG, "Sonim EMERGENCY DOWN (KeyEvent fg) — already held; dropping duplicate")
                        return@OnKeyListener true
                    }
                    held = true
                    Log.i(TAG, "Sonim EMERGENCY DOWN (KeyEvent fg)")
                    try {
                        onEdge(true, PttSource.SONIM_EMERGENCY)
                    } catch (t: Throwable) {
                        Log.w(TAG, "onEdge(down) threw", t)
                    }
                    true
                }
                SonimHardwareButtons.FallbackAction.PTT_UP -> {
                    if (!held) {
                        Log.d(TAG, "Sonim EMERGENCY UP (KeyEvent fg) — not held; dropping")
                        return@OnKeyListener true
                    }
                    held = false
                    Log.i(TAG, "Sonim EMERGENCY UP (KeyEvent fg)")
                    try {
                        onEdge(false, PttSource.SONIM_EMERGENCY)
                    } catch (t: Throwable) {
                        Log.w(TAG, "onEdge(up) threw", t)
                    }
                    true
                }
                SonimHardwareButtons.FallbackAction.IGNORE -> false
            }
        }

    fun start(mapView: MapView): Boolean {
        synchronized(this) {
            if (attached) {
                Log.i(TAG, "start() ignored — foreground listener already attached")
                return true
            }
            return try {
                mapView.addOnKeyListener(listener)
                attached = true
                Log.i(TAG, "Sonim Emergency foreground KeyEvent reader started")
                true
            } catch (t: Throwable) {
                Log.w(TAG, "addOnKeyListener threw", t)
                false
            }
        }
    }

    fun stop(mapView: MapView?) {
        synchronized(this) {
            if (!attached) return
            if (mapView != null) {
                try {
                    mapView.removeOnKeyListener(listener)
                } catch (t: Throwable) {
                    Log.w(TAG, "removeOnKeyListener threw", t)
                }
            }
            attached = false
            held = false
            Log.i(TAG, "Sonim Emergency foreground KeyEvent reader stopped")
        }
    }

    fun isRunning(): Boolean = attached

    companion object {
        private const val TAG = "XvSonimEmergencyFg"
    }
}
