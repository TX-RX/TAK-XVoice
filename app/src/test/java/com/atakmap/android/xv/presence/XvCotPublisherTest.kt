package com.atakmap.android.xv.presence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-Kotlin coverage for the `<__xv>` CoT detail builder. The
 * production code in [XvCotPublisher.rebuildAndRegister] feeds these
 * maps into a fresh CotDetail via setAttribute; the listener side
 * ([XvCotListener.buildPresence]) consumes the same attribute names.
 * Any drift between these two surfaces makes XV peers invisible to
 * each other on the air — a regression with no log signature.
 *
 * Tests pin the exact attribute shape (which keys are emitted, which
 * are omitted when null/blank, exact value formatting).
 */
class XvCotPublisherTest {
    @Test
    fun `buildPresenceAttributes includes required fields verbatim`() {
        val attrs =
            XvCotPublisher.buildPresenceAttributes(
                version = "0.1.11",
                deviceUid = "ANDROID-alice",
                capabilities = setOf("direct-call", "emc"),
                certFp = null,
                server = null,
                mumbleSession = null,
                callsign = null,
            )
        assertEquals("0.1.11", attrs["ver"])
        assertEquals("ANDROID-alice", attrs["uid"])
        // CSV; iteration order of a Set is unspecified but the
        // production code uses joinToString(",") which preserves
        // insertion order for a LinkedHashSet; the constructor here
        // uses setOf which builds a LinkedHashSet.
        assertEquals("direct-call,emc", attrs["caps"])
    }

    @Test
    fun `optional fields omitted when null`() {
        val attrs =
            XvCotPublisher.buildPresenceAttributes(
                version = "0.1.11",
                deviceUid = "ANDROID-alice",
                capabilities = emptySet(),
                certFp = null,
                server = null,
                mumbleSession = null,
                callsign = null,
            )
        assertFalse("certFp must be absent when null", attrs.containsKey("certFp"))
        assertFalse("server must be absent when null", attrs.containsKey("server"))
        assertFalse("mumbleSession must be absent when null", attrs.containsKey("mumbleSession"))
        assertFalse("callsign must be absent when null", attrs.containsKey("callsign"))
    }

    @Test
    fun `optional fields included when non-null`() {
        val attrs =
            XvCotPublisher.buildPresenceAttributes(
                version = "0.1.11",
                deviceUid = "ANDROID-alice",
                capabilities = setOf("direct-call"),
                certFp = "sha256:abc123",
                server = "tak.example.com",
                mumbleSession = 31,
                callsign = "Alice",
            )
        assertEquals("sha256:abc123", attrs["certFp"])
        assertEquals("tak.example.com", attrs["server"])
        assertEquals("31", attrs["mumbleSession"])
        assertEquals("Alice", attrs["callsign"])
    }

    @Test
    fun `blank callsign is omitted (forces deviceUid display fallback on consumers)`() {
        val attrs =
            XvCotPublisher.buildPresenceAttributes(
                version = "0.1.11",
                deviceUid = "ANDROID-alice",
                capabilities = emptySet(),
                certFp = null,
                server = null,
                mumbleSession = null,
                callsign = "   ",
            )
        assertFalse("blank callsign must be absent", attrs.containsKey("callsign"))
    }

    @Test
    fun `empty capabilities still emit a caps attribute (empty string)`() {
        // Production code always emits `caps`, even when empty. Pinning
        // this lets us distinguish "no caps declared" from "no caps
        // attribute present" on the listener side.
        val attrs =
            XvCotPublisher.buildPresenceAttributes(
                version = "0.1.11",
                deviceUid = "ANDROID-alice",
                capabilities = emptySet(),
                certFp = null,
                server = null,
                mumbleSession = null,
                callsign = null,
            )
        assertEquals("", attrs["caps"])
    }

    @Test
    fun `mumbleSession=0 still gets emitted (valid session id)`() {
        val attrs =
            XvCotPublisher.buildPresenceAttributes(
                version = "0.1.11",
                deviceUid = "ANDROID-alice",
                capabilities = emptySet(),
                certFp = null,
                server = null,
                mumbleSession = 0,
                callsign = null,
            )
        assertEquals("0", attrs["mumbleSession"])
    }

