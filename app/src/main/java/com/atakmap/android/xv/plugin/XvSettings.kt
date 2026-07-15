package com.atakmap.android.xv.plugin

import android.content.SharedPreferences
import com.atakmap.android.xv.audio.TptTone
import com.atakmap.android.xv.transport.multicast.ChannelMulticastConfig
import com.atakmap.android.xv.transport.multicast.MulticastGroupDerivation

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

    // External button — an OPTIONAL second bonded BT PTT input whose
    // button drives slot 0 in parallel with the primary speakermic.
    // Button-only role: a BLE PTT puck (Pryme BT-PTT-Z, PTT-Z01, generic
    // BLE-HID). Motorcyclist use case: AINA helmet speakermic + Pryme
    // handlebar puck both keying VS1. Independent of the primary so the
    // operator can swap either side without affecting the other.
    // Null/blank = no external button selected (single-input behaviour).
    fun persistedExternalButtonMac(): String? =
        prefs()?.getString(PREF_EXTERNAL_BUTTON_MAC, null)?.takeIf { it.isNotBlank() }

    fun persistExternalButtonMac(mac: String?) {
        prefs()?.edit()?.apply {
            if (mac.isNullOrBlank()) remove(PREF_EXTERNAL_BUTTON_MAC) else putString(PREF_EXTERNAL_BUTTON_MAC, mac)
            apply()
        }
    }

    fun persistedExternalButtonKind(): String? =
        prefs()?.getString(PREF_EXTERNAL_BUTTON_KIND, null)?.takeIf { it.isNotBlank() }

    fun persistExternalButtonKind(kind: String?) {
        prefs()?.edit()?.apply {
            if (kind.isNullOrBlank()) remove(PREF_EXTERNAL_BUTTON_KIND) else putString(PREF_EXTERNAL_BUTTON_KIND, kind)
            apply()
        }
    }

    // Manually-added BLE PTT devices (HM-10-based buttons — PTT-Z01,
    // Pryme BT-PTT-Z, etc.). These buttons don't always show up in
    // adapter.bondedDevices with a classifier-friendly SDP UUID set
    // (PTT-Z01 in particular doesn't bond via system BT settings at
    // all), so the settings picker would otherwise never surface them.
    // We persist them here so the operator sees them in the picker on
    // every subsequent plugin launch until they explicitly remove one.
    //
    // Storage format: Set<String> where each element is "MAC|Name".
    // Name is best-effort — HM-10 modules sometimes advertise nothing,
    // in which case we fall back to the MAC as the display label.
    fun knownBlePttDevices(): List<Pair<String, String?>> {
        val raw = prefs()?.getStringSet(PREF_BLE_PTT_KNOWN, emptySet()) ?: emptySet()
        return raw.mapNotNull { entry ->
            val idx = entry.indexOf('|')
            if (idx < 0) {
                entry.takeIf { it.isNotBlank() }?.let { it to null }
            } else {
                val mac = entry.substring(0, idx)
                val name = entry.substring(idx + 1).takeIf { it.isNotBlank() }
                if (mac.isBlank()) null else mac to name
            }
        }.sortedBy { (mac, name) -> (name ?: mac).lowercase() }
    }

    fun addKnownBlePttDevice(
        mac: String,
        name: String?,
    ) {
        val normalized = mac.trim().uppercase().takeIf { it.isNotBlank() } ?: return
        val existing =
            prefs()
                ?.getStringSet(PREF_BLE_PTT_KNOWN, emptySet())
                ?.toMutableSet()
                ?: mutableSetOf()
        // Replace any existing entry for the same MAC so the name gets
        // updated if the operator re-added the device after learning
        // its advertised name.
        existing.removeAll { it.startsWith("$normalized|") || it == normalized }
        val cleanName = name?.trim()?.takeIf { it.isNotBlank() && !it.contains('|') }
        existing.add(if (cleanName != null) "$normalized|$cleanName" else normalized)
        prefs()?.edit()?.putStringSet(PREF_BLE_PTT_KNOWN, existing)?.apply()
    }

    fun removeKnownBlePttDevice(mac: String) {
        val normalized = mac.trim().uppercase().takeIf { it.isNotBlank() } ?: return
        val existing =
            prefs()
                ?.getStringSet(PREF_BLE_PTT_KNOWN, emptySet())
                ?.toMutableSet()
                ?: return
        val before = existing.size
        existing.removeAll { it.startsWith("$normalized|") || it == normalized }
        if (existing.size != before) {
            prefs()?.edit()?.putStringSet(PREF_BLE_PTT_KNOWN, existing)?.apply()
        }
    }

    // Operator preference: should XV auto-connect a compatible
    // speakermic / BLE PTT button it detects in the bond table on
    // plugin load? Default true so first-launch UX is "pair the device
    // in Android Settings → open ATAK → it just works." Operators who
    // don't want the auto-pick (e.g. running multiple speakermics for
    // testing) can flip this off and rely on the explicit AINA picker
    // in Settings → Preferences. Persists across launches.
    fun persistedAutoConnectBtEnabled(): Boolean =
        prefs()?.getBoolean(PREF_AUTO_CONNECT_BT, true) ?: true

    fun persistAutoConnectBtEnabled(enabled: Boolean) {
        prefs()?.edit()?.putBoolean(PREF_AUTO_CONNECT_BT, enabled)?.apply()
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

    // Whether the Samsung ruggedized-device Active Key is enabled as
    // a PTT source. The corresponding Settings row is only shown at
    // all when [com.atakmap.android.xv.util.SamsungActiveKey.isSupported]
    // returns true (Galaxy Tab Active5, XCover6 Pro / 7, Tab Active4 Pro,
    // Tab Active3) — on any other device this preference is inert and
    // the toggle is hidden entirely. Default OFF so first launch on
    // new hardware doesn't silently start intercepting the key; the
    // operator opts in explicitly. Read at plugin load by
    // [XvMapComponent.autoStartSamsungActiveKeyIfEnabled].
    fun persistedSamsungActiveKeyEnabled(): Boolean =
        prefs()?.getBoolean(PREF_SAMSUNG_ACTIVE_KEY_ENABLED, false) ?: false

    fun persistSamsungActiveKeyEnabled(enabled: Boolean) {
        prefs()?.edit()?.putBoolean(PREF_SAMSUNG_ACTIVE_KEY_ENABLED, enabled)?.apply()
    }

    // Whether the Sonim ruggedized-device dedicated PTT side button is
    // enabled as a PTT source. The corresponding Settings row is only
    // shown at all when [com.atakmap.android.xv.util.SonimHardwareButtons.isSupported]
    // returns true (Sonim XP10 / XP9900 and XP-family peers) — on any
    // other device this preference is inert and the toggle is hidden
    // entirely. Default OFF so first launch on new hardware doesn't
    // silently start intercepting the key; the operator opts in
    // explicitly. Read at plugin load by
    // [XvMapComponent.autoStartSonimButtonsIfEnabled].
    fun persistedSonimPttButtonEnabled(): Boolean =
        prefs()?.getBoolean(PREF_SONIM_PTT_BUTTON_ENABLED, false) ?: false

    fun persistSonimPttButtonEnabled(enabled: Boolean) {
        prefs()?.edit()?.putBoolean(PREF_SONIM_PTT_BUTTON_ENABLED, enabled)?.apply()
    }

    // Whether the Sonim ruggedized-device dedicated Emergency / SOS
    // button is enabled as a PTT source. Same gate story as the PTT
    // button pref above — hidden on non-Sonim hardware, default OFF.
    // Currently treated as a plain PTT source with a distinct
    // [com.atakmap.android.xv.audio.PttSource.SONIM_EMERGENCY] tag
    // and a distinct log tag; a follow-up may promote it to fire a
    // real emergency CoT event.
    fun persistedSonimEmergencyButtonEnabled(): Boolean =
        prefs()?.getBoolean(PREF_SONIM_EMERGENCY_BUTTON_ENABLED, false) ?: false

    fun persistSonimEmergencyButtonEnabled(enabled: Boolean) {
        prefs()?.edit()?.putBoolean(PREF_SONIM_EMERGENCY_BUTTON_ENABLED, enabled)?.apply()
    }

    // Last-joined primary channel. Written by onChannelChanged on
    // every slot-0 move (excluding "TAK PRIVATE - …" temp channels);
    // read by connectMumbleWithDefaults to override server-side default
    // channel placement on reconnect.
    fun persistedPrimaryChannel(): String = prefs()?.getString(PREF_PRIMARY_CHANNEL, "").orEmpty()

    fun persistPrimaryChannel(name: String) {
        prefs()?.edit()?.putString(PREF_PRIMARY_CHANNEL, name)?.apply()
    }

    // Global mesh-voice (multicast) master toggle. When ON, every
    // joined Mumble channel gets an auto-derived multicast failover
    // leg (FAILOVER mode, XV-native encrypted) unless a per-channel
    // config below overrides it. Default OFF for the first release
    // carrying the feature — operators opt in deliberately while the
    // failover path soaks; flipping the default is a one-line change.
    fun persistedMeshVoiceEnabled(): Boolean = prefs()?.getBoolean(PREF_MESH_VOICE_ENABLED, false) ?: false

    fun persistMeshVoiceEnabled(enabled: Boolean) {
        prefs()?.edit()?.putBoolean(PREF_MESH_VOICE_ENABLED, enabled)?.apply()
    }

    // Mission auto-channels master toggle. When ON, the operator's active
    // ATAK Data Sync mission drives the primary voice channel: XV derives
    // a deterministic channel name from the mission, creates it on the
    // server if the server allows and it doesn't exist, and joins it — so
    // a whole mission team lands on one voice channel with no manual
    // coordination (and, with mesh voice on, its failover leg follows for
    // free). Default OFF; opt-in like mesh voice. See
    // MissionChannelProvisioner for the reconciliation policy.
    fun persistedMissionChannelsEnabled(): Boolean = prefs()?.getBoolean(PREF_MISSION_CHANNELS_ENABLED, false) ?: false

    fun persistMissionChannelsEnabled(enabled: Boolean) {
        prefs()?.edit()?.putBoolean(PREF_MISSION_CHANNELS_ENABLED, enabled)?.apply()
    }

    // Per-channel multicast overrides, stored as a Set<String> of
    // canonical-JSON ChannelMulticastConfig entries (one JSON object
    // per element — same shape the comms-plan bundle embeds). Absence
    // of an entry for a channel means "use the auto-derived default",
    // NOT "multicast off"; see ChannelMulticastConfig.defaultFor.
    // Entries that fail to parse (e.g. written by a newer XV with an
    // enum this build doesn't know) are skipped, degrading that
    // channel to the default rather than failing channel setup.
    fun channelMulticastConfigs(): List<ChannelMulticastConfig> {
        val raw = prefs()?.getStringSet(PREF_CHANNEL_MULTICAST, emptySet()) ?: emptySet()
        return raw
            .mapNotNull { ChannelMulticastConfig.fromJson(it) }
            .sortedBy { it.channelName }
    }

    fun channelMulticastConfigFor(channelName: String): ChannelMulticastConfig? {
        val canonical = MulticastGroupDerivation.canonicalChannelName(channelName)
        return channelMulticastConfigs().firstOrNull { it.channelName == canonical }
    }

    // Upsert by canonical channel name: at most one override per
    // channel, and a re-save replaces the prior one.
    fun persistChannelMulticastConfig(cfg: ChannelMulticastConfig) {
        val canonical = cfg.copy(channelName = MulticastGroupDerivation.canonicalChannelName(cfg.channelName))
        val existing =
            prefs()
                ?.getStringSet(PREF_CHANNEL_MULTICAST, emptySet())
                ?.toMutableSet()
                ?: mutableSetOf()
        existing.removeAll { ChannelMulticastConfig.fromJson(it)?.channelName == canonical.channelName }
        existing.add(canonical.toJson())
        prefs()?.edit()?.putStringSet(PREF_CHANNEL_MULTICAST, existing)?.apply()
    }

    fun removeChannelMulticastConfig(channelName: String) {
        val canonical = MulticastGroupDerivation.canonicalChannelName(channelName)
        val existing =
            prefs()
                ?.getStringSet(PREF_CHANNEL_MULTICAST, emptySet())
                ?.toMutableSet()
                ?: return
        val before = existing.size
        existing.removeAll { ChannelMulticastConfig.fromJson(it)?.channelName == canonical }
        if (existing.size != before) {
            prefs()?.edit()?.putStringSet(PREF_CHANNEL_MULTICAST, existing)?.apply()
        }
    }

    companion object {
        // SharedPreferences file name. Lives under the plugin's own
        // Context so we don't pollute ATAK's prefs file.
        const val PREFS_NAME = "xv_settings"

        // Persistent keys.
        private const val PREF_AINA_MAC = "aina_mac"

        // External-button input pair — see persistedExternalButtonMac.
        //
        // On-disk key names retain the historical `_secondary` suffix
        // intentionally: this constant was renamed from
        // PREF_AINA_MAC_SECONDARY / PREF_AINA_KIND_SECONDARY as part
        // of the "Secondary → External button" concept rename, but the
        // stored String value is preserved so existing installs keep
        // auto-connecting the persisted MAC without a prefs migration.
        // Do NOT change the string values.
        private const val PREF_EXTERNAL_BUTTON_MAC = "aina_mac_secondary"
        private const val PREF_EXTERNAL_BUTTON_KIND = "aina_kind_secondary"

        // Manually-added BLE PTT devices — see knownBlePttDevices.
        // Set<String> with "MAC|Name" entries.
        private const val PREF_BLE_PTT_KNOWN = "ble_ptt_known"

        // BT auto-connect on plugin load (default true). See
        // persistedAutoConnectBtEnabled.
        private const val PREF_AUTO_CONNECT_BT = "auto_connect_bt"

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

        // Whether the Samsung ruggedized-device Active Key is used as
        // a PTT source. Only meaningful on hardware that has the key.
        // Default false; the row is hidden on other devices.
        private const val PREF_SAMSUNG_ACTIVE_KEY_ENABLED = "samsung_active_key_enabled"

        // Sonim ruggedized-device programmable-key toggles. Only
        // meaningful on Sonim hardware that carries the dedicated
        // buttons (XP10 / XP9900). Default false; the row is hidden
        // on other devices.
        private const val PREF_SONIM_PTT_BUTTON_ENABLED = "sonim_ptt_button_enabled"
        private const val PREF_SONIM_EMERGENCY_BUTTON_ENABLED = "sonim_emergency_button_enabled"

        // TAK server picker — empty/missing means auto-pick (first
        // connected, else first configured); else the explicit host
        // string the operator chose in Settings.
        private const val PREF_TAK_SERVER_HOST = "tak_server_host"

        // Last-joined primary channel. Used for auto-rejoin override
        // on reconnect.
        private const val PREF_PRIMARY_CHANNEL = "primary_channel"

        // Mesh-voice (multicast) master toggle + per-channel overrides.
        // See persistedMeshVoiceEnabled / channelMulticastConfigs.
        private const val PREF_MESH_VOICE_ENABLED = "mesh_voice_enabled"
        private const val PREF_CHANNEL_MULTICAST = "channel_multicast_configs"

        // Mission auto-channels master toggle. See
        // persistedMissionChannelsEnabled.
        private const val PREF_MISSION_CHANNELS_ENABLED = "mission_channels_enabled"

        // PTT-timeout slider clamp. Bottom prevents an operator from
        // setting a timeout so short the warning chirp + cutoff tone
        // overlap; top is the longest single transmission anyone has
        // a real-world need for (FCC accidental-stuck-mic etiquette).
        const val PTT_TIMEOUT_MIN_SEC: Int = 20
        const val PTT_TIMEOUT_MAX_SEC: Int = 90
    }
}
