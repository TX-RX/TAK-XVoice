package com.atakmap.android.xv.aina

// Description of a bonded BT device the user can pick as a speakermic
// in Settings → Preferences. NOT AINA-specific — any HFP-class
// device (Pryme, Sheepdog, generic Bluetooth headset) shows up here
// alongside AINA V1/V2. The button-input protocol is detected by
// SDP UUID; audio routing relies on the device's HFP profile being
// paired in the OS. UNKNOWN buttonProtocol means we don't know how
// to read PTT events from this device — it's listed so the operator
// can still designate it as the audio target, but the on-screen PTT
// button is the only key path until we add HID / other input support.
data class AinaDeviceInfo(
    val mac: String,
    val name: String,
    val buttonProtocol: ButtonProtocol,
) {
    enum class ButtonProtocol(
        val display: String,
    ) {
        // V1-style SPP ASCII frames over RFCOMM. The classic AINA APTT
        // protocol; works for any device exposing the SerialPort UUID.
        SPP("SPP buttons"),

        // V2-style BLE GATT button-mask characteristic. AINA-specific
        // service UUID 127FACE1-…; XV reads via AinaBleReader.
        BLE("BLE buttons"),

        // Generic BLE HID-over-GATT button device — Pryme BT-PTT, BLE
        // PTT pucks, similar third-party hardware that pairs as a
        // HID input device and emits media-button keycodes (HEADSET-
        // HOOK, VOICE_ASSIST, VOLUME_*, PLAY/PAUSE) when pressed.
        // XV captures via a MediaSession in the foreground service.
        // Single-button devices fire VS1 only; XV doesn't yet
        // distinguish between primary/secondary on these.
        BLE_HID("BLE HID button (VS1 only)"),

        // Audio path only — device has HFP/audio profile but we don't
        // recognize a button protocol. On-screen PTT still works.
        AUDIO_ONLY("audio only"),

        // Couldn't tell. Listed so user can override; same caveat as
        // AUDIO_ONLY.
        UNKNOWN(""),
    }

    fun displayLabel(): String =
        if (buttonProtocol == ButtonProtocol.UNKNOWN) {
            name
        } else {
            "$name  ·  ${buttonProtocol.display}"
        }
}
