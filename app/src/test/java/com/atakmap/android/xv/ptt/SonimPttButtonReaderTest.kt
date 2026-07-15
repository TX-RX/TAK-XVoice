package com.atakmap.android.xv.ptt

import android.content.Intent
import com.atakmap.android.xv.audio.PttDispatcher
import com.atakmap.android.xv.audio.PttSource
import com.atakmap.android.xv.audio.StatusTones
import com.atakmap.android.xv.audio.TptPlayer
import com.atakmap.android.xv.audio.TxController
import com.atakmap.android.xv.util.SonimHardwareButtons
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Coverage for [SonimPttButtonReader]'s wiring to [PttDispatcher].
 *
 * The reader translates the Sonim framework's
 * `com.sonim.intent.action.PTT_KEY_DOWN` / `_UP` broadcasts into PTT
 * down / up edges tagged with [PttSource.SONIM_PTT]. Robolectric
 * dispatches a synthetic broadcast against a registered receiver and
 * verifies the dispatcher sees the correct edges.
 *
 * We do NOT rely on any Sonim-only APIs — the mechanism is a plain
 * `BroadcastReceiver` filtering on the framework's action strings, so
 * a Robolectric context is a fully-representative harness.
 */
@RunWith(RobolectricTestRunner::class)
class SonimPttButtonReaderTest {
    private lateinit var txController: TxController
    private lateinit var statusTones: StatusTones
    private lateinit var tptPlayer: TptPlayer
    private lateinit var dispatcher: PttDispatcher
    private lateinit var reader: SonimPttButtonReader

    @Before
    fun setup() {
        txController = mockk(relaxed = true)
        statusTones = mockk(relaxed = true)
        tptPlayer = mockk(relaxed = true)
        dispatcher =
            PttDispatcher(
                txController = txController,
                statusTones = statusTones,
                latchedModeEnabled = { false },
                momentaryTimeoutSec = { 0 },
                latchedTimeoutSec = { 0 },
                tptPlayer = tptPlayer,
            )
        reader =
            SonimPttButtonReader(
                context = RuntimeEnvironment.getApplication(),
                onEdge = { isDown, source ->
                    if (isDown) dispatcher.down(slot = 0, source = source) else dispatcher.up(slot = 0, source = source)
                },
            )
    }

    private fun pressBroadcast(): Intent = Intent(SonimHardwareButtons.ACTION_PTT_KEY_DOWN)

    private fun releaseBroadcast(): Intent = Intent(SonimHardwareButtons.ACTION_PTT_KEY_UP)

    @Test
    fun `start registers the receiver and press-broadcast fires slot-0 TX`() {
        val ok = reader.start()
        assertTrue("reader.start() should register the receiver in a Robolectric context", ok)
        assertTrue(reader.isRunning())
        RuntimeEnvironment.getApplication().sendBroadcast(pressBroadcast())
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        verify(exactly = 1) { txController.start(slot = 0) }
    }

    @Test
    fun `press then release fires start then stop`() {
        reader.start()
        val app = RuntimeEnvironment.getApplication()
        app.sendBroadcast(pressBroadcast())
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        app.sendBroadcast(releaseBroadcast())
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        verify(exactly = 1) { txController.start(slot = 0) }
        verify(exactly = 1) { txController.stop() }
    }

    @Test
    fun `stop unregisters the receiver — later broadcasts are dropped`() {
        reader.start()
        reader.stop()
        assertFalse(reader.isRunning())
        RuntimeEnvironment.getApplication().sendBroadcast(pressBroadcast())
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        verify(exactly = 0) { txController.start(slot = 0) }
    }

    @Test
    fun `SOS broadcast is ignored — only PTT actions drive TX from this reader`() {
        // The Emergency / SOS broadcast is handled by
        // SonimEmergencyButtonReader, not this reader. If the PTT
        // reader's filter matched too widely we'd double-fire on
        // the wrong source. The IntentFilter here explicitly
        // subscribes to only the two PTT actions, so an SOS
        // broadcast delivered to this reader's process is dropped
        // by the filter itself. This test exists as belt-and-
        // suspenders in case a future refactor widens the filter.
        reader.start()
        val emergency = Intent(SonimHardwareButtons.ACTION_SOS_DOWN)
        RuntimeEnvironment.getApplication().sendBroadcast(emergency)
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        verify(exactly = 0) { txController.start(slot = 0) }
    }

    @Test
    fun `double start is a safe no-op — receiver still delivers exactly one event`() {
        assertTrue(reader.start())
        assertTrue("second start() should also return true (already-registered path)", reader.start())
        RuntimeEnvironment.getApplication().sendBroadcast(pressBroadcast())
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        // The receiver was registered only once, so exactly one TX
        // engage — a duplicate registration would double-fire.
        verify(exactly = 1) { txController.start(slot = 0) }
    }

