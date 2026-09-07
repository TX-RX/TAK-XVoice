@file:android.annotation.SuppressLint("NewApi")

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
    // BT MAC of the operator's "Audio device" override — the explicit
    // pick from the Settings audio-device dropdown. When non-null AND
    // that device appears in [AudioManager.availableCommunicationDevices]
    // (i.e. it exposes HFP as a comm device), it wins absolutely over
    // [preferredBtMac]. Design intent: "if I set an Audio device, that's
    // where audio should go — regardless of which speakermic has the
    // button." When the override MAC is present but the device isn't a
    // valid comm device (A2DP-only headphones, out-of-range, etc.),
    // ScoLink logs a warning and falls back to [preferredBtMac].
    // Resolved on each acquire so the picker takes effect without
    // re-creating the link.
    private val outputOverrideMac: () -> String? = { null },
    // BT MAC of the operator's preferred speakermic (typically AINA),
    // resolved on each acquire so changing the AINA picker takes effect
    // without re-creating the link. Returns null if no preference is
    // pinned, in which case ScoLink falls back to "first BT_SCO device
    // available" — the legacy behavior that surfaced as the in-car
    // bug: when both AINA and a car stereo are paired, Android's
    // most-recently-connected device wins and XV's voice routes to
    // whichever the OS picked, not the operator's chosen speakermic.
    private val preferredBtMac: () -> String? = { null },
    // Human-readable display name for the audio-device override MAC.
    // Populated from [AudioRouter.availableBtOutputs] / the persisted
    // display name — never derived from the raw MAC. Injected as a
    // lookup so ScoLink doesn't need to reach across to AudioRouter
    // or the preferences DAO; VoicePlant closes the lambda over the
    // router. Returns null when the operator hasn't set an override
    // or when the device name isn't cached; the toast omits the name
    // segment in that case.
    private val overrideDisplayName: () -> String? = { null },
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
                //
                // Watch BOTH candidates: the audio-device override MAC
                // (wins when set + HFP-capable) and the AINA hint MAC.
                // Either reappearing is a reason to re-pin so we land
                // on whichever wins the current-precedence.
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
                if (state != State.CONNECTED) return
                val override = outputOverrideMac()
                val hint = preferredBtMac()
                if (override == null && hint == null) return
                val matched =
                    addedDevices.any { dev ->
                        if (dev.type != AudioDeviceInfo.TYPE_BLUETOOTH_SCO) return@any false
                        val mac = macOf(dev)
                        (override != null && mac.equals(override, ignoreCase = true)) ||
                            (hint != null && mac.equals(hint, ignoreCase = true))
                    }
                if (matched) {
                    Log.i(
                        TAG,
                        "AudioDeviceCallback: preferred BT (override or hint) just appeared — re-pinning comm device",
                    )
                    reassertCommDeviceIfNeeded()
                }
            }
        }

    /**
     * Pick the best available BT comm-device candidate, honoring the
     * "Audio device" override first and the AINA hint second. Used by
     * [startCommDevice] and [reassertCommDeviceIfNeeded] so both
     * cold-start and warm-path routing route to the operator's chosen
     * device. Production wrapper around the pure companion
     * [pickBtCommDeviceFromCandidates] — see that for the full
     * selection contract.
     *
     * When the override MAC is set but the device isn't a valid comm
     * device in the current candidate list (typical for A2DP-only
     * headphones — they don't advertise HFP and never appear in
     * [AudioManager.availableCommunicationDevices]), we log a warning
     * with the redacted MAC and fall through to the AINA hint / speaker,
     * per the "override is best-effort" design.
     */
    private fun pickBtCommDevice(candidates: List<AudioDeviceInfo>): AudioDeviceInfo? {
        val mapped = candidates.map { Candidate(it.type, macOf(it), it) }
        val override = outputOverrideMac()
        val hint = preferredBtMac()
        val result = pickBtCommDeviceWithOverride(mapped, override, hint)
        if (result.overrideMissed && override != null) {
            // Override MAC was set but the device isn't a valid comm
            // device (typical for A2DP-only headphones — they don't
            // advertise HFP and never appear in
            // [AudioManager.availableCommunicationDevices]). Log a
            // warning with the REDACTED MAC per the sensitive-content
            // rules and let the fallback (hint / speaker) stand.
            Log.w(
                TAG,
                "outputBtOverrideMac ${com.atakmap.android.xv.aina.redactMac(override)} not available " +
                    "as comm device — falling back to preferredBtMacHint / speaker",
            )
            // Try to nudge the OS into making the override the active
            // HFP device (reflection into BluetoothAdapter.setActiveDevice)
            // and, if that fails, open the system output switcher so the
            // operator can pick manually. On SUCCESS, re-read the comm
            // devices briefly — if the override now appears, pin to it
            // instead of the fallback. See [handleTargetMissed] for
            // the decision tree.
            val pinned = handleTargetMissed(override, isHint = false)
            if (pinned != null) return pinned
        } else if (result.hintMissed && override.isNullOrBlank() && !hint.isNullOrBlank()) {
            // No explicit override — Auto path. The hint (primary AINA)
            // is set but not currently a comm device. Field 2026-07-11:
            // Pixel + AINA APTT + Shokz OpenMove both HFP-connected;
            // OpenMove is the OS's active HFP and
            // [AudioManager.availableCommunicationDevices] only returns
            // OpenMove. Without a nudge, [pickBtCommDeviceFromCandidates]
            // falls through to "first BT SCO" = OpenMove and audio routes
            // to the wrong device. Same reflection → switcher fallback
            // path as the override case, just against the hint MAC and
            // with hint-side toast wording.
            Log.w(
                TAG,
                "preferredBtMacHint ${com.atakmap.android.xv.aina.redactMac(hint)} not available " +
                    "as comm device — attempting active-HFP nudge for primary speakermic",
            )
            val pinned = handleTargetMissed(hint, isHint = true)
            if (pinned != null) return pinned
        }
        return result.pick?.audioDevice as AudioDeviceInfo?
    }

    // ============================================================
    // Target-unreachable handling (feat/bt-active-device-controller +
    // feat/bt-active-device-nudge-primary-hint)
    // ============================================================
    //
    // Timestamp of the last system output-switcher launch, ms since
    // boot. Rate-limits the Intent so a stuck-out-of-range target
    // (either override or primary AINA hint) + repeated PTT presses
    // doesn't yank the system panel over ATAK every burst. The
    // cooldown is shared across both entry points — one switcher
    // launch per 30 s regardless of which target triggered it. See
    // [SWITCHER_COOLDOWN_MS].
    @Volatile
    private var lastSwitcherLaunchMs: Long = 0L

    /**
     * Called from [pickBtCommDevice] when the effective target MAC —
     * either the operator's explicit "Audio device" override, or the
     * primary AINA hint set by connectAinaInternal — is not currently a
     * comm device in [AudioManager.availableCommunicationDevices]. Wraps
     * the pure [decideOverrideAction] state machine and executes the
     * resulting action:
     *
     *   - `PIN_DIRECT` never happens here (we're already inside the
     *     "miss" branch), but the enum includes it for the outer
     *     re-poll after a successful reflection.
     *   - `TRY_REFLECTION`: call [BtActiveDeviceController.trySetActive]
     *     against [targetMac]. On SUCCESS, poll
     *     [AudioManager.availableCommunicationDevices] briefly and
     *     re-pin to [targetMac] if it now appears.
     *   - `LAUNCH_SWITCHER`: fire the media-output panel intent + a
     *     one-time toast so the operator knows what happened. Falls
     *     through to `null` — the caller keeps the hint / auto pick.
     *   - `PIN_HINT`: no-op; caller keeps the hint / auto pick. This
     *     is the silent path taken while the cooldown is running.
     *
     * @param targetMac The MAC we want to make the active HFP device.
     * @param isHint    True when [targetMac] came from the AINA hint
     *                  (Auto path). False when it came from the
     *                  explicit override. Only affects the operator-
     *                  facing toast wording — the reflection + switcher
     *                  path itself is identical.
     *
     * Returns the target AudioDeviceInfo when reflection succeeded fast
     * enough for the OS to re-enumerate — the caller pins to it
     * directly. Returns null when the caller should fall back to the
     * hint / auto chain.
     */
    private fun handleTargetMissed(
        targetMac: String,
        isHint: Boolean,
    ): AudioDeviceInfo? {
        val availableMacs =
            try {
                audioManager.availableCommunicationDevices
                    .map { macOf(it).uppercase() }
                    .toSet()
            } catch (_: Throwable) {
                emptySet()
            }
        // Pass BOTH override and hint to the pure decision so the state
        // machine sees the same view of the world as the caller. In the
        // hint-side entry [targetMac] equals `hint` and `override` is
        // null; in the override-side entry [targetMac] equals `override`
        // and `hint` is passed through for completeness. The decision
        // function resolves the effective target the same way we did.
        val override = outputOverrideMac()
        val hint = preferredBtMac()
        // Peek at our per-MAC record of the last reflection outcome. We
        // maintain [lastReflectionByMac] alongside BtActiveDeviceController's
        // own cache because [decideOverrideAction] wants a nullable
        // "never tried this session" signal — the controller's cache
        // doesn't expose null vs. cached-but-uninteresting cleanly.
        val cached = lastReflectionByMac[targetMac.uppercase()]
        val nowMs = android.os.SystemClock.elapsedRealtime()
        val action =
            decideOverrideAction(
                overrideMac = override,
                hintMac = hint,
                availableMacs = availableMacs,
                reflectionCached = cached,
                lastSwitcherLaunchMs = lastSwitcherLaunchMs,
                nowMs = nowMs,
                switcherCooldownMs = SWITCHER_COOLDOWN_MS,
            )
        Log.i(
            TAG,
            "handleTargetMissed(${com.atakmap.android.xv.aina.redactMac(targetMac)}, isHint=$isHint) → $action" +
                " (cached=${cached?.javaClass?.simpleName ?: "none"})",
        )
        return when (action) {
            OverrideRoutingAction.PIN_DIRECT -> null // structurally impossible here; caller path stands
            OverrideRoutingAction.PIN_HINT -> null
            OverrideRoutingAction.TRY_REFLECTION -> {
                val result = BtActiveDeviceController.trySetActive(context, targetMac)
                lastReflectionByMac[targetMac.uppercase()] = result
                if (result is BtActiveDeviceController.TrySetActiveResult.Success) {
                    // Wait briefly for the OS to re-enumerate. 25 ms
                    // polls × 8 = ~200 ms budget, same shape as the
                    // startCommDevice readiness poll.
                    val rePin = pollForOverrideAvailability(targetMac)
                    if (rePin != null) {
                        Log.i(
                            TAG,
                            "active HFP switched via reflection — pinning target " +
                                com.atakmap.android.xv.aina.redactMac(targetMac),
                        )
                        return rePin
                    }
                    // Reflection claimed success but the OS didn't
                    // move the device into the available list. Fall
                    // through to the switcher so the operator can
                    // complete the swap.
                    Log.i(TAG, "reflection SUCCESS but target still absent — opening switcher")
                    launchSwitcherIfPermitted(targetMac, nowMs, isHint)
                    return null
                }
                Log.d(TAG, "reflection non-success (${result.javaClass.simpleName}) — will consider switcher")
                launchSwitcherIfPermitted(targetMac, nowMs, isHint)
                null
            }
            OverrideRoutingAction.LAUNCH_SWITCHER -> {
                launchSwitcherIfPermitted(targetMac, nowMs, isHint)
                null
            }
        }
    }

    // Per-MAC cache of the last reflection result seen through this
    // ScoLink instance. Deliberately shadowed here (in addition to
    // [BtActiveDeviceController]'s own cache) so [decideOverrideAction]
    // can see a nullable "never tried in this session" signal without
    // the controller having to expose its internal map. Cleared on
    // [forceStop] (implicit — new plant → new ScoLink → new map).
    private val lastReflectionByMac: MutableMap<String, BtActiveDeviceController.TrySetActiveResult> =
        java.util.Collections.synchronizedMap(HashMap())

    private fun pollForOverrideAvailability(targetMac: String): AudioDeviceInfo? {
        val targetUpper = targetMac.uppercase()
        val deadline = System.nanoTime() + POST_SET_ACTIVE_POLL_BUDGET_MS * 1_000_000L
        while (System.nanoTime() < deadline) {
            try {
                val devs = audioManager.availableCommunicationDevices
                val hit = devs.firstOrNull { macOf(it).equals(targetUpper, ignoreCase = true) }
                if (hit != null) return hit
            } catch (_: Throwable) {
            }
            try {
                Thread.sleep(POST_SET_ACTIVE_POLL_STEP_MS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return null
            }
        }
        return null
    }

    private fun launchSwitcherIfPermitted(
        targetMac: String,
        nowMs: Long,
        isHint: Boolean,
    ) {
        if (nowMs - lastSwitcherLaunchMs < SWITCHER_COOLDOWN_MS) {
            Log.d(
                TAG,
                "switcher launch throttled — last=${nowMs - lastSwitcherLaunchMs}ms ago " +
                    "(cooldown=${SWITCHER_COOLDOWN_MS}ms)",
            )
            return
        }
        lastSwitcherLaunchMs = nowMs
        // Toast on the main thread. Uses the device's display name
        // (from AudioRouter.availableBtOutputs) rather than the raw
        // MAC per CLAUDE.md sensitive-content rules. When the name
        // isn't available (device was picked before it was ever seen
        // in the output list) we omit the device segment entirely
        // rather than fall back to the redacted MAC — a redacted MAC
        // in a Toast is worse UX than "system requires manual
        // selection" with no name.
        //
        // Wording differs slightly by target source. The override case
        // is the explicit-picker path — operator picked a device and
        // it's not reachable. The hint case is the Auto path with a
        // primary AINA — operator didn't pick anything explicitly but
        // the primary speakermic isn't where the OS is routing audio.
        val name = overrideDisplayName()
        val msg =
            if (isHint) {
                if (!name.isNullOrBlank()) {
                    "Primary speakermic ($name) not active — opening switcher to fix"
                } else {
                    "Primary speakermic (AINA) not active — opening switcher to fix"
                }
            } else {
                if (!name.isNullOrBlank()) {
                    "Route override to $name: system requires manual selection. Opening switcher…"
                } else {
                    "Route override: system requires manual selection. Opening switcher…"
                }
            }
        mainHandler.post {
            try {
                android.widget.Toast
                    .makeText(context, msg, android.widget.Toast.LENGTH_LONG)
                    .show()
            } catch (t: Throwable) {
                Log.w(TAG, "target-unreachable toast threw", t)
            }
        }
        val launched = AudioRouter.launchSystemOutputSwitcher(context)
        Log.i(
            TAG,
            "opened system output switcher for target " +
                "${com.atakmap.android.xv.aina.redactMac(targetMac)} (isHint=$isHint) launched=$launched",
        )
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
        // Compute the effective desired MAC using the same precedence
        // pickBtCommDevice() applies at cold-start: override wins when
        // it's set AND currently a comm device, otherwise fall back to
        // the hint. Reading availableCommunicationDevices here — even
        // when we might skip the re-assert below — is cheap and avoids
        // divergence between the "should we re-assert" check and the
        // actual pick.
        val overrideMac = outputOverrideMac()
        val hintMac = preferredBtMac()
        val candidatesForDesired =
            try {
                audioManager.availableCommunicationDevices
            } catch (_: Throwable) {
                emptyList()
            }
        val overrideIsValidCommDev =
            overrideMac != null &&
                candidatesForDesired.any { macOf(it).equals(overrideMac, ignoreCase = true) }
        val wantedMac = if (overrideIsValidCommDev) overrideMac else hintMac
        // Re-assert if EITHER the current comm device isn't BT, OR it
        // IS BT but doesn't match our effective MAC. The second case is
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
        val overrideMac = outputOverrideMac()
        val hintMac = preferredBtMac()
        Log.i(
            TAG,
            "available comm devices: ${candidates.size} " +
                "(override=${overrideMac ?: "<none>"} hint=${hintMac ?: "<none>"})",
        )
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
         * Outcome of the two-input selector [pickBtCommDeviceWithOverride].
         * [overrideMissed] is true when the caller passed an [overrideMac]
         * that didn't match any BT candidate — the production wrapper
         * uses this signal to emit the fall-back warning log and enter
         * the active-HFP-nudge path. [hintMissed] is true when the
         * override was null / blank AND a non-blank [hintMac] failed to
         * match any BT candidate — the extension to the nudge path
         * added 2026-07-11 for the Auto + primary-AINA + secondary-BT
         * field scenario. Both flags are mutually exclusive: if
         * [overrideMissed] fires the code never consults the hint side.
         */
        internal data class Selection(
            val pick: Candidate?,
            val overrideMissed: Boolean,
            val hintMissed: Boolean = false,
        )

        /**
         * Two-input pure selector layered over
         * [pickBtCommDeviceFromCandidates]. Precedence:
         *
         *   1. [overrideMac] wins absolutely when set AND matches a BT
         *      candidate (SCO preferred over A2DP for the same MAC).
         *   2. [overrideMac] set but not matched → fall through to the
         *      single-arg selector on [hintMac], and flag the miss so
         *      the caller can log a warning.
         *   3. [overrideMac] null/blank → single-arg selector on
         *      [hintMac]. When [hintMac] is non-blank but doesn't
         *      match any BT candidate, flag [Selection.hintMissed]
         *      so the caller can attempt the active-HFP nudge for
         *      the primary speakermic (Auto path — 2026-07-11 field
         *      extension).
         *
         * Split out from [pickBtCommDevice] so tests can pin every
         * precedence branch without standing up AudioManager. See the
         * class-level docstring for the "Audio device" precedence
         * design intent.
         */
        @androidx.annotation.VisibleForTesting
        internal fun pickBtCommDeviceWithOverride(
            candidates: List<Candidate>,
            overrideMac: String?,
            hintMac: String?,
        ): Selection {
            if (overrideMac.isNullOrBlank()) {
                val pick = pickBtCommDeviceFromCandidates(candidates, hintMac)
                // Hint-missed only fires when the operator has a hint
                // set (primary AINA MAC pushed by connectAinaInternal)
                // AND that MAC didn't match any BT candidate. Blank
                // hint = no speakermic paired at all; there's no
                // primary target to nudge toward.
                val hintMissed =
                    !hintMac.isNullOrBlank() &&
                        candidates.none {
                            it.mac.equals(hintMac, ignoreCase = true) &&
                                (
                                    it.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                                        it.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
                                    )
                        }
                return Selection(
                    pick = pick,
                    overrideMissed = false,
                    hintMissed = hintMissed,
                )
            }
            // Override set — try to match it first. Use the same
            // SCO-over-A2DP-for-same-MAC ordering as the single-arg
            // selector so an override that happens to match a device
            // exposing both profiles picks the SCO variant.
            val overrideScoMatch =
                candidates.firstOrNull {
                    it.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO &&
                        it.mac.equals(overrideMac, ignoreCase = true)
                }
            if (overrideScoMatch != null) {
                return Selection(pick = overrideScoMatch, overrideMissed = false)
            }
            val overrideA2dpMatch =
                candidates.firstOrNull {
                    it.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP &&
                        it.mac.equals(overrideMac, ignoreCase = true)
                }
            if (overrideA2dpMatch != null) {
                return Selection(pick = overrideA2dpMatch, overrideMissed = false)
            }
            // Override missed — fall back to the hint and flag it.
            return Selection(
                pick = pickBtCommDeviceFromCandidates(candidates, hintMac),
                overrideMissed = true,
            )
        }

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
        // ceiling is 60s, RX SCO_HOT is 8s, TX cool-down is 5s — so
        // anything past 90s of continuous hold means a holder isn't
        // releasing as expected. Below this, the watchdog logs at DEBUG.
        private const val WATCHDOG_WARN_THRESHOLD_MS = 90_000L

        /**
         * Cooldown between system output-switcher launches. When the
         * operator's audio-device override MAC isn't reachable, we
         * fire the switcher panel intent + a toast — but only once
         * per this window, per ScoLink lifetime. Without this, PTT
         * hammering during a stuck-out-of-range override would yank
         * the system panel over ATAK on every burst.
         *
         * 30 s is a compromise: long enough that a run of ~5 quick
         * PTT presses doesn't re-trigger, short enough that if the
         * operator dismissed the panel by accident they can PTT
         * again to get it back within the same conversation window.
         */
        internal const val SWITCHER_COOLDOWN_MS: Long = 30_000L

        /**
         * Total time budget for polling
         * [android.media.AudioManager.getAvailableCommunicationDevices]
         * after a successful [BtActiveDeviceController.trySetActive]
         * call. On Samsung / older AOSP where reflection actually
         * takes effect, the OS re-enumerates within ~50-100 ms.
         * Beyond ~200 ms we assume the platform accepted the call
         * without honoring the switch (or racing something else) and
         * fall through to the switcher fallback.
         */
        private const val POST_SET_ACTIVE_POLL_BUDGET_MS: Long = 200L

        /** Poll cadence during [POST_SET_ACTIVE_POLL_BUDGET_MS]. */
        private const val POST_SET_ACTIVE_POLL_STEP_MS: Long = 25L
    }
}
