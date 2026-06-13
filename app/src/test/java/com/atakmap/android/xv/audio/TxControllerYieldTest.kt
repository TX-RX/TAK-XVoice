package com.atakmap.android.xv.audio

import android.media.AudioManager
import android.media.AudioRecordingConfiguration
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Coverage for the warm-mic auto-yield behavior added 2026-05-21
 * (Google Assistant blocking the mic field complaint).
 *
 * Production contract:
 *   - armSessionMic registers an AudioRecordingCallback on AudioManager
 *     and allocates AudioCapture iff no other app is currently
 *     recording.
 *   - When the callback reports another app recording (clientAudio-
 *     SessionId differs from ours), we release AudioCapture so the
 *     other app can acquire.
 *   - When the callback reports that other app released, we re-allocate
 *     AudioCapture so PTT-down stays low-latency.
 *   - disarmSessionMic unregisters the callback and releases.
 *   - Mid-burst recording starts (state != IDLE) are ignored; the
 *     post-burst re-check in stopInternal catches them.
 *
 * The test uses a MockK-built AudioManager (not Robolectric's real
 * one) so we can capture the registered callback and feed it
 * controlled AudioRecordingConfiguration mocks.
 */
@RunWith(RobolectricTestRunner::class)
class TxControllerYieldTest {
    private lateinit var audioManager: AudioManager
    private lateinit var mockCapture: AudioCapture
    private val captureFactoryInvocations = mutableListOf<AudioCapture>()
    private var captureStarted: Boolean = false
    private var captureStopped: Boolean = false
    private val callbackSlot = slot<AudioManager.AudioRecordingCallback>()

    /** Default to "no other app recording" — tests override per-case. */
    private var currentConfigs: List<AudioRecordingConfiguration> = emptyList()

    @Before
    fun setup() {
        captureFactoryInvocations.clear()
        captureStarted = false
        captureStopped = false
        audioManager = mockk(relaxed = true)
        every {
            audioManager.registerAudioRecordingCallback(capture(callbackSlot), any())
        } returns Unit
        every { audioManager.activeRecordingConfigurations } answers { currentConfigs }
    }

    private fun newCapture(sessionId: Int): AudioCapture {
        val cap = mockk<AudioCapture>(relaxed = true)
        every { cap.activeSessionId } returns sessionId
        every { cap.start() } answers { captureStarted = true }
        every { cap.stop() } answers { captureStopped = true }
        return cap
    }

    private fun config(sessionId: Int): AudioRecordingConfiguration {
        val c = mockk<AudioRecordingConfiguration>(relaxed = true)
        every { c.clientAudioSessionId } returns sessionId
        return c
    }

    private fun build(): TxController {
        // Capture factory hands out a fresh mock per allocation; the
        // first one will be the warm-mic capture, subsequent ones
        // (if the yield/re-arm cycle runs) are also tracked.
        return TxController(
            scoLink = mockk(relaxed = true),
            btPolicy = mockk(relaxed = true),
            tptPlayer = mockk(relaxed = true),
            audioCaptureFactory = { _ ->
                val cap = newCapture(sessionId = 42)
                captureFactoryInvocations += cap
                cap
            },
            opusEncoderFactory = { mockk(relaxed = true) },
            audioManager = audioManager,
            audioController = mockk(relaxed = true),
            sendOpus = { _, _ -> },
        )
    }

    // ============================================================
    // Basic registration + allocation
    // ============================================================

    @Test
    fun `armSessionMic registers AudioRecordingCallback on first arm`() {
        val tx = build()
        tx.armSessionMic()
        verify(exactly = 1) {
            audioManager.registerAudioRecordingCallback(any(), any())
        }
    }

    @Test
    fun `armSessionMic allocates AudioCapture when no other app is recording`() {
        currentConfigs = emptyList()
        val tx = build()
        tx.armSessionMic()
        assertTrue("AudioCapture must be allocated", captureFactoryInvocations.isNotEmpty())
        assertTrue("AudioCapture.start() must be called", captureStarted)
    }

    @Test
    fun `armSessionMic defers allocation when another app is currently recording`() {
        // Assistant is already running when Mumble connects.
        currentConfigs = listOf(config(sessionId = 999))
        val tx = build()
        tx.armSessionMic()
        // Callback registered, but no AudioCapture allocated yet.
        verify(exactly = 1) { audioManager.registerAudioRecordingCallback(any(), any()) }
        assertTrue(
            "AudioCapture must NOT be allocated while another app holds the mic",
            captureFactoryInvocations.isEmpty(),
        )
    }

