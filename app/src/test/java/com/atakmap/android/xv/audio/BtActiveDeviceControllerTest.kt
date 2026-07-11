package com.atakmap.android.xv.audio

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure-Kotlin coverage for the target-routing decision function used
 * by [ScoLink.handleTargetMissed]. Real reflective
 * [BtActiveDeviceController.trySetActive] behavior varies by OEM /
 * platform release; the decision matrix around it does not, and is
 * pinned here so future refactors don't regress the "don't spam the
 * switcher" or "try reflection before giving up" contracts.
 *
 * The state machine handles two entry points via a single "effective
 * target" concept:
 *
 *   - Explicit override — the operator picked a specific "Audio device"
 *     in Settings and it isn't the OS's currently-active HFP peer.
 *   - Primary AINA hint — Auto path with an AINA paired as primary
 *     speakermic, but the OS's active HFP is some other BT device
 *     (e.g. Shokz OpenMove that connected after the AINA). Added
 *     2026-07-11 to fix a field observation on Pixel 9 Pro / API 35.
 *
 * Both use the same reflection → switcher fallback → cooldown path;
 * the caller distinguishes them for toast wording only.
 */
class BtActiveDeviceControllerTest {
    private val overrideMac = "AA:BB:CC:DD:EE:FF"
    private val hintMac = "11:22:33:44:55:66"
    private val otherMac = "99:88:77:66:55:44"
    private val cooldown = 30_000L

    // ============================================================
    // Override path (PR #41 pinned behaviour — unchanged)
    // ============================================================

    @Test
    fun `override present in available set — PIN_DIRECT`() {
        val action =
            decideOverrideAction(
                overrideMac = overrideMac,
                hintMac = hintMac,
                availableMacs = setOf("AA:BB:CC:DD:EE:FF", "11:22:33:44:55:66"),
                reflectionCached = null,
                lastSwitcherLaunchMs = 0L,
                nowMs = 1_000L,
                switcherCooldownMs = cooldown,
            )
        assertEquals(OverrideRoutingAction.PIN_DIRECT, action)
    }

    @Test
    fun `override present is case-insensitive`() {
        // The comparator uppercases before hitting the set — a mixed-
        // case override MAC still matches an upper-cased available set.
        val action =
            decideOverrideAction(
                overrideMac = "aa:bb:cc:dd:ee:ff",
                hintMac = hintMac,
                availableMacs = setOf("AA:BB:CC:DD:EE:FF"),
                reflectionCached = null,
                lastSwitcherLaunchMs = 0L,
                nowMs = 1_000L,
                switcherCooldownMs = cooldown,
            )
        assertEquals(OverrideRoutingAction.PIN_DIRECT, action)
    }

    @Test
    fun `override absent, no reflection cache — TRY_REFLECTION`() {
        val action =
            decideOverrideAction(
                overrideMac = overrideMac,
                hintMac = hintMac,
                availableMacs = setOf("11:22:33:44:55:66"),
                reflectionCached = null,
                lastSwitcherLaunchMs = 0L,
                nowMs = 1_000L,
                switcherCooldownMs = cooldown,
            )
        assertEquals(OverrideRoutingAction.TRY_REFLECTION, action)
    }

    @Test
    fun `override absent, reflection SUCCESS but override still absent — LAUNCH_SWITCHER`() {
        // Defensive branch: reflection said the switch worked but the
        // override never appeared in availableCommunicationDevices.
        // Could be an OEM that returns Void on the reflective call
        // without actually honoring the switch, or a race with a
        // parallel platform-side re-enumeration. Either way, escalate
        // to the switcher so the operator can complete the swap.
        val action =
            decideOverrideAction(
                overrideMac = overrideMac,
                hintMac = hintMac,
                availableMacs = setOf("11:22:33:44:55:66"),
                reflectionCached = BtActiveDeviceController.TrySetActiveResult.Success,
                lastSwitcherLaunchMs = 0L,
                nowMs = 1_000L,
                switcherCooldownMs = cooldown,
            )
        assertEquals(OverrideRoutingAction.LAUNCH_SWITCHER, action)
    }

    @Test
    fun `override absent, reflection REFUSED_BY_PLATFORM, cooldown expired — LAUNCH_SWITCHER`() {
        val action =
            decideOverrideAction(
                overrideMac = overrideMac,
                hintMac = hintMac,
                availableMacs = setOf("11:22:33:44:55:66"),
                reflectionCached = BtActiveDeviceController.TrySetActiveResult.RefusedByPlatform,
                lastSwitcherLaunchMs = 0L,
                nowMs = cooldown + 1L,
                switcherCooldownMs = cooldown,
            )
        assertEquals(OverrideRoutingAction.LAUNCH_SWITCHER, action)
    }

