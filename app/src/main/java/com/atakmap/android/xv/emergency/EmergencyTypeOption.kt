package com.atakmap.android.xv.emergency

// Mirror of ATAK's com.atakmap.android.emergency.tool.EmergencyType so we can
// store/configure a stable identifier without pulling SDK classes into pure-
// Kotlin layers. Mapped at the boundary in AtakEmergencyDispatcher.
enum class EmergencyTypeOption(
    val displayName: String,
) {
    NineOneOne("911 Alert"),
    GeoFenceBreach("Geo-fence Breached"),
    RingTheBell("Ring The Bell"),
    TroopsInContact("In Contact"),
    Custom("Custom"),
    ;

    companion object {
        val DEFAULT: EmergencyTypeOption = NineOneOne

        fun fromName(name: String?): EmergencyTypeOption = entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}
