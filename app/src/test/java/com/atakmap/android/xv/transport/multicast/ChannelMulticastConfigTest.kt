package com.atakmap.android.xv.transport.multicast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// Robolectric for a real org.json — the mockable android.jar returns
// default values (nulls) from JSONObject, silently corrupting the
// canonical-JSON assertions this class exists to pin.
@RunWith(RobolectricTestRunner::class)
class ChannelMulticastConfigTest {
    private val server = ServerIdentity.fromHostname("tak.example.com")

    // ---- defaults ----

    @Test
    fun `defaultFor is a valid auto-derived FAILOVER leg`() {
        val cfg = ChannelMulticastConfig.defaultFor("Ops-1")
        assertEquals(MulticastMode.FAILOVER, cfg.mode)
        assertEquals(WireFormat.XV_NATIVE, cfg.wireFormat)
        assertEquals(CryptoPolicy.PREFERRED, cfg.cryptoPolicy)
        assertNull(cfg.pinnedGroup)
        assertNull(cfg.pinnedPort)
        assertNull(cfg.validate())
    }

    @Test
    fun `defaultFor canonicalizes the channel name so lookups cannot fork on spelling`() {
        assertEquals("ops-1", ChannelMulticastConfig.defaultFor("  OPS-1 ").channelName)
        assertEquals(
            ChannelMulticastConfig.defaultFor("ops-1"),
            ChannelMulticastConfig.defaultFor("Ops-1"),
        )
    }

    // ---- validate ----

    @Test
    fun `blank channel name is invalid`() {
        assertNotNull(ChannelMulticastConfig(channelName = "  ").validate())
    }

    @Test
    fun `pinned group and port must be set together`() {
        assertNotNull(ChannelMulticastConfig(channelName = "ops-1", pinnedGroup = "239.1.2.3").validate())
        assertNotNull(ChannelMulticastConfig(channelName = "ops-1", pinnedPort = 5007).validate())
        assertNull(
            ChannelMulticastConfig(channelName = "ops-1", pinnedGroup = "239.1.2.3", pinnedPort = 5007).validate(),
        )
    }

    @Test
    fun `pinned port must be a real UDP port`() {
        fun withPort(p: Int) = ChannelMulticastConfig(channelName = "ops-1", pinnedGroup = "239.1.2.3", pinnedPort = p)
        assertNotNull(withPort(0).validate())
        assertNotNull(withPort(65536).validate())
        assertNull(withPort(1).validate())
        assertNull(withPort(65535).validate())
    }

    @Test
    fun `pinned group must be an ipv4 multicast address`() {
        fun withGroup(g: String) = ChannelMulticastConfig(channelName = "ops-1", pinnedGroup = g, pinnedPort = 5007)
        // Class D boundaries.
        assertNull(withGroup("224.0.0.0").validate())
        assertNull(withGroup("239.255.255.255").validate())
        assertNotNull(withGroup("223.255.255.255").validate())
        assertNotNull(withGroup("240.0.0.0").validate())
        // Unicast / structurally broken inputs.
        assertNotNull(withGroup("192.0.2.10").validate())
        assertNotNull(withGroup("239.1.2").validate())
        assertNotNull(withGroup("239.1.2.3.4").validate())
        assertNotNull(withGroup("239.1.2.256").validate())
        assertNotNull(withGroup("not-an-address").validate())
    }

    @Test
    fun `openmanet compat demands an explicit pin`() {
        val cfg =
            ChannelMulticastConfig(
                channelName = "mesh-ptt",
                wireFormat = WireFormat.OPENMANET_COMPAT,
                cryptoPolicy = CryptoPolicy.CLEARTEXT,
            )
        assertNotNull(cfg.validate())
    }

    @Test
    fun `openmanet compat demands cleartext`() {
        fun withCrypto(c: CryptoPolicy) =
            ChannelMulticastConfig(
                channelName = "mesh-ptt",
                wireFormat = WireFormat.OPENMANET_COMPAT,
                cryptoPolicy = c,
                pinnedGroup = "224.0.0.1",
                pinnedPort = 5007,
            )
        assertNotNull(withCrypto(CryptoPolicy.REQUIRED).validate())
        assertNotNull(withCrypto(CryptoPolicy.PREFERRED).validate())
        assertNull(withCrypto(CryptoPolicy.CLEARTEXT).validate())
    }