    @Test
    fun `override absent, reflection REFUSED_BY_PLATFORM, cooldown not expired — PIN_HINT`() {
        // PTT hammered right after a switcher launch. Silent fall-back
        // to the hint prevents the panel from being yanked back in
        // front of ATAK on every burst.
        val action =
            decideOverrideAction(
                overrideMac = overrideMac,
                hintMac = hintMac,
                availableMacs = setOf("11:22:33:44:55:66"),
                reflectionCached = BtActiveDeviceController.TrySetActiveResult.RefusedByPlatform,
                lastSwitcherLaunchMs = 100L,
                nowMs = 100L + cooldown - 1L,
                switcherCooldownMs = cooldown,
            )
        assertEquals(OverrideRoutingAction.PIN_HINT, action)
    }

    @Test
    fun `override absent, reflection NOT_SUPPORTED, cooldown expired — LAUNCH_SWITCHER`() {
        // API < 28 / stripped ROM — same terminal outcome as
        // REFUSED_BY_PLATFORM: switcher is the only way forward.
        val action =
            decideOverrideAction(
                overrideMac = overrideMac,
                hintMac = hintMac,
                availableMacs = setOf("11:22:33:44:55:66"),
                reflectionCached = BtActiveDeviceController.TrySetActiveResult.NotSupported,
                lastSwitcherLaunchMs = 0L,
                nowMs = cooldown + 1L,
                switcherCooldownMs = cooldown,
            )
        assertEquals(OverrideRoutingAction.LAUNCH_SWITCHER, action)
    }

    @Test
    fun `override absent, reflection NOT_SUPPORTED, cooldown not expired — PIN_HINT`() {
        val action =
            decideOverrideAction(
                overrideMac = overrideMac,
                hintMac = hintMac,
                availableMacs = setOf("11:22:33:44:55:66"),
                reflectionCached = BtActiveDeviceController.TrySetActiveResult.NotSupported,
                lastSwitcherLaunchMs = 100L,
                nowMs = 100L + cooldown - 1L,
                switcherCooldownMs = cooldown,
            )
        assertEquals(OverrideRoutingAction.PIN_HINT, action)
    }

    @Test
    fun `override absent, reflection THREW, cooldown expired — LAUNCH_SWITCHER`() {
        val action =
            decideOverrideAction(
                overrideMac = overrideMac,
                hintMac = hintMac,
                availableMacs = setOf("11:22:33:44:55:66"),
                reflectionCached = BtActiveDeviceController.TrySetActiveResult.Threw(RuntimeException("boom")),
                lastSwitcherLaunchMs = 0L,
                nowMs = cooldown + 1L,
                switcherCooldownMs = cooldown,
            )
        assertEquals(OverrideRoutingAction.LAUNCH_SWITCHER, action)
    }

    // ============================================================
    // Hint path (2026-07-11 extension — Auto + primary AINA)
    // ============================================================

    @Test
    fun `override null, hint present in available set — PIN_DIRECT`() {
        // Effective target = hint. Hint is in the available set →
        // pin directly. No override → the hint IS the effective
        // target, not a fallback.
        val action =
            decideOverrideAction(
                overrideMac = null,
                hintMac = hintMac,
                availableMacs = setOf("11:22:33:44:55:66"),
                reflectionCached = null,
                lastSwitcherLaunchMs = 0L,
                nowMs = 1_000L,
                switcherCooldownMs = cooldown,
            )
        assertEquals(OverrideRoutingAction.PIN_DIRECT, action)
    }

    @Test
    fun `override blank, hint present in available set — PIN_DIRECT`() {
        // Same as above with a whitespace override — !isBlank guard
        // treats "   " as unset.
        val action =
            decideOverrideAction(
                overrideMac = "   ",
                hintMac = hintMac,
                availableMacs = setOf("11:22:33:44:55:66"),
                reflectionCached = null,
                lastSwitcherLaunchMs = 0L,
                nowMs = 1_000L,
                switcherCooldownMs = cooldown,
            )
        assertEquals(OverrideRoutingAction.PIN_DIRECT, action)
    }

    @Test
    fun `override null, hint absent from available set — TRY_REFLECTION on hint`() {
        // Field 2026-07-11: Auto path, primary AINA hint set, available
        // comm devices only include a different BT device (Shokz OpenMove
        // is the OS's active HFP peer). Attempt the reflection nudge
        // for the AINA MAC, same as the override case.
        val action =
            decideOverrideAction(
                overrideMac = null,
                hintMac = hintMac,
                availableMacs = setOf(otherMac.uppercase()),
                reflectionCached = null,
                lastSwitcherLaunchMs = 0L,
                nowMs = 1_000L,
                switcherCooldownMs = cooldown,
            )
        assertEquals(OverrideRoutingAction.TRY_REFLECTION, action)
    }

