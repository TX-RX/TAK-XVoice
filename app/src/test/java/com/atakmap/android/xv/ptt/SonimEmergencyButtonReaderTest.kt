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
 * Coverage for [SonimEmergencyButtonReader]'s wiring to
 * [PttDispatcher]. The reader translates the framework's
 * `android.intent.action.SOS.down` / `_up` broadcasts into PTT down /
 * up edges tagged with [PttSource.SONIM_EMERGENCY] — a distinct
 * source from [PttSource.SONIM_PTT] so on-device debugging can
 * distinguish which button drove a given burst and so a future PR can
 * promote presses of this button into an emergency CoT event without
 * disturbing the plain PTT path.
 */
@RunWith(RobolectricTestRunner::class)
class SonimEmergencyButtonReaderTest {
    private lateinit var txController: TxController
    private lateinit var statusTones: StatusTones
    private lateinit var tptPlayer: TptPlayer
    private lateinit var dispatcher: PttDispatcher
    private lateinit var reader: SonimEmergencyButtonReader

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
            SonimEmergencyButtonReader(
                context = RuntimeEnvironment.getApplication(),
                onEdge = { isDown, source ->
                    if (isDown) dispatcher.down(slot = 0, source = source) else dispatcher.up(slot = 0, source = source)
                },
            )
    }

    private fun pressBroadcast(): Intent = Intent(SonimHardwareButtons.ACTION_SOS_DOWN)

    private fun releaseBroadcast(): Intent = Intent(SonimHardwareButtons.ACTION_SOS_UP)

    @Test
    fun `start registers the receiver and press-broadcast fires slot-0 TX`() {
        assertTrue(reader.start())
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
    fun `PTT_KEY broadcast is ignored — only SOS actions drive this reader`() {
        // Symmetric guard to the PTT reader's SOS-ignored test. The
        // filter here subscribes only to the two SOS actions; a
        // com.sonim.intent.action.PTT_KEY_DOWN delivered to this
        // process is dropped so we don't double-fire on the wrong
        // source.
        reader.start()
        val pttDown = Intent(SonimHardwareButtons.ACTION_PTT_KEY_DOWN)
        RuntimeEnvironment.getApplication().sendBroadcast(pttDown)
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        verify(exactly = 0) { txController.start(slot = 0) }
    }

    @Test
    fun `double start is a safe no-op`() {
        assertTrue(reader.start())
        assertTrue(reader.start())
        RuntimeEnvironment.getApplication().sendBroadcast(pressBroadcast())
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
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
    fun `edges are tagged with SONIM_EMERGENCY source`() {
        // Belt-and-suspenders against a copy-paste regression where
        // the reader ships events under the wrong PttSource (e.g.
        // SONIM_PTT instead of SONIM_EMERGENCY). A future
        // emergency-dispatch upgrade will key off the SONIM_EMERGENCY
        // tag; getting it wrong here would silently break that path.
        val seenSources = mutableListOf<Pair<Boolean, PttSource>>()
        val r =
            SonimEmergencyButtonReader(
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
            "all edges must be tagged SONIM_EMERGENCY",
            seenSources.all { it.second == PttSource.SONIM_EMERGENCY },
        )
    }
}
