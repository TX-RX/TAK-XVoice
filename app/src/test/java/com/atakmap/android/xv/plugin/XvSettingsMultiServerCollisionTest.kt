package com.atakmap.android.xv.plugin

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.atakmap.android.xv.transport.multicast.ChannelMulticastConfig
import com.atakmap.android.xv.transport.multicast.CryptoPolicy
import com.atakmap.android.xv.transport.multicast.MulticastMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * CHARACTERIZATION test — documents CURRENT (INCORRECT) behavior.
 *
 * Persisted per-channel voice state on [XvSettings] is keyed by the
 * canonical channel NAME only, never by (server, channel). Channel
 * identity is really the pair (server, channel) — the same channel name
 * ("ops") on two different TAK servers is two different multicast
 * channels with potentially different group/port overrides, crypto
 * posture, and server provenance — but the stores below collapse them
 * onto one key, so the second server's write silently overwrites the
 * first server's state.
 *
 * These tests assert the buggy overwrite ON PURPOSE. They are a
 * regression fence around known debt, not a specification of desired
 * behavior. When the (serverIdentity, canonicalChannelName) rekey lands
 * (see docs/multi-server-keying-migration.md), each `assertEquals`
 * marked "CURRENT (incorrect)" flips to the "IDEAL" assertion shown
 * beside it, and this class gets renamed / retired.
 *
 * Robolectric gives a real SharedPreferences-backed [Context] — the
 * collision is a property of the on-disk key layout, so a mock would
 * only exercise the mock, not the persistence semantics under test.
 */
@RunWith(RobolectricTestRunner::class)
class XvSettingsMultiServerCollisionTest {
    private lateinit var settings: XvSettings

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        ctx.getSharedPreferences(XvSettings.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .apply()
        settings = XvSettings { ctx.getSharedPreferences(XvSettings.PREFS_NAME, Context.MODE_PRIVATE) }
    }

    @Test
    fun `CURRENT (incorrect) - multicast config for the same channel name on a second server overwrites the first`() {
        // Server 1 (tak.example): "ops" pinned to one group/port, ALWAYS mode.
        val onServer1 =
            ChannelMulticastConfig(
                channelName = CHANNEL,
                mode = MulticastMode.ALWAYS,
                pinnedGroup = "239.10.10.10",
                pinnedPort = 5010,
                cryptoPolicy = CryptoPolicy.REQUIRED,
            )
        settings.persistChannelMulticastConfig(onServer1)
        assertEquals(onServer1.pinnedGroup, settings.channelMulticastConfigFor(CHANNEL)?.pinnedGroup)

        // Server 2 (tak2.example): DIFFERENT "ops" — different group/port,
        // FAILOVER mode. In reality this is a distinct multicast channel.
        val onServer2 =
            ChannelMulticastConfig(
                channelName = CHANNEL,
                mode = MulticastMode.FAILOVER,
                pinnedGroup = "239.20.20.20",
                pinnedPort = 5020,
                cryptoPolicy = CryptoPolicy.PREFERRED,
            )
        settings.persistChannelMulticastConfig(onServer2)

        val readBack = settings.channelMulticastConfigFor(CHANNEL)

        // CURRENT (incorrect): only one config survives; server 2 clobbered
        // server 1 because both hash to the canonical name "ops".
        assertEquals("239.20.20.20", readBack?.pinnedGroup)
        assertEquals(5020, readBack?.pinnedPort)
        assertEquals(MulticastMode.FAILOVER, readBack?.mode)
        // Server 1's pin is gone — the overwrite, made explicit.
        assertNotEquals("239.10.10.10", readBack?.pinnedGroup)
        assertEquals(1, settings.channelMulticastConfigs().size)

        // IDEAL (post-rekey): both configs coexist, looked up by
        // (serverIdentity, channel). Enable once the migration lands.
        // assertEquals("239.10.10.10", settings.channelMulticastConfigFor("tak.example", CHANNEL)?.pinnedGroup)
        // assertEquals("239.20.20.20", settings.channelMulticastConfigFor("tak2.example", CHANNEL)?.pinnedGroup)
        // assertEquals(2, settings.channelMulticastConfigs().size)
    }

    @Test
    fun `CURRENT (incorrect) - server tag for the same channel name is overwritten by the second server`() {
        // "ops" seen on tak.example, then the same name seen on tak2.example.
        settings.persistChannelServer(CHANNEL, "tak.example")
        assertEquals("tak.example", settings.channelServer(CHANNEL))

        settings.persistChannelServer(CHANNEL, "tak2.example")

        // CURRENT (incorrect): the provenance tag can hold only ONE server
        // per channel name, so the first server's provenance is lost.
        assertEquals("tak2.example", settings.channelServer(CHANNEL))
        assertNotEquals("tak.example", settings.channelServer(CHANNEL))

        // IDEAL (post-rekey): both provenances coexist; a channel name maps
        // to a SET of servers, not a single last-writer-wins host.
        // assertTrue(settings.serversForChannel(CHANNEL).containsAll(listOf("tak.example", "tak2.example")))
    }

    @Test
    fun `CURRENT (incorrect) - crypto policy for the same channel name is overwritten by the second server`() {
        // Server 1 demands encryption for "ops"; server 2's "ops" is a
        // cleartext interop channel of the same name.
        settings.persistCryptoPolicy(CHANNEL, CryptoPolicy.REQUIRED)
        assertEquals(CryptoPolicy.REQUIRED, settings.channelCryptoPolicyFor(CHANNEL))

        settings.persistCryptoPolicy(CHANNEL, CryptoPolicy.CLEARTEXT)

        // CURRENT (incorrect): server 2's cleartext posture now applies to
        // BOTH servers' "ops" — a crypto-posture bleed across deployments,
        // the most safety-relevant facet of this collision.
        assertEquals(CryptoPolicy.CLEARTEXT, settings.channelCryptoPolicyFor(CHANNEL))
        assertNotEquals(CryptoPolicy.REQUIRED, settings.channelCryptoPolicyFor(CHANNEL))
    }

    companion object {
        // Canonical channel name shared by two unrelated deployments.
        // Real hostnames use the sanctioned tak.example / tak2.example
        // placeholders per CLAUDE.md.
        private const val CHANNEL = "ops"
    }
}
