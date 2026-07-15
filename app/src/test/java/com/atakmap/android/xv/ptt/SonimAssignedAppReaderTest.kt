package com.atakmap.android.xv.ptt

import android.content.Intent
import com.atakmap.android.xv.util.SonimHardwareButtons
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.shadows.ShadowLooper

/**
 * Coverage for [SonimAssignedAppReader] — the receiver for the Sonim
 * "assigned to ATAK" (package-scoped) key delivery mode used on XP9900
 * handsets when programmable keys are assigned to ATAK in Settings.
 *
 * The reader routes:
 *   - `YELLOW_KEY_DOWN` / `_UP` → PTT (`onPttKeyEdge`). Sonim's
 *     assigned-app API naming is backwards relative to the physical
 *     buttons: the operator's physical PTT button is delivered as the
 *     `YELLOW_KEY` action (verified on-device 2026-07-14).
 *   - `SOS_KEY_DOWN` / `_UP` (+ the paired Kodiak SOS emission) →
 *     emergency (`onSosKeyEdge`), collapsed to a single edge.
 *
 * Robolectric dispatches synthetic broadcasts against the registered
 * receiver and asserts the callbacks see the correct, de-duplicated
 * edges. No Sonim-only APIs are used — the mechanism is a plain
 * `BroadcastReceiver` on the framework action strings.
 */
@RunWith(RobolectricTestRunner::class)
class SonimAssignedAppReaderTest {
    private val pttEdges = mutableListOf<Boolean>()
    private val sosEdges = mutableListOf<Boolean>()
    private lateinit var reader: SonimAssignedAppReader

    @Before
    fun setup() {
        pttEdges.clear()
        sosEdges.clear()
        reader =
            SonimAssignedAppReader(
                context = RuntimeEnvironment.getApplication(),
                onPttKeyEdge = { isDown -> pttEdges.add(isDown) },
                onSosKeyEdge = { isDown -> sosEdges.add(isDown) },
            )
        assertTrue("reader.start() should register the receiver in a Robolectric context", reader.start())
    }

    private fun send(action: String) {
        RuntimeEnvironment.getApplication().sendBroadcast(Intent(action))
        ShadowLooper.idleMainLooper()
    }

    // ------------------------------------------------------------------
    // PTT (YELLOW_KEY — backwards API: this is the physical PTT button)
    // ------------------------------------------------------------------

    @Test
    fun `YELLOW_KEY down then up routes to PTT edges`() {
        send(SonimHardwareButtons.ACTION_YELLOW_KEY_DOWN)
        send(SonimHardwareButtons.ACTION_YELLOW_KEY_UP)
        assertEquals(listOf(true, false), pttEdges)
        assertTrue("SOS path must not fire on PTT keys", sosEdges.isEmpty())
    }

    @Test
    fun `duplicate YELLOW_KEY down without an intervening up fires a single PTT down`() {
        send(SonimHardwareButtons.ACTION_YELLOW_KEY_DOWN)
        send(SonimHardwareButtons.ACTION_YELLOW_KEY_DOWN)
        assertEquals(listOf(true), pttEdges)
    }

    @Test
    fun `YELLOW_KEY up with no prior down is dropped`() {
        send(SonimHardwareButtons.ACTION_YELLOW_KEY_UP)
        assertTrue("an unmatched up must not fire a PTT edge", pttEdges.isEmpty())
    }

    // ------------------------------------------------------------------
    // SOS (SOS_KEY + paired Kodiak emission → emergency)
    // ------------------------------------------------------------------

    @Test
    fun `SOS_KEY down then up routes to emergency edges`() {
        send(SonimHardwareButtons.ACTION_SOS_KEY_DOWN)
        send(SonimHardwareButtons.ACTION_SOS_KEY_UP)
        assertEquals(listOf(true, false), sosEdges)
        assertTrue("PTT path must not fire on SOS keys", pttEdges.isEmpty())
    }

    @Test
    fun `Kodiak SOS paired with Sonim SOS collapses to a single emergency down`() {
        // On the XP9900 the SOS press emits SOS_KEY_DOWN and the Kodiak
        // KEYCODE_SOS action within the same millisecond. The Kodiak
        // action is dropped and the held-state guard absorbs any second
        // down, so exactly one emergency down edge is produced.
        send(SonimHardwareButtons.ACTION_SOS_KEY_DOWN)
        send(SonimHardwareButtons.ACTION_KODIAK_SOS)
        assertEquals(listOf(true), sosEdges)
    }

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    @Test
    fun `stop unregisters the receiver — later broadcasts are dropped`() {
        reader.stop()
        send(SonimHardwareButtons.ACTION_YELLOW_KEY_DOWN)
        send(SonimHardwareButtons.ACTION_SOS_KEY_DOWN)
        assertTrue("no PTT edge after stop", pttEdges.isEmpty())
        assertTrue("no SOS edge after stop", sosEdges.isEmpty())
    }

    @Test
    fun `double start is a safe no-op — receiver still delivers exactly one edge`() {
        assertTrue("second start() returns true (already-registered path)", reader.start())
        send(SonimHardwareButtons.ACTION_YELLOW_KEY_DOWN)
        assertEquals(listOf(true), pttEdges)
    }
}
