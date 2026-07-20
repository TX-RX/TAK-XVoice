package com.atakmap.android.xv.provisioning

import com.atakmap.android.xv.transport.multicast.CryptoPolicy
import com.atakmap.android.xv.transport.multicast.MulticastGroupDerivation
import com.atakmap.android.xv.transport.multicast.WireFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MeshChannelSpecTest {
    // ---- the "just name it" case (no pinning) ----

    @Test
    fun `named channel with no pin derives its endpoint and auto-keys`() {
        val r = MeshChannelSpec.build("Ops-1", group = null, port = null, WireFormat.XV_NATIVE, CryptoPolicy.PREFERRED)
        assertNull(r.error)
        assertNotNull(r.config)
        assertNull("no manual pin", r.config!!.pinnedGroup)
        assertNull(r.config.pinnedPort)
        assertEquals(MulticastGroupDerivation.canonicalChannelName("Ops-1"), r.config.channelName)
        assertTrue("encrypted posture generates a key", r.autoKey)
    }

    @Test
    fun `blank name is rejected`() {
        val r = MeshChannelSpec.build("  ", group = null, port = null, WireFormat.XV_NATIVE, CryptoPolicy.PREFERRED)
        assertNotNull(r.error)
        assertNull(r.config)
    }

    @Test
    fun `blank group and port strings are treated as derive`() {
        val r = MeshChannelSpec.build("Ops-1", group = "  ", port = "", WireFormat.XV_NATIVE, CryptoPolicy.PREFERRED)
        assertNull(r.error)
        assertNull(r.config!!.pinnedGroup)
    }

    // ---- the interop / pin case (path 3) ----

    @Test
    fun `openmanet interop channel pins group and port with cleartext`() {
        val r =
            MeshChannelSpec.build(
                "mesh-ptt",
                group = "224.0.0.1",
                port = "5007",
                WireFormat.VX_COMPAT,
                CryptoPolicy.CLEARTEXT,
            )
        assertNull(r.error)
        assertEquals("224.0.0.1", r.config!!.pinnedGroup)
        assertEquals(5007, r.config.pinnedPort)
        assertFalse("cleartext generates no key", r.autoKey)
    }

    @Test
    fun `group without port is rejected`() {
        val r = MeshChannelSpec.build("x", group = "239.1.2.3", port = null, WireFormat.XV_NATIVE, CryptoPolicy.PREFERRED)
        assertNotNull(r.error)
    }

    @Test
    fun `port without group is rejected`() {
        val r = MeshChannelSpec.build("x", group = null, port = "5007", WireFormat.XV_NATIVE, CryptoPolicy.PREFERRED)
        assertNotNull(r.error)
    }

    @Test
    fun `non-numeric port is rejected with a clear message`() {
        val r = MeshChannelSpec.build("x", group = "239.1.2.3", port = "abc", WireFormat.XV_NATIVE, CryptoPolicy.PREFERRED)
        assertNotNull(r.error)
        assertTrue(r.error!!.contains("abc"))
    }

    @Test
    fun `out-of-range port is rejected via config validation`() {
        val r = MeshChannelSpec.build("x", group = "239.1.2.3", port = "70000", WireFormat.XV_NATIVE, CryptoPolicy.PREFERRED)
        assertNotNull(r.error)
    }

    @Test
    fun `non-multicast pinned group is rejected via config validation`() {
        val r = MeshChannelSpec.build("x", group = "192.0.2.10", port = "5007", WireFormat.XV_NATIVE, CryptoPolicy.PREFERRED)
        assertNotNull(r.error)
    }

    @Test
    fun `openmanet compat without a pin is rejected`() {
        val r = MeshChannelSpec.build("x", group = null, port = null, WireFormat.VX_COMPAT, CryptoPolicy.CLEARTEXT)
        assertNotNull(r.error)
    }

    @Test
    fun `openmanet compat with encryption is rejected`() {
        val r =
            MeshChannelSpec.build(
                "x",
                group = "224.0.0.1",
                port = "5007",
                WireFormat.VX_COMPAT,
                CryptoPolicy.REQUIRED,
            )
        assertNotNull(r.error)
    }

    @Test
    fun `required crypto auto-keys`() {
        val r = MeshChannelSpec.build("secure-1", group = null, port = null, WireFormat.XV_NATIVE, CryptoPolicy.REQUIRED)
        assertNull(r.error)
        assertTrue(r.autoKey)
    }
}
