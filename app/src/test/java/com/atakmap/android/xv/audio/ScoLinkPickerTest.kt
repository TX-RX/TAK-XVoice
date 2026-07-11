package com.atakmap.android.xv.audio

import android.media.AudioDeviceInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pure-Kotlin coverage for ScoLink's BT comm-device selector. Production
 * code adapts AudioDeviceInfo → Candidate; the selector itself is pure.
 * Pinned in tests because picking the wrong BT device → audio routes to
 * the wrong speakermic → silent failure in the field.
 */
class ScoLinkPickerTest {
    private fun sco(mac: String) = ScoLink.Candidate(AudioDeviceInfo.TYPE_BLUETOOTH_SCO, mac)

    private fun a2dp(mac: String) = ScoLink.Candidate(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, mac)

    private fun otherType(mac: String) = ScoLink.Candidate(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, mac)

    @Test
    fun `pinned MAC matched on SCO wins absolutely`() {
        val list =
            listOf(
                sco("AA:11:11:11:11:11"),
                sco("AA:BB:CC:DD:EE:FF"), // pinned AINA
                a2dp("AA:BB:CC:DD:EE:FF"),
            )
        val pick = ScoLink.pickBtCommDeviceFromCandidates(list, "AA:BB:CC:DD:EE:FF")
        assertEquals(AudioDeviceInfo.TYPE_BLUETOOTH_SCO, pick!!.type)
        assertEquals("AA:BB:CC:DD:EE:FF", pick.mac)
    }

    @Test
    fun `pinned MAC match is case-insensitive`() {
        // Operators sometimes type MACs in different case via settings;
        // pinned MAC storage might keep the original capitalization.
        val list = listOf(sco("AA:BB:CC:DD:EE:FF"))
        val pick = ScoLink.pickBtCommDeviceFromCandidates(list, "aa:bb:cc:dd:ee:ff")
        assertEquals("AA:BB:CC:DD:EE:FF", pick!!.mac)
    }

    @Test
    fun `pinned MAC falls back to A2DP when the same device has no SCO profile`() {
        // Some BT devices expose only A2DP (Bluetooth speakers, JBL etc.)
        // — the pin should still match if the operator wants that device
        // even though TX won't work without SCO.
        val list = listOf(a2dp("AA:BB:CC:DD:EE:FF"))
        val pick = ScoLink.pickBtCommDeviceFromCandidates(list, "AA:BB:CC:DD:EE:FF")
        assertEquals(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, pick!!.type)
    }

    @Test
    fun `pinned MAC missing falls through to first-SCO`() {
        val list =
            listOf(
                sco("AA:11:11:11:11:11"),
                a2dp("BB:22:22:22:22:22"),
            )
        val pick = ScoLink.pickBtCommDeviceFromCandidates(list, "ZZ:99:99:99:99:99")
        // Pinned MAC not in list; falls through to first-SCO order.
        assertEquals(AudioDeviceInfo.TYPE_BLUETOOTH_SCO, pick!!.type)
        assertEquals("AA:11:11:11:11:11", pick.mac)
    }

    @Test
    fun `null pinned MAC picks first SCO in iteration order`() {
        val list =
            listOf(
                a2dp("XX:00:00:00:00:00"),
                sco("AA:11:11:11:11:11"),
                sco("BB:22:22:22:22:22"),
            )
        val pick = ScoLink.pickBtCommDeviceFromCandidates(list, null)
        assertEquals("AA:11:11:11:11:11", pick!!.mac)
    }

    @Test
    fun `blank pinned MAC behaves like null`() {
        val list = listOf(sco("AA:11"))
        val pick = ScoLink.pickBtCommDeviceFromCandidates(list, "   ")
        assertEquals("AA:11", pick!!.mac)
    }

    @Test
    fun `no SCO falls through to first A2DP`() {
        val list = listOf(a2dp("AA:11"), a2dp("BB:22"))
        val pick = ScoLink.pickBtCommDeviceFromCandidates(list, null)
        assertEquals(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, pick!!.type)
        assertEquals("AA:11", pick.mac)
    }

