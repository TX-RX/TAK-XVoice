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
 * Policy (2026-07-11 revision — supersedes both the original
 * "block everything" (2026-07-10 21:00) and the source-scoped
 * "on-screen only" (2026-07-10 22:00) positions): **every PTT source
 * blocks during an active cellular call.** Phone calls are the #1
 * priority on the device — nothing XV does should silently interrupt
 * or auto-hold a live call regardless of which button initiated the
 * PTT.
 *
 * Rationale:
 *  - Field observation 2026-07-11: a hardware AINA button press
 *    during a cellular voicemail call auto-held the call (Android's
 *    Telecom framework parks the first self-managed call when a
 *    second one is placed). The operator had NOT intended to
 *    interrupt the call; the button was pressed as part of an
 *    unrelated test motion. That failure mode is the same
 *    "accident-prone" concern the on-screen tap has — and it turns
 *    out the "hardware = intentional" assumption in the earlier
 *    source-scoped policy was too generous.
 *  - Blocking every source keeps the invariant simple, testable, and
 *    matches operator intent: XV does not compete with a live phone
 *    call. The toast tells the operator what happened.
 *  - The [source] parameter is retained (rather than removed) so
 *    logging can still name the actual button that would have fired,
 *    and so a future need to re-introduce per-source behaviour does
 *    not have to reshape the API.
 */
fun shouldGateForCellularCall(
    callState: Int,
    @Suppress("UNUSED_PARAMETER") source: PttSource,
): PttGate =
    when (callState) {
        // All PTT sources get blocked — phone calls are #1 priority.
        // Hardware AINA button, Pryme puck, Samsung Active Key, Sonim
        // PTT / Emergency, on-screen, DEBUG intent — every one of them.
        TelephonyManager.CALL_STATE_OFFHOOK -> PttGate.BLOCK_CELLULAR_CALL
        TelephonyManager.CALL_STATE_RINGING -> PttGate.BLOCK_CELLULAR_RINGING
        else -> PttGate.ALLOW
    }

/**
 * Suppress a [PttGate.BLOCK_CELLULAR_CALL] verdict when XV itself is
 * currently holding the BT SCO link.
 *
 * The gate infers "a call is active" from [AudioManager.getMode]. But
 * XV's OWN audio plant drives the device into MODE_IN_COMMUNICATION /
 * MODE_IN_CALL whenever it holds the SCO link — RX SCO_HOT, the TX
 * cool-down tail, Hot-Mic, and the pre-warm window all acquire SCO with
 * no Telecom call registered. Field repro 2026-07-13 (Pixel 9 Pro):
 * after the operator's own Telecom call had ended ~39 s earlier, an
 * Assistant-screensaver wake re-acquired SCO_HOT; the gate saw
 * MODE_IN_COMMUNICATION with no registered call, mapped it to OFFHOOK,
 * and blocked a legitimate PTT with "Cellular call active." The
 * ActiveCallRegistry disambiguation could not catch it — there was no
 * Telecom call by then, only XV's audio-plant SCO hold.
 *
 * If XV holds the SCO link, the comm mode is unambiguously OURS, so the
 * OFFHOOK-derived block is a false positive → ALLOW. [PttGate.BLOCK_CELLULAR_RINGING]
 * is never suppressed: it comes from MODE_RINGTONE, which XV's plant
 * never sets, so a ringing state is always genuinely external.
 *
 * Trade-off (accepted): if a real external call is active AND XV happens
 * to be holding SCO at the same moment, this would let XV place its own
 * call. That requires XV to be actively holding SCO during an external
 * call — during which the cellular stack owns HFP/SCO and XV's acquire
 * would normally lose — so it is a rare edge. The gate is a fidget-guard,
 * not a hard interlock, and blocking a legitimate tactical PTT is the
 * worse failure (see [shouldGateForCellularCall]'s fail-open rationale).
 */
fun resolveGateWithOwnSco(
    gate: PttGate,
    xvHoldsSco: Boolean,
): PttGate =
    if (xvHoldsSco && gate == PttGate.BLOCK_CELLULAR_CALL) {
        PttGate.ALLOW
    } else {
        gate
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
    suppressInCommunicationDefensiveBlock: Boolean = false,
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
            } else if (suppressInCommunicationDefensiveBlock) {
                // Device-specific suppression — the defensive
                // "somebody else's VoIP call is holding
                // MODE_IN_COMMUNICATION" mapping is a false positive
                // on hardware where MODE_IN_COMMUNICATION is a
                // steady-state artefact rather than a real call
                // indicator. Field-observed 2026-07-14 on Sonim XP10
                // (AT&T carrier XP9900, Android 12): the resident
                // AT&T EPTT + Dispatch Hub apps hold
                // MODE_IN_COMMUNICATION continuously for minutes,
                // producing an unbroken stream of "Cellular call
                // active" blocks that don't correspond to any actual
                // call. Callers pass suppressInCommunicationDefensiveBlock=true
                // for known-false-positive device classes.
                //
                // Real cellular calls (MODE_IN_CALL, above) and
                // incoming rings (MODE_RINGTONE) still block
                // unconditionally regardless of this flag.
                TelephonyManager.CALL_STATE_IDLE
            } else {
                // Default: nobody in XV placed a call, yet the
                // device is in MODE_IN_COMMUNICATION. Somebody else
                // did — VoLTE cellular is the field-observed case,
                // but any other VoIP app in the foreground would
                // land here too. Treat as an active external call:
                // block the accident-prone on-screen PTT so we do
                // not auto-hold their call by placing our own.
                TelephonyManager.CALL_STATE_OFFHOOK
            }
        else -> TelephonyManager.CALL_STATE_IDLE
    }
