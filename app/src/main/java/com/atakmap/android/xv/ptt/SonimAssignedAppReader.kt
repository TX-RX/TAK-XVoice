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
 * Receiver for the "Assigned to ATAK" broadcast delivery mode used by
 * Sonim ruggedized handsets that route programmable keys to a chosen
 * app package (Settings → System → Buttons → Programmable Keys →
 * <key> → assign to app).
 *
 * When the operator assigns PTT / SOS / Yellow to ATAK on those
 * handsets, Sonim's WindowManager fires each press as a broadcast
 * with `Intent.setPackage("com.atakmap.app.civ")`. Because the intent
 * is pkg-scoped, only receivers running **in ATAK's process** see it —
 * the XV service process ([SonimEmergencyButtonReader],
 * [SonimPttButtonReader]) is silent for this delivery mode. This
 * reader is instantiated from [com.atakmap.android.xv.plugin.XvMapComponent],
 * which runs inside ATAK's process, so the pkg-scoped delivery reaches it.
 *
 * ---
 *
 * ### Key → action mapping (field-verified 2026-07-14, XP9900)
 *
 *   - Yellow key (Sonim "convenience" key, keyCode 291):
 *     `com.sonim.intent.action.YELLOW_KEY_DOWN` / `_UP`
 *     → routed to PTT (source [PttSource.SONIM_PTT])
 *     Operators on the XP9900 chassis use the Yellow key as their
 *     effective PTT trigger; the button labelled "PTT" (keyCode 228)
 *     is not what they press.
 *
 *   - SOS key (red top button, keyCode 294):
 *     `com.sonim.intent.action.SOS_KEY_DOWN` / `_UP` +
 *     `com.kodiak.intent.action.KEYCODE_SOS` (fires alongside on the
 *     same press — Kodiak MCPTT-stack redundant emission)
 *     → routed to emergency (matches AINA PTTE behaviour: short-press
 *     fires ATAK Alert, long-hold cancels; NO voice keying)
 *
 * ---
 *
 * ### Dedup contract
 *
 * On the XP9900 the SOS press produces two down events in the same
 * millisecond (SOS_KEY_DOWN + KODIAK_SOS). The held-state guard in
 * this reader drops the second — the OR-gate downstream also handles
 * duplicates but doing it here keeps the log clean and the
 * emergency-controller state machine from being reset by the
 * redundant edge.
 *
 * ---
 *
 * ### Coexistence with the service-process readers
 *
 * The service-process [SonimEmergencyButtonReader] /
 * [SonimPttButtonReader] listen for the *classic* Sonim broadcast
 * actions (`android.intent.action.SOS.down`, `PTT_KEY_DOWN`, etc.)
 * emitted in unbroadcast (non-pkg-scoped) form by other Sonim
 * firmware variants (XP10 non-carrier, etc.). Both readers coexist
 * so a single build covers both delivery modes without a variant
 * check at startup. If a firmware happens to emit both classic and
 * assigned-app forms for the same press, the downstream dispatchers
 * (PttDispatcher OR-gate for PTT, EmergencyController state machine
 * for SOS) suppress the duplicate.
 */
