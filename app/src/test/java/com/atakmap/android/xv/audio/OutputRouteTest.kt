package com.atakmap.android.xv.audio

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Coverage for OutputRoute.fromName persistence parse. Same shape as
 * TptToneTest — protects the SharedPreferences round-trip.
 */
class OutputRouteTest {
    @Test
    fun `fromName resolves all canonical entries`() {
        assertEquals(OutputRoute.AUTO, OutputRoute.fromName("AUTO"))
        assertEquals(OutputRoute.SPEAKER, OutputRoute.fromName("SPEAKER"))
        assertEquals(OutputRoute.EARPIECE, OutputRoute.fromName("EARPIECE"))
        assertEquals(OutputRoute.WIRED, OutputRoute.fromName("WIRED"))
    }

    @Test
    fun `fromName is case-insensitive`() {
        assertEquals(OutputRoute.SPEAKER, OutputRoute.fromName("speaker"))
        assertEquals(OutputRoute.WIRED, OutputRoute.fromName("Wired"))
    }

    @Test
    fun `fromName returns DEFAULT for null`() {
        assertEquals(OutputRoute.DEFAULT, OutputRoute.fromName(null))
    }

    @Test
    fun `fromName returns DEFAULT for unknown`() {
        assertEquals(OutputRoute.DEFAULT, OutputRoute.fromName("CARPLAY"))
    }

    @Test
    fun `fromName returns DEFAULT for empty`() {
        assertEquals(OutputRoute.DEFAULT, OutputRoute.fromName(""))
    }

    @Test
    fun `DEFAULT is SPEAKER`() {
        // Pinned to the documented default for clean installs.
        assertEquals(OutputRoute.SPEAKER, OutputRoute.DEFAULT)
    }
}
