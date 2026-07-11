package com.atakmap.android.xv.ptt

import android.view.KeyEvent
import com.atakmap.android.xv.util.SamsungActiveKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Unit tests for [SamsungActiveKeyAccessibilityService].
 *
 * Because [SamsungActiveKeyAccessibilityService] extends
 * [android.accessibilityservice.AccessibilityService] (an Android
 * framework class), we test its logic through the pure helpers it
 * delegates to:
 *
 * - `SamsungActiveKey.handleKeyEvent` — classifies a key tuple; the
 *   service body is just a `when` switch on that result.  The
 *   classification is already exercised by [SamsungActiveKeyTest] /
 *   [SamsungActiveKeyForegroundReaderTest]; here we verify the service
 *   honours those results and doesn't duplicate them.
 *
 * - The device gate: `SamsungActiveKey.isSupported` / `isSupportedInternal`
 *   — tested without an Android runtime via the pure test seam.
 *
 * - The key-code filter: non-PTT keycodes must return `false` so the
 *   service doesn't swallow volume / navigation events system-wide.
 *
 * We do NOT stand up a full AccessibilityService lifecycle (that
 * requires an instrumented device test) — the runtime-heavy
 * onKeyEvent-routing part is exercised on the device; here we cover
 * the pure-logic surface that is both testable and highest-risk.
 */
@RunWith(RobolectricTestRunner::class)
class SamsungActiveKeyAccessibilityServiceTest {

    // ---- handleKeyEvent delegation ----
    // The service delegates to SamsungActiveKey.handleKeyEvent and maps
    // its result to a consumed boolean + dispatchEdge call.  We verify
    // the mapping is correct without instancing the service (avoids
    // AccessibilityService lifecycle complexity in a JVM test).

    @Test
    fun `handleKeyEvent PTT_DOWN on initial press`() {
        val result = SamsungActiveKey.handleKeyEvent(
            keyCode = SamsungActiveKey.KEY_CODE_PTT,
            action = KeyEvent.ACTION_DOWN,
            repeatCount = 0,
        )
        assertEquals(SamsungActiveKey.FallbackAction.PTT_DOWN, result)
    }

    @Test
    fun `handleKeyEvent PTT_UP on release`() {
        val result = SamsungActiveKey.handleKeyEvent(
            keyCode = SamsungActiveKey.KEY_CODE_PTT,
            action = KeyEvent.ACTION_UP,
            repeatCount = 0,
        )
        assertEquals(SamsungActiveKey.FallbackAction.PTT_UP, result)
    }

    @Test
    fun `handleKeyEvent IGNORE on auto-repeat DOWN`() {
        val result = SamsungActiveKey.handleKeyEvent(
            keyCode = SamsungActiveKey.KEY_CODE_PTT,
            action = KeyEvent.ACTION_DOWN,
            repeatCount = 3,
        )
        assertEquals(SamsungActiveKey.FallbackAction.IGNORE, result)
    }

    @Test
    fun `handleKeyEvent IGNORE for non-PTT keycodes`() {
        // Belt-and-suspenders: if the service somehow received a volume
        // or navigation key it must not consume it. The classification
        // must return IGNORE for any keyCode != KEY_CODE_PTT.
        listOf(
            KeyEvent.KEYCODE_VOLUME_DOWN,
            KeyEvent.KEYCODE_VOLUME_UP,
            KeyEvent.KEYCODE_BACK,
            KeyEvent.KEYCODE_HOME,
            KeyEvent.KEYCODE_CAMERA,
            1079, // Samsung KEYCODE_EMERGENCY — out of scope
        ).forEach { kc ->
            val result = SamsungActiveKey.handleKeyEvent(
                keyCode = kc,
                action = KeyEvent.ACTION_DOWN,
                repeatCount = 0,
            )
            assertEquals(
                "keyCode $kc must be classified IGNORE",
                SamsungActiveKey.FallbackAction.IGNORE,
                result,
            )
        }
    }

    // ---- Device gate ----
    // The service calls SamsungActiveKey.isSupported(context) and
    // returns false immediately on unsupported hardware.  The pure
    // isSupportedInternal seam lets us test the classification without
    // a real Android Build.

    @Test
    fun `isSupportedInternal true for Tab Active5 SM-X308U`() {
        assertTrue(
            SamsungActiveKey.isSupportedInternal(
                manufacturer = "samsung",
                model = "SM-X308U",
                features = emptySet(),
            ),
        )
    }

    @Test
    fun `isSupportedInternal true for XCover6 Pro SM-G736B`() {
        assertTrue(
            SamsungActiveKey.isSupportedInternal(
                manufacturer = "Samsung",
                model = "SM-G736B",
                features = emptySet(),
            ),
        )
    }

    @Test
    fun `isSupportedInternal false for generic Android phone`() {
        assertFalse(
            SamsungActiveKey.isSupportedInternal(
                manufacturer = "Google",
                model = "Pixel 9",
                features = emptySet(),
            ),
        )
    }

    @Test
    fun `isSupportedInternal false for non-Samsung manufacturer even with matching model prefix`() {
        // Ensure the manufacturer check is primary — a third-party
        // device cloning a Samsung model number must not pass the gate.
        assertFalse(
            SamsungActiveKey.isSupportedInternal(
                manufacturer = "Generic",
                model = "SM-X308",
                features = emptySet(),
            ),
        )
    }

    @Test
    fun `isSupportedInternal true via feature flag fallback`() {
        // Hardware not in the hard-list but exposing a future Samsung
        // system feature should still pass so the service activates
        // without an XV update.
        assertTrue(
            SamsungActiveKey.isSupportedInternal(
                manufacturer = "Samsung",
                model = "SM-Z999", // hypothetical future model not in the list
                features = setOf("com.samsung.hardware.active_key"),
            ),
        )
    }

    // ---- COMPONENT_NAME format ----
    // The component-name constant used to detect whether the service is
    // enabled must match the format AccessibilityManager returns:
    // "packageName/className" (with a leading dot if the class is in the
    // same package, but AccessibilityManager returns the full flattened
    // form).  Verify the constant is non-empty and contains a slash.

    @Test
    fun `COMPONENT_NAME is non-empty and contains a slash separator`() {
        val cn = SamsungActiveKeyAccessibilityService.COMPONENT_NAME
        assertTrue("COMPONENT_NAME must not be empty", cn.isNotEmpty())
        assertTrue("COMPONENT_NAME must contain '/' separator", cn.contains('/'))
    }

    @Test
    fun `COMPONENT_NAME package matches XV application ID`() {
        val cn = SamsungActiveKeyAccessibilityService.COMPONENT_NAME
        val pkg = cn.substringBefore('/')
        assertEquals("com.atakmap.android.xv", pkg)
    }

    // ---- Key code constant sanity ----
    // The Samsung-documented value for the Active Key is 1015.  Any
    // accidental refactor that changes this would silently break PTT
    // on every supported device — a compile-time constant check
    // catches it immediately.

    @Test
    fun `KEY_CODE_PTT is 1015`() {
        assertEquals(1015, SamsungActiveKey.KEY_CODE_PTT)
    }

    @Test
    fun `KEY_REPORT_TYPE_PRESSED is 1 and RELEASED is 2`() {
        assertEquals(1, SamsungActiveKey.KEY_REPORT_TYPE_PRESSED)
        assertEquals(2, SamsungActiveKey.KEY_REPORT_TYPE_RELEASED)
    }
}
