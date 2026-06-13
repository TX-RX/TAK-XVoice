package com.atakmap.android.xv.calling

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Coverage for the XV direct-call signaling parser. The wire format
 * is CoT (broadcast event with `<__xvcall>` detail), parsed here into
 * a [XvCallSignals.Signal] for the call orchestrator.
 *
 * Security-relevant invariants pinned here:
 *   - calleeUid filter: an event addressed to someone else returns null
 *     (broadcast model + receiver-side filter, see KDoc on parse).
 *   - callerUid sourced from the OUTER event UID prefix, NOT from the
 *     detail attribute. The detail attribute is operator-supplied
 *     (untrusted); the event UID is signed by the TAK Server TLS path.
 *     A spoofed detail with callerUid="someone-else" must NOT win
 *     against the authentic outer UID.
 *
 * Behavioral invariants pinned:
 *   - Missing required field → null (action / channel / calleeUid).
 *   - Non-XV event type at the outer level returned by parse() — but
 *     parseFromAttributes() trusts the caller filtered on type, so we
 *     don't re-test the type guard here.
 */
class XvCallSignalsTest {
    @Test
    fun `parseFromAttributes returns a complete Signal for a normal REQUEST`() {
        val s =
            XvCallSignals.parseFromAttributes(
                eventUid = "ANDROID-alice-xvcall-abc123",
                action = "REQUEST",
                channel = "TAK PRIVATE - deadbeef",
                channelId = "42",
                callerUidAttr = "ANDROID-alice", // matches outer
                calleeUidAttr = "ANDROID-bob",
                callerCallsign = "Alice",
                ourUid = "ANDROID-bob",
            )
        assertNotNull(s)
        s!!
        assertEquals("REQUEST", s.action)
        assertEquals("TAK PRIVATE - deadbeef", s.tempChannelName)
        assertEquals(42, s.tempChannelId)
        assertEquals("ANDROID-alice", s.callerUid)
        assertEquals("ANDROID-bob", s.calleeUid)
        assertEquals("Alice", s.callerCallsign)
    }

    @Test
    fun `parseFromAttributes returns null when this event is for someone else`() {
        // calleeUid (bob) != ourUid (charlie). Broadcast model: every
        // peer receives every signal, but only the addressee acts.
        val s =
            XvCallSignals.parseFromAttributes(
                eventUid = "ANDROID-alice-xvcall-abc",
                action = "REQUEST",
                channel = "TAK PRIVATE - x",
                channelId = "1",
                callerUidAttr = "ANDROID-alice",
                calleeUidAttr = "ANDROID-bob",
                callerCallsign = "Alice",
                ourUid = "ANDROID-charlie",
            )
        assertNull(s)
    }

    @Test
    fun `parseFromAttributes ignores ourUid when null (broadcast inspection mode)`() {
        // Diagnostic / debug path: ourUid==null disables the callee
        // filter so a tool can decode every signal flowing past.
        val s =
            XvCallSignals.parseFromAttributes(
                eventUid = "ANDROID-alice-xvcall-x",
                action = "CANCEL",
                channel = "TAK PRIVATE - y",
                channelId = "7",
                callerUidAttr = "ANDROID-alice",
                calleeUidAttr = "ANDROID-bob",
                callerCallsign = "",
                ourUid = null,
            )
        assertNotNull(s)
        assertEquals("ANDROID-bob", s!!.calleeUid)
    }

    @Test
    fun `parseFromAttributes — caller UID derived from outer event UID (anti-spoof)`() {
        // Detail attribute claims callerUid="evil", outer UID names alice.
        // The outer UID wins — that's the security invariant. A spoofed
        // detail can't impersonate alice.
        val s =
            XvCallSignals.parseFromAttributes(
                eventUid = "ANDROID-alice-xvcall-tok",
                action = "REQUEST",
                channel = "TAK PRIVATE - z",
                channelId = "1",
                callerUidAttr = "ANDROID-evil-spoofer", // ignored
                calleeUidAttr = "ANDROID-bob",
                callerCallsign = "Whatever",
                ourUid = "ANDROID-bob",
            )
        assertNotNull(s)
        assertEquals("ANDROID-alice", s!!.callerUid)
    }

    @Test
    fun `parseFromAttributes falls back to detail attribute when outer UID has no -xvcall- suffix`() {
        // Defensive: an event with a malformed outer UID (no -xvcall-
        // token) shouldn't crash; use the attribute as a best-effort
        // fallback. Marked as untrusted in production logging.
        val s =
            XvCallSignals.parseFromAttributes(
                eventUid = "ANDROID-something-weird",
                action = "REQUEST",
                channel = "TAK PRIVATE - x",
                channelId = "1",
                callerUidAttr = "ANDROID-alice",
                calleeUidAttr = "ANDROID-bob",
                callerCallsign = "",
                ourUid = "ANDROID-bob",
            )
        assertNotNull(s)
        assertEquals("ANDROID-alice", s!!.callerUid)
    }