    @Test
    fun `empty candidate list returns null`() {
        assertNull(ScoLink.pickBtCommDeviceFromCandidates(emptyList(), null))
        assertNull(ScoLink.pickBtCommDeviceFromCandidates(emptyList(), "AA:BB:CC:DD:EE:FF"))
    }

    @Test
    fun `candidates of unrelated types are ignored even when MAC matches`() {
        // BUILTIN_SPEAKER with a MAC that happens to match (won't ever
        // happen in practice but the selector must be defensive).
        val list = listOf(otherType("AA:BB:CC:DD:EE:FF"))
        val pick = ScoLink.pickBtCommDeviceFromCandidates(list, "AA:BB:CC:DD:EE:FF")
        assertNull("non-BT candidates must be ignored entirely", pick)
    }

    @Test
    fun `SCO preferred over A2DP when both present for the SAME pinned MAC`() {
        // Device exposes both profiles; SCO must win because TX needs it.
        val list =
            listOf(
                a2dp("AA:BB:CC:DD:EE:FF"),
                sco("AA:BB:CC:DD:EE:FF"),
            )
        val pick = ScoLink.pickBtCommDeviceFromCandidates(list, "AA:BB:CC:DD:EE:FF")
        assertEquals(AudioDeviceInfo.TYPE_BLUETOOTH_SCO, pick!!.type)
    }

    @Test
    fun `SCO-on-different-MAC loses to A2DP-on-pinned-MAC (the pin wins)`() {
        // Operator pinned device X (which only has A2DP). Another device
        // Y is present with SCO. The pin must win — we route to X even
        // though TX won't work, rather than silently going to Y.
        val list =
            listOf(
                sco("YY:99"),
                a2dp("XX:11"),
            )
        val pick = ScoLink.pickBtCommDeviceFromCandidates(list, "XX:11")
        assertEquals("XX:11", pick!!.mac)
        assertEquals(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, pick.type)
    }

    // ============================================================
    // Two-input selector — "Audio device" override precedence
    // ============================================================
    //
    // Regression coverage for the bug where a persisted primary AINA
    // (preferredBtMacHint) silently swallowed the operator's explicit
    // "Audio device" pick (outputBtOverrideMac). Design intent: the
    // override wins absolutely when set + HFP-capable; the hint is the
    // fallback (either no override at all, or an A2DP-only override).

    @Test
    fun `override wins over hint when both are set and override is HFP-capable`() {
        // AINA on AA:BB… (has SCO), headphones on 11:22… (has SCO too —
        // simulating an HFP-capable BT headset). The override must win.
        val list =
            listOf(
                sco("AA:BB:CC:DD:EE:FF"), // hint (AINA)
                sco("11:22:33:44:55:66"), // override (headphones)
            )
        val result =
            ScoLink.pickBtCommDeviceWithOverride(
                candidates = list,
                overrideMac = "11:22:33:44:55:66",
                hintMac = "AA:BB:CC:DD:EE:FF",
            )
        assertEquals("11:22:33:44:55:66", result.pick!!.mac)
        assertEquals(AudioDeviceInfo.TYPE_BLUETOOTH_SCO, result.pick.type)
        assertEquals(false, result.overrideMissed)
    }

    @Test
    fun `override match is case-insensitive`() {
        val list = listOf(sco("11:22:33:44:55:66"))
        val result =
            ScoLink.pickBtCommDeviceWithOverride(
                candidates = list,
                overrideMac = "11:22:33:44:55:66".lowercase(),
                hintMac = null,
            )
        assertEquals("11:22:33:44:55:66", result.pick!!.mac)
        assertEquals(false, result.overrideMissed)
    }

    @Test
    fun `only hint set — behaves like legacy single-arg selector (regression)`() {
        // Regression coverage: an operator with a primary AINA and NO
        // Audio-device override picked must still route to the AINA.
        val list =
            listOf(
                sco("AA:BB:CC:DD:EE:FF"), // hint (AINA)
                sco("99:99:99:99:99:99"), // some other BT
            )
        val result =
            ScoLink.pickBtCommDeviceWithOverride(
                candidates = list,
                overrideMac = null,
                hintMac = "AA:BB:CC:DD:EE:FF",
            )
        assertEquals("AA:BB:CC:DD:EE:FF", result.pick!!.mac)
        assertEquals(false, result.overrideMissed)
    }

