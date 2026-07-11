package com.atakmap.android.xv.ptt

import android.util.Log
import android.view.KeyEvent
import android.view.View
import com.atakmap.android.maps.MapView
import com.atakmap.android.xv.audio.PttSource
import com.atakmap.android.xv.util.SamsungActiveKey

/**
 * Foreground-KeyEvent fallback path for the Samsung Active Key.
 *
 * Companion to [SamsungActiveKeyReader] (which listens for the
 * `HARD_KEY_REPORT` broadcast). Both paths coexist and dedupe via the
 * central [com.atakmap.android.xv.audio.PttDispatcher]'s source-based
 * OR-gate — a duplicate down edge for
 * [PttSource.SAMSUNG_ACTIVE_KEY] while it's already held is ignored,
 * and this reader further short-circuits back-to-back `ACTION_DOWN`s
 * for its own state so the log output stays clean.
 *
 * ---
 *
 * Why the fallback exists:
 *
 * On-device validation 2026-07-10 against a Samsung Galaxy Tab
 * Active5 (SM-X308U) confirmed that shipping firmware does NOT emit
 * the `com.samsung.android.knox.intent.action.HARD_KEY_REPORT`
 * broadcast for `KEY_CODE == 1015`, despite what the Samsung Knox
 * documentation implies. The operator's Settings → Advanced features
 * → Active Key surface exposes only "launch app" mappings, which fire
 * one intent on press and produce no release signal — unusable as a
 * PTT source.
 *
 * The plain [KeyEvent] path, in contrast, produces both `ACTION_DOWN`
 * (with `repeatCount == 0`) and `ACTION_UP` for `KEYCODE == 1015` and
 * is delivered to the foreground activity by
 * `PhoneWindowManager.interceptKeyTq` → `InputDispatcher`. That
 * matches what PTT needs on both edges.
 *
 * ---
 *
 * Trade-off / operational limitation:
 *
 * Because `InputDispatcher` routes non-broadcast keys to the top
 * activity, this reader only sees events while ATAK is in the
 * foreground. That's the same constraint any hardware-button PTT app
 * has when it is neither a system app nor an IME nor an accessibility
 * service — no way around it without one of those privileges. Backing
 * the reader with an accessibility service is a non-starter for a
 * plugin (privacy prompt cost).
 *
 * The broadcast reader ([SamsungActiveKeyReader]) IS backgrounded-safe
 * and is still the ideal — on hardware that does emit the broadcast,
 * both paths fire and PttDispatcher's dedupe collapses the duplicate
 * down / up cleanly. On Tab Active5-class firmware where only the
 * KeyEvent fires, this path is the sole PTT source for the Active
 * Key, and it works whenever XV is what the operator is looking at.
 *
 * ---
 *
 * Device gate: same as the broadcast path — the caller
 * ([com.atakmap.android.xv.plugin.XvMapComponent]) short-circuits on
 * [com.atakmap.android.xv.util.SamsungActiveKey.isSupported] so a
 * non-Samsung device never instantiates this reader.
 */
class SamsungActiveKeyForegroundReader(
    // Fired on the leading down edge (with `isDown = true`) and on the
    // release (`isDown = false`). Wired to
    // XvVoiceService.notifySamsungActiveKeyEdge in the plugin, which
    // routes through the service's PttDispatcher.down(..., source =
    // SAMSUNG_ACTIVE_KEY) / up(...) in the correct process.
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
            when (SamsungActiveKey.handleKeyEvent(keyCode, event.action, event.repeatCount)) {
                SamsungActiveKey.FallbackAction.PTT_DOWN -> {
                    if (held) {
                        // Repeat / re-entry after a lost UP — drop.
                        Log.d(TAG, "Samsung Active Key DOWN (via KeyEvent foreground) — already held; dropping duplicate")
                        return@OnKeyListener true
                    }
                    held = true
                    Log.i(TAG, "Samsung Active Key DOWN (via KeyEvent foreground)")
                    try {
                        onEdge(true, PttSource.SAMSUNG_ACTIVE_KEY)
                    } catch (t: Throwable) {
                        Log.w(TAG, "onEdge(down) threw", t)
                    }
                    true
                }
                SamsungActiveKey.FallbackAction.PTT_UP -> {
                    if (!held) {
                        // UP without a matching DOWN — likely delivered
                        // to us after the foreground shifted mid-press.
                        // Suppress to avoid a spurious txController.stop
                        // for a burst we never engaged.
                        Log.d(TAG, "Samsung Active Key UP (via KeyEvent foreground) — not held; dropping")
                        return@OnKeyListener true
                    }
                    held = false
                    Log.i(TAG, "Samsung Active Key UP (via KeyEvent foreground)")
                    try {
                        onEdge(false, PttSource.SAMSUNG_ACTIVE_KEY)
                    } catch (t: Throwable) {
                        Log.w(TAG, "onEdge(up) threw", t)
                    }
                    true
                }
                SamsungActiveKey.FallbackAction.IGNORE -> {
                    // Not our key / not an edge we care about — let ATAK
                    // and any downstream OnKeyListener handle it.
                    false
                }
            }
        }

    /**
     * Attach the KeyEvent listener to [mapView]. Idempotent — a second
     * call while already attached is a no-op. Returns true if the
     * listener is currently attached after the call, false only if the
     * runtime rejected the attach.
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
                Log.i(TAG, "Samsung Active Key foreground KeyEvent reader started")
                true
            } catch (t: Throwable) {
                Log.w(TAG, "addOnKeyListener threw", t)
                false
            }
        }
    }

    /**
     * Detach the KeyEvent listener. Idempotent — safe to call if never
     * started, if already stopped, or if the MapView was torn down
     * from under us.
     *
     * If a press was in flight (the operator toggled the feature off
     * or the plugin unloaded while the Active Key was held), the
     * dispatcher-side cleanup is the caller's responsibility — this
     * class only owns the KeyEvent hook. Callers should invoke
     * [com.atakmap.android.xv.audio.PttDispatcher.forgetSource] with
     * [PttSource.SAMSUNG_ACTIVE_KEY] on stop.
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
            Log.i(TAG, "Samsung Active Key foreground KeyEvent reader stopped")
        }
    }

    /** True while the KeyEvent listener is currently attached. */
    fun isRunning(): Boolean = attached

    companion object {
        private const val TAG = "XvSamsungActiveKeyFg"
    }
}
