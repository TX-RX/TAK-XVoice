package com.atakmap.android.xv.aina

import com.atakmap.android.xv.aina.PttReaderRespawnDecision.Decision
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Coverage for the External Button reader-lifecycle decision matrix.
 * Mirror of [AinaReaderKindResolverTest] but with MAC-based inputs
 * because the External Button slot's kind is auto-derived at connect
 * time rather than operator-configurable.
 *
 * Pins the field-bug fix from 2026-07-11 (Pixel 9 Pro, Pryme
 * BT-PTT-Z puck): the picker showed "Connected" but button presses
 * did not reach XvVoicePlant until the operator toggled the picker
 * to "(none)" and re-selected the puck. Same class as PR #35's
 * primary-AINA config-toggle bug — mirroring the fix here.
 *
 * Uses placeholder MACs per CLAUDE.md sensitive-content policy.
 */
class ExternalButtonReaderResolverTest {
    private companion object {
        const val PUCK_A = "AA:BB:CC:DD:EE:FF"
        const val PUCK_B = "11:22:33:44:55:66"
        const val PUCK_A_LOWER = "aa:bb:cc:dd:ee:ff"
    }

    // ---- Not-connected: every change is persist-only ----

    @Test
    fun `not connected — MAC change is a no-op`() {
        assertEquals(
            "picking a new MAC while the slot is idle is persist-only",
            Decision.NO_OP,
            ExternalButtonReaderResolver.shouldRespawnReader(
                currentMac = null,
                newMac = PUCK_A,
                isConnected = false,
            ),
        )
    }

    @Test
    fun `not connected — MAC clear is a no-op`() {
        assertEquals(
            Decision.NO_OP,
            ExternalButtonReaderResolver.shouldRespawnReader(
                currentMac = PUCK_A,
                newMac = null,
                isConnected = false,
            ),
        )
    }

    @Test
    fun `not connected — same MAC pick is a no-op`() {
        assertEquals(
            Decision.NO_OP,
            ExternalButtonReaderResolver.shouldRespawnReader(
                currentMac = PUCK_A,
                newMac = PUCK_A,
                isConnected = false,
            ),
        )
    }

    // ---- Connected + no-op paths ----

    @Test
    fun `connected + same MAC same kind is a no-op (idempotent under rapid re-pick)`() {
        // The 2026-07-11 field bug's happy-path variant: operator
        // re-picks the currently-attached puck. Idempotent — no
        // churn, no reader flap mid-transmission.
        assertEquals(
            Decision.NO_OP,
            ExternalButtonReaderResolver.shouldRespawnReader(
                currentMac = PUCK_A,
                newMac = PUCK_A,
                isConnected = true,
            ),
        )
    }

    @Test
    fun `connected + same MAC case-insensitive is a no-op`() {
        // BluetoothAdapter.getRemoteDevice normalises to uppercase;
        // the picker can hand back either case. The decision has to
        // treat them as equivalent so a case-only "change" doesn't
        // trigger a spurious respawn.
        assertEquals(
            Decision.NO_OP,
            ExternalButtonReaderResolver.shouldRespawnReader(
                currentMac = PUCK_A,
                newMac = PUCK_A_LOWER,
                isConnected = true,
            ),
        )
    }

    @Test
    fun `connected + blank MAC on both sides is a no-op`() {
        // Rare: operator re-picks (none) when already on (none).
        // No reader running, no change to make.
        assertEquals(
            Decision.NO_OP,
            ExternalButtonReaderResolver.shouldRespawnReader(
                currentMac = null,
                newMac = null,
                isConnected = true,
            ),
        )
        assertEquals(
            Decision.NO_OP,
            ExternalButtonReaderResolver.shouldRespawnReader(
                currentMac = "",
                newMac = null,
                isConnected = true,
            ),
        )
        assertEquals(
            Decision.NO_OP,
            ExternalButtonReaderResolver.shouldRespawnReader(
                currentMac = null,
                newMac = "  ",
                isConnected = true,
            ),
        )
    }

    // ---- Connected + TEARDOWN_ONLY ----

    @Test
    fun `connected + MAC cleared to null tears down`() {
        // Operator picked (none) on a live external button. The
        // existing workaround path — before this fix, the ONLY way
        // to re-attach a stuck reader.
        assertEquals(
            Decision.TEARDOWN_ONLY,
            ExternalButtonReaderResolver.shouldRespawnReader(
                currentMac = PUCK_A,
                newMac = null,
                isConnected = true,
            ),
        )
    }

    @Test
    fun `connected + MAC cleared to blank tears down`() {
        assertEquals(
            Decision.TEARDOWN_ONLY,
            ExternalButtonReaderResolver.shouldRespawnReader(
                currentMac = PUCK_A,
                newMac = "",
                isConnected = true,
            ),
        )
    }

    // ---- Connected + RESPAWN ----

    @Test
    fun `connected + MAC swap respawns`() {
        // Operator swapped from Puck A to Puck B on a live slot.
        // Full teardown + reconnect so the new device gets its
        // classification / connect flow re-run under the new MAC.
        assertEquals(
            Decision.RESPAWN,
            ExternalButtonReaderResolver.shouldRespawnReader(
                currentMac = PUCK_A,
                newMac = PUCK_B,
                isConnected = true,
            ),
        )
    }

    @Test
    fun `connected + null to MAC respawns`() {
        // Operator was on (none) with the slot still marked connected
        // (rare — probably a stale flag, but the resolver handles
        // it: since new mac is real, we need to attach a reader).
        assertEquals(
            Decision.RESPAWN,
            ExternalButtonReaderResolver.shouldRespawnReader(
                currentMac = null,
                newMac = PUCK_A,
                isConnected = true,
            ),
        )
    }

    @Test
    fun `connected + blank to MAC respawns`() {
        assertEquals(
            Decision.RESPAWN,
            ExternalButtonReaderResolver.shouldRespawnReader(
                currentMac = "",
                newMac = PUCK_A,
                isConnected = true,
            ),
        )
    }
}
