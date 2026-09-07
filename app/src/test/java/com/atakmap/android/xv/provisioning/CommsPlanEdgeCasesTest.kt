package com.atakmap.android.xv.provisioning

import com.atakmap.android.xv.transport.multicast.ChannelMulticastConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Edge-case characterization for [CommsPlan] — expiry boundary and
 * duplicate-canonical-name handling. These PIN the behavior the code
 * actually implements today; a change in behavior should make one of
 * these fail on purpose, not slip through.
 *
 * Robolectric for a real org.json (the mockable android.jar returns
 * default nulls from JSONObject, which corrupts parse/round-trip
 * assertions), matching CommsPlanTest / CommsPlanCarrierTest.
 */
@RunWith(RobolectricTestRunner::class)
class CommsPlanEdgeCasesTest {
    private fun planExpiringAt(notAfterMs: Long): CommsPlan =
        CommsPlan(
            planId = "plan-exp",
            name = "Exercise Alpha",
            createdAtMs = 1_000L,
            serverIdentity = null,
            channels = listOf(CommsPlan.Channel("Ops 1", ChannelMulticastConfig.defaultFor("ops-1"))),
            notAfterMs = notAfterMs,
        )

    // ---- expiry boundary (isExpired) ----

    // PINNED: isExpired() implements `nowMs >= notAfterMs`, i.e. the
    // notAfter instant is INCLUSIVE — a plan is already expired at the
    // exact millisecond notAfterMs, not one ms later. (CommsPlan.kt:112)
    @Test
    fun `expiry boundary - now equal to notAfter is already expired (inclusive)`() {
        val plan = planExpiringAt(10_000L)
        assertFalse("one ms before notAfter: still live", plan.isExpired(9_999L))
        assertTrue("exactly at notAfter: expired (boundary is inclusive)", plan.isExpired(10_000L))
        assertTrue("one ms after notAfter: expired", plan.isExpired(10_001L))
    }

    @Test
    fun `expiry boundary - notAfter of zero expires at now zero`() {
        // notAfterMs = 0 is a non-null expiry, so the schema is "expiring"
        // and now >= 0 is always expired. Documents that 0 is a real
        // (immediately-expired) value, not a "no expiry" sentinel — null
        // is the only no-expiry marker.
        val plan = planExpiringAt(0L)
        assertTrue(plan.isExpired(0L))
        assertFalse("negative clock is before the boundary", plan.isExpired(-1L))
    }

    @Test
    fun `null expiry never expires even at Long MAX`() {
        val plan = planExpiringAt(10_000L).copy(notAfterMs = null)
        assertFalse(plan.isExpired(Long.MAX_VALUE))
    }

    @Test
    fun `import at the exact expiry instant is refused (boundary is inclusive)`() {
        // The same inclusive boundary is enforced at import when a clock
        // is supplied: nowMs == notAfterMs must be rejected, not admitted.
        val json = planExpiringAt(10_000L).toCanonicalJson()
        assertNotNull(CommsPlan.fromJson(json, nowMs = 9_999L))
        assertThrows(IllegalArgumentException::class.java) {
            CommsPlan.fromJson(json, nowMs = 10_000L)
        }
    }

    // ---- duplicate canonical channel names ----

    // PINNED: validate() has NO duplicate-name check. Two channels whose
    // config channel names canonicalize to the SAME value (NFC + trim +
    // lowercase, see MulticastGroupDerivation.canonicalChannelName) are
    // BOTH accepted and BOTH retained — no dedup, no error, no last-wins.
    // NOTE: possible bug — both channels then resolve to the same derived
    // multicast endpoint (defaultFor stores the canonical name, and
    // resolveEndpoint derives from it), so a plan can silently carry two
    // colliding channels. Current behavior is "allowed"; this test pins
    // that so a future dedup/reject change trips here on purpose.
    @Test
    fun `two channels with the same canonical name are both kept and validate passes`() {
        // "Ops-1" and "OPS-1" both canonicalize to "ops-1".
        val a = CommsPlan.Channel("Alpha label", ChannelMulticastConfig.defaultFor("Ops-1"))
        val b = CommsPlan.Channel("Bravo label", ChannelMulticastConfig.defaultFor("OPS-1"))
        assertEquals("precondition: names collide after canonicalization", a.config.channelName, b.config.channelName)

        val plan =
            CommsPlan(
                planId = "plan-dup",
                name = "Dup canonical",
                createdAtMs = 1L,
                channels = listOf(a, b),
            )

        assertNull("no dedup / no rejection of colliding canonical names", plan.validate())
        assertEquals("both channels retained (not deduped, not last-wins)", 2, plan.channels.size)

        // The collision survives a canonical round-trip too.
        val back = CommsPlan.fromJson(plan.toCanonicalJson())
        assertEquals(2, back.channels.size)
        assertEquals(back.channels[0].config.channelName, back.channels[1].config.channelName)
        assertNotNull(back)
    }

    @Test
    fun `identical duplicate channels are still both kept`() {
        val ch = CommsPlan.Channel("Ops 1", ChannelMulticastConfig.defaultFor("ops-1"))
        val plan =
            CommsPlan(
                planId = "plan-dup2",
                name = "Exact dup",
                createdAtMs = 1L,
                channels = listOf(ch, ch),
            )
        assertNull(plan.validate())
        assertEquals(2, plan.channels.size)
    }
}
