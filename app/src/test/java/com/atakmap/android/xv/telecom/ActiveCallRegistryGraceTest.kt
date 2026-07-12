package com.atakmap.android.xv.telecom

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Coverage for the post-teardown grace window on [ActiveCallRegistry].
 * The grace exists to paper over a race between XV's self-managed
 * Telecom call unregistering (synchronous, drops
 * [ActiveCallRegistry.hasActiveCall] immediately) and the Android
 * audio HAL winding `AudioManager.getMode()` back from
 * `MODE_IN_COMMUNICATION` to `MODE_NORMAL` (asynchronous, ~1.4 s
 * observed on Pixel 9 Pro / API 35 on 2026-07-11 14:47). Without
 * this grace the cellular gate saw a stale IN_COMMUNICATION + no
 * own call and blocked legitimate operator PTT presses with a
 * spurious "Cellular call active" toast.
 *
 * The tests here drive [ActiveCallRegistry.withinRecentCallGrace]
 * directly via the [ActiveCallRegistry.setLastOwnCallEndedAtMsForTest]
 * hook so the boundary cases are hermetic — no need to instantiate a
 * real [android.telecom.Connection] and route it through
 * [ActiveCallRegistry.unregister] (which would require Robolectric).
 */
class ActiveCallRegistryGraceTest {
    @Before
    fun resetRegistry() {
        // Registry is a process-wide singleton (Kotlin object). Clear
        // the timestamp between tests so ordering is irrelevant.
        ActiveCallRegistry.setLastOwnCallEndedAtMsForTest(0L)
    }

    @After
    fun cleanUpRegistry() {
        ActiveCallRegistry.setLastOwnCallEndedAtMsForTest(0L)
    }

    // ============================================================
    // withinRecentCallGrace — pure decision function
    // ============================================================

    @Test
    fun `never had an own call — grace always false`() {
        // Sentinel-zero state (fresh process, or explicit reset).
        // The grace must not fire without a prior teardown to base
        // it on; otherwise it would hide the block-all-sources
        // policy for external cellular calls arriving before XV
        // has ever placed its own call.
        assertFalse(ActiveCallRegistry.withinRecentCallGrace(nowMs = 0L, graceMs = 3_000L))
        assertFalse(ActiveCallRegistry.withinRecentCallGrace(nowMs = 1_000L, graceMs = 3_000L))
        assertFalse(ActiveCallRegistry.withinRecentCallGrace(nowMs = Long.MAX_VALUE, graceMs = 3_000L))
    }

    @Test
    fun `never had an own call — grace false for any graceMs value`() {
        // Belt-and-suspenders: the "no prior teardown" short-circuit
        // must dominate over an unusual [graceMs] argument. A caller
        // passing an absurd grace should not accidentally unlock the
        // gate when there is no basis for the grace.
        assertFalse(ActiveCallRegistry.withinRecentCallGrace(nowMs = 500L, graceMs = 0L))
        assertFalse(ActiveCallRegistry.withinRecentCallGrace(nowMs = 500L, graceMs = Long.MAX_VALUE))
    }

    @Test
    fun `just torn down — grace true within window`() {
        // 500 ms after teardown, still well inside the 3 s window —
        // this is the exact scenario from the 2026-07-11 14:47 field
        // trace where AudioManager.getMode() lagged ~1.4 s.
        ActiveCallRegistry.setLastOwnCallEndedAtMsForTest(1_000L)
        assertTrue(
            ActiveCallRegistry.withinRecentCallGrace(nowMs = 1_500L, graceMs = 3_000L),
        )
    }

    @Test
    fun `past grace boundary — grace false`() {
        // 3500 ms after teardown, past the 3 s window. The
        // AudioManager mode has certainly caught up by now; a
        // MODE_IN_COMMUNICATION reading at this point is somebody
        // ELSE's call and must be blocked.
        ActiveCallRegistry.setLastOwnCallEndedAtMsForTest(1_000L)
        assertFalse(
            ActiveCallRegistry.withinRecentCallGrace(nowMs = 4_500L, graceMs = 3_000L),
        )
    }

    @Test
    fun `exactly at teardown instant — grace true`() {
        // Zero-elapsed sample. Not a physically possible ordering in
        // production (the caller reads nowMs strictly after
        // unregister stamps the field) but the boundary matters —
        // if a caller ever passes a nowMs equal to the stamp, we
        // must treat it as within.
        ActiveCallRegistry.setLastOwnCallEndedAtMsForTest(1_000L)
        assertTrue(
            ActiveCallRegistry.withinRecentCallGrace(nowMs = 1_000L, graceMs = 3_000L),
        )
    }

