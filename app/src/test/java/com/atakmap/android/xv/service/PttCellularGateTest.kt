package com.atakmap.android.xv.service

import android.media.AudioManager
import android.telephony.TelephonyManager
import com.atakmap.android.xv.audio.PttSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Coverage for the pure PTT cellular-call gate helpers extracted from
 * VoicePlant. These are intentionally free of Android framework state
 * (no Robolectric, no TelephonyManager instance) — only the integer
 * call-state code enum and a monotonic timestamp are exercised.
 */
class PttCellularGateTest {
    // ============================================================
    // shouldGateForCellularCall — decision function
    //
    // Source-scoped policy (2026-07-10 revision): the gate ONLY
    // applies to PttSource.ON_SCREEN. Every other source — every
    // hardware button (AINA V1/V2, Pryme MFB, and any future
    // BT-HID / gpio input) and the DEBUG intent path — bypasses
    // the gate and returns ALLOW even during OFFHOOK / RINGING.
    // The screen-tap path stays blocked because a stray finger
    // during a phone call should not auto-hold the call.
    // ============================================================

    @Test
    fun `IDLE call-state permits TX for on-screen source`() {
        assertEquals(
            PttGate.ALLOW,
            shouldGateForCellularCall(TelephonyManager.CALL_STATE_IDLE, PttSource.ON_SCREEN),
        )
    }

    @Test
    fun `OFFHOOK call-state blocks TX for on-screen source`() {
        // On-screen taps are the accident-prone path — a wayward
        // finger during a cellular call should not silently
        // auto-hold that call via XV's self-managed Telecom call.
        assertEquals(
            PttGate.BLOCK_CELLULAR_CALL,
            shouldGateForCellularCall(TelephonyManager.CALL_STATE_OFFHOOK, PttSource.ON_SCREEN),
        )
    }

    @Test
    fun `RINGING call-state blocks TX for on-screen source`() {
        assertEquals(
            PttGate.BLOCK_CELLULAR_RINGING,
            shouldGateForCellularCall(TelephonyManager.CALL_STATE_RINGING, PttSource.ON_SCREEN),
        )
    }

    @Test
    fun `unknown call-state fails open to ALLOW for on-screen source`() {
        // Fail-open is deliberate — a silent PTT lockout with no
        // operator-visible cause is a worse failure mode than the
        // auto-hold bug this gate exists to fix. See PttCellularGate.
        assertEquals(PttGate.ALLOW, shouldGateForCellularCall(-1, PttSource.ON_SCREEN))
        assertEquals(PttGate.ALLOW, shouldGateForCellularCall(Int.MAX_VALUE, PttSource.ON_SCREEN))
    }

    // --- Hardware sources bypass the gate entirely ---------------

    @Test
    fun `AINA V1 hardware bypasses gate during OFFHOOK`() {
        // Hardware button presses represent deliberate operator
        // intent. Tactical scenarios may require an operator to
        // key up mid-call; blocking a hardware press would
        // surprise-block a deliberate transmission attempt.
        assertEquals(
            PttGate.ALLOW,
            shouldGateForCellularCall(TelephonyManager.CALL_STATE_OFFHOOK, PttSource.AINA_V1),
        )
    }

    @Test
    fun `AINA V1 hardware bypasses gate during RINGING`() {
        assertEquals(
            PttGate.ALLOW,
            shouldGateForCellularCall(TelephonyManager.CALL_STATE_RINGING, PttSource.AINA_V1),
        )
    }

    @Test
    fun `AINA V2 hardware bypasses gate during OFFHOOK`() {
        assertEquals(
            PttGate.ALLOW,
            shouldGateForCellularCall(TelephonyManager.CALL_STATE_OFFHOOK, PttSource.AINA_V2),
        )
    }

    @Test
    fun `AINA V2 hardware bypasses gate during RINGING`() {
        assertEquals(
            PttGate.ALLOW,
            shouldGateForCellularCall(TelephonyManager.CALL_STATE_RINGING, PttSource.AINA_V2),
        )
    }

    @Test
    fun `Pryme BLE hardware bypasses gate during OFFHOOK`() {
        assertEquals(
            PttGate.ALLOW,
            shouldGateForCellularCall(TelephonyManager.CALL_STATE_OFFHOOK, PttSource.PRYME_BLE),
        )
    }

    @Test
    fun `Pryme BLE hardware bypasses gate during RINGING`() {
        assertEquals(
            PttGate.ALLOW,
            shouldGateForCellularCall(TelephonyManager.CALL_STATE_RINGING, PttSource.PRYME_BLE),
        )
    }

