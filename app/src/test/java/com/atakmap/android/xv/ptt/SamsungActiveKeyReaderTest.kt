package com.atakmap.android.xv.ptt

import android.content.Intent
import com.atakmap.android.xv.audio.PttDispatcher
import com.atakmap.android.xv.audio.PttSource
import com.atakmap.android.xv.audio.StatusTones
import com.atakmap.android.xv.audio.TptPlayer
import com.atakmap.android.xv.audio.TxController
import com.atakmap.android.xv.util.SamsungActiveKey
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
 * Coverage for [SamsungActiveKeyReader]'s wiring to [PttDispatcher].
 *
 * The reader translates the Samsung framework's `HARD_KEY_REPORT`
 * broadcast into PTT down/up edges tagged with
 * [PttSource.SAMSUNG_ACTIVE_KEY]. These tests use Robolectric to
 * dispatch a synthetic broadcast against a registered receiver and
 * verify the dispatcher sees the correct edges.
 *
 * We do NOT rely on any Samsung-only APIs — the mechanism is a plain
 * `BroadcastReceiver` filtering on the framework's action string, so
 * a Robolectric context is a fully-representative harness.
 */
@RunWith(RobolectricTestRunner::class)
class SamsungActiveKeyReaderTest {
    private lateinit var txController: TxController
    private lateinit var statusTones: StatusTones
    private lateinit var tptPlayer: TptPlayer
    private lateinit var dispatcher: PttDispatcher
    private lateinit var reader: SamsungActiveKeyReader

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
            SamsungActiveKeyReader(
                context = RuntimeEnvironment.getApplication(),
                onEdge = { isDown, source ->
                    if (isDown) dispatcher.down(slot = 0, source = source) else dispatcher.up(slot = 0, source = source)
                },
            )
    }

    private fun pressBroadcast(): Intent =
        Intent(SamsungActiveKey.ACTION_HARD_KEY_REPORT).apply {
            putExtra(SamsungActiveKey.EXTRA_KEY_CODE, SamsungActiveKey.KEY_CODE_PTT)
            putExtra(SamsungActiveKey.EXTRA_KEY_REPORT_TYPE, SamsungActiveKey.KEY_REPORT_TYPE_PRESSED)
        }

    private fun releaseBroadcast(): Intent =
        Intent(SamsungActiveKey.ACTION_HARD_KEY_REPORT).apply {
            putExtra(SamsungActiveKey.EXTRA_KEY_CODE, SamsungActiveKey.KEY_CODE_PTT)
            putExtra(SamsungActiveKey.EXTRA_KEY_REPORT_TYPE, SamsungActiveKey.KEY_REPORT_TYPE_RELEASED)
        }

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
    fun `emergency key code is ignored — only KEYCODE_PTT drives TX`() {
        reader.start()
        val emergency =
            Intent(SamsungActiveKey.ACTION_HARD_KEY_REPORT).apply {
                // KEY_CODE_EMERGENCY = 1079 per Samsung Knox docs. XV's
                // emergency subsystem is wired to AINA PTTE only; this
                // reader must not couple the top key to slot-0 PTT.
                putExtra(SamsungActiveKey.EXTRA_KEY_CODE, 1079)
                putExtra(SamsungActiveKey.EXTRA_KEY_REPORT_TYPE, SamsungActiveKey.KEY_REPORT_TYPE_PRESSED)
            }
        RuntimeEnvironment.getApplication().sendBroadcast(emergency)
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        verify(exactly = 0) { txController.start(slot = 0) }
    }

    @Test
    fun `malformed report type is dropped without touching TX`() {
        reader.start()
        val bogus =
            Intent(SamsungActiveKey.ACTION_HARD_KEY_REPORT).apply {
                putExtra(SamsungActiveKey.EXTRA_KEY_CODE, SamsungActiveKey.KEY_CODE_PTT)
                // Not 1 (press) or 2 (release) — some future firmware
                // may emit an unexpected value; we must not misinterpret.
                putExtra(SamsungActiveKey.EXTRA_KEY_REPORT_TYPE, 99)
            }
        RuntimeEnvironment.getApplication().sendBroadcast(bogus)
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        verify(exactly = 0) { txController.start(slot = 0) }
        verify(exactly = 0) { txController.stop() }
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
        // No throw; no state change; subsequent start still works.
        reader.stop()
        assertFalse(reader.isRunning())
        assertTrue(reader.start())
        assertTrue(reader.isRunning())
    }

    @Test
    fun `edges are tagged with SAMSUNG_ACTIVE_KEY source`() {
        // Wire a custom onEdge to inspect the source directly (belt-
        // and-suspenders against a copy-paste regression where the
        // reader ships events under the wrong PttSource).
        val seenSources = mutableListOf<Pair<Boolean, PttSource>>()
        val r =
            SamsungActiveKeyReader(
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
            "all edges must be tagged SAMSUNG_ACTIVE_KEY",
            seenSources.all { it.second == PttSource.SAMSUNG_ACTIVE_KEY },
        )
    }
}
