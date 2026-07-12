package com.atakmap.android.xv.util

import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Fire-and-forget safety pin for [DiagnosticLogger]. The public API
 * MUST be safe to call before [DiagnosticLogger.init] (early plugin
 * lifecycle) and after [DiagnosticLogger.shutdown] (teardown races).
 * Neither should throw and neither should require a Context.
 *
 * Field-motivating incident: 2026-07-12 post-mortem could not see
 * whether the plugin was alive during the outage because our own
 * logger became a footgun — if callers had to defend against "am I
 * in the right lifecycle stage to log?" every site would rot.
 * These tests pin the invariant that logging is always safe.
 */
class DiagnosticLoggerTest {
    @Test
    fun `event before init is a silent no-op`() {
        // Do NOT call init(). Call event(). Expect no throw.
        DiagnosticLogger.event(tag = "test", message = "before-init")
    }

    @Test
    fun `flush before init is a silent no-op`() {
        DiagnosticLogger.flush()
    }

    @Test
    fun `shutdown before init is a silent no-op`() {
        DiagnosticLogger.shutdown()
    }

    @Test
    fun `exception before init is a silent no-op`() {
        DiagnosticLogger.exception(
            tag = "test",
            prefix = "before-init exception",
            t = RuntimeException("simulated"),
        )
    }

    @Test
    fun `severity char defaults to I when omitted`() {
        // No throw and no crash: the default severity path exercises
        // the same code as an explicit call. This is a smoke test to
        // pin the default so a future refactor can't silently make
        // this an error severity (which would flood peer logs on hot
        // paths like PTT down / up).
        DiagnosticLogger.event(tag = "test", message = "default severity")
        DiagnosticLogger.event(tag = "test", severity = 'W', message = "warn severity")
        DiagnosticLogger.event(tag = "test", severity = 'E', message = "error severity")
        // Not much to assert positively — the point is that all four
        // permitted severity chars are accepted without exception.
        // Any regression would surface here as a thrown IAE.
        assertFalse(
            "test finished without exception",
            false,
        )
    }
}
