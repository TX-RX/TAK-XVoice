package com.atakmap.android.xv.transport

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Coverage for [MumbleTransport.lobbyLabel] — the shared display helper
 * that every offline/mesh UI site funnels channel names through so the
 * lobby always renders as "Lobby", never "Root" or lowercase "lobby".
 *
 * This is the display-side companion to the interop-side alias in
 * [com.atakmap.android.xv.transport.multicast.MulticastGroupDerivation.canonicalChannelName]:
 * the derivation folds "Root"→"lobby" so the multicast group can't fork,
 * and this helper folds any lobby-canonical spelling → the "Lobby"
 * display name. Pure logic, no Android context required.
 */
class MumbleTransportLobbyLabelTest {
    @Test
    fun `lobby-canonical spellings all render as the Lobby display name`() {
        assertEquals(MumbleTransport.LOBBY_DISPLAY_NAME, MumbleTransport.lobbyLabel("Root"))
        assertEquals(MumbleTransport.LOBBY_DISPLAY_NAME, MumbleTransport.lobbyLabel("root"))
        assertEquals(MumbleTransport.LOBBY_DISPLAY_NAME, MumbleTransport.lobbyLabel("  ROOT "))
        assertEquals(MumbleTransport.LOBBY_DISPLAY_NAME, MumbleTransport.lobbyLabel("Lobby"))
        assertEquals(MumbleTransport.LOBBY_DISPLAY_NAME, MumbleTransport.lobbyLabel("lobby"))
    }

    @Test
    fun `a non-lobby name is returned trimmed and otherwise unchanged`() {
        assertEquals("Ops-1", MumbleTransport.lobbyLabel("Ops-1"))
        assertEquals("Ops-1", MumbleTransport.lobbyLabel("  Ops-1  "))
        // Case is preserved for non-lobby names (display, not canonical).
        assertEquals("Bravo", MumbleTransport.lobbyLabel("Bravo"))
    }
}
