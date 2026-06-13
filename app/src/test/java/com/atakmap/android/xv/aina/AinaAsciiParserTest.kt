package com.atakmap.android.xv.aina

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Wire-format tests for the AINA V1 SPP ASCII button protocol. These
 * frames arrive from BR/EDR SPP without reliable terminators — the V1
 * firmware emits raw `+CMD=P` / `+CMD=R` strings with inconsistent
 * trailing CR/LF — so the parser leans on three delimiting signals:
 * explicit CR/LF, a leading `+` while the buffer already has data, or
 * an in-buffer pattern that already looks complete.
 *
 * Regression-target ground truth captured from the AINA APTT V1 against
 * the developer SDK build during field bring-up; covered here so a
 * future parser refactor can't silently break button input.
 */
class AinaAsciiParserTest {
    @Test
    fun `single complete frame with CR-LF emits the button-down edge`() {
        val p = AinaAsciiParser()
        val frame = "+PTT=P\r\n".toByteArray(Charsets.US_ASCII)
        val edges = p.process(frame, frame.size)
        assertEquals(listOf(AinaAsciiParser.ButtonEdge(AinaButton.PTT, true)), edges)
    }

    @Test
    fun `release frame emits the up edge`() {
        val p = AinaAsciiParser()
        val frame = "+PTT=R\r\n".toByteArray(Charsets.US_ASCII)
        val edges = p.process(frame, frame.size)
        assertEquals(listOf(AinaAsciiParser.ButtonEdge(AinaButton.PTT, false)), edges)
    }

    @Test
    fun `lowercase argument is normalized and still parses`() {
        // V1 firmware variants emit lowercase argument bytes on a
        // subset of chassis revs. The parser uppercases the arg slice
        // before matching so P / p / R / r all resolve to a known edge.
        val p = AinaAsciiParser()
        val frame = "+PTT=p".toByteArray(Charsets.US_ASCII)
        val edges = p.process(frame, frame.size)
        assertEquals(listOf(AinaAsciiParser.ButtonEdge(AinaButton.PTT, true)), edges)
    }

    @Test
    fun `frame without CR-LF is still flushed when the buffer looks complete`() {
        // V1 silently drops the terminator on the last frame of a
        // burst — if we only flushed on CR/LF the button would stick
        // until the next press. The `looksComplete` shape check (+CMD=[PR])
        // catches this and forces the flush at end-of-chunk.
        val p = AinaAsciiParser()
        val frame = "+PTTS=P".toByteArray(Charsets.US_ASCII)
        val edges = p.process(frame, frame.size)
        assertEquals(listOf(AinaAsciiParser.ButtonEdge(AinaButton.PTTS, true)), edges)
    }

    @Test
    fun `leading plus mid-buffer flushes the previous frame`() {
        // When two frames arrive back-to-back without a terminator
        // between them, the leading `+` of the second frame is the
        // only delimiter we have. The parser flushes the buffered
        // first frame, then begins accumulating the second.
        val p = AinaAsciiParser()
        val bytes = "+PTT=P+PTT=R".toByteArray(Charsets.US_ASCII)
        val edges = p.process(bytes, bytes.size)
        assertEquals(
            listOf(
                AinaAsciiParser.ButtonEdge(AinaButton.PTT, true),
                AinaAsciiParser.ButtonEdge(AinaButton.PTT, false),
            ),
            edges,
        )
    }

    @Test
    fun `chunked frame is reassembled across two calls`() {
        // SPP reads can split mid-frame. Operator gets ~10ms of latency
        // either way, but the parser must hold partial buffer state
        // between calls instead of dropping the half-frame.
        val p = AinaAsciiParser()
        val first = "+PT".toByteArray(Charsets.US_ASCII)
        val second = "T=P\r\n".toByteArray(Charsets.US_ASCII)
        val edges1 = p.process(first, first.size)
        val edges2 = p.process(second, second.size)
        assertEquals(emptyList<AinaAsciiParser.ButtonEdge>(), edges1)
        assertEquals(listOf(AinaAsciiParser.ButtonEdge(AinaButton.PTT, true)), edges2)
    }