    @Test
    fun `parseFromAttributes returns null when action is missing`() {
        val s =
            XvCallSignals.parseFromAttributes(
                eventUid = "ANDROID-alice-xvcall-x",
                action = null,
                channel = "TAK PRIVATE - x",
                channelId = "1",
                callerUidAttr = "ANDROID-alice",
                calleeUidAttr = "ANDROID-bob",
                callerCallsign = "",
                ourUid = "ANDROID-bob",
            )
        assertNull(s)
    }

    @Test
    fun `parseFromAttributes returns null when channel is missing`() {
        val s =
            XvCallSignals.parseFromAttributes(
                eventUid = "ANDROID-alice-xvcall-x",
                action = "REQUEST",
                channel = null,
                channelId = "1",
                callerUidAttr = "ANDROID-alice",
                calleeUidAttr = "ANDROID-bob",
                callerCallsign = "",
                ourUid = "ANDROID-bob",
            )
        assertNull(s)
    }

    @Test
    fun `parseFromAttributes returns null when calleeUid is missing`() {
        val s =
            XvCallSignals.parseFromAttributes(
                eventUid = "ANDROID-alice-xvcall-x",
                action = "REQUEST",
                channel = "TAK PRIVATE - x",
                channelId = "1",
                callerUidAttr = "ANDROID-alice",
                calleeUidAttr = null,
                callerCallsign = "",
                ourUid = "ANDROID-bob",
            )
        assertNull(s)
    }

    @Test
    fun `parseFromAttributes treats blank required fields as missing`() {
        val s =
            XvCallSignals.parseFromAttributes(
                eventUid = "ANDROID-alice-xvcall-x",
                action = "   ", // blank, should be treated as absent
                channel = "TAK PRIVATE - x",
                channelId = "1",
                callerUidAttr = "ANDROID-alice",
                calleeUidAttr = "ANDROID-bob",
                callerCallsign = "",
                ourUid = "ANDROID-bob",
            )
        assertNull(s)
    }

    @Test
    fun `parseFromAttributes accepts null channelId — that field is optional`() {
        // channelId is the integer Mumble channel id; some flows
        // (CANCEL, REJECT) don't require it. The parser must keep
        // a null channelId without rejecting the signal.
        val s =
            XvCallSignals.parseFromAttributes(
                eventUid = "ANDROID-alice-xvcall-x",
                action = "CANCEL",
                channel = "TAK PRIVATE - x",
                channelId = null,
                callerUidAttr = "ANDROID-alice",
                calleeUidAttr = "ANDROID-bob",
                callerCallsign = "",
                ourUid = "ANDROID-bob",
            )
        assertNotNull(s)
        assertNull(s!!.tempChannelId)
    }

    @Test
    fun `parseFromAttributes accepts non-integer channelId by treating as null`() {
        // Defensive: a malformed channelId attribute shouldn't crash the
        // parser. toIntOrNull returns null, signal still constructs.
        val s =
            XvCallSignals.parseFromAttributes(
                eventUid = "ANDROID-alice-xvcall-x",
                action = "REQUEST",
                channel = "TAK PRIVATE - x",
                channelId = "not-a-number",
                callerUidAttr = "ANDROID-alice",
                calleeUidAttr = "ANDROID-bob",
                callerCallsign = "",
                ourUid = "ANDROID-bob",
            )
        assertNotNull(s)
        assertNull(s!!.tempChannelId)
    }

    @Test
    fun `parseFromAttributes treats missing callerCallsign as empty string`() {
        // The callsign is operator-facing display text; null becomes
        // empty so the call UI can show the deviceUid fallback without
        // null-checks downstream.
        val s =
            XvCallSignals.parseFromAttributes(
                eventUid = "ANDROID-alice-xvcall-x",
                action = "REQUEST",
                channel = "TAK PRIVATE - x",
                channelId = "1",
                callerUidAttr = "ANDROID-alice",
                calleeUidAttr = "ANDROID-bob",
                callerCallsign = null,
                ourUid = "ANDROID-bob",
            )
        assertNotNull(s)
        assertEquals("", s!!.callerCallsign)
    }

    @Test
    fun `action constants are pinned — operator-facing strings`() {
        // The string values are part of the wire format. A change here
        // would silently desynchronize XV peers running different
        // versions.
        assertEquals("REQUEST", XvCallSignals.ACTION_REQUEST)
        assertEquals("CANCEL", XvCallSignals.ACTION_CANCEL)
        assertEquals("REJECT", XvCallSignals.ACTION_REJECT)
    }

    @Test
    fun `event type and detail name constants are pinned`() {
        // Same wire-format concern. The b-x- prefix is a CoT convention
        // (transient command event); ATAK's map filter relies on it
        // to NOT render markers for these.
        assertEquals("b-x-xv-call", XvCallSignals.EVENT_TYPE)
        assertEquals("__xvcall", XvCallSignals.DETAIL_NAME)
    }
}
