package com.atakmap.android.xv.provisioning

import com.atakmap.android.xv.transport.multicast.AeadCodec
import com.atakmap.android.xv.transport.multicast.ChannelMulticastConfig
import com.atakmap.android.xv.transport.multicast.CryptoPolicy
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// Robolectric for a real org.json — the mockable android.jar returns
// default values (nulls) from JSONObject, breaking parse + canonical
// byte-stability assertions.
@RunWith(RobolectricTestRunner::class)
class CommsPlanTest {
    private val psk = ByteArray(AeadCodec.KEY_BYTES) // all-zero test key, 32 bytes

    private fun samplePlan(): CommsPlan =
        CommsPlan(
            planId = "plan-0001",
            name = "Exercise Alpha",
            createdAtMs = 1_750_000_000_000L,
            serverIdentity = "tak.example.com",
            channels =
            listOf(
                CommsPlan.Channel(
                    displayName = "Ops 1",
                    config = ChannelMulticastConfig.defaultFor("ops-1"),
                ),
                CommsPlan.Channel(
                    displayName = "Bravo",
                    config =
                    ChannelMulticastConfig.defaultFor("bravo").copy(
                        cryptoPolicy = CryptoPolicy.REQUIRED,
                    ),
                    preSharedKey = psk,
                ),
            ),
        )

    @Test
    fun `sample plan is valid`() {
        assertNull(samplePlan().validate())
    }

    @Test
    fun `canonical json round-trips including the psk bytes`() {
        val plan = samplePlan()
        val back = CommsPlan.fromJson(plan.toCanonicalJson())
        assertEquals(plan, back)
        assertArrayEquals(psk, back.channels[1].preSharedKey)
    }

    @Test
    fun `canonical encoding is byte-stable - schema v1 known answer`() {
        // Known-answer pin: signatures and passphrase-KDF wrapping in
        // Phase C operate on these exact bytes. Editing this expected
        // string means bumping SCHEMA_VERSION, not adjusting the test.
        val expected =
            """{"v":1,"planId":"plan-0001","name":"Exercise Alpha","createdAtMs":1750000000000,""" +
                """"serverIdentity":"tak.example.com","channels":[""" +
                """{"displayName":"Ops 1","config":""" +
                """{"channel":"ops-1","mode":"FAILOVER","wireFormat":"XV_NATIVE","cryptoPolicy":"PREFERRED"}},""" +
                """{"displayName":"Bravo","config":""" +
                """{"channel":"bravo","mode":"FAILOVER","wireFormat":"XV_NATIVE","cryptoPolicy":"REQUIRED"},""" +
                """"psk":"${"A".repeat(43)}"}]}"""
        assertEquals(expected, samplePlan().toCanonicalJson())
        assertArrayEquals(expected.toByteArray(Charsets.UTF_8), samplePlan().toCanonicalBytes())
    }

    @Test
    fun `re-encoding a parsed plan reproduces the original bytes`() {
        // The property every carrier depends on: parse → re-emit is the
        // identity on canonical bytes.
        val json = samplePlan().toCanonicalJson()
        assertEquals(json, CommsPlan.fromJson(json).toCanonicalJson())
    }

    @Test
    fun `server identity is optional and omitted from json when absent`() {
        val plan = samplePlan().copy(serverIdentity = null)
        assertTrue(!plan.toCanonicalJson().contains("serverIdentity"))
        assertNull(CommsPlan.fromJson(plan.toCanonicalJson()).serverIdentity)
    }

    // ---- validate ----

    @Test
    fun `structural validation flags each broken field`() {
        assertNotNull(samplePlan().copy(planId = " ").validate())
        assertNotNull(samplePlan().copy(name = "").validate())
        assertNotNull(samplePlan().copy(channels = emptyList()).validate())

        val blankDisplay = samplePlan().channels[0].copy(displayName = " ")
        assertNotNull(samplePlan().copy(channels = listOf(blankDisplay)).validate())

        val shortKey = samplePlan().channels[1].copy(preSharedKey = ByteArray(16))
        assertNotNull(samplePlan().copy(channels = listOf(shortKey)).validate())
    }

    @Test
    fun `a channel config problem is reported against the offending channel`() {
        // VX_COMPAT with no pin is the config-level error.
        val broken =
            CommsPlan.Channel(
                displayName = "Mesh PTT",
                config =
                ChannelMulticastConfig.defaultFor("mesh-ptt").copy(
                    wireFormat = com.atakmap.android.xv.transport.multicast.WireFormat.VX_COMPAT,
                    cryptoPolicy = CryptoPolicy.CLEARTEXT,
                ),
            )
        val reason = samplePlan().copy(channels = listOf(broken)).validate()
        assertNotNull(reason)
        assertTrue("expected channel attribution in '$reason'", reason!!.startsWith("channel 'Mesh PTT':"))
    }

    // ---- fromJson failure modes (imports fail loudly) ----

    @Test
    fun `unknown schema versions are rejected at import`() {
        val v2 = samplePlan().toCanonicalJson().replaceFirst("\"v\":1", "\"v\":2")
        assertThrows(IllegalArgumentException::class.java) { CommsPlan.fromJson(v2) }
        assertThrows(IllegalArgumentException::class.java) { CommsPlan.fromJson("""{"planId":"x"}""") }
    }

    @Test
    fun `non-json and structurally hollow input are rejected`() {
        assertThrows(IllegalArgumentException::class.java) { CommsPlan.fromJson("not a plan") }
        assertThrows(IllegalArgumentException::class.java) { CommsPlan.fromJson("""{"v":1,"planId":"p","name":"n"}""") }
        assertThrows(IllegalArgumentException::class.java) {
            CommsPlan.fromJson("""{"v":1,"planId":"p","name":"n","createdAtMs":1,"channels":[{"displayName":"x"}]}""")
        }
    }