    @Test
    fun `attribute insertion order is stable for caps order-sensitive consumers`() {
        // LinkedHashSet preserves the order callers supply; the CSV
        // join then matches. A consumer that does ordered-set parse
        // wouldn't be tripped by reordering.
        val attrs =
            XvCotPublisher.buildPresenceAttributes(
                version = "0.1.11",
                deviceUid = "ANDROID-alice",
                capabilities = linkedSetOf("zeta", "alpha", "beta"),
                certFp = null,
                server = null,
                mumbleSession = null,
                callsign = null,
            )
        assertEquals("zeta,alpha,beta", attrs["caps"])
    }

    // ============================================================
    // channelToAttributes — per-channel <ch …/> attribute map
    // ============================================================

    @Test
    fun `channelToAttributes emits name and id always`() {
        val attrs = XvCotPublisher.channelToAttributes(XvChannel("REACT", 6, keyEpoch = 0))
        assertEquals("REACT", attrs["name"])
        assertEquals("6", attrs["id"])
    }

    @Test
    fun `channelToAttributes omits keyEpoch when zero (compact common case)`() {
        val attrs = XvCotPublisher.channelToAttributes(XvChannel("REACT", 6, keyEpoch = 0))
        assertFalse("keyEpoch=0 should be omitted", attrs.containsKey("keyEpoch"))
    }

    @Test
    fun `channelToAttributes includes keyEpoch when positive`() {
        val attrs = XvCotPublisher.channelToAttributes(XvChannel("REACT", 6, keyEpoch = 42))
        assertEquals("42", attrs["keyEpoch"])
    }

    // ============================================================
    // Roundtrip — publisher → listener semantics
    // ============================================================

    @Test
    fun `roundtrip — publisher attributes parse back to an equivalent XvPresence`() {
        // The two halves of the wire format must agree. Build a
        // presence attributes map via the publisher; feed each
        // attribute to the listener's buildPresence; verify the
        // round-trip preserves every value.
        val attrs =
            XvCotPublisher.buildPresenceAttributes(
                version = "0.1.11",
                deviceUid = "ANDROID-alice",
                capabilities = linkedSetOf("direct-call", "emc"),
                certFp = "sha256:abc123",
                server = "tak.example.com",
                mumbleSession = 31,
                callsign = "Alice",
            )
        val presence =
            XvCotListener.buildPresence(
                outerEventUid = attrs["uid"]!!,
                ver = attrs["ver"],
                capsCsv = attrs["caps"],
                certFp = attrs["certFp"],
                server = attrs["server"],
                mumbleSessionStr = attrs["mumbleSession"],
                callsign = attrs["callsign"],
                channels = emptyList(),
                nowMs = 1L,
            )
        assertEquals("ANDROID-alice", presence.deviceUid)
        assertEquals("0.1.11", presence.version)
        assertEquals(setOf("direct-call", "emc"), presence.capabilities)
        assertEquals("sha256:abc123", presence.certFingerprint)
        assertEquals("tak.example.com", presence.server)
        assertEquals(31, presence.mumbleSession)
        assertEquals("Alice", presence.callsign)
    }

    @Test
    fun `roundtrip — omitted fields parse to nulls on the listener side`() {
        val attrs =
            XvCotPublisher.buildPresenceAttributes(
                version = "0.1.11",
                deviceUid = "ANDROID-alice",
                capabilities = emptySet(),
                certFp = null,
                server = null,
                mumbleSession = null,
                callsign = null,
            )
        val presence =
            XvCotListener.buildPresence(
                outerEventUid = attrs["uid"]!!,
                ver = attrs["ver"],
                capsCsv = attrs["caps"],
                certFp = attrs["certFp"],
                server = attrs["server"],
                mumbleSessionStr = attrs["mumbleSession"],
                callsign = attrs["callsign"],
                channels = emptyList(),
                nowMs = 1L,
            )
        assertNull(presence.certFingerprint)
        assertNull(presence.server)
        assertNull(presence.mumbleSession)
        assertNull(presence.callsign)
        assertTrue(presence.capabilities.isEmpty())
    }

    @Test
    fun `DETAIL_NAME constant is pinned to the documented wire shape`() {
        // XvPresenceRegistry, XvCotListener, and the CoT pipeline all
        // key off this. Changing it would orphan every operator's
        // existing presence cache.
        assertEquals("__xv", XvCotPublisher.DETAIL_NAME)
    }
}
