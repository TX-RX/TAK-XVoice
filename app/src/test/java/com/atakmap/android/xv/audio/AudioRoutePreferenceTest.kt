package com.atakmap.android.xv.audio

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Coverage for AudioRoutePreference — the SharedPreferences-backed
 * persistence of the operator's chosen output route + BT-output
 * override MAC.
 *
 * Robolectric provides a real SharedPreferences implementation so the
 * round-trip behavior (write → read on a fresh instance) is exercised
 * end-to-end, not just through MockK.
 */
@RunWith(RobolectricTestRunner::class)
class AudioRoutePreferenceTest {
    private fun pref(): AudioRoutePreference {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        // Wipe the preferences file between tests so they don't leak
        // state into each other (Robolectric reuses the same prefs
        // file across tests in the same class).
        ctx.getSharedPreferences("xv_audio_prefs", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .apply()
        return AudioRoutePreference(ctx)
    }

    @Test
    fun `default route on fresh install is SPEAKER`() {
        // No key present → fromName(null) → DEFAULT = SPEAKER.
        assertEquals(OutputRoute.SPEAKER, pref().route)
    }

    @Test
    fun `route round-trip — set then re-read`() {
        val p = pref()
        p.route = OutputRoute.EARPIECE
        assertEquals(OutputRoute.EARPIECE, p.route)
    }

    @Test
    fun `route persists across distinct AudioRoutePreference instances`() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        ctx.getSharedPreferences("xv_audio_prefs", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .apply()
        AudioRoutePreference(ctx).route = OutputRoute.WIRED
        // A fresh instance reading the same prefs file should see WIRED.
        assertEquals(OutputRoute.WIRED, AudioRoutePreference(ctx).route)
    }

    @Test
    fun `outputBtOverrideMac defaults to null when unset`() {
        assertNull(pref().outputBtOverrideMac)
    }

    @Test
    fun `outputBtOverrideMac round-trip — set then re-read`() {
        val p = pref()
        p.outputBtOverrideMac = "AA:BB:CC:DD:EE:FF"
        assertEquals("AA:BB:CC:DD:EE:FF", p.outputBtOverrideMac)
    }

    @Test
    fun `outputBtOverrideMac — blank input clears the override`() {
        val p = pref()
        p.outputBtOverrideMac = "AA:BB:CC:DD:EE:FF"
        assertEquals("AA:BB:CC:DD:EE:FF", p.outputBtOverrideMac)
        p.outputBtOverrideMac = "  " // blank → clear
        assertNull(p.outputBtOverrideMac)
    }

    @Test
    fun `outputBtOverrideMac — null clears the override`() {
        val p = pref()
        p.outputBtOverrideMac = "AA:BB:CC:DD:EE:FF"
        p.outputBtOverrideMac = null
        assertNull(p.outputBtOverrideMac)
    }

    @Test
    fun `outputBtOverrideMac — trims surrounding whitespace`() {
        val p = pref()
        p.outputBtOverrideMac = "  AA:BB:CC:DD:EE:FF  "
        assertEquals("AA:BB:CC:DD:EE:FF", p.outputBtOverrideMac)
    }

    @Test
    fun `setting an invalid route name does not corrupt persistence`() {
        // We can't write an invalid OutputRoute via the typed setter,
        // but defensively confirm that a stale-pref value (a real
        // scenario after enum-entry removal across versions) reads
        // back as DEFAULT rather than throwing.
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        ctx.getSharedPreferences("xv_audio_prefs", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .putString("output_route", "MOTOROLA_RADIO_SOUND")
            .apply()
        assertEquals(OutputRoute.DEFAULT, AudioRoutePreference(ctx).route)
    }
}
