package com.atakmap.android.xv.aina

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Coverage for [BitmaskGattPttReader.diffEdges] (the base's edge-
 * triggered press/release derivation), [PrymeBleReader]'s mask
 * decoder, and [BitmaskGattPttReader.classifyDisconnect] (the pure
 * decision function that decides whether an incoming
 * `STATE_DISCONNECTED` callback should propagate or be suppressed).
 * Exercised as pure functions so no Android context / GATT stack is
 * needed.
 */
class BitmaskGattPttReaderTest {
    // ============================================================
    // Edge diff — the base's press/release derivation
    // ============================================================

    @Test
    fun `diffEdges returns null when nothing changes`() {
        assertNull(BitmaskGattPttReader.diffEdges(emptySet(), emptySet()))
        assertNull(
            BitmaskGattPttReader.diffEdges(
                setOf(AinaButton.PTT),
                setOf(AinaButton.PTT),
            ),
        )
    }

    @Test
    fun `diffEdges reports a down event for a newly pressed button`() {
        val edges = BitmaskGattPttReader.diffEdges(emptySet(), setOf(AinaButton.PTT))
        assertEquals(listOf(AinaButton.PTT), edges?.down)
        assertTrue("no releases when going from nothing to something", edges?.up?.isEmpty() == true)
    }

    @Test
    fun `diffEdges reports an up event for a released button`() {
        val edges = BitmaskGattPttReader.diffEdges(setOf(AinaButton.PTT), emptySet())
        assertTrue("no presses when going from something to nothing", edges?.down?.isEmpty() == true)
        assertEquals(listOf(AinaButton.PTT), edges?.up)
    }

    @Test
    fun `diffEdges reports both edges when the held set rotates`() {
        // Ex: operator releases PTT and immediately presses secondary
        val edges =
            BitmaskGattPttReader.diffEdges(
                setOf(AinaButton.PTT),
                setOf(AinaButton.PTTS),
            )
        assertEquals(listOf(AinaButton.PTTS), edges?.down)
        assertEquals(listOf(AinaButton.PTT), edges?.up)
    }

    @Test
    fun `diffEdges handles multi-button transitions independently`() {
        // Operator was holding PTT; now holds PTT + SOS. Only SOS
        // reports as newly-pressed; PTT stays held so nothing about
        // it is emitted.
        val edges =
            BitmaskGattPttReader.diffEdges(
                setOf(AinaButton.PTT),
                setOf(AinaButton.PTT, AinaButton.PTTE),
            )
        assertEquals(listOf(AinaButton.PTTE), edges?.down)
        assertTrue(edges?.up?.isEmpty() == true)
    }

    // ============================================================
    // Pryme decoder — "any non-zero = PTT" heuristic
    // ============================================================

    @Test
    fun `Pryme decoder maps zero mask to nothing held`() {
        assertTrue(PrymeBleReader.decodePrymeMask(0x00).isEmpty())
    }

    @Test
    fun `Pryme decoder maps any non-zero mask to PTT held`() {
        // Every observed value we've seen from field units is caught
        // by the "non-zero = PTT" heuristic. This locks that behavior
        // in so the refactor cannot silently narrow it.
        for (m in 0x01..0xFF) {
            assertEquals(
                "mask=0x${m.toString(16)} should map to PTT held",
                setOf(AinaButton.PTT),
                PrymeBleReader.decodePrymeMask(m),
            )
        }
    }

    // ============================================================
    // classifyDisconnect — decision table for STATE_DISCONNECTED
    // ============================================================
    //
    // Field bug 2026-07-10: Pryme BT-PTT reconnect emitted two
    // `up=false` callbacks in the same millisecond — one from the
    // synchronous [disconnect] path, one from the OS's async
    // STATE_DISCONNECTED echo. classifyDisconnect encodes the
    // suppression policy that dedupes them.

    @Test
    fun `classifyDisconnect suppresses when reader is disposed`() {
        // disposed beats every other reason — the owner has moved on
        // and the reader must never fire back into stale state, even
        // if this is technically a real link drop.
        assertEquals(
            BitmaskGattPttReader.DisconnectAction.SUPPRESS_DISPOSED,
            BitmaskGattPttReader.classifyDisconnect(
                intentional = false,
                disposed = true,
            ),
        )
        assertEquals(
            BitmaskGattPttReader.DisconnectAction.SUPPRESS_DISPOSED,
            BitmaskGattPttReader.classifyDisconnect(
                intentional = true,
                disposed = true,
            ),
        )
    }

    @Test
    fun `classifyDisconnect suppresses the async echo of an intentional teardown`() {
        // disconnect() already fired onConnectionState(false)
        // synchronously; the OS's STATE_DISCONNECTED echo lands
        // here and MUST be swallowed so the plugin doesn't log
        // the duplicate `External button connection up=false`.
        assertEquals(
            BitmaskGattPttReader.DisconnectAction.SUPPRESS_INTENTIONAL,
            BitmaskGattPttReader.classifyDisconnect(
                intentional = true,
                disposed = false,
            ),
        )
    }

    @Test
    fun `classifyDisconnect notifies and reconnects on a real link drop`() {
        // Neither the app nor a dispose caused this — it's a real
        // BT link loss (out-of-range, device power-off, adapter
        // toggle). Fire the callback so PttDispatcher can release
        // any stuck slot, and schedule reconnect so an operator
        // walking back into range doesn't have to fiddle with UI.
        assertEquals(
            BitmaskGattPttReader.DisconnectAction.NOTIFY_AND_RECONNECT,
            BitmaskGattPttReader.classifyDisconnect(
                intentional = false,
                disposed = false,
            ),
        )
    }
}
