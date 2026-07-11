package com.atakmap.android.xv.aina

import com.atakmap.android.xv.aina.PttReaderRespawnDecision.Decision
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Coverage for the slot-agnostic decision matrix shared between
 * [AinaReaderKindResolver] (primary AINA button-protocol slot) and
 * [ExternalButtonReaderResolver] (External Button MAC slot). Both
 * slots collapse their domain input (enum vs. MAC) to the same
 * `(currentHasReader, newHasReader, currentEqualsNew)` triple and
 * feed it here, so this test pins the truth table both specialisations
 * inherit from.
 *
 * Same semantic contract as the old
 * [AinaReaderKindResolverTest] — see that file for the
 * enum-specialised cases the primary AINA path exercises.
 */
class PttReaderRespawnDecisionTest {
    // ---- Not-connected: every change is persist-only ----

    @Test
    fun `not connected — all inputs collapse to NO_OP`() {
        // Persist-only writes happen at the caller regardless; the
        // reader decision is "there is no reader to touch."
        for (currentHasReader in listOf(false, true)) {
            for (newHasReader in listOf(false, true)) {
                for (currentEqualsNew in listOf(false, true)) {
                    assertEquals(
                        "not connected: current=$currentHasReader new=$newHasReader " +
                            "eq=$currentEqualsNew should be NO_OP",
                        Decision.NO_OP,
                        PttReaderRespawnDecision.decide(
                            currentHasReader = currentHasReader,
                            newHasReader = newHasReader,
                            currentEqualsNew = currentEqualsNew,
                            isConnected = false,
                        ),
                    )
                }
            }
        }
    }

    // ---- Connected, both "no reader" ----

    @Test
    fun `connected + no reader on either side is NO_OP regardless of equality`() {
        // Both AinaReaderKindResolver's "AUDIO_ONLY → UNKNOWN" branch
        // and ExternalButtonReaderResolver's "(none) → (none)" branch
        // land here.
        assertEquals(
            Decision.NO_OP,
            PttReaderRespawnDecision.decide(
                currentHasReader = false,
                newHasReader = false,
                currentEqualsNew = false,
                isConnected = true,
            ),
        )
        assertEquals(
            Decision.NO_OP,
            PttReaderRespawnDecision.decide(
                currentHasReader = false,
                newHasReader = false,
                currentEqualsNew = true,
                isConnected = true,
            ),
        )
    }

    // ---- Connected, same live config ----

    @Test
    fun `connected + identical live config is NO_OP (idempotent under rapid A → A toggle)`() {
        // Rapid picker double-tap or programmatic re-pick of the
        // currently-attached MAC / kind. Idempotent so the reader
        // doesn't churn mid-transmission.
        assertEquals(
            Decision.NO_OP,
            PttReaderRespawnDecision.decide(
                currentHasReader = true,
                newHasReader = true,
                currentEqualsNew = true,
                isConnected = true,
            ),
        )
    }

    // ---- Connected, live → no reader → TEARDOWN_ONLY ----

    @Test
    fun `connected + live to no reader tears down`() {
        // Operator flipped from a real reader to (none) / AUDIO_ONLY.
        assertEquals(
            Decision.TEARDOWN_ONLY,
            PttReaderRespawnDecision.decide(
                currentHasReader = true,
                newHasReader = false,
                currentEqualsNew = false,
                isConnected = true,
            ),
        )
    }

    // ---- Connected, no reader → live → RESPAWN ----

    @Test
    fun `connected + no reader to live respawns`() {
        // Operator was on (none) / AUDIO_ONLY and just picked a real
        // reader on a slot that's already "connected" (audio route
        // pinned but reader not attached).
        assertEquals(
            Decision.RESPAWN,
            PttReaderRespawnDecision.decide(
                currentHasReader = false,
                newHasReader = true,
                currentEqualsNew = false,
                isConnected = true,
            ),
        )
    }

    // ---- Connected, live → different live → RESPAWN ----

    @Test
    fun `connected + live to different live respawns`() {
        // Operator swapped MAC / flipped kind on an already-attached
        // reader — need the full connect path to re-run.
        assertEquals(
            Decision.RESPAWN,
            PttReaderRespawnDecision.decide(
                currentHasReader = true,
                newHasReader = true,
                currentEqualsNew = false,
                isConnected = true,
            ),
        )
    }
}
