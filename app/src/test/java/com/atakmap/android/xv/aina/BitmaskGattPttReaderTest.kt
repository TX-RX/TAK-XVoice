package com.atakmap.android.xv.aina

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Coverage for [BitmaskGattPttReader.diffEdges] (the base's edge-
 * triggered press/release derivation) and [PrymeBleReader]'s mask
 * decoder. Exercised as pure functions so no Android context / GATT
 * stack is needed.
 */
class BitmaskGattPttReaderTest {
    // ============================================================
    // Edge diff — the base's press/release derivation
    // ============================================================

    @Test
    fun `diffEdges returns null when nothing changes`() {
        assertNull(BitmaskGattPttReader.diffEdges(emptySet(), emptySet()))
        assertNull(
            BitmaskGattPttReader.diffEdges(
                setOf(AinaButton.PTT),
                setOf(AinaButton.PTT),
            ),
        )
    }

    @Test
    fun `diffEdges reports a down event for a newly pressed button`() {
        val edges = BitmaskGattPttReader.diffEdges(emptySet(), setOf(AinaButton.PTT))
        assertEquals(listOf(AinaButton.PTT), edges?.down)
        assertTrue("no releases when going from nothing to something", edges?.up?.isEmpty() == true)
    }

    @Test
    fun `diffEdges reports an up event for a released button`() {
        val edges = BitmaskGattPttReader.diffEdges(setOf(AinaButton.PTT), emptySet())
        assertTrue("no presses when going from something to nothing", edges?.down?.isEmpty() == true)
        assertEquals(listOf(AinaButton.PTT), edges?.up)
    }

    @Test
    fun `diffEdges reports both edges when the held set rotates`() {
        // Ex: operator releases PTT and immediately presses secondary
        val edges =
            BitmaskGattPttReader.diffEdges(
                setOf(AinaButton.PTT),
                setOf(AinaButton.PTTS),
            )
        assertEquals(listOf(AinaButton.PTTS), edges?.down)
        assertEquals(listOf(AinaButton.PTT), edges?.up)
    }

    @Test
    fun `diffEdges handles multi-button transitions independently`() {
        // Operator was holding PTT; now holds PTT + SOS. Only SOS
        // reports as newly-pressed; PTT stays held so nothing about
        // it is emitted.
        val edges =
            BitmaskGattPttReader.diffEdges(
                setOf(AinaButton.PTT),
                setOf(AinaButton.PTT, AinaButton.PTTE),
            )
        assertEquals(listOf(AinaButton.PTTE), edges?.down)
        assertTrue(edges?.up?.isEmpty() == true)
    }

    // ============================================================
    // Pryme decoder — "any non-zero = PTT" heuristic
    // ============================================================

    @Test
    fun `Pryme decoder maps zero mask to nothing held`() {
        assertTrue(PrymeBleReader.decodePrymeMask(0x00).isEmpty())
    }

    @Test
    fun `Pryme decoder maps any non-zero mask to PTT held`() {
        // Every observed value we've seen from field units is caught
        // by the "non-zero = PTT" heuristic. This locks that behavior
        // in so the refactor cannot silently narrow it.
        for (m in 0x01..0xFF) {
            assertEquals(
                "mask=0x${m.toString(16)} should map to PTT held",
                setOf(AinaButton.PTT),
                PrymeBleReader.decodePrymeMask(m),
            )
        }
    }
}
