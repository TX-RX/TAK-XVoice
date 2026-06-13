package com.atakmap.android.xv.audio

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.CopyOnWriteArraySet

// Reference-counted Bluetooth SCO link. Both RX (AudioPlayback) and TX
// (TxController) need SCO when the route is HFP-only BT. The link stays up
// while *any* holder has acquired it; it releases when the last holder
// releases. RX adds a SCO_HOT post-conversation hold window; TX holds for
// the duration of a transmission. Together they give the right behavior:
// rapid back-and-forth between RX and TX never re-establishes SCO.
//
// API 31+: AudioManager.setCommunicationDevice(AudioDeviceInfo). The SCO
// engagement is implicit — the system routes audio to the chosen comm
// device. Confirmed in ~200ms on Android 14 / Pixel 9 Pro / AINA APTT.
//
// Older API: legacy startBluetoothSco / stopBluetoothSco. XV's minSdk is
// 26 so the legacy path still has to compile.
class ScoLink(
    private val context: Context,
    // BT MAC of the operator's preferred speakermic (typically AINA),
    // resolved on each acquire so changing the AINA picker takes effect
    // without re-creating the link. Returns null if no preference is
    // pinned, in which case ScoLink falls back to "first BT_SCO device
    // available" — the legacy behavior that surfaced as the in-car
    // bug: when both AINA and a car stereo are paired, Android's
    // most-recently-connected device wins and XV's voice routes to
    // whichever the OS picked, not the operator's chosen speakermic.
    private val preferredBtMac: () -> String? = { null },
) {
    // SUSPENDED is a transient broken state inserted between CONNECTED
    // and DISCONNECTED. It indicates the physical SCO link was torn
    // down by a system event (incoming phone call, alarm, navigation
    // prompt) while we still have logical holders. Holders should
    // either retry-acquire (driving the link back through CONNECTING)
    // or release. The internal cleanup of the SCO surface has already
    // happened by the time SUSPENDED is published; the next acquire()
    // restarts from scratch.
    enum class State { DISCONNECTED, CONNECTING, CONNECTED, SUSPENDED }

    /** Pure type + MAC carrier for [pickBtCommDeviceFromCandidates]. The
     *  [audioDevice] payload is opaque (production passes AudioDeviceInfo,
     *  tests pass null) so the pure selector doesn't touch any Android
     *  runtime. */
    internal data class Candidate(
        val type: Int,
        val mac: String,
        val audioDevice: Any? = null,
    )

    interface StateListener {
        fun onScoStateChanged(state: State) {}
    }

    private val audioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    var state: State = State.DISCONNECTED
        private set

    private val holders = mutableSetOf<Any>()
    private val stateListeners = CopyOnWriteArraySet<StateListener>()

    // OS-level visibility into BT SCO availability. Without this we
    // only know about SCO state changes we initiated — when the
    // operator powers off the speakermic mid-burst, our internal
    // state still says CONNECTED and TxController/AudioPlayback keep
    // pumping audio into a dead route until the next user-driven
    // teardown. The callback fires onAudioDevicesRemoved with the
    // SCO device, we synthesize a teardown so listeners react cleanly.
    private val deviceCallback =
        object : AudioDeviceCallback() {
            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
                val scoGone = removedDevices.any { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
                if (!scoGone) return
                if (state == State.DISCONNECTED) return
                Log.w(TAG, "AudioDeviceCallback: BT_SCO removed while state=$state — forcing teardown")
                synchronized(this@ScoLink) {
                    if (state == State.DISCONNECTED) return@synchronized
                    cleanup()
                    holders.clear()
                    disarmWatchdog()
                    transitionTo(State.DISCONNECTED)
                }
            }

            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
                // AINA-pinning hardening: when our preferred speakermic
                // re-appears (operator turned it on, walked back into
                // range, or just got in the car AFTER pairing the AINA
                // earlier), re-pin the comm device immediately. Without
                // this, Android's "most recently connected wins" leaves
                // XV's voice on whichever BT device connected last —
                // typically the car stereo when the operator gets in
                // the car with both paired.
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
                if (state != State.CONNECTED) return
                val wantedMac = preferredBtMac() ?: return
                val matched =
                    addedDevices.any {
                        it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO &&
                            macOf(it).equals(wantedMac, ignoreCase = true)
                    }
                if (matched) {
                    Log.i(
                        TAG,
                        "AudioDeviceCallback: preferred BT $wantedMac just appeared — re-pinning comm device",
                    )
                    reassertCommDeviceIfNeeded()
                }
            }
        }

    /**
     * Pick the best available BT comm-device candidate, preferring the
     * operator's pinned MAC over the OS's most-recently-connected
     * default. Used by [startCommDevice] and
     * [reassertCommDeviceIfNeeded] so both cold-start and warm-path
     * routing honor the AINA pin. Production wrapper around the pure
     * companion [pickBtCommDeviceFromCandidates] — see that for the
     * full selection contract.
     */
    private fun pickBtCommDevice(candidates: List<AudioDeviceInfo>): AudioDeviceInfo? {
        val mapped = candidates.map { Candidate(it.type, macOf(it), it) }
        return pickBtCommDeviceFromCandidates(mapped, preferredBtMac())?.audioDevice as AudioDeviceInfo?
    }

    /**
     * Best-effort BT MAC extraction from an AudioDeviceInfo. The
     * `address` field is the MAC string for BT devices on every
     * Android version we support, but Android occasionally hides it
     * for privacy on non-system apps — fall back to empty if we can't
     * read it.
     */
    private fun macOf(d: AudioDeviceInfo): String =
        try {
            d.address ?: ""
        } catch (_: Throwable) {
            ""
        }

    init {
        try {
            audioManager.registerAudioDeviceCallback(deviceCallback, mainHandler)
        } catch (t: Throwable) {
            Log.w(TAG, "registerAudioDeviceCallback failed", t)
        }
    }

    @Volatile
    private var savedMode: Int = AudioManager.MODE_NORMAL

    @Volatile
    private var pollRunnable: Runnable? = null

    fun addStateListener(l: StateListener) {
        stateListeners.add(l)
        // Immediately surface current state so a late subscriber knows
        // whether SCO is already up. We deliberately suppress the
        // immediate-fire when state is DISCONNECTED — that's the
        // default and conveys no useful info, and subscribers
        // (AudioPlayback in PENDING_SCO, TxController in ACQUIRING_SCO)
        // interpret an unsolicited DISCONNECTED as "the link I was
        // counting on dropped, abandon ship," which would tear down
        // their just-set-up session before acquire() has even started
        // the link.
        if (state != State.DISCONNECTED) {
            l.onScoStateChanged(state)
        }
    }

    fun removeStateListener(l: StateListener) {
        stateListeners.remove(l)
    }

    /** Returns the current number of acquire-holders. Used by callers
     *  that need to know "is anyone keeping SCO alive right now" without
     *  themselves becoming holders (e.g. AudioControllerImpl deciding
     *  whether to enable speakerphone for the no-BT case). */
    @Synchronized
    fun holdersCount(): Int = holders.size

    @Synchronized
    fun acquire(holder: Any) {
        val wasEmpty = holders.isEmpty()
        if (!holders.add(holder)) {
            Log.i(TAG, "acquire($holder) — already held by this holder")
            return
        }
        Log.i(TAG, "acquire($holder); holders=${holders.size} state=$state")
        if (wasEmpty) {
            armWatchdog()
            if (state == State.DISCONNECTED || state == State.SUSPENDED) {
                // SUSPENDED = system tore down our link (phone call,
                // alarm, etc) but holders may still want it back. Treat
                // exactly like DISCONNECTED: drive a fresh CONNECTING
                // cycle.
                beginConnect()
                return
            }
        } else if (state == State.SUSPENDED) {
            // Non-empty holder set entering on top of a SUSPENDED link
            // (i.e. the surviving holders chose to retry). Restart the
            // physical link.
            beginConnect()
            return
        }
        // Warm-path acquire on an already-CONNECTED link. The system's
        // AudioPolicyManager can lose our communication-device selection
        // between bursts (typically when AudioPlayback's AudioTrack is
        // released entering SCO_HOT — the policy decides "no active
        // voice stream needs this routing" and clears the comm device
        // back to the default earpiece). The next AudioTrack built
        // with USAGE_VOICE_COMMUNICATION then routes to the phone
        // earpiece instead of the AINA, so the operator hears nothing
        // useful (TPT, peer voice resume). Re-assert here so every
        // fresh holder lands on a freshly-routed SCO.
        if (state == State.CONNECTED && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            reassertCommDeviceIfNeeded()
        }
    }

    private fun reassertCommDeviceIfNeeded() {
        val current =
            try {
                audioManager.communicationDevice
            } catch (_: Throwable) {
                null
            }
        val wantedMac = preferredBtMac()
        // Re-assert if EITHER the current comm device isn't BT, OR it
        // IS BT but doesn't match our pinned MAC. The second case is
        // the in-car bug: AINA was active, then car stereo connected
        // and Android switched the comm device to the car. Without
        // the MAC check we'd see "current is BT_SCO" and skip the
        // re-pin, leaving voice routed to the car.
        val currentMac = current?.let { macOf(it) }.orEmpty()
        val matchesWanted =
            wantedMac == null ||
                (
                    current?.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO &&
                        currentMac.equals(wantedMac, ignoreCase = true)
                    )
        if (current?.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO && matchesWanted) return
        Log.i(
            TAG,
            "reassertCommDevice: current commDev is ${current?.productName}/type=${current?.type}/mac=$currentMac" +
                (wantedMac?.let { " wanted=$it" } ?: " (no preference)") +
                " — re-asserting",
        )
        val candidates =
            try {
                audioManager.availableCommunicationDevices
            } catch (_: Throwable) {
                emptyList()
            }
        val sco = pickBtCommDevice(candidates)
        if (sco == null) {
            Log.w(TAG, "reassertCommDevice: no BT comm device candidate available")
            return
        }
        try {
            val ok = audioManager.setCommunicationDevice(sco)
            Log.i(TAG, "reassertCommDevice → ${sco.productName}/mac=${macOf(sco)}/type=${sco.type} ok=$ok")
        } catch (t: Throwable) {
            Log.w(TAG, "reassertCommDevice threw", t)
        }
    }

    /**
     * Notification that the system has yanked our SCO link out from
     * under us (audio focus loss to incoming phone call, alarm, nav
     * prompt, etc). Cleans up internal SCO + comm-device references
     * and publishes [State.SUSPENDED] to listeners. Holders are
     * preserved — the link state machine is reset, but the logical
     * holder set survives so [acquire] can restart the link from
     * scratch when it's safe to come back. No-op if we were already
     * DISCONNECTED.
     *
     * Intentionally does NOT clear [holders]. Holders that observe
     * SUSPENDED should choose: either re-acquire (which drives the
     * link back through CONNECTING) or release.
     */
    @Synchronized
    fun handleSystemSuspend() {
        if (state == State.DISCONNECTED) {
            Log.i(TAG, "handleSystemSuspend: already DISCONNECTED — no-op")
            return
        }
        Log.w(TAG, "handleSystemSuspend: tearing down SCO surface (state=$state, holders=${holders.size})")
        cleanup()
        disarmWatchdog()
        // Don't touch holders — surviving holders observe SUSPENDED and
        // choose retry or release. Watchdog re-arms the moment a holder
        // re-acquires.
        transitionTo(State.SUSPENDED)
    }

    @Synchronized
    fun release(holder: Any) {
        if (!holders.remove(holder)) {
            Log.i(TAG, "release($holder) — not held")
            return
        }
        Log.i(TAG, "release($holder); holders=${holders.size}")
        if (holders.isEmpty()) {
            disarmWatchdog()
            beginDisconnect()
        }
    }

    // Watchdog. Logs every WATCHDOG_INTERVAL_MS while ANY holder
    // exists. Lets us spot stuck-SCO incidents (like the AINA's
    // purple LED staying lit past the 5s cool-down) without needing
    // the user to grab logs at the right moment — the log will list
    // the surviving holders so we know whether AudioPlayback,
    // TxController, or both are responsible. No autotearing; just
    // visibility.
    private fun armWatchdog() {
        disarmWatchdog()
        watchdogStartMs = System.currentTimeMillis()
        watchdogRunnable = Runnable { logHolders() }
        mainHandler.postDelayed(watchdogRunnable!!, WATCHDOG_INTERVAL_MS)
    }

    private fun disarmWatchdog() {
        watchdogRunnable?.let { mainHandler.removeCallbacks(it) }
        watchdogRunnable = null
        watchdogStartMs = 0L
    }

    private fun logHolders() {
        synchronized(this) {
            val held = holders.size
            if (held == 0) return
            val ageMs = System.currentTimeMillis() - watchdogStartMs
            // Below WATCHDOG_WARN_THRESHOLD_MS, SCO is legitimately
            // warm (Hot Mic mode, rapid PTT cycling, RX SCO_HOT). Log
            // at DEBUG so the trace is still there for diagnosis but
            // doesn't clutter WARN. Above the threshold, something is
            // leaking — promote to WARN.
            val msg =
                "WATCHDOG: SCO held for ${ageMs}ms by $held holder(s): " +
                    holders.joinToString { it.javaClass.simpleName + "@" + Integer.toHexString(System.identityHashCode(it)) }
            if (ageMs >= WATCHDOG_WARN_THRESHOLD_MS) {
                Log.w(TAG, msg)
            } else {
                Log.d(TAG, msg)
            }
            // Re-arm — keep periodic visibility while held.
            watchdogRunnable = Runnable { logHolders() }
            mainHandler.postDelayed(watchdogRunnable!!, WATCHDOG_INTERVAL_MS)
        }
    }

    @Volatile
    private var watchdogStartMs: Long = 0L

    @Volatile
    private var watchdogRunnable: Runnable? = null

    private fun beginConnect() {
        try {
            savedMode = audioManager.mode
        } catch (t: Throwable) {
            Log.w(TAG, "read mode failed", t)
        }
        try {
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            Log.i(TAG, "mode set to IN_COMMUNICATION (savedMode=$savedMode)")
        } catch (t: Throwable) {
            // Plugin context can't always set MODE_IN_COMMUNICATION (NPE on
            // getOpPackageName). setCommunicationDevice doesn't strictly need
            // it on API 31+, so swallow and continue.
            Log.w(TAG, "setMode IN_COMMUNICATION failed", t)
        }
        transitionTo(State.CONNECTING)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            startCommDevice()
        } else {
            startLegacySco()
        }
    }

    private fun beginDisconnect() {
        Log.i(TAG, "beginDisconnect (state=$state)")
        cleanup()
        transitionTo(State.DISCONNECTED)
    }

    private fun startCommDevice() {
        val candidates =
            try {
                audioManager.availableCommunicationDevices
            } catch (t: Throwable) {
                Log.w(TAG, "availableCommunicationDevices failed", t)
                emptyList()
            }
        val wantedMac = preferredBtMac()
        Log.i(TAG, "available comm devices: ${candidates.size} (preferredMac=${wantedMac ?: "<none>"})")
        for (d in candidates) {
            Log.i(TAG, "  candidate type=${d.type} name='${d.productName}' mac='${macOf(d)}'")
        }
        val sco = pickBtCommDevice(candidates)
        if (sco == null) {
            Log.w(TAG, "no BT comm device available — bailing")
            beginDisconnect()
            return
        }
        Log.i(TAG, "setCommunicationDevice → ${sco.productName}/mac=${macOf(sco)}/type=${sco.type}")
        val ok =
            try {
                audioManager.setCommunicationDevice(sco)
            } catch (t: Throwable) {
                Log.e(TAG, "setCommunicationDevice threw", t)
                false
            }
        if (!ok) {
            Log.w(TAG, "setCommunicationDevice returned false")
            beginDisconnect()
            return
        }
        scheduleReadinessCheck(attempt = 0)
    }

    private fun scheduleReadinessCheck(attempt: Int) {
        val r =
            Runnable {
                if (state != State.CONNECTING) return@Runnable
                val ready =
                    try {
                        val current = audioManager.communicationDevice
                        current?.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                            current?.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
                    } catch (_: Throwable) {
                        false
                    }
                if (ready) {
                    Log.i(TAG, "SCO comm device confirmed (attempt=$attempt)")
                    transitionTo(State.CONNECTED)
                } else if (attempt >= MAX_READINESS_ATTEMPTS) {
                    Log.w(TAG, "SCO comm device never confirmed after $attempt attempts")
                    beginDisconnect()
                } else {
                    scheduleReadinessCheck(attempt + 1)
                }
            }
        pollRunnable = r
        mainHandler.postDelayed(r, READINESS_POLL_MS)
    }

    @Suppress("DEPRECATION")
    private fun startLegacySco() {
        Log.i(TAG, "legacy startBluetoothSco")
        try {
            audioManager.startBluetoothSco()
        } catch (t: Throwable) {
            Log.e(TAG, "startBluetoothSco threw", t)
            beginDisconnect()
            return
        }
        scheduleReadinessCheck(attempt = 0)
    }

    private fun cleanup() {
        pollRunnable?.let { mainHandler.removeCallbacks(it) }
        pollRunnable = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                audioManager.clearCommunicationDevice()
            } catch (t: Throwable) {
                Log.w(TAG, "clearCommunicationDevice threw", t)
            }
        } else {
            try {
                @Suppress("DEPRECATION")
                audioManager.stopBluetoothSco()
            } catch (_: Throwable) {
            }
        }
        try {
            audioManager.mode = savedMode
        } catch (t: Throwable) {
            Log.w(TAG, "restore mode failed", t)
        }
    }

    private fun transitionTo(next: State) {
        if (state == next) return
        Log.i(TAG, "state: $state → $next")
        state = next
        for (l in stateListeners) {
            try {
                l.onScoStateChanged(next)
            } catch (t: Throwable) {
                Log.w(TAG, "listener threw", t)
            }
        }
    }

    /**
     * Force teardown regardless of holders. For shutdown only.
     */
    fun forceStop() {
        try {
            audioManager.unregisterAudioDeviceCallback(deviceCallback)
        } catch (t: Throwable) {
            Log.w(TAG, "unregisterAudioDeviceCallback failed", t)
        }
        synchronized(this) {
            holders.clear()
            beginDisconnect()
        }
    }

    companion object {
        private const val TAG = "XvSco"

        /**
         * Pure-function selector — picks the best comm-device candidate
         * from a typed (type, mac) list, honoring an optional pinned
         * MAC override. Order of preference:
         *
         *   1. SCO endpoint matching pinned MAC (exact, case-insensitive)
         *   2. A2DP endpoint matching pinned MAC (fallback for SCO-less
         *      pair on the same device)
         *   3. Pinned MAC missed entirely or null → first SCO endpoint
         *      in the list, then first A2DP endpoint
         *
         * Pure so [ScoLinkPickerTest] can pin every branch without
         * standing up AudioManager. Production [pickBtCommDevice]
         * wraps this with the AudioDeviceInfo → Candidate adapter.
         */
        @androidx.annotation.VisibleForTesting
        internal fun pickBtCommDeviceFromCandidates(
            candidates: List<Candidate>,
            preferredMac: String?,
        ): Candidate? {
            if (!preferredMac.isNullOrBlank()) {
                candidates
                    .firstOrNull {
                        it.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO &&
                            it.mac.equals(preferredMac, ignoreCase = true)
                    }?.let { return it }
                candidates
                    .firstOrNull {
                        it.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP &&
                            it.mac.equals(preferredMac, ignoreCase = true)
                    }?.let { return it }
            }
            return candidates.firstOrNull { it.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
                ?: candidates.firstOrNull { it.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP }
        }

        // 25 ms cadence so a Pixel 9 Pro + AINA V2 cold acquire (~200 ms
        // observed settle) lands within the first 8-9 polls instead of
        // rounding up to the next 100 ms boundary. The poll itself is a
        // cheap AudioManager property read; the cadence is purely about
        // rounding error against the chipset's settle time. Total timeout
        // window held at ~3 s by bumping MAX_READINESS_ATTEMPTS in step.
        private const val READINESS_POLL_MS = 25L
        private const val MAX_READINESS_ATTEMPTS = 120 // ~3 seconds total

        // Watchdog cadence — picks up stuck-SCO incidents within 10s.
        // Long enough that healthy bursts (1-2s) don't generate a
        // log line; short enough that operator complaints about
        // "purple LED stayed on for a while" land in the next pull.
        private const val WATCHDOG_INTERVAL_MS = 10_000L

        // Threshold at which a held SCO link is no longer "legitimately
        // warm" but instead probably leaking. Hot Mic's idle release
        // ceiling is 60s, RX SCO_HOT is 5s, TX cool-down is 5s — so
        // anything past 90s of continuous hold means a holder isn't
        // releasing as expected. Below this, the watchdog logs at DEBUG.
        private const val WATCHDOG_WARN_THRESHOLD_MS = 90_000L
    }
}
