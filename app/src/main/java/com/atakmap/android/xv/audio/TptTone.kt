package com.atakmap.android.xv.audio

// Talk Permit Tone style — the audible "go ahead, you're transmitting"
// cue operators get on LMR systems. Played locally on PTT-down so the
// operator knows the system has armed their TX. Always synthesized in
// code from public spec data (no sampled audio — IP/trademark hygiene).
enum class TptTone(
    val displayName: String,
) {
    NONE("None"),
    ASTRO_25("ASTRO 25 (P25)"),
    DMR("DMR / MOTOTRBO"),
    NEXTEL("Nextel (sim)"),
    NEXTEL_TRUE("Nextel iDEN (alt)"),
    VERTEX("Vertex Standard"),
    ;

    companion object {
        val DEFAULT: TptTone = ASTRO_25

        fun fromName(name: String?): TptTone = entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: DEFAULT
    }
}
