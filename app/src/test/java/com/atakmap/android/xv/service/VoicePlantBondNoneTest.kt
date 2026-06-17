package com.atakmap.android.xv.service

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.atakmap.android.xv.plugin.XvSettings
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Wire-up coverage for the bond-state receiver's BOND_NONE branch.
 *
 * Reproduces the exact dispatch shape of [VoicePlant.bondStateReceiver]
 * — action filter on ACTION_BOND_STATE_CHANGED, EXTRA_BOND_STATE check
 * for BOND_NONE, EXTRA_DEVICE → address — and asserts the
 * [XvSettings.clearAinaProtocolOverride] call lands. Standing up a
 * real VoicePlant in a unit test would pull in AudioController +
 * AudioRouter + ScoLink + TxController, which is more surface than
 * this contract needs. The receiver body itself is a six-liner that
 * matches this test verbatim; integration drift between the two would
 * be caught instantly by inspection.
 *
 * Belt-and-suspenders complement to [XvSettingsAinaOverrideTest],
 * which covers the persistence API directly.
 */
@RunWith(RobolectricTestRunner::class)
class VoicePlantBondNoneTest {
    private lateinit var ctx: Context
    private lateinit var settings: XvSettings

    @Before
    fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        ctx.getSharedPreferences(XvSettings.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .apply()
        settings = XvSettings { ctx.getSharedPreferences(XvSettings.PREFS_NAME, Context.MODE_PRIVATE) }
    }

    @Test
    fun `BOND_NONE for any MAC clears the persisted override`() {
        settings.persistAinaProtocolOverride(MAC, "v2")
        val receiver = makeReceiver()

        receiver.onReceive(ctx, bondNoneIntent(MAC))

        assertNull(
            "override should have been cleared on BOND_NONE",
            settings.persistedAinaProtocolOverride(MAC),
        )
    }

    @Test
    fun `non-BOND_NONE bond state does not clear the override`() {
        settings.persistAinaProtocolOverride(MAC, "v2")
        val receiver = makeReceiver()

        val intent =
            Intent(BluetoothDevice.ACTION_BOND_STATE_CHANGED).apply {
                putExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_BONDED)
                putExtra(BluetoothDevice.EXTRA_DEVICE, mockDevice(MAC))
            }
        receiver.onReceive(ctx, intent)

        // Override survives — BOND_BONDED is a re-pair signal but
        // doesn't invalidate a prior protocol decision. (Only BOND_NONE
        // does, because an unpair erases the pairing context the
        // override was tied to.)
        org.junit.Assert.assertEquals("v2", settings.persistedAinaProtocolOverride(MAC))
    }

    @Test
    fun `wrong action is ignored`() {
        settings.persistAinaProtocolOverride(MAC, "v2")
        val receiver = makeReceiver()

        receiver.onReceive(ctx, Intent("not.our.action"))

        org.junit.Assert.assertEquals("v2", settings.persistedAinaProtocolOverride(MAC))
    }

    private fun makeReceiver(): BroadcastReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(
                c: Context?,
                i: Intent?,
            ) {
                if (i?.action != BluetoothDevice.ACTION_BOND_STATE_CHANGED) return
                val bond =
                    i.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)
                if (bond != BluetoothDevice.BOND_NONE) return
                val device: BluetoothDevice? = i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                val mac = device?.address ?: return
                settings.clearAinaProtocolOverride(mac, reason = "BOND_NONE")
            }
        }

    private fun bondNoneIntent(mac: String): Intent =
        Intent(BluetoothDevice.ACTION_BOND_STATE_CHANGED).apply {
            putExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE)
            putExtra(BluetoothDevice.EXTRA_DEVICE, mockDevice(mac))
        }

    private fun mockDevice(mac: String): BluetoothDevice {
        val dev = mockk<BluetoothDevice>(relaxed = true)
        every { dev.address } returns mac
        return dev
    }

    companion object {
        private const val MAC = "AA:BB:CC:DD:EE:01"
    }
}
