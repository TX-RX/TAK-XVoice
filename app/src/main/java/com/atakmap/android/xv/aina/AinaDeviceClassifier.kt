package com.atakmap.android.xv.aina

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.os.ParcelUuid
import java.util.UUID

// Pure classification helpers for the "is this BT device a speakermic?"
// + "what button-input protocol does it speak?" decisions. Extracted
// from XvMapComponent during the L5+L6 split so the same logic can be
// unit-tested without standing up a MapComponent. Each function takes a
// BluetoothDevice (with SuppressLint("MissingPermission") — callers have
// BLUETOOTH_CONNECT already since they get the device from a bonded set).
//
// Scope: AINA V1 (SPP) / V2 (BLE GATT) + Pryme BT-PTT (BLE HID-over-GATT
// via vendor service). Every other Bluetooth device falls through to
// UNKNOWN — XV doesn't read PTT events from generic HID buttons /
// headsets / car kits and surfacing them just makes the picker
// unwieldy for the operator.
@SuppressLint("MissingPermission")
object AinaDeviceClassifier {
    // Bluetooth vendor service UUID for the Pryme PTT-Z BLE button.
    // Matched by [classifyButtonProtocol] to route the device through
    // PrymeBleReader (BLE_HID).
    val PRYME_VENDOR_UUID: UUID =
        UUID.fromString("00420000-8f59-4420-870d-84f3b617e493")

    // HM10 BLE module service. Some Pryme units (and other devices
    // built on the HM10) expose this as their primary service. Keep
    // alongside the Pryme vendor UUID so both Pryme flavors are
    // matched in the picker.
    val HM10_SERVICE_UUID: UUID =
        UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb")

    // Returns true if the device looks like a speakermic XV knows how
    // to talk to. Used by the Settings → Preferences picker to filter
    // bonded devices down to the meaningful set.
    //
    // Identification:
    //   - AINA: device name starts with "APTT" (covers both V1 and
    //     V2 — they share the branding prefix). V1 SDP cache only
    //     has the generic SPP UUID so the UUID alone isn't a tight
    //     match; the name prefix is the reliable signal.
    //   - Pryme: device name contains "Pryme" / "BT-PTT" / "PTT-Z",
    //     OR GATT exposes Pryme's vendor UUID (00420000-…) or its
    //     HM10 BLE-module UUID (0000ffe0-…), OR GATT exposes the
    //     AINA V2 vendor service (so dual-mode AINAs are still in).
    fun isPlausibleSpeakermic(device: BluetoothDevice): Boolean {
        if (isAinaByName(device)) return true
        if (isPrymeByName(device)) return true
        val uuids = safeUuids(device) ?: return false
        for (pu in uuids) {
            val u = pu.uuid ?: continue
            if (u == AinaBleReader.SERVICE_UUID) return true
            if (u == PRYME_VENDOR_UUID) return true
            if (u == HM10_SERVICE_UUID) return true
        }
        return false
    }

    fun isAinaByName(device: BluetoothDevice): Boolean {
        val name = safeName(device) ?: return false
        return name.startsWith("APTT", ignoreCase = true)
    }

    fun isPrymeByName(device: BluetoothDevice): Boolean {
        val name = safeName(device) ?: return false
        val lower = name.lowercase()
        return lower.contains("pryme") ||
            lower.contains("bt-ptt") ||
            lower.contains("ptt-z")
    }