    @Test
    fun `stop before start is a safe no-op`() {
        reader.stop()
        assertFalse(reader.isRunning())
        assertTrue(reader.start())
        assertTrue(reader.isRunning())
    }

    @Test
    fun `edges are tagged with SONIM_PTT source`() {
        val seenSources = mutableListOf<Pair<Boolean, PttSource>>()
        val r =
            SonimPttButtonReader(
                context = RuntimeEnvironment.getApplication(),
                onEdge = { isDown, source -> seenSources.add(isDown to source) },
            )
        r.start()
        val app = RuntimeEnvironment.getApplication()
        app.sendBroadcast(pressBroadcast())
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        app.sendBroadcast(releaseBroadcast())
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        r.stop()
        assertTrue("expected one down and one up edge", seenSources.size == 2)
        assertTrue("first edge is down", seenSources[0].first)
        assertFalse("second edge is up", seenSources[1].first)
        assertTrue(
            "all edges must be tagged SONIM_PTT",
            seenSources.all { it.second == PttSource.SONIM_PTT },
        )
    }

    // ------------------------------------------------------------------
    // MCX / MCPTT carrier firmware path (AT&T XP9900)
    // ------------------------------------------------------------------

    private fun mcxPressBroadcast(): Intent =
        Intent(SonimHardwareButtons.ACTION_MCX_KEY).apply {
            putExtra(SonimHardwareButtons.MCX_EXTRA_STATE, SonimHardwareButtons.MCX_STATE_PRESSED)
        }

    private fun mcxReleaseBroadcast(): Intent =
        Intent(SonimHardwareButtons.ACTION_MCX_KEY).apply {
            putExtra(SonimHardwareButtons.MCX_EXTRA_STATE, SonimHardwareButtons.MCX_STATE_RELEASED)
        }

    @Test
    fun `MCX state=1 broadcast fires slot-0 TX start`() {
        reader.start()
        RuntimeEnvironment.getApplication().sendBroadcast(mcxPressBroadcast())
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        verify(exactly = 1) { txController.start(slot = 0) }
    }

    @Test
    fun `MCX press then release fires start then stop`() {
        reader.start()
        val app = RuntimeEnvironment.getApplication()
        app.sendBroadcast(mcxPressBroadcast())
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        app.sendBroadcast(mcxReleaseBroadcast())
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        verify(exactly = 1) { txController.start(slot = 0) }
        verify(exactly = 1) { txController.stop() }
    }

    @Test
    fun `MCX broadcast with unknown state is ignored`() {
        reader.start()
        val bad = Intent(SonimHardwareButtons.ACTION_MCX_KEY).apply {
            putExtra(SonimHardwareButtons.MCX_EXTRA_STATE, 99)
        }
        RuntimeEnvironment.getApplication().sendBroadcast(bad)
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        verify(exactly = 0) { txController.start(slot = 0) }
        verify(exactly = 0) { txController.stop() }
    }

    @Test
    fun `MCX broadcast with missing state extra is ignored`() {
        reader.start()
        // No extra at all — getIntExtra returns the default (-1).
        RuntimeEnvironment.getApplication().sendBroadcast(Intent(SonimHardwareButtons.ACTION_MCX_KEY))
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        verify(exactly = 0) { txController.start(slot = 0) }
    }

    @Test
    fun `MCX edges are tagged with SONIM_PTT source`() {
        val seenSources = mutableListOf<Pair<Boolean, PttSource>>()
        val r =
            SonimPttButtonReader(
                context = RuntimeEnvironment.getApplication(),
                onEdge = { isDown, source -> seenSources.add(isDown to source) },
            )
        r.start()
        val app = RuntimeEnvironment.getApplication()
        app.sendBroadcast(mcxPressBroadcast())
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        app.sendBroadcast(mcxReleaseBroadcast())
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        r.stop()
        assertTrue("expected one down and one up edge", seenSources.size == 2)
        assertTrue("first edge is down", seenSources[0].first)
        assertFalse("second edge is up", seenSources[1].first)
        assertTrue(
            "MCX edges must be tagged SONIM_PTT",
            seenSources.all { it.second == PttSource.SONIM_PTT },
        )
    }

    @Test
    fun `classic and MCX broadcasts deduplicate via dispatcher OR-gate`() {
        // Simulate a firmware that emits both classic PTT_KEY_DOWN and
        // MCX_KEY(state=1) for the same physical press. The dispatcher's
        // OR-gate must absorb the second edge so TX is only started once.
        reader.start()
        val app = RuntimeEnvironment.getApplication()
        app.sendBroadcast(pressBroadcast())
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        app.sendBroadcast(mcxPressBroadcast())
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        verify(exactly = 1) { txController.start(slot = 0) }
    }
}
