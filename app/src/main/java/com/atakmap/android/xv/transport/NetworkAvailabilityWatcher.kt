package com.atakmap.android.xv.transport

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Watches Android's default network and fires [onSwap] whenever the
 * active network handle changes — e.g. wifi↔LTE handoff while
 * walking out of an AP, route flap, network IP rotation. Used by
 * the plugin to nudge [ReconnectingMumbleTransport] to drop its
 * orphaned TCP socket immediately rather than waiting 30-60s for
 * the kernel timeout.
 *
 * Edge-only: [onSwap] does NOT fire for the initial network at
 * registration time, only on transitions. This avoids tearing down
 * a freshly-built session because Android happens to deliver the
 * "current network" callback right after [start] is called.
 *
 * Debounced: a cellular bounce or wifi reassociation can fire 3-5
 * `onAvailable` callbacks within 200ms while the system settles. Each
 * fire would torpedo a fresh TLS handshake in flight. We collapse
 * notifications within [SWAP_DEBOUNCE_MS] into a single delivery so
 * the wrapper sees one swap event per logical transition.
 *
 * Single-threaded: the wrapped callback delivers on a binder thread
 * (per `ConnectivityManager.NetworkCallback` contract). The debounce
 * Handler is bound to the main looper — [onSwap] always fires on the
 * main thread. Keep [onSwap]'s body cheap and post any heavy work
 * elsewhere if needed.
 */
