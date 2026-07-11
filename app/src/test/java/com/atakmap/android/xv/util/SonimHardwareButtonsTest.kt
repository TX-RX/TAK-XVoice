package com.atakmap.android.xv.util

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Coverage for [SonimHardwareButtons] — the device capability gate +
 * pure KeyEvent classifier for the two Sonim ruggedized-device
 * dedicated hardware buttons (XP10 PTT side key + Emergency / SOS
 * key).
 *
 * All checks are pure functions of Build fields / KeyEvent tuples —
 * no Android runtime needed — so the Robolectric harness stays
 * optional here.
 */
class SonimHardwareButtonsTest {
    // ============================================================
    // Device gate — Sonim XP10 positives
    // ============================================================

    @Test
    fun `Sonim XP10 XP9900 model prefix → supported`() {
        // XP9900 is the commercial / Build.MODEL identifier for the
        // XP10 across the AT&T, Verizon, T-Mobile, and global SKUs.
        assertTrue(
            SonimHardwareButtons.isSupportedInternal(
                manufacturer = "sonim",
                brand = "sonim",
                model = "XP9900",
                features = emptySet(),
            ),
        )
    }

    @Test
    fun `Sonim XP9900 regional variant XP9900-A → supported`() {
        // Prefix-match tolerates a regional / carrier suffix on the
        // Build.MODEL string that we haven't hard-listed yet.
        assertTrue(
            SonimHardwareButtons.isSupportedInternal(
                manufacturer = "sonim",
                brand = "sonim",
                model = "XP9900-A",
                features = emptySet(),
            ),
        )
    }

    @Test
    fun `Sonim XP10-A model form → supported`() {
        // Some Sonim documentation references "XP10" / "XP10-A" as the
        // Build.MODEL form. Both variants are in our prefix list so
        // whichever the shipping firmware reports lights up the toggle.
        assertTrue(
            SonimHardwareButtons.isSupportedInternal(
                manufacturer = "sonim",
                brand = "sonim",
                model = "XP10-A",
                features = emptySet(),
            ),
        )
    }

    @Test
    fun `Sonim manufacturer with brand differing still matches XP9900`() {
        // Defensive against a future firmware quirk where BRAND ≠
        // MANUFACTURER. Per SonimHardwareButtons: either matching is
        // sufficient (OR gate).
        assertTrue(
            SonimHardwareButtons.isSupportedInternal(
                manufacturer = "sonim",
                brand = "att",
                model = "XP9900",
                features = emptySet(),
            ),
        )
    }

    @Test
    fun `Sonim brand only (manufacturer differs) still matches`() {
        // The reverse — brand=sonim, some other manufacturer string.
        // Sonim's OEM relationship history includes carrier-branded
        // devices; be lenient.
        assertTrue(
            SonimHardwareButtons.isSupportedInternal(
                manufacturer = "other",
                brand = "sonim",
                model = "XP9900",
                features = emptySet(),
            ),
        )
    }

    @Test
    fun `mixed-case manufacturer still matches`() {
        // Build.MANUFACTURER is empirically all-lowercase "sonim" on
        // real hardware, but be tolerant of a future firmware quirk.
        assertTrue(
            SonimHardwareButtons.isSupportedInternal(
                manufacturer = "SONIM",
                brand = "SONIM",
                model = "XP9900",
                features = emptySet(),
            ),
        )
    }

    @Test
    fun `mixed-case model still matches`() {
        assertTrue(
            SonimHardwareButtons.isSupportedInternal(
                manufacturer = "sonim",
                brand = "sonim",
                model = "xp9900",
                features = emptySet(),
            ),
        )
    }

    // ============================================================
    // Device gate — negatives
    // ============================================================

    @Test
    fun `Sonim XP8 XP8800 → not supported (out of scope for this PR)`() {
        // XP8 is the older Sonim ruggedized flagship. It also has PTT
        // and Emergency keys, but is deliberately out of scope for
        // this PR — the operator has explicitly asked to validate the
        // XP10 first. If XP8 later gets integrated + validated, add
        // "XP8800" to SONIM_MODEL_PREFIXES.
        assertFalse(
            SonimHardwareButtons.isSupportedInternal(
                manufacturer = "sonim",
                brand = "sonim",
                model = "XP8800",
                features = emptySet(),
            ),
        )
    }

    @Test
    fun `Sonim XP5s → not supported (out of scope for this PR)`() {
        assertFalse(
            SonimHardwareButtons.isSupportedInternal(
                manufacturer = "sonim",
                brand = "sonim",
                model = "XP5800",
                features = emptySet(),
            ),
        )
    }

