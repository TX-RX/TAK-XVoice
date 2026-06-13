package com.atakmap.android.xv.transport.multicast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FailoverPolicyTest {
    private fun policy(
        rxWindow: Long = 3_000,
        hysteresis: Long = 10_000,
        interBurst: Long = 200,
    ) = FailoverPolicy(rxWindow, hysteresis, interBurst)

    @Test
    fun `starts on Mumble`() {
        val p = policy()
        assertEquals(FailoverPolicy.Leg.MUMBLE, p.active())
    }

    @Test
    fun `Mumble disconnect flips to multicast immediately`() {
        val p = policy()
        val d = p.evaluate(nowMs = 1_000, mumbleConnected = false)
        assertTrue("expected FlippedTo, got $d", d is FailoverPolicy.Decision.FlippedTo)
        assertEquals(FailoverPolicy.Leg.MULTICAST, d.active)
        assertEquals(FailoverPolicy.Reason.MUMBLE_DISCONNECTED, (d as FailoverPolicy.Decision.FlippedTo).reason)
    }

    @Test
    fun `Mumble connect alone doesn't trigger failback`() {
        val p = policy()
        // failover
        p.evaluate(nowMs = 1_000, mumbleConnected = false)
        // Mumble TCP says it's connected but we have no recent RX → not healthy.
        val d = p.evaluate(nowMs = 2_000, mumbleConnected = true)
        assertTrue(d is FailoverPolicy.Decision.NoChange)
        assertEquals(FailoverPolicy.Leg.MULTICAST, d.active)
    }

    @Test
    fun `failback waits the full hysteresis window`() {
        val p = policy(hysteresis = 10_000)
        // failover at t=1000
        p.evaluate(nowMs = 1_000, mumbleConnected = false)
        // Mumble connected + RX at t=2000 — healthy starts now.
        p.observeMumbleRx(nowMs = 2_000)
        val mid = p.evaluate(nowMs = 2_000, mumbleConnected = true)
        assertTrue("expected NoChange mid-hysteresis, got $mid", mid is FailoverPolicy.Decision.NoChange)
        assertEquals(FailoverPolicy.Leg.MULTICAST, mid.active)
        // Still mid-hysteresis at t=11_000 (only 9s elapsed).
        p.observeMumbleRx(nowMs = 10_500)
        val almost = p.evaluate(nowMs = 11_500, mumbleConnected = true)
        assertTrue(almost is FailoverPolicy.Decision.NoChange)
        // After hysteresis window: t=12_001 — 10001ms healthy.
        p.observeMumbleRx(nowMs = 12_000)
        val flip = p.evaluate(nowMs = 12_001, mumbleConnected = true)
        assertTrue("expected FlippedTo MUMBLE at t=12_001, got $flip", flip is FailoverPolicy.Decision.FlippedTo)
        assertEquals(FailoverPolicy.Leg.MUMBLE, flip.active)
    }

    @Test
    fun `failback hysteresis resets if Mumble bounces`() {
        val p = policy(hysteresis = 10_000)
        p.evaluate(nowMs = 1_000, mumbleConnected = false)
        // Healthy for 5s
        p.observeMumbleRx(nowMs = 2_000)
        p.evaluate(nowMs = 2_000, mumbleConnected = true)
        p.observeMumbleRx(nowMs = 6_000)
        p.evaluate(nowMs = 6_000, mumbleConnected = true)
        // Mumble bounces.
        p.evaluate(nowMs = 7_000, mumbleConnected = false)
        // Healthy again from t=8_000. Hysteresis restarts.
        p.observeMumbleRx(nowMs = 8_000)
        p.evaluate(nowMs = 8_000, mumbleConnected = true)
        // At t=15_000 only 7s into the new healthy window — still hold.
        p.observeMumbleRx(nowMs = 14_500)
        val mid = p.evaluate(nowMs = 15_000, mumbleConnected = true)
        assertTrue("expected NoChange after bounce reset", mid is FailoverPolicy.Decision.NoChange)
        // At t=18_001 we've been healthy for 10001ms — flip back.
        p.observeMumbleRx(nowMs = 18_000)
        val flip = p.evaluate(nowMs = 18_001, mumbleConnected = true)
        assertTrue(flip is FailoverPolicy.Decision.FlippedTo)
    }

    @Test
    fun `failback is held off mid-burst`() {
        val p = policy(hysteresis = 1_000, interBurst = 200)
        p.evaluate(nowMs = 0, mumbleConnected = false)
        p.observeMumbleRx(nowMs = 100)
        p.evaluate(nowMs = 100, mumbleConnected = true)
        // Operator is transmitting: TX frame just now.
        p.observeTxFrame(nowMs = 1_500)
        // Hysteresis window has passed BUT we sent a frame 50ms ago.
        p.observeMumbleRx(nowMs = 1_550)
        val mid = p.evaluate(nowMs = 1_550, mumbleConnected = true)
        assertTrue("expected NoChange while mid-burst, got $mid", mid is FailoverPolicy.Decision.NoChange)
        assertEquals(FailoverPolicy.Leg.MULTICAST, mid.active)
        // Inter-burst gap elapsed (200ms+ since last TX).
        p.observeMumbleRx(nowMs = 1_800)
        val flip = p.evaluate(nowMs = 1_800, mumbleConnected = true)
        assertTrue("expected flip after inter-burst gap, got $flip", flip is FailoverPolicy.Decision.FlippedTo)
    }

    @Test
    fun `RX gap longer than window resets healthy clock`() {
        val p = policy(rxWindow = 3_000, hysteresis = 5_000)
        p.evaluate(nowMs = 0, mumbleConnected = false)
        p.observeMumbleRx(nowMs = 1_000)
        p.evaluate(nowMs = 1_000, mumbleConnected = true)
        // 4s of silence — RX is now stale.
        val stale = p.evaluate(nowMs = 5_000, mumbleConnected = true)
        assertTrue(stale is FailoverPolicy.Decision.NoChange)
        // Fresh RX restarts hysteresis.
        p.observeMumbleRx(nowMs = 6_000)
        val resumed = p.evaluate(nowMs = 6_000, mumbleConnected = true)
        assertTrue(resumed is FailoverPolicy.Decision.NoChange)
        // Need 5s more healthy from t=6_000 → flip at t=11_001.
        p.observeMumbleRx(nowMs = 11_000)
        val flip = p.evaluate(nowMs = 11_001, mumbleConnected = true)
        assertTrue(flip is FailoverPolicy.Decision.FlippedTo)
    }

    @Test
    fun `re-failover from multicast back to multicast is a no-op`() {
        val p = policy()
        p.evaluate(nowMs = 0, mumbleConnected = false)
        // Stay on multicast on subsequent ticks while Mumble is still down.
        val d = p.evaluate(nowMs = 1_000, mumbleConnected = false)
        assertTrue(d is FailoverPolicy.Decision.NoChange)
        assertEquals(FailoverPolicy.Leg.MULTICAST, d.active)
    }

    @Test
    fun `Mumble disconnects again immediately re-failovers`() {
        val p = policy(hysteresis = 1_000)
        p.evaluate(nowMs = 0, mumbleConnected = false)
        p.observeMumbleRx(nowMs = 100)
        p.evaluate(nowMs = 100, mumbleConnected = true)
        p.observeMumbleRx(nowMs = 1_500)
        val flip = p.evaluate(nowMs = 1_500, mumbleConnected = true)
        assertTrue(flip is FailoverPolicy.Decision.FlippedTo)
        assertEquals(FailoverPolicy.Leg.MUMBLE, flip.active)
        // Mumble dies again at t=2_000.
        val refail = p.evaluate(nowMs = 2_000, mumbleConnected = false)
        assertTrue(refail is FailoverPolicy.Decision.FlippedTo)
        assertEquals(FailoverPolicy.Leg.MULTICAST, refail.active)
    }
}