class SonimAssignedAppReader(
    private val context: Context,
    // Called on any Yellow-key edge (assigned-app PTT). isDown = true
    // on press, false on release. Wired from XvMapComponent to
    // IXvVoice.notifySonimPttEdge → plant.pttDown/pttUp(SONIM_PTT).
    private val onYellowKeyEdge: (isDown: Boolean) -> Unit,
    // Called on any SOS-key edge (assigned-app emergency). isDown =
    // true on press, false on release. Wired from XvMapComponent to
    // IXvVoice.notifySonimEmergencyEdge → plant.onSonimEmergencyEdge
    // → callbacks.onEmergencyButton → EmergencyController.
    private val onSosKeyEdge: (isDown: Boolean) -> Unit,
) {
    // Independent per-source held-state guards. SOS emits two down
    // actions in a single millisecond (Sonim + Kodiak); both need to
    // collapse to a single edge.
    @Volatile private var yellowHeld: Boolean = false
    @Volatile private var sosHeld: Boolean = false

    @Volatile private var registered: Boolean = false

    private val receiver: BroadcastReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(
                ctx: Context,
                intent: Intent,
            ) {
                when (intent.action) {
                    SonimHardwareButtons.ACTION_YELLOW_KEY_DOWN -> handleYellow(isDown = true)
                    SonimHardwareButtons.ACTION_YELLOW_KEY_UP -> handleYellow(isDown = false)
                    SonimHardwareButtons.ACTION_SOS_KEY_DOWN,
                    SonimHardwareButtons.ACTION_KODIAK_SOS -> {
                        // ACTION_KODIAK_SOS is edge-ambiguous by name.
                        // On the XP9900 it fires paired with the Sonim
                        // SOS_KEY_DOWN / _UP action, so its edge is
                        // implied by whichever Sonim edge is currently
                        // being processed. The held-state guard means
                        // whichever action lands first wins and the
                        // other is a no-op — safe either way.
                        if (intent.action == SonimHardwareButtons.ACTION_KODIAK_SOS) {
                            // No standardized state extra; treat as
                            // toggle-through-guard. Down first because
                            // most fires we've observed are paired
                            // with SOS_KEY_DOWN and land within 1ms.
                            handleSos(isDown = !sosHeld && true)
                        } else {
                            handleSos(isDown = true)
                        }
                    }
                    SonimHardwareButtons.ACTION_SOS_KEY_UP -> handleSos(isDown = false)
                    else -> Log.d(TAG, "unexpected action=${intent.action} — ignoring")
                }
            }
        }

    private fun handleYellow(isDown: Boolean) {
        if (isDown) {
            if (yellowHeld) {
                Log.d(TAG, "Yellow DOWN — already held; dropping duplicate")
                return
            }
            yellowHeld = true
            Log.i(TAG, "Yellow key DOWN (assigned-to-ATAK broadcast) → PTT")
            safeInvoke("onYellowKeyEdge(down)") { onYellowKeyEdge(true) }
        } else {
            if (!yellowHeld) {
                Log.d(TAG, "Yellow UP — not held; dropping")
                return
            }
            yellowHeld = false
            Log.i(TAG, "Yellow key UP (assigned-to-ATAK broadcast) → PTT release")
            safeInvoke("onYellowKeyEdge(up)") { onYellowKeyEdge(false) }
        }
    }

    private fun handleSos(isDown: Boolean) {
        if (isDown) {
            if (sosHeld) {
                Log.d(TAG, "SOS DOWN — already held; dropping duplicate (Sonim + Kodiak paired emission)")
                return
            }
            sosHeld = true
            Log.i(TAG, "SOS key DOWN (assigned-to-ATAK broadcast) → emergency")
            safeInvoke("onSosKeyEdge(down)") { onSosKeyEdge(true) }
        } else {
            if (!sosHeld) {
                Log.d(TAG, "SOS UP — not held; dropping")
                return
            }
            sosHeld = false
            Log.i(TAG, "SOS key UP (assigned-to-ATAK broadcast) → emergency release")
            safeInvoke("onSosKeyEdge(up)") { onSosKeyEdge(false) }
        }
    }

    private inline fun safeInvoke(
        tag: String,
        block: () -> Unit,
    ) {
        try {
            block()
        } catch (t: Throwable) {
            Log.w(TAG, "$tag threw", t)
        }
    }

    /**
     * Register the receiver on ATAK's context. Must be called from
     * XvMapComponent (or another site running in ATAK's process) —
     * calling from the plugin's service process will register the
     * receiver in the wrong package and the pkg-scoped broadcasts
     * won't be delivered. Idempotent.
     */
    fun start(): Boolean {
        synchronized(this) {
            if (registered) {
                Log.i(TAG, "start() ignored — already registered")
                return true
            }
            val filter =
                IntentFilter().apply {
                    addAction(SonimHardwareButtons.ACTION_YELLOW_KEY_DOWN)
                    addAction(SonimHardwareButtons.ACTION_YELLOW_KEY_UP)
                    addAction(SonimHardwareButtons.ACTION_SOS_KEY_DOWN)
                    addAction(SonimHardwareButtons.ACTION_SOS_KEY_UP)
                    addAction(SonimHardwareButtons.ACTION_KODIAK_SOS)
                }
            return try {
                ContextCompat.registerReceiver(
                    context,
                    receiver,
                    filter,
                    ContextCompat.RECEIVER_NOT_EXPORTED,
                )
                registered = true
                Log.i(TAG, "SonimAssignedAppReader started (Yellow → PTT, SOS+Kodiak → emergency, pkg-scoped to ATAK)")
                true
            } catch (t: Throwable) {
                Log.w(TAG, "registerReceiver(SonimAssignedAppReader) threw", t)
                false
            }
        }
    }

    /** Idempotent. */
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
            yellowHeld = false
            sosHeld = false
        }
    }

    fun isRunning(): Boolean = registered

    companion object {
        private const val TAG = "XvSonimAssignedFg"
    }
}