    @Test
    fun `Google Pixel 9 Pro → not supported`() {
        assertFalse(
            SonimHardwareButtons.isSupportedInternal(
                manufacturer = "Google",
                brand = "google",
                model = "Pixel 9 Pro",
                features = emptySet(),
            ),
        )
    }

    @Test
    fun `Samsung Tab Active5 → not supported`() {
        // Even though Samsung ruggedized devices have their own
        // Active Key surface, the Sonim gate must NOT match — Samsung
        // hardware routes through SamsungActiveKey.isSupported()
        // instead.
        assertFalse(
            SonimHardwareButtons.isSupportedInternal(
                manufacturer = "samsung",
                brand = "samsung",
                model = "SM-X306B",
                features = emptySet(),
            ),
        )
    }

    @Test
    fun `Microsoft Surface Duo → not supported`() {
        assertFalse(
            SonimHardwareButtons.isSupportedInternal(
                manufacturer = "Microsoft",
                brand = "microsoft",
                model = "Surface Duo",
                features = emptySet(),
            ),
        )
    }

    @Test
    fun `blank manufacturer AND brand → not supported`() {
        assertFalse(
            SonimHardwareButtons.isSupportedInternal(
                manufacturer = "",
                brand = "",
                model = "XP9900",
                features = emptySet(),
            ),
        )
    }

    @Test
    fun `blank model on Sonim → not supported`() {
        assertFalse(
            SonimHardwareButtons.isSupportedInternal(
                manufacturer = "sonim",
                brand = "sonim",
                model = "",
                features = emptySet(),
            ),
        )
    }

    // ============================================================
    // Feature-flag positives — future firmware
    // ============================================================

    @Test
    fun `Sonim unknown model but declares programmable_keys feature → supported`() {
        // Hypothetical future Sonim ruggedized device with a model
        // prefix we haven't seen yet, but firmware exposes a system
        // feature. MANUFACTURER / BRAND = sonim is still required.
        assertTrue(
            SonimHardwareButtons.isSupportedInternal(
                manufacturer = "sonim",
                brand = "sonim",
                model = "XP12345",
                features = setOf("com.sonim.hardware.programmable_keys"),
            ),
        )
    }

    @Test
    fun `alt feature flag naming also honored`() {
        assertTrue(
            SonimHardwareButtons.isSupportedInternal(
                manufacturer = "sonim",
                brand = "sonim",
                model = "XP12345",
                features = setOf("com.sonim.feature.programmable_keys"),
            ),
        )
    }

    @Test
    fun `feature flag on non-Sonim device → not supported`() {
        assertFalse(
            SonimHardwareButtons.isSupportedInternal(
                manufacturer = "Google",
                brand = "google",
                model = "Pixel 9 Pro",
                features = setOf("com.sonim.hardware.programmable_keys"),
            ),
        )
    }

    // ============================================================
    // KeyEvent classifier — PTT button
    // ============================================================

    @Test
    fun `PTT primary keycode DOWN repeat 0 → PTT_DOWN`() {
        assertEquals(
            SonimHardwareButtons.FallbackAction.PTT_DOWN,
            SonimHardwareButtons.handlePttKeyEvent(
                keyCode = SonimHardwareButtons.PTT_KEY_CODE_PRIMARY,
                action = KeyEvent.ACTION_DOWN,
                repeatCount = 0,
            ),
        )
    }

    @Test
    fun `PTT primary keycode UP → PTT_UP`() {
        assertEquals(
            SonimHardwareButtons.FallbackAction.PTT_UP,
            SonimHardwareButtons.handlePttKeyEvent(
                keyCode = SonimHardwareButtons.PTT_KEY_CODE_PRIMARY,
                action = KeyEvent.ACTION_UP,
                repeatCount = 0,
            ),
        )
    }

    @Test
    fun `PTT primary keycode DOWN repeat 1 → IGNORE`() {
        // Auto-repeat dropped so a held key doesn't spam duplicate
        // down edges past the first — belt-and-suspenders against the
        // dispatcher's OR-gate.
        assertEquals(
            SonimHardwareButtons.FallbackAction.IGNORE,
            SonimHardwareButtons.handlePttKeyEvent(
                keyCode = SonimHardwareButtons.PTT_KEY_CODE_PRIMARY,
                action = KeyEvent.ACTION_DOWN,
                repeatCount = 1,
            ),
        )
    }

