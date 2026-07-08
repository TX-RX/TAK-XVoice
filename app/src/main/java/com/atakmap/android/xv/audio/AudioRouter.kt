package com.atakmap.android.xv.audio

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.util.Log

// Picks an output AudioDeviceInfo for AudioPlayback's AudioTrack. Default
// priority (when route preference is AUTO):
//   1. Bluetooth — A2DP or SCO, whichever is connected. The whole point
//      of pairing a speakermic / headset with the device is so audio
//      goes there.
//   2. Wired — headset/headphones/USB-headset. Operator plugged it in,
//      they want it (overrides any saved internal-route preference).
//   3. Built-in speaker.
//   4. Earpiece — last resort, when nothing else is available.
//
// Explicit user preference (SPEAKER / EARPIECE) only differentiates the
// two internal devices when no external device is present.
//
// Reactive: registers an AudioDeviceCallback so RouteListener gets notified
// when devices come and go. AudioPlayback re-applies setPreferredDevice on
// its existing AudioTrack so a BT headset that pairs mid-call gets the
// stream without a teardown.
class AudioRouter(
    private val context: Context,
    private val preference: AudioRoutePreference = AudioRoutePreference(context),
) {
    interface RouteListener {
        fun onPreferredDeviceChanged(device: AudioDeviceInfo?)
    }

    private val audioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val mainHandler = Handler(Looper.getMainLooper())

    private val listeners = java.util.concurrent.CopyOnWriteArrayList<RouteListener>()

    // Set true while TX is in flight (set by TxController via the
    // injected provider). When true, hot-attach route changes are
    // queued instead of fanned out — swapping the comm device under
    // a live AudioRecord/AudioTrack pipeline causes audible glitches
    // and sometimes torn-down PCM frames. The deferred change applies
    // when TxController returns to IDLE (it calls flushPendingRouteChange
    // via its onIdle hook).
    //
    // Defaults to "always idle" so route changes apply immediately
    // unless someone wires this up. Tests + code paths that don't
    // care about TX state stay simple.
    @Volatile
    var txActiveProvider: () -> Boolean = { false }

    @Volatile
    private var pendingRouteChange: Boolean = false

    private val deviceCallback =
        object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
                logDevices("added", addedDevices)
                reevaluateBtHintAvailability()
                notifyChange()
            }

            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
                logDevices("removed", removedDevices)
                reevaluateBtHintAvailability()
                notifyChange()
            }
        }

    fun start(listener: RouteListener) {
        addListener(listener)
        audioManager.registerAudioDeviceCallback(deviceCallback, mainHandler)
        registerBecomingNoisyReceiver()
    }

    fun addListener(listener: RouteListener) {
        listeners.addIfAbsent(listener)
    }

    fun removeListener(listener: RouteListener) {
        listeners.remove(listener)
    }

    fun stop() {
        audioManager.unregisterAudioDeviceCallback(deviceCallback)
        unregisterBecomingNoisyReceiver()
        // Cancel any armed speaker-fallback runnable so it doesn't
        // fire after we've torn down (listeners.clear below would
        // make the fan-out a no-op, but the log noise and the flag
        // state would still linger for the next start()).
        clearSpeakerFallback("router stopped")
        listeners.clear()
    }

    // ACTION_AUDIO_BECOMING_NOISY fires when a previously-loud route
    // (BT headset, wired headphones) is going away and audio is about
    // to fall to the loudspeaker. Standard media apps pause when they
    // see it; for a PTT app the right response is to immediately
    // re-evaluate the preferred output so the next frame (peer voice,
    // tone) goes to the new route instead of blasting on the
    // loudspeaker through whoever's in the room.
    //
    // Field shape: operator with wired headphones is monitoring a
    // mission. They unplug the headphones to switch to a different
    // device — without this hook, the next voice frame plays on the
    // phone speaker until something else (a UI route preference
    // change, a hot-attach BT add) triggers notifyChange. Audit H3.
    private val becomingNoisyReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(
                ctx: Context?,
                intent: Intent?,
            ) {
                if (intent?.action != AudioManager.ACTION_AUDIO_BECOMING_NOISY) return
                Log.i(TAG, "ACTION_AUDIO_BECOMING_NOISY — wired/BT output removed, re-evaluating route")
                notifyChangeFromOperator()
            }
        }

    @Volatile
    private var becomingNoisyRegistered: Boolean = false

    private fun registerBecomingNoisyReceiver() {
        if (becomingNoisyRegistered) return
        try {
            val filter = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(becomingNoisyReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                context.registerReceiver(becomingNoisyReceiver, filter)
            }
            becomingNoisyRegistered = true
        } catch (t: Throwable) {
            Log.w(TAG, "BECOMING_NOISY receiver registration failed — auto-reroute on unplug disabled", t)
        }
    }

    private fun unregisterBecomingNoisyReceiver() {
        if (!becomingNoisyRegistered) return
        try {
            context.unregisterReceiver(becomingNoisyReceiver)
        } catch (_: Throwable) {
        }
        becomingNoisyRegistered = false
    }

    var route: OutputRoute
        get() = preference.route
        set(value) {
            val old = preference.route
            preference.route = value
            if (old != value) {
                Log.i(TAG, "user route preference: $old -> $value")
                notifyChangeFromOperator()
            }
        }

    /**
     * Set the route preference quietly (no listener fan-out). Callers
     * that need to update the comm device BEFORE the listener fan-out
     * (so freshly-rebuilt AudioTracks pick up the new comm device) use
     * this, do the comm-device work, then call [notifyOperatorChange]
     * to trigger the listener fan-out.
     */
    fun setRoutePreferenceQuiet(value: OutputRoute): Boolean {
        val old = preference.route
        if (old == value) return false
        preference.route = value
        Log.i(TAG, "user route preference (quiet): $old -> $value")
        return true
    }

    /** Trigger an operator-driven listener fan-out (used after
     *  [setRoutePreferenceQuiet] + comm-device update). */
    fun notifyOperatorChange() {
        notifyChangeFromOperator()
    }

    // In-memory hint about which BT device the user picked as their
    // PTT input source (the AINA picker selection). Used to break ties
    // when multiple BT outputs are connected — without this, "first
    // BT wins" is non-deterministic and routing flips between bursts
    // when AINA + AirPods are both up.
    //
    // Order of precedence in preferredDevice():
    //   1. Explicit BT override (operator picked a specific BT in
    //      "Bluetooth audio override")
    //   2. preferredBtMacHint (the AINA picker selection — implicit
    //      default)
    //   3. First BT (fallback when neither is set)
    //
    // Not persisted here — VoicePlant pushes the value on connectAina
    // and clears it on disconnectAina. Persistence of the AINA pick
    // itself lives in XvMapComponent's SharedPreferences.
    @Volatile
    var preferredBtMacHint: String? = null
        set(value) {
            val old = field
            field = value
            if (old != value) {
                Log.i(TAG, "preferredBtMacHint: $old -> $value")
                // Hint changed — reset the speaker-fallback timer.
                // Setting the hint to a new device gives the device a
                // fresh grace window to appear before we fall back;
                // clearing the hint (voluntary disconnect) always
                // exits fallback mode.
                clearSpeakerFallback("hint changed to $value")
                reevaluateBtHintAvailability()
                notifyChangeFromOperator()
            }
        }

    // ============================================================
    // Fallback-to-speaker lifecycle (feat/bt-device-startup-ux)
    // ============================================================
    //
    // Field 2026-07-08: operators reported that when a picked BT
    // speakermic disconnects mid-session (battery, out of range, puck
    // gets bumped off) the plugin sits mute — nothing routes audio to
    // the phone speaker as a graceful fallback. The user's preferred
    // MAC is preserved (correct — they still want that puck when it
    // comes back), but silence is not a useful state during a live
    // session.
    //
    // This block adds a grace-period timer: while a BT hint is set
    // AND that MAC is NOT present in the audio-device output list,
    // start a countdown. If the countdown elapses without the device
    // reappearing, flip [speakerFallbackActive] and re-notify listeners
    // — [preferredDevice] then routes to the internal speaker while
    // keeping the hint (and the operator's persisted AINA MAC) intact.
    // When the device reconnects, we clear the fallback and audio
    // routes back to BT on the next fan-out.
    //
    // Constant is public so tests / operator docs can reference the
    // grace window we chose.
    @Volatile
    private var speakerFallbackActive: Boolean = false

    @Volatile
    private var pendingSpeakerFallback: Runnable? = null

    /**
     * True while the AudioRouter has fallen back to the built-in
     * speaker because the operator's preferred BT device stayed
     * unavailable for [BT_UNAVAILABLE_SPEAKER_FALLBACK_MS]. Cleared
     * automatically when the device reconnects.
     */
    fun isSpeakerFallbackActive(): Boolean = speakerFallbackActive

    /**
     * Arms or disarms the speaker-fallback timer based on whether the
     * currently-set BT hint is present in the AudioManager output list.
     * Called from the AudioDeviceCallback edges and from the setter
     * for [preferredBtMacHint]. Cheap — one enumeration of outputs.
     */
    private fun reevaluateBtHintAvailability() {
        val hint = preferredBtMacHint ?: run {
            // No BT hint set — no notion of "our preferred device is
            // missing." Cancel any pending fallback + reset the
            // active flag so we don't linger in fallback mode
            // indefinitely after the operator picks "(none)".
            clearSpeakerFallback("hint is null")
            return
        }
        val outputs =
            try {
                audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            } catch (t: Throwable) {
                Log.w(TAG, "reevaluateBtHintAvailability: getDevices threw", t)
                return
            }
        val present = outputs.any { it.isBluetooth() && it.address == hint }
        if (present) {
            // The device just came back (or was already there and this
            // is a spurious re-eval). Cancel any armed fallback timer
            // and, if we were in fallback mode, clear it — [notifyChange]
            // called by the AudioDeviceCallback will fan out and let
            // AudioPlayback rebuild against the BT device.
            clearSpeakerFallback("hint $hint is reachable")
        } else if (pendingSpeakerFallback == null && !speakerFallbackActive) {
            // Device is absent and we haven't already committed to
            // fallback. Arm the timer.
            armSpeakerFallback(hint)
        }
    }

    private fun armSpeakerFallback(hint: String) {
        Log.i(
            TAG,
            "arming speaker-fallback timer — hint=$hint absent, will fire in " +
                "${BT_UNAVAILABLE_SPEAKER_FALLBACK_MS / 1000}s if not restored",
        )
        val runnable =
            Runnable {
                pendingSpeakerFallback = null
                if (speakerFallbackActive) return@Runnable
                val currentHint = preferredBtMacHint
                if (currentHint == null) {
                    Log.i(TAG, "speaker-fallback fired but hint cleared — skipping")
                    return@Runnable
                }
                val stillMissing =
                    try {
                        audioManager
                            .getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                            .none { it.isBluetooth() && it.address == currentHint }
                    } catch (t: Throwable) {
                        Log.w(TAG, "speaker-fallback re-check threw", t)
                        false
                    }
                if (!stillMissing) {
                    Log.i(TAG, "speaker-fallback fired but hint $currentHint reappeared — skipping")
                    return@Runnable
                }
                Log.w(
                    TAG,
                    "speaker-fallback firing — hint=$currentHint has been unreachable " +
                        "for ${BT_UNAVAILABLE_SPEAKER_FALLBACK_MS / 1000}s; falling back to " +
                        "phone speaker (operator MAC preserved for re-connect)",
                )
                speakerFallbackActive = true
                // Fan out so AudioPlayback / VoicePlant re-pick the
                // route immediately — [preferredDevice] now returns
                // the internal speaker regardless of the hint.
                notifyChangeFromOperator()
            }
        pendingSpeakerFallback = runnable
        mainHandler.postDelayed(runnable, BT_UNAVAILABLE_SPEAKER_FALLBACK_MS)
    }

    private fun clearSpeakerFallback(reason: String) {
        val hadPending = pendingSpeakerFallback != null
        pendingSpeakerFallback?.let { mainHandler.removeCallbacks(it) }
        pendingSpeakerFallback = null
        if (speakerFallbackActive) {
            Log.i(TAG, "clearing speaker-fallback — reason=$reason")
            speakerFallbackActive = false
            notifyChangeFromOperator()
        } else if (hadPending) {
            Log.i(TAG, "cancelling pending speaker-fallback — reason=$reason")
        }
    }

    // Optional override: a BT MAC address. When set AND that device is
    // currently connected, [preferredDevice] returns it instead of
    // applying the regular priority chain. When set but the device
    // isn't connected, falls through to the regular chain (AINA HFP /
    // built-in route per [route]). null disables the override.
    var outputBtOverrideMac: String?
        get() = preference.outputBtOverrideMac
        set(value) {
            val old = preference.outputBtOverrideMac
            preference.outputBtOverrideMac = value
            if (old != value) {
                Log.i(TAG, "user BT output override: $old -> $value")
                notifyChangeFromOperator()
            }
        }

    // Snapshot of currently-connected BT audio output devices, listed
    // by name + MAC. Used by the UI picker to populate the override
    // dropdown. Refreshed on every call (source of truth: AudioManager
    // device enumeration).
    fun availableBtOutputs(): List<BtOutput> =
        audioManager
            .getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .filter { it.isBluetooth() }
            .mapNotNull { dev ->
                val mac = dev.address ?: return@mapNotNull null
                if (mac.isBlank()) return@mapNotNull null
                BtOutput(
                    address = mac,
                    displayName =
                    dev.productName
                        ?.toString()
                        .orEmpty()
                        .ifBlank { mac },
                    type = dev.type,
                )
            }.distinctBy { it.address }

    data class BtOutput(
        val address: String,
        val displayName: String,
        val type: Int,
    )

    // Returns the AudioDeviceInfo to pass to AudioTrack.setPreferredDevice.
    // Order: BT > wired > speaker > earpiece. Explicit EARPIECE
    // preference is the only way to bypass speaker; everything else
    // routes to the loudest available appropriate device. Returning null
    // means we couldn't find any device at all (shouldn't happen on a
    // phone) — Android will pick.
    fun preferredDevice(): AudioDeviceInfo? {
        val outputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)

        // -1) Speaker fallback (feat/bt-device-startup-ux). When the
        //     operator's preferred BT device has been unreachable for
        //     [BT_UNAVAILABLE_SPEAKER_FALLBACK_MS] we deliberately
        //     skip the BT / wired chain and route to the phone
        //     speaker. Cleared automatically when the device
        //     reconnects (see [reevaluateBtHintAvailability]).
        if (speakerFallbackActive) {
            val speaker = outputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
            if (speaker != null) {
                Log.i(TAG, "preferred: ${describe(speaker)} (speaker fallback active)")
                return speaker
            }
            Log.w(TAG, "speaker fallback active but no BUILTIN_SPEAKER output present — dropping through")
        }

        // 0) Explicit BT override — operator picked a specific BT
        //    audio device (e.g. car BT, headphones) that should win
        //    over both auto-priority AND any other connected BT.
        //    When the chosen device isn't currently present we fall
        //    through to the regular chain — preference is preserved
        //    in prefs so when the device returns it auto-resumes.
        val overrideMac = preference.outputBtOverrideMac
        if (overrideMac != null) {
            val pinned = outputs.firstOrNull { it.isBluetooth() && it.address == overrideMac }
            if (pinned != null) {
                Log.i(TAG, "preferred: ${describe(pinned)} (BT override $overrideMac)")
                return pinned
            }
            Log.i(TAG, "BT override $overrideMac not currently connected — falling back to priority chain")
        }

        // 1) Bluetooth — the user's intent when they pair a speakermic.
        //    When the operator has selected an AINA in the picker, prefer
        //    THAT specific BT device over any other BT output (e.g. AINA
        //    + AirPods both connected — first-found-wins would route
        //    non-deterministically). Fall back to first BT if the hint
        //    isn't set or its device isn't currently present.
        val btOutputs = outputs.filter { it.isBluetooth() }
        if (btOutputs.isNotEmpty()) {
            val hint = preferredBtMacHint
            val hinted = if (hint != null) btOutputs.firstOrNull { it.address == hint } else null
            if (hinted != null) {
                Log.i(TAG, "preferred: ${describe(hinted)} (BT, matches hint $hint)")
                return hinted
            }
            val bt = btOutputs.first()
            Log.i(TAG, "preferred: ${describe(bt)} (BT wins; hint=$hint not present)")
            return bt
        }

        // 2) Wired — operator physically plugged this in. Overrides any
        //    saved internal preference: if you wanted earpiece-only,
        //    you'd have unplugged the headset.
        val wired = outputs.firstOrNull { it.isWired() }
        if (wired != null) {
            Log.i(TAG, "preferred: ${describe(wired)} (wired present)")
            return wired
        }

        // 3) No external device — internal speaker vs earpiece. Default
        //    behaviour is speaker so peer voice is loud enough to hear
        //    in tactical environments. EARPIECE preference flips it
        //    only when the operator explicitly chose the privacy mode.
        val speaker = outputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
        val earpiece = outputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE }
        val picked =
            when (route) {
                OutputRoute.EARPIECE -> earpiece ?: speaker
                else -> speaker ?: earpiece // AUTO / SPEAKER / WIRED-fallback
            }
        Log.i(TAG, "preferred: ${describe(picked)} (route=$route, internal fallback)")
        return picked
    }

    private fun notifyChange() {
        if (listeners.isEmpty()) return
        if (txActiveProvider()) {
            // Hot-attach (or any other route-impacting change) arrived
            // while TX is in flight. Mark it pending and bail — the
            // listener fan-out runs from flushPendingRouteChange when
            // TxController.stopInternal completes.
            pendingRouteChange = true
            Log.i(TAG, "deferring route change — TX in flight")
            return
        }
        fanOutToListeners()
    }

    /**
     * Operator-driven route preference change. Unlike [notifyChange]
     * this bypasses the TX-active deferral, because during a private
     * call the mic is latched-on for the entire call duration —
     * deferring until TX ends means deferring until hangup, which
     * defeats the point of letting the operator pick earpiece /
     * speaker / BT mid-call. Used by the [route], [preferredBtMacHint]
     * and [outputBtOverrideMac] setters.
     */
    private fun notifyChangeFromOperator() {
        if (listeners.isEmpty()) return
        // Clear any pending deferral so we don't double-fire when the
        // current TX burst (if any) ends.
        pendingRouteChange = false
        fanOutToListeners()
    }

    private fun fanOutToListeners() {
        val dev = preferredDevice()
        for (l in listeners) {
            try {
                l.onPreferredDeviceChanged(dev)
            } catch (t: Throwable) {
                Log.w(TAG, "RouteListener threw", t)
            }
        }
    }

    /**
     * Apply any route change that was deferred during a TX burst.
     * Wired into [TxController]'s onIdle hook so the swap lands the
     * moment we're back to State.IDLE — typically <10ms after the last
     * TX frame went out. No-op if nothing was deferred.
     */
    fun flushPendingRouteChange() {
        if (!pendingRouteChange) return
        pendingRouteChange = false
        Log.i(TAG, "flushing deferred route change")
        // Not guarded by txActiveProvider — callers (TxController) only
        // invoke this AFTER state has flipped to IDLE, so the guard in
        // notifyChange() is structurally false here.
        notifyChange()
    }

    private fun logDevices(
        prefix: String,
        devs: Array<out AudioDeviceInfo>,
    ) {
        for (d in devs) Log.i(TAG, "$prefix: ${describe(d)}")
    }

    private fun describe(d: AudioDeviceInfo?): String {
        if (d == null) return "auto"
        return "${typeName(d.type)}(${d.productName})"
    }

    /**
     * Short, human-friendly label for the current preferred device.
     * Used in the main-view audio-route indicator and in transition
     * toasts. Examples:
     *   - "AINA APTT" (BT speakermic — uses productName)
     *   - "Wired headset"
     *   - "Earpiece"
     *   - "Speaker"
     *   - "Auto" (no preferred device picked yet)
     */
    fun currentRouteLabel(): String {
        val d = preferredDevice() ?: return "Auto"
        return shortLabel(d)
    }

    private fun shortLabel(d: AudioDeviceInfo): String =
        when (d.type) {
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Speaker"
            AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "Earpiece"
            AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired headset"
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "Wired headphones"
            AudioDeviceInfo.TYPE_USB_HEADSET -> "USB headset"
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            ->
                // Prefer the human product name (e.g. "APTT301448").
                // Falls back to the type label if the device exposes
                // nothing useful.
                d.productName?.toString()?.takeIf { it.isNotBlank() } ?: "Bluetooth"
            else -> typeName(d.type)
        }

    private fun typeName(t: Int): String =
        when (t) {
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "SPEAKER"
            AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "EARPIECE"
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "BT_A2DP"
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "BT_SCO"
            AudioDeviceInfo.TYPE_WIRED_HEADSET -> "WIRED_HS"
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "WIRED_HP"
            AudioDeviceInfo.TYPE_USB_HEADSET -> "USB_HS"
            AudioDeviceInfo.TYPE_USB_DEVICE -> "USB"
            else -> "T$t"
        }

    private fun AudioDeviceInfo.isBluetooth(): Boolean =
        type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO

    private fun AudioDeviceInfo.isWired(): Boolean =
        type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
            type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
            type == AudioDeviceInfo.TYPE_USB_HEADSET

    companion object {
        private const val TAG = "XvAudioRouter"

        /**
         * How long the operator's preferred BT device (the AINA /
         * speakermic that matches [preferredBtMacHint]) is allowed to
         * stay unreachable in the audio-device output list before
         * [preferredDevice] falls back to the built-in phone speaker.
         *
         * 15s is a balance of two field constraints:
         *   - Short enough that a live session doesn't sit mute after
         *     an accidental disconnect (battery, out of range, puck
         *     bumped off).
         *   - Long enough to ride out a routine ~5s BT profile-reset
         *     (SCO teardown / re-establish that happens on some
         *     handsets around A2DP mode changes) without a spurious
         *     speaker chirp mid-transmission.
         *
         * The operator's persisted MAC is NOT cleared while fallback
         * is active — as soon as the device reappears in the output
         * list we exit fallback and route back to BT.
         */
        const val BT_UNAVAILABLE_SPEAKER_FALLBACK_MS: Long = 15_000L

        /**
         * Pure type + address carrier for the audio-device selector
         * [pickPreferredDeviceFromCandidates]. Production wraps
         * AudioDeviceInfo into this; tests build the list directly.
         */
        internal data class DeviceCandidate(
            val type: Int,
            val address: String,
            val audioDevice: Any? = null,
        )

        /**
         * Pure-function audio-device selector. Mirrors the production
         * priority chain in [preferredDevice]:
         *
         *   1. Explicit BT override MAC (operator picked a specific BT
         *      device). Falls through if device not currently present.
         *   2. First Bluetooth output, with [preferredBtHintMac] match
         *      winning over iteration order when multiple BT outputs
         *      are present.
         *   3. First wired (HEADSET / HEADPHONES / USB_HEADSET).
         *   4. Internal speaker / earpiece per [route].
         *
         * Returns the selected candidate or null when nothing matches.
         * Pure so AudioRouterPickerTest can pin every branch without
         * standing up AudioManager.
         */
        @androidx.annotation.VisibleForTesting
        internal fun pickPreferredDeviceFromCandidates(
            outputs: List<DeviceCandidate>,
            route: OutputRoute,
            overrideMac: String?,
            preferredBtHintMac: String?,
        ): DeviceCandidate? {
            // 0) Explicit BT override.
            if (overrideMac != null) {
                val pinned = outputs.firstOrNull { it.isBluetooth() && it.address == overrideMac }
                if (pinned != null) return pinned
                // Fall through — preserved-but-absent override drops to
                // the regular chain.
            }

            // 1) Bluetooth — hinted preference wins over first-found.
            val btOutputs = outputs.filter { it.isBluetooth() }
            if (btOutputs.isNotEmpty()) {
                val hinted =
                    if (preferredBtHintMac != null) {
                        btOutputs.firstOrNull { it.address == preferredBtHintMac }
                    } else {
                        null
                    }
                return hinted ?: btOutputs.first()
            }

            // 2) Wired.
            val wired = outputs.firstOrNull { it.isWired() }
            if (wired != null) return wired

            // 3) Internal speaker / earpiece per route.
            val speaker = outputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
            val earpiece = outputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE }
            return when (route) {
                OutputRoute.EARPIECE -> earpiece ?: speaker
                else -> speaker ?: earpiece
            }
        }

        private fun DeviceCandidate.isBluetooth(): Boolean =
            type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO

        private fun DeviceCandidate.isWired(): Boolean =
            type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                type == AudioDeviceInfo.TYPE_USB_HEADSET
    }
}
