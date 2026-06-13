package com.atakmap.android.xv.audio

import android.content.Context
import android.content.SharedPreferences

// Persisted output route preference. Survives plugin reload + ATAK restart.
// Stored under XV's package-scoped prefs file (NOT ATAK's — we only own
// XV-specific keys).
class AudioRoutePreference(
    context: Context,
) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var route: OutputRoute
        get() = OutputRoute.fromName(prefs.getString(KEY_ROUTE, null))
        set(value) {
            prefs.edit().putString(KEY_ROUTE, value.name).apply()
        }

    // Optional override: a specific BT audio device to use as the
    // output, regardless of which AINA is paired or which other BT
    // devices are present. Stored as a MAC address; empty/null means
    // "no override — let AudioRouter pick by priority."
    //
    // Use case: AINA speakermic provides PTT buttons, but operator
    // wants audio routed to car BT or BT headphones. Default (null)
    // keeps the legacy behavior where the AINA does both buttons and
    // audio.
    //
    // When the override device isn't currently connected, AudioRouter
    // falls through to the regular priority chain — the preference
    // is preserved so when the device returns, audio routes to it
    // automatically.
    var outputBtOverrideMac: String?
        get() = prefs.getString(KEY_OUTPUT_BT_OVERRIDE, null)?.takeIf { it.isNotBlank() }
        set(value) {
            val cleaned = value?.trim()?.takeIf { it.isNotBlank() }
            if (cleaned == null) {
                prefs.edit().remove(KEY_OUTPUT_BT_OVERRIDE).apply()
            } else {
                prefs.edit().putString(KEY_OUTPUT_BT_OVERRIDE, cleaned).apply()
            }
        }

    companion object {
        private const val PREFS_NAME = "xv_audio_prefs"
        private const val KEY_ROUTE = "output_route"
        private const val KEY_OUTPUT_BT_OVERRIDE = "output_bt_override_mac"
    }
}
