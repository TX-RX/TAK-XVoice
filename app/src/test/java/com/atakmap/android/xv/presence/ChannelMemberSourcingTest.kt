package com.atakmap.android.xv.presence

import com.atakmap.android.xv.presence.ChannelMemberSourcing.ServerSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the mesh-vs-cloud member classification to the mesh-voice
 * toggle. Regression guard for the bug where a cloud-only
 * (mesh-disabled) session showed a non-zero 📻 mesh client count:
 * presence-derived rows were classified LOCAL_MESH / BOTH regardless
 * of whether mesh voice was actually enabled.
 */
class ChannelMemberSourcingTest {
    @Test
    fun serverMemberWithMeshPresenceIsPlainServerWhenMeshDisabled() {
        assertEquals(
            ServerSource.SERVER,
            ChannelMemberSourcing.serverSource(alsoReachableOnMesh = true, meshEnabled = false),
        )
    }

    @Test
    fun serverMemberIsBothOnlyWhenMeshEnabledAndAlsoOnMesh() {
        assertEquals(
            ServerSource.BOTH,
            ChannelMemberSourcing.serverSource(alsoReachableOnMesh = true, meshEnabled = true),
        )
    }

    @Test
    fun serverMemberWithoutMeshPresenceIsAlwaysServer() {
        assertEquals(
            ServerSource.SERVER,
            ChannelMemberSourcing.serverSource(alsoReachableOnMesh = false, meshEnabled = true),
        )
        assertEquals(
            ServerSource.SERVER,
            ChannelMemberSourcing.serverSource(alsoReachableOnMesh = false, meshEnabled = false),
        )
    }

    @Test
    fun localMeshRowsCountedOnlyWhenMeshEnabled() {
        assertFalse(ChannelMemberSourcing.includeLocalMeshRows(meshEnabled = false))
        assertTrue(ChannelMemberSourcing.includeLocalMeshRows(meshEnabled = true))
    }
}
