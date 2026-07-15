package com.atakmap.android.xv.transport.multicast

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RxDeduperTest {
    private val dedup = RxDeduper(ownerSilenceMs = 750)

    @Test
    fun `first leg to deliver a speaker owns the burst`() {
        assertTrue(dedup.shouldPlay("mumble", "uid-alpha", nowMs = 1_000))
        assertFalse(dedup.shouldPlay("mesh:ops-1", "uid-alpha", nowMs = 1_020))
        // Owner keeps playing.
        assertTrue(dedup.shouldPlay("mumble", "uid-alpha", nowMs = 1_040))
        // Duplicate leg keeps dropping for the whole burst.
        assertFalse(dedup.shouldPlay("mesh:ops-1", "uid-alpha", nowMs = 1_060))
    }

    @Test
    fun `different speakers never collide`() {
        assertTrue(dedup.shouldPlay("mumble", "uid-alpha", nowMs = 1_000))
        assertTrue(dedup.shouldPlay("mesh:ops-1", "uid-bravo", nowMs = 1_010))
    }

    @Test
    fun `ownership expires after the silence window so the other leg can take over`() {
        assertTrue(dedup.shouldPlay("mumble", "uid-alpha", nowMs = 1_000))
        // Mumble leg dies mid-conversation; next burst arrives on mesh
        // only, after the silence window.
        assertTrue(dedup.shouldPlay("mesh:ops-1", "uid-alpha", nowMs = 2_000))
        // And now mesh owns — a late mumble copy drops.
        assertFalse(dedup.shouldPlay("mumble", "uid-alpha", nowMs = 2_020))
    }

    @Test
    fun `duplicate-leg frames do not extend the owner's burst`() {
        assertTrue(dedup.shouldPlay("mumble", "uid-alpha", nowMs = 1_000))
        // A steady stream of mesh duplicates while the mumble copy
        // stopped must NOT keep ownership alive forever.
        assertFalse(dedup.shouldPlay("mesh:ops-1", "uid-alpha", nowMs = 1_400))
        assertTrue(dedup.shouldPlay("mesh:ops-1", "uid-alpha", nowMs = 1_800))
    }

    @Test
    fun `reset forgets all ownership`() {
        assertTrue(dedup.shouldPlay("mumble", "uid-alpha", nowMs = 1_000))
        dedup.reset()
        assertTrue(dedup.shouldPlay("mesh:ops-1", "uid-alpha", nowMs = 1_010))
    }

    @Test
    fun `prune drops stale speakers but keeps live ones`() {
        assertTrue(dedup.shouldPlay("mumble", "uid-alpha", nowMs = 1_000))
        assertTrue(dedup.shouldPlay("mumble", "uid-bravo", nowMs = 5_000))
        dedup.prune(nowMs = 5_100)
        // alpha's entry is gone — mesh can claim instantly.
        assertTrue(dedup.shouldPlay("mesh:ops-1", "uid-alpha", nowMs = 5_150))
        // bravo is still owned by mumble.
        assertFalse(dedup.shouldPlay("mesh:ops-1", "uid-bravo", nowMs = 5_150))
    }
}
