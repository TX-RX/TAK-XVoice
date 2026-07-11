package com.atakmap.android.xv.audio

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Coverage for [logPrefixForPttSource]. The helper feeds VoicePlant's
 * primary-AINA and external-button log lines — one assertion per enum
 * value so a rename in [PttSource] or a mislabelled branch is caught
 * at test-time rather than in field logs. Pure function; no Robolectric
 * shell needed.
 */
class PttSourceLogPrefixTest {
    @Test
    fun `on-screen source maps to on-screen PTT`() {
        assertEquals("on-screen PTT", logPrefixForPttSource(PttSource.ON_SCREEN))
    }

    @Test
    fun `AINA V1 source maps to primary AINA V1 button`() {
        assertEquals("primary AINA V1 button", logPrefixForPttSource(PttSource.AINA_V1))
    }

    @Test
    fun `AINA V2 source maps to primary AINA V2 button`() {
        assertEquals("primary AINA V2 button", logPrefixForPttSource(PttSource.AINA_V2))
    }

    @Test
    fun `Pryme BLE source maps to external button`() {
        assertEquals("external button", logPrefixForPttSource(PttSource.PRYME_BLE))
    }

    @Test
    fun `Samsung Active Key source maps to Samsung Active Key`() {
        assertEquals("Samsung Active Key", logPrefixForPttSource(PttSource.SAMSUNG_ACTIVE_KEY))
    }

    @Test
    fun `Sonim PTT source maps to Sonim PTT`() {
        assertEquals("Sonim PTT", logPrefixForPttSource(PttSource.SONIM_PTT))
    }

    @Test
    fun `Sonim Emergency source maps to Sonim Emergency`() {
        assertEquals("Sonim Emergency", logPrefixForPttSource(PttSource.SONIM_EMERGENCY))
    }

    @Test
    fun `debug source maps to debug PTT`() {
        assertEquals("debug PTT", logPrefixForPttSource(PttSource.DEBUG))
    }
}
