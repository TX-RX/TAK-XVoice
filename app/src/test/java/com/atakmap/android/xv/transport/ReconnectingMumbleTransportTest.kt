package com.atakmap.android.xv.transport

import com.atakmap.android.xv.transport.mumble.FatalMumbleException
import com.atakmap.android.xv.transport.mumble.MumbleSession
import com.atakmap.android.xv.transport.mumble.SelfKickedException
import com.atakmap.android.xv.transport.mumble.UsernameInUseException
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.Callable
import java.util.concurrent.Delayed
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import mumble.MumbleProto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Coverage for the reconnect wrapper that controls Mumble field
 * reliability — auth-failure-no-retry, transient-failure-with-backoff,
 * UsernameInUse-retry-with-same-name (ghost cleanup), and operator-
 * disconnect cancels pending retries.
 *
 * Driven via constructor-injected [ScheduledExecutorService] that runs
 * `submit` synchronously and captures `schedule` delays for manual
 * firing. Inner MumbleTransport is MockK relaxed so the wrapper sees
 * the full method surface but production socket / TLS code never runs.
 */
class ReconnectingMumbleTransportTest {
    private lateinit var executor: TestScheduledExecutor
    private val factoryInvocations = mutableListOf<MumbleTransport>()
    private val capturedListeners = mutableListOf<TransportListener>()
    private lateinit var upstream: TransportListener
    private var primaryReadyResult = true
    private var primaryConnectedResult = true

    @Before
    fun setup() {
        executor = TestScheduledExecutor()
        factoryInvocations.clear()
        capturedListeners.clear()
        upstream = mockk(relaxed = true)
        primaryReadyResult = true
        primaryConnectedResult = true
    }

    /** Factory the wrapper calls per attempt. Builds a relaxed MumbleTransport
     *  mock; tests can grab the most recent one via [factoryInvocations]. */
    private val factory: (String, String) -> MumbleTransport = { _, _ ->
        val tx = mockk<MumbleTransport>(relaxed = true)
        val listenerSlot = slot<TransportListener>()
        every { tx.connectPrimaryOnly(capture(listenerSlot)) } answers {
            capturedListeners.add(listenerSlot.captured)
        }
        every { tx.awaitPrimaryReady(any()) } answers { primaryReadyResult }
        every { tx.awaitFullyDisconnected(any()) } returns true
        every { tx.isConnected } answers { primaryConnectedResult }
        factoryInvocations.add(tx)
        tx
    }

    private fun build(): ReconnectingMumbleTransport = buildWith { true }

    private fun buildWith(reconnectEnabled: () -> Boolean): ReconnectingMumbleTransport =
        ReconnectingMumbleTransport(
            config =
            TransportConfig.Mumble(
                host = "test.example.com",
                username = "test-user",
                channelName = "TEST",
            ),
            primarySlotSuffix = "VS1",
            secondarySlotSuffix = "VS2",
            factory = factory,
            reconnectEnabled = reconnectEnabled,
            executor = executor,
        )

    // ============================================================
    // Happy path — clean connect
    // ============================================================

    @Test
    fun `connect invokes the factory once and wires the upstream listener`() {
        val r = build()
        r.connect(upstream)
        assertEquals("factory must be called exactly once on connect", 1, factoryInvocations.size)
        assertEquals(
            "wrapper must install its intercept listener on inner.connectPrimaryOnly",
            1,
            capturedListeners.size,
        )
        verify { factoryInvocations[0].connectPrimaryOnly(any()) }
    }

    @Test
    fun `inner onConnected resets backoff and forwards upstream`() {
        val r = build()
        r.connect(upstream)
        capturedListeners[0].onConnected()
        verify(exactly = 1) { upstream.onConnected() }
        assertEquals(
            "successful connect resets attempt count",
            0,
            r.reconnectAttempt(),
        )
        assertFalse(r.isReconnecting())
    }

    @Test
    fun `connect waits for primary ready then calls connectSecondary`() {
        val r = build()
        r.connect(upstream)
        // VS1 → wait for ready → VS2.
        verify { factoryInvocations[0].connectSecondary() }
    }

