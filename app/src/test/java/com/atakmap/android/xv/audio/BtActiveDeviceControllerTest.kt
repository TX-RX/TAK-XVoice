package com.atakmap.android.xv.audio

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure-Kotlin coverage for the override-routing decision function used
 * by [ScoLink.handleOverrideMissed]. Real reflective
 * [BtActiveDeviceController.trySetActive] behavior varies by OEM /
 * platform release; the decision matrix around it does not, and is
 * pinned here so future refactors don't regress the "don't spam the
 * switcher" or "try reflection before giving up" contracts.
 */
class BtActiveDeviceControllerTest {
    private val overrideMac = "AA:BB:CC:DD:EE:FF"
    private val hintMac = "11:22:33:44:55:66"
    private val cooldown = 30_000L

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

    @Test
    fun `override null — PIN_HINT (caller resolves absence of hint separately)`() {
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
        assertEquals(OverrideRoutingAction.PIN_HINT, action)
    }

    @Test
    fun `override blank — PIN_HINT`() {
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
        assertEquals(OverrideRoutingAction.PIN_HINT, action)
    }

    @Test
    fun `override null AND hint null — PIN_HINT (caller falls into speaker chain)`() {
        // The spec calls out this case explicitly: when neither
        // override nor hint is set we return PIN_HINT and let the
        // caller's existing "no hint, speaker" logic take over. We
        // don't invent a new state for it here.
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
