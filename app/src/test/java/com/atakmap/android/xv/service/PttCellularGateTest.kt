package com.atakmap.android.xv.service

import android.media.AudioManager
import android.telephony.TelephonyManager
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
    // ============================================================

    @Test
    fun `IDLE call-state permits TX`() {
        assertEquals(
            PttGate.ALLOW,
            shouldGateForCellularCall(TelephonyManager.CALL_STATE_IDLE),
        )
    }

    @Test
    fun `OFFHOOK call-state blocks TX for active cellular call`() {
        assertEquals(
            PttGate.BLOCK_CELLULAR_CALL,
            shouldGateForCellularCall(TelephonyManager.CALL_STATE_OFFHOOK),
        )
    }

    @Test
    fun `RINGING call-state blocks TX for incoming cellular call`() {
        assertEquals(
            PttGate.BLOCK_CELLULAR_RINGING,
            shouldGateForCellularCall(TelephonyManager.CALL_STATE_RINGING),
        )
    }

    @Test
    fun `unknown call-state fails open to ALLOW`() {
        // Fail-open is deliberate — a silent PTT lockout with no
        // operator-visible cause is a worse failure mode than the
        // auto-hold bug this gate exists to fix. See PttCellularGate.
        assertEquals(PttGate.ALLOW, shouldGateForCellularCall(-1))
        assertEquals(PttGate.ALLOW, shouldGateForCellularCall(Int.MAX_VALUE))
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
    fun `AudioManager mode round-trips through the full gate for cell OFFHOOK`() {
        // End-to-end sanity: getMode() returns MODE_IN_CALL during an
        // active cellular call → provider adapter maps to
        // CALL_STATE_OFFHOOK → gate returns BLOCK_CELLULAR_CALL.
        assertEquals(
            PttGate.BLOCK_CELLULAR_CALL,
            shouldGateForCellularCall(
                cellularCallStateFromAudioMode(AudioManager.MODE_IN_CALL),
            ),
        )
    }

    @Test
    fun `AudioManager mode round-trips through the full gate for ringing`() {
        assertEquals(
            PttGate.BLOCK_CELLULAR_RINGING,
            shouldGateForCellularCall(
                cellularCallStateFromAudioMode(AudioManager.MODE_RINGTONE),
            ),
        )
    }

    @Test
    fun `AudioManager MODE_IN_COMMUNICATION does not gate ourselves`() {
        // Full-pipeline complement to the mapping test: XV's own
        // Telecom call must never cause the gate to fire.
        assertEquals(
            PttGate.ALLOW,
            shouldGateForCellularCall(
                cellularCallStateFromAudioMode(AudioManager.MODE_IN_COMMUNICATION),
            ),
        )
    }
}