    // ---- endpoint resolution ----

    @Test
    fun `resolveEndpoint prefers the operator pin`() {
        val cfg = ChannelMulticastConfig(channelName = "ops-1", pinnedGroup = "224.0.0.1", pinnedPort = 5007)
        assertEquals(MulticastEndpoint("224.0.0.1", 5007), cfg.resolveEndpoint(server))
    }

    @Test
    fun `resolveEndpoint falls back to the v1 derivation when unpinned`() {
        val cfg = ChannelMulticastConfig.defaultFor("Ops-1")
        assertEquals(MulticastGroupDerivation.derive(server, "Ops-1"), cfg.resolveEndpoint(server))
    }

    // ---- canonical JSON ----

    @Test
    fun `toJson is byte-stable with the frozen v1 field order`() {
        // Known-answer pin: comms-plan signatures depend on this exact
        // string. If this test needs editing, that is a schema change.
        assertEquals(
            """{"channel":"ops-1","mode":"FAILOVER","wireFormat":"XV_NATIVE","cryptoPolicy":"PREFERRED"}""",
            ChannelMulticastConfig.defaultFor("ops-1").toJson(),
        )
        assertEquals(
            """{"channel":"mesh-ptt","mode":"ALWAYS","wireFormat":"OPENMANET_COMPAT","cryptoPolicy":"CLEARTEXT","group":"224.0.0.1","port":5007}""",
            ChannelMulticastConfig(
                channelName = "mesh-ptt",
                mode = MulticastMode.ALWAYS,
                wireFormat = WireFormat.OPENMANET_COMPAT,
                cryptoPolicy = CryptoPolicy.CLEARTEXT,
                pinnedGroup = "224.0.0.1",
                pinnedPort = 5007,
            ).toJson(),
        )
    }

    @Test
    fun `json round-trips without and with pins`() {
        val derived = ChannelMulticastConfig.defaultFor("ops-1")
        assertEquals(derived, ChannelMulticastConfig.fromJson(derived.toJson()))

        val pinned =
            ChannelMulticastConfig(
                channelName = "mesh-ptt",
                mode = MulticastMode.ALWAYS,
                wireFormat = WireFormat.OPENMANET_COMPAT,
                cryptoPolicy = CryptoPolicy.CLEARTEXT,
                pinnedGroup = "224.0.0.1",
                pinnedPort = 5007,
            )
        assertEquals(pinned, ChannelMulticastConfig.fromJson(pinned.toJson()))
    }

    @Test
    fun `json escaping survives hostile channel names`() {
        val cfg = ChannelMulticastConfig.defaultFor("""ops "quoted" \ slash""")
        val parsed = ChannelMulticastConfig.fromJson(cfg.toJson())
        assertEquals(cfg, parsed)
    }

    @Test
    fun `fromJson degrades to null instead of crashing`() {
        // A NEWER build's enum value must read as "no override".
        assertNull(
            ChannelMulticastConfig.fromJson(
                """{"channel":"ops-1","mode":"TELEPATHY","wireFormat":"XV_NATIVE","cryptoPolicy":"PREFERRED"}""",
            ),
        )
        // Missing required field.
        assertNull(ChannelMulticastConfig.fromJson("""{"channel":"ops-1"}"""))
        // Not JSON at all.
        assertNull(ChannelMulticastConfig.fromJson("not json"))
    }

    @Test
    fun `fromJson accepts a validatable but unvalidated config`() {
        // fromJson is parse-only; validation is the caller's second
        // step. A structurally sound but semantically broken override
        // (compat without a pin) must parse so the UI can show WHY it
        // is rejected instead of silently reverting to defaults.
        val parsed =
            ChannelMulticastConfig.fromJson(
                """{"channel":"mesh-ptt","mode":"ALWAYS","wireFormat":"OPENMANET_COMPAT","cryptoPolicy":"CLEARTEXT"}""",
            )
        assertNotNull(parsed)
        assertTrue(parsed!!.validate() != null)
    }
}