    @Test
    fun `exactly at grace boundary — grace true`() {
        // Inclusive boundary: elapsed == graceMs must return true.
        // Choosing exclusive at this edge would introduce a
        // one-millisecond flaky window; inclusive is unambiguous
        // and matches the fail-safe direction (favor "still ours").
        ActiveCallRegistry.setLastOwnCallEndedAtMsForTest(1_000L)
        assertTrue(
            ActiveCallRegistry.withinRecentCallGrace(nowMs = 4_000L, graceMs = 3_000L),
        )
    }

    @Test
    fun `nowMs before teardown — grace true (thread-race fail-safe)`() {
        // Negative elapsed can happen if the caller samples nowMs
        // from a stale local variable before the unregister
        // stamp landed on the volatile field. We know a teardown
        // occurred (endedAt is non-zero); treat as within so the
        // race resolves as "still ours" — the same direction the
        // whole grace exists to protect.
        ActiveCallRegistry.setLastOwnCallEndedAtMsForTest(2_000L)
        assertTrue(
            ActiveCallRegistry.withinRecentCallGrace(nowMs = 1_000L, graceMs = 3_000L),
        )
    }

    @Test
    fun `re-registered after grace expired — grace still orthogonal`() {
        // When a fresh own call comes up after the grace expired,
        // hasActiveCall becomes true again. The grace field only
        // updates on unregister — it does NOT reset on register —
        // but that is fine, because the [hasActiveCall] check in
        // the provider already dominates the OR before
        // withinRecentCallGrace is even consulted. The invariant
        // this test locks: the grace reader is a pure function of
        // (nowMs, graceMs, lastOwnCallEndedAtMs) — it does not
        // consult activeConnection state, so a re-register does
        // not alter its output.
        ActiveCallRegistry.setLastOwnCallEndedAtMsForTest(1_000L)
        // 4500 ms > 3000 ms grace → false regardless of any
        // register/unregister motion elsewhere.
        assertFalse(
            ActiveCallRegistry.withinRecentCallGrace(nowMs = 4_500L, graceMs = 3_000L),
        )
    }

    // ============================================================
    // RECENT_OWN_CALL_GRACE_MS — constant contract
    // ============================================================

    @Test
    fun `grace constant is 3 seconds`() {
        // Sized on the 2026-07-11 field observation: Pixel 9 Pro /
        // API 35 held MODE_IN_COMMUNICATION for ~1.4 s after XV's
        // Telecom teardown. 3000 ms gives ~2x headroom for slower
        // devices while staying well under any operator-noticeable
        // "why is this still blocked?" threshold.
        assertEquals(3_000L, ActiveCallRegistry.RECENT_OWN_CALL_GRACE_MS)
    }

    // ============================================================
    // hasHadOwnCallInProcess — signal for the ghost-purge guard
    // ============================================================

    @Test
    fun `hasHadOwnCallInProcess is false on fresh sentinel-zero state`() {
        // The @Before hook resets the timestamp to zero — matching the
        // state of the singleton on a fresh service-process load
        // (Kotlin object initializes to zero). The
        // XvVoiceService.shouldGhostPurgeBeforePlaceCall guard relies
        // on this reader to distinguish "first PTT ever" from "we've
        // already placed and ended a call" — the first case must NOT
        // pay the extra Telecom roundtrip.
        assertFalse(ActiveCallRegistry.hasHadOwnCallInProcess())
    }

    @Test
    fun `hasHadOwnCallInProcess is true after a teardown timestamp is stamped`() {
        // Any non-zero stamp counts — the exact value is irrelevant to
        // the boolean signal. Ordering: this is what
        // ActiveCallRegistry.unregister() does synchronously the
        // moment the last own call teardown lands (via
        // SystemClock.elapsedRealtime()).
        ActiveCallRegistry.setLastOwnCallEndedAtMsForTest(1L)
        assertTrue(ActiveCallRegistry.hasHadOwnCallInProcess())
    }

    @Test
    fun `hasHadOwnCallInProcess flips back to false only via explicit reset`() {
        // The stamp is not zeroed on re-register — a subsequent call
        // going ACTIVE does not clear the "has had one before" flag.
        // The ghost-purge guard depends on the flag NOT resetting when
        // a fresh call comes up; otherwise a rapid PTT cycle could see
        // the signal drop and lose its protection between calls.
        ActiveCallRegistry.setLastOwnCallEndedAtMsForTest(2_000L)
        assertTrue(ActiveCallRegistry.hasHadOwnCallInProcess())
        // The only way back to false is an explicit test reset (or a
        // fresh process, which reinitializes the object).
        ActiveCallRegistry.setLastOwnCallEndedAtMsForTest(0L)
        assertFalse(ActiveCallRegistry.hasHadOwnCallInProcess())
    }
}