    @Test
    fun `DEBUG source bypasses gate during OFFHOOK`() {
        // DEBUG originates from adb / dev tooling, not from a
        // stray touch. Keep the debug path usable during a call.
        assertEquals(
            PttGate.ALLOW,
            shouldGateForCellularCall(TelephonyManager.CALL_STATE_OFFHOOK, PttSource.DEBUG),
        )
    }

    @Test
    fun `DEBUG source bypasses gate during RINGING`() {
        assertEquals(
            PttGate.ALLOW,
            shouldGateForCellularCall(TelephonyManager.CALL_STATE_RINGING, PttSource.DEBUG),
        )
    }

    @Test
    fun `IDLE call-state permits TX for every source`() {
        // Belt-and-suspenders sanity: IDLE must always return ALLOW
        // regardless of source. If a future PttSource ever slipped
        // into a code path that read IDLE-as-blocked, the
        // fidget-guard would flip from "silent success" to "silent
        // lockout" — the exact failure mode the fail-open policy
        // exists to prevent.
        for (src in PttSource.values()) {
            assertEquals(
                "IDLE + $src must ALLOW",
                PttGate.ALLOW,
                shouldGateForCellularCall(TelephonyManager.CALL_STATE_IDLE, src),
            )
        }
    }

    @Test
    fun `every non-screen source bypasses gate during OFFHOOK`() {
        // Data-driven complement to the enum-by-enum tests above.
        // Any hardware source added in the future automatically
        // gets the ALLOW-during-call treatment; the only source
        // that stays blocked is ON_SCREEN.
        for (src in PttSource.values()) {
            if (src == PttSource.ON_SCREEN) continue
            assertEquals(
                "$src must bypass OFFHOOK gate",
                PttGate.ALLOW,
                shouldGateForCellularCall(TelephonyManager.CALL_STATE_OFFHOOK, src),
            )
        }
    }

    @Test
    fun `every non-screen source bypasses gate during RINGING`() {
        for (src in PttSource.values()) {
            if (src == PttSource.ON_SCREEN) continue
            assertEquals(
                "$src must bypass RINGING gate",
                PttGate.ALLOW,
                shouldGateForCellularCall(TelephonyManager.CALL_STATE_RINGING, src),
            )
        }
    }

    // ============================================================
    // shouldToastCellularBlock — throttle helper
    // ============================================================

    @Test
    fun `first-ever toast always fires (sentinel zero)`() {
        assertTrue(
            "lastToastAtMs=0 sentinel must always allow toast",
            shouldToastCellularBlock(nowMs = 1_000L, lastToastAtMs = 0L),
        )
    }

    @Test
    fun `toast fires when throttle window has elapsed`() {
        // Default throttle is 3000 ms. Exactly at the window boundary
        // qualifies (>=).
        assertTrue(
            shouldToastCellularBlock(nowMs = 5_000L, lastToastAtMs = 2_000L),
        )
        assertTrue(
            shouldToastCellularBlock(nowMs = 10_000L, lastToastAtMs = 2_000L),
        )
    }

    @Test
    fun `toast is throttled when window has not elapsed`() {
        // Rapid PTT mashing during a call would otherwise stack toasts;
        // sub-3000ms gaps must be suppressed.
        assertFalse(
            shouldToastCellularBlock(nowMs = 4_000L, lastToastAtMs = 2_000L),
        )
        assertFalse(
            shouldToastCellularBlock(nowMs = 2_100L, lastToastAtMs = 2_000L),
        )
    }

    @Test
    fun `throttle window is configurable via throttleMs override`() {
        // A 1000 ms override should suppress a 400 ms gap and allow a
        // 1000 ms gap even though both would be blocked by the default
        // 3000 ms window. Sanity-check so a future caller can dial the
        // window without touching the default.
        assertFalse(
            shouldToastCellularBlock(nowMs = 500L, lastToastAtMs = 100L, throttleMs = 1_000L),
        )
        assertTrue(
            shouldToastCellularBlock(nowMs = 1_100L, lastToastAtMs = 100L, throttleMs = 1_000L),
        )
    }

    @Test
    fun `throttle constant is 3 seconds`() {
        assertEquals(3_000L, CELLULAR_BLOCK_TOAST_THROTTLE_MS)
    }

    // ============================================================
    // cellularCallStateFromAudioMode — permission-free state source
    // ============================================================