    @Test
    fun `a config this build cannot parse fails the whole import`() {
        // Unlike per-channel stored overrides (which degrade quietly),
        // an operator-initiated import of a half-understood plan must
        // refuse rather than load a subset.
        val json =
            """{"v":1,"planId":"p","name":"n","createdAtMs":1,"channels":[{"displayName":"x","config":""" +
                """{"channel":"ops-1","mode":"TELEPATHY","wireFormat":"XV_NATIVE","cryptoPolicy":"PREFERRED"}}]}"""
        assertThrows(IllegalArgumentException::class.java) { CommsPlan.fromJson(json) }
    }

    @Test
    fun `invalid psk encodings and sizes are rejected`() {
        fun planWithPsk(psk: String): String =
            """{"v":1,"planId":"p","name":"n","createdAtMs":1,"channels":[{"displayName":"x","config":""" +
                """{"channel":"ops-1","mode":"FAILOVER","wireFormat":"XV_NATIVE","cryptoPolicy":"PREFERRED"},"psk":"$psk"}]}"""
        assertThrows(IllegalArgumentException::class.java) { CommsPlan.fromJson(planWithPsk("!!!not-base64!!!")) }
        // Valid base64url, wrong key length (16 bytes).
        val short = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(16))
        assertThrows(IllegalArgumentException::class.java) { CommsPlan.fromJson(planWithPsk(short)) }
    }

    @Test
    fun `display name defaults to the config channel name when missing`() {
        val json =
            """{"v":1,"planId":"p","name":"n","createdAtMs":1,"channels":[{"config":""" +
                """{"channel":"ops-1","mode":"FAILOVER","wireFormat":"XV_NATIVE","cryptoPolicy":"PREFERRED"}}]}"""
        assertEquals("ops-1", CommsPlan.fromJson(json).channels[0].displayName)
    }

    // ---- guest-plan expiry / schema v2 (#95) ----

    @Test
    fun `no expiry still emits schema v1`() {
        assertTrue(samplePlan().toCanonicalJson().startsWith("""{"v":1,"""))
    }

    @Test
    fun `an expiry promotes the encoding to v2 and round-trips`() {
        val plan = samplePlan().copy(notAfterMs = 1_750_432_000_000L)
        val json = plan.toCanonicalJson()
        assertTrue(json.startsWith("""{"v":2,"""))
        assertTrue(json.contains(""""notAfter":1750432000000"""))
        assertEquals(plan, CommsPlan.fromJson(json))
    }

    @Test
    fun `v2 places notAfter right after createdAtMs - known answer`() {
        val plan =
            CommsPlan(
                planId = "p",
                name = "n",
                createdAtMs = 1,
                serverIdentity = null,
                channels = listOf(CommsPlan.Channel("Ops 1", ChannelMulticastConfig.defaultFor("ops-1"))),
                notAfterMs = 999,
            )
        assertEquals(
            """{"v":2,"planId":"p","name":"n","createdAtMs":1,"notAfter":999,"channels":[""" +
                """{"displayName":"Ops 1","config":""" +
                """{"channel":"ops-1","mode":"FAILOVER","wireFormat":"XV_NATIVE","cryptoPolicy":"PREFERRED"}}]}""",
            plan.toCanonicalJson(),
        )
    }

    @Test
    fun `isExpired is false before and true at or after notAfter`() {
        val plan = samplePlan().copy(notAfterMs = 1_000L)
        assertTrue(!plan.isExpired(999))
        assertTrue(plan.isExpired(1_000))
        assertTrue(plan.isExpired(1_001))
    }

    @Test
    fun `a plan with no expiry never expires`() {
        assertTrue(!samplePlan().isExpired(Long.MAX_VALUE))
    }

    @Test
    fun `import enforces expiry when a clock is supplied`() {
        val plan = samplePlan().copy(notAfterMs = 5_000L)
        val json = plan.toCanonicalJson()
        // Before expiry: imports fine.
        assertNotNull(CommsPlan.fromJson(json, nowMs = 4_000L))
        // After expiry: refused.
        assertThrows(IllegalArgumentException::class.java) {
            CommsPlan.fromJson(json, nowMs = 6_000L)
        }
        // No clock: parse-only, expiry not enforced.
        assertNotNull(CommsPlan.fromJson(json))
    }

    @Test
    fun `expiryFrom clamps the ttl to the 5-day ceiling`() {
        val created = 1_000_000L
        val fiveDays = com.atakmap.android.xv.transport.multicast.KeyLifecyclePolicy.HARD_CEILING_MS
        // A one-day ttl is honored.
        assertEquals(created + fiveDays / 5, CommsPlan.expiryFrom(created, fiveDays / 5))
        // A 30-day request is clamped to 5 days.
        assertEquals(created + fiveDays, CommsPlan.expiryFrom(created, 30L * 24 * 60 * 60 * 1000))
    }

    @Test
    fun `an unknown schema version is still rejected`() {
        val json =
            """{"v":9,"planId":"p","name":"n","createdAtMs":1,"channels":[{"config":""" +
                """{"channel":"ops-1","mode":"FAILOVER","wireFormat":"XV_NATIVE","cryptoPolicy":"PREFERRED"}}]}"""
        assertThrows(IllegalArgumentException::class.java) { CommsPlan.fromJson(json) }
    }
}
