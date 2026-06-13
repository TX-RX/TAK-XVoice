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
                val sdkType = mgr.emergencyType ?: EmergencyType.getDefault()
                Log.w(TAG, "fire ${sdkType.name} (live AlertTool type) — $reason")
                mgr.initiateRepeat(sdkType, true)
            } catch (t: Throwable) {
                Log.e(TAG, "initiateRepeat failed", t)
            }
        }
    }

    override fun cancelEmergency(reason: String) {
        Log.w(TAG, "cancel — $reason")
        mainHandler.post {
            try {
                EmergencyManager.getInstance().cancelRepeat()
            } catch (t: Throwable) {
                Log.e(TAG, "cancelRepeat failed", t)
            }
        }
    }

    companion object {
        private const val TAG = "XvEmergency"
    }
}
