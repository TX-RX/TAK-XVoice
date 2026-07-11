package com.atakmap.android.xv.aina

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Coverage for the External Button kind-resolution precedence. Pins
 * the semantic contract that
 * [com.atakmap.android.xv.plugin.XvMapComponent.resolveConnectKind]
 * and [com.atakmap.android.xv.plugin.XvMapComponent.connectSavedExternalButton]
 * rely on:
 *
 *  - a known-BLE-PTT membership always beats the classifier and any
 *    persisted hint — this is the invariant that closes the
 *    2026-07-11 field bug where a MAC added to knownBlePtt AFTER
 *    being picked as the External Button was surviving a stale
 *    persisted kind across cold launches;
 *  - the SDP-based classifier's answer wins over the persisted hint
 *    when it's a concrete "v1" / "v2" / "ble-hid";
 *  - a classifier "auto" result is treated as a punt so the hint
 *    can recover the reader;
 *  - the persisted hint is the last-resort fallback so a bonded
 *    device the classifier can no longer see (e.g. a bonded puck
 *    Android forgot how to enumerate over SDP) still spins up a
 *    reader instead of collapsing to "auto" = no reader;
 *  - "auto" is the terminal fallback when nothing else has an
 *    opinion.
 *
 * Uses placeholder MACs implicitly via the parameter names — the
 * resolver takes booleans / strings, not MACs, because the MAC
 * lookup is a caller responsibility.
 */
class ExternalButtonKindResolverTest {
    // ---- Known-BLE-PTT wins everything ----

    @Test
    fun `knownBlePtt beats classifier v1`() {
        // The 2026-07-11 field bug's canonical case: MAC is in
        // knownBlePtt but the SDP classifier said SPP. Without the
        // knownBlePtt precedence rule, the stale classification wins
        // and the operator's puck presses silently drop.
        assertEquals(
            "knownBlePtt membership must override an SPP classifier result",
            "ble-hid",
            ExternalButtonKindResolver.resolve(
                isInKnownBlePtt = true,
                classifierResult = "v1",
                persistedHint = null,
            ),
        )
    }

    @Test
    fun `knownBlePtt beats classifier v2`() {
        assertEquals(
            "ble-hid",
            ExternalButtonKindResolver.resolve(
                isInKnownBlePtt = true,
                classifierResult = "v2",
                persistedHint = null,
            ),
        )
    }

    @Test
    fun `knownBlePtt beats stale persisted hint`() {
        // The 2026-07-11 field-report second variant: operator picked
        // the bonded MAC before adding it to knownBlePtt, persisted
        // kind was written as "v1" from the classifier, then the
        // scan-and-add wrote the MAC into knownBlePtt. The persisted
        // hint on next cold launch must not win over the fresh
        // knownBlePtt precedence.
        assertEquals(
            "ble-hid",
            ExternalButtonKindResolver.resolve(
                isInKnownBlePtt = true,
                classifierResult = null,
                persistedHint = "v1",
            ),
        )
    }

    @Test
    fun `knownBlePtt with no other inputs still resolves to ble-hid`() {
        assertEquals(
            "ble-hid",
            ExternalButtonKindResolver.resolve(
                isInKnownBlePtt = true,
                classifierResult = null,
                persistedHint = null,
            ),
        )
    }

    // ---- Classifier wins when its result is concrete ----

    @Test
    fun `classifier v1 wins over persisted hint`() {
        assertEquals(
            "v1",
            ExternalButtonKindResolver.resolve(
                isInKnownBlePtt = false,
                classifierResult = "v1",
                persistedHint = "ble-hid",
            ),
        )
    }

    @Test
    fun `classifier v2 wins over persisted hint`() {
        assertEquals(
            "v2",
            ExternalButtonKindResolver.resolve(
                isInKnownBlePtt = false,
                classifierResult = "v2",
                persistedHint = "v1",
            ),
        )
    }

    @Test
    fun `classifier ble-hid wins over persisted hint`() {
        assertEquals(
            "ble-hid",
            ExternalButtonKindResolver.resolve(
                isInKnownBlePtt = false,
                classifierResult = "ble-hid",
                persistedHint = "v1",
            ),
        )
    }

