package com.atakmap.android.xv.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Coverage for the pure device-capability check that gates the
 * Samsung Active Key PTT feature in Settings.
 *
 * The check is intentionally a pure function of Build.MANUFACTURER,
 * Build.MODEL, and system features — no Android runtime needed — so
 * the Robolectric harness stays optional here. If Samsung ever ships
 * a real system feature for the Active Key, the extension is one
 * `features` entry away without breaking the existing branches.
 */
class SamsungActiveKeyTest {
    // ============================================================
    // Model-prefix positives — Tab Active5 family
    // ============================================================

    @Test
    fun `Samsung SM-X306B Tab Active5 5G → supported`() {
        assertTrue(
            SamsungActiveKey.isSupportedInternal(
                manufacturer = "samsung",
                model = "SM-X306B",
                features = emptySet(),
            ),
        )
    }

    @Test
    fun `Samsung SM-X300 Tab Active5 Wi-Fi → supported`() {
        assertTrue(
            SamsungActiveKey.isSupportedInternal(
                manufacturer = "samsung",
                model = "SM-X300",
                features = emptySet(),
            ),
        )
    }

    @Test
    fun `Samsung SM-X308U Tab Active5 5G US variant → supported`() {
        assertTrue(
            SamsungActiveKey.isSupportedInternal(
                manufacturer = "samsung",
                model = "SM-X308U",
                features = emptySet(),
            ),
        )
    }

    // ============================================================
    // Model-prefix positives — other supported Samsung ruggedized
    // ============================================================

    @Test
    fun `Samsung SM-T636 Tab Active4 Pro → supported`() {
        assertTrue(
            SamsungActiveKey.isSupportedInternal(
                manufacturer = "samsung",
                model = "SM-T636",
                features = emptySet(),
            ),
        )
    }

    @Test
    fun `Samsung SM-T575 Tab Active3 → supported`() {
        assertTrue(
            SamsungActiveKey.isSupportedInternal(
                manufacturer = "samsung",
                model = "SM-T575",
                features = emptySet(),
            ),
        )
    }

    @Test
    fun `Samsung SM-G736U XCover6 Pro → supported`() {
        assertTrue(
            SamsungActiveKey.isSupportedInternal(
                manufacturer = "samsung",
                model = "SM-G736U",
                features = emptySet(),
            ),
        )
    }

    @Test
    fun `Samsung SM-G556B XCover7 → supported`() {
        assertTrue(
            SamsungActiveKey.isSupportedInternal(
                manufacturer = "samsung",
                model = "SM-G556B",
                features = emptySet(),
            ),
        )
    }

    // ============================================================
    // Negatives — non-Samsung
    // ============================================================

    @Test
    fun `Google Pixel 9 Pro → not supported`() {
        assertFalse(
            SamsungActiveKey.isSupportedInternal(
                manufacturer = "Google",
                model = "Pixel 9 Pro",
                features = emptySet(),
            ),
        )
    }

    @Test
    fun `Motorola anything → not supported`() {
        assertFalse(
            SamsungActiveKey.isSupportedInternal(
                manufacturer = "motorola",
                model = "moto g power (2024)",
                features = emptySet(),
            ),
        )
    }

    @Test
    fun `Microsoft Surface Duo → not supported`() {
        // Development host in AGENTS.md; the operator explicitly
        // wants a hard "no" on non-Samsung dev hardware.
        assertFalse(
            SamsungActiveKey.isSupportedInternal(
                manufacturer = "Microsoft",
                model = "Surface Duo",
                features = emptySet(),
            ),
        )
    }

    // ============================================================
    // Negatives — Samsung, but a non-Active model (has power +
    // volume only, no programmable side key)
    // ============================================================

    @Test
    fun `Samsung SM-G998B Galaxy S21 Ultra → not supported`() {
        assertFalse(
            SamsungActiveKey.isSupportedInternal(
                manufacturer = "samsung",
                model = "SM-G998B",
                features = emptySet(),
            ),
        )
    }

    @Test
    fun `Samsung SM-A536B Galaxy A53 → not supported`() {
        assertFalse(
            SamsungActiveKey.isSupportedInternal(
                manufacturer = "samsung",
                model = "SM-A536B",
                features = emptySet(),
            ),
        )
    }

    // ============================================================
    // Feature-flag positive — future Samsung firmware may publish a
    // proper system feature; we honour it as an additive signal so
    // hardware we haven't hard-listed still lights up the toggle.
    // ============================================================

    @Test
    fun `Samsung unknown model but declares active_key system feature → supported`() {
        // Hypothetical future ruggedized Samsung with a model prefix
        // we haven't seen yet, but firmware exposes the feature flag.
        // Note: MANUFACTURER == samsung is still required — the feature
        // flag alone on a non-Samsung device isn't recognised.
        assertTrue(
            SamsungActiveKey.isSupportedInternal(
                manufacturer = "samsung",
                model = "SM-X999X",
                features = setOf("com.samsung.hardware.active_key"),
            ),
        )
    }

    @Test
    fun `alt feature flag naming also honored`() {
        // Documented as a defensive fallback in case Samsung publishes
        // the flag under the `com.samsung.feature.*` namespace instead
        // of `com.samsung.hardware.*`.
        assertTrue(
            SamsungActiveKey.isSupportedInternal(
                manufacturer = "samsung",
                model = "SM-X999X",
                features = setOf("com.samsung.feature.active_key"),
            ),
        )
    }

    @Test
    fun `feature flag on non-Samsung device → not supported`() {
        assertFalse(
            SamsungActiveKey.isSupportedInternal(
                manufacturer = "Google",
                model = "Pixel 9 Pro",
                features = setOf("com.samsung.hardware.active_key"),
            ),
        )
    }

    // ============================================================
    // Edge cases
    // ============================================================

    @Test
    fun `blank manufacturer → not supported`() {
        assertFalse(
            SamsungActiveKey.isSupportedInternal(
                manufacturer = "",
                model = "SM-X306B",
                features = emptySet(),
            ),
        )
    }

    @Test
    fun `blank model on Samsung → not supported`() {
        assertFalse(
            SamsungActiveKey.isSupportedInternal(
                manufacturer = "samsung",
                model = "",
                features = emptySet(),
            ),
        )
    }

    @Test
    fun `mixed-case manufacturer still matches`() {
        // Build.MANUFACTURER is empirically all-lowercase "samsung" on
        // real hardware, but be tolerant of a future firmware quirk.
        assertTrue(
            SamsungActiveKey.isSupportedInternal(
                manufacturer = "SAMSUNG",
                model = "SM-X306B",
                features = emptySet(),
            ),
        )
    }

    @Test
    fun `mixed-case model still matches`() {
        assertTrue(
            SamsungActiveKey.isSupportedInternal(
                manufacturer = "samsung",
                model = "sm-x306b",
                features = emptySet(),
            ),
        )
    }
}