class NetworkAvailabilityWatcher(
    private val context: Context,
    private val onSwap: () -> Unit,
) {
    private val cm: ConnectivityManager? =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    private val debounceHandler = Handler(Looper.getMainLooper())
    private val debounceRunnable = Runnable { safeFire() }

    // Pending "still offline" fire scheduled from onLost. If a
    // replacement default network shows up via onAvailable within
    // [LOST_FIRE_DELAY_MS], we cancel this and run the normal swap
    // path instead. If no replacement arrives in that window we
    // fire once so the wrapper can surface "reconnecting…" and
    // tear down its dead inner immediately rather than waiting for
    // SO_TIMEOUT.
    private val lostFireRunnable = Runnable { safeFireIfStillOffline() }

    @Volatile
    private var currentNetwork: Network? = null

    @Volatile
    private var registered: Boolean = false

    private val callback =
        object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                val previous = currentNetwork
                currentNetwork = network
                // A replacement network arrived — cancel any
                // pending "still offline" fire from a prior onLost
                // so the loss path and the swap path don't both
                // deliver a notification for the same transition.
                debounceHandler.removeCallbacks(lostFireRunnable)
                if (previous == null) {
                    // Initial registration — note the current network
                    // but don't treat it as a swap.
                    Log.i(TAG, "initial default network = $network")
                } else if (previous != network) {
                    Log.i(TAG, "network swap: $previous -> $network (debounced)")
                    scheduleSwapFire()
                }
            }

            override fun onLost(network: Network) {
                if (currentNetwork == network) {
                    Log.i(TAG, "network lost: $network — clearing current, scheduling still-offline fire")
                    currentNetwork = null
                    // Schedule a deferred fire: if no replacement
                    // default network arrives within
                    // [LOST_FIRE_DELAY_MS], notify the wrapper so it
                    // can tear down its now-orphaned socket without
                    // waiting on SO_TIMEOUT. Guarded inside
                    // safeFireIfStillOffline by currentNetwork == null
                    // so a late onAvailable can preempt this even if
                    // removeCallbacks lost a race with the post.
                    debounceHandler.removeCallbacks(lostFireRunnable)
                    debounceHandler.postDelayed(lostFireRunnable, LOST_FIRE_DELAY_MS)
                }
            }

            override fun onCapabilitiesChanged(
                network: Network,
                caps: NetworkCapabilities,
            ) {
                // Handle in-place capability changes (e.g. validated→
                // unvalidated). Currently only logged; the swap path
                // covers handoffs since handoffs almost always change
                // the Network handle, not just its capabilities.
                if (currentNetwork == null) {
                    currentNetwork = network
                    Log.i(TAG, "first network observed via caps: $network ${caps.transportSummary()}")
                }
            }
        }

    fun start() {
        val mgr =
            cm ?: run {
                Log.w(TAG, "ConnectivityManager unavailable — network swap detection disabled")
                return
            }
        if (registered) return
        try {
            mgr.registerDefaultNetworkCallback(callback)
            registered = true
            Log.i(TAG, "started")
        } catch (t: Throwable) {
            Log.w(TAG, "registerDefaultNetworkCallback threw", t)
        }
    }

    fun stop() {
        val mgr = cm ?: return
        if (!registered) return
        try {
            mgr.unregisterNetworkCallback(callback)
        } catch (t: Throwable) {
            Log.w(TAG, "unregisterNetworkCallback threw", t)
        }
        // Cancel any pending debounced fire — we're tearing down,
        // delivering after stop() would race against any new watcher
        // the caller spins up against a fresh transport.
        debounceHandler.removeCallbacks(debounceRunnable)
        debounceHandler.removeCallbacks(lostFireRunnable)
        registered = false
        currentNetwork = null
        Log.i(TAG, "stopped")
    }

    private fun scheduleSwapFire() {
        // Coalesce: if a fire is already pending, drop this one. We only
        // need to know that *something* changed, not how many times.
        debounceHandler.removeCallbacks(debounceRunnable)
        debounceHandler.postDelayed(debounceRunnable, SWAP_DEBOUNCE_MS)
    }

    private fun safeFire() {
        try {
            onSwap()
        } catch (t: Throwable) {
            Log.w(TAG, "onSwap threw", t)
        }
    }

    /**
     * Fire the still-offline event scheduled from [onLost]. Guarded
     * by [currentNetwork] == null so a late onAvailable that raced
     * with removeCallbacks (different binder thread post + a main
     * looper drain) does NOT deliver a doomed swap notification on
     * top of the normal swap path.
     */
    private fun safeFireIfStillOffline() {
        if (currentNetwork != null) {
            // Replacement landed while we were waiting — the
            // onAvailable swap path already handled it.
            return
        }
        Log.i(TAG, "still-offline fire: no replacement within ${LOST_FIRE_DELAY_MS}ms — notifying")
        safeFire()
    }

    private fun NetworkCapabilities.transportSummary(): String {
        val parts = mutableListOf<String>()
        if (hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) parts += "wifi"
        if (hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) parts += "cellular"
        if (hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) parts += "eth"
        if (hasTransport(NetworkCapabilities.TRANSPORT_VPN)) parts += "vpn"
        return parts.joinToString(",", prefix = "[", postfix = "]")
    }

    companion object {
        private const val TAG = "XvNetWatch"

        // Cellular bounce / wifi reassociation tends to fire 3-5
        // onAvailable callbacks inside ~200ms. 250ms covers that —
        // tightened from the prior 500ms after field reports that
        // the perceived freeze on a handoff was dominated by this
        // debounce. Cellular bounce typically settles in ~200ms, so
        // 250ms still coalesces the burst into one fire but halves
        // the wall-clock delay before the wrapper sees the swap.
        private const val SWAP_DEBOUNCE_MS: Long = 250L

        // How long to wait on bare network loss before firing the
        // wrapper. If a replacement default network shows up within
        // this window the onAvailable path delivers the swap event
        // and this fire is cancelled. If no replacement arrives, we
        // still need to nudge the wrapper so its inner socket can
        // unwind before SO_TIMEOUT (~20s post-fix). 2 s matches the
        // typical Wi-Fi -> cell promotion latency Android needs to
        // bring up the cellular default route.
        private const val LOST_FIRE_DELAY_MS: Long = 2_000L
    }
}
