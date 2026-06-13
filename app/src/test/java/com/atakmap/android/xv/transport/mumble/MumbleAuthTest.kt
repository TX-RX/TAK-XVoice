package com.atakmap.android.xv.transport.mumble

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Coverage for the pure helpers in MumbleAuth — the wire-username
 * builder, specifically. The rest of MumbleAuth (TLS socket
 * construction, ATAK keystore loading) is integration-level and
 * needs a real ATAK runtime + provisioned cert; those paths are
 * exercised in field testing, not unit tests.
 *
 * `mumbleUsername` is load-bearing in two places:
 *   1. OTS Murmur authenticator uses the full string as the auth
 *      principal — drift here splits an operator's identity in the
 *      Murmur user table.
 *   2. XV's own roster scanner (MumbleTransport) uses the literal
 *      `---` separator to detect XV peers vs generic Mumble clients.
 *      A reformatting of this builder would make XV peers invisible
 *      to each other.
 */
class MumbleAuthTest {
    @Test
    fun `mumbleUsername joins callsign and slot with the load-bearing --- separator`() {
        val u = MumbleAuth.mumbleUsername(callsign = "Whiskey-1", slotSuffix = "a3f8b2VS1")
        assertEquals("Whiskey-1---a3f8b2VS1", u)
    }

    @Test
    fun `mumbleUsername replaces spaces in callsign with underscores`() {
        // OTS's username column doesn't accept spaces; the substitution
        // keeps the username deterministic without rejecting any
        // operator's chosen display callsign.
        val u = MumbleAuth.mumbleUsername(callsign = "Echo Two", slotSuffix = "abc123VS2")
        assertEquals("Echo_Two---abc123VS2", u)
    }

    @Test
    fun `mumbleUsername trims leading and trailing whitespace from callsign`() {
        // Operators sometimes paste callsigns from notes — strip
        // outer whitespace so two operators with "Alpha" and " Alpha "
        // can't end up as distinct Murmur users.
        val u = MumbleAuth.mumbleUsername(callsign = "  Alpha  ", slotSuffix = "deadbeefVS1")
        assertEquals("Alpha---deadbeefVS1", u)
    }

    @Test
    fun `mumbleUsername falls back to ATAK when callsign is null`() {
        // Edge case: ATAK can return null deviceCallsign on a fresh
        // device that hasn't been configured. Don't crash; use a
        // recognizable sentinel so the operator sees "you're showing
        // up as ATAK---..." and knows to set the callsign.
        val u = MumbleAuth.mumbleUsername(callsign = null, slotSuffix = "ffffff1VS1")
        assertEquals("ATAK---ffffff1VS1", u)
    }

    @Test
    fun `mumbleUsername handles a callsign that is only whitespace`() {
        // After trim() the safe value becomes empty. The current
        // implementation pipes through unchanged — pin the actual
        // behavior so a future refactor that changes "empty trim →
        // ATAK fallback" surfaces here.
        val u = MumbleAuth.mumbleUsername(callsign = "   ", slotSuffix = "aaaaaaVS1")
        assertEquals("---aaaaaaVS1", u)
    }

    @Test
    fun `mumbleUsername substitutes ALL spaces, not just the first`() {
        val u = MumbleAuth.mumbleUsername(callsign = "Bravo Six Niner", slotSuffix = "112233VS2")
        assertEquals("Bravo_Six_Niner---112233VS2", u)
    }
}