    @Test
    fun `connect skips VS2 when primary not ready (listener path drives recovery)`() {
        primaryReadyResult = false
        val r = build()
        r.connect(upstream)
        verify(exactly = 0) { factoryInvocations[0].connectSecondary() }
    }

    // ============================================================
    // Transient failure → backoff retry
    // ============================================================

    @Test
    fun `transient disconnect schedules a retry attempt`() {
        val r = build()
        r.connect(upstream)
        // Inner reports disconnect; wrapper schedules a retry.
        capturedListeners[0].onDisconnected("link blip")
        verify(exactly = 1) { upstream.onDisconnected("link blip") }
        assertTrue("retry must be queued on the executor", executor.hasPendingSchedule())
        // The first ReconnectPolicy delay is 1s (DEFAULT schedule).
        assertEquals(1_000L, executor.lastScheduledDelayMs)
    }

    @Test
    fun `firing the scheduled retry rebuilds a fresh inner via the factory`() {
        val r = build()
        r.connect(upstream)
        capturedListeners[0].onDisconnected("link blip")
        executor.fireScheduled()
        assertEquals("second factory call on retry", 2, factoryInvocations.size)
    }

    @Test
    fun `reconnectAttempt grows across consecutive transient failures`() {
        val r = build()
        r.connect(upstream)
        capturedListeners[0].onDisconnected("first blip")
        assertEquals(1, r.reconnectAttempt())
        executor.fireScheduled()
        capturedListeners[1].onDisconnected("second blip")
        assertEquals(2, r.reconnectAttempt())
    }

    @Test
    fun `onReconnectStateChanged fires with the policy's chosen delay`() {
        var observed: Pair<Int, Long>? = null
        val r =
            ReconnectingMumbleTransport(
                config =
                TransportConfig.Mumble(
                    host = "test.example.com",
                    username = "u",
                    channelName = "c",
                ),
                primarySlotSuffix = "VS1",
                secondarySlotSuffix = "VS2",
                factory = factory,
                onReconnectStateChanged = { attempt, delay -> observed = attempt to delay },
                executor = executor,
            )
        r.connect(upstream)
        capturedListeners[0].onDisconnected("blip")
        assertNotNull("UI hook must fire when a retry schedules", observed)
        assertEquals(1, observed!!.first)
        assertEquals(1_000L, observed!!.second)
        assertEquals(1_000L, r.nextScheduledDelayMs())
    }

    // ============================================================
    // Fatal failures — no retry
    // ============================================================

    @Test
    fun `Fatal MumbleException does NOT trigger a retry`() {
        val r = build()
        r.connect(upstream)
        capturedListeners[0].onConnectionFailed(
            FatalMumbleException(MumbleProto.Reject.RejectType.WrongUserPW, "bad password"),
        )
        assertFalse(
            "fatal failure must not schedule a retry",
            executor.hasPendingSchedule(),
        )
        // Upstream still got the failure event.
        verify { upstream.onConnectionFailed(any()) }
    }

    @Test
    fun `SelfKickedException does NOT trigger a retry`() {
        val r = build()
        r.connect(upstream)
        capturedListeners[0].onConnectionFailed(
            SelfKickedException(reason = "spam", banned = true, byActorSession = 0),
        )
        assertFalse(executor.hasPendingSchedule())
    }

    // Regression test for the 2026-07-06 field bug: a self-kick surfaces
    // BOTH as onConnectionFailed(Fatal) AND as onDisconnected(Transient)
    // because the server-side kick closes the socket, which trips the
    // read loop's finally-block. Without the fatalRejected latch, the
    // Transient path scheduled a fresh reconnect ~1s later that got
    // kicked again — infinite loop hammering the server.
    @Test
    fun `SelfKick followed by onDisconnected does NOT schedule a Transient retry`() {
        val r = build()
        r.connect(upstream)
        capturedListeners[0].onConnectionFailed(
            SelfKickedException(reason = "duplicate", banned = false, byActorSession = 0),
        )
        capturedListeners[0].onDisconnected("read loop exit")
        assertFalse(
            "Post-Fatal onDisconnected must not sneak a Transient retry past shouldRetry",
            executor.hasPendingSchedule(),
        )
        assertEquals("no fresh factory invocation after fatal", 1, factoryInvocations.size)
    }

