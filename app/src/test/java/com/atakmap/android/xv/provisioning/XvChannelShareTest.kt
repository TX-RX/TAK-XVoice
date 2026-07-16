package com.atakmap.android.xv.provisioning

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Coverage for the CoT channel-share parser. Wire format is a broadcast
 * `b-x-xv-share` event with a `<__xvshare>` detail; parsed here into a
 * [XvChannelShare.ShareSignal].
 *
 * Invariants pinned (mirrors XvCallSignalsTest):
 *   - target filter: a share addressed to specific UIDs is dropped for a
 *     non-addressee; an empty target list is a broadcast everyone acts on.
 *   - sharerUid comes from the TLS-authenticated OUTER event UID prefix,
 *     not the untrusted detail attribute.
 *   - our own share echoed back is ignored.
 *   - channel names split on newline, trimmed, blanks dropped; no channels
 *     → null.
 */
class XvChannelShareTest {
    private fun parse(
        eventUid: String? = "uid-alice-xvshare-abc123",
        sharerUidAttr: String? = "uid-alice",
        callsign: String? = "Alpha-1",
        targets: String? = "uid-bob uid-carol",
        serverHost: String? = "tak.example.com",
        channels: String? = "Falcon-73",
        ourUid: String? = "uid-bob",
    ) = XvChannelShare.parseFromAttributes(
        eventUid = eventUid,
        sharerUidAttr = sharerUidAttr,
        sharerCallsign = callsign,
        targets = targets,
        serverHost = serverHost,
        channels = channels,
        ourUid = ourUid,
    )

    @Test
    fun `addressed share parses for the addressee`() {
        val s = parse(ourUid = "uid-bob")!!
        assertEquals("uid-alice", s.sharerUid)
        assertEquals("Alpha-1", s.sharerCallsign)
        assertEquals(listOf("Falcon-73"), s.channelNames)
        assertEquals("tak.example.com", s.serverHost)
        assertTrue("uid-bob" in s.targetUids)
    }

    @Test
    fun `share addressed to someone else is dropped`() {
        assertNull(parse(targets = "uid-carol uid-dave", ourUid = "uid-bob"))
    }

    @Test
    fun `empty targets is a broadcast everyone acts on`() {
        val s = parse(targets = "", ourUid = "uid-zzz")!!
        assertEquals(listOf("Falcon-73"), s.channelNames)
    }

    @Test
    fun `our own share echoed back is ignored`() {
        assertNull(parse(eventUid = "uid-bob-xvshare-x", targets = "", ourUid = "uid-bob"))
    }

    @Test
    fun `sharer uid comes from the outer event uid, not the attribute`() {
        // A spoofed detail claiming someone else must not win.
        val s = parse(eventUid = "uid-alice-xvshare-tok", sharerUidAttr = "uid-victim", ourUid = "uid-bob")!!
        assertEquals("uid-alice", s.sharerUid)
    }

    @Test
    fun `multiple channel names split on newline, trimmed, blanks dropped`() {
        val s = parse(channels = "Falcon-73\n  Ops 1  \n\nbravo\n", ourUid = "uid-bob")!!
        assertEquals(listOf("Falcon-73", "Ops 1", "bravo"), s.channelNames)
    }

    @Test
    fun `no channels yields null`() {
        assertNull(parse(channels = "  \n \n", ourUid = "uid-bob"))
        assertNull(parse(channels = null, ourUid = "uid-bob"))
    }

    @Test
    fun `null ourUid is broadcast-inspection mode — no target filter`() {
        val s = parse(targets = "uid-bob", ourUid = null)!!
        assertEquals("uid-alice", s.sharerUid)
    }
}
