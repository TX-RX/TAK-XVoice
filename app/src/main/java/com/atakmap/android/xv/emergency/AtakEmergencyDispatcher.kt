package com.atakmap.android.xv.emergency

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.atakmap.android.emergency.tool.EmergencyManager
import com.atakmap.android.emergency.tool.EmergencyType

// Production EmergencyDispatcher. XV runs in ATAK's process, so we call
// EmergencyManager directly — no cross-process broadcast needed (unlike
// aina-vx-bridge, which has a separate plugin process).
//
// Type source: EmergencyManager.getEmergencyType(). This is the LIVE selection
// from the Alert Tool. We tried `plugins.emergency.beacon.type` (the
// SharedPreferences key documented as the Alert Tool's storage) and it
// returned the stale "911 Alert" while the user's actual selection was "In
// Contact" — the pref doesn't reflect live UI state. Verified 2026-05-05 on
// SDK ATAK 5.6.0.17.
//
// Marshals to the main looper: EmergencyManager.initiateRepeat triggers
// EmergencyTool.emergencyStateChanged → Switch.setChecked → ValueAnimator
// .start, which throws "Animators may only be run on Looper threads" when
// invoked from our SPP/BLE reader threads.
class AtakEmergencyDispatcher(
    private val mainHandler: Handler = Handler(Looper.getMainLooper()),
) : EmergencyDispatcher {
    override fun firePanic(reason: String) {
        mainHandler.post {
            try {
                val mgr = EmergencyManager.getInstance()
                // Guard against repeat fires when an emergency is
                // already active. Field-observed 2026-07-14: pressing
                // the SOS button repeatedly created orphaned emergency
                // records on the TAK server because every short-press
                // fired initiateRepeat unconditionally. An emergency
                // has a well-defined lifecycle (start → cancel);
                // firing "start" while already active leaves the peer
                // side with no matching end.
                //
                // Uses EmergencyManager.isEmergencyOn() (returns
                // java.lang.Boolean — can be null before first ever
                // interaction) as the source of truth so a cancel
                // done through ATAK's own Alert Tool UI is reflected
                // here without our own bookkeeping.
                if (mgr.isEmergencyOn == true) {
                    Log.i(TAG, "firePanic skipped — emergency already active — $reason")
                    return@post
                }
                val sdkType = mgr.emergencyType ?: EmergencyType.getDefault()
                Log.w(TAG, "fire ${sdkType.name} (live AlertTool type) — $reason")
                mgr.initiateRepeat(sdkType, true)
            } catch (t: Throwable) {
                Log.e(TAG, "initiateRepeat failed", t)
            }
        }
    }

    override fun cancelEmergency(reason: String) {
        mainHandler.post {
            try {
                val mgr = EmergencyManager.getInstance()
                // cancelRepeat is intentionally NOT gated on
                // isEmergencyOn — a redundant cancel is a no-op at the
                // ATAK layer, and this way an out-of-sync perceived
                // state (e.g. cancel via UI didn't reflect here yet)
                // still lets the operator affirmatively clear it via
                // the hardware button. Skipping cancel on the other
                // hand could leave a beacon stuck.
                Log.w(TAG, "cancel — $reason (isEmergencyOn was ${mgr.isEmergencyOn})")
                mgr.cancelRepeat()
            } catch (t: Throwable) {
                Log.e(TAG, "cancelRepeat failed", t)
            }
        }
    }

    companion object {
        private const val TAG = "XvEmergency"
    }
}
