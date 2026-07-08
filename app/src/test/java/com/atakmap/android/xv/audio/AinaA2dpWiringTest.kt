package com.atakmap.android.xv.audio

import android.app.NotificationManager
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.media.AudioManager
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Test

/**
 * Pins the connect-time wiring that was missing from the field-bug
 * fix: [AinaA2dpController.forbid] must be invoked exactly once per
 * AINA-class device when [BtAudioPolicy] fans out a connect event,
 * and must NOT be invoked for non-AINA devices (operator's AirPods,
 * car kit, portable speaker).
 *
 * Root cause this test guards against: prior to the wiring fix,
 * [AinaA2dpController] was never instantiated or invoked from any
 * production code path. The reflection-based `setConnectionPolicy`
 * mechanism was dead — every connected AINA continued to advertise
 * an A2DP sink and Spotify / YouTube / system sounds happily routed
 * through it. The test exercises the smallest possible surface that
 * proves the wiring is alive: classifier verdict in → forbid call
 * count out.
 *
 * Robolectric is intentionally not used here — the wiring helper
 * accepts an injected classifier and an explicit-null
 * AudioManager / NotificationManager, so a pure-JVM test reaches
 * the full forbid path without standing up the Android runtime.
 * Avoiding Robolectric also avoids a ~3s harness startup per case,
 * which matters when this runs in the gate before every push.
 *
 * MockK is used to stub [AinaA2dpController] in relaxed mode —
 * the production class is final but MockK's mockk-agent bytecode
 * instrumentation handles that on the JVM test runtime. The
 * controller's real Bluetooth/reflection side effects never run,
 * so no live BluetoothAdapter is needed.
 */
class AinaA2dpWiringTest {
    /**
     * Given an AINA-classified BluetoothDevice and a mocked
     * [AinaA2dpController], the new wiring calls
     * [AinaA2dpController.forbid] exactly once on the connect path.
     */
    @Test
    fun `AINA-classified device on connect — forbid called exactly once`() {
        val ctx = mockk<Context>(relaxed = true)
        val controller = mockk<AinaA2dpController>(relaxed = true)
        every { controller.forbid(any()) } returns AinaA2dpController.ForbidResult.OK
        val ainaDevice =
            mockk<BluetoothDevice>(relaxed = true).also {
                every { it.address } returns "38:B8:EB:31:67:82"
            }
        val wiring =
            AinaA2dpWiring(
                context = ctx,
                controller = controller,
                isAina = { true },
                // Pass nulls so the helper does not try to talk to the
                // real NotificationManager / AudioManager — the wiring
                // call path we care about runs before any of those
                // dependencies are touched on the OK branch.
                notificationManager = null as NotificationManager?,
                audioManager = null as AudioManager?,
            )

        wiring.onDeviceConnected(ainaDevice)

        verify(exactly = 1) { controller.forbid(ainaDevice) }
    }

    /**
     * Given a non-AINA-classified device, [AinaA2dpController.forbid]
     * is NOT called. This is the guardrail that keeps the wiring
     * narrowly scoped to operator-tactical speakermics — any other
     * BT audio device the operator pairs (AirPods, car kit, JBL
     * speaker, etc.) keeps full A2DP routing for music / podcasts /
     * navigation prompts.
     */
    @Test
    fun `non-AINA device on connect — forbid is NOT called`() {
        val ctx = mockk<Context>(relaxed = true)
        val controller = mockk<AinaA2dpController>(relaxed = true)
        val airpods =
            mockk<BluetoothDevice>(relaxed = true).also {
                every { it.address } returns "AA:BB:CC:DD:EE:FF"
            }
        val wiring =
            AinaA2dpWiring(
                context = ctx,
                controller = controller,
                isAina = { false },
                notificationManager = null as NotificationManager?,
                audioManager = null as AudioManager?,
            )

        wiring.onDeviceConnected(airpods)

        verify(exactly = 0) { controller.forbid(any()) }
    }

    /**
     * Repeated connect events for the same AINA MAC must not trigger
     * a second forbid call. The wiring is registered with BOTH the
     * HEADSET profile-proxy fan-out (devices that pre-dated XV start)
     * AND the ACL_CONNECTED broadcast (post-start re-connects), so
     * the same device can surface through both signals when it
     * reconnects mid-session. Double-forbid isn't catastrophic but
     * does emit duplicate log lines and re-issues the reflective
     * disconnect, which on some OEM stacks causes a brief routing
     * flicker — guard against it here.
     */
    @Test
    fun `same AINA fanned out twice — forbid still called only once`() {
        val ctx = mockk<Context>(relaxed = true)
        val controller = mockk<AinaA2dpController>(relaxed = true)
        every { controller.forbid(any()) } returns AinaA2dpController.ForbidResult.OK
        val ainaDevice =
            mockk<BluetoothDevice>(relaxed = true).also {
                every { it.address } returns "38:B8:EB:31:67:82"
            }
        val wiring =
            AinaA2dpWiring(
                context = ctx,
                controller = controller,
                isAina = { true },
                notificationManager = null as NotificationManager?,
                audioManager = null as AudioManager?,
            )

        wiring.onDeviceConnected(ainaDevice)
        wiring.onDeviceConnected(ainaDevice)

        verify(exactly = 1) { controller.forbid(ainaDevice) }
    }
}
