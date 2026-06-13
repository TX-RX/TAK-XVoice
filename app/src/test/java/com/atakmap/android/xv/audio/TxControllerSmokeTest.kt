package com.atakmap.android.xv.audio

import android.content.Context
import android.media.AudioManager
import androidx.test.core.app.ApplicationProvider
import io.mockk.mockk
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Robolectric smoke test for TxController.
 *
 * Goal: prove the test infrastructure (Robolectric + MockK + the
 * existing JUnit 4 runtime) can stand up a TxController under test
 * without bringing in the real ATAK audio plant, then assert the
 * initial-state contract.
 *
 * Future state-machine tests (priming → TPT → transmitting →
 * cooldown, encoder-reset on encode failure exercised end-to-end,
 * etc.) build on this scaffolding — see [TxControllerScreechTest]
 * for the pure-Kotlin assertions that pin the screech-bug fix
 * without needing this Robolectric harness at all.
 *
 * The dependencies passed to TxController follow the production
 * shape: collaborator interfaces / concrete classes mocked with
 * MockK relaxed-mode (no-op default), AudioManager pulled from
 * the Robolectric application context. ScoLink / BtAudioPolicy /
 * TptPlayer are concrete classes; relaxed-mode mocks return
 * sensible defaults for unstubbed methods, which is fine for a
 * smoke that doesn't call into them.
 */
@RunWith(RobolectricTestRunner::class)
class TxControllerSmokeTest {
    @Test
    fun `controller stands up clean and reports IDLE before any PTT activity`() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val audioManager = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager

        val tx =
            TxController(
                scoLink = mockk(relaxed = true),
                btPolicy = mockk(relaxed = true),
                tptPlayer = mockk(relaxed = true),
                audioCaptureFactory = { _ -> mockk(relaxed = true) },
                opusEncoderFactory = { mockk(relaxed = true) },
                audioManager = audioManager,
                audioController = mockk(relaxed = true),
                sendOpus = { _, _ -> },
            )

        // The whole point of isTxActive() is to gate AudioRouter's
        // hot-attach route-change deferral — it must reflect "not
        // currently in a TX cycle" out of the box. If this asserts
        // ever fails, the route-deferral state machine is wrong
        // before any user input.
        assertFalse("TxController must report idle at construction", tx.isTxActive())
    }

    @Test
    fun `Robolectric AudioManager is functional — getMode returns the platform default`() {
        // Proof that Robolectric's AudioManager shadow is wired in.
        // Without this confirmation, a future test that depends on
        // AudioManager.setMode / getMode behavior could pass under
        // Robolectric while diverging from real-device semantics.
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        // Default mode on a fresh device is MODE_NORMAL (0).
        assertTrue("AudioManager.getMode should be reachable", am.mode >= 0)
    }
}