    // ============================================================
    // UsernameInUse on primary — transient with same name
    // ============================================================

    @Test
    fun `primary UsernameInUse triggers a retry under the same deterministic suffix`() {
        val r = build()
        r.connect(upstream)
        capturedListeners[0].onConnectionFailed(UsernameInUseException("ghost still tracked"))
        assertTrue(
            "UsernameInUse on primary is transient — must schedule a retry",
            executor.hasPendingSchedule(),
        )
        executor.fireScheduled()
        assertEquals("retry uses a fresh inner", 2, factoryInvocations.size)
    }

    // ============================================================
    // Operator disconnect — cancels pending work
    // ============================================================

    @Test
    fun `disconnect cancels a pending retry`() {
        val r = build()
        r.connect(upstream)
        capturedListeners[0].onDisconnected("blip")
        assertTrue(executor.hasPendingSchedule())
        r.disconnect()
        // Pending retry future was cancelled — firing it now is a no-op.
        assertTrue(
            "disconnect must cancel the queued retry",
            executor.lastScheduledFuture?.isCancelled == true,
        )
    }

    @Test
    fun `disconnect after a successful connect tears down the inner`() {
        val r = build()
        r.connect(upstream)
        capturedListeners[0].onConnected()
        r.disconnect()
        verify { factoryInvocations[0].disconnect() }
    }

    @Test
    fun `disconnect does not call the factory again — no new attempt after operator quit`() {
        val r = build()
        r.connect(upstream)
        capturedListeners[0].onConnected()
        r.disconnect()
        capturedListeners[0].onDisconnected("late callback after operator disconnect")
        assertFalse(
            "no retry should schedule after teardownRequested",
            executor.hasPendingSchedule(),
        )
        assertEquals("factory invocations frozen at 1", 1, factoryInvocations.size)
    }

    // ============================================================
    // Network swap — collapse pending backoff, force-rebuild live
    // ============================================================

    @Test
    fun `network swap during pending backoff collapses to immediate retry`() {
        val r = build()
        r.connect(upstream)
        capturedListeners[0].onDisconnected("blip")
        val pending = executor.lastScheduledFuture
        r.notifyNetworkSwap()
        assertTrue(
            "pending retry should be cancelled by network swap",
            pending?.isCancelled == true,
        )
        // The immediate retry ran via submit on the same executor.
        assertEquals("immediate factory call after collapse", 2, factoryInvocations.size)
    }

    @Test
    fun `network swap with live inner triggers a disconnect (listener drives reconnect)`() {
        val r = build()
        r.connect(upstream)
        capturedListeners[0].onConnected()
        primaryConnectedResult = true
        r.notifyNetworkSwap()
        verify { factoryInvocations[0].disconnect() }
    }

    // ============================================================
    // Bug fix (2026-06): notifyNetworkSwap during the TLS / Authenticate
    // handshake must tear down and re-attempt — without this branch the
    // previous wrapper code matched the "no live inner" else and
    // silently waited 30-50 s for the stale-link watchdog to notice the
    // orphaned socket. Verified by faking a MumbleSession that reports
    // CONNECTING (or AUTHENTICATING) while isConnected stays false.
    // ============================================================

    @Test
    fun `notifyNetworkSwap_duringHandshake_tearsDownAndReattempts`() {
        val r = build()
        r.connect(upstream)
        // Fresh attempt: inner.isConnected is false (no ServerSync yet)
        // and the primary MumbleSession reports CONNECTING — exactly
        // the window where the prior code dropped the swap on the floor.
        primaryConnectedResult = false
        val freshInner = factoryInvocations[0]
        val fakeSession = mockk<MumbleSession>(relaxed = true)
        every { fakeSession.currentState() } returns MumbleSession.ConnectState.CONNECTING
        every { freshInner.primarySession() } returns fakeSession

        r.notifyNetworkSwap()

        // Mid-handshake branch ran: disconnect + awaitFullyDisconnected
        // on the SAME inner, then a fresh attempt via the factory.
        // Two disconnect calls are expected — one from the swap path
        // itself, one from runAttempt's Step 1 (which tears down any
        // prior inner before building the new one). The important
        // invariant is the swap path called it AT LEAST once, vs the
        // pre-fix behavior where it was never called and we waited
        // ~30-50s for the stale-link watchdog.
        verify(atLeast = 1) { freshInner.disconnect() }
        verify { freshInner.awaitFullyDisconnected(1500) }
        assertEquals(
            "mid-handshake swap must drive a brand-new factory attempt, not wait on the orphaned socket",
            2,
            factoryInvocations.size,
        )
    }

