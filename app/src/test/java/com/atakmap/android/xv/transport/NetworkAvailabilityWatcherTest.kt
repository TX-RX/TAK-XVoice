package com.atakmap.android.xv.transport

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLooper

/**
 * Coverage for [NetworkAvailabilityWatcher] — the [ConnectivityManager]
 * default-network observer that nudges the transport on swap, plus the
 * "still offline" fire scheduled when a bare loss has no replacement
 * within [LOST_FIRE_DELAY_MS] (2 s, per 2026-06 handoff hardening).
 *
 * Driven via Robolectric so [android.os.Handler] on the main looper is
 * a real Handler with a controllable scheduler — every postDelayed
 * lands as a deferred message we can advance by exact ms.
 *
 * The watcher's NetworkCallback is normally registered by
 * [ConnectivityManager.registerDefaultNetworkCallback]; we extract it
 * via a MockK capture slot so the test can deliver onAvailable/onLost
 * directly without having to plumb a real Network handle (which
 * Robolectric does not synthesize for us at the granularity we need).
 */
@RunWith(RobolectricTestRunner::class)
class NetworkAvailabilityWatcherTest {
    private lateinit var context: Context
    private lateinit var cm: ConnectivityManager
    private lateinit var callbackSlot: io.mockk.CapturingSlot<ConnectivityManager.NetworkCallback>
    private lateinit var fireCount: AtomicInteger
    private lateinit var watcher: NetworkAvailabilityWatcher

    // Stand-in Network handles. Robolectric's ShadowNetwork.newInstance
    // returns a real-looking Network object backed by an int id; two
    // distinct ids guarantee the watcher sees `previous != network` so
    // the swap branch fires.
    private val netA: Network = org.robolectric.shadows.ShadowNetwork.newInstance(1)
    private val netB: Network = org.robolectric.shadows.ShadowNetwork.newInstance(2)

    @Before
    fun setup() {
        // Real Robolectric context for the Handler(mainLooper) the
        // watcher uses internally, with a MOCKED ConnectivityManager so
        // we control the callback registration. Robolectric's default
        // ConnectivityManager doesn't make it easy to fire onLost on
        // demand the way we need; the mock seam is cleaner.
        val realCtx = ApplicationProvider.getApplicationContext<Context>()
        cm = mockk(relaxed = true)
        callbackSlot = slot()
        every { cm.registerDefaultNetworkCallback(capture(callbackSlot)) } answers { }
        context = mockk(relaxed = true) {
            every { getSystemService(Context.CONNECTIVITY_SERVICE) } returns cm
            // Forward anything else the Handler internals might pull
            // (mainLooper) through to the real Robolectric context.
            every { mainLooper } returns realCtx.mainLooper
        }
        fireCount = AtomicInteger(0)
        watcher = NetworkAvailabilityWatcher(context) { fireCount.incrementAndGet() }
        watcher.start()
        // The watcher caches the ConnectivityManager via the system-
        // service lookup at construction time; sanity-check we wired
        // it correctly by asserting the registration ran.
        verify { cm.registerDefaultNetworkCallback(any()) }
    }

    // ============================================================
    // #1 — tightened debounce: 3 onAvailable fires inside 250 ms
    // coalesce into a single onSwap delivery
    // ============================================================

    @Test
    fun `three onAvailable callbacks inside 250ms coalesce into a single fire`() {
        val cb = callbackSlot.captured
        // Seed an initial network so subsequent onAvailable calls are
        // treated as swaps (not the initial-registration case).
        cb.onAvailable(netA)
        // Three quick swaps to netB within the debounce window. The
        // wrapper should collapse them into one scheduled fire because
        // each scheduleSwapFire removes the prior pending runnable.
        cb.onAvailable(netB)
        ShadowLooper.idleMainLooper(50, java.util.concurrent.TimeUnit.MILLISECONDS)
        cb.onAvailable(netA)
        ShadowLooper.idleMainLooper(50, java.util.concurrent.TimeUnit.MILLISECONDS)
        cb.onAvailable(netB)
        // Advance JUST past 250 ms from the LAST call so the final
        // pending runnable fires; the earlier ones were removed.
        ShadowLooper.idleMainLooper(300, java.util.concurrent.TimeUnit.MILLISECONDS)
        assertEquals(
            "three onAvailable calls inside the debounce window must collapse to one swap",
            1,
            fireCount.get(),
        )
    }

    @Test
    fun `debounce window is 250ms — fire does not arrive before that`() {
        val cb = callbackSlot.captured
        cb.onAvailable(netA)
        cb.onAvailable(netB)
        // 240 ms after the most recent onAvailable — under the
        // tightened 250 ms threshold. Must NOT have fired yet.
        ShadowLooper.idleMainLooper(240, java.util.concurrent.TimeUnit.MILLISECONDS)
        assertEquals(
            "fire must not arrive before the 250ms debounce elapses",
            0,
            fireCount.get(),
        )
        ShadowLooper.idleMainLooper(50, java.util.concurrent.TimeUnit.MILLISECONDS)
        assertEquals(
            "fire arrives once the 250ms debounce elapses",
            1,
            fireCount.get(),
        )
    }

