package com.atakmap.android.xv.audio

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the reject-cue selection for a denied PTT press. Two distinct
 * "can't transmit" reasons must map to two distinct, operator-
 * recognizable tones:
 *   - No live Mumble session (disconnected / not in a channel) → BONK.
 *     This is the fix for "the talk-permit tone fired while disconnected":
 *     a dead link now bonks instead of either playing the permit tone
 *     (misleading) or the listen-only deny tone (wrong reason).
 *   - Live session but suppressed on this slot (OTS direction OUT / admin
 *     mute) → DENY (the listen-only reject).
 */
class TxControllerRejectToneTest {
    @Test
    fun `no live session bonks`() {
        assertEquals(TxController.RejectTone.BONK, TxController.rejectToneFor(sessionLive = false))
    }

    @Test
    fun `live-but-listen-only denies`() {
        assertEquals(TxController.RejectTone.DENY, TxController.rejectToneFor(sessionLive = true))
    }
}
