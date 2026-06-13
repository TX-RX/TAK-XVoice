package com.atakmap.android.xv.aina

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pin the bit-mask values for AinaButton. The masks are the wire-format
 * decode contract for AINA V2 BLE button notifications: a regression in
 * even one value would silently flip which physical button maps to which
 * action — a field bug with no log signature.
 */
class AinaButtonTest {
    @Test
    fun `bit masks pinned to canonical hex values`() {
        assertEquals(0x01, AinaButton.PTT.bitMask)
        assertEquals(0x02, AinaButton.PTTE.bitMask)
        assertEquals(0x04, AinaButton.PTTS.bitMask)
        assertEquals(0x08, AinaButton.PTTB1.bitMask)
        assertEquals(0x10, AinaButton.PTTB2.bitMask)
        assertEquals(0x20, AinaButton.MFB.bitMask)
    }

    @Test
    fun `all bit masks are distinct (no two buttons share a bit)`() {
        val masks = AinaButton.entries.map { it.bitMask }
        assertEquals(
            "duplicate bit masks would make button events ambiguous",
            masks.size,
            masks.toSet().size,
        )
    }

    @Test
    fun `every bit mask is a single set bit (power of two)`() {
        // The bitmask decoder OR-combines masks for multi-press; each
        // individual button must occupy exactly one bit so the decode
        // is unambiguous.
        for (b in AinaButton.entries) {
            assertEquals(
                "${b.name} mask 0x${"%X".format(b.bitMask)} should have exactly one bit set",
                1,
                Integer.bitCount(b.bitMask),
            )
        }
    }

    @Test
    fun `HEARTBEAT_MASK does not overlap any button mask`() {
        // V2 firmware sets the heartbeat bit periodically on every
        // notification. If it overlapped a button bit, decoders would
        // see a phantom press on every heartbeat.
        val anyButton = AinaButton.entries.fold(0) { acc, b -> acc or b.bitMask }
        assertEquals(
            "HEARTBEAT_MASK must be disjoint from ALL button masks",
            0,
            AinaButton.HEARTBEAT_MASK and anyButton,
        )
    }

    @Test
    fun `ALL_BUTTON_MASK is the union of all button bits`() {
        val expected = AinaButton.entries.fold(0) { acc, b -> acc or b.bitMask }
        assertEquals(
            "ALL_BUTTON_MASK must equal the OR of every button.bitMask",
            expected,
            AinaButton.ALL_BUTTON_MASK,
        )
    }

    @Test
    fun `ALL_BUTTON_MASK is 0x3F (covers the 6 buttons)`() {
        // Pinned to the documented value.
        assertEquals(0x3F, AinaButton.ALL_BUTTON_MASK)
    }
}