    @Test
    fun `blank override treated like null — hint drives selection`() {
        val list = listOf(sco("AA:BB:CC:DD:EE:FF"))
        val result =
            ScoLink.pickBtCommDeviceWithOverride(
                candidates = list,
                overrideMac = "   ",
                hintMac = "AA:BB:CC:DD:EE:FF",
            )
        assertEquals("AA:BB:CC:DD:EE:FF", result.pick!!.mac)
        assertEquals(false, result.overrideMissed)
    }

    @Test
    fun `override MAC absent from candidates — falls back to hint and flags miss`() {
        // A2DP-only headphones don't advertise HFP → they don't appear
        // in availableCommunicationDevices at all. Override MAC won't
        // match any candidate. Fall back to the hint AINA and flag the
        // miss so the production wrapper can log a warning.
        val list =
            listOf(
                sco("AA:BB:CC:DD:EE:FF"), // hint (AINA)
            )
        val result =
            ScoLink.pickBtCommDeviceWithOverride(
                candidates = list,
                overrideMac = "11:22:33:44:55:66", // A2DP-only headphones
                hintMac = "AA:BB:CC:DD:EE:FF",
            )
        assertEquals("AA:BB:CC:DD:EE:FF", result.pick!!.mac)
        assertEquals(true, result.overrideMissed)
    }

    @Test
    fun `override missed and no hint — falls through to first-SCO with miss flag`() {
        // Operator set an Audio-device override, no primary AINA. The
        // override isn't currently present. Fall through to whatever
        // the single-arg selector picks with null hint (first SCO) so
        // audio at least routes somewhere sane, and flag the miss.
        val list =
            listOf(
                sco("77:77:77:77:77:77"),
                sco("88:88:88:88:88:88"),
            )
        val result =
            ScoLink.pickBtCommDeviceWithOverride(
                candidates = list,
                overrideMac = "11:22:33:44:55:66",
                hintMac = null,
            )
        assertEquals("77:77:77:77:77:77", result.pick!!.mac)
        assertEquals(true, result.overrideMissed)
    }

    @Test
    fun `override with A2DP-only match (both profiles absent) still returns A2DP`() {
        // Some BT devices expose only A2DP as a comm device on certain
        // handsets — the override should still match. Not the primary
        // field case (A2DP normally isn't in availableCommunicationDevices)
        // but keep behavior consistent with the single-arg selector.
        val list = listOf(a2dp("11:22:33:44:55:66"))
        val result =
            ScoLink.pickBtCommDeviceWithOverride(
                candidates = list,
                overrideMac = "11:22:33:44:55:66",
                hintMac = null,
            )
        assertEquals("11:22:33:44:55:66", result.pick!!.mac)
        assertEquals(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, result.pick.type)
        assertEquals(false, result.overrideMissed)
    }

    @Test
    fun `override prefers SCO over A2DP for the same MAC`() {
        // Device advertises both profiles under the same MAC. Override
        // must land on the SCO endpoint — TX needs SCO, and the single-
        // arg selector applies the same rule.
        val list =
            listOf(
                a2dp("11:22:33:44:55:66"),
                sco("11:22:33:44:55:66"),
            )
        val result =
            ScoLink.pickBtCommDeviceWithOverride(
                candidates = list,
                overrideMac = "11:22:33:44:55:66",
                hintMac = null,
            )
        assertEquals(AudioDeviceInfo.TYPE_BLUETOOTH_SCO, result.pick!!.type)
        assertEquals(false, result.overrideMissed)
    }

    // ============================================================
    // hintMissed flag (2026-07-11 extension — Auto + primary AINA)
    // ============================================================
    //
    // Regression coverage for the field observation where Auto + primary
    // AINA + a secondary BT headset (Shokz OpenMove) resulted in audio
    // routing to the headset instead of the AINA. The selector still
    // returns the fallback pick (whatever the single-arg selector picks
    // from the candidate list) but the caller uses [hintMissed] to
    // decide whether to run the active-HFP-nudge state machine against
    // the hint MAC.

