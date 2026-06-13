package com.atakmap.android.xv.emergency

interface EmergencyDispatcher {
    // Type is resolved by the implementation — typically by reading ATAK's
    // configured emergency-tool preference (plugins.emergency.beacon.type) so
    // XV honors whatever the user picked in the Alert Tool, not a hardcoded
    // default.
    fun firePanic(reason: String)

    fun cancelEmergency(reason: String)
}
