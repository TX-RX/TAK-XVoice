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
 *   - `SOS_KEY_DOWN` / `_UP` (+ the paired Kodiak SOS emission) →
 *     emergency (`onSosKeyEdge`), collapsed to a single edge.
 *
 * The Yellow key is intentionally NOT handled — it is an
 * application-launcher convenience key, not a PTT trigger. These tests
 * assert that a stray YELLOW_KEY broadcast produces no emergency edge
 * (the reader never registers for it, so it is simply ignored).
 *
 * Robolectric dispatches synthetic broadcasts against the registered
 * receiver and asserts the callbacks see the correct, de-duplicated
 * edges. No Sonim-only APIs are used — the mechanism is a plain
 * `BroadcastReceiver` on the framework action strings.
 */
@RunWith(RobolectricTestRunner::class)
class SonimAssignedAppReaderTest {
    private val sosEdges = mutableListOf<Boolean>()
    private lateinit var reader: SonimAssignedAppReader

    @Before
    fun setup() {
        sosEdges.clear()
        reader =
            SonimAssignedAppReader(
                context = RuntimeEnvironment.getApplication(),
                onSosKeyEdge = { isDown -> sosEdges.add(isDown) },
            )
        assertTrue("reader.start() should register the receiver in a Robolectric context", reader.start())
    }

    private fun send(action: String) {
        RuntimeEnvironment.getApplication().sendBroadcast(Intent(action))
        ShadowLooper.idleMainLooper()
    }

    // ------------------------------------------------------------------
    // SOS (SOS_KEY + paired Kodiak emission → emergency)
    // ------------------------------------------------------------------

    @Test
    fun `SOS_KEY down then up routes to emergency edges`() {
        send(SonimHardwareButtons.ACTION_SOS_KEY_DOWN)
        send(SonimHardwareButtons.ACTION_SOS_KEY_UP)
        assertEquals(listOf(true, false), sosEdges)
    }

    @Test
    fun `SOS_KEY up with no prior down is dropped`() {
        send(SonimHardwareButtons.ACTION_SOS_KEY_UP)
        assertTrue("an unmatched up must not fire an emergency edge", sosEdges.isEmpty())
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
    // Yellow key (app-launcher convenience key) — must NOT be handled
    // ------------------------------------------------------------------

    @Test
    fun `YELLOW_KEY broadcast fires no emergency edge — launcher key is not handled`() {
        // The Yellow key is an application-launcher key, not a PTT or
        // emergency trigger. The reader never registers for its actions,
        // so a stray YELLOW_KEY broadcast (whatever its origin) must be
        // inert. Regression guard for the launcher-keys-PTT bug.
        send("com.sonim.intent.action.YELLOW_KEY_DOWN")
        send("com.sonim.intent.action.YELLOW_KEY_UP")
        assertTrue("Yellow key must not drive the emergency path", sosEdges.isEmpty())
    }

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    @Test
    fun `stop unregisters the receiver — later broadcasts are dropped`() {
        reader.stop()
        send(SonimHardwareButtons.ACTION_SOS_KEY_DOWN)
        assertTrue("no SOS edge after stop", sosEdges.isEmpty())
    }

    @Test
    fun `double start is a safe no-op — receiver still delivers exactly one edge`() {
        assertTrue("second start() returns true (already-registered path)", reader.start())
        send(SonimHardwareButtons.ACTION_SOS_KEY_DOWN)
        assertEquals(listOf(true), sosEdges)
    }
}
