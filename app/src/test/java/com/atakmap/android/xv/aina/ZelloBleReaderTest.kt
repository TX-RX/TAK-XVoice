package com.atakmap.android.xv.aina

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Coverage for [ZelloBleReader.decodeZelloMask] — pins the bitmask
 * decoder against Zello's Hardware Partner Technical Integration
 * specification so the button-event mapping is validated ahead of
 * hardware bring-up (the actual Service / Characteristic UUIDs are
 * still `TODO(UUID)` in [ZelloBleReader], but the wire-level mask
 * semantics are stable across Zello-conformant devices).
 */
class ZelloBleReaderTest {
    @Test
    fun `zero mask decodes to nothing held`() {
        assertTrue(ZelloBleReader.decodeZelloMask(0x00).isEmpty())
    }

    @Test
    fun `0x01 decodes to primary PTT`() {
        assertEquals(setOf(AinaButton.PTT), ZelloBleReader.decodeZelloMask(0x01))
    }

    @Test
    fun `0x02 decodes to emergency PTT (SOS analog)`() {
        assertEquals(setOf(AinaButton.PTTE), ZelloBleReader.decodeZelloMask(0x02))
    }

    @Test
    fun `0x04 decodes to secondary PTT`() {
        assertEquals(setOf(AinaButton.PTTS), ZelloBleReader.decodeZelloMask(0x04))
    }

    @Test
    fun `multiple simultaneously held bits combine into a set`() {
        // Primary + secondary pressed at once — e.g. a dual-button
        // Zello puck where the operator squeezes both.
        assertEquals(
            setOf(AinaButton.PTT, AinaButton.PTTS),
            ZelloBleReader.decodeZelloMask(0x01 or 0x04),
        )
    }

    @Test
    fun `channel-switch bits are ignored today`() {
        // 0x08 = Chan Down, 0x10 = Chan Up. Intentionally not mapped
        // today (see ZelloBleReader KDoc); this pins that decision so
        // a future extension is a deliberate choice, not accidental.
        assertTrue(ZelloBleReader.decodeZelloMask(0x08).isEmpty())
        assertTrue(ZelloBleReader.decodeZelloMask(0x10).isEmpty())
        assertTrue(ZelloBleReader.decodeZelloMask(0x08 or 0x10).isEmpty())
    }

    @Test
    fun `channel-switch bits ignored even when combined with PTT`() {
        // Operator holds primary PTT AND presses channel-up. The PTT
        // is honored; the channel-up bit is currently swallowed. When
        // channel-switch is added to the button vocabulary this test
        // will need updating.
        assertEquals(
            setOf(AinaButton.PTT),
            ZelloBleReader.decodeZelloMask(0x01 or 0x10),
        )
    }
}