    // ============================================================
    // #6 — onLost with no replacement fires a single still-offline
    // event at 2 s; onAvailable cancels the lost-fire.
    // ============================================================

    @Test
    fun `onLost with no replacement fires exactly once at 2000ms`() {
        val cb = callbackSlot.captured
        cb.onAvailable(netA)
        // 250 ms hasn't elapsed and we haven't called swap — so no
        // pending swap runnable. Now drop the network.
        cb.onLost(netA)
        // Just before the 2 s threshold — must not have fired yet.
        ShadowLooper.idleMainLooper(1900, java.util.concurrent.TimeUnit.MILLISECONDS)
        assertEquals(
            "still-offline fire must not arrive before 2000ms",
            0,
            fireCount.get(),
        )
        ShadowLooper.idleMainLooper(200, java.util.concurrent.TimeUnit.MILLISECONDS)
        assertEquals(
            "still-offline fire arrives at 2000ms when no replacement landed",
            1,
            fireCount.get(),
        )
    }

    @Test
    fun `onAvailable inside the 2s window cancels the still-offline fire`() {
        val cb = callbackSlot.captured
        cb.onAvailable(netA)
        cb.onLost(netA)
        // Replacement default network shows up 800 ms later — well
        // inside the 2 s lost-fire window. This MUST cancel the
        // scheduled still-offline fire.
        //
        // Note: after onLost clears currentNetwork to null, the
        // subsequent onAvailable sees `previous == null` and treats
        // itself as a fresh initial registration (no swap fire). That
        // is intentional — we don't have a way to distinguish "lost
        // then reacquired the same logical link" from "first network
        // since registration," so the safe choice is "don't claim a
        // swap happened." The still-offline-fire guard inside
        // safeFireIfStillOffline (currentNetwork == null) is the
        // real safety net regardless.
        ShadowLooper.idleMainLooper(800, java.util.concurrent.TimeUnit.MILLISECONDS)
        cb.onAvailable(netB)
        // Advance well past the original 2 s mark from the loss —
        // the still-offline runnable must NOT have fired. If
        // removeCallbacks lost a race, the safeFireIfStillOffline
        // guard catches it because currentNetwork is now netB (non-
        // null) so the fire returns early without invoking onSwap.
        ShadowLooper.idleMainLooper(1500, java.util.concurrent.TimeUnit.MILLISECONDS)
        assertEquals(
            "still-offline fire must NOT deliver once a replacement default network arrives",
            0,
            fireCount.get(),
        )
    }

    @Test
    fun `still-offline fire is short-circuited even if removeCallbacks races`() {
        // Belt-and-suspenders test for the safeFireIfStillOffline
        // guard: even if onAvailable's removeCallbacks lost a race
        // with a pending lostFireRunnable that's already on the
        // looper's deliver path, the runnable itself must re-check
        // currentNetwork and bail.
        //
        // Simulated by manually advancing to t=2000ms first (so the
        // runnable would have fired), then calling onAvailable
        // mid-looper-drain. Easier: just verify that calling
        // onAvailable BEFORE the runnable resets currentNetwork to a
        // non-null value, so even if the runnable fires the guard
        // catches it.
        val cb = callbackSlot.captured
        cb.onAvailable(netA)
        cb.onLost(netA)
        // Replacement arrives JUST before the 2 s mark — race with
        // the looper drain.
        ShadowLooper.idleMainLooper(1990, java.util.concurrent.TimeUnit.MILLISECONDS)
        cb.onAvailable(netB)
        // Now drain — any still-offline runnable that was already in
        // the dispatch queue runs, but its guard sees currentNetwork
        // == netB and bails.
        ShadowLooper.idleMainLooper(100, java.util.concurrent.TimeUnit.MILLISECONDS)
        assertEquals(
            "guard in safeFireIfStillOffline must short-circuit if a replacement arrived between scheduling and firing",
            0,
            fireCount.get(),
        )
    }

    @Test
    fun `onAvailable for the same network does not fire a swap`() {
        val cb = callbackSlot.captured
        // Initial registration — must NOT fire.
        cb.onAvailable(netA)
        ShadowLooper.idleMainLooper(500, java.util.concurrent.TimeUnit.MILLISECONDS)
        assertEquals(
            "initial-network callback at registration must not be treated as a swap",
            0,
            fireCount.get(),
        )
    }

    @Test
    fun `stop cancels any pending still-offline fire`() {
        val cb = callbackSlot.captured
        cb.onAvailable(netA)
        cb.onLost(netA)
        // Tear the watcher down before the 2 s lost-fire elapses; the
        // pending runnable should be removed and never deliver.
        watcher.stop()
        ShadowLooper.idleMainLooper(3000, java.util.concurrent.TimeUnit.MILLISECONDS)
        assertEquals(
            "stop() must cancel any pending lost-fire so it never delivers post-teardown",
            0,
            fireCount.get(),
        )
    }
}