    @Test
    fun `PTT alt keycode DOWN → PTT_DOWN`() {
        // The alt keycode is checked in parallel with the primary so
        // whichever mapping the XP10 firmware actually uses, we catch
        // it. TODO: prune to the actually-emitted one after on-device
        // validation.
        assertEquals(
            SonimHardwareButtons.FallbackAction.PTT_DOWN,
            SonimHardwareButtons.handlePttKeyEvent(
                keyCode = SonimHardwareButtons.PTT_KEY_CODE_ALT,
                action = KeyEvent.ACTION_DOWN,
                repeatCount = 0,
            ),
        )
    }

    @Test
    fun `PTT wrong keycode DOWN → IGNORE`() {
        // Volume keys, navigation keys etc. must pass through so
        // ATAK and any downstream OnKeyListener still handle them.
        assertEquals(
            SonimHardwareButtons.FallbackAction.IGNORE,
            SonimHardwareButtons.handlePttKeyEvent(
                keyCode = KeyEvent.KEYCODE_VOLUME_UP,
                action = KeyEvent.ACTION_DOWN,
                repeatCount = 0,
            ),
        )
    }

    @Test
    @Suppress("DEPRECATION")
    fun `PTT unusual action ACTION_MULTIPLE → IGNORE`() {
        // The deprecated ACTION_MULTIPLE (which InputDispatcher used
        // to synthesize for repeats) is dropped just in case some
        // older firmware still emits it.
        assertEquals(
            SonimHardwareButtons.FallbackAction.IGNORE,
            SonimHardwareButtons.handlePttKeyEvent(
                keyCode = SonimHardwareButtons.PTT_KEY_CODE_PRIMARY,
                action = KeyEvent.ACTION_MULTIPLE,
                repeatCount = 0,
            ),
        )
    }

    // ============================================================
    // KeyEvent classifier — Emergency / SOS button
    // ============================================================

    @Test
    fun `Emergency SOS keycode DOWN repeat 0 → PTT_DOWN`() {
        assertEquals(
            SonimHardwareButtons.FallbackAction.PTT_DOWN,
            SonimHardwareButtons.handleEmergencyKeyEvent(
                keyCode = SonimHardwareButtons.SOS_KEY_CODE,
                action = KeyEvent.ACTION_DOWN,
                repeatCount = 0,
            ),
        )
    }

    @Test
    fun `Emergency SOS keycode UP → PTT_UP`() {
        assertEquals(
            SonimHardwareButtons.FallbackAction.PTT_UP,
            SonimHardwareButtons.handleEmergencyKeyEvent(
                keyCode = SonimHardwareButtons.SOS_KEY_CODE,
                action = KeyEvent.ACTION_UP,
                repeatCount = 0,
            ),
        )
    }

    @Test
    fun `Emergency SOS keycode DOWN repeat 1 → IGNORE`() {
        assertEquals(
            SonimHardwareButtons.FallbackAction.IGNORE,
            SonimHardwareButtons.handleEmergencyKeyEvent(
                keyCode = SonimHardwareButtons.SOS_KEY_CODE,
                action = KeyEvent.ACTION_DOWN,
                repeatCount = 1,
            ),
        )
    }

    @Test
    fun `Emergency wrong keycode DOWN → IGNORE`() {
        assertEquals(
            SonimHardwareButtons.FallbackAction.IGNORE,
            SonimHardwareButtons.handleEmergencyKeyEvent(
                keyCode = SonimHardwareButtons.PTT_KEY_CODE_PRIMARY,
                action = KeyEvent.ACTION_DOWN,
                repeatCount = 0,
            ),
        )
    }

    @Test
    fun `Emergency PTT keycodes are NOT accepted by the emergency classifier`() {
        // Belt-and-suspenders: the emergency classifier only accepts
        // SOS_KEY_CODE. If a firmware misroutes the SOS press through
        // the PTT keycode, the PTT reader will pick it up (correctly
        // tagged SONIM_PTT) — but this classifier must not double-
        // fire on the same key.
        assertEquals(
            SonimHardwareButtons.FallbackAction.IGNORE,
            SonimHardwareButtons.handleEmergencyKeyEvent(
                keyCode = SonimHardwareButtons.PTT_KEY_CODE_ALT,
                action = KeyEvent.ACTION_DOWN,
                repeatCount = 0,
            ),
        )
    }

    @Test
    fun `SOS_KEY_CODE constant is 1079`() {
        // Guard against a copy-paste regression that changes the
        // constant to something the firmware doesn't emit.
        assertEquals(1079, SonimHardwareButtons.SOS_KEY_CODE)
    }
}
