package com.atakmap.android.xv.audio

// User-selectable audio output routes for RX. Bluetooth-priority is enforced
// in AudioRouter regardless of this preference — these are the candidates
// for "where to play if NO BT is connected."
enum class OutputRoute {
    // Let Android pick. Conservative default that works on every device.
    AUTO,

    // Built-in loudspeaker (the louder one, not the earpiece).
    SPEAKER,

    // Phone earpiece (the small speaker held to the ear during a call).
    EARPIECE,

    // 3.5mm or USB-C wired headset.
    WIRED,
    ;

    companion object {
        val DEFAULT: OutputRoute = SPEAKER

        fun fromName(name: String?): OutputRoute = entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: DEFAULT
    }
}