    @Test
    fun `override null, hint absent, reflection REFUSED, cooldown expired — LAUNCH_SWITCHER`() {
        // Pixel 14+ path in the hint-side flow. Reflection was refused
        // (platform-hardened setActiveDevice); cooldown has expired so
        // the switcher fires. Toast wording differs at the caller (hint-
        // side vs. override-side) but the decision itself is identical.
        val action =
            decideOverrideAction(
                overrideMac = null,
                hintMac = hintMac,
                availableMacs = setOf(otherMac.uppercase()),
                reflectionCached = BtActiveDeviceController.TrySetActiveResult.RefusedByPlatform,
                lastSwitcherLaunchMs = 0L,
                nowMs = cooldown + 1L,
                switcherCooldownMs = cooldown,
            )
        assertEquals(OverrideRoutingAction.LAUNCH_SWITCHER, action)
    }

    @Test
    fun `override null, hint absent, reflection REFUSED, cooldown not expired — PIN_HINT`() {
        // Cooldown protects the operator from a torrent of switcher
        // panels during rapid PTT — even on the hint-side path. Falls
        // through to whatever the caller's hint / auto chain picks
        // (typically the OS's active HFP peer).
        val action =
            decideOverrideAction(
                overrideMac = null,
                hintMac = hintMac,
                availableMacs = setOf(otherMac.uppercase()),
                reflectionCached = BtActiveDeviceController.TrySetActiveResult.RefusedByPlatform,
                lastSwitcherLaunchMs = 100L,
                nowMs = 100L + cooldown - 1L,
                switcherCooldownMs = cooldown,
            )
        assertEquals(OverrideRoutingAction.PIN_HINT, action)
    }

    @Test
    fun `override null, hint absent, reflection SUCCESS but hint still absent — LAUNCH_SWITCHER`() {
        // Same defensive branch as the override version: reflection
        // reported success but the OS didn't re-enumerate the hint in.
        val action =
            decideOverrideAction(
                overrideMac = null,
                hintMac = hintMac,
                availableMacs = setOf(otherMac.uppercase()),
                reflectionCached = BtActiveDeviceController.TrySetActiveResult.Success,
                lastSwitcherLaunchMs = 0L,
                nowMs = 1_000L,
                switcherCooldownMs = cooldown,
            )
        assertEquals(OverrideRoutingAction.LAUNCH_SWITCHER, action)
    }

    @Test
    fun `override null, hint null — PIN_HINT (nothing to nudge)`() {
        // Neither override nor hint is set — no effective target. Falls
        // through to the caller's existing "no hint, speaker" chain
        // without invoking the reflection / switcher path at all. Also
        // the zero-cost path when no BT is paired.
        val action =
            decideOverrideAction(
                overrideMac = null,
                hintMac = null,
                availableMacs = emptySet(),
                reflectionCached = null,
                lastSwitcherLaunchMs = 0L,
                nowMs = 1_000L,
                switcherCooldownMs = cooldown,
            )
        assertEquals(OverrideRoutingAction.PIN_HINT, action)
    }

    @Test
    fun `override null, hint blank — PIN_HINT`() {
        // Whitespace hint counts as unset, same as null.
        val action =
            decideOverrideAction(
                overrideMac = null,
                hintMac = "   ",
                availableMacs = emptySet(),
                reflectionCached = null,
                lastSwitcherLaunchMs = 0L,
                nowMs = 1_000L,
                switcherCooldownMs = cooldown,
            )
        assertEquals(OverrideRoutingAction.PIN_HINT, action)
    }

    @Test
    fun `cooldown exactly at boundary counts as expired`() {
        // nowMs - lastSwitcherLaunchMs == cooldown → cooldown expired
        // (>= comparison). Boundary pinned so a future refactor to
        // strict-greater-than doesn't silently regress the "one
        // launch per 30s" spec.
        val action =
            decideOverrideAction(
                overrideMac = overrideMac,
                hintMac = hintMac,
                availableMacs = setOf("11:22:33:44:55:66"),
                reflectionCached = BtActiveDeviceController.TrySetActiveResult.RefusedByPlatform,
                lastSwitcherLaunchMs = 100L,
                nowMs = 100L + cooldown,
                switcherCooldownMs = cooldown,
            )
        assertEquals(OverrideRoutingAction.LAUNCH_SWITCHER, action)
    }
}