    // Detect which button-input protocol XV will use for this device.
    //
    // Decision precedence:
    //   1. AINA V2 vendor service UUID → BLE (richest path; preferred
    //      for dual-mode AINAs that also expose SPP).
    //   2. Pryme vendor / HM10 service UUID OR Pryme name pattern →
    //      BLE_HID (routed to PrymeBleReader in the service).
    //   3. AINA name prefix → SPP (V1 reader). V1's UUID cache only
    //      shows the generic SPP UUID so the name match is what makes
    //      this AINA-specific rather than "any SPP device."
    //   4. Otherwise → UNKNOWN.
    fun classifyButtonProtocol(device: BluetoothDevice): AinaDeviceInfo.ButtonProtocol {
        val uuids = safeUuids(device)
        if (uuids != null) {
            for (pu in uuids) {
                if (pu.uuid == AinaBleReader.SERVICE_UUID) {
                    return AinaDeviceInfo.ButtonProtocol.BLE
                }
            }
            for (pu in uuids) {
                val u = pu.uuid ?: continue
                if (u == PRYME_VENDOR_UUID || u == HM10_SERVICE_UUID) {
                    return AinaDeviceInfo.ButtonProtocol.BLE_HID
                }
            }
        }
        if (isPrymeByName(device)) return AinaDeviceInfo.ButtonProtocol.BLE_HID
        if (isAinaByName(device)) {
            // V1 (SPP) is the assumed kind for an APTT-prefixed device
            // whose UUID cache doesn't show the V2 vendor service. If
            // the device is in fact a V2 with a not-yet-discovered UUID
            // cache, the auto-resolve in VoicePlant.connectAina sorts
            // it out on connect.
            return AinaDeviceInfo.ButtonProtocol.SPP
        }
        return AinaDeviceInfo.ButtonProtocol.UNKNOWN
    }

    // Sort key for the bonded-device picker. SPP first (V1 AINAs are
    // the most common test rig), then BLE (V2 AINAs), then BLE_HID
    // (Pryme), then AUDIO_ONLY / UNKNOWN at the bottom.
    fun protocolOrder(p: AinaDeviceInfo.ButtonProtocol): Int =
        when (p) {
            AinaDeviceInfo.ButtonProtocol.SPP -> 0
            AinaDeviceInfo.ButtonProtocol.BLE -> 1
            AinaDeviceInfo.ButtonProtocol.BLE_HID -> 2
            AinaDeviceInfo.ButtonProtocol.AUDIO_ONLY -> 3
            AinaDeviceInfo.ButtonProtocol.UNKNOWN -> 4
        }

    // Composite picker sort used by the settings dropdown. Pulled out
    // as a pure function so unit tests can pin the ordering without
    // needing a live BluetoothAdapter — the field UX contract is:
    //   1. Currently-reachable devices first (visually normal, tappable).
    //   2. Then by protocol per [protocolOrder].
    //   3. Then by lowercase name for a stable order across refreshes.
    // Inspired by how modern call-picker UIs (Meet, WhatsApp Calls, the
    // AOSP device-chooser dialog) rank reachable devices ahead of the
    // stale-but-remembered set.
    fun rankForPicker(devices: List<AinaDeviceInfo>): List<AinaDeviceInfo> =
        devices.sortedWith(
            compareBy(
                { if (it.available) 0 else 1 },
                { protocolOrder(it.buttonProtocol) },
                { it.name.lowercase() },
            ),
        )

    // Choose the auto-connect candidate for the PRIMARY slot from a
    // ranked device list. Primary is where XV routes speakermic audio;
    // BLE_HID pucks (Pryme BT-PTT and similar HID-over-GATT buttons)
    // have no speaker / mic and belong in the SECONDARY slot alongside
    // the on-screen PTT, so we filter them out here even when they're
    // the only currently-"available" device.
    //
    // BLE_HID availability is hard-coded true in listBondedAinaDevices
    // (there's no comm-device signal we can query for a HID puck), so
    // without this filter a bonded-but-off Pryme wins auto-pick whenever
    // the operator's AINA is powered down at plugin load. Reported by
    // operator 2026-07-10.
    //
    // Expects [ranked] to already be sorted by [rankForPicker]. Returns
    // null if no non-BLE_HID device is currently available — the caller
    // should NOT fall back to picking a BLE_HID (the operator explicitly
    // does not want the button-only puck driving primary audio).
    fun pickPrimary(ranked: List<AinaDeviceInfo>): AinaDeviceInfo? =
        ranked.firstOrNull {
            it.available && it.buttonProtocol != AinaDeviceInfo.ButtonProtocol.BLE_HID
        }

    private fun safeName(device: BluetoothDevice): String? =
        try {
            device.name
        } catch (_: Throwable) {
            null
        }

    private fun safeUuids(device: BluetoothDevice): Array<ParcelUuid>? =
        try {
            device.uuids
        } catch (_: Throwable) {
            null
        }
}