    // ---- Classifier "auto" is a punt: hint gets a chance ----

    @Test
    fun `classifier auto lets persisted hint win`() {
        // "auto" from the classifier is the "I don't know" answer —
        // don't let it flatten a real hint. This is the recovery
        // path for a device the classifier can no longer see.
        assertEquals(
            "v2",
            ExternalButtonKindResolver.resolve(
                isInKnownBlePtt = false,
                classifierResult = "auto",
                persistedHint = "v2",
            ),
        )
    }

    @Test
    fun `classifier auto case-insensitive`() {
        // Case-insensitive so a caller passing "AUTO" or "Auto"
        // doesn't accidentally pin the reader to a bad kind.
        assertEquals(
            "ble-hid",
            ExternalButtonKindResolver.resolve(
                isInKnownBlePtt = false,
                classifierResult = "AUTO",
                persistedHint = "ble-hid",
            ),
        )
    }

    // ---- Persisted hint as last-resort ----

    @Test
    fun `null classifier lets persisted hint win`() {
        // Classifier could not run (adapter null, device not bonded,
        // SDP threw). Fall back to the hint so a bonded-but-
        // unclassifiable puck still gets a reader.
        assertEquals(
            "ble-hid",
            ExternalButtonKindResolver.resolve(
                isInKnownBlePtt = false,
                classifierResult = null,
                persistedHint = "ble-hid",
            ),
        )
    }

    @Test
    fun `blank classifier lets persisted hint win`() {
        assertEquals(
            "v1",
            ExternalButtonKindResolver.resolve(
                isInKnownBlePtt = false,
                classifierResult = "",
                persistedHint = "v1",
            ),
        )
    }

    @Test
    fun `whitespace classifier lets persisted hint win`() {
        assertEquals(
            "v2",
            ExternalButtonKindResolver.resolve(
                isInKnownBlePtt = false,
                classifierResult = "   ",
                persistedHint = "v2",
            ),
        )
    }

    // ---- Terminal fallback: "auto" ----

    @Test
    fun `no inputs at all resolves to auto`() {
        assertEquals(
            "auto",
            ExternalButtonKindResolver.resolve(
                isInKnownBlePtt = false,
                classifierResult = null,
                persistedHint = null,
            ),
        )
    }

    @Test
    fun `classifier auto and no hint resolves to auto`() {
        assertEquals(
            "auto",
            ExternalButtonKindResolver.resolve(
                isInKnownBlePtt = false,
                classifierResult = "auto",
                persistedHint = null,
            ),
        )
    }

    @Test
    fun `classifier auto and hint auto resolves to auto`() {
        assertEquals(
            "auto",
            ExternalButtonKindResolver.resolve(
                isInKnownBlePtt = false,
                classifierResult = "auto",
                persistedHint = "auto",
            ),
        )
    }

    @Test
    fun `blank hint falls through to auto`() {
        assertEquals(
            "auto",
            ExternalButtonKindResolver.resolve(
                isInKnownBlePtt = false,
                classifierResult = null,
                persistedHint = "",
            ),
        )
    }

    @Test
    fun `whitespace hint falls through to auto`() {
        assertEquals(
            "auto",
            ExternalButtonKindResolver.resolve(
                isInKnownBlePtt = false,
                classifierResult = null,
                persistedHint = "   ",
            ),
        )
    }

    // ---- Trimming ----

    @Test
    fun `classifier result is trimmed`() {
        // Defensive — no known caller passes untrimmed strings but
        // the resolver's contract is to treat leading/trailing
        // whitespace as insignificant, matching the rest of the XV
        // string-handling surface.
        assertEquals(
            "v2",
            ExternalButtonKindResolver.resolve(
                isInKnownBlePtt = false,
                classifierResult = "  v2  ",
                persistedHint = null,
            ),
        )
    }

    @Test
    fun `persisted hint is trimmed`() {
        assertEquals(
            "ble-hid",
            ExternalButtonKindResolver.resolve(
                isInKnownBlePtt = false,
                classifierResult = null,
                persistedHint = "  ble-hid  ",
            ),
        )
    }
}