    @Test
    fun `notifyNetworkSwap_duringAuthenticating_tearsDownAndReattempts`() {
        val r = build()
        r.connect(upstream)
        // AUTHENTICATING is the same critical window — TLS up, Version /
        // Authenticate on the wire, ServerSync not yet landed so the
        // wrapper's isConnected reads false.
        primaryConnectedResult = false
        val freshInner = factoryInvocations[0]
        val fakeSession = mockk<MumbleSession>(relaxed = true)
        every { fakeSession.currentState() } returns MumbleSession.ConnectState.AUTHENTICATING
        every { freshInner.primarySession() } returns fakeSession

        r.notifyNetworkSwap()

        // Same as the CONNECTING test: at least one disconnect from
        // the swap path itself; runAttempt's Step 1 may add a second.
        verify(atLeast = 1) { freshInner.disconnect() }
        assertEquals(2, factoryInvocations.size)
    }

    @Test
    fun `notifyNetworkSwap with disconnected inner does not reattempt`() {
        val r = build()
        r.connect(upstream)
        // Inner exists but its primary session is fully gone — no
        // pending retry, no live connection, no in-flight handshake.
        // Per the wrapper contract this is a no-op: the next user
        // action (operator reconnect, listener-driven retry) will
        // build a fresh attempt.
        primaryConnectedResult = false
        val freshInner = factoryInvocations[0]
        val fakeSession = mockk<MumbleSession>(relaxed = true)
        every { fakeSession.currentState() } returns MumbleSession.ConnectState.DISCONNECTED
        every { freshInner.primarySession() } returns fakeSession

        // Drain the post-construction pending future so the
        // pending-backoff branch doesn't pick this up.
        executor.fireScheduled()
        val attemptsBefore = factoryInvocations.size

        r.notifyNetworkSwap()

        assertEquals(
            "swap on a fully disconnected inner with no pending retry must NOT spawn a new attempt",
            attemptsBefore,
            factoryInvocations.size,
        )
    }

    // ============================================================
    // sendFrame — proxies to live inner; tolerates a torn-down inner
    // ============================================================

    @Test
    fun `sendFrame proxies to the current inner`() {
        val r = build()
        r.connect(upstream)
        val frame = VoiceFrame(opusPayload = byteArrayOf(1, 2, 3))
        r.sendFrame(frame)
        verify(exactly = 1) { factoryInvocations[0].sendFrame(frame) }
    }

    // ============================================================
    // current() — exposes the live inner for transport-specific ops
    // ============================================================

    @Test
    fun `current returns the most recent inner`() {
        val r = build()
        r.connect(upstream)
        assertSame(factoryInvocations[0], r.current())
        capturedListeners[0].onDisconnected("blip")
        executor.fireScheduled()
        assertSame("current must follow factory between attempts", factoryInvocations[1], r.current())
    }

    @Test
    fun `passthrough — voice frame from inner reaches upstream`() {
        val r = build()
        r.connect(upstream)
        val frame = VoiceFrame(opusPayload = byteArrayOf(0x42))
        capturedListeners[0].onVoiceFrame(frame)
        verify(exactly = 1) { upstream.onVoiceFrame(frame) }
    }

    // ============================================================
    // Sanity — additional passthroughs that the intercept handles
    // ============================================================

    @Test
    fun `passthrough — peer started talking reaches upstream`() {
        val r = build()
        r.connect(upstream)
        capturedListeners[0].onPeerStartedTalking("alice")
        verify { upstream.onPeerStartedTalking("alice") }
    }

    @Test
    fun `passthrough — peer stopped talking reaches upstream`() {
        val r = build()
        r.connect(upstream)
        capturedListeners[0].onPeerStoppedTalking("alice")
        verify { upstream.onPeerStoppedTalking("alice") }
    }

