package com.atakmap.android.xv.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Coverage for the custom equals/hashCode on VoiceFrame. Kotlin's
 * default `data class` equals does referential comparison on ByteArray
 * (which would always return false for two distinct frames with the
 * same content), so VoiceFrame overrides it with `contentEquals`. The
 * override is hand-maintained — any new field added to VoiceFrame
 * needs a parallel addition here, or the override silently diverges
 * from the constructor's notion of equality.
 *
 * These tests pin the field-by-field contract.
 */
class VoiceFrameTest {
    private fun frame(
        opus: ByteArray = byteArrayOf(1, 2, 3),
        sampleRate: Int = 48_000,
        durationMs: Int = 20,
        sender: String? = "session-1",
        ts: Long = 1_000L,
        slot: Int = 0,
    ) = VoiceFrame(
        opusPayload = opus,
        sampleRateHz = sampleRate,
        frameDurationMs = durationMs,
        senderId = sender,
        monotonicTimestampMs = ts,
        targetSlot = slot,
    )

    @Test
    fun `two frames with identical contents are equal even with distinct ByteArray instances`() {
        // The whole reason the override exists. Default data-class
        // equals would return false here because ByteArray uses
        // referential equality.
        val a = frame(opus = byteArrayOf(1, 2, 3))
        val b = frame(opus = byteArrayOf(1, 2, 3))
        assertEquals("contentEquals: a == b for identical bytes", a, b)
        assertEquals("contentHashCode: identical bytes → identical hash", a.hashCode(), b.hashCode())
    }

    @Test
    fun `frame is equal to itself`() {
        val f = frame()
        assertEquals(f, f)
        assertEquals(f.hashCode(), f.hashCode())
    }

    @Test
    fun `equality is sensitive to opusPayload bytes`() {
        assertNotEquals(frame(opus = byteArrayOf(1, 2, 3)), frame(opus = byteArrayOf(1, 2, 4)))
    }

    @Test
    fun `equality is sensitive to sampleRateHz`() {
        assertNotEquals(frame(sampleRate = 48_000), frame(sampleRate = 16_000))
    }

    @Test
    fun `equality is sensitive to frameDurationMs`() {
        assertNotEquals(frame(durationMs = 20), frame(durationMs = 60))
    }

    @Test
    fun `equality is sensitive to senderId`() {
        assertNotEquals(frame(sender = "alice"), frame(sender = "bob"))
        assertNotEquals(frame(sender = "alice"), frame(sender = null))
    }

    @Test
    fun `equality is sensitive to monotonicTimestampMs`() {
        assertNotEquals(frame(ts = 1_000L), frame(ts = 2_000L))
    }

    @Test
    fun `equality is sensitive to targetSlot`() {
        // The slot is what routes VS2 frames to a different Mumble
        // VoiceTarget — drift here would cross-route bursts.
        assertNotEquals(frame(slot = 0), frame(slot = 1))
    }

    @Test
    fun `equality returns false against null and against an unrelated type`() {
        val f = frame()
        @Suppress("ReplaceCallWithBinaryOperator", "EqualsBetweenInconvertibleTypes")
        assertEquals(false, f.equals(null))
        @Suppress("EqualsBetweenInconvertibleTypes")
        assertEquals(false, f.equals("not a frame"))
    }

    @Test
    fun `hashCode is stable across calls on the same instance`() {
        val f = frame()
        val h1 = f.hashCode()
        val h2 = f.hashCode()
        assertEquals("hashCode must not vary across calls", h1, h2)
    }

    @Test
    fun `default values match the Mumble Opus profile`() {
        // Pinned to ensure the wire-format defaults don't drift —
        // Mumble expects 48 kHz Opus, and the rest of the audio path
        // is configured around these constants.
        val f = VoiceFrame(opusPayload = byteArrayOf())
        assertEquals(48_000, f.sampleRateHz)
        assertEquals(20, f.frameDurationMs)
        assertEquals(null, f.senderId)
        assertEquals(0L, f.monotonicTimestampMs)
        assertEquals(0, f.targetSlot)
    }

    @Test
    fun `large byte payloads produce sensible hash codes (no overflow)`() {
        // Defensive: hashCode uses 31-multiplication accumulation;
        // shouldn't throw on long payloads. Just verify it doesn't
        // crash and produces a finite int.
        val big = ByteArray(10_000) { i -> (i and 0xFF).toByte() }
        val f = frame(opus = big)
        val h = f.hashCode()
        // Any int is fine — just confirm we got one.
        assertTrue("hashCode should be a regular Int", h <= Int.MAX_VALUE && h >= Int.MIN_VALUE)
    }
}