    @Test
    fun `all known commands map to the corresponding AinaButton`() {
        // COMMAND_TO_BUTTON table — drift here means a button stops
        // working with no obvious symptom in the log (parser returns
        // null, frame silently discarded). Pin every entry.
        val cases =
            mapOf(
                "+PTT=P" to AinaButton.PTT,
                "+PTTS=P" to AinaButton.PTTS,
                "+PTTE=P" to AinaButton.PTTE,
                "+PTTB1=P" to AinaButton.PTTB1,
                "+PTTB2=P" to AinaButton.PTTB2,
            )
        for ((wire, button) in cases) {
            val p = AinaAsciiParser()
            val bytes = "$wire\r\n".toByteArray(Charsets.US_ASCII)
            val edges = p.process(bytes, bytes.size)
            assertEquals(
                "wire=$wire",
                listOf(AinaAsciiParser.ButtonEdge(button, true)),
                edges,
            )
        }
    }

    @Test
    fun `unknown command is silently dropped — does not synthesize a button`() {
        // Future firmware might add `+PTTX=P` for a new button class
        // the parser doesn't know yet. Don't emit a fake edge under
        // some other button's identity.
        val p = AinaAsciiParser()
        val frame = "+PTTX=P\r\n".toByteArray(Charsets.US_ASCII)
        val edges = p.process(frame, frame.size)
        assertEquals(emptyList<AinaAsciiParser.ButtonEdge>(), edges)
    }

    @Test
    fun `malformed frame (no equals) is silently dropped`() {
        val p = AinaAsciiParser()
        val frame = "+PTTP\r\n".toByteArray(Charsets.US_ASCII)
        val edges = p.process(frame, frame.size)
        assertEquals(emptyList<AinaAsciiParser.ButtonEdge>(), edges)
    }

    @Test
    fun `empty input returns no edges`() {
        val p = AinaAsciiParser()
        assertEquals(emptyList<AinaAsciiParser.ButtonEdge>(), p.process(ByteArray(0), 0))
        assertEquals(emptyList<AinaAsciiParser.ButtonEdge>(), p.process(ByteArray(8), 0))
    }

    @Test
    fun `length argument bounds processing — bytes past length are ignored`() {
        // SPP read returns a buffer-and-length tuple; bytes past `n` are
        // stale from a previous read. The parser must respect the
        // length and not bleed previous-call garbage into the current
        // frame.
        val p = AinaAsciiParser()
        val frame = "+PTT=P\r\n+GARBAGE".toByteArray(Charsets.US_ASCII)
        val edges = p.process(frame, 8) // only the first "+PTT=P\r\n"
        assertEquals(listOf(AinaAsciiParser.ButtonEdge(AinaButton.PTT, true)), edges)
    }

    @Test
    fun `reset clears partial buffer so a stale half-frame is discarded`() {
        val p = AinaAsciiParser()
        val partial = "+PT".toByteArray(Charsets.US_ASCII)
        p.process(partial, partial.size)
        p.reset()
        // After reset (called by AinaSppReader on disconnect), the next
        // partial fragment should be parsed in isolation — not joined
        // to the stale "+PT" prefix to form "+PTT=R".
        val complete = "T=R\r\n".toByteArray(Charsets.US_ASCII)
        val edges = p.process(complete, complete.size)
        assertEquals(emptyList<AinaAsciiParser.ButtonEdge>(), edges)
    }

    @Test
    fun `frame longer than MAX_FRAME does not overflow buffer`() {
        // Defensive: a noisy SPP channel might dump a long run of junk
        // bytes. Buffer cap is MAX_FRAME (32 chars) — anything past
        // that is silently dropped so we don't accumulate unbounded
        // memory in the parser.
        val p = AinaAsciiParser()
        val junk = ByteArray(200) { 'X'.code.toByte() }
        // Should complete without throwing, no edges emitted.
        val edges = p.process(junk, junk.size)
        assertEquals(emptyList<AinaAsciiParser.ButtonEdge>(), edges)
    }
}
