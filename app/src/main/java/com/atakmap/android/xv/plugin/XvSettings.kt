package com.atakmap.android.xv.plugin

import android.content.SharedPreferences
import com.atakmap.android.xv.audio.TptTone

// Persistent settings for the XV plugin. Wraps a SharedPreferences
// provider so call sites read "settings.persistedX()" / "settings.persistX()"
// without threading a Context (or a nullable MapView) through every layer.
//
// Construction takes a `() -> SharedPreferences?` provider rather than a
// fixed SharedPreferences reference so the provider can return null
// during early lifecycle (before the MapView's context is attached) —
// every accessor gracefully degrades to the on-disk default in that
// window instead of NPE.
//
// Extracted from XvMapComponent during the L5+L6 split. Pure data
// surface — no Android dependencies beyond SharedPreferences itself.
class XvSettings(
    private val prefsProvider: () -> SharedPreferences?,
) {
    private fun prefs(): SharedPreferences? = prefsProvider()

    // Persisted across plugin loads in SharedPreferences (PREF_TAK_SERVER_HOST).
    // Null/blank = no explicit pick; TakServerDiscovery.pickPreferred falls
    // back to auto (first connected, else first configured). Written from
    // the Settings → Server picker; read by connectMumbleWithDefaults on
    // every plugin start and by connectedTakHost() for the live label.
    fun persistedPreferredTakHost(): String? = prefs()?.getString(PREF_TAK_SERVER_HOST, null)?.takeIf { it.isNotBlank() }

    fun persistPreferredTakHost(host: String?) {
        prefs()?.edit()?.apply {
            if (host.isNullOrBlank()) remove(PREF_TAK_SERVER_HOST) else putString(PREF_TAK_SERVER_HOST, host)
            apply()
        }
    }

    fun persistedAinaMac(): String? = prefs()?.getString(PREF_AINA_MAC, null)

    fun persistAinaMac(mac: String?) {
        prefs()?.edit()?.apply {
            if (mac == null) remove(PREF_AINA_MAC) else putString(PREF_AINA_MAC, mac)
            apply()
        }
    }

    // Per-MAC protocol override used when the SDP-based classifier picks
    // wrong. Necessary because the AINA APTT v18 spec doesn't put the V2
    // BLE vendor service `127FACE1-...` in the BR/EDR SDP record — it's
    // only discoverable via a live GATT connect. For a dual-mode V2
    // device whose SDP cache shows SPP + HFP + battery (no vendor UUID),
    // `classifyButtonProtocol` falls back to SPP → V1, and the operator
    // never gets BLE button events. Once they've identified the device
    // as V2 (e.g. via the debug AINA_CONNECT --es kind v2 broadcast),
    // they persist a per-MAC override here so every subsequent connect
    // skips the misclassification. V1 devices that aren't overridden
    // continue using the auto-detect path — no regression risk.
    //
    // Valid values: "v1" (SPP), "v2" (BLE GATT), "ble-hid" (generic
    // BLE PTT), or null/empty to clear the override.
    fun persistedAinaProtocolOverride(mac: String?): String? {
        if (mac.isNullOrBlank()) return null
        return prefs()?.getString(prefAinaProtocolKeyFor(mac), null)?.takeIf { it.isNotBlank() }
    }

    fun persistAinaProtocolOverride(
        mac: String?,
        proto: String?,
    ) {
        if (mac.isNullOrBlank()) return
        val key = prefAinaProtocolKeyFor(mac)
        prefs()?.edit()?.apply {
            if (proto.isNullOrBlank()) remove(key) else putString(key, proto.lowercase())
            apply()
        }
    }

    // Removes any persisted override for [mac]. Called from the
    // BOND_NONE branch of the bond-state receiver so that a re-pair
    // (a likely operator response to a misbehaving AINA) starts from
    // a clean auto-detect rather than a stale override that might no
    // longer match the device's firmware. Idempotent: safe to call
    // for a MAC with no recorded override.
    fun clearAinaProtocolOverride(
        mac: String?,
        reason: String = "manual",
    ) {
        if (mac.isNullOrBlank()) return
        val key = prefAinaProtocolKeyFor(mac)
        prefs()?.edit()?.remove(key)?.apply()
        android.util.Log.i("XvSettings", "override cleared mac=${redactMacForLog(mac)} reason=$reason")
    }

    private fun redactMacForLog(mac: String): String {
        val parts = mac.split(":")
        if (parts.size != 6) return "??:XX:XX:XX:XX:??"
        return "${parts.first()}:XX:XX:XX:XX:${parts.last()}"
    }

    private fun prefAinaProtocolKeyFor(mac: String): String = PREF_AINA_PROTOCOL_PREFIX + mac.uppercase()

    fun persistedLatchedMode(): Boolean = prefs()?.getBoolean(PREF_LATCHED, false) ?: false

    fun persistLatchedMode(enabled: Boolean) {
        prefs()?.edit()?.putBoolean(PREF_LATCHED, enabled)?.apply()
    }

    fun persistedHotMicMode(): Boolean = prefs()?.getBoolean(PREF_HOT_MIC, false) ?: false

    fun persistHotMicMode(enabled: Boolean) {
        prefs()?.edit()?.putBoolean(PREF_HOT_MIC, enabled)?.apply()
    }

    fun persistedTptTone(): TptTone = TptTone.fromName(prefs()?.getString(PREF_TPT_TONE, null))

    fun persistTptTone(tone: TptTone) {
        prefs()?.edit()?.putString(PREF_TPT_TONE, tone.name)?.apply()
    }

    // PTT-timeout slider is pinned to 20–90 s in the UI (xv_settings.xml
    // SeekBar min/max). Default 30. Old persisted values from the prior
    // 0–120 range are coerced into the new range on read so an upgraded
    // install doesn't surface an out-of-range slider.
    fun persistedPttTimeout(): Int =
        (prefs()?.getInt(PREF_PTT_TIMEOUT, 30) ?: 30).coerceIn(PTT_TIMEOUT_MIN_SEC, PTT_TIMEOUT_MAX_SEC)

    fun persistPttTimeout(s: Int) {
        prefs()?.edit()?.putInt(PREF_PTT_TIMEOUT, s.coerceIn(PTT_TIMEOUT_MIN_SEC, PTT_TIMEOUT_MAX_SEC))?.apply()
    }

    fun persistedLatchedTimeout(): Int = prefs()?.getInt(PREF_LATCHED_TIMEOUT, 180) ?: 180

    fun persistLatchedTimeout(s: Int) {
        prefs()?.edit()?.putInt(PREF_LATCHED_TIMEOUT, s.coerceAtLeast(0))?.apply()
    }

    // Whether status tones (channel join/leave chirps) are enabled.
    // Default ON — matches the VX 2.1 default. User can toggle in
    // Preferences. Read on every play so live toggles take effect
    // immediately without a transport reconnect.
    fun persistedStatusTonesEnabled(): Boolean = prefs()?.getBoolean(PREF_STATUS_TONES, true) ?: true

    fun persistStatusTonesEnabled(enabled: Boolean) {
        prefs()?.edit()?.putBoolean(PREF_STATUS_TONES, enabled)?.apply()
    }

    // Last-joined primary channel. Written by onChannelChanged on
    // every slot-0 move (excluding "TAK PRIVATE - …" temp channels);
    // read by connectMumbleWithDefaults to override server-side default
    // channel placement on reconnect.
    fun persistedPrimaryChannel(): String = prefs()?.getString(PREF_PRIMARY_CHANNEL, "").orEmpty()

    fun persistPrimaryChannel(name: String) {
        prefs()?.edit()?.putString(PREF_PRIMARY_CHANNEL, name)?.apply()
    }

    companion object {
        // SharedPreferences file name. Lives under the plugin's own
        // Context so we don't pollute ATAK's prefs file.
        const val PREFS_NAME = "xv_settings"

        // Persistent keys.
        private const val PREF_AINA_MAC = "aina_mac"

        // Per-MAC AINA protocol override; key suffix is the upper-cased
        // MAC. Value is "v1" / "v2" / "ble-hid" or absent for auto-detect.
        // See [persistedAinaProtocolOverride] for the rationale.
        private const val PREF_AINA_PROTOCOL_PREFIX = "aina_proto_override:"
        private const val PREF_LATCHED = "latched_mode"
        private const val PREF_PTT_TIMEOUT = "ptt_timeout_sec"
        private const val PREF_HOT_MIC = "hot_mic_mode"
        private const val PREF_TPT_TONE = "tpt_tone"
        private const val PREF_LATCHED_TIMEOUT = "latched_timeout_sec"
        private const val PREF_STATUS_TONES = "status_tones_enabled"

        // TAK server picker — empty/missing means auto-pick (first
        // connected, else first configured); else the explicit host
        // string the operator chose in Settings.
        private const val PREF_TAK_SERVER_HOST = "tak_server_host"

        // Last-joined primary channel. Used for auto-rejoin override
        // on reconnect.
        private const val PREF_PRIMARY_CHANNEL = "primary_channel"

        // PTT-timeout slider clamp. Bottom prevents an operator from
        // setting a timeout so short the warning chirp + cutoff tone
        // overlap; top is the longest single transmission anyone has
        // a real-world need for (FCC accidental-stuck-mic etiquette).
        const val PTT_TIMEOUT_MIN_SEC: Int = 20
        const val PTT_TIMEOUT_MAX_SEC: Int = 90
    }
}
