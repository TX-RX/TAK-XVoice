package com.atakmap.android.xv.transport.mumble

import mumble.MumbleProto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Coverage for the typed-exception surface that
 * ReconnectingMumbleTransport switches on to classify failures
 * (Fatal vs Transient). The exception messages are surfaced to the
 * operator via the UI when reconnect gives up, so the format matters
 * for diagnosability — drift here means worse field debugging.
 */
class MumbleFailuresTest {
    // ============================================================
    // FatalMumbleException
    // ============================================================

    @Test
    fun `FatalMumbleException carries the reject type and reason in the message`() {
        val e = FatalMumbleException(MumbleProto.Reject.RejectType.WrongUserPW, "bad pw")
        assertEquals("Mumble REJECT WrongUserPW: bad pw", e.message)
        assertEquals(MumbleProto.Reject.RejectType.WrongUserPW, e.rejectType)
    }

    @Test
    fun `FatalMumbleException omits the trailing colon-reason when reason is null`() {
        val e = FatalMumbleException(MumbleProto.Reject.RejectType.InvalidUsername, null)
        // No trailing ": " after the type — the message-builder guards
        // on isNullOrBlank.
        assertEquals("Mumble REJECT InvalidUsername", e.message)
    }

    @Test
    fun `FatalMumbleException omits the colon for blank reason too`() {
        val e = FatalMumbleException(MumbleProto.Reject.RejectType.NoCertificate, "")
        assertEquals("Mumble REJECT NoCertificate", e.message)
    }

    @Test
    fun `FatalMumbleException — every documented reject type produces a non-blank message`() {
        // All seven Mumble RejectTypes that the spec enumerates. The
        // reconnect wrapper switches on the throwable type to decide
        // retry behavior; the message is operator-facing.
        for (type in MumbleProto.Reject.RejectType.entries) {
            val e = FatalMumbleException(type, "reason")
            assertNotNull(e.message)
            assertTrue("type=$type message should be non-blank", !e.message.isNullOrBlank())
        }
    }

    // ============================================================
    // SelfKickedException
    // ============================================================

    @Test
    fun `SelfKickedException — banned variant says banned in message`() {
        val e = SelfKickedException(reason = "spam", banned = true, byActorSession = 42)
        assertEquals("Mumble session banned: spam (by session 42)", e.message)
        assertEquals(true, e.banned)
        assertEquals(42, e.byActorSession)
    }

    @Test
    fun `SelfKickedException — kicked variant says kicked`() {
        val e = SelfKickedException(reason = "be nicer", banned = false, byActorSession = 7)
        assertEquals("Mumble session kicked: be nicer (by session 7)", e.message)
    }

    @Test
    fun `SelfKickedException — null reason omits reason text but keeps actor`() {
        val e = SelfKickedException(reason = null, banned = false, byActorSession = 7)
        assertEquals("Mumble session kicked (by session 7)", e.message)
    }

    @Test
    fun `SelfKickedException — actor session 0 omits the actor suffix`() {
        // byActorSession == 0 is the "unknown actor" sentinel; don't
        // render it as "(by session 0)" which would look like the
        // server kicked itself.
        val e = SelfKickedException(reason = "timeout", banned = false, byActorSession = 0)
        assertEquals("Mumble session kicked: timeout", e.message)
    }

    // ============================================================
    // UsernameInUseException
    // ============================================================

    @Test
    fun `UsernameInUseException uses the supplied reason as the message`() {
        val e = UsernameInUseException("ghost session still tracked")
        assertEquals("ghost session still tracked", e.message)
    }

    @Test
    fun `UsernameInUseException — null reason falls back to a stable default`() {
        val e = UsernameInUseException(null)
        assertEquals("username already in use", e.message)
    }

    // ============================================================
    // Class-hierarchy invariants — ReconnectingMumbleTransport
    // pattern-matches on these types
    // ============================================================

    @Test
    fun `all three exceptions are RuntimeException subtypes`() {
        // The reconnect wrapper catches Throwable on connect; we don't
        // want any of these to short-circuit the catch via being
        // checked exceptions.
        assertTrue(FatalMumbleException(MumbleProto.Reject.RejectType.None, null) is RuntimeException)
        assertTrue(SelfKickedException(null, false, 0) is RuntimeException)
        assertTrue(UsernameInUseException(null) is RuntimeException)
    }
}
