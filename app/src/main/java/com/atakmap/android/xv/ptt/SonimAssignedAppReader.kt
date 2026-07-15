package com.atakmap.android.xv.ptt

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import androidx.core.content.ContextCompat
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
 *   - PTT button (assigned to ATAK in Programmable Keys):
 *     `com.sonim.intent.action.YELLOW_KEY_DOWN` / `_UP`
 *     → routed to PTT (source [com.atakmap.android.xv.audio.PttSource.SONIM_PTT])
 *     Sonim's assigned-app API naming is backwards relative to the
 *     physical buttons: the operator's PTT button is delivered as the
 *     `YELLOW_KEY` action, not a `PTT_KEY` action. This was verified
 *     on-device (XP9900, 2026-07-14) — wiring these edges to the
 *     dispatcher keys TX exactly as intended.
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
    // Called on any PTT-key edge (assigned-app PTT). isDown = true on
    // press, false on release. On the XP9900 the physical PTT button,
    // when assigned to ATAK in Programmable Keys, is delivered as the
    // YELLOW_KEY broadcast — Sonim's assigned-app API naming is backwards
    // relative to the physical buttons (verified on-device 2026-07-14).
    // Wired from XvMapComponent to IXvVoice.notifySonimPttEdge → the PTT
    // dispatcher, tagged SONIM_PTT.
    private val onPttKeyEdge: (isDown: Boolean) -> Unit,
    // Called on any SOS-key edge (assigned-app emergency). isDown =
    // true on press, false on release. Wired from XvMapComponent to
    // IXvVoice.notifySonimEmergencyEdge → plant.onSonimEmergencyEdge
    // → callbacks.onEmergencyButton → EmergencyController.
    private val onSosKeyEdge: (isDown: Boolean) -> Unit,
) {
    // Held-state guard for SOS — the paired Sonim+Kodiak emission
    // (fires within 1ms of each other on the XP9900) needs to
    // collapse to a single edge. Kodiak action is dropped entirely
    // (see the when branch below); this guard survives to handle any
    // future case where two Sonim SOS_KEY_DOWN broadcasts arrive
    // without an intervening UP.
    @Volatile private var sosHeld: Boolean = false

    // Held-state guard for the assigned-app PTT (YELLOW_KEY) path — same
    // dedup rationale as [sosHeld]: keeps a physically-held key from
    // spamming duplicate down edges into the dispatcher's OR-gate.
    @Volatile private var pttHeld: Boolean = false

    @Volatile private var registered: Boolean = false

    private val receiver: BroadcastReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(
                ctx: Context,
                intent: Intent,
            ) {
                when (intent.action) {
                    // The physical PTT button, when assigned to ATAK in
                    // Programmable Keys, is delivered as the YELLOW_KEY
                    // broadcast. Sonim's assigned-app API naming is
                    // backwards relative to the physical buttons — the
                    // "Yellow"-named action is the operator's PTT, not a
                    // separate convenience key. Verified on-device
                    // 2026-07-14 (XP9900): routing these edges to the PTT
                    // dispatcher keys TX exactly as the operator expects.
                    SonimHardwareButtons.ACTION_YELLOW_KEY_DOWN -> handlePtt(isDown = true)
                    SonimHardwareButtons.ACTION_YELLOW_KEY_UP -> handlePtt(isDown = false)
                    SonimHardwareButtons.ACTION_SOS_KEY_DOWN -> handleSos(isDown = true)
                    SonimHardwareButtons.ACTION_SOS_KEY_UP -> handleSos(isDown = false)
                    // ACTION_KODIAK_SOS is edge-ambiguous by name and
                    // fires paired with the Sonim SOS_KEY_DOWN / _UP
                    // actions in the same millisecond on the XP9900.
                    // Field-verified 2026-07-14: treating the Kodiak
                    // action as either edge caused intermittent
                    // premature release (down + phantom up on the same
                    // press). We rely exclusively on the Sonim actions
                    // for edge info and drop the Kodiak emission as
                    // redundant. Registered in the filter only to keep
                    // logcat quiet — anyone else broadcasting this
                    // action to ATAK's package would surface here.
                    SonimHardwareButtons.ACTION_KODIAK_SOS -> {
                        Log.d(TAG, "Kodiak SOS action received — ignoring (redundant with SOS_KEY_*)")
                    }
                    else -> Log.d(TAG, "unexpected action=${intent.action} — ignoring")
                }
            }
        }

    private fun handlePtt(isDown: Boolean) {
        if (isDown) {
            if (pttHeld) {
                Log.d(TAG, "PTT DOWN (YELLOW_KEY) — already held; dropping duplicate")
                return
            }
            pttHeld = true
            Log.i(TAG, "PTT key DOWN (assigned-to-ATAK YELLOW_KEY broadcast) → PTT")
            safeInvoke("onPttKeyEdge(down)") { onPttKeyEdge(true) }
        } else {
            if (!pttHeld) {
                Log.d(TAG, "PTT UP (YELLOW_KEY) — not held; dropping")
                return
            }
            pttHeld = false
            Log.i(TAG, "PTT key UP (assigned-to-ATAK YELLOW_KEY broadcast) → PTT release")
            safeInvoke("onPttKeyEdge(up)") { onPttKeyEdge(false) }
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
            // Clear BOTH held flags so a stop while a key is held (or
            // after a dropped UP) can't leave stale state that makes the
            // next DOWN after a restart look like a duplicate.
            sosHeld = false
            pttHeld = false
        }
    }

    fun isRunning(): Boolean = registered

    companion object {
        private const val TAG = "XvSonimAssignedFg"
    }
}
