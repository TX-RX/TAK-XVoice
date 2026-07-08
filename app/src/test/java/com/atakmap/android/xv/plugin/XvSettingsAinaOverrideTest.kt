package com.atakmap.android.xv.plugin

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Coverage for the per-MAC AINA protocol override on [XvSettings].
 *
 * The override is the cure for V2 AINAs whose UUID cache misses the
 * V2 vendor service — [AinaProtocolProbe] persists "v2" here when it
 * resolves BLE so subsequent connects skip the misclassification.
 * On BOND_NONE (operator re-paired the device) the override gets
 * cleared so a stale entry can't bind a future re-pair to the wrong
 * protocol.
 *
 * Robolectric for a real SharedPreferences-backed [Context] —
 * mocking SharedPreferences explicitly would just exercise the
 * mock, not the persistence semantics we care about.
 */
@RunWith(RobolectricTestRunner::class)
class XvSettingsAinaOverrideTest {
    private lateinit var settings: XvSettings

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        // Clear prefs between tests so order independence holds.
        ctx.getSharedPreferences(XvSettings.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .apply()
        settings = XvSettings { ctx.getSharedPreferences(XvSettings.PREFS_NAME, Context.MODE_PRIVATE) }
    }

    @Test
    fun `persist then read returns the same protocol`() {
        settings.persistAinaProtocolOverride(MAC, "v2")
        assertEquals("v2", settings.persistedAinaProtocolOverride(MAC))
    }

    @Test
    fun `read is MAC-case-insensitive via uppercase key normalization`() {
        settings.persistAinaProtocolOverride(MAC.lowercase(), "v2")
        // Both casings resolve to the same key suffix, so the
        // override is observable regardless of how the caller
        // formatted the MAC.
        assertEquals("v2", settings.persistedAinaProtocolOverride(MAC))
        assertEquals("v2", settings.persistedAinaProtocolOverride(MAC.lowercase()))
    }

    @Test
    fun `clear removes the override`() {
        settings.persistAinaProtocolOverride(MAC, "v2")
        assertEquals("v2", settings.persistedAinaProtocolOverride(MAC))
        settings.clearAinaProtocolOverride(MAC, reason = "test")
        assertNull(settings.persistedAinaProtocolOverride(MAC))
    }

    @Test
    fun `clear is idempotent on absent key`() {
        // No override set → clear must be a no-op (rather than throwing
        // or implicitly creating a tombstone).
        settings.clearAinaProtocolOverride(MAC, reason = "test")
        assertNull(settings.persistedAinaProtocolOverride(MAC))

        // Calling clear twice in a row must remain a no-op.
        settings.persistAinaProtocolOverride(MAC, "v2")
        settings.clearAinaProtocolOverride(MAC, reason = "test")
        settings.clearAinaProtocolOverride(MAC, reason = "test")
        assertNull(settings.persistedAinaProtocolOverride(MAC))
    }

    @Test
    fun `clear with blank MAC is a no-op`() {
        settings.persistAinaProtocolOverride(MAC, "v2")
        settings.clearAinaProtocolOverride("")
        settings.clearAinaProtocolOverride(null)
        // Original override survives — a blank MAC is operator error,
        // not an instruction to wipe everything.
        assertEquals("v2", settings.persistedAinaProtocolOverride(MAC))
    }

    @Test
    fun `clear only affects the targeted MAC`() {
        settings.persistAinaProtocolOverride(MAC, "v2")
        settings.persistAinaProtocolOverride(OTHER_MAC, "v1")
        settings.clearAinaProtocolOverride(MAC)
        assertNull(settings.persistedAinaProtocolOverride(MAC))
        // The other device's override must not be collateral damage.
        assertEquals("v1", settings.persistedAinaProtocolOverride(OTHER_MAC))
    }

    @Test
    fun `persist with null proto clears the entry`() {
        // Symmetry with the existing persist-empty contract — a null/
        // blank proto value behaves like clear() rather than storing
        // a literal empty string that would later read back as null
        // via the blank-coalesce in [persistedAinaProtocolOverride].
        settings.persistAinaProtocolOverride(MAC, "v2")
        settings.persistAinaProtocolOverride(MAC, null)
        assertNull(settings.persistedAinaProtocolOverride(MAC))
    }

    companion object {
        private const val MAC = "AA:BB:CC:DD:EE:01"
        private const val OTHER_MAC = "11:22:33:44:55:66"
    }
}
