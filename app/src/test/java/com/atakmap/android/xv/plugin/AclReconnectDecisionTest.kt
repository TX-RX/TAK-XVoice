package com.atakmap.android.xv.plugin

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-function coverage for [XvMapComponent.shouldReconnectOnAcl].
 *
 * The ACL_CONNECTED receiver in [XvMapComponent] calls this predicate
 * to decide whether an incoming BluetoothDevice.ACTION_ACL_CONNECTED
 * broadcast warrants spawning (or respawning) a reader for a given
 * slot.  Extracting the decision as a pure function means we can pin
 * the edge cases here without standing up an Android Context or a
 * live Bluetooth stack.
 *
 * Field bug closed by this path (issue #88): after a BT adapter
 * OFF → ON cycle the [autoConnectAina] timer (3 s + 1.2 s = 4.2 s)
 * could fire BEFORE the AINA finished re-publishing its SDP records
 * (observed 4-5 s on Pixel 9 Pro).  [autoConnectAina] saw
 * available=false and silently bailed — the operator ended up with
 * working BT audio but silent PTT buttons until a manual picker tap.
 * The ACL_CONNECTED receiver fixes the race by reacting to the actual
 * reachability signal rather than a wall-clock guess.
 */
class AclReconnectDecisionTest {
    // ============================================================
    // shouldReconnectOnAcl — ACL_CONNECTED broadcast decision table
    // ============================================================

    @Test
    fun `matches when connected MAC equals saved MAC`() {
        assertTrue(
            XvMapComponent.shouldReconnectOnAcl(
                connectedMac = "AA:BB:CC:DD:EE:FF",
                savedMac = "AA:BB:CC:DD:EE:FF",
            ),
        )
    }

    @Test
    fun `matches case-insensitively — upper connected, lower saved`() {
        // Android broadcasts MACs upper-cased; some persist paths
        // may have stored lower-case.  The comparison must be
        // case-insensitive so neither direction misses.
        assertTrue(
            XvMapComponent.shouldReconnectOnAcl(
                connectedMac = "AA:BB:CC:DD:EE:FF",
                savedMac = "aa:bb:cc:dd:ee:ff",
            ),
        )
    }

    @Test
    fun `matches case-insensitively — lower connected, upper saved`() {
        assertTrue(
            XvMapComponent.shouldReconnectOnAcl(
                connectedMac = "aa:bb:cc:dd:ee:ff",
                savedMac = "AA:BB:CC:DD:EE:FF",
            ),
        )
    }

    @Test
    fun `no match when saved MAC is null — operator has not picked a device`() {
        // Null savedMac means no device has been configured for this
        // slot.  An ACL_CONNECTED from any MAC must not trigger a
        // reconnect attempt for an unconfigured slot.
        assertFalse(
            XvMapComponent.shouldReconnectOnAcl(
                connectedMac = "AA:BB:CC:DD:EE:FF",
                savedMac = null,
            ),
        )
    }

    @Test
    fun `no match when connected MAC differs from saved MAC`() {
        // A different device reconnected — do not disturb the slot
        // that is waiting for its own device.
        assertFalse(
            XvMapComponent.shouldReconnectOnAcl(
                connectedMac = "AA:BB:CC:DD:EE:01",
                savedMac = "AA:BB:CC:DD:EE:FF",
            ),
        )
    }
}
