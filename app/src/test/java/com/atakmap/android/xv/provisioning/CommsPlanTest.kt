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
        // OPENMANET_COMPAT with no pin is the config-level error.
        val broken =
            CommsPlan.Channel(
                displayName = "Mesh PTT",
                config =
                ChannelMulticastConfig.defaultFor("mesh-ptt").copy(
                    wireFormat = com.atakmap.android.xv.transport.multicast.WireFormat.OPENMANET_COMPAT,
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
}