    // ============================================================
    // Auto-reconnect toggle — OFF suspends the background ladder
    // ============================================================

    @Test
    fun `auto-reconnect disabled suspends the ladder instead of scheduling`() {
        val r = buildWith { false }
        r.connect(upstream)
        capturedListeners[0].onDisconnected("blip")
        // Operator turned auto-reconnect off (limited-connectivity ops):
        // a drop must NOT queue a background retry, and the UI reads
        // "disconnected" rather than a "reconnecting…" that never fires.
        assertFalse(
            "auto-reconnect off must not schedule a background retry",
            executor.hasPendingSchedule(),
        )
        assertFalse("suspended ladder is not 'reconnecting'", r.isReconnecting())
        assertEquals("no fresh attempt while suspended", 1, factoryInvocations.size)
    }

    // ============================================================
    // retryNow — operator PTT collapses backoff / re-arms a paused ladder
    // ============================================================

    @Test
    fun `retryNow forces an attempt even when auto-reconnect is disabled`() {
        primaryConnectedResult = false
        val r = buildWith { false }
        r.connect(upstream)
        capturedListeners[0].onDisconnected("blip")
        assertFalse(executor.hasPendingSchedule())
        val before = factoryInvocations.size
        // Operator keys PTT on the dead channel — an explicit "connect me"
        // request that ignores the toggle and rebuilds immediately.
        r.retryNow()
        assertEquals("retryNow must rebuild a fresh inner", before + 1, factoryInvocations.size)
    }

    @Test
    fun `retryNow collapses a pending backoff into an immediate attempt`() {
        val r = build()
        r.connect(upstream)
        capturedListeners[0].onDisconnected("blip")
        val pending = executor.lastScheduledFuture
        r.retryNow()
        assertTrue("pending backoff cancelled by retryNow", pending?.isCancelled == true)
        assertEquals("immediate rebuild after collapse", 2, factoryInvocations.size)
    }

    @Test
    fun `retryNow is a no-op when already connected`() {
        val r = build()
        r.connect(upstream)
        capturedListeners[0].onConnected()
        val before = factoryInvocations.size
        r.retryNow()
        assertEquals("no rebuild when the link is already up", before, factoryInvocations.size)
    }

    @Test
    fun `suspendAutoReconnect cancels a pending backoff and drops the reconnecting state`() {
        primaryConnectedResult = false
        val r = build()
        r.connect(upstream)
        capturedListeners[0].onDisconnected("blip")
        val pending = executor.lastScheduledFuture
        assertTrue("backoff pending before suspend", r.isReconnecting())
        // Operator switched auto-reconnect off — take effect at once.
        r.suspendAutoReconnect()
        assertTrue("pending backoff cancelled immediately", pending?.isCancelled == true)
        assertFalse("suspended ladder is not 'reconnecting'", r.isReconnecting())
    }

    @Test
    fun `pending retry is suppressed when auto-reconnect is toggled off during backoff`() {
        var enabled = true
        val r = buildWith { enabled }
        r.connect(upstream)
        capturedListeners[0].onDisconnected("blip")
        assertTrue(executor.hasPendingSchedule())
        val before = factoryInvocations.size
        // Operator flips the toggle off while the backoff is still pending;
        // the queued task must re-check the gate at fire time and suppress
        // the attempt rather than waking the radio one more time.
        enabled = false
        executor.fireScheduled()
        assertEquals(
            "fired retry must not rebuild when disabled mid-backoff",
            before,
            factoryInvocations.size,
        )
        assertFalse(r.isReconnecting())
    }

    // ============================================================
    // Auto-pause — stop scheduling after a long run of failures
    // ============================================================

    @Test
    fun `ladder auto-pauses after the pause threshold and stops scheduling`() {
        val r = build()
        r.connect(upstream)
        // Drive consecutive transient failures. Each disconnect schedules
        // a retry (attemptCount++); each fire rebuilds a fresh inner whose
        // freshly-captured listener we fail again next iteration.
        var idx = 0
        repeat(ReconnectPolicy.PAUSE_AFTER_ATTEMPTS) {
            capturedListeners[idx].onDisconnected("blip")
            assertTrue("still retrying below the pause threshold", executor.hasPendingSchedule())
            executor.fireScheduled()
            idx++
        }
        // attemptCount has now reached the threshold — the next failure
        // must auto-pause rather than schedule yet another retry.
        capturedListeners[idx].onDisconnected("blip past threshold")
        assertFalse("ladder must auto-pause at the threshold", executor.hasPendingSchedule())
        assertFalse("auto-paused ladder reads as not-reconnecting", r.isReconnecting())
    }

