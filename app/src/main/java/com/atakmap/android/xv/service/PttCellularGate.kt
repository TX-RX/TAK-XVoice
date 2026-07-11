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
 *   the telephony stack sets MODE_IN_CALL whenever a legacy CSFB /
 *   circuit-switched cellular call is in the OFFHOOK (connected)
 *   state. Pre-VoLTE handsets and VoLTE-disabled carrier profiles
 *   still land here.
 * - [AudioManager.MODE_RINGTONE] → [TelephonyManager.CALL_STATE_RINGING]:
 *   set while an inbound call is ringing.
 * - [AudioManager.MODE_IN_COMMUNICATION]: **ambiguous on modern
 *   Android.** Two independent things put the device into
 *   MODE_IN_COMMUNICATION:
 *     1. XV's OWN self-managed Telecom call (see
 *        AudioControllerImpl.enterTx and
 *        XvVoiceService.placeTelecomCallInternal). Mapping this to
 *        OFFHOOK would self-gate every PTT during an active burst.
 *     2. On modern (API ≥ 30) Pixel/Samsung/etc. handsets with
 *        VoLTE-enabled cellular — which is the default on every
 *        major US carrier now — the telephony stack ALSO uses
 *        MODE_IN_COMMUNICATION for the real cellular call instead
 *        of the legacy MODE_IN_CALL. Same audio mode as any other
 *        VoIP app (Signal, WhatsApp, Discord, Meet, etc.). Field
 *        evidence from Pixel 9 Pro / API 35 (2026-07-11): the OS
 *        reported "OnAudioModeChanged: update mode from
 *        IN_COMMUNICATION to NORMAL" as a live VoLTE voicemail
 *        call hung up. The old MODE_IN_COMMUNICATION → IDLE
 *        mapping incorrectly treated that as "not a phone call",
 *        allowed the on-screen PTT, and let XV's self-managed
 *        Telecom call auto-hold the operator's live call.
 *   Disambiguate at the call site: if XV already has an active
 *   self-managed Telecom call ([xvHasActiveTelecomCall] = true),
 *   MODE_IN_COMMUNICATION is OURS → IDLE (do not self-gate).
 *   Otherwise MODE_IN_COMMUNICATION is somebody else's call
 *   (VoLTE cellular, or another VoIP app) → OFFHOOK (block the
 *   on-screen PTT so we do not auto-hold their call).
 * - Everything else → [TelephonyManager.CALL_STATE_IDLE].
 *   MODE_NORMAL, MODE_CURRENT, and MODE_INVALID all fall to IDLE
 *   for the same fail-open rationale as
 *   [shouldGateForCellularCall].
 *
 * [xvHasActiveTelecomCall] is sampled at the moment the gate runs
 * — supplied by the caller from
 * [com.atakmap.android.xv.telecom.ActiveCallRegistry.hasActiveCall].
 * The registration and unregistration lifecycle is driven by
 * XvConnection.onDestroy / teardownLocal, which is invoked by the
 * TELECOM_END_DEBOUNCE_MS runnable in XvVoiceService — so the
 * "our own call" flag stays true across the 8 s post-activity
 * wind-down and drops only after Telecom has actually torn our
 * connection down.
 */
fun cellularCallStateFromAudioMode(
    audioMode: Int,
    xvHasActiveTelecomCall: Boolean,
): Int =
    when (audioMode) {
        AudioManager.MODE_IN_CALL -> TelephonyManager.CALL_STATE_OFFHOOK
        AudioManager.MODE_RINGTONE -> TelephonyManager.CALL_STATE_RINGING
        AudioManager.MODE_IN_COMMUNICATION ->
            if (xvHasActiveTelecomCall) {
                // OUR own Telecom call is holding MODE_IN_COMMUNICATION.
                // Do not gate the operator out of their own follow-up
                // presses inside a burst / the end-debounce window.
                TelephonyManager.CALL_STATE_IDLE
            } else {
                // Nobody in XV placed a call, yet the device is in
                // MODE_IN_COMMUNICATION. Somebody else did — VoLTE
                // cellular is the field-observed case, but any other
                // VoIP app in the foreground would land here too.
                // Treat as an active external call: block the
                // accident-prone on-screen PTT so we do not
                // auto-hold their call by placing our own.
                TelephonyManager.CALL_STATE_OFFHOOK
            }
        else -> TelephonyManager.CALL_STATE_IDLE
    }
