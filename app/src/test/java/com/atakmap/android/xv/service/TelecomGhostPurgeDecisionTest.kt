package com.atakmap.android.xv.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-function coverage for the pre-[android.telecom.TelecomManager.placeCall]
 * ghost-purge guard extracted from [XvVoiceService.shouldGhostPurgeBeforePlaceCall].
 *
 * Field bug (issue #66 item #1, ship-blocking): XV's
 * [com.atakmap.android.xv.telecom.ActiveCallRegistry] (a Kotlin object
 * in the plugin service process) can desync from Android Telecom's
 * internal `mCalls` ledger. When it does:
 *  - `ActiveCallRegistry.activeConnection() == null` — our reuse check
 *    in [XvVoiceService.placeTelecomCallInternal] concludes there is no
 *    active call.
 *  - We call [android.telecom.TelecomManager.placeCall].
 *  - Android Telecom sees the new call attempt against the same self-
 *    managed PhoneAccount that ALREADY has a ghost `TC@N` in its
 *    `mCalls` ledger.
 *  - The "Hang up XV to place a new call" system arbitration fires.
 *  - Field operators experience: PTT press → no TX → toast → confusion.
 *
 * Observed 2026-07-11 during TPP validation on Pixel 9 Pro (API 35)
 * and Sonim XP9900 with `dumpsys telecom` showing calls stacked TC@86
 * through TC@95 with the last still ACTIVE 138+ s past the 8 s
 * [XvVoiceService.TELECOM_END_DEBOUNCE_MS] teardown timer.
 *
 * We can not consult Telecom directly ([android.telecom.TelecomManager.isInSelfManagedCall]
 * / [android.telecom.TelecomManager.isInCall] require READ_PHONE_STATE
 * — the abandoned `copilot/fix-telecom-arbitration-deadlock` branch
 * proved this route dead), so the decision function approximates the
 * "we might have a ghost" signal from information XV already owns:
 * (1) no live registry entry AND (2) an own call has been unregistered
 * in this process before. Both conditions must hold; either alone is
 * insufficient.
 *
 * The remediation itself (an unregister → re-register cycle on
 * [com.atakmap.android.xv.telecom.XvPhoneAccount]) is tested only for
 * its side effect on the boolean gate here — the actual
 * TelecomManager interaction requires Robolectric or an instrumented
 * device to observe.
 */
class TelecomGhostPurgeDecisionTest {
    /**
     * Fresh process, no call ever placed. This is the very first PTT
     * press after plugin load — [XvVoiceService.purgeGhostSelfManagedCallsOnFreshProcess]
     * already ran in onCreate to cover any cross-process ghost, so the
     * per-placeCall check MUST short-circuit false and skip the extra
     * roundtrip. Paying it here would tack a few hundred ms of latency
     * onto the operator's very first press for no benefit.
     */
    @Test
    fun `fresh process, no active call, no prior call — do not purge`() {
        val decision =
            XvVoiceService.shouldGhostPurgeBeforePlaceCall(
                hasActiveConnection = false,
                hasHadOwnCallInProcess = false,
            )
        assertFalse("fresh process with no history must not purge on first placeCall", decision)
    }

    /**
     * The exact bug scenario: a call was placed and ended in this
     * process (registry unregistered, `lastOwnCallEndedAtMs` stamped
     * non-zero) and now a fresh placeCall is arriving. Registry says
     * no live connection, but Telecom may still hold a ghost TC@N
     * under our PhoneAccount. This is the case the guard MUST fire on.
     */
    @Test
    fun `no active call but process has ended one before — purge`() {
        val decision =
            XvVoiceService.shouldGhostPurgeBeforePlaceCall(
                hasActiveConnection = false,
                hasHadOwnCallInProcess = true,
            )
        assertTrue(
            "field-bug scenario: registry empty + prior teardown must trigger ghost-purge",
            decision,
        )
    }

    /**
     * A live connection is already in the registry. This is the reuse
     * path — [XvVoiceService.placeTelecomCallInternal] returns early
     * without reaching the ghost-purge check, so the pure decision
     * function must also return false when the caller passes
     * `hasActiveConnection = true`. Belt-and-suspenders against a
     * future caller who forgets the early-return.
     */
    @Test
    fun `active call in registry — never purge`() {
        val withPriorTeardown =
            XvVoiceService.shouldGhostPurgeBeforePlaceCall(
                hasActiveConnection = true,
                hasHadOwnCallInProcess = true,
            )
        assertFalse(
            "active connection is the reuse path; never purge underneath a live call",
            withPriorTeardown,
        )
        val noPriorTeardown =
            XvVoiceService.shouldGhostPurgeBeforePlaceCall(
                hasActiveConnection = true,
                hasHadOwnCallInProcess = false,
            )
        assertFalse(
            "active connection dominates over the prior-teardown flag either way",
            noPriorTeardown,
        )
    }

    /**
     * Documentation invariant: the two inputs are AND-gated, not
     * OR-gated. `hasHadOwnCallInProcess == false` on its own — even
     * with a null active connection — MUST NOT trigger a purge. That
     * corresponds to the fresh-process first-press case above, but is
     * broken out separately as a truth-table pin.
     */
    @Test
    fun `truth table — only both-conditions-true triggers purge`() {
        // (F, F) — fresh process, first press. No purge.
        assertFalse(
            XvVoiceService.shouldGhostPurgeBeforePlaceCall(
                hasActiveConnection = false,
                hasHadOwnCallInProcess = false,
            ),
        )
        // (F, T) — the bug case. Purge.
        assertTrue(
            XvVoiceService.shouldGhostPurgeBeforePlaceCall(
                hasActiveConnection = false,
                hasHadOwnCallInProcess = true,
            ),
        )
        // (T, F) — reuse path with no prior. No purge.
        assertFalse(
            XvVoiceService.shouldGhostPurgeBeforePlaceCall(
                hasActiveConnection = true,
                hasHadOwnCallInProcess = false,
            ),
        )
        // (T, T) — reuse path after a prior. No purge (reuse dominates).
        assertFalse(
            XvVoiceService.shouldGhostPurgeBeforePlaceCall(
                hasActiveConnection = true,
                hasHadOwnCallInProcess = true,
            ),
        )
    }
}
