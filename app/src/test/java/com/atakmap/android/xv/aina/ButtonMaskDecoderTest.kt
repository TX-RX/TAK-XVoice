package com.atakmap.android.xv.aina

import org.junit.Assert.assertEquals
import org.junit.Test

class ButtonMaskDecoderTest {
    @Test
    fun `idle to PTT down emits a single down edge`() {
        val d = ButtonMaskDecoder()
        val edges = d.process(AinaButton.PTT.bitMask)
        assertEquals(listOf(ButtonMaskDecoder.ButtonEdge(AinaButton.PTT, true)), edges)
    }

    @Test
    fun `repeated identical mask emits no edges`() {
        val d = ButtonMaskDecoder()
        d.process(AinaButton.PTT.bitMask)
        // Polled the same mask again — the operator is still holding,
        // not pressing twice. Don't double-fire TX or chunk through
        // the dispatcher's edge logic spuriously.
        val edges = d.process(AinaButton.PTT.bitMask)
        assertEquals(emptyList<ButtonMaskDecoder.ButtonEdge>(), edges)
    }

    @Test
    fun `release after hold emits a single up edge`() {
        val d = ButtonMaskDecoder()
        d.process(AinaButton.PTT.bitMask)
        val edges = d.process(0)
        assertEquals(listOf(ButtonMaskDecoder.ButtonEdge(AinaButton.PTT, false)), edges)
    }

    @Test
    fun `simultaneous PTT and PTTE press emits both down edges`() {
        val d = ButtonMaskDecoder()
        val edges = d.process(AinaButton.PTT.bitMask or AinaButton.PTTE.bitMask)
        // Two-channel speakermics emit a combined mask; the decoder
        // should fan that into one event per pressed button so
        // PttDispatcher sees the slot-1 trigger as a real press, not
        // a piggyback on slot 0.
        val expected =
            setOf(
                ButtonMaskDecoder.ButtonEdge(AinaButton.PTT, true),
                ButtonMaskDecoder.ButtonEdge(AinaButton.PTTE, true),
            )
        assertEquals(expected, edges.toSet())
    }

    @Test
    fun `transition from one button to another fires the right pair`() {
        val d = ButtonMaskDecoder()
        d.process(AinaButton.PTT.bitMask)
        // Swap to PTTE — operator released PTT and pressed PTTE so
        // close together that the device coalesced both into one
        // notification frame. Should fire one up + one down.
        val edges = d.process(AinaButton.PTTE.bitMask)
        val expected =
            setOf(
                ButtonMaskDecoder.ButtonEdge(AinaButton.PTT, false),
                ButtonMaskDecoder.ButtonEdge(AinaButton.PTTE, true),
            )
        assertEquals(expected, edges.toSet())
    }

    @Test
    fun `heartbeat bit is ignored — does not synthesize a button edge`() {
        val d = ButtonMaskDecoder()
        // V2 firmware periodically sets the heartbeat bit to prove the
        // notification pipe is alive even when no buttons changed.
        // Without masking, every heartbeat would manifest as a
        // ghost-button down/up cycle.
        val edges = d.process(AinaButton.HEARTBEAT_MASK)
        assertEquals(emptyList<ButtonMaskDecoder.ButtonEdge>(), edges)
    }

    @Test
    fun `heartbeat overlay on a held button does not retrigger the held edge`() {
        val d = ButtonMaskDecoder()
        d.process(AinaButton.PTT.bitMask)
        val edges = d.process(AinaButton.PTT.bitMask or AinaButton.HEARTBEAT_MASK)
        // PTT is still down + heartbeat ticked. Decoder must mask off
        // the heartbeat before comparing, otherwise it'd see a "new
        // mask" and re-fire PTT down.
        assertEquals(emptyList<ButtonMaskDecoder.ButtonEdge>(), edges)
    }

    @Test
    fun `reset clears state so a held button is forgotten`() {
        val d = ButtonMaskDecoder()
        d.process(AinaButton.PTT.bitMask)
        d.reset()
        // After reset (used by AinaBleReader on disconnect), the next
        // PTT mask should re-fire as a fresh down rather than being
        // suppressed as "still held."
        val edges = d.process(AinaButton.PTT.bitMask)
        assertEquals(listOf(ButtonMaskDecoder.ButtonEdge(AinaButton.PTT, true)), edges)
    }
}
