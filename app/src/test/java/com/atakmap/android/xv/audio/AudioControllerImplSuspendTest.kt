package com.atakmap.android.xv.audio

import android.content.Context
import android.media.AudioManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * Coverage for [AudioControllerImpl]'s system-suspend behavior — the
 * path that fires when Android telephony (or any higher-priority focus
 * holder) yanks focus mid-RX.
 *
 * The regression this pins: the earlier suspend path funnelled through
 * the same [releaseRxFocus] helper the orderly teardown uses, which
 * schedules a phone-mode restore SCO_COOL_DOWN_MS in the future. When
 * that deferred restore fired mid phone-call, it forced
 * [AudioManager.mode] back to MODE_NORMAL and clobbered
 * STREAM_VOICE_CALL's volume while the phone call was still active —
 * the operator observed "volume buttons regressed / call audio suddenly
 * wrong" a few seconds into the phone call. The corrected suspend path
 * restores immediately (matching TX's suspend semantics) so the system
 * takes over from a clean baseline with no future runnables outstanding.
 *
 * Robolectric provides the AudioManager shadow that persists mode /
 * stream-volume writes in-process, so we can assert against real
 * observable side effects instead of mocking every AudioManager call.
 */
@RunWith(RobolectricTestRunner::class)
class AudioControllerImplSuspendTest {
    private lateinit var ctx: Context
    private lateinit var audioManager: AudioManager

    @Before
    fun setup() {
        ctx = ApplicationProvider.getApplicationContext()
        audioManager = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        // Establish a known pre-engage baseline. The regression is
        // specifically about clobbering the operator's pre-engage
        // volume + mode after focus-loss, so both must be observable.
        audioManager.mode = AudioManager.MODE_NORMAL
        // STREAM_VOICE_CALL max on Robolectric shadow is high; use a
        // low current value so bumpVoiceCallVolumeIfLow does bump.
        audioManager.setStreamVolume(AudioManager.STREAM_VOICE_CALL, 2, 0)
    }

    @Test
    fun `focus-loss during RX restores mode immediately — no deferred runnable`() {
        val controller = AudioControllerImpl(ctx)

        // Enter RX — controller captures pre-engage mode (NORMAL) +
        // pre-engage STREAM_VOICE_CALL volume (2) + engages
        // MODE_IN_COMMUNICATION + bumps voice-call volume.
        assertEquals(
            "enterRx must grant focus on the Robolectric shadow",
            true,
            controller.enterRx(),
        )
        assertEquals(AudioState.RX, controller.state)
        assertEquals(
            "MODE_IN_COMMUNICATION must be engaged after enterRx",
            AudioManager.MODE_IN_COMMUNICATION,
            audioManager.mode,
        )

        // System (typically Telecom placing an incoming phone call)
        // yanks focus. The old code path scheduled a deferred restore
        // that would fire mid-call and clobber Telephony's mode +
        // stream volume. The fix restores immediately so nothing is
        // outstanding to blow away the phone call's audio state.
        controller.simulateFocusLossForTest()

        assertEquals(
            "focus loss must transition the controller into SUSPENDED",
            AudioState.SUSPENDED,
            controller.state,
        )
        assertEquals(
            "phone-mode restore MUST run synchronously in the suspend " +
                "path — a deferred runnable would fire mid phone-call and " +
                "overwrite Telephony's MODE_IN_CALL back to MODE_NORMAL, " +
                "which the operator perceives as \"volume buttons regressed\"",
            AudioManager.MODE_NORMAL,
            audioManager.mode,
        )
        assertEquals(
            "STREAM_VOICE_CALL must be restored to the pre-engage level in " +
                "the same synchronous step — a deferred restore would fire " +
                "several seconds into the phone call and yank the call's " +
                "volume out from under the user",
            2,
            audioManager.getStreamVolume(AudioManager.STREAM_VOICE_CALL),
        )

        // Guard against a stray deferred runnable that would later fire
        // and re-clobber whatever the system has set. Drain the main
        // Looper — if anything was scheduled by the suspend path, it
        // will run now and we can detect it by observing state churn.
        val priorMode = audioManager.mode
        val priorVol = audioManager.getStreamVolume(AudioManager.STREAM_VOICE_CALL)
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals(
            "no follow-up runnable may fire after suspend — mode must not change",
            priorMode,
            audioManager.mode,
        )
        assertEquals(
            "no follow-up runnable may fire after suspend — volume must not change",
            priorVol,
            audioManager.getStreamVolume(AudioManager.STREAM_VOICE_CALL),
        )
    }

    @Test
    fun `orderly returnToIdle from RX still uses cool-down (behavioural regression guard)`() {
        // Belt-and-suspenders: make sure the fix only touched the
        // system-suspend path. The regular teardown path (peer-voice
        // burst ended → AudioPlayback.teardown → returnToIdle) must
        // still defer the restore so a rapid re-engage lands warm.
        val controller = AudioControllerImpl(ctx)
        assertEquals(true, controller.enterRx())
        assertEquals(AudioManager.MODE_IN_COMMUNICATION, audioManager.mode)

        controller.returnToIdle()
        assertEquals(AudioState.IDLE, controller.state)
        // Cool-down is armed → mode + volume must remain engaged
        // until the deferred runnable fires. Deferred = not yet run,
        // so mode stays IN_COMMUNICATION at this synchronous point.
        assertEquals(
            "returnToIdle from RX must arm a deferred restore (mode " +
                "still IN_COMMUNICATION until cool-down fires); the fix " +
                "must not have collapsed this path into an immediate " +
                "restore",
            AudioManager.MODE_IN_COMMUNICATION,
            audioManager.mode,
        )
    }
}
