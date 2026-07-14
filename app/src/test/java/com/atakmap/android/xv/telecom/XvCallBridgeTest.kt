package com.atakmap.android.xv.telecom

import com.atakmap.android.xv.service.IXvVoice
import com.atakmap.android.xv.service.XvVoiceClient
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Test

/**
 * Regression coverage for the XvCallBridge dead-flag fix.
 *
 * Previously the bridge carried a plugin-side `callActive` flag that was
 * only ever set true by a `startChannelCall` proxy that nothing called.
 * As a result `callActive` was always false, and `endChannelCall()`'s
 * `if (!callActive) return` guard made it a permanent no-op — silently
 * breaking the in-app "End Call" bar and the pre-transport-teardown
 * end-call in stopActiveTransport. The flag/proxy were removed and
 * `endChannelCall()` now routes to the service unconditionally (the
 * service side is idempotent).
 */
class XvCallBridgeTest {
    private fun bridgeWithBoundVoice(voice: IXvVoice): XvCallBridge {
        val client = mockk<XvVoiceClient>()
        // Simulate a bound service: ifBound invokes the action with the
        // live IXvVoice, mirroring XvVoiceClient.ifBound's bound branch.
        every { client.ifBound(any()) } answers {
            firstArg<(IXvVoice) -> Unit>().invoke(voice)
        }
        return XvCallBridge(client)
    }

    @Test
    fun `endChannelCall routes to the service unconditionally`() {
        val voice = mockk<IXvVoice>(relaxed = true)
        val bridge = bridgeWithBoundVoice(voice)

        bridge.endChannelCall()

        verify(exactly = 1) { voice.endChannelCall() }
    }

    @Test
    fun `endChannelCall fires every time (idempotent, no self-gating flag)`() {
        val voice = mockk<IXvVoice>(relaxed = true)
        val bridge = bridgeWithBoundVoice(voice)

        bridge.endChannelCall()
        bridge.endChannelCall()

        // The old callActive guard would have suppressed the second (and
        // in practice the first) call. Both must reach the service now.
        verify(exactly = 2) { voice.endChannelCall() }
    }

    @Test
    fun `shutdown ends the channel call`() {
        val voice = mockk<IXvVoice>(relaxed = true)
        val bridge = bridgeWithBoundVoice(voice)

        bridge.shutdown()

        verify(exactly = 1) { voice.endChannelCall() }
    }
}