    @Test
    fun `MODE_IN_CALL maps to CALL_STATE_OFFHOOK`() {
        // AudioManager.MODE_IN_CALL is set by the telephony framework
        // whenever a cellular call is in the OFFHOOK (connected) state.
        // This is the primary "block PTT" trigger — we do not want XV
        // to place its self-managed Telecom call and auto-hold the
        // active cellular call.
        assertEquals(
            TelephonyManager.CALL_STATE_OFFHOOK,
            cellularCallStateFromAudioMode(AudioManager.MODE_IN_CALL),
        )
    }

    @Test
    fun `MODE_RINGTONE maps to CALL_STATE_RINGING`() {
        assertEquals(
            TelephonyManager.CALL_STATE_RINGING,
            cellularCallStateFromAudioMode(AudioManager.MODE_RINGTONE),
        )
    }

    @Test
    fun `MODE_NORMAL maps to CALL_STATE_IDLE`() {
        assertEquals(
            TelephonyManager.CALL_STATE_IDLE,
            cellularCallStateFromAudioMode(AudioManager.MODE_NORMAL),
        )
    }

    @Test
    fun `MODE_IN_COMMUNICATION maps to CALL_STATE_IDLE (not our own gate)`() {
        // XV's self-managed Telecom call sets MODE_IN_COMMUNICATION on
        // every TX (see AudioControllerImpl.enterTx). If we mapped that
        // to OFFHOOK the gate would fire on our OWN follow-up presses
        // during a burst — a permanent PTT lockout. Explicitly assert
        // the invariant "MODE_IN_CALL = cell, MODE_IN_COMMUNICATION =
        // us, skip".
        assertEquals(
            TelephonyManager.CALL_STATE_IDLE,
            cellularCallStateFromAudioMode(AudioManager.MODE_IN_COMMUNICATION),
        )
    }

    @Test
    fun `unknown audio-mode fails open to CALL_STATE_IDLE`() {
        // Same fail-open rationale as shouldGateForCellularCall — a
        // silent PTT lockout with no visible cause is worse than the
        // auto-hold bug this gate exists to fix. Any future audio-mode
        // constant we do not recognize (or a spurious MODE_INVALID)
        // must not gate PTT.
        assertEquals(
            TelephonyManager.CALL_STATE_IDLE,
            cellularCallStateFromAudioMode(AudioManager.MODE_INVALID),
        )
        assertEquals(
            TelephonyManager.CALL_STATE_IDLE,
            cellularCallStateFromAudioMode(Int.MAX_VALUE),
        )
    }

    @Test
    fun `AudioManager mode round-trips through the full gate for cell OFFHOOK on-screen`() {
        // End-to-end sanity: getMode() returns MODE_IN_CALL during an
        // active cellular call → provider adapter maps to
        // CALL_STATE_OFFHOOK → gate returns BLOCK_CELLULAR_CALL for
        // an on-screen tap.
        assertEquals(
            PttGate.BLOCK_CELLULAR_CALL,
            shouldGateForCellularCall(
                cellularCallStateFromAudioMode(AudioManager.MODE_IN_CALL),
                PttSource.ON_SCREEN,
            ),
        )
    }

    @Test
    fun `AudioManager mode round-trips through the full gate for ringing on-screen`() {
        assertEquals(
            PttGate.BLOCK_CELLULAR_RINGING,
            shouldGateForCellularCall(
                cellularCallStateFromAudioMode(AudioManager.MODE_RINGTONE),
                PttSource.ON_SCREEN,
            ),
        )
    }

    @Test
    fun `AudioManager MODE_IN_COMMUNICATION does not gate ourselves on-screen`() {
        // Full-pipeline complement to the mapping test: XV's own
        // Telecom call must never cause the gate to fire even on
        // the on-screen path.
        assertEquals(
            PttGate.ALLOW,
            shouldGateForCellularCall(
                cellularCallStateFromAudioMode(AudioManager.MODE_IN_COMMUNICATION),
                PttSource.ON_SCREEN,
            ),
        )
    }

    @Test
    fun `AudioManager MODE_IN_CALL does not gate an AINA hardware press`() {
        // Full-pipeline complement of the source-scope policy: even
        // when the cellular stack has claimed MODE_IN_CALL, a
        // hardware button press is deliberate operator intent and
        // must transmit. XV's Telecom call will still auto-hold the
        // cellular call as a side effect — but that side effect is
        // now the operator's choice, not an accident.
        assertEquals(
            PttGate.ALLOW,
            shouldGateForCellularCall(
                cellularCallStateFromAudioMode(AudioManager.MODE_IN_CALL),
                PttSource.AINA_V2,
            ),
        )
    }
}
