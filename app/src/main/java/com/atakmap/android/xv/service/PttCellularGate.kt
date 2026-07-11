package com.atakmap.android.xv.service

import android.media.AudioManager
import android.telephony.TelephonyManager
import com.atakmap.android.xv.audio.PttSource

// Pure decision function for the "block PTT during an active cellular
// call" gate. Kept isolated from VoicePlant so it can be exercised by a
// plain JUnit test without Robolectric — the mapping from telephony
// call-state codes to gate outcome is the only interesting logic.
//
// Why this gate exists: XV places a self-managed Telecom call
// unconditionally on every PTT-down (see feedback_audio_plant_modes.md
// — the Telecom call is what claims MODE_IN_COMMUNICATION + audio focus
// so background media apps yield the SCO chipset). Android's Telecom
// framework auto-holds any active third-party call whenever a second
// self-managed call is placed. Operators fidgeting with a BT speakermic
// during an active cellular phone call have accidentally keyed PTT and
// dropped their phone call onto hold mid-conversation. This gate is
// Option A of the fix: block the TX entirely and prompt the operator
// to hang up first, rather than preempting the cellular call for what
// is almost certainly an unintentional press.
//
// State source: [AudioManager.getMode] rather than
// [TelephonyManager.getCallState]. The original PR wired the provider
// to getCallState() on the assumption that the plain non-permission
// API works from API 26 upward; that assumption was wrong from API 31
// onward — on Pixel API 35 getCallState throws SecurityException
// ("Neither user nor current process has android.permission.READ_PHONE_STATE")
// on every call. AudioManager.getMode requires no permission at any
// API level, reports MODE_IN_CALL while a cellular call is OFFHOOK and
// MODE_RINGTONE while one is ringing, and is what XV's own audio-plant
// code already reads elsewhere (see AudioControllerImpl / ScoLink /
// TptPlayer). We refuse to widen the app's permission surface for a
// fidget-guard during a production freeze.

enum class PttGate {
    /** Cellular telephony reports IDLE — proceed with TX as normal. */
    ALLOW,

    /** A cellular call is CONNECTED (OFFHOOK). Block TX so we do not
     *  auto-hold it via Telecom's second-self-managed-call arbitration. */
    BLOCK_CELLULAR_CALL,

    /** A cellular call is RINGING. Block TX so PTT does not race the
     *  operator's answer/decline decision on the incoming call UI. */
    BLOCK_CELLULAR_RINGING,
}

/**
 * Map a [TelephonyManager] call-state code + PTT [source] to the
 * corresponding [PttGate] outcome. Any unrecognized call-state value
 * defaults to [PttGate.ALLOW] — the gate fails open. Locking out PTT
 * because we could not read telephony state is worse than the
 * auto-hold bug this gate exists to fix; XV is a tactical voice tool
 * and unexplained mute is the more dangerous failure mode.
 *
 * Source-scoped policy (2026-07-10 revision): the cellular-call gate
 * only applies to [PttSource.ON_SCREEN] — the on-screen PTT button.
 * Every non-screen source (AINA V1/V2, Pryme MFB, any future
 * BT-HID / gpio hardware button, and the DEBUG intent path) bypasses
 * the gate entirely and returns [PttGate.ALLOW] even during OFFHOOK
 * or RINGING.
 *
 * Rationale:
 *  - On-screen PTT taps are accident-prone. A wayward finger during a
 *    phone call should not silently auto-hold the operator's call by
 *    placing XV's self-managed Telecom call. The toast + block is the
 *    right response for that path.
 *  - A hardware button press represents deliberate operator intent.
 *    Tactical scenarios routinely require an operator to key up
 *    mid-call (call-of-record on the phone, but the incident lives on
 *    XV). Blocking a hardware press would surprise-block a
 *    deliberate transmission attempt — a worse failure than the
 *    auto-hold it would prevent. The operator gets what they asked
 *    for; XV's Telecom call still auto-holds the cellular call as a
 *    side effect, but that side effect is now intentional.
 *  - DEBUG intents originate from adb / dev tooling, never from a
 *    stray touch. Treating them as bypass keeps the debug path
 *    usable while a cellular call is up.
 *
 * Future hardware sources added to [PttSource] inherit the ALLOW
 * behavior automatically — the gate only ever blocks the one
 * accident-prone input (ON_SCREEN).
 */
fun shouldGateForCellularCall(
    callState: Int,
    source: PttSource,
): PttGate =
    when {
        // Only the on-screen PTT tap is accident-prone enough to gate.
        // Every hardware button and the debug intent path bypass.
        source != PttSource.ON_SCREEN -> PttGate.ALLOW
        callState == TelephonyManager.CALL_STATE_OFFHOOK -> PttGate.BLOCK_CELLULAR_CALL
        callState == TelephonyManager.CALL_STATE_RINGING -> PttGate.BLOCK_CELLULAR_RINGING
        else -> PttGate.ALLOW
    }

/**
 * Decide whether a "cellular call active — hang up before XV PTT"
 * toast should be shown right now, given [nowMs] and the timestamp of
 * the previous toast [lastToastAtMs]. Rapid PTT button mashing during
 * a call would otherwise stack a wall of identical toasts; a fixed
 * throttle keeps at most one visible per [throttleMs] window.
 *
 * A [lastToastAtMs] value of 0 (the "never toasted" sentinel) always
 * qualifies. Callers store the returned decision's "now" value on true.
 */
fun shouldToastCellularBlock(
    nowMs: Long,
    lastToastAtMs: Long,
    throttleMs: Long = CELLULAR_BLOCK_TOAST_THROTTLE_MS,
): Boolean = lastToastAtMs <= 0L || (nowMs - lastToastAtMs) >= throttleMs

/** Minimum spacing between repeat "cellular call active" toasts. */
const val CELLULAR_BLOCK_TOAST_THROTTLE_MS: Long = 3_000L

/**
 * Map an [AudioManager] audio-mode constant to the equivalent
 * [TelephonyManager] call-state constant that [shouldGateForCellularCall]
 * consumes.
 *
 * Kept as its own pure function so the VoicePlant provider stays a
 * thin adapter (system-service lookup → this mapping → gate decision)
 * and the mapping can be unit-tested without Robolectric.
 *
 * Correspondence:
 * - [AudioManager.MODE_IN_CALL] → [TelephonyManager.CALL_STATE_OFFHOOK]:
 *   the telephony stack sets MODE_IN_CALL whenever a cellular call is
 *   in the OFFHOOK (connected) state.
 * - [AudioManager.MODE_RINGTONE] → [TelephonyManager.CALL_STATE_RINGING]:
 *   set while an inbound call is ringing.
 * - Everything else → [TelephonyManager.CALL_STATE_IDLE]. In particular
 *   [AudioManager.MODE_IN_COMMUNICATION] is what XV's OWN self-managed
 *   Telecom call uses (see AudioControllerImpl.enterTx) — mapping it
 *   to OFFHOOK here would gate ourselves out of every subsequent PTT
 *   press. MODE_NORMAL, MODE_CURRENT, and MODE_INVALID all fall to
 *   IDLE for the same fail-open rationale as
 *   [shouldGateForCellularCall].
 */
fun cellularCallStateFromAudioMode(audioMode: Int): Int =
    when (audioMode) {
        AudioManager.MODE_IN_CALL -> TelephonyManager.CALL_STATE_OFFHOOK
        AudioManager.MODE_RINGTONE -> TelephonyManager.CALL_STATE_RINGING
        else -> TelephonyManager.CALL_STATE_IDLE
    }