    @Test
    fun `no override, hint present in candidates — hintMissed false`() {
        // Baseline: hint is set AND matches a BT candidate. No nudge
        // needed; the caller pins directly to the hint.
        val list =
            listOf(
                sco("AA:BB:CC:DD:EE:FF"), // hint (AINA)
                sco("11:22:33:44:55:66"), // OpenMove
            )
        val result =
            ScoLink.pickBtCommDeviceWithOverride(
                candidates = list,
                overrideMac = null,
                hintMac = "AA:BB:CC:DD:EE:FF",
            )
        assertEquals("AA:BB:CC:DD:EE:FF", result.pick!!.mac)
        assertEquals(false, result.overrideMissed)
        assertEquals(false, result.hintMissed)
    }

    @Test
    fun `no override, hint missing from candidates — hintMissed true`() {
        // Field 2026-07-11: Auto path with primary AINA set. The AINA
        // MAC isn't in the candidate list because the OS's active HFP
        // is a different device (Shokz OpenMove). The selector still
        // returns the OpenMove SCO as a fallback pick — the caller uses
        // [hintMissed] to fire the active-HFP nudge against the AINA
        // MAC before returning that fallback.
        val list =
            listOf(
                sco("11:22:33:44:55:66"), // OpenMove — the only comm device
            )
        val result =
            ScoLink.pickBtCommDeviceWithOverride(
                candidates = list,
                overrideMac = null,
                hintMac = "AA:BB:CC:DD:EE:FF", // AINA — not in candidates
            )
        assertEquals("11:22:33:44:55:66", result.pick!!.mac)
        assertEquals(false, result.overrideMissed)
        assertEquals(true, result.hintMissed)
    }

    @Test
    fun `no override, null hint — hintMissed false (nothing to nudge toward)`() {
        // No hint = no primary AINA paired. Nothing to nudge; the flag
        // stays false and the caller's existing hint / auto chain stands.
        val list = listOf(sco("11:22:33:44:55:66"))
        val result =
            ScoLink.pickBtCommDeviceWithOverride(
                candidates = list,
                overrideMac = null,
                hintMac = null,
            )
        assertEquals("11:22:33:44:55:66", result.pick!!.mac)
        assertEquals(false, result.overrideMissed)
        assertEquals(false, result.hintMissed)
    }

    @Test
    fun `no override, blank hint — hintMissed false`() {
        val list = listOf(sco("11:22:33:44:55:66"))
        val result =
            ScoLink.pickBtCommDeviceWithOverride(
                candidates = list,
                overrideMac = null,
                hintMac = "   ",
            )
        assertEquals(false, result.hintMissed)
    }

    @Test
    fun `no override, hint absent, empty candidates — hintMissed true, pick null`() {
        // No BT at all — but the operator still has a hint set. The flag
        // fires so the caller can attempt the nudge even when there's
        // nothing to fall back to (the nudge itself is a no-op with no
        // candidates present; the caller still gets accurate state).
        val result =
            ScoLink.pickBtCommDeviceWithOverride(
                candidates = emptyList(),
                overrideMac = null,
                hintMac = "AA:BB:CC:DD:EE:FF",
            )
        assertNull(result.pick)
        assertEquals(true, result.hintMissed)
    }

    @Test
    fun `override missed does not also set hintMissed`() {
        // The two flags are mutually exclusive. When the override is
        // set-but-missed, the caller enters the override-side of the
        // nudge; hintMissed stays false regardless of whether the hint
        // matches or not.
        val list = listOf(sco("77:77:77:77:77:77")) // neither override nor hint
        val result =
            ScoLink.pickBtCommDeviceWithOverride(
                candidates = list,
                overrideMac = "AA:BB:CC:DD:EE:FF",
                hintMac = "11:22:33:44:55:66",
            )
        assertEquals(true, result.overrideMissed)
        assertEquals(false, result.hintMissed)
    }
}
