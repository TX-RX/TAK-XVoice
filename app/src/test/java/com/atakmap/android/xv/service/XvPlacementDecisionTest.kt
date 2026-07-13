package com.atakmap.android.xv.service

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure-function coverage for the Telecom double-place race guard
 * extracted from [XvVoiceService.decidePlacement].
 *
 * Bug: `TelecomManager.placeCall()` is asynchronous — the [XvConnection]
 * only lands in
 * [com.atakmap.android.xv.telecom.ActiveCallRegistry] later, when
 * Telecom calls `onCreateOutgoingConnection`. The old reuse guard only
 * checked `activeConnection() != null`, so a second PTT-down inside that
 * registration gap saw no connection and issued a SECOND placeCall,
 * which Telecom rejects with "there is another call connecting."
 *
 * The fix tracks a synchronous [XvVoiceService] `telecomState`
 * (IDLE / ACTIVE_TX_RX / TAIL_WARM) that flips to active the instant we
 * commit to placing — BEFORE the async placeCall completes. This pure
 * function encodes the resulting decision from two synchronous signals:
 * whether the registry holds a live connection, and whether that state
 * says a call is active/warming.
 */
class XvPlacementDecisionTest {
    /**
     * IDLE lifecycle, no live connection — the only case that warrants a
     * fresh placeCall.
     */
    @Test
    fun `no connection and not warm-or-active — PLACE`() {
        assertEquals(
            XvVoiceService.PlaceDecision.PLACE,
            XvVoiceService.decidePlacement(hasActiveConnection = false, warmOrActive = false),
        )
    }

    /**
     * The race case: telecomState is ACTIVE_TX_RX/TAIL_WARM (our first
     * placeCall committed) but the connection has not registered yet.
     * A second press MUST NOT place again — it reuses the in-flight one.
     */
    @Test
    fun `no connection but warm-or-active — REUSE_IN_FLIGHT (the race fix)`() {
        assertEquals(
            XvVoiceService.PlaceDecision.REUSE_IN_FLIGHT,
            XvVoiceService.decidePlacement(hasActiveConnection = false, warmOrActive = true),
        )
    }

    /**
     * A live connection always wins — placing over a live self-managed
     * call is exactly what Telecom rejects — regardless of the state flag.
     */
    @Test
    fun `live connection always reuses, regardless of state flag`() {
        assertEquals(
            XvVoiceService.PlaceDecision.REUSE_ACTIVE,
            XvVoiceService.decidePlacement(hasActiveConnection = true, warmOrActive = true),
        )
        assertEquals(
            "a registered connection with a stale-IDLE flag must still reuse, never place",
            XvVoiceService.PlaceDecision.REUSE_ACTIVE,
            XvVoiceService.decidePlacement(hasActiveConnection = true, warmOrActive = false),
        )
    }

    /**
     * Full truth table pin so a future refactor can't silently let the
     * "no connection but warming" case fall through to PLACE (which is
     * the collision).
     */
    @Test
    fun `truth table`() {
        assertEquals(
            XvVoiceService.PlaceDecision.PLACE,
            XvVoiceService.decidePlacement(hasActiveConnection = false, warmOrActive = false),
        )
        assertEquals(
            XvVoiceService.PlaceDecision.REUSE_IN_FLIGHT,
            XvVoiceService.decidePlacement(hasActiveConnection = false, warmOrActive = true),
        )
        assertEquals(
            XvVoiceService.PlaceDecision.REUSE_ACTIVE,
            XvVoiceService.decidePlacement(hasActiveConnection = true, warmOrActive = false),
        )
        assertEquals(
            XvVoiceService.PlaceDecision.REUSE_ACTIVE,
            XvVoiceService.decidePlacement(hasActiveConnection = true, warmOrActive = true),
        )
    }
}