    /**
     * Custom ScheduledExecutorService used by every test:
     *
     *   - `submit(Runnable)` runs synchronously on the calling thread,
     *     so the wrapper's `runAttempt()` completes before the test
     *     moves on.
     *   - `schedule(Runnable, delay, unit)` captures the task + delay
     *     for inspection. Tests fire the pending schedule manually via
     *     [fireScheduled].
     *
     * Single-pending-schedule is fine for the wrapper's contract —
     * `scheduleRetry` checks `pendingRetry?.isDone == false` and skips
     * duplicate schedules, so at most one is pending at any time.
     */
    private class TestScheduledExecutor : AbstractExecutorService(), ScheduledExecutorService {
        var lastScheduledFuture: SyncFuture<*>? = null
            private set
        var lastScheduledDelayMs: Long = 0L
            private set
        private val pendingTasks = mutableListOf<SyncFuture<*>>()

        fun hasPendingSchedule(): Boolean = pendingTasks.any { !it.isDone && !it.isCancelled }

        fun fireScheduled() {
            val task = pendingTasks.lastOrNull { !it.isDone && !it.isCancelled }
            task?.runIfPending()
        }

        override fun execute(command: Runnable) {
            command.run()
        }

        override fun shutdown() {}

        override fun shutdownNow(): List<Runnable> = emptyList()

        override fun isShutdown(): Boolean = false

        override fun isTerminated(): Boolean = false

        override fun awaitTermination(
            timeout: Long,
            unit: TimeUnit,
        ): Boolean = true

        override fun schedule(
            command: Runnable,
            delay: Long,
            unit: TimeUnit,
        ): ScheduledFuture<*> {
            lastScheduledDelayMs = unit.toMillis(delay)
            val f = SyncFuture<Unit>(command, lastScheduledDelayMs)
            pendingTasks.add(f)
            lastScheduledFuture = f
            return f
        }

        override fun <V> schedule(
            callable: Callable<V>,
            delay: Long,
            unit: TimeUnit,
        ): ScheduledFuture<V> = throw UnsupportedOperationException("not used by wrapper")

        override fun scheduleAtFixedRate(
            command: Runnable,
            initialDelay: Long,
            period: Long,
            unit: TimeUnit,
        ): ScheduledFuture<*> = throw UnsupportedOperationException("not used by wrapper")

        override fun scheduleWithFixedDelay(
            command: Runnable,
            initialDelay: Long,
            delay: Long,
            unit: TimeUnit,
        ): ScheduledFuture<*> = throw UnsupportedOperationException("not used by wrapper")
    }

    private class SyncFuture<T>(
        private val task: Runnable,
        private val delayMs: Long,
    ) : ScheduledFuture<T> {
        private val cancelled = AtomicBoolean(false)
        private val done = AtomicBoolean(false)

        fun runIfPending() {
            if (cancelled.get() || done.get()) return
            done.set(true)
            task.run()
        }

        override fun getDelay(unit: TimeUnit): Long = unit.convert(delayMs, TimeUnit.MILLISECONDS)

        override fun compareTo(other: Delayed): Int = delayMs.compareTo(other.getDelay(TimeUnit.MILLISECONDS))

        override fun cancel(mayInterruptIfRunning: Boolean): Boolean {
            cancelled.set(true)
            return true
        }

        override fun isCancelled(): Boolean = cancelled.get()

        override fun isDone(): Boolean = done.get() || cancelled.get()

        @Suppress("UNCHECKED_CAST")
        override fun get(): T = null as T

        @Suppress("UNCHECKED_CAST")
        override fun get(
            timeout: Long,
            unit: TimeUnit,
        ): T = null as T
    }

    @Suppress("unused") // keeps MumbleSession import alive while tests evolve
    private val sessionRefForFutureUse: Class<MumbleSession> = MumbleSession::class.java
}
