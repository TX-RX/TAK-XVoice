package com.atakmap.android.xv.service

import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.atakmap.android.xv.audio.PttDispatcher
import com.atakmap.android.xv.audio.PttSource
import com.atakmap.android.xv.audio.StatusTones
import com.atakmap.android.xv.audio.TxController
import io.mockk.mockk
import io.mockk.verify
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Regression coverage for field bug 2026-07-08: operator holds AINA V2
 * PTT, turns Bluetooth OFF on the phone mid-transmission. The reader's
 * BLE `onConnectionStateChange(STATE_DISCONNECTED)` fires (or the OS
 * tears BT down wholesale via `BluetoothAdapter.ACTION_STATE_CHANGED`),
 * but before this fix the button-down state was left pinned in
 * [PttDispatcher.heldButtons]. The OR-gate never saw an empty set, so
 * [TxController.stop] was never called and the transmit burst never
 * ended.
 *
 * These tests reproduce the exact dispatch shape of the two release
 * paths added in this PR — mirroring the pattern in
 * [VoicePlantBondNoneTest] where standing up a real [VoicePlant] would
 * pull in AudioController + AudioRouter + ScoLink + TxController, which
 * is more surface than the contract needs.
 *
 * The paths under test are:
 *
 *   1. **Reader-level release** — the `onConn` callback attached to
 *      each reader (see [VoicePlant.connectAina]) calls
 *      `pttDispatcher.forgetSource(source)` on the drop edge.
 *   2. **Plugin-level cascade** — the `BluetoothAdapter.STATE_TURNING_OFF`
 *      receiver in [XvVoiceService] calls
 *      [VoicePlant.releaseAllBtSourcedPtt], which forgets every BT
 *      [PttSource].
 */
@RunWith(RobolectricTestRunner::class)
class BtTransportDropReleasesPttTest {
    private fun buildDispatcher(txController: TxController): PttDispatcher =
        PttDispatcher(
            txController = txController,
            statusTones = mockk<StatusTones>(relaxed = true),
            latchedModeEnabled = { false },
            momentaryTimeoutSec = { 0 },
            latchedTimeoutSec = { 0 },
            tptPlayer = null,
        )

    // Simulates the shape of the reader `onConn` lambda in
    // [VoicePlant.connectAina] — on a false connection edge, forget
    // the reader's source so the OR-gate can see an empty held set.
    private fun readerConnCallback(
        dispatcher: PttDispatcher,
        source: PttSource,
    ): (Boolean) -> Unit =
        { up ->
            if (!up) dispatcher.forgetSource(source)
        }

    @Test
    fun `reader disconnect edge releases held PTT for that source`() {
        // Field-bug repro: AINA V2 keyed, then BT dies mid-burst.
        val tx = mockk<TxController>(relaxed = true)
        val dispatcher = buildDispatcher(tx)
        // Simulate an in-flight burst from the AINA V2 reader.
        dispatcher.down(slot = 0, source = PttSource.AINA_V2)

        // Reader's onConn(false) fires — via the wire-up added in
        // [VoicePlant.connectAina], this must forget PttSource.AINA_V2.
        val callback = readerConnCallback(dispatcher, PttSource.AINA_V2)
        callback(false)

        // Burst terminated — TxController.stop() was invoked exactly
        // once by forgetSource's "held set empty" branch.
        verify(exactly = 1) { tx.stop() }
    }

    @Test
    fun `reader disconnect leaves TX engaged when another source is still held`() {
        // Multi-source scenario: motorcyclist's helmet AINA drops but
        // the handlebar Pryme puck is still pressed. TX must stay
        // engaged on the surviving source; the disconnected source's
        // forget path can't tear the burst down under the surviving
        // source's feet.
        val tx = mockk<TxController>(relaxed = true)
        val dispatcher = buildDispatcher(tx)
        dispatcher.down(slot = 0, source = PttSource.AINA_V2)
        dispatcher.down(slot = 0, source = PttSource.PRYME_BLE)

        readerConnCallback(dispatcher, PttSource.AINA_V2)(false)

        verify(exactly = 0) { tx.stop() }
    }

    @Test
    fun `adapter STATE_TURNING_OFF cascades release across all BT sources`() {
        // Wholesale BT teardown: individual readers may not observe
        // their transport dropping fast enough on some OEM stacks, so
        // the adapter-state receiver in [XvVoiceService] cascades a
        // release across every BT [PttSource]. This mirrors the exact
        // dispatch shape of that receiver — action filter on
        // ACTION_STATE_CHANGED, EXTRA_STATE = STATE_TURNING_OFF,
        // then a call into [VoicePlant.releaseAllBtSourcedPtt].
        val tx = mockk<TxController>(relaxed = true)
        val dispatcher = buildDispatcher(tx)
        dispatcher.down(slot = 0, source = PttSource.AINA_V1)

        val receiver = makeAdapterReceiver { dispatcher.releaseAllBtSourcedPttTestHook() }
        receiver.onReceive(mockk(relaxed = true), turningOffIntent())

        verify(exactly = 1) { tx.stop() }
    }

    @Test
    fun `adapter STATE_ON is ignored (no spurious release)`() {
        val tx = mockk<TxController>(relaxed = true)
        val dispatcher = buildDispatcher(tx)
        dispatcher.down(slot = 0, source = PttSource.AINA_V1)

        val receiver = makeAdapterReceiver { dispatcher.releaseAllBtSourcedPttTestHook() }
        val onIntent =
            Intent(BluetoothAdapter.ACTION_STATE_CHANGED).apply {
                putExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.STATE_ON)
            }
        receiver.onReceive(mockk(relaxed = true), onIntent)

        // Held state intact — the burst is still in flight.
        verify(exactly = 0) { tx.stop() }
    }

    // Reproduces the receiver body in [XvVoiceService.btAdapterStateReceiver].
    // On STATE_TURNING_OFF or STATE_OFF, invoke the release cascade.
    private fun makeAdapterReceiver(release: () -> Unit): BroadcastReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(
                c: Context?,
                i: Intent?,
            ) {
                if (i?.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
                val state = i.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                if (state == BluetoothAdapter.STATE_TURNING_OFF ||
                    state == BluetoothAdapter.STATE_OFF
                ) {
                    release()
                }
            }
        }

    private fun turningOffIntent(): Intent =
        Intent(BluetoothAdapter.ACTION_STATE_CHANGED).apply {
            putExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.STATE_TURNING_OFF)
        }

    /**
     * Helper extension: emulates [VoicePlant.releaseAllBtSourcedPtt]'s
     * body without needing a real VoicePlant. Kept beside this test
     * only — the production version lives in [VoicePlant] and is what
     * the [XvVoiceService] receiver actually calls.
     */
    private fun PttDispatcher.releaseAllBtSourcedPttTestHook() {
        forgetSource(PttSource.AINA_V1)
        forgetSource(PttSource.AINA_V2)
        forgetSource(PttSource.PRYME_BLE)
    }
}
