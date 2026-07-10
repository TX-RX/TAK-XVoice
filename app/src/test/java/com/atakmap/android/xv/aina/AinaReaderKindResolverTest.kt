package com.atakmap.android.xv.aina

import com.atakmap.android.xv.aina.AinaDeviceInfo.ButtonProtocol
import com.atakmap.android.xv.aina.AinaReaderKindResolver.Decision
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Coverage for the reader-lifecycle decision matrix used when the
 * operator flips button-protocol on the currently-selected primary
 * AINA. Pins the semantic contract that XvMapComponent's
 * setAinaButtonProtocolInternal relies on:
 *
 *  - a persist-only change (device not connected) is always a NO_OP;
 *  - "no reader → no reader" (AUDIO_ONLY / UNKNOWN / null in any
 *    combination) is a NO_OP even when connected;
 *  - live-reader → same-live-reader is a NO_OP (idempotent under
 *    rapid A → B → A toggles);
 *  - live-reader → "no reader" tears the reader down but leaves the
 *    audio route intact;
 *  - any transition into a real reader kind (SPP / BLE / BLE_HID) is
 *    a RESPAWN so the existing connect path re-runs the SDP probe,
 *    retry logic, and callback wiring.
 */
class AinaReaderKindResolverTest {
    // ---- Not-connected: every change is persist-only ----

    @Test
    fun `not connected — any change is a no-op`() {
        // Persist-only writes happen regardless; the reader decision
        // is "there is no reader to touch."
        for (current in nullablePlus(ButtonProtocol.entries)) {
            for (new in nullablePlus(ButtonProtocol.entries)) {
                assertEquals(
                    "not connected: current=$current new=$new should be NO_OP",
                    Decision.NO_OP,
                    AinaReaderKindResolver.shouldRespawnReader(current, new, isConnected = false),
                )
            }
        }
    }

    // ---- Connected + same kind ----

    @Test
    fun `connected + same kind is a no-op (idempotent under rapid toggle)`() {
        for (kind in ButtonProtocol.entries) {
            assertEquals(
                "identical kind $kind should not churn the reader",
                Decision.NO_OP,
                AinaReaderKindResolver.shouldRespawnReader(kind, kind, isConnected = true),
            )
        }
        // null → null too — no reader on either side.
        assertEquals(
            Decision.NO_OP,
            AinaReaderKindResolver.shouldRespawnReader(null, null, isConnected = true),
        )
    }

    // ---- Real reader → real reader → RESPAWN ----

    @Test
    fun `connected + SPP to BLE respawns`() {
        assertEquals(
            Decision.RESPAWN,
            AinaReaderKindResolver.shouldRespawnReader(
                currentKind = ButtonProtocol.SPP,
                newKind = ButtonProtocol.BLE,
                isConnected = true,
            ),
        )
    }

    @Test
    fun `connected + BLE to SPP respawns`() {
        assertEquals(
            Decision.RESPAWN,
            AinaReaderKindResolver.shouldRespawnReader(
                currentKind = ButtonProtocol.BLE,
                newKind = ButtonProtocol.SPP,
                isConnected = true,
            ),
        )
    }

    @Test
    fun `connected + BLE to BLE_HID respawns`() {
        assertEquals(
            Decision.RESPAWN,
            AinaReaderKindResolver.shouldRespawnReader(
                currentKind = ButtonProtocol.BLE,
                newKind = ButtonProtocol.BLE_HID,
                isConnected = true,
            ),
        )
    }

    // ---- Live reader → no reader → TEARDOWN_ONLY ----

    @Test
    fun `connected + BLE to null teardowns reader keeping audio`() {
        assertEquals(
            "operator kept the speakermic paired but flipped to on-screen PTT — keep audio",
            Decision.TEARDOWN_ONLY,
            AinaReaderKindResolver.shouldRespawnReader(
                currentKind = ButtonProtocol.BLE,
                newKind = null,
                isConnected = true,
            ),
        )
    }

    @Test
    fun `connected + SPP to AUDIO_ONLY teardowns reader`() {
        assertEquals(
            Decision.TEARDOWN_ONLY,
            AinaReaderKindResolver.shouldRespawnReader(
                currentKind = ButtonProtocol.SPP,
                newKind = ButtonProtocol.AUDIO_ONLY,
                isConnected = true,
            ),
        )
    }

    @Test
    fun `connected + BLE_HID to UNKNOWN teardowns reader`() {
        assertEquals(
            Decision.TEARDOWN_ONLY,
            AinaReaderKindResolver.shouldRespawnReader(
                currentKind = ButtonProtocol.BLE_HID,
                newKind = ButtonProtocol.UNKNOWN,
                isConnected = true,
            ),
        )
    }

    // ---- No reader → real reader → RESPAWN ----
    // The kind-null-on-current branch models "operator was on
    // AUDIO_ONLY and just picked SPP" — we need a fresh connectAina
    // to spin the reader up.

    @Test
    fun `connected + null to SPP respawns`() {
        assertEquals(
            Decision.RESPAWN,
            AinaReaderKindResolver.shouldRespawnReader(
                currentKind = null,
                newKind = ButtonProtocol.SPP,
                isConnected = true,
            ),
        )
    }

    @Test
    fun `connected + AUDIO_ONLY to BLE respawns`() {
        assertEquals(
            Decision.RESPAWN,
            AinaReaderKindResolver.shouldRespawnReader(
                currentKind = ButtonProtocol.AUDIO_ONLY,
                newKind = ButtonProtocol.BLE,
                isConnected = true,
            ),
        )
    }

    @Test
    fun `connected + UNKNOWN to BLE_HID respawns`() {
        assertEquals(
            Decision.RESPAWN,
            AinaReaderKindResolver.shouldRespawnReader(
                currentKind = ButtonProtocol.UNKNOWN,
                newKind = ButtonProtocol.BLE_HID,
                isConnected = true,
            ),
        )
    }

    // ---- "No reader" ↔ "no reader" → NO_OP even when connected ----

    @Test
    fun `connected + AUDIO_ONLY to UNKNOWN is a no-op (both mean no reader)`() {
        assertEquals(
            Decision.NO_OP,
            AinaReaderKindResolver.shouldRespawnReader(
                currentKind = ButtonProtocol.AUDIO_ONLY,
                newKind = ButtonProtocol.UNKNOWN,
                isConnected = true,
            ),
        )
    }

    @Test
    fun `connected + UNKNOWN to null is a no-op (both mean no reader)`() {
        assertEquals(
            Decision.NO_OP,
            AinaReaderKindResolver.shouldRespawnReader(
                currentKind = ButtonProtocol.UNKNOWN,
                newKind = null,
                isConnected = true,
            ),
        )
    }

    @Test
    fun `connected + null to AUDIO_ONLY is a no-op (both mean no reader)`() {
        assertEquals(
            Decision.NO_OP,
            AinaReaderKindResolver.shouldRespawnReader(
                currentKind = null,
                newKind = ButtonProtocol.AUDIO_ONLY,
                isConnected = true,
            ),
        )
    }

    private fun nullablePlus(entries: List<ButtonProtocol>): List<ButtonProtocol?> =
        entries.toMutableList<ButtonProtocol?>().apply { add(null) }
}