    // ============================================================
    // Auto-yield on Assistant starting
    // ============================================================

    @Test
    fun `recording-callback fires with another session id — capture released`() {
        currentConfigs = emptyList()
        val tx = build()
        tx.armSessionMic()
        captureStopped = false
        assertTrue("callback must be captured by the test slot", callbackSlot.isCaptured)
        // Simulate Assistant starting: its config shows up with a
        // different session id.
        callbackSlot.captured.onRecordingConfigChanged(
            mutableListOf(
                config(sessionId = 42), // ours
                config(sessionId = 999), // Assistant's
            ),
        )
        assertTrue("warm mic must be released to yield to the other app", captureStopped)
    }

    @Test
    fun `recording-callback fires with only our session id — no release`() {
        currentConfigs = emptyList()
        val tx = build()
        tx.armSessionMic()
        captureStopped = false
        // Only our recording is in the list — no yield.
        callbackSlot.captured.onRecordingConfigChanged(
            mutableListOf(config(sessionId = 42)),
        )
        assertTrue(
            "no release when only our recording is active",
            !captureStopped,
        )
    }

    // ============================================================
    // Auto-rearm when other app releases
    // ============================================================

    @Test
    fun `re-arm fires when other app stops and our recording is gone`() {
        // Sequence: arm → yield to Assistant → Assistant releases → re-arm
        currentConfigs = emptyList()
        val tx = build()
        tx.armSessionMic()
        val firstCaptureCount = captureFactoryInvocations.size
        // Assistant starts.
        callbackSlot.captured.onRecordingConfigChanged(
            mutableListOf(config(sessionId = 42), config(sessionId = 999)),
        )
        // We yielded — capture released. ourSessionId in the next
        // callback invocation is 0 (capture == null → activeSessionId
        // returns 0).
        // Assistant stops — only our (gone) session id is left, so
        // configs is empty (or contains nothing matching session 0).
        callbackSlot.captured.onRecordingConfigChanged(mutableListOf())
        assertTrue(
            "must re-arm after the other app releases",
            captureFactoryInvocations.size > firstCaptureCount,
        )
    }

    // ============================================================
    // disarmSessionMic — unregisters callback and releases
    // ============================================================

    @Test
    fun `disarmSessionMic unregisters the recording callback`() {
        currentConfigs = emptyList()
        val tx = build()
        tx.armSessionMic()
        tx.disarmSessionMic()
        verify(exactly = 1) { audioManager.unregisterAudioRecordingCallback(any()) }
    }

    @Test
    fun `disarmSessionMic releases the capture`() {
        currentConfigs = emptyList()
        val tx = build()
        tx.armSessionMic()
        captureStopped = false
        tx.disarmSessionMic()
        assertTrue("capture must be released on disarmSessionMic", captureStopped)
    }

    @Test
    fun `armSessionMic is idempotent — second call is a no-op`() {
        currentConfigs = emptyList()
        val tx = build()
        tx.armSessionMic()
        val firstCount = captureFactoryInvocations.size
        tx.armSessionMic()
        assertTrue("idempotent: second arm must not allocate again", captureFactoryInvocations.size == firstCount)
    }

    @Test
    fun `disarmSessionMic without prior arm is a no-op`() {
        val tx = build()
        tx.disarmSessionMic()
        verify(exactly = 0) { audioManager.unregisterAudioRecordingCallback(any()) }
    }

    // ============================================================
    // State-gated yield — mid-burst recording is ignored
    // ============================================================

    @Test
    fun `mid-burst Assistant start is ignored (state != IDLE)`() {
        currentConfigs = emptyList()
        val tx = build()
        tx.armSessionMic()
        captureStopped = false
        // Pretend we're mid-burst.
        tx.setStateForTest(TxController.State.TRANSMITTING)
        callbackSlot.captured.onRecordingConfigChanged(
            mutableListOf(config(sessionId = 42), config(sessionId = 999)),
        )
        assertTrue(
            "must NOT yield mid-burst — operator's TX wins",
            !captureStopped,
        )
    }
}
