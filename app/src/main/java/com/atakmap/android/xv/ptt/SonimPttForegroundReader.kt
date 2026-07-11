package com.atakmap.android.xv.ptt

import android.util.Log
import android.view.View
import com.atakmap.android.maps.MapView
import com.atakmap.android.xv.audio.PttSource
import com.atakmap.android.xv.util.SonimHardwareButtons

/**
 * Foreground-KeyEvent fallback path for the Sonim dedicated PTT
 * button.
 *
 * Companion to [SonimPttButtonReader] (which listens for the
 * `com.sonim.intent.action.PTT_KEY_DOWN/_UP` broadcasts). Both paths
 * coexist and dedupe via the central
 * [com.atakmap.android.xv.audio.PttDispatcher]'s source-based OR-gate
 * — a duplicate down edge for [PttSource.SONIM_PTT] while it's
 * already held is ignored, and this reader further short-circuits
 * back-to-back `ACTION_DOWN`s for its own state so the log output
 * stays clean.
 *
 * ---
 *
 * ### Why the fallback exists
 *
 * Sonim ruggedized firmware historically supports BOTH signal paths
 * for the dedicated PTT key:
 *
 *   - The broadcast intent path is backgrounded-safe but requires the
 *     operator to have the app selected as the PTT target in Sonim's
 *     Programmable Keys settings.
 *   - The plain [android.view.KeyEvent] path is delivered to the
 *     foreground activity by `PhoneWindowManager.interceptKeyTq` →
 *     `InputDispatcher`, requires no per-app configuration, but works
 *     only while ATAK is what the operator is looking at.
 *
 * Registering both paths in parallel means the operator's first-run
 * UX is "hold the PTT key while XV is up and it just works", with the
 * backgrounded-safe broadcast path activating as soon as they map the
 * button in Sonim system settings.
 *
 * ---
 *
 * ### Trade-off / operational limitation
 *
 * Because `InputDispatcher` routes non-broadcast keys to the top
 * activity, this reader only sees events while ATAK is in the
 * foreground. That's the same constraint any hardware-button PTT app
 * has when it is neither a system app nor an IME nor an accessibility
 * service — no way around it without one of those privileges.
 *
 * The broadcast reader ([SonimPttButtonReader]) IS backgrounded-safe
 * and is still the ideal — on hardware that does emit the broadcast
 * with the operator's mapping applied, both paths fire and
 * PttDispatcher's dedupe collapses the duplicate down / up cleanly.
 *
 * ---
 *
 * ### Device gate
 *
 * Same as the broadcast path — the caller
 * ([com.atakmap.android.xv.plugin.XvMapComponent]) short-circuits on
 * [com.atakmap.android.xv.util.SonimHardwareButtons.isSupported] so a
 * non-Sonim device never instantiates this reader.
 *
 * ---
 *
 * ### On-device validation TODO
 *
 * The exact keycode(s) the XP10 side PTT emits are pending on-device
 * confirmation — see the primary / alt constants in
 * [com.atakmap.android.xv.util.SonimHardwareButtons]. Both candidate
 * keycodes are accepted so the operator can validate without a
 * firmware update; whichever one fires in field capture will be the
 * one we prune to in a follow-up.
 */
class SonimPttForegroundReader(
    // Fired on the leading down edge (with `isDown = true`) and on the
    // release (`isDown = false`). Wired to
    // XvVoiceService.notifySonimPttEdge in the plugin, which routes
    // through the service's PttDispatcher.down(..., source = SONIM_PTT)
    // / up(...) in the correct process.
    private val onEdge: (isDown: Boolean, source: PttSource) -> Unit,
) {
    // Track our own held-state so we drop a stray KeyEvent that would
    // otherwise fire an already-held down or already-released up edge.
    // Two motivations:
    //   1) Guard against InputDispatcher redelivering the same key
    //      when the foreground activity changes mid-hold.
    //   2) Keep the log narrative honest — one "DOWN" line per real
    //      press, one "UP" line per real release.
    @Volatile
    private var held: Boolean = false

    @Volatile
    private var attached: Boolean = false

    // Package-visible so unit tests can synthesize a KeyEvent against
    // the listener directly, without needing a live MapView (which
    // requires an ATAK-runtime bring-up we don't want in a Robolectric
    // suite).
    internal val listener =
        View.OnKeyListener { _, keyCode, event ->
            when (SonimHardwareButtons.handlePttKeyEvent(keyCode, event.action, event.repeatCount)) {
                SonimHardwareButtons.FallbackAction.PTT_DOWN -> {
                    if (held) {
                        Log.d(TAG, "Sonim PTT DOWN (KeyEvent fg) — already held; dropping duplicate")
                        return@OnKeyListener true
                    }
                    held = true
                    Log.i(TAG, "Sonim PTT DOWN (KeyEvent fg)")
                    try {
                        onEdge(true, PttSource.SONIM_PTT)
                    } catch (t: Throwable) {
                        Log.w(TAG, "onEdge(down) threw", t)
                    }
                    true
                }
                SonimHardwareButtons.FallbackAction.PTT_UP -> {
                    if (!held) {
                        Log.d(TAG, "Sonim PTT UP (KeyEvent fg) — not held; dropping")
                        return@OnKeyListener true
                    }
                    held = false
                    Log.i(TAG, "Sonim PTT UP (KeyEvent fg)")
                    try {
                        onEdge(false, PttSource.SONIM_PTT)
                    } catch (t: Throwable) {
                        Log.w(TAG, "onEdge(up) threw", t)
                    }
                    true
                }
                SonimHardwareButtons.FallbackAction.IGNORE -> {
                    // Not our key — let ATAK and any downstream
                    // OnKeyListener handle it.
                    false
                }
            }
        }

    /**
     * Attach the KeyEvent listener to [mapView]. Idempotent — a second
     * call while already attached is a no-op.
     */
    fun start(mapView: MapView): Boolean {
        synchronized(this) {
            if (attached) {
                Log.i(TAG, "start() ignored — foreground listener already attached")
                return true
            }
            return try {
                mapView.addOnKeyListener(listener)
                attached = true
                Log.i(TAG, "Sonim PTT foreground KeyEvent reader started")
                true
            } catch (t: Throwable) {
                Log.w(TAG, "addOnKeyListener threw", t)
                false
            }
        }
    }

    /**
     * Detach the KeyEvent listener. Idempotent. Callers are responsible
     * for firing a defensive `notifySonimPttEdge(false)` on stop so the
     * service dispatcher doesn't strand [PttSource.SONIM_PTT] in its
     * held-source set.
     */
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
            Log.i(TAG, "Sonim PTT foreground KeyEvent reader stopped")
        }
    }

    /** True while the KeyEvent listener is currently attached. */
    fun isRunning(): Boolean = attached

    companion object {
        private const val TAG = "XvSonimPttFg"
    }
}
