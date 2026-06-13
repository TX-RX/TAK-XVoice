package com.atakmap.android.xv.audio

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Coverage for the TptTone enum + `fromName` lookup. The lookup is the
 * persistence path: the operator's chosen tone is stored as a String
 * in SharedPreferences and re-parsed on every plugin load. A regression
 * in fromName would silently reset every operator's preference to
 * DEFAULT (ASTRO_25) on next install — invisible from logs.
 */
class TptToneTest {
    @Test
    fun `fromName resolves canonical enum names`() {
        assertEquals(TptTone.NONE, TptTone.fromName("NONE"))
        assertEquals(TptTone.ASTRO_25, TptTone.fromName("ASTRO_25"))
        assertEquals(TptTone.DMR, TptTone.fromName("DMR"))
        assertEquals(TptTone.NEXTEL, TptTone.fromName("NEXTEL"))
        assertEquals(TptTone.NEXTEL_TRUE, TptTone.fromName("NEXTEL_TRUE"))
        assertEquals(TptTone.VERTEX, TptTone.fromName("VERTEX"))
    }

    @Test
    fun `fromName is case-insensitive`() {
        assertEquals(TptTone.NEXTEL, TptTone.fromName("nextel"))
        assertEquals(TptTone.NEXTEL, TptTone.fromName("Nextel"))
        assertEquals(TptTone.ASTRO_25, TptTone.fromName("astro_25"))
    }

    @Test
    fun `fromName returns DEFAULT for null`() {
        // SharedPreferences.getString returns null for unset keys —
        // fromName must handle that path so a clean install gets the
        // documented default rather than a NullPointerException.
        assertEquals(TptTone.DEFAULT, TptTone.fromName(null))
    }

    @Test
    fun `fromName returns DEFAULT for unknown name`() {
        // Forward-compat: a future build that removes an enum entry
        // would land on installs where SharedPreferences still has
        // the old name. Don't crash; fall back to DEFAULT and let
        // the operator re-pick.
        assertEquals(TptTone.DEFAULT, TptTone.fromName("MOTOROLA_XTS_5000"))
    }

    @Test
    fun `fromName returns DEFAULT for empty string`() {
        assertEquals(TptTone.DEFAULT, TptTone.fromName(""))
    }

    @Test
    fun `DEFAULT is ASTRO_25`() {
        // Pinned to the documented choice; a flip here changes every
        // clean-install operator's TPT.
        assertEquals(TptTone.ASTRO_25, TptTone.DEFAULT)
    }

    @Test
    fun `every enum entry has a non-blank display name`() {
        // The display name is what the operator sees in the Settings
        // dropdown; an empty entry would render as a blank row.
        for (tone in TptTone.entries) {
            assertEquals(
                "entry ${tone.name} must have a non-blank display name",
                false,
                tone.displayName.isBlank(),
            )
        }
    }
}
