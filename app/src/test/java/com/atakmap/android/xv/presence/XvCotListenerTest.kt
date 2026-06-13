package com.atakmap.android.xv.presence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-Kotlin coverage for the `<__xv>` presence parser. Logic
 * extracted from XvCotListener.handlePresence into the pure
 * [XvCotListener.buildPresence] helper so tests can pin the
 * attribute-shape semantics without standing up the ATAK CotEvent /
 * CotDetail runtime.
 *
 * Production callsite does the security-critical "outer event.uid
 * only" guard ahead of buildPresence — that guard is not tested
 * here because it lives in the ATAK-runtime path. What we test:
 * the shape of the resulting [XvPresence] given the raw attribute
 * inputs.
 */
class XvCotListenerTest {
    private fun build(
        outerUid: String = "ANDROID-alice",
        ver: String? = "0.1.11",
        caps: String? = "direct-call,emc",
        certFp: String? = "abc123",
        server: String? = "tak.example.com",
        mumbleSession: String? = "31",
        callsign: String? = "Alice",
        channels: List<XvChannel> = emptyList(),
        nowMs: Long = 1_000_000L,
    ): XvPresence =
        XvCotListener.buildPresence(
            outerEventUid = outerUid,
            ver = ver,
            capsCsv = caps,
            certFp = certFp,
            server = server,
            mumbleSessionStr = mumbleSession,
            callsign = callsign,
            channels = channels,
            nowMs = nowMs,
        )

    @Test
    fun `complete attribute set produces a fully-populated XvPresence`() {
        val p =
            build(
                channels = listOf(XvChannel("REACT", 6, keyEpoch = 42)),
            )
        assertEquals("ANDROID-alice", p.deviceUid)
        assertEquals("0.1.11", p.version)
        assertEquals(setOf("direct-call", "emc"), p.capabilities)
        assertEquals("abc123", p.certFingerprint)
        assertEquals("tak.example.com", p.server)
        assertEquals(31, p.mumbleSession)
        assertEquals("Alice", p.callsign)
        assertEquals(1, p.channels.size)
        assertEquals(1_000_000L, p.lastSeenMs)
    }

    @Test
    fun `capabilities CSV is split, trimmed, and de-empty-filtered`() {
        val p =
            build(caps = " direct-call ,, emc ,  ,extra ")
        // Empty / whitespace-only entries dropped; surviving entries trimmed.
        assertEquals(setOf("direct-call", "emc", "extra"), p.capabilities)
    }

    @Test
    fun `null caps becomes empty set, not null`() {
        // Forward-compat: a peer running older XV may not have caps.
        // Downstream code expects a Set, not null.
        val p = build(caps = null)
        assertEquals(emptySet<String>(), p.capabilities)
    }

    @Test
    fun `null ver becomes empty string (display fallback)`() {
        val p = build(ver = null)
        assertEquals("", p.version)
    }

    @Test
    fun `null certFp stays null on the presence (no fallback)`() {
        val p = build(certFp = null)
        assertNull(p.certFingerprint)
    }

    @Test
    fun `blank certFp becomes null`() {
        val p = build(certFp = "   ")
        assertNull(p.certFingerprint)
    }

    @Test
    fun `blank server becomes null`() {
        val p = build(server = " ")
        assertNull(p.server)
    }

    @Test
    fun `valid mumbleSession parses to Int`() {
        assertEquals(31, build(mumbleSession = "31").mumbleSession)
    }

    @Test
    fun `non-integer mumbleSession becomes null`() {
        // Defensive: peer publishes a malformed attribute; we recover
        // without crashing and the UI falls back to roster scanning.
        assertNull(build(mumbleSession = "not-a-number").mumbleSession)
    }

    @Test
    fun `null mumbleSession stays null`() {
        assertNull(build(mumbleSession = null).mumbleSession)
    }

    @Test
    fun `blank callsign becomes null (forces deviceUid display fallback)`() {
        // The call picker falls back to deviceUid when callsign is null
        // or blank — a blank-but-non-null callsign would render as
        // empty UI; null is the recognizable "no callsign yet" signal.
        assertNull(build(callsign = "   ").callsign)
    }

    @Test
    fun `non-blank callsign is preserved verbatim`() {
        assertEquals("Bravo Six", build(callsign = "Bravo Six").callsign)
    }

    @Test
    fun `channels list is passed through unchanged`() {
        val chans =
            listOf(
                XvChannel("REACT", 6, keyEpoch = 42),
                XvChannel("Family", 3, keyEpoch = 7),
            )
        val p = build(channels = chans)
        assertEquals(chans, p.channels)
    }

    @Test
    fun `empty channels list is preserved`() {
        val p = build(channels = emptyList())
        assertTrue(p.channels.isEmpty())
    }

    @Test
    fun `lastSeenMs is taken from the supplied clock value`() {
        // Tests pass deterministic clock so the timestamps in
        // assertions don't depend on System.currentTimeMillis().
        assertEquals(12_345L, build(nowMs = 12_345L).lastSeenMs)
    }
}
