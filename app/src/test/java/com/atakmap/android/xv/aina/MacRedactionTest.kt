package com.atakmap.android.xv.aina

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure-function coverage for [redactMac]. CLAUDE.md sensitive-content
 * rules require operator-tied MACs to never appear verbatim in
 * committed log output; the AINA readers all funnel address strings
 * through this helper. Behaviour pinned:
 *  - first + last octet preserved (operator-side correlation aid),
 *  - middle four octets replaced with `XX`,
 *  - case-insensitive on the hex,
 *  - idempotent on already-redacted input,
 *  - no-op on strings that aren't MAC-shaped at all.
 */
class MacRedactionTest {
    @Test
    fun `preserves first and last octet with XX middle`() {
        assertEquals("AA:XX:XX:XX:XX:FF", redactMac("AA:BB:CC:DD:EE:FF"))
    }

    @Test
    fun `accepts lowercase hex`() {
        // The Android stack returns MACs uppercase, but operator
        // configs and adb shell output are routinely lowercase — the
        // redactor must not pass those through unchanged.
        assertEquals("aa:XX:XX:XX:XX:ff", redactMac("aa:bb:cc:dd:ee:ff"))
    }

    @Test
    fun `accepts mixed case hex`() {
        assertEquals("Ab:XX:XX:XX:XX:Ef", redactMac("Ab:CD:eF:01:23:Ef"))
    }

    @Test
    fun `already-redacted input is passed through unchanged`() {
        // Idempotent: calling redactMac twice yields the same value.
        // Guards against double-redaction artefacts ("AA:XX:XX:XX:XX:FF"
        // -> "AA:XX:XX:XX:XX:FF") if a caller defensively re-routes.
        assertEquals("AA:XX:XX:XX:XX:FF", redactMac("AA:XX:XX:XX:XX:FF"))
    }

    @Test
    fun `already-redacted input with lowercase x is also passed through`() {
        // The chosen placeholder happens to be uppercase XX, but
        // accept lowercase too so callers that lowercase their logs
        // don't re-mangle.
        assertEquals("aa:xx:xx:xx:xx:ff", redactMac("aa:xx:xx:xx:xx:ff"))
    }

    @Test
    fun `non-MAC string is returned unchanged`() {
        // Defensive: the helper is called liberally; if a caller
        // hands it a name, debug label, or empty placeholder it
        // should not mangle it into something MAC-looking.
        assertEquals("not-a-mac", redactMac("not-a-mac"))
        assertEquals("AINA APTT V1", redactMac("AINA APTT V1"))
        assertEquals("AA:BB:CC", redactMac("AA:BB:CC")) // too short
        assertEquals("GG:HH:II:JJ:KK:LL", redactMac("GG:HH:II:JJ:KK:LL")) // not hex
    }

    @Test
    fun `null or empty MAC returns placeholder rather than crashing`() {
        assertEquals("??", redactMac(null))
        assertEquals("??", redactMac(""))
    }

    @Test
    fun `MAC with dash separators is passed through unchanged`() {
        // We only canonicalise colon-separated form. Dash-separated
        // (Windows-style) MACs hit the no-op path so the log shows
        // raw input — bug-finding aid: surfaces wrong-shape MACs.
        assertEquals("AA-BB-CC-DD-EE-FF", redactMac("AA-BB-CC-DD-EE-FF"))
    }
}
