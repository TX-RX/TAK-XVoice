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
    // Block-all-sources policy (2026-07-11 revision — supersedes
    // both the original "block everything" position from
    // 2026-07-10 21:00 and the source-scoped "on-screen only"
    // position from 2026-07-10 22:00): EVERY PttSource blocks
    // during an active cellular call (OFFHOOK / RINGING). Phone
    // calls are the #1 priority on the device — nothing XV does
    // should silently interrupt or auto-hold a live call
    // regardless of which button fired the PTT.
    //
    // Field observation 2026-07-11 that drove the revision: a
    // hardware AINA button press during a cellular voicemail call
    // auto-held the call via Android's Telecom second-self-managed-
    // call arbitration. The press was unintentional — the previous
    // "hardware = deliberate intent" assumption was too generous.
    // The [source] parameter is retained (rather than removed) so
    // logging can still name the button that would have fired, and
    // so a future need to reintroduce per-source behaviour does not
    // have to reshape the gate API.
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

    // --- Hardware sources are ALSO blocked (block-all-sources policy) ---

    @Test
    fun `AINA V1 hardware blocks during OFFHOOK`() {
        // 2026-07-11 revision: hardware presses no longer bypass the
        // gate. A live cellular call is authoritative; every PTT
        // source — including the operator's BT speakermic button —
        // must be blocked so XV does not auto-hold the call by
        // placing its own self-managed Telecom call on top.
        assertEquals(
            PttGate.BLOCK_CELLULAR_CALL,
            shouldGateForCellularCall(TelephonyManager.CALL_STATE_OFFHOOK, PttSource.AINA_V1),
        )
    }

    @Test
    fun `AINA V1 hardware blocks during RINGING`() {
        assertEquals(
            PttGate.BLOCK_CELLULAR_RINGING,
            shouldGateForCellularCall(TelephonyManager.CALL_STATE_RINGING, PttSource.AINA_V1),
        )
    }

    @Test
    fun `AINA V2 hardware blocks during OFFHOOK`() {
        assertEquals(
            PttGate.BLOCK_CELLULAR_CALL,
            shouldGateForCellularCall(TelephonyManager.CALL_STATE_OFFHOOK, PttSource.AINA_V2),
        )
    }

    @Test
    fun `AINA V2 hardware blocks during RINGING`() {
        assertEquals(
            PttGate.BLOCK_CELLULAR_RINGING,
            shouldGateForCellularCall(TelephonyManager.CALL_STATE_RINGING, PttSource.AINA_V2),
        )
    }

    @Test
    fun `Pryme BLE hardware blocks during OFFHOOK`() {
        assertEquals(
            PttGate.BLOCK_CELLULAR_CALL,
            shouldGateForCellularCall(TelephonyManager.CALL_STATE_OFFHOOK, PttSource.PRYME_BLE),
        )
    }

    @Test
    fun `Pryme BLE hardware blocks during RINGING`() {
        assertEquals(
            PttGate.BLOCK_CELLULAR_RINGING,
            shouldGateForCellularCall(TelephonyManager.CALL_STATE_RINGING, PttSource.PRYME_BLE),
        )
    }

    @Test
    fun `DEBUG source blocks during OFFHOOK`() {
        // DEBUG originates from adb / dev tooling. Under the
        // block-all-sources policy it is blocked too so unit /
        // integration tests observe the same behavior operators
        // see in the field — no accidental "works from adb, fails
        // from a button" divergence.
        assertEquals(
            PttGate.BLOCK_CELLULAR_CALL,
            shouldGateForCellularCall(TelephonyManager.CALL_STATE_OFFHOOK, PttSource.DEBUG),
        )
    }

    @Test
    fun `DEBUG source blocks during RINGING`() {
        assertEquals(
            PttGate.BLOCK_CELLULAR_RINGING,
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
    fun `every source blocks during OFFHOOK`() {
        // Data-driven complement to the enum-by-enum tests above.
        // Under the block-all-sources policy EVERY PttSource —
        // including any future BT-HID / gpio / vendor-specific
        // input — must return BLOCK_CELLULAR_CALL during an
        // active cellular call. No ON_SCREEN skip: the on-screen
        // path is blocked the same way as everything else.
        for (src in PttSource.values()) {
            assertEquals(
                "$src must BLOCK during OFFHOOK",
                PttGate.BLOCK_CELLULAR_CALL,
                shouldGateForCellularCall(TelephonyManager.CALL_STATE_OFFHOOK, src),
            )
        }
    }

    @Test
    fun `every source blocks during RINGING`() {
        for (src in PttSource.values()) {
            assertEquals(
                "$src must BLOCK during RINGING",
                PttGate.BLOCK_CELLULAR_RINGING,
                shouldGateForCellularCall(TelephonyManager.CALL_STATE_RINGING, src),
            )
        }
    }

    @Test
    fun `unknown call-state fails open to ALLOW for every source`() {
        // Fail-open policy applies uniformly across sources: any
        // unrecognized call-state value must ALLOW regardless of
        // which button fired the PTT. Silent lockouts on
        // unrecognized state codes are worse than the auto-hold
        // bug the gate exists to fix.
        for (src in PttSource.values()) {
            assertEquals(
                "-1 + $src must ALLOW (fail-open)",
                PttGate.ALLOW,
                shouldGateForCellularCall(-1, src),
            )
            assertEquals(
                "Int.MAX_VALUE + $src must ALLOW (fail-open)",
                PttGate.ALLOW,
                shouldGateForCellularCall(Int.MAX_VALUE, src),
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
    //
    // Two-input pure function: (audioMode, xvHasActiveTelecomCall).
    // The second input disambiguates MODE_IN_COMMUNICATION, which
    // modern Android uses BOTH for XV's own self-managed Telecom
    // call AND for a real VoLTE cellular call (as of API 30+ on
    // every major US carrier's default VoLTE profile — field
    // evidence: Pixel 9 Pro / API 35, 2026-07-11, voicemail call
    // observed in MODE_IN_COMMUNICATION). Without the second
    // input the gate cannot tell "we placed this call" from "the
    // cellular stack placed this call" and either self-gates
    // every PTT (map to OFFHOOK) or fails to block VoLTE (map to
    // IDLE — the pre-fix behavior that PR #37 shipped and the
    // field bug this test suite guards against).
    // ============================================================

    @Test
    fun `MODE_IN_CALL maps to CALL_STATE_OFFHOOK regardless of own-call state`() {
        // AudioManager.MODE_IN_CALL is set by the telephony framework
        // whenever a legacy CSFB / circuit-switched cellular call is
        // OFFHOOK. Pre-VoLTE handsets and VoLTE-disabled carrier
        // profiles land here. XV never enters MODE_IN_CALL itself
        // (our self-managed VoIP call uses MODE_IN_COMMUNICATION),
        // so this branch is independent of xvHasActiveTelecomCall —
        // real cellular always wins.
        assertEquals(
            TelephonyManager.CALL_STATE_OFFHOOK,
            cellularCallStateFromAudioMode(
                audioMode = AudioManager.MODE_IN_CALL,
                xvHasActiveTelecomCall = false,
            ),
        )
        assertEquals(
            "MODE_IN_CALL with our own call already up must still map to OFFHOOK — " +
                "cellular MODE_IN_CALL is authoritative and XV never enters it",
            TelephonyManager.CALL_STATE_OFFHOOK,
            cellularCallStateFromAudioMode(
                audioMode = AudioManager.MODE_IN_CALL,
                xvHasActiveTelecomCall = true,
            ),
        )
    }

    @Test
    fun `MODE_RINGTONE maps to CALL_STATE_RINGING regardless of own-call state`() {
        assertEquals(
            TelephonyManager.CALL_STATE_RINGING,
            cellularCallStateFromAudioMode(
                audioMode = AudioManager.MODE_RINGTONE,
                xvHasActiveTelecomCall = false,
            ),
        )
        assertEquals(
            TelephonyManager.CALL_STATE_RINGING,
            cellularCallStateFromAudioMode(
                audioMode = AudioManager.MODE_RINGTONE,
                xvHasActiveTelecomCall = true,
            ),
        )
    }

    @Test
    fun `MODE_NORMAL maps to CALL_STATE_IDLE regardless of own-call state`() {
        assertEquals(
            TelephonyManager.CALL_STATE_IDLE,
            cellularCallStateFromAudioMode(
                audioMode = AudioManager.MODE_NORMAL,
                xvHasActiveTelecomCall = false,
            ),
        )
        assertEquals(
            TelephonyManager.CALL_STATE_IDLE,
            cellularCallStateFromAudioMode(
                audioMode = AudioManager.MODE_NORMAL,
                xvHasActiveTelecomCall = true,
            ),
        )
    }

    @Test
    fun `MODE_IN_COMMUNICATION with our own active call maps to IDLE`() {
        // XV's self-managed Telecom call sets MODE_IN_COMMUNICATION on
        // every TX (see AudioControllerImpl.enterTx). If we mapped that
        // to OFFHOOK the gate would fire on our OWN follow-up presses
        // during a burst — a permanent PTT lockout. The own-call flag
        // is how we detect that we placed the call.
        assertEquals(
            TelephonyManager.CALL_STATE_IDLE,
            cellularCallStateFromAudioMode(
                audioMode = AudioManager.MODE_IN_COMMUNICATION,
                xvHasActiveTelecomCall = true,
            ),
        )
    }

    @Test
    fun `MODE_IN_COMMUNICATION with NO own active call maps to OFFHOOK (VoLTE cellular)`() {
        // Field-critical case, 2026-07-11 regression fix. Modern
        // Pixel / Samsung / Motorola handsets with VoLTE enabled
        // (default on every major US carrier's plans) put the OS in
        // MODE_IN_COMMUNICATION for the REAL cellular call — the
        // same audio mode our self-managed VoIP call uses. Log
        // evidence from Pixel 9 Pro / API 35 during a live
        // voicemail call:
        //
        //   AHal::CrystalClearAudioWrapper: OnAudioModeChanged:
        //   update mode from IN_COMMUNICATION to NORMAL
        //
        // Before this fix the mapping unconditionally returned IDLE
        // for MODE_IN_COMMUNICATION on the theory that only XV
        // could be responsible for that mode. That was wrong: any
        // VoIP app (Signal, WhatsApp, Discord, Meet) can also
        // claim MODE_IN_COMMUNICATION, and VoLTE cellular does too.
        //
        // The fix: if XV has no active self-managed Telecom call
        // and the device is nonetheless in MODE_IN_COMMUNICATION,
        // somebody else placed the call. Treat as OFFHOOK so the
        // on-screen PTT gate blocks and XV does not auto-hold the
        // operator's live call by placing our own self-managed
        // call on top.
        assertEquals(
            TelephonyManager.CALL_STATE_OFFHOOK,
            cellularCallStateFromAudioMode(
                audioMode = AudioManager.MODE_IN_COMMUNICATION,
                xvHasActiveTelecomCall = false,
            ),
        )
    }

    @Test
    fun `MODE_IN_COMMUNICATION with suppression flag maps to IDLE (Sonim carrier false-positive)`() {
        // Device-specific suppression, field-observed 2026-07-14 on
        // Sonim XP9900 (AT&T carrier, Android 12): the resident AT&T
        // EPTT / Dispatch Hub apps hold MODE_IN_COMMUNICATION
        // continuously with no actual call, so the default OFFHOOK
        // mapping above produced an unbroken stream of false-positive
        // "hang up before PTT" blocks. Callers pass
        // suppressInCommunicationDefensiveBlock=true for those device
        // classes; the mapping then falls through to IDLE so PTT is not
        // gated.
        assertEquals(
            TelephonyManager.CALL_STATE_IDLE,
            cellularCallStateFromAudioMode(
                audioMode = AudioManager.MODE_IN_COMMUNICATION,
                xvHasActiveTelecomCall = false,
                suppressInCommunicationDefensiveBlock = true,
            ),
        )
    }

    @Test
    fun `suppression flag does NOT relax MODE_IN_CALL — real cellular still blocks`() {
        // The suppression only touches the ambiguous
        // MODE_IN_COMMUNICATION artefact. A real cellular call
        // (MODE_IN_CALL) must still block unconditionally regardless of
        // the flag.
        assertEquals(
            TelephonyManager.CALL_STATE_OFFHOOK,
            cellularCallStateFromAudioMode(
                audioMode = AudioManager.MODE_IN_CALL,
                xvHasActiveTelecomCall = false,
                suppressInCommunicationDefensiveBlock = true,
            ),
        )
    }

    @Test
    fun `suppression flag does NOT relax MODE_RINGTONE — incoming ring still blocks`() {
        assertEquals(
            TelephonyManager.CALL_STATE_RINGING,
            cellularCallStateFromAudioMode(
                audioMode = AudioManager.MODE_RINGTONE,
                xvHasActiveTelecomCall = false,
                suppressInCommunicationDefensiveBlock = true,
            ),
        )
    }

    @Test
    fun `unknown audio-mode fails open to CALL_STATE_IDLE regardless of own-call state`() {
        // Same fail-open rationale as shouldGateForCellularCall — a
        // silent PTT lockout with no visible cause is worse than the
        // auto-hold bug this gate exists to fix. Any future audio-mode
        // constant we do not recognize (or a spurious MODE_INVALID)
        // must not gate PTT.
        assertEquals(
            TelephonyManager.CALL_STATE_IDLE,
            cellularCallStateFromAudioMode(
                audioMode = AudioManager.MODE_INVALID,
                xvHasActiveTelecomCall = false,
            ),
        )
        assertEquals(
            TelephonyManager.CALL_STATE_IDLE,
            cellularCallStateFromAudioMode(
                audioMode = AudioManager.MODE_INVALID,
                xvHasActiveTelecomCall = true,
            ),
        )
        assertEquals(
            TelephonyManager.CALL_STATE_IDLE,
            cellularCallStateFromAudioMode(
                audioMode = Int.MAX_VALUE,
                xvHasActiveTelecomCall = false,
            ),
        )
        assertEquals(
            TelephonyManager.CALL_STATE_IDLE,
            cellularCallStateFromAudioMode(
                audioMode = Int.MAX_VALUE,
                xvHasActiveTelecomCall = true,
            ),
        )
    }

    @Test
    fun `AudioManager mode round-trips through the full gate for legacy cell OFFHOOK on-screen`() {
        // End-to-end sanity: getMode() returns MODE_IN_CALL during an
        // active cellular call → provider adapter maps to
        // CALL_STATE_OFFHOOK → gate returns BLOCK_CELLULAR_CALL for
        // an on-screen tap.
        assertEquals(
            PttGate.BLOCK_CELLULAR_CALL,
            shouldGateForCellularCall(
                cellularCallStateFromAudioMode(
                    audioMode = AudioManager.MODE_IN_CALL,
                    xvHasActiveTelecomCall = false,
                ),
                PttSource.ON_SCREEN,
            ),
        )
    }

    @Test
    fun `AudioManager mode round-trips through the full gate for ringing on-screen`() {
        assertEquals(
            PttGate.BLOCK_CELLULAR_RINGING,
            shouldGateForCellularCall(
                cellularCallStateFromAudioMode(
                    audioMode = AudioManager.MODE_RINGTONE,
                    xvHasActiveTelecomCall = false,
                ),
                PttSource.ON_SCREEN,
            ),
        )
    }

    @Test
    fun `AudioManager mode round-trips through the full gate for VoLTE cell on-screen`() {
        // End-to-end sanity for the Pixel-VoLTE regression: getMode()
        // returns MODE_IN_COMMUNICATION during an active VoLTE call,
        // and XV has NOT placed its own Telecom call yet. Provider
        // adapter must map to CALL_STATE_OFFHOOK and the gate must
        // return BLOCK_CELLULAR_CALL for an on-screen tap. Before
        // this fix the mapping was IDLE → ALLOW → XV placed its own
        // call → Telecom auto-held the operator's VoLTE call. This
        // test locks in the correct behavior.
        assertEquals(
            PttGate.BLOCK_CELLULAR_CALL,
            shouldGateForCellularCall(
                cellularCallStateFromAudioMode(
                    audioMode = AudioManager.MODE_IN_COMMUNICATION,
                    xvHasActiveTelecomCall = false,
                ),
                PttSource.ON_SCREEN,
            ),
        )
    }

    @Test
    fun `AudioManager MODE_IN_COMMUNICATION does not gate ourselves on-screen`() {
        // Full-pipeline complement to the mapping test: XV's own
        // Telecom call must never cause the gate to fire even on
        // the on-screen path. This is what keeps mid-burst PTT
        // presses working — during a burst xvHasActiveTelecomCall
        // is true (registry entry lives until end-debounce fires).
        assertEquals(
            PttGate.ALLOW,
            shouldGateForCellularCall(
                cellularCallStateFromAudioMode(
                    audioMode = AudioManager.MODE_IN_COMMUNICATION,
                    xvHasActiveTelecomCall = true,
                ),
                PttSource.ON_SCREEN,
            ),
        )
    }

    @Test
    fun `AudioManager MODE_IN_CALL blocks an AINA hardware press`() {
        // Full-pipeline complement of the block-all-sources policy
        // (2026-07-11 revision): even a hardware AINA button press
        // is blocked when the cellular stack has claimed
        // MODE_IN_CALL. The prior "hardware = deliberate intent"
        // carve-out was retired after a field observation showed
        // an unintentional hardware press auto-holding a live
        // voicemail call. Phone calls are the #1 priority; every
        // PTT source defers to them.
        assertEquals(
            PttGate.BLOCK_CELLULAR_CALL,
            shouldGateForCellularCall(
                cellularCallStateFromAudioMode(
                    audioMode = AudioManager.MODE_IN_CALL,
                    xvHasActiveTelecomCall = false,
                ),
                PttSource.AINA_V2,
            ),
        )
    }

    @Test
    fun `AudioManager MODE_IN_COMMUNICATION VoLTE blocks an AINA hardware press`() {
        // Same block-all-sources policy but for the VoLTE case: a
        // hardware key-up during an active VoLTE call is blocked
        // for the same reason as MODE_IN_CALL — the state
        // resolution correctly identifies the call as external,
        // and every PTT source (including hardware) defers to a
        // live cellular call.
        assertEquals(
            PttGate.BLOCK_CELLULAR_CALL,
            shouldGateForCellularCall(
                cellularCallStateFromAudioMode(
                    audioMode = AudioManager.MODE_IN_COMMUNICATION,
                    xvHasActiveTelecomCall = false,
                ),
                PttSource.AINA_V2,
            ),
        )
    }

    // ============================================================
    // Post-teardown grace window — VoicePlant closure semantics
    //
    // These tests model the OR that the `cellularCallStateProvider`
    // closure in VoicePlant performs before calling
    // [cellularCallStateFromAudioMode]:
    //
    //   val xvOwnCallOrGrace =
    //       registry.hasActiveCall() ||
    //       registry.withinRecentCallGrace(nowMs, RECENT_OWN_CALL_GRACE_MS)
    //
    // The pure gate function's contract does not change — it still
    // receives a single Boolean "own call?" input. The disambiguation
    // between "call is literally active" and "call was recently
    // active" happens at the caller level. The tests here lock in
    // the round-trip through the pure functions so a future refactor
    // that alters the pure gate's Boolean contract does not silently
    // regress the grace path.
    //
    // Field trace 2026-07-11 14:47 (Pixel 9 Pro / API 35):
    //   14:47:14.722 XV Telecom externally torn down → registry
    //                unregister → hasActiveCall = false
    //   14:47:14.765 AudioManager.getMode() still IN_COMMUNICATION
    //   14:47:16.126 AudioManager.getMode() finally NORMAL
    // The ~1.4 s window between 14.722 and 16.126 is where operator
    // PTT presses were being falsely blocked.
    // ============================================================

    @Test
    fun `IN_COMMUNICATION during post-teardown grace resolves as own call — ALLOW`() {
        // hasActiveCall=false (registry already unregistered), but
        // withinGrace=true (audio mode still catching up). The
        // provider closure ORs them; the pure gate sees "own call =
        // true", maps IN_COMMUNICATION → IDLE, and the gate ALLOWs.
        // This is the scenario the fix restores from the field
        // regression.
        val withinGrace = true
        val hasActiveCall = false
        val xvOwnCallOrGrace = hasActiveCall || withinGrace
        assertEquals(
            PttGate.ALLOW,
            shouldGateForCellularCall(
                cellularCallStateFromAudioMode(
                    audioMode = AudioManager.MODE_IN_COMMUNICATION,
                    xvHasActiveTelecomCall = xvOwnCallOrGrace,
                ),
                PttSource.ON_SCREEN,
            ),
        )
    }

    @Test
    fun `IN_COMMUNICATION after grace expired still blocks external call — BLOCK`() {
        // Both hasActiveCall and withinGrace are false — grace
        // window has elapsed and no fresh own call exists. This is
        // an actual external call (VoLTE cellular, other VoIP app)
        // and the block-all-sources policy must still fire.
        val withinGrace = false
        val hasActiveCall = false
        val xvOwnCallOrGrace = hasActiveCall || withinGrace
        assertEquals(
            PttGate.BLOCK_CELLULAR_CALL,
            shouldGateForCellularCall(
                cellularCallStateFromAudioMode(
                    audioMode = AudioManager.MODE_IN_COMMUNICATION,
                    xvHasActiveTelecomCall = xvOwnCallOrGrace,
                ),
                PttSource.ON_SCREEN,
            ),
        )
    }

    @Test
    fun `grace window covers every PTT source during audio-HAL tail`() {
        // The grace does not carve out per-source behaviour — if the
        // OR resolves to "still ours" the whole gate treats the mode
        // as IDLE for every source. A hardware AINA press, on-screen
        // tap, Pryme puck, and DEBUG intent all ALLOW during the
        // tail, matching operator intent that mid-burst / just-
        // -after-burst presses should not lock out.
        val withinGrace = true
        val hasActiveCall = false
        val xvOwnCallOrGrace = hasActiveCall || withinGrace
        for (src in PttSource.values()) {
            assertEquals(
                "$src must ALLOW during post-teardown grace",
                PttGate.ALLOW,
                shouldGateForCellularCall(
                    cellularCallStateFromAudioMode(
                        audioMode = AudioManager.MODE_IN_COMMUNICATION,
                        xvHasActiveTelecomCall = xvOwnCallOrGrace,
                    ),
                    src,
                ),
            )
        }
    }

    @Test
    fun `grace window does not unlock a legacy MODE_IN_CALL cellular call`() {
        // Belt-and-suspenders: the grace only papers over the
        // MODE_IN_COMMUNICATION ambiguity. If the audio HAL reports
        // MODE_IN_CALL — the legacy CSFB / non-VoLTE path that XV
        // NEVER enters itself — the gate must still block regardless
        // of the grace, because MODE_IN_CALL is unambiguously an
        // external cellular call. The pure mapping already handles
        // this (MODE_IN_CALL → OFFHOOK independent of the own-call
        // flag), but locking it in here defends against a future
        // refactor that might route the flag through more broadly.
        val withinGrace = true
        val hasActiveCall = false
        val xvOwnCallOrGrace = hasActiveCall || withinGrace
        assertEquals(
            PttGate.BLOCK_CELLULAR_CALL,
            shouldGateForCellularCall(
                cellularCallStateFromAudioMode(
                    audioMode = AudioManager.MODE_IN_CALL,
                    xvHasActiveTelecomCall = xvOwnCallOrGrace,
                ),
                PttSource.ON_SCREEN,
            ),
        )
    }

    @Test
    fun `grace window does not unlock MODE_RINGTONE`() {
        // Same rationale as MODE_IN_CALL — MODE_RINGTONE is
        // unambiguously an incoming external call. The grace only
        // exists to hide the IN_COMMUNICATION tail from misclassifying
        // an own-call teardown as external.
        val withinGrace = true
        val hasActiveCall = false
        val xvOwnCallOrGrace = hasActiveCall || withinGrace
        assertEquals(
            PttGate.BLOCK_CELLULAR_RINGING,
            shouldGateForCellularCall(
                cellularCallStateFromAudioMode(
                    audioMode = AudioManager.MODE_RINGTONE,
                    xvHasActiveTelecomCall = xvOwnCallOrGrace,
                ),
                PttSource.ON_SCREEN,
            ),
        )
    }

    // ============================================================
    // resolveGateWithOwnSco — suppress the false-positive block when
    // XV's own audio plant is holding the SCO link (the comm-mode the
    // gate detected is ours, not an external call). 2026-07-13 repro:
    // screensaver-wake re-acquired SCO_HOT ~39 s after XV's own call
    // ended, and the gate misread MODE_IN_COMMUNICATION as external.
    // ============================================================

    @Test
    fun `own-SCO hold flips a cellular-call block to ALLOW`() {
        assertEquals(
            PttGate.ALLOW,
            resolveGateWithOwnSco(PttGate.BLOCK_CELLULAR_CALL, xvHoldsSco = true),
        )
    }

    @Test
    fun `no SCO hold leaves a cellular-call block intact (real external call)`() {
        assertEquals(
            PttGate.BLOCK_CELLULAR_CALL,
            resolveGateWithOwnSco(PttGate.BLOCK_CELLULAR_CALL, xvHoldsSco = false),
        )
    }

    @Test
    fun `ringing is never suppressed even when XV holds SCO`() {
        // MODE_RINGTONE is unambiguously an incoming external call; XV's
        // plant never drives it. Holding SCO must NOT unlock PTT here.
        assertEquals(
            PttGate.BLOCK_CELLULAR_RINGING,
            resolveGateWithOwnSco(PttGate.BLOCK_CELLULAR_RINGING, xvHoldsSco = true),
        )
    }

    @Test
    fun `ALLOW stays ALLOW regardless of SCO hold`() {
        assertEquals(
            PttGate.ALLOW,
            resolveGateWithOwnSco(PttGate.ALLOW, xvHoldsSco = true),
        )
        assertEquals(
            PttGate.ALLOW,
            resolveGateWithOwnSco(PttGate.ALLOW, xvHoldsSco = false),
        )
    }

    @Test
    fun `full chain — VoLTE-style block during XV SCO_HOT resolves to ALLOW`() {
        // End-to-end: no own Telecom call (grace expired), mode reads
        // IN_COMMUNICATION (XV's SCO_HOT hold), operator presses PTT.
        // Raw gate would BLOCK; the own-SCO override rescues it.
        val rawGate =
            shouldGateForCellularCall(
                cellularCallStateFromAudioMode(
                    audioMode = AudioManager.MODE_IN_COMMUNICATION,
                    xvHasActiveTelecomCall = false,
                ),
                PttSource.ON_SCREEN,
            )
        assertEquals(PttGate.BLOCK_CELLULAR_CALL, rawGate)
        assertEquals(PttGate.ALLOW, resolveGateWithOwnSco(rawGate, xvHoldsSco = true))
    }
}
