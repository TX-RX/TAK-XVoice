package com.atakmap.android.xv.audio

// Identifies which physical input drove a given PTT edge. Used by the
// OR-gate in [PttDispatcher] to support concurrent presses from multiple
// inputs (e.g. a motorcyclist holding the AINA-V2 button on their
// handlebar AND tapping the on-screen PTT card — second press must not
// start a fresh TX, and releasing one button must not end TX while the
// other is still held).
//
// `dropsTrailingClick` flags inputs whose physical button emits an
// audible click on release. The XvAudioCapture trailing-frame trim is
// gated on this flag so a release click that the operator actually
// wants to hear (the on-screen PTT tap "thud" — there is none) doesn't
// get suppressed. AINA hardware buttons click; the on-screen PTT does
// not. Pryme MFB clicks. Debug intents don't.
enum class PttSource(val dropsTrailingClick: Boolean) {
    ON_SCREEN(dropsTrailingClick = false),
    AINA_V1(dropsTrailingClick = true),
    AINA_V2(dropsTrailingClick = true),
    PRYME_BLE(dropsTrailingClick = true),

    // Sonim ruggedized-device dedicated PTT side button (XP10 and
    // XP-family peers). Fires through either the Sonim broadcast intent
    // path (`com.sonim.intent.action.PTT_KEY_DOWN` / `_UP`) or the
    // foreground KeyEvent path — see [com.atakmap.android.xv.util.SonimHardwareButtons].
    // The dedicated side key is a physical mechanical button with a
    // faint tactile click but no audible click through the audio path
    // (the button lives on the phone chassis, not on a BT
    // speakermic), so we do NOT trim the trailing frame on release —
    // same treatment as ON_SCREEN. Independent PTT source; runs in
    // parallel with AINA / External Button via the dispatcher's
    // OR-gate.
    SONIM_PTT(dropsTrailingClick = false),

    // Sonim ruggedized-device dedicated Emergency / SOS button. Distinct
    // from SONIM_PTT so a future iteration can promote presses of this
    // button into an emergency CoT event or SOS broadcast without
    // disturbing the plain PTT path. For now, treated as a plain
    // additional PTT source; the distinct enum value is what makes the
    // upgrade a local change.
    SONIM_EMERGENCY(dropsTrailingClick = false),
    DEBUG(dropsTrailingClick = false),
    ;

    companion object {
        // Fallback for call sites that don't know (or don't care) which
        // input drove the edge. Used by the call-button/auto-engage
        // paths inside [VoicePlant] where the source has already been
        // gated by other state and isn't strictly a button.
        val DEFAULT: PttSource = ON_SCREEN
    }
}
