package com.atakmap.android.xv.mission

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MissionChannelProvisionerTest {
    private val emptyDir: (String) -> Boolean = { false }

    private fun dirOf(vararg names: String): (String) -> Boolean {
        val set = names.map { it.lowercase() }.toSet()
        return { it.lowercase() in set }
    }

    // ---- no mission ----

    @Test
    fun `no active mission never disturbs the operator's channel`() {
        val p = MissionChannelProvisioner()
        assertEquals(
            MissionChannelProvisioner.Action.NoOp,
            p.reconcile(emptyList(), emptyDir, joinedChannel = "Lobby", allowCreate = true),
        )
        assertNull(p.currentTarget())
    }

    // ---- join existing ----

    @Test
    fun `existing mission channel is joined`() {
        val p = MissionChannelProvisioner()
        val action =
            p.reconcile(listOf("Bravo Response"), dirOf("Bravo Response"), joinedChannel = "Lobby", allowCreate = true)
        assertEquals(MissionChannelProvisioner.Action.Join("Bravo Response"), action)
        assertEquals("Bravo Response", p.currentTarget())
    }

    @Test
    fun `already on the mission channel is a no-op`() {
        val p = MissionChannelProvisioner()
        assertEquals(
            MissionChannelProvisioner.Action.NoOp,
            p.reconcile(listOf("Bravo"), dirOf("Bravo"), joinedChannel = "Bravo", allowCreate = true),
        )
    }

    @Test
    fun `channel match is case and whitespace insensitive`() {
        val p = MissionChannelProvisioner()
        assertEquals(
            MissionChannelProvisioner.Action.NoOp,
            p.reconcile(listOf("  Bravo  "), dirOf("bravo"), joinedChannel = "BRAVO", allowCreate = true),
        )
    }

    // ---- create-then-join ----

    @Test
    fun `absent channel is created then joined once it appears`() {
        val p = MissionChannelProvisioner()
        val first = p.reconcile(listOf("Bravo"), emptyDir, joinedChannel = "Lobby", allowCreate = true)
        assertEquals(MissionChannelProvisioner.Action.Create("Bravo"), first)
        // Still absent next tick — wait, don't spam create.
        assertEquals(
            MissionChannelProvisioner.Action.NoOp,
            p.reconcile(listOf("Bravo"), emptyDir, joinedChannel = "Lobby", allowCreate = true),
        )
        // ChannelState arrives → join.
        assertEquals(
            MissionChannelProvisioner.Action.Join("Bravo"),
            p.reconcile(listOf("Bravo"), dirOf("Bravo"), joinedChannel = "Lobby", allowCreate = true),
        )
    }

    @Test
    fun `create retries on the retry cadence then reports unavailable`() {
        val p = MissionChannelProvisioner(createRetryTicks = 2, maxCreateAttempts = 2)
        // t1: first create.
        assertEquals(
            MissionChannelProvisioner.Action.Create("Bravo"),
            p.reconcile(listOf("Bravo"), emptyDir, "Lobby", allowCreate = true),
        )
        // t2: within retry window → wait.
        assertEquals(
            MissionChannelProvisioner.Action.NoOp,
            p.reconcile(listOf("Bravo"), emptyDir, "Lobby", allowCreate = true),
        )
        // t3: retry window elapsed → second (final) create.
        assertEquals(
            MissionChannelProvisioner.Action.Create("Bravo"),
            p.reconcile(listOf("Bravo"), emptyDir, "Lobby", allowCreate = true),
        )
        // t4: both attempts spent and channel still absent → Unavailable once.
        val exhausted = p.reconcile(listOf("Bravo"), emptyDir, "Lobby", allowCreate = true)
        assertTrue(exhausted is MissionChannelProvisioner.Action.Unavailable)
        // Then quiet.
        assertEquals(
            MissionChannelProvisioner.Action.NoOp,
            p.reconcile(listOf("Bravo"), emptyDir, "Lobby", allowCreate = true),
        )
    }

    @Test
    fun `server that forbids creation reports unavailable without creating`() {
        val p = MissionChannelProvisioner()
        val action = p.reconcile(listOf("Bravo"), emptyDir, "Lobby", allowCreate = false)
        assertTrue(action is MissionChannelProvisioner.Action.Unavailable)
        assertEquals("Bravo", (action as MissionChannelProvisioner.Action.Unavailable).channelName)
        // Not repeated on subsequent ticks.
        assertEquals(
            MissionChannelProvisioner.Action.NoOp,
            p.reconcile(listOf("Bravo"), emptyDir, "Lobby", allowCreate = false),
        )
    }

    // ---- manual override ----

    @Test
    fun `operator leaving the mission channel yields control until the mission changes`() {
        val p = MissionChannelProvisioner()
        // Placed on the mission channel.
        assertEquals(
            MissionChannelProvisioner.Action.NoOp,
            p.reconcile(listOf("Bravo"), dirOf("Bravo"), joinedChannel = "Bravo", allowCreate = true),
        )
        // Operator moves to Lobby — provisioner must NOT drag them back.
        assertEquals(
            MissionChannelProvisioner.Action.NoOp,
            p.reconcile(listOf("Bravo"), dirOf("Bravo", "Lobby"), joinedChannel = "Lobby", allowCreate = true),
        )
        assertEquals(
            MissionChannelProvisioner.Action.NoOp,
            p.reconcile(listOf("Bravo"), dirOf("Bravo", "Lobby"), joinedChannel = "Lobby", allowCreate = true),
        )
    }

    @Test
    fun `switching missions clears the yield and drives to the new channel`() {
        val p = MissionChannelProvisioner()
        p.reconcile(listOf("Bravo"), dirOf("Bravo"), joinedChannel = "Bravo", allowCreate = true)
        // Operator moves off → yielded.
        p.reconcile(listOf("Bravo"), dirOf("Bravo", "Lobby"), joinedChannel = "Lobby", allowCreate = true)
        // New mission becomes primary → drive to its channel.
        val action =
            p.reconcile(listOf("Charlie", "Bravo"), dirOf("Bravo", "Lobby", "Charlie"), joinedChannel = "Lobby", allowCreate = true)
        assertEquals(MissionChannelProvisioner.Action.Join("Charlie"), action)
        assertEquals("Charlie", p.currentTarget())
    }

    @Test
    fun `primary mission is the first in the ordered list`() {
        val p = MissionChannelProvisioner()
        val action =
            p.reconcile(
                listOf("Alpha", "Bravo", "Charlie"),
                dirOf("Alpha", "Bravo", "Charlie"),
                joinedChannel = "Lobby",
                allowCreate = true,
            )
        assertEquals(MissionChannelProvisioner.Action.Join("Alpha"), action)
    }

    // ---- custom naming ----

    @Test
    fun `custom channel naming is applied and stays deterministic`() {
        val p = MissionChannelProvisioner(channelNameForMission = { "MSN/${it.trim()}" })
        val action = p.reconcile(listOf("Bravo"), dirOf("MSN/Bravo"), joinedChannel = "Lobby", allowCreate = true)
        assertEquals(MissionChannelProvisioner.Action.Join("MSN/Bravo"), action)
    }

    @Test
    fun `blank derived channel name is treated as no mission`() {
        val p = MissionChannelProvisioner(channelNameForMission = { "   " })
        assertEquals(
            MissionChannelProvisioner.Action.NoOp,
            p.reconcile(listOf("Bravo"), emptyDir, joinedChannel = "Lobby", allowCreate = true),
        )
    }

    @Test
    fun `mission ending returns to no-op without forcing a channel change`() {
        val p = MissionChannelProvisioner()
        p.reconcile(listOf("Bravo"), dirOf("Bravo"), joinedChannel = "Bravo", allowCreate = true)
        // Mission ends; operator stays where they are.
        assertEquals(
            MissionChannelProvisioner.Action.NoOp,
            p.reconcile(emptyList(), dirOf("Bravo"), joinedChannel = "Bravo", allowCreate = true),
        )
        assertNull(p.currentTarget())
    }
}
