package com.atakmap.android.xv.transport

import android.util.Log
import com.atakmap.android.xv.transport.mumble.FatalMumbleException
import com.atakmap.android.xv.transport.mumble.MumbleSession
import com.atakmap.android.xv.transport.mumble.SelfKickedException
import com.atakmap.android.xv.transport.mumble.UsernameInUseException
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

// Long-lived voice transport that wraps an inner [MumbleTransport]
// and re-establishes the inner session on transient failure (network
// blip, server bounce, transient TLS error, ServerFull). Per the
// VoiceTransport contract a single MumbleTransport is single-use, so
// each retry constructs a fresh inner via the supplied [factory].
//
// Threading model (locked 2026-05-10):
//   Every connect/disconnect/notifyNetworkSwap operation runs on a
//   single-threaded scheduled executor (`xv-mumble-recon`). Two cycles
//   of "build inner → connect VS1 → wait for VS1 READY → connect VS2"
//   cannot overlap; the executor's queue serializes them. A pending
//   retry is a ScheduledFuture submitted to the same executor — when
//   the operator hits Disconnect, that future is cancelled before any
//   new work is queued.
//
//   This replaces the prior Handler(mainLooper) + reconnectScheduled
//   guard pattern, which had a race window between "set the flag" and
//   "actually post the runnable" during which a second
//   `notifyNetworkSwap` could collapse the (not yet posted) backoff
//   and double up the in-flight connect. Single-threaded executor
//   makes that window impossible.
//
// Failure classes (see ReconnectPolicy.Outcome):
//   - Fatal — auth wall, NoCertificate, banned, server-side self-kick.
//     Surfaced via onConnectionFailed; NOT retried (would loop into
//     the same wall).
//   - UsernameInUse — primary slot: ghost session still tracked
//     server-side; backoff + retry under the same stable username.
//     Murmur cleans the ghost within ~30-60s and the next attempt
//     succeeds.
//   - Transient — anything else. Retried with capped exponential
//     backoff via the [ReconnectPolicy].
//
// VS2 ghost-cleanup ladder: on secondary-side UsernameInUse / self-kick,
// the wrapper schedules retries at +3s, +8s (same deterministic suffix).
// If both retries still see a ghost we surface the failure rather than
// rotating to a fresh identity — slot suffixes are deterministic per
// (device, slot) so there's nothing to rotate to.
//
// The wrapper exposes [current] so call sites that need MumbleTransport-
// specific operations (joinChannel, retargetSecondary, availableChannels)
// can reach the live inner. That inner reference changes between
// retries — call sites must re-look it up rather than caching.
class ReconnectingMumbleTransport(
    override val config: TransportConfig.Mumble,
    private val primarySlotSuffix: String,
    private val secondarySlotSuffix: String,
    // Builds a fresh MumbleTransport with the given slot suffixes.
    // Both suffixes are deterministic per (device, slot) and never
    // change for the lifetime of the wrapper.
    private val factory: (primarySuffix: String, secondarySuffix: String) -> MumbleTransport,
    // Optional per-state hooks for the UI. Defaults to no-op so call
    // sites that don't care about reconnect state don't grow.
    private val onReconnectStateChanged: (attempt: Int, scheduledDelayMs: Long) -> Unit = { _, _ -> },
    // Operator gate for AUTOMATIC reconnect attempts. Read on every
    // scheduleRetry so a live settings toggle takes effect without a
    // reconnect. When it returns false the wrapper stops scheduling
    // background retries entirely — for limited-connectivity ops where
    // the operator knows the server is unreachable and doesn't want the
    // battery drain or the periodic "voice lost" alert. This gates only
    // the automatic ladder: an explicit connect() or retryNow() (PTT
    // press) still attempts regardless, because those are direct "connect
    // me now" requests. Default always-on preserves prior behavior.
    private val reconnectEnabled: () -> Boolean = { true },
    // Single-threaded executor for ALL connect/disconnect/network-swap
    // work. Daemon thread so it doesn't block process exit. Naming is
    // intentional — grep-able in logcat for thread-attribution. Exposed
    // as a constructor parameter so tests can inject a deterministic
    // executor (e.g. immediate-run for submit, manually-fired for
    // schedule) without standing up real threads.
    private val executor: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "xv-mumble-recon").apply { isDaemon = true }
        },
) : VoiceTransport {

    private val policy = ReconnectPolicy()

    @Volatile
    private var inner: MumbleTransport? = null

    @Volatile
    private var upstream: TransportListener? = null

    @Volatile
    private var teardownRequested: Boolean = false

    // Pending retry handle. Cancellable when an operator-initiated
    // disconnect / network-swap collapses the backoff.
    @Volatile
    private var pendingRetry: ScheduledFuture<*>? = null

    // True only while the executor is mid-way through an attempt OR a
    // retry is queued. Read by UI for "reconnecting…" indicator.
    private val reconnecting = AtomicBoolean(false)

    // Set when notifyNetworkSwap is driving an immediate teardown +
    // re-attempt. Consumed once by intercept.onDisconnected — that's the
    // signal to skip the disconnect-driven scheduleRetry path so the
    // 1s backoff doesn't add latency to a known-good network swap. The
    // swap path calls runAttempt() directly instead.
    private val swapInProgress = AtomicBoolean(false)

    // Latched once a Fatal outcome has been observed (e.g. self-kicked
    // because the server sees a duplicate username). Blocks any
    // subsequent scheduleRetry — including the Transient one that
    // onDisconnected fires when the read loop exits after the same
    // failure — from tight-looping the reconnect. Cleared by an
    // operator-initiated connect() or by a successful onConnected
    // (network swap / manual retry re-arms the wrapper).
    private val fatalRejected = AtomicBoolean(false)

    @Volatile
    private var lastScheduledDelayMs: Long = 0L

    // Set once the auto-pause threshold is crossed (or auto-reconnect is
    // switched off): the ladder has stopped scheduling and is waiting for
    // an explicit re-arm. Read by [isReconnecting] so the UI drops from
    // amber "reconnecting…" back to a plain "disconnected" state — honest
    // signalling that XV is no longer actively trying on its own.
    @Volatile
    private var reconnectSuspended: Boolean = false

    // VS2 ghost-cleanup attempt counter. Resets on a successful VS2
    // connect (in onSecondaryFailed when we get a clean connect, we
    // see the upstream onConnected from the primary, but secondary
    // success is silent — so the counter rolls over instead on a
    // FRESH primary connect cycle).
    private val secondaryUsernameInUseAttempts = AtomicInteger(0)

    /** Live inner transport, or null while between attempts (during
     *  the backoff window or before [connect] is called). Call sites
     *  that need MumbleTransport-specific operations should call this
     *  on each use rather than caching the result. */
    fun current(): MumbleTransport? = inner

    /** True when the wrapper is between attempts — the upstream
     *  saw an `onDisconnected` and a retry is queued. UI uses this
     *  to render "reconnecting…" instead of "disconnected". False once
     *  the ladder has auto-paused (or been switched off) so the UI
     *  stops implying an attempt is imminent. */
    fun isReconnecting(): Boolean = reconnecting.get() && !teardownRequested && !reconnectSuspended

    /** Number of consecutive retry attempts without a successful
     *  connect. Resets to 0 on a clean (re)connect. */
    fun reconnectAttempt(): Int = policy.attemptCount()

    /** Backoff delay handed to the most recently scheduled retry —
     *  not "time remaining," just the policy's chosen wait. UI uses
     *  this to render an approximate countdown. 0 if no retry has
     *  been scheduled in this lifecycle yet. */
    fun nextScheduledDelayMs(): Long = lastScheduledDelayMs

    /**
     * External signal that the underlying network link just changed
     * (wifi↔LTE handoff, IP address rotation, transport route flap).
     *
     * Without this, a wifi-to-LTE handoff dies silently for ~30-60s
     * until the orphaned TCP socket times out — the operator hears
     * nothing and the dot stays green. With this, we tear down the
     * orphaned live inner so its disconnect path triggers the
     * existing retry chain.
     *
     * Runs on the wrapper's single executor — any concurrent connect
     * / disconnect work serializes behind this.
     */
    fun notifyNetworkSwap() {
        executor.submit {
            if (teardownRequested) return@submit
            val pending = pendingRetry
            val currentInner = inner
            val primaryState = currentInner?.primarySession()?.currentState()
            // CONNECTING / AUTHENTICATING means a fresh inner is mid-
            // handshake: TLS or Authenticate already on the wire, but
            // ServerSync hasn't landed yet so [isConnected] reads false.
            // Without the explicit handshake branch below, this case
            // matched the "no live inner" else and we waited ~30-50 s
            // for the stale-link watchdog to notice the orphaned socket
            // (see field bug 2026-06-15: operator hits "voice gone" on
            // Wi-Fi -> cell handoff right at the moment of connect).
            // Treat mid-handshake exactly like a live-inner swap: tear
            // down, await quiescence, drive a fresh attempt.
            val handshakeInFlight =
                primaryState == MumbleSession.ConnectState.CONNECTING ||
                    primaryState == MumbleSession.ConnectState.AUTHENTICATING
            val midHandshake =
                currentInner != null && !currentInner.isConnected && handshakeInFlight
            when {
                pending != null && !pending.isDone -> {
                    Log.i(TAG, "network swap — collapsing pending backoff and retrying immediately")
                    pending.cancel(false)
                    pendingRetry = null
                    policy.reset()
                    runAttempt()
                }
                currentInner?.isConnected == true -> {
                    Log.i(TAG, "network swap — tearing down live inner for immediate reattempt")
                    // Tell intercept.onDisconnected to skip its retry
                    // schedule; we're driving the next attempt directly
                    // and don't want a parallel scheduled retry racing
                    // against runAttempt(). Set BEFORE disconnect so the
                    // read-thread's finally-block fires onDisconnected
                    // with the flag already visible.
                    swapInProgress.set(true)
                    try {
                        currentInner.disconnect()
                        // Brief wait so runAttempt's Step 1 (which also
                        // disconnects + awaits) doesn't burn 1.5s on a
                        // socket that's already mid-teardown here. Cap
                        // matches awaitFullyDisconnected's own budget.
                        currentInner.awaitFullyDisconnected(1500)
                    } catch (t: Throwable) {
                        Log.w(TAG, "inner.disconnect threw on network swap", t)
                    }
                    policy.reset()
                    runAttempt()
                }
                midHandshake -> {
                    // BUG FIX (2026-06-15): mid-handshake swap previously
                    // fell through to the "no live inner" else and the
                    // orphaned socket sat there until the SO_TIMEOUT /
                    // stale-link watchdog noticed — ~30-50 s of dead air
                    // on a network-promotion event the watcher had
                    // already told us about.
                    Log.i(
                        TAG,
                        "network swap — inner mid-handshake ($primaryState); tearing down for immediate reattempt",
                    )
                    swapInProgress.set(true)
                    try {
                        currentInner!!.disconnect()
                        currentInner.awaitFullyDisconnected(1500)
                    } catch (t: Throwable) {
                        Log.w(TAG, "inner.disconnect threw on mid-handshake network swap", t)
                    }
                    policy.reset()
                    runAttempt()
                }
                else -> Log.i(TAG, "network swap — no live inner, ignoring")
            }
        }
    }

    override val isConnected: Boolean
        get() = inner?.isConnected == true

    override fun connect(listener: TransportListener) {
        upstream = listener
        teardownRequested = false
        policy.reset()
        secondaryUsernameInUseAttempts.set(0)
        fatalRejected.set(false)
        reconnectSuspended = false
        executor.submit { runAttempt() }
    }

    /**
     * Operator-initiated "reconnect now." Collapses any pending backoff
     * (or dormant auto-pause) and drives a fresh attempt immediately.
     * Wired to the PTT-down path so keying a dead channel tries to bring
     * voice back without waiting out the ladder — and re-arms a ladder
     * that had auto-paused or been switched off. Deliberately ignores
     * [reconnectEnabled]: an explicit PTT press is a direct "connect me"
     * request, distinct from the automatic background retries that toggle
     * governs. No-op if already connected or torn down.
     *
     * Runs on the wrapper's single executor so it serializes with any
     * in-flight connect / disconnect / retry work.
     */
    fun retryNow() {
        executor.submit {
            if (teardownRequested) return@submit
            if (inner?.isConnected == true) return@submit
            Log.i(TAG, "retryNow() — operator-initiated; collapsing backoff/pause")
            pendingRetry?.cancel(false)
            pendingRetry = null
            policy.reset()
            reconnectSuspended = false
            // An explicit reconnect request clears the fatal latch for a
            // single fresh shot — same rationale as connect(). If the
            // fatal cause persists the next attempt re-latches it; this
            // fires once per press, so it can't tight-loop.
            fatalRejected.set(false)
            runAttempt()
        }
    }

    override fun sendFrame(frame: VoiceFrame) {
        // sendFrame intentionally NOT executor-routed: voice frames
        // arrive ~50/s from the audio thread and queueing them
        // through the recon executor would add per-frame latency for
        // no benefit. The inner reference is volatile; worst case
        // during a swap is a couple of frames sent to a torn-down
        // inner, which drops them harmlessly.
        inner?.sendFrame(frame)
    }

    override fun disconnect() {
        Log.i(TAG, "disconnect() — operator-initiated, cancelling any pending retry")
        teardownRequested = true
        pendingRetry?.cancel(false)
        pendingRetry = null
        reconnecting.set(false)
        // Submit teardown on the executor so it serializes with any
        // in-flight runAttempt. Don't await — caller is the UI
        // thread and the executor will run it shortly.
        executor.submit {
            try {
                inner?.disconnect()
                // Best-effort wait for full quiescence. 1500ms covers
                // VS1 + VS2 teardown sequentially (each capped at
                // ~900ms = 500ms read-join + 400ms write-await).
                inner?.awaitFullyDisconnected(1500)
            } catch (t: Throwable) {
                Log.w(TAG, "inner.disconnect threw", t)
            }
            inner = null
            upstream = null
        }
        // Don't shutdown the executor — disconnect() may be followed
        // by connect() within the same wrapper lifetime, and an
        // already-shutdown executor can't accept new tasks. Executor
        // is a daemon thread so GC of the wrapper releases it.
    }

    /**
     * The full per-attempt sequence. Runs on the executor; idempotent
     * re-entry is prevented by the executor's single-threading.
     */
    private fun runAttempt() {
        if (teardownRequested) return
        reconnecting.set(true)
        pendingRetry = null

        // Step 1: tear down any prior inner. A failed previous attempt
        // may have left state around (ScoLink, ping thread, write
        // executor). Calling disconnect on a never-started session is
        // safe.
        val prior = inner
        if (prior != null) {
            try {
                prior.disconnect()
            } catch (t: Throwable) {
                Log.w(TAG, "pre-attempt inner.disconnect threw", t)
            }
            // Wait for both slots to be fully gone before the fresh
            // inner's VS1 can start — otherwise a stale VS2 socket
            // can race against our new auth. Capped at 1500ms;
            // overrunning isn't fatal but logs a warning.
            try {
                prior.awaitFullyDisconnected(1500)
            } catch (t: Throwable) {
                Log.w(TAG, "awaitFullyDisconnected threw", t)
            }
        }

        // Step 2: build a fresh inner. Slot suffixes are deterministic
        // per (device, slot) and never change for the lifetime of the
        // wrapper.
        val fresh = factory(primarySlotSuffix, secondarySlotSuffix)
        inner = fresh
        Log.i(
            TAG,
            "attempt #${policy.attemptCount() + 1} — primary=$primarySlotSuffix secondary=$secondarySlotSuffix",
        )

        // Step 3: connect VS1 only. Wait for READY before starting
        // VS2 — back-to-back connects in the prior model could put
        // VS2's auth on the wire BEFORE VS1's ServerSync, allowing
        // Murmur to see the two as a duplicate-username collision
        // (UsernameInUse on VS2 during the same authenticator pass
        // as VS1).
        try {
            fresh.connectPrimaryOnly(intercept)
        } catch (t: Throwable) {
            Log.e(TAG, "connectPrimaryOnly threw — treating as transient", t)
            scheduleRetry(ReconnectPolicy.Outcome.Transient)
            return
        }

        // Step 4: wait for VS1 READY. PRIMARY_READY_TIMEOUT_MS covers
        // a slow TLS handshake + auth roundtrip; on success we drop
        // straight into VS2 start. On timeout we leak forward and
        // let the inner's listener path drive recovery — it'll have
        // surfaced onError / onDisconnected by then.
        val ready = fresh.awaitPrimaryReady(PRIMARY_READY_TIMEOUT_MS)
        if (teardownRequested) return
        if (!ready) {
            Log.w(TAG, "primary not READY within ${PRIMARY_READY_TIMEOUT_MS}ms — letting listener path drive recovery")
            return
        }
        Log.i(TAG, "primary READY — starting VS2")

        // Step 5: start VS2 (no-op if config has no secondary).
        try {
            fresh.connectSecondary()
        } catch (t: Throwable) {
            Log.w(TAG, "connectSecondary threw — leaving primary running", t)
        }
    }

    private fun scheduleRetry(outcome: ReconnectPolicy.Outcome) {
        if (teardownRequested) return
        // Fatal outcomes latch fatalRejected so the follow-up Transient
        // outcome that onDisconnected fires (the read loop exits after
        // the same server-side kick) can't slip past shouldRetry and
        // reopen a session that will just get kicked again. Field bug
        // 2026-07-06: a self-kick loop hammered Murmur once a second
        // because Fatal was logged as "not retrying" but the paired
        // Transient scheduled a retry anyway. See fatalRejected doc.
        if (fatalRejected.get()) {
            Log.i(TAG, "scheduleRetry($outcome): fatal already surfaced — ignoring")
            reconnecting.set(false)
            return
        }
        // A single failed connect can produce multiple listener calls
        // (onError → onConnectionFailed AND read-loop finally →
        // onDisconnected). The single-threaded executor guarantees
        // they serialize, but we still need de-dupe semantics — the
        // second call would otherwise schedule another retry on top
        // of the first.
        if (pendingRetry?.isDone == false) {
            Log.i(TAG, "scheduleRetry($outcome): retry already scheduled — ignoring duplicate")
            return
        }
        if (!policy.shouldRetry(outcome)) {
            Log.w(TAG, "not retrying — outcome=$outcome")
            if (outcome is ReconnectPolicy.Outcome.Fatal) {
                fatalRejected.set(true)
            }
            reconnecting.set(false)
            return
        }
        // Operator switched automatic reconnect off (limited-connectivity
        // ops). Stop the background ladder — a manual reconnect / PTT
        // press via retryNow() still works and re-arms. Suspend so the UI
        // reads "disconnected" rather than a "reconnecting…" that will
        // never fire.
        if (!reconnectEnabled()) {
            Log.i(TAG, "auto-reconnect disabled by setting — suspending ladder (PTT/manual re-arms)")
            reconnectSuspended = true
            reconnecting.set(false)
            return
        }
        // Auto-pause: after a long run of continuous failures nobody is
        // benefiting from the periodic retries. Stop scheduling and wait
        // for an explicit re-arm (retryNow / connect). Complements the
        // dormant tail — the tail slows the drain, this ends it.
        if (ReconnectPolicy.shouldPause(policy.attemptCount())) {
            Log.i(
                TAG,
                "auto-pausing reconnect after ${policy.attemptCount()} attempts — " +
                    "waiting for operator (PTT/manual) to re-arm",
            )
            reconnectSuspended = true
            reconnecting.set(false)
            return
        }
        val delay = policy.nextDelayMs()
        lastScheduledDelayMs = delay
        Log.i(
            TAG,
            "scheduled reconnect attempt ${policy.attemptCount()} in ${delay}ms after $outcome",
        )
        try {
            onReconnectStateChanged(policy.attemptCount(), delay)
        } catch (t: Throwable) {
            Log.w(TAG, "onReconnectStateChanged threw", t)
        }
        pendingRetry =
            executor.schedule({
                if (!teardownRequested) runAttempt()
            }, delay, TimeUnit.MILLISECONDS)
    }

    /**
     * Ghost-cleanup ladder for VS2-only failures. Steps:
     *   1. First failure: retry in 3s with the same deterministic suffix.
     *      Most ghosts evict within Murmur's keepalive window (~30s).
     *   2. Second failure: retry in 8s with the same suffix.
     *   3. Third failure: surface as an error — slot suffixes are
     *      deterministic per (device, slot), so there's nothing to
     *      rotate to. If a real collision persists this long it's
     *      either a same-callsign-and-same-device-hash duplicate
     *      (extremely rare given 24-bit hash) or operator intervention
     *      is needed (Murmur user table cleanup).
     *
     * Runs on the wrapper's executor so it serializes with any
     * in-flight primary reconnect work.
     */
    private fun scheduleSecondaryGhostCleanup() {
        if (teardownRequested) return
        val attempt = secondaryUsernameInUseAttempts.incrementAndGet()
        when (attempt) {
            1 -> {
                Log.w(TAG, "VS2 ghost cleanup: first attempt — retry in 3s with same suffix")
                pendingRetry =
                    executor.schedule({
                        if (!teardownRequested) restartSecondary("ghost-cleanup retry #1")
                    }, 3_000L, TimeUnit.MILLISECONDS)
            }
            2 -> {
                Log.w(TAG, "VS2 ghost cleanup: second attempt — retry in 8s with same suffix")
                pendingRetry =
                    executor.schedule({
                        if (!teardownRequested) restartSecondary("ghost-cleanup retry #2")
                    }, 8_000L, TimeUnit.MILLISECONDS)
            }
            else -> {
                Log.e(
                    TAG,
                    "VS2 ghost cleanup: attempt $attempt exhausted retries — VS2 suffix " +
                        "$secondarySlotSuffix appears persistently in-use. Surfacing as " +
                        "secondary failure; primary stays up.",
                )
                // Leave VS2 down. The inner's own VS2 retry path (see
                // MumbleTransport.scheduleSecondaryRetry, 5 retries up
                // to ~62s) handles slower ghost windows on its own.
            }
        }
    }

    /**
     * Reset and restart VS2 on the EXISTING primary connection. Used
     * only by the ghost-cleanup ladder — primary stays up, only VS2
     * cycles. If primary itself has dropped, we leave the retry to
     * runAttempt() instead.
     */
    private fun restartSecondary(reason: String) {
        if (teardownRequested) return
        val current =
            inner ?: run {
                Log.w(TAG, "restartSecondary($reason): no live inner — full reconnect will handle")
                return
            }
        if (!current.isConnected) {
            Log.w(TAG, "restartSecondary($reason): primary not connected — letting full reconnect handle")
            return
        }
        Log.i(TAG, "restartSecondary: $reason — spinning VS2")
        try {
            current.connectSecondary()
        } catch (t: Throwable) {
            Log.w(TAG, "restartSecondary: connectSecondary threw", t)
        }
    }

    // Listener installed on every inner. Translates inner failures
    // into a retry decision and forwards everything else verbatim
    // to the upstream listener. All retry-scheduling work is
    // routed through the executor so the upstream's callback
    // thread (the inner's read thread) is freed quickly.
    private val intercept =
        object : TransportListener {
            override fun onConnected() {
                Log.i(TAG, "inner connected — resetting backoff")
                policy.reset()
                reconnecting.set(false)
                reconnectSuspended = false
                secondaryUsernameInUseAttempts.set(0)
                fatalRejected.set(false)
                upstream?.onConnected()
            }

            override fun onDisconnected(reason: String?) {
                upstream?.onDisconnected(reason)
                if (teardownRequested) return
                // notifyNetworkSwap drives the next attempt directly via
                // runAttempt() — skip the scheduleRetry path so the
                // disconnect-triggered retry doesn't double-up with the
                // swap-driven one.
                if (swapInProgress.getAndSet(false)) {
                    Log.i(TAG, "onDisconnected: network-swap reattempt in flight — skipping scheduleRetry")
                    return
                }
                // Plain disconnect (server closed socket, link blip,
                // ping timeout) is always transient. Fatal events
                // come through onConnectionFailed instead.
                executor.submit { scheduleRetry(ReconnectPolicy.Outcome.Transient) }
            }

            override fun onConnectionFailed(error: Throwable) {
                upstream?.onConnectionFailed(error)
                if (teardownRequested) return
                val outcome =
                    when (error) {
                        is FatalMumbleException ->
                            ReconnectPolicy.Outcome.Fatal(error.message ?: "fatal reject")
                        is SelfKickedException ->
                            ReconnectPolicy.Outcome.Fatal(error.message ?: "self-kicked")
                        is UsernameInUseException -> {
                            // Primary uses a stable device-UID-derived
                            // username (see MumbleAuth.mumbleUsername).
                            // UsernameInUse here means our prior
                            // session is still tracked server-side
                            // (Murmur hasn't detected the dead TCP
                            // yet — keepalive can take 30-60s).
                            // Backoff + retry with the SAME username;
                            // once Murmur cleans up the ghost, the
                            // retry succeeds. Rotating to a random
                            // username (old behavior) would
                            // re-introduce the very ghost-session bug
                            // we avoid by stabilizing the name.
                            Log.w(
                                TAG,
                                "primary UsernameInUse — ghost session still tracked; " +
                                    "backoff + retry with stable name",
                            )
                            ReconnectPolicy.Outcome.Transient
                        }
                        else -> ReconnectPolicy.Outcome.Transient
                    }
                executor.submit { scheduleRetry(outcome) }
            }

            override fun onSecondaryFailed(error: Throwable) {
                Log.w(TAG, "secondary failed: ${error.message}")
                if (teardownRequested) return
                // Fatal classes localized to VS2 just stop trying VS2 —
                // they don't disturb primary. Auth failure that
                // applies to BOTH identities (e.g. a server-wide ban)
                // would have surfaced on primary first anyway.
                when (error) {
                    is FatalMumbleException -> {
                        Log.w(TAG, "secondary fatal — leaving VS2 down, primary unaffected")
                        return
                    }
                    is SelfKickedException -> {
                        Log.w(TAG, "secondary self-kicked — leaving VS2 down, primary unaffected")
                        return
                    }
                    is UsernameInUseException -> {
                        executor.submit { scheduleSecondaryGhostCleanup() }
                    }
                    else -> {
                        // Other transient — re-run the cleanup ladder;
                        // it'll retry-with-backoff and eventually
                        // rotate if the failure persists.
                        executor.submit { scheduleSecondaryGhostCleanup() }
                    }
                }
            }

            override fun onVoiceFrame(frame: VoiceFrame) {
                upstream?.onVoiceFrame(frame)
            }

            override fun onPeerStartedTalking(peerId: String) {
                upstream?.onPeerStartedTalking(peerId)
            }

            override fun onPeerStoppedTalking(peerId: String) {
                upstream?.onPeerStoppedTalking(peerId)
            }
        }

    companion object {
        private const val TAG = "XvMumbleReconnect"

        // Budget for VS1 to reach ServerSync. 8s covers a slow TLS
        // handshake (multiple roundtrips, RSA validation, cert-path
        // build) on a marginal cellular link. Beyond this the
        // inner's listener path (onError / onDisconnected) is the
        // recovery driver — the wait just unblocks the executor.
        private const val PRIMARY_READY_TIMEOUT_MS: Long = 8_000L
    }
}
