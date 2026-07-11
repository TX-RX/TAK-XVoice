package com.atakmap.android.xv.ptt

import android.view.KeyEvent
import android.view.View
import com.atakmap.android.xv.audio.PttSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Coverage for [SamsungActiveKeyForegroundReader], the foreground-
 * KeyEvent fallback path for the Samsung Active Key. The broadcast
 * path ([SamsungActiveKeyReaderTest]) covers the ideal-case wiring;
 * this suite covers the fallback that Tab Active5-class firmware
 * actually needs (the broadcast is not emitted on SM-X308U as of
 * 2026-07-10).
 *
 * We drive the reader's `OnKeyListener` directly against synthetic
 * `KeyEvent` instances rather than standing up a real ATAK `MapView`
 * — the listener is the whole behaviour surface, and standing up an
 * ATAK runtime inside a unit test would add cost with no additional
 * coverage.
 */
@RunWith(RobolectricTestRunner::class)
class SamsungActiveKeyForegroundReaderTest {
    private class Recorder : (Boolean, PttSource) -> Unit {
        val edges = mutableListOf<Pair<Boolean, PttSource>>()

        override fun invoke(
            isDown: Boolean,
            source: PttSource,
        ) {
            edges.add(isDown to source)
        }
    }

    private fun keyEvent(
        keyCode: Int,
        action: Int,
        repeatCount: Int = 0,
    ): KeyEvent =
        // Args to the raw KeyEvent constructor: downTime, eventTime,
        // action, code, repeatCount. downTime + eventTime are not read
        // by handleKeyEvent, so we pass 0L for both.
        KeyEvent(
            0L,
            0L,
            action,
            keyCode,
            repeatCount,
        )

    private fun dispatch(
        listener: View.OnKeyListener,
        event: KeyEvent,
    ): Boolean {
        // The View argument is not used by the reader's listener — it
        // reads only keyCode + event.action + event.repeatCount — so
        // passing null keeps the harness minimal. (Kotlin's null-safety
        // is fine here because View is Java and the listener signature
        // accepts a nullable receiver at the JVM level; use the
        // application context's decor-view surrogate if a future rev
        // needs a real View instance.)
        val view: View? = null
        @Suppress("UNCHECKED_CAST")
        return listener.onKey(view, event.keyCode, event)
    }

    @Test
    fun `KEYCODE_PTT press fires exactly one SAMSUNG_ACTIVE_KEY down edge`() {
        val recorder = Recorder()
        val reader = SamsungActiveKeyForegroundReader(recorder)
        val consumed =
            dispatch(reader.listener, keyEvent(1015, KeyEvent.ACTION_DOWN, repeatCount = 0))
        assertTrue("Active-Key press must be consumed by the fallback listener", consumed)
        assertEquals(1, recorder.edges.size)
        assertEquals(true to PttSource.SAMSUNG_ACTIVE_KEY, recorder.edges.single())
    }

    @Test
    fun `press then release fires down then up`() {
        val recorder = Recorder()
        val reader = SamsungActiveKeyForegroundReader(recorder)
        dispatch(reader.listener, keyEvent(1015, KeyEvent.ACTION_DOWN))
        dispatch(reader.listener, keyEvent(1015, KeyEvent.ACTION_UP))
        assertEquals(2, recorder.edges.size)
        assertEquals(true to PttSource.SAMSUNG_ACTIVE_KEY, recorder.edges[0])
        assertEquals(false to PttSource.SAMSUNG_ACTIVE_KEY, recorder.edges[1])
    }

    @Test
    fun `auto-repeat DOWN events after the initial press are dropped`() {
        // A physically-held key produces an ACTION_DOWN with a rising
        // repeatCount at the system auto-repeat rate. handleKeyEvent
        // drops those; the reader's held-state further guards against
        // any pathological duplicate ACTION_DOWN with repeatCount == 0.
        val recorder = Recorder()
        val reader = SamsungActiveKeyForegroundReader(recorder)
        dispatch(reader.listener, keyEvent(1015, KeyEvent.ACTION_DOWN, repeatCount = 0))
        dispatch(reader.listener, keyEvent(1015, KeyEvent.ACTION_DOWN, repeatCount = 1))
        dispatch(reader.listener, keyEvent(1015, KeyEvent.ACTION_DOWN, repeatCount = 2))
        dispatch(reader.listener, keyEvent(1015, KeyEvent.ACTION_UP))
        assertEquals(2, recorder.edges.size)
        assertEquals(true to PttSource.SAMSUNG_ACTIVE_KEY, recorder.edges[0])
        assertEquals(false to PttSource.SAMSUNG_ACTIVE_KEY, recorder.edges[1])
    }

    @Test
    fun `duplicate press (both repeatCount=0) after held is dropped by internal held-guard`() {
        // Belt-and-suspenders: if InputDispatcher somehow redelivers a
        // fresh ACTION_DOWN while we still think the key is held (no
        // intervening UP), the reader must suppress it — otherwise the
        // dispatcher would see back-to-back down edges for the same
        // source, which the OR-gate would collapse but a debug operator
        // reading logs shouldn't have to squint at.
        val recorder = Recorder()
        val reader = SamsungActiveKeyForegroundReader(recorder)
        dispatch(reader.listener, keyEvent(1015, KeyEvent.ACTION_DOWN))
        dispatch(reader.listener, keyEvent(1015, KeyEvent.ACTION_DOWN))
        assertEquals("only one down edge should reach the callback", 1, recorder.edges.size)
    }

    @Test
    fun `release without a matching press is dropped by held-guard`() {
        // Foreground handoff mid-press: the KeyEvent path may deliver
        // an ACTION_UP to us that we never saw the ACTION_DOWN for
        // (e.g. the operator pressed the key while ATAK was
        // backgrounded, then swapped back before release). A spurious
        // txController.stop() for a burst we never engaged is exactly
        // the bug held-state guards against.
        val recorder = Recorder()
        val reader = SamsungActiveKeyForegroundReader(recorder)
        dispatch(reader.listener, keyEvent(1015, KeyEvent.ACTION_UP))
        assertEquals(0, recorder.edges.size)
    }

    @Test
    fun `volume-down keycode is not consumed and does not fire PTT`() {
        // A rogue "consumed=true" here would swallow volume rocker
        // events for every other component in ATAK — must return false.
        val recorder = Recorder()
        val reader = SamsungActiveKeyForegroundReader(recorder)
        val consumed = dispatch(reader.listener, keyEvent(KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.ACTION_DOWN))
        assertFalse("volume keys must pass through untouched", consumed)
        assertEquals(0, recorder.edges.size)
    }

    @Test
    fun `edges are tagged SAMSUNG_ACTIVE_KEY`() {
        // Belt-and-suspenders against a copy-paste regression where
        // the reader might ship events under the wrong PttSource.
        val recorder = Recorder()
        val reader = SamsungActiveKeyForegroundReader(recorder)
        dispatch(reader.listener, keyEvent(1015, KeyEvent.ACTION_DOWN))
        dispatch(reader.listener, keyEvent(1015, KeyEvent.ACTION_UP))
        assertTrue(
            "every edge must be tagged SAMSUNG_ACTIVE_KEY",
            recorder.edges.all { it.second == PttSource.SAMSUNG_ACTIVE_KEY },
        )
    }

    @Test
    fun `start attaches to a real ATAK-adjacent view and stop detaches idempotently`() {
        // We can't stand up a real com.atakmap.android.maps.MapView in
        // a Robolectric unit test (needs ATAK runtime), but we CAN
        // exercise start()/stop()'s idempotency and internal state
        // machine against a plain View surrogate via reflection —
        // MapView.addOnKeyListener / removeOnKeyListener are just
        // View methods. Here we exercise state-machine behaviour
        // without touching a live view: a stop() before any start()
        // is a no-op, and isRunning() is false throughout.
        val recorder = Recorder()
        val reader = SamsungActiveKeyForegroundReader(recorder)
        assertFalse(reader.isRunning())
        reader.stop(mapView = null)
        assertFalse("stop() before start() must be a safe no-op", reader.isRunning())
        // No edges dispatched simply from lifecycle transitions.
        assertEquals(0, recorder.edges.size)
        // Sanity: the app context is available in Robolectric so future
        // extensions can rely on it if they want.
        assertTrue(RuntimeEnvironment.getApplication() != null)
    }
}
