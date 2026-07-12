package com.atakmap.android.xv.service

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.util.Log
import com.atakmap.android.xv.aina.AinaBleReader
import com.atakmap.android.xv.aina.AinaButton
import com.atakmap.android.xv.aina.AinaSppReader
import com.atakmap.android.xv.aina.PrymeBleReader
import com.atakmap.android.xv.audio.AinaA2dpController
import com.atakmap.android.xv.audio.AinaA2dpWiring
import com.atakmap.android.xv.audio.AudioCapture
import com.atakmap.android.xv.audio.AudioController
import com.atakmap.android.xv.audio.AudioControllerImpl
import com.atakmap.android.xv.audio.AudioPlayback
import com.atakmap.android.xv.audio.AudioRouter
import com.atakmap.android.xv.audio.BtAudioMode
import com.atakmap.android.xv.audio.BtAudioPolicy
import com.atakmap.android.xv.audio.ConcentusOpusDecoder
import com.atakmap.android.xv.audio.ConcentusOpusEncoder
import com.atakmap.android.xv.audio.OpusDecoder
import com.atakmap.android.xv.audio.OpusEncoder
import com.atakmap.android.xv.audio.OutputRoute
import com.atakmap.android.xv.audio.PttDispatcher
import com.atakmap.android.xv.audio.PttSource
import com.atakmap.android.xv.audio.ScoLink
import com.atakmap.android.xv.audio.StatusTones
import com.atakmap.android.xv.audio.TptPlayer
import com.atakmap.android.xv.audio.TptTone
import com.atakmap.android.xv.audio.TxController
import com.atakmap.android.xv.audio.logPrefixForPttSource
import com.atakmap.android.xv.ptt.SamsungActiveKeyReader
import com.atakmap.android.xv.ptt.SonimEmergencyButtonReader
import com.atakmap.android.xv.ptt.SonimPttButtonReader

// All of XV's audio + AINA + PTT-state subsystem, instantiated in our
// APK's UID where FOREGROUND_SERVICE_TYPE_MICROPHONE actually grants
// background AudioRecord privileges. Lives for the lifetime of
// XvVoiceService.
//
// What's here: AudioCapture (mic), AudioPlayback (speaker), TptPlayer,
// ScoLink, BtAudioPolicy, AudioController, TxController, PttDispatcher,
// StatusTones, AINA SPP / BLE readers, Opus codec factories.
//
// What's NOT here (lives in plugin): MumbleTransport (cert lookup
// requires ATAK runtime), CoT presence, Emergency, UI dropdown.
//
// Communication boundary:
//   - Plugin → plant: PTT events, settings, RX Opus frames from Mumble,
//                     AINA control, current channel state for canTransmit
//   - Plant → plugin: TX Opus frames, terminators, PTT state edges,
//                     AINA connection state, audio state text
//
// All cross-process traffic is small (Opus ~50B/frame at 100Hz = 5KB/s).
class VoicePlant(
    private val context: Context,
    private val callbacks: Callbacks,
    // Probe: does XV have a live self-managed Telecom Connection right
    // now? Injected so unit tests can drive the "own call vs external
    // call" branch of [cellularCallStateFromAudioMode] without a real
    // Telecom registry or Robolectric. Defaults to reading
    // [ActiveCallRegistry.hasActiveCall], which flips true the moment
    // XvConnectionService registers a fresh XvConnection and flips
    // back to false when XvConnection.onDestroy / teardownLocal fires
    // — driven by the TELECOM_END_DEBOUNCE_MS runnable in
    // XvVoiceService. That means the flag stays true across the whole
    // 8 s post-activity wind-down of a burst (so mid-burst PTT
    // presses do not self-gate) and only drops after Telecom has
    // actually torn our connection down (so a fresh cellular call
    // arriving after the debounce ends is correctly seen as
    // external).
    //
    // Why we need this: on modern Pixel / Samsung / Motorola handsets
    // with VoLTE cellular (the default on every major US carrier),
    // the OS uses MODE_IN_COMMUNICATION for the *real* cellular call
    // — the same audio mode XV's own self-managed Telecom call uses,
    // and the same mode any other VoIP app (Signal, WhatsApp,
    // Discord, Meet) uses. AudioManager.getMode() alone cannot tell
    // "we placed this call" from "the cellular stack / another VoIP
    // app placed this call". Combining getMode() with a lookup of
    // our own connection state disambiguates: same MODE_IN_COMMUNICATION
    // reading means IDLE when it's our call (do not self-gate) and
    // OFFHOOK when it's theirs (block the accident-prone on-screen
    // PTT so we do not auto-hold their call by placing our own on
    // top). Field evidence for the ambiguity: Pixel 9 Pro / API 35,
    // 2026-07-11 — a VoLTE voicemail call put the device in
    // MODE_IN_COMMUNICATION; the old getMode()-only mapping treated
    // that as IDLE, allowed the on-screen PTT, and let XV's Telecom
    // call auto-hold the operator's live cellular call.
    private val xvHasActiveTelecomCallProvider: () -> Boolean = {
        try {
            com.atakmap.android.xv.telecom.ActiveCallRegistry
                .hasActiveCall()
        } catch (t: Throwable) {
            // Registry is a plain object with a volatile field read —
            // it does not throw in practice. Belt-and-suspenders:
            // treat "we don't know" as "we have no call", so a
            // MODE_IN_COMMUNICATION reading maps to OFFHOOK and the
            // on-screen PTT is blocked. That fails safe for the
            // exact bug this gate exists to prevent — auto-holding
            // an external call — at the cost of a spurious block on
            // our own call, which is recoverable (release, press
            // again, timing settles).
            android.util.Log.w(
                TAG,
                "xvHasActiveTelecomCallProvider: unexpected throw — treating as no active call",
                t,
            )
            false
        }
    },
    // Cellular-call state probe for the pttDown gate. Injected so unit
    // tests can drive OFFHOOK / RINGING / IDLE without touching a real
    // AudioManager. Defaults to reading AudioManager.getMode() +
    // [xvHasActiveTelecomCallProvider] and mapping the pair to a
    // TelephonyManager call-state constant via
    // [cellularCallStateFromAudioMode].
    //
    // Why AudioManager and not TelephonyManager: the original wire-up
    // used TelephonyManager.getCallState() on the assumption that the
    // plain non-permission API works from API 26 upward. That is wrong
    // from API 31 onward — on-device evidence from Pixel API 35 shows
    // getCallState() throws SecurityException ("Neither user nor
    // current process has android.permission.READ_PHONE_STATE") on
    // every PTT-down. The fidget-guard exists to reduce noise; a probe
    // that itself throws + logs a stack trace on every press makes the
    // audio hot-path more expensive AND buys no benefit (we never see
    // OFFHOOK). AudioManager.getMode() requires no permission at any
    // API level and returns MODE_IN_CALL / MODE_RINGTONE / MODE_IN_COMMUNICATION
    // for the states we want to gate on. XV's own audio-plant code
    // already reads audioManager.mode extensively (AudioControllerImpl,
    // ScoLink, TptPlayer) — this is just one more reader.
    //
    // Belt-and-suspenders: a defensive catch on Throwable still returns
    // IDLE (fail-open). getMode() is not documented to throw, but a
    // SILENT permission denial that we cannot observe is a worse
    // failure than the auto-hold bug this gate exists to fix.
    private val cellularCallStateProvider: () -> Int = {
        try {
            val am =
                context.getSystemService(Context.AUDIO_SERVICE)
                    as? AudioManager
            // Post-teardown grace: after our own Telecom call
            // unregisters, AudioManager.getMode() can lag on the
            // MODE_IN_COMMUNICATION → MODE_NORMAL transition for up
            // to ~1.4 s on Pixel 9 Pro / API 35 (field observation
            // 2026-07-11 14:47). In that window
            // xvHasActiveTelecomCallProvider() returns false but
            // the mode still reads IN_COMMUNICATION, which without
            // this grace would resolve to CALL_STATE_OFFHOOK and
            // block the operator's legitimate follow-up press with
            // a spurious "Cellular call active" toast. OR the grace
            // check into the own-call signal so the disambiguation
            // stays "ours" for RECENT_OWN_CALL_GRACE_MS after our
            // registry entry drops. External calls still resolve
            // correctly because they never registered — the grace
            // stamp only advances on our OWN teardown.
            val nowMs = android.os.SystemClock.elapsedRealtime()
            val xvOwnCallOrGrace =
                xvHasActiveTelecomCallProvider() ||
                    com.atakmap.android.xv.telecom.ActiveCallRegistry
                        .withinRecentCallGrace(
                            nowMs = nowMs,
                            graceMs = com.atakmap.android.xv.telecom.ActiveCallRegistry
                                .RECENT_OWN_CALL_GRACE_MS,
                        )
            cellularCallStateFromAudioMode(
                audioMode = am?.mode ?: AudioManager.MODE_NORMAL,
                xvHasActiveTelecomCall = xvOwnCallOrGrace,
            )
        } catch (t: Throwable) {
            android.util.Log.w(
                TAG,
                "cellularCallStateProvider: unexpected throw — treating as IDLE",
                t,
            )
            android.telephony.TelephonyManager.CALL_STATE_IDLE
        }
    },
    // Monotonic clock for the cellular-block toast throttle. Injected
    // for the unit test so its "advance time" step does not require
    // real elapsed wall-clock. Defaults to SystemClock.elapsedRealtime
    // — insensitive to wall-clock jumps (NTP sync during a burst),
    // unlike currentTimeMillis.
    private val nowMsProvider: () -> Long = { android.os.SystemClock.elapsedRealtime() },
) {
    interface Callbacks {
        fun onTxOpus(
            slot: Int,
            opus: ByteArray,
        )

        fun onTxTerminator(slot: Int)

        fun onPttStateChanged(
            transmitting: Boolean,
            slot: Int,
        )

        fun onAinaConnectionChanged(connected: Boolean)

        fun onRxActivity()

        fun onAudioStateText(text: String)

        // Emergency button (PTTE) edge from the AINA. Routed to the
        // plugin's EmergencyController which talks to ATAK's
        // EmergencyManager (the dispatcher lives in ATAK's UID, so
        // the actual ATAK API call has to happen on the plugin side).
        fun onEmergencyButton(down: Boolean)

        // Telecom call lifecycle hooks. These fire from the SERVICE side
        // synchronously with PTT events so the call placement happens
        // before any SCO acquire / TPT activity — Tidal etc. release
        // their focus immediately on PTT-down instead of leaking through
        // SCO during the ~1.5 s warmup window. The service implements
        // these by calling TelecomManager.placeCall in our UID (where
        // MANAGE_OWN_CALLS lives).
        fun onPlaceTelecomCall(tag: String)

        fun onEndTelecomCall()

        // AINA Voice Responder MFB tap. Service decides what it means
        // based on current Telecom call state: answer if a connection
        // is RINGING, hang up if one is ACTIVE, otherwise no-op. Fires
        // on button RELEASE only (single tap).
        fun onCallButtonTapped()

        // Audio route changed (BT device plug/unplug, wired headset
        // plug/unplug, explicit operator route preference change).
        // [label] is the short human string from
        // [AudioRouter.currentRouteLabel].
        fun onAudioRouteChanged(label: String)

        // AudioCapture build / startRecording failed — almost always
        // RECORD_AUDIO permission revoked mid-session. Forwarded to the
        // plugin so it can Toast the operator instead of leaving them
        // staring at a dead PTT.
        fun onCaptureError(reason: String)

        // PTT press was suppressed because the cellular telephony stack
        // reports an active or ringing call. Plugin surfaces [reason]
        // as a Toast so the operator learns why nothing transmitted;
        // the plant already throttles this event so mashing PTT during
        // a call does not spam the UI.
        fun onPttBlockedByCellularCall(reason: String)
    }

    private val audioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val audioController: AudioController = AudioControllerImpl(context)
    private val btPolicy: BtAudioPolicy = BtAudioPolicy(context).also { it.start() }

    // A2DP forbid for AINA speakermics. Prevents phone media (Spotify,
    // YouTube, system sounds) from routing through the AINA when a
    // peer's voice isn't on the air. Without this, the AINA's A2DP
    // sink stays advertised as a valid music sink to the OS and
    // operators leak entertainment audio onto a tactical mic. See
    // [AinaA2dpController] for the reflection-based forbid mechanism
    // and [AinaA2dpWiring] for the connect-time invocation +
    // Pixel-signature-permission fallback prompt.
    private val ainaA2dpController: AinaA2dpController =
        AinaA2dpController(context).also { it.start() }
    private val ainaA2dpWiring: AinaA2dpWiring =
        AinaA2dpWiring(context, ainaA2dpController).also { wiring ->
            btPolicy.addConnectListener(wiring)
        }

    // Router constructed BEFORE scoLink so scoLink's preferred-MAC
    // accessor can close over it. The lambda is read on each comm-
    // device pick (cold start, warm re-assert, AudioDeviceCallback
    // re-pin), so changing the AINA picker takes effect on the next
    // acquire without rebuilding the link.
    private val router: AudioRouter = AudioRouter(context)
    private val scoLink: ScoLink =
        ScoLink(
            context = context,
            // "Audio device" override — the explicit Settings pick.
            // Wins absolutely when set AND the device exposes HFP
            // (i.e. shows up in availableCommunicationDevices). When
            // the operator picks a specific BT output, their mental
            // model is "audio goes there, regardless of which puck
            // owns the button" — ScoLink honors that here.
            outputOverrideMac = { router.outputBtOverrideMac },
            // AINA-hint fallback. Used when either (a) no override
            // is set or (b) the override MAC isn't a valid comm
            // device — A2DP-only headphones, out-of-range puck,
            // etc. ScoLink logs a warning at the fall-back and
            // pins comm to the hint (which is typically the AINA
            // that owns the PTT button).
            preferredBtMac = { router.preferredBtMacHint },
            // Display-name lookup for the audio-device override
            // MAC. Used only for the operator-facing Toast when
            // ScoLink can't reach the override programmatically
            // and is about to open the system output switcher.
            // Reads from the router's live output enumeration —
            // when the operator picked the device from the XV
            // Settings picker the name was captured then. Returns
            // null when the device isn't currently in the output
            // list, in which case ScoLink omits the name segment
            // of the Toast rather than fall back to the redacted
            // MAC (worse UX than a nameless prompt).
            overrideDisplayName = overrideDisplayName@{
                val mac = router.outputBtOverrideMac ?: return@overrideDisplayName null
                router
                    .availableBtOutputs()
                    .firstOrNull { it.address.equals(mac, ignoreCase = true) }
                    ?.displayName
            },
        ).also { link ->
            // Wire ScoLink into AudioControllerImpl so focus-loss
            // teardown publishes SUSPENDED instead of the controller
            // calling stopBluetoothSco directly behind ScoLink's back.
            (audioController as? AudioControllerImpl)?.setScoLink(link)
        }
    private val tptPlayer: TptPlayer =
        TptPlayer(
            context = context,
            // TPT goes to the operator's selected output. Without this
            // the non-SCO tone path hardcoded BUILTIN_SPEAKER and
            // ignored the route picker — explicit EARPIECE picks
            // played on speaker anyway.
            preferredDeviceForTones = { router.preferredDevice() },
        ).also { it.primeMediaPath() }

    // Session id of the currently-live AudioCapture, or null when no
    // capture is running. Published by AudioCapture.start() (via the
    // onSessionIdChanged callback wired below) and cleared by
    // AudioCapture.stop(). AudioPlayback consults it when building a
    // fresh AudioTrack so RX playback shares a session with the mic
    // path — Android's AcousticEchoCanceler (attached to the capture
    // session in AudioCapture.configureAudioEffects) then has a real
    // downlink reference signal to subtract from the mic input, which
    // is what stops the operator's peer from hearing themselves
    // through the operator's own speakermic on a warm back-and-forth.
    // Volatile: TX thread writes (AudioCapture.start/stop runs on the
    // caller thread; TxController drives the lifecycle from its own
    // executor), RX thread reads (playPcm delivery landing on the
    // Mumble decoder thread).
    @Volatile private var currentCaptureSessionId: Int? = null

    /** Called by AudioCapture on start()/stop() so AudioPlayback can
     *  bind its AudioTrack to the same audio session. Public API is
     *  package-scoped-by-convention — external callers should never
     *  touch this. */
    internal fun setCurrentCaptureSessionId(id: Int?) {
        currentCaptureSessionId = id
        Log.i(TAG, "currentCaptureSessionId → $id")
    }

    private val audioPlayback: AudioPlayback =
        AudioPlayback(
            controller = audioController,
            router = router,
            btPolicy = btPolicy,
            scoLink = scoLink,
            onRxActivity = { callbacks.onRxActivity() },
            telecomActive = {
                com.atakmap.android.xv.telecom.ActiveCallRegistry
                    .hasActiveCall()
            },
            // Wire RX SCO_HOT → TX mic pre-warm. When a peer's burst
            // ends, AudioPlayback holds SCO warm for ~5 s. Use that
            // window to let the BT chipset's mic data path settle so
            // the operator's response PTT skips the chipset wake-up
            // cost. Especially helps slow combos (Duo+V1) where the
            // mic returns silence for 1-3 s after a cold SCO setup.
            onScoHotChanged = { active -> txController.preWarmMic(active) },
            // AEC session linkage. When a capture is live, AudioTrack
            // is built against the same session id so the AEC effect
            // (attached to the capture session in AudioCapture) has a
            // real reference signal. First RX before any TX still
            // races — this covers the warm back-and-forth case which
            // is the field bug.
            captureSessionIdProvider = { currentCaptureSessionId },
        ).also { router.start(it) }

    private val statusTones = StatusTones(tptPlayer = tptPlayer, enabled = { statusTonesEnabled })

    @Volatile private var statusTonesEnabled: Boolean = true

    @Volatile private var currentTptTone: TptTone = TptTone.DEFAULT

    @Volatile private var latchedMode: Boolean = false

    @Volatile private var pttTimeoutSec: Int = 30

    @Volatile private var latchedTimeoutSec: Int = 180

    @Volatile private var mumbleSessionLive: Boolean = false

    // Per-slot speak permission. Default true so the first PTT before
    // any UserState lands isn't spuriously denied; flips false the
    // moment the server tells us we're suppressed (OTS direction OUT
    // / Mumble admin mute). Plugin pushes updates via
    // setCanSpeakOnSlot AIDL.
    private val canSpeakOnSlot = booleanArrayOf(true, true)

    // Hot Mic mode preference, set by the operator via the AIDL surface.
    // Read on every TxController cool-down decision so toggles take
    // effect on the next burst without reconnect / restart.
    @Volatile
    private var hotMicEnabled: Boolean = false

    private val txController: TxController by lazy {
        TxController(
            scoLink = scoLink,
            btPolicy = btPolicy,
            tptPlayer = tptPlayer,
            audioCaptureFactory = { onFrame ->
                AudioCapture(
                    context = context,
                    onFrame = onFrame,
                    onCaptureError = { reason -> callbacks.onCaptureError(reason) },
                    // Publish the OS-assigned capture session id up so
                    // AudioPlayback can share it with its AudioTrack. See
                    // [currentCaptureSessionId] docstring for the AEC
                    // rationale.
                    onSessionIdChanged = { id -> setCurrentCaptureSessionId(id) },
                )
            },
            opusEncoderFactory = { ConcentusOpusEncoder() as OpusEncoder },
            audioManager = audioManager,
            audioController = audioController,
            sendOpus = { opus, slot -> callbacks.onTxOpus(slot, opus) },
            sendTerminator = { slot -> callbacks.onTxTerminator(slot) },
            onPttStateChanged = { transmitting, slot ->
                callbacks.onPttStateChanged(transmitting, slot)
                // TX-state edge → end Telecom call when transmitting
                // turns false. Covers latched mode (where pttUp is a
                // no-op but stopInternal still fires this callback on
                // the second press) and momentary mode equally.
                if (!transmitting) callbacks.onEndTelecomCall()
            },
            tonePreference = { currentTptTone },
            canTransmit = { slot -> canTransmitOnSlot(slot) },
            isRxActive = { audioPlayback.isActive() },
            telecomActive = {
                com.atakmap.android.xv.telecom.ActiveCallRegistry
                    .hasActiveCall()
            },
            // H3: when TX returns to idle, fire any route change that
            // arrived while we were transmitting. The router queues
            // hot-attach events during the burst (see AudioRouter.txActiveProvider
            // wiring below) so we don't swap the comm device under a
            // live capture/playback pipeline.
            onIdle = { router.flushPendingRouteChange() },
            // Hot Mic mode: read live from the per-burst preference so
            // the operator's toggle takes effect without restarting
            // anything. TxController consults this in the cool-down
            // release runnable to decide whether to release SCO.
            hotMicMode = { hotMicEnabled },
        ).also {
            // Tell the router how to ask "is TX in flight right now?"
            // so notifyChange() knows whether to defer. Set after
            // TxController is constructed because the lambda captures it.
            router.txActiveProvider = { it.isTxActive() }
        }
    }

    private val pttDispatcher: PttDispatcher by lazy {
        PttDispatcher(
            txController = txController,
            statusTones = statusTones,
            latchedModeEnabled = { latchedMode },
            momentaryTimeoutSec = { pttTimeoutSec },
            latchedTimeoutSec = { latchedTimeoutSec },
            tptPlayer = tptPlayer,
            // True iff we're mid-burst AND SCO is the live audio path —
            // i.e. the speakermic will hear voice-comm output. Lets the
            // pre-cutoff warning chirp route through the speakermic
            // instead of leaking out of the phone speaker in the
            // operator's pocket.
            txOnSco = {
                txController.isTxActive() &&
                    btPolicy.classify() == BtAudioMode.HFP_ONLY &&
                    scoLink.state == ScoLink.State.CONNECTED
            },
        )
    }

    private val opusDecoderFactory: () -> OpusDecoder = { ConcentusOpusDecoder() as OpusDecoder }

    @Volatile private var ainaSpp: AinaSppReader? = null

    @Volatile private var ainaBle: AinaBleReader? = null
    // BleHidPttReader was a never-used MediaSession-style BLE HID PTT
    // reader for generic BLE PTT pucks. XV's PTT input is now scoped to
    // AINA (V1 SPP, V2 BLE) + Pryme (vendor BLE GATT) only — see task
    // #83 — so the field is gone. Left as a comment so a future "where
    // did this go?" search lands here.

    @Volatile private var prymeBle: PrymeBleReader? = null

    // External-button PTT slot (e.g. a Pryme handlebar puck for
    // motorcyclists who already wear an AINA speakermic). Independent
    // connect / disconnect surface so the operator can pair both a
    // speakermic AND an external button without one tearing the other
    // down. PttDispatcher's OR-gate keeps concurrent presses from
    // cutting each other off — see [PttDispatcher.down]. The external
    // button is hard-locked to slot 0 (primary channel) and ignores
    // PTTS/PTTE because the typical motorcyclist use case is "talk on
    // the main channel without taking a hand off the bar" — not a full
    // second input device with its own emergency button.
    @Volatile private var externalButtonSpp: AinaSppReader? = null

    @Volatile private var externalButtonBle: AinaBleReader? = null

    @Volatile private var externalButtonPrymeBle: PrymeBleReader? = null

    // Samsung ruggedized-device programmable Active Key reader
    // (Tab Active5, XCover6 Pro / 7, Tab Active4 Pro, Tab Active3).
    // Independent of AINA / External Button — runs in parallel and
    // keys slot 0 via [PttSource.SAMSUNG_ACTIVE_KEY]. Present only on
    // hardware that emits `HARD_KEY_REPORT`; on any other device this
    // stays null because [XvVoiceService] never calls
    // [startSamsungActiveKey]. See [com.atakmap.android.xv.util.SamsungActiveKey].
    @Volatile private var samsungActiveKey: SamsungActiveKeyReader? = null

    // Sonim ruggedized-device dedicated PTT + Emergency broadcast
    // readers (XP10 / XP9900 and XP-family peers). Independent of AINA
    // / Secondary — run in parallel and key slot 0 via
    // PttSource.SONIM_PTT / SONIM_EMERGENCY. Present only on Sonim
    // hardware that emits the corresponding broadcasts; on any other
    // device these stay null because [XvVoiceService] never calls
    // [startSonimPttButton] / [startSonimEmergencyButton]. See
    // [com.atakmap.android.xv.util.SonimHardwareButtons].
    @Volatile private var sonimPtt: SonimPttButtonReader? = null

    @Volatile private var sonimEmergency: SonimEmergencyButtonReader? = null

    // MAC of the currently-connected AINA (or null when none). Used by
    // the BOND_STATE_CHANGED receiver to detect "the operator just
    // unpaired the AINA we're using" and tear the reader down before
    // its reconnect loop wastes BLE scanning on a device that no
    // longer exists in the bond table.
    @Volatile private var connectedAinaMac: String? = null

    // Same SharedPreferences file as the plugin-side XvSettings — this
    // service runs in the same APK / UID, so it shares prefs storage.
    // Used to clear stale per-MAC AINA protocol overrides on BOND_NONE
    // (operator re-paired the device; the override may no longer
    // match the firmware they re-paired against).
    private val settingsForOverride =
        com.atakmap.android.xv.plugin.XvSettings(
            prefsProvider = {
                context.getSharedPreferences(
                    com.atakmap.android.xv.plugin.XvSettings.PREFS_NAME,
                    Context.MODE_PRIVATE,
                )
            },
        )

    private val bondStateReceiver =
        object : android.content.BroadcastReceiver() {
            override fun onReceive(
                c: android.content.Context?,
                i: android.content.Intent?,
            ) {
                if (i?.action != android.bluetooth.BluetoothDevice.ACTION_BOND_STATE_CHANGED) return
                val bond =
                    i.getIntExtra(
                        android.bluetooth.BluetoothDevice.EXTRA_BOND_STATE,
                        android.bluetooth.BluetoothDevice.ERROR,
                    )
                if (bond != android.bluetooth.BluetoothDevice.BOND_NONE) return
                val device: android.bluetooth.BluetoothDevice? =
                    i.getParcelableExtra(android.bluetooth.BluetoothDevice.EXTRA_DEVICE)
                val mac = device?.address ?: return
                onBondNone(mac)
            }
        }

    // Extracted as a private (effectively-internal) helper so the
    // BOND_NONE behavior can be exercised by VoicePlantBondNoneTest
    // without standing up the rest of the plant. Clears the per-MAC
    // protocol override (so a re-pair starts clean) and tears down
    // the live AINA reader if this MAC is the connected one.
    internal fun onBondNone(mac: String) {
        try {
            settingsForOverride.clearAinaProtocolOverride(mac, reason = "BOND_NONE")
        } catch (t: Throwable) {
            Log.w(TAG, "clearAinaProtocolOverride threw", t)
        }
        val current = connectedAinaMac
        if (current != null && current.equals(mac, ignoreCase = true)) {
            Log.w(TAG, "AINA $mac was unpaired — tearing down reader to stop reconnect storm")
            disconnectAina()
        }
    }

    // Re-applies our no-SCO route preference whenever SCO transitions
    // to DISCONNECTED with a Telecom call still active. Without this,
    // a BT speakermic powered off mid-call would leave audio routing
    // to whatever default the OS picks (typically earpiece) instead
    // of honoring the operator's SPEAKER preference.
    private val scoStateListenerForRoute =
        object : ScoLink.StateListener {
            override fun onScoStateChanged(state: ScoLink.State) {
                // SUSPENDED also means SCO no longer owns the comm
                // device — treat as a teardown for routing purposes.
                if ((state == ScoLink.State.DISCONNECTED || state == ScoLink.State.SUSPENDED) &&
                    com.atakmap.android.xv.telecom.ActiveCallRegistry
                        .hasActiveCall()
                ) {
                    Log.i(TAG, "SCO $state while call active — re-applying no-SCO route preference")
                    applyNoScoCommDeviceForCurrentRoute()
                }
            }
        }

    // Re-applies the no-SCO comm device whenever the audio device
    // landscape changes. Fires when a BT device is paired/connected
    // or removed, when wired plugs in, etc. The point: when the
    // operator's BT-override device disappears we fall back; when it
    // returns we route back to it without requiring a fresh PTT.
    //
    // Retries on a short delay because availableCommunicationDevices
    // lags audioManager.getDevices() on add events: the device shows
    // up as a generic output before it's ready for voice-comm. Three
    // attempts at 0/250/750 ms covers the typical readiness window
    // without blocking; we stop early if a retry succeeds.
    private val commRouteHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val commRouteRetryDelays = longArrayOf(0L, 250L, 750L)

    private val commDeviceRouteListener =
        object : AudioRouter.RouteListener {
            override fun onPreferredDeviceChanged(device: android.media.AudioDeviceInfo?) {
                if (!com.atakmap.android.xv.telecom.ActiveCallRegistry
                        .hasActiveCall()
                ) {
                    return
                }
                // Cancel any in-flight retries from a prior event so we
                // don't accumulate scheduled callbacks during rapid
                // device churn.
                commRouteHandler.removeCallbacksAndMessages(commRouteRetryToken)
                for (delay in commRouteRetryDelays) {
                    commRouteHandler.postAtTime(
                        { applyNoScoCommDeviceForCurrentRoute() },
                        commRouteRetryToken,
                        android.os.SystemClock.uptimeMillis() + delay,
                    )
                }
            }
        }

    // Publishes route changes to the plugin UI so the audio-route
    // indicator updates instantly on BT plug/unplug (the 2s refresh
    // poll would otherwise leave the operator looking at a stale
    // label during the most attention-grabbing moments — a headset
    // dying mid-burst). Also dedupes against the last-published label
    // so we don't fan out spurious updates when the router fires for
    // device-list changes that don't affect the preferred device.
    @Volatile private var lastPublishedRouteLabel: String = ""

    private val routeUiListener =
        object : AudioRouter.RouteListener {
            override fun onPreferredDeviceChanged(device: android.media.AudioDeviceInfo?) {
                // Piggyback on the route-change fan-out to let the AINA
                // A2DP wiring drop its diag notification once the AINA
                // A2DP sink is no longer in the output device list
                // (operator powered off the mic, or actioned the
                // manual "disable Media audio" fix).
                try {
                    ainaA2dpWiring.reconcileNotification()
                } catch (t: Throwable) {
                    Log.w(TAG, "ainaA2dpWiring.reconcileNotification threw", t)
                }
                val label = router.currentRouteLabel()
                if (label == lastPublishedRouteLabel) return
                lastPublishedRouteLabel = label
                try {
                    callbacks.onAudioRouteChanged(label)
                } catch (t: Throwable) {
                    Log.w(TAG, "onAudioRouteChanged callback threw", t)
                }
            }
        }

    // Token used to batch-cancel the staggered retries above.
    private val commRouteRetryToken = Any()

    // Telecom-side route changes. Fires when XvConnection's
    // onCallAudioStateChanged runs, which is the moment Telecom
    // actually transitions the device to MODE_IN_COMMUNICATION and
    // picks an output (defaulting to earpiece on Pixel/most devices).
    //
    // Earlier this listener reactively called applyNoScoCommDeviceForCur-
    // rentRoute() to force SPEAKER. That created a feedback loop with
    // Telecom's CallEndpointController: each setCommunicationDevice
    // triggered a Telecom CARC.pM_SPEAKER_OFF transition, which fired a
    // new onCallAudioStateChanged, which prompted another reapply.
    // Observed 2026-05-19 (Pixel 9 Pro, Android 16): 5-8 SPEAKER↔EARPIECE
    // bounces in 10ms during call setup, with the TPT (USAGE_VOICE_COMMU-
    // NICATION) landing on whichever side of the bounce was active when
    // it played — commDev=type=1 (EARPIECE) in the captured audio ctx
    // log. Operator's complaint: "TPT coming out of the wrong speaker."
    //
    // Fix: stop fighting Telecom's CallEndpointController with the
    // deprecated setCommunicationDevice API. For self-managed VoIP,
    // Telecom owns the comm device via its own state machine and
    // [XvConnection.maybeApplyDefaultRoute] uses Connection.setAudio-
    // Route(ROUTE_SPEAKER) which IS the right API — it's processed by
    // CallEndpointController without re-triggering the feedback loop.
    // The route-settle guard in TxController.startTpt() gives Telecom
    // ~150ms to converge after setAudioRoute before the tone plays.
    private val telecomRouteListener: (android.telecom.CallAudioState?) -> Unit = { state ->
        Log.d(TAG, "telecom route changed (observe only; route owned by Telecom.setAudioRoute): state=$state")
    }

    init {
        // Concentus codec warmup on a background thread. Loads
        // org.concentus.* classes, runs SILK/CELT static init, and
        // gives the JIT a chance to compile encode()/decode() hot
        // paths before the operator's first PTT. Saves ~15-30ms on
        // the very first burst after plugin load (one-time win, not
        // per-burst — class loading is global). Background thread so
        // plugin-load critical path isn't blocked. Result is
        // discarded; the Concentus encoder has no native resources
        // to leak.
        Thread({
            try {
                ConcentusOpusEncoder().close()
                ConcentusOpusDecoder().close()
                Log.i(TAG, "Concentus codec warmup complete")
            } catch (t: Throwable) {
                Log.w(TAG, "Concentus codec warmup threw (non-fatal)", t)
            }
        }, "XvCodecWarmup").apply {
            isDaemon = true
            start()
        }
        // Touch lazies so the constructor work happens up front, not
        // on first PTT.
        @Suppress("UNUSED_VARIABLE")
        val warmTx = txController

        @Suppress("UNUSED_VARIABLE")
        val warmPtt = pttDispatcher
        // SCO drop → re-apply our no-SCO route preference so audio
        // doesn't fall back to the OS earpiece default mid-call.
        scoLink.addStateListener(scoStateListenerForRoute)
        // Device add/remove → re-apply the no-SCO comm device if
        // there's a live call. Lets the BT-override picker react in
        // real time when the chosen device disappears or reappears.
        router.addListener(commDeviceRouteListener)
        router.addListener(routeUiListener)
        // Telecom route change → re-apply. Critical: the pre-call
        // setCommunicationDevice runs before Telecom enters
        // MODE_IN_COMMUNICATION and Telecom will overwrite it on
        // call activation. This callback fires after Telecom settles,
        // which is when our setCommunicationDevice actually sticks.
        com.atakmap.android.xv.telecom.ActiveCallRegistry
            .addRouteListener(telecomRouteListener)
        // Bond loss observation — operator unpairs the AINA via system
        // settings. Without this, the reader's reconnect loop keeps
        // trying to reach a device that's no longer in the bond table.
        try {
            val filter =
                android.content.IntentFilter(
                    android.bluetooth.BluetoothDevice.ACTION_BOND_STATE_CHANGED,
                )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(bondStateReceiver, filter, android.content.Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                context.registerReceiver(bondStateReceiver, filter)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "registerReceiver(BOND_STATE_CHANGED) failed", t)
        }
        Log.i(TAG, "VoicePlant constructed (pid=${android.os.Process.myPid()} uid=${android.os.Process.myUid()})")
    }

    // ---- Plugin-driven control ----

    // True during an active private (Phase E) call. While set, all
    // external PTT events are silently dropped — phone behavior, not
    // radio. The mic is held open by the auto-engaged latched TX
    // (set up by [pttDownForPrivateCall] from XvVoiceService); the
    // operator's button presses shouldn't disrupt that. Cleared on
    // call end by [XvVoiceService.disengagePrivateCallAudioMode],
    // which fires [releaseActiveTx] to drop the latched mic, restores
    // the operator's pre-call latched-mode preference, and finally
    // calls setPrivateCallActive(false) to re-open the gate.
    @Volatile private var privateCallActive: Boolean = false

    // True while an INCOMING call is ringing (RINGING Telecom state).
    // While set, a PTT button press is interpreted as "answer the
    // call" — fires onCallButtonTapped instead of going to PTT TX.
    // This is what lets ANY Bluetooth speakermic / generic BLE button
    // pick up a call: the same physical button that does PTT under
    // normal radio operation becomes the answer button during a ring.
    @Volatile private var callRinging: Boolean = false

    // Timestamp (elapsedRealtime ms) of the most recent
    // "cellular call active — hang up before XV PTT" toast fire.
    // 0 = never fired. Used by the pttDown cellular-call gate to
    // throttle the toast so mashing PTT during an active phone call
    // does not spam the operator's screen. Bump on every fire.
    @Volatile private var lastCellCallToastAtMs: Long = 0L

    /** Used by XvVoiceService to gate external PTT events during the
     *  call lifecycle. The internal mic-engage uses
     *  [pttDownForPrivateCall]; the matching release is split across
     *  [releaseActiveTx] (drops latched TX), [setLatchedMode] (restores
     *  the operator's prior latched-mode preference) and
     *  setPrivateCallActive(false) (re-opens the external-PTT gate) —
     *  all sequenced by [XvVoiceService.disengagePrivateCallAudioMode]. */
    fun setPrivateCallActive(active: Boolean) {
        privateCallActive = active
        Log.i(TAG, "setPrivateCallActive($active) — external PTT will be ${if (active) "ignored" else "honored"}")
    }

    /**
     * Pre-allocate the playback AudioTrack so the first inbound call
     * frame plays immediately. Called from the call-active engagement
     * path BEFORE the peer joins (caller side) and right after the
     * callee taps Answer (callee side). Idempotent — no-op when the
     * track is already up.
     */
    fun warmupCallPlayback() {
        try {
            audioPlayback.warmupForCall()
        } catch (t: Throwable) {
            Log.w(TAG, "warmupCallPlayback threw", t)
        }
    }

    /** Used by XvVoiceService to mark Telecom RINGING state so PTT
     *  presses route to call-answer instead of TX. Cleared on
     *  answer or decline (the answer path flips this off + sets
     *  privateCallActive=true). */
    fun setCallRinging(active: Boolean) {
        callRinging = active
        Log.i(TAG, "setCallRinging($active) — PTT will ${if (active) "answer the call" else "TX"}")
    }

    fun pttDown(slot: Int) {
        pttDown(slot, PttSource.DEFAULT)
    }

    fun pttDown(
        slot: Int,
        source: PttSource,
    ) {
        if (callRinging) {
            // Incoming call ringing: turn any PTT-like button press
            // (AINA PTT, BLE HID HEADSETHOOK, etc.) into an
            // answer-call gesture. The button no-ops as a PTT —
            // we route it through the call-button path so the
            // operator can pick up by pressing whatever they'd
            // normally use to talk.
            Log.i(TAG, "pttDown(slot=$slot) → call-answer (ringing)")
            try {
                callbacks.onCallButtonTapped()
            } catch (t: Throwable) {
                Log.w(TAG, "callButton dispatch from pttDown threw", t)
            }
            return
        }
        if (privateCallActive) {
            // Active private call: pressing PTT hangs up. Same
            // physical button → call-button semantics during a call.
            // (Latched TX is already engaged; this is the operator's
            // way to end a call from the speakermic.)
            Log.i(TAG, "pttDown(slot=$slot) → call-hangup (active call)")
            try {
                callbacks.onCallButtonTapped()
            } catch (t: Throwable) {
                Log.w(TAG, "callButton dispatch from pttDown threw", t)
            }
            return
        }
        // Cellular-call gate. XV places a self-managed Telecom call
        // on every pttDown to claim MODE_IN_COMMUNICATION + audio
        // focus; Android's Telecom framework auto-holds any active
        // third-party call whenever a second self-managed call is
        // placed. Operators fidgeting with a BT speakermic during
        // an active cellular call have accidentally keyed PTT and
        // dropped their phone call onto hold mid-conversation. Block
        // the TX (Option A) instead of preempting the cellular call
        // for what is almost certainly an unintentional press. See
        // PttCellularGate for the pure decision function and the
        // toast throttle. This gate must run BEFORE the Telecom call
        // placement below — the whole point is to avoid placing that
        // call while the cellular stack is busy.
        //
        // Blocks EVERY source (2026-07-11 revision — supersedes
        // both the original "block everything" (2026-07-10 21:00)
        // and the source-scoped "on-screen only" (2026-07-10 22:00)
        // positions). Phone calls are the #1 priority on the device
        // regardless of which button initiated the PTT. Field
        // observation 2026-07-11: a hardware AINA press during a
        // cellular voicemail call auto-held the call because the
        // source-scoped policy allowed hardware through. The
        // "hardware = intentional" assumption was too generous.
        // See [shouldGateForCellularCall] for the pure decision.
        val cellState = cellularCallStateProvider()
        val gate = shouldGateForCellularCall(cellState, source)
        if (gate != PttGate.ALLOW) {
            Log.i(TAG, "PTT blocked — cellular call state=$cellState source=$source (gate=$gate)")
            val now = nowMsProvider()
            if (shouldToastCellularBlock(nowMs = now, lastToastAtMs = lastCellCallToastAtMs)) {
                lastCellCallToastAtMs = now
                try {
                    callbacks.onPttBlockedByCellularCall(CELLULAR_BLOCK_TOAST_MSG)
                } catch (t: Throwable) {
                    Log.w(TAG, "onPttBlockedByCellularCall dispatch threw", t)
                }
            } else {
                Log.i(
                    TAG,
                    "cellular-block toast throttled (last=${now - lastCellCallToastAtMs}ms ago)",
                )
            }
            return
        }
        // Pre-set the comm device for our route preference BEFORE
        // anything else. Two consumers need this assertion:
        //
        // 1. TPT / deny / bonk tones on the canTransmit=false path —
        //    TxController plays a deny tone on a denied press, but no
        //    Telecom call gets placed (no `voice` connection → no
        //    Connection.setAudioRoute path), so the only thing pointing
        //    USAGE_VOICE_COMMUNICATION audio at the loudspeaker is this
        //    direct setCommunicationDevice. Skipping it routes the
        //    deny tone to earpiece — the operator hears nothing useful.
        //
        // 2. TPT on the canTransmit=true path — Telecom defaults
        //    self-managed VoIP calls to earpiece for ~50-100 ms before
        //    our XvConnection.maybeApplyDefaultRoute fires
        //    setAudioRoute(SPEAKER). TPT starts in that window and
        //    routes to earpiece. Pre-setting the comm device here
        //    gives Telecom a hint of the operator's preferred route
        //    before it does its own (audible) default-pick dance.
        //    ScoLink overrides this when SCO acquires; it's a no-op
        //    if SCO is already up.
        applyNoScoCommDeviceForCurrentRoute()
        // Gate the Telecom call placement on canTransmit: pressing PTT
        // on a listen-only channel (or with no Mumble session live)
        // shouldn't place a call, claim media focus, and pause Spotify
        // for the whole 8 s end-debounce window just to immediately
        // tear it back down. TxController will play the deny tone via
        // its own canTransmit gate; we just suppress the wasted Telecom
        // side effect.
        if (canTransmitOnSlot(slot)) {
            callbacks.onPlaceTelecomCall("voice")
        } else {
            Log.i(TAG, "pttDown(slot=$slot): canTransmit=false — skipping Telecom call placement")
        }
        pttDispatcher.down(slot, source)
    }

    /** Internal entry point bypassing the privateCallActive gate.
     *  Used ONLY by XvVoiceService.engagePrivateCallAudioMode to
     *  auto-engage the mic when the call goes ACTIVE. Don't call
     *  this from normal PTT input paths — those should go through
     *  pttDown so the gate applies. */
    fun pttDownForPrivateCall(slot: Int) {
        if (canTransmitOnSlot(slot)) {
            applyNoScoCommDeviceForCurrentRoute()
            callbacks.onPlaceTelecomCall("voice")
        }
        pttDispatcher.down(slot)
    }

    private fun canTransmitOnSlot(slot: Int): Boolean =
        mumbleSessionLive &&
            slot in canSpeakOnSlot.indices &&
            canSpeakOnSlot[slot]

    fun pttUp(slot: Int) {
        pttUp(slot, PttSource.DEFAULT)
    }

    fun pttUp(
        slot: Int,
        source: PttSource,
    ) {
        if (callRinging || privateCallActive) {
            // pttDown was the call-button trigger — just absorb the
            // matching up so PttDispatcher's edge-trigger state stays
            // consistent (no half-pressed PTT lingering).
            Log.i(TAG, "pttUp(slot=$slot) ignored — call-mode (ringing=$callRinging, active=$privateCallActive)")
            return
        }
        pttDispatcher.up(slot, source)
    }

    /** Force-release any active TX (latched or momentary) and cancel
     *  pending TX-timeout warnings. Used when an external party tears
     *  down our Telecom call (Telecom preemption, system call) — we
     *  need to drop the in-flight burst even though the operator hasn't
     *  released the button. PttDispatcher.release() is idempotent and
     *  no-ops if nothing was active. */
    fun releaseActiveTx() {
        pttDispatcher.release()
    }

    fun setLatchedMode(enabled: Boolean) {
        latchedMode = enabled
    }

    /**
     * Short human label for the currently-active audio route — surfaced
     * in the main view's audio-route indicator so the operator can see
     * at a glance whether they're on AINA / wired / speaker / earpiece
     * without inferring from the headset LED. Live-updated via the
     * RouteListener fan-out (see [routeUiListener]).
     */
    fun audioRouteLabel(): String = router.currentRouteLabel()

    /** Current latched-mode setting. Used by XvVoiceService to save +
     *  restore around a private call (which forces latched=true). */
    fun latchedMode(): Boolean = latchedMode

    fun setPttTimeoutSec(seconds: Int) {
        pttTimeoutSec = seconds.coerceAtLeast(0)
    }

    /** Current latched-timeout setting in seconds. 0 = no timeout
     *  (latched TX runs indefinitely until pttUp / external release).
     *  Used by XvVoiceService to save + restore around a private
     *  call (which forces 0 so the call doesn't auto-cut at 3 min). */
    fun latchedTimeoutSec(): Int = latchedTimeoutSec

    fun setLatchedTimeoutSec(seconds: Int) {
        latchedTimeoutSec = seconds.coerceAtLeast(0)
    }

    fun setStatusTonesEnabled(enabled: Boolean) {
        statusTonesEnabled = enabled
    }

    fun setTptTone(tone: TptTone) {
        currentTptTone = tone
    }

    fun setOutputRoute(route: OutputRoute) {
        // Order matters: set the route preference QUIETLY (no listener
        // fan-out yet), apply the new comm device, THEN trigger the
        // listener fan-out. The fan-out tells AudioPlayback to drop
        // its active AudioTrack so the next inbound frame rebuilds
        // fresh — and by then the system's comm device is already on
        // the new route, so the rebuilt track lands on the right
        // device.
        //
        // Without this ordering, the listener fan-out fires first
        // (teardown), then setCommunicationDevice runs — and any
        // peer-voice frame arriving in that microsecond gap rebuilds
        // a track against the OLD comm device. On Surface Duo 2 this
        // window is wide enough that the operator's earpiece tap
        // visibly fails mid-call (verified 2026-05-11).
        val changed = router.setRoutePreferenceQuiet(route)
        applyNoScoCommDeviceForCurrentRoute()
        if (changed) router.notifyOperatorChange()
    }

    fun setOutputBtOverride(mac: String?) {
        router.outputBtOverrideMac = mac
        // Live update: if the new override matches a currently-
        // connected BT audio device, route audio there immediately.
        applyOutputBtOverrideIfApplicable()
    }

    // When the override is set to a connected BT device, take it as
    // the comm device. Skip when ScoLink is engaged for a different
    // device (the AINA HFP path) — that case is rare (the AINA picker
    // and the audio override would be pointing at different things)
    // and ScoLink owns the comm device while it's up.
    private fun applyOutputBtOverrideIfApplicable() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val mac = router.outputBtOverrideMac ?: return
        // SUSPENDED is "system-broken, holders may retry" — not engaged
        // for routing purposes, so we're free to set the comm device.
        if (scoLink.state != ScoLink.State.DISCONNECTED && scoLink.state != ScoLink.State.SUSPENDED) {
            Log.i(TAG, "applyOutputBtOverride: SCO engaged, deferring to ScoLink")
            return
        }
        val device =
            audioManager.availableCommunicationDevices.firstOrNull { it.address == mac }
        if (device == null) {
            Log.i(TAG, "applyOutputBtOverride: $mac not currently a communication device")
            return
        }
        try {
            val ok = audioManager.setCommunicationDevice(device)
            Log.i(TAG, "applyOutputBtOverride: setCommunicationDevice(${device.productName}, $mac) → ok=$ok")
        } catch (t: Throwable) {
            Log.w(TAG, "setCommunicationDevice for override threw", t)
        }
    }

    /** Snapshot of currently-connected BT audio output devices for the UI picker. */
    fun availableBtOutputs(): List<AudioRouter.BtOutput> = router.availableBtOutputs()

    /** Currently-persisted BT audio output override MAC, or null. */
    fun outputBtOverrideMac(): String? = router.outputBtOverrideMac

    // When no BT speakermic is engaged, Android's audio policy decides
    // where USAGE_VOICE_COMMUNICATION lands — and the default in
    // MODE_IN_COMMUNICATION is the earpiece, even if the operator's
    // route preference is SPEAKER. AudioTrack.setPreferredDevice is
    // only a hint; the policy overrides it during voice mode.
    //
    // setCommunicationDevice is the right knob: it tells the audio
    // policy "route my voice traffic to THIS device." ScoLink already
    // does this for the SCO path. This method covers the no-SCO path
    // so the speaker/earpiece preference actually takes effect during
    // a Telecom call.
    //
    // Called on every pttDown (so the first transmission lands on the
    // right device) and on every setOutputRoute (so live preference
    // changes apply mid-call). Steps aside whenever SCO is currently
    // engaged — ScoLink owns the comm device while it's up, and our
    // call would override the SCO routing.
    private fun applyNoScoCommDeviceForCurrentRoute() {
        // SUSPENDED = system tore down the link; ScoLink no longer
        // owns the comm device, so it's safe to set our preference.
        if (scoLink.state != ScoLink.State.DISCONNECTED && scoLink.state != ScoLink.State.SUSPENDED) {
            // ScoLink owns the comm device while it's CONNECTING or
            // CONNECTED. Don't fight it.
            return
        }
        // BT override takes priority over the built-in route. If it's
        // set and the device is connected, route there. If set but
        // disconnected, fall through to the built-in route — operator's
        // preference is preserved in prefs and will apply automatically
        // when the device reappears (via the AudioDeviceCallback hook).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val overrideMac = router.outputBtOverrideMac
            if (overrideMac != null) {
                val pinned =
                    audioManager.availableCommunicationDevices.firstOrNull { it.address == overrideMac }
                if (pinned != null) {
                    try {
                        val ok = audioManager.setCommunicationDevice(pinned)
                        Log.i(
                            TAG,
                            "applyNoScoCommDevice: setCommunicationDevice(${pinned.productName}, " +
                                "override $overrideMac) → ok=$ok",
                        )
                        return
                    } catch (t: Throwable) {
                        Log.w(TAG, "setCommunicationDevice for BT override threw", t)
                    }
                } else {
                    Log.i(TAG, "applyNoScoCommDevice: BT override $overrideMac not available, falling back to built-in route")
                }
            }
        }
        val targetType =
            when (router.route) {
                OutputRoute.EARPIECE -> AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
                else -> AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
            }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val device =
                audioManager.availableCommunicationDevices.firstOrNull { it.type == targetType }
            if (device == null) {
                Log.w(TAG, "applyNoScoCommDevice: no AudioDeviceInfo for type=$targetType available")
                return
            }
            try {
                val ok = audioManager.setCommunicationDevice(device)
                Log.i(
                    TAG,
                    "applyNoScoCommDevice: setCommunicationDevice(${device.productName}, " +
                        "type=${describeType(targetType)}) → ok=$ok (route=${router.route})",
                )
            } catch (t: Throwable) {
                Log.w(TAG, "setCommunicationDevice threw", t)
            }
        } else {
            // Pre-S fallback. setSpeakerphoneOn is the only knob that
            // overrides the earpiece-default in MODE_IN_COMMUNICATION
            // on older builds. EARPIECE preference → speakerphone off.
            try {
                @Suppress("DEPRECATION")
                audioManager.isSpeakerphoneOn = (router.route != OutputRoute.EARPIECE)
                Log.i(TAG, "applyNoScoCommDevice (pre-S): speakerphone=${audioManager.isSpeakerphoneOn}")
            } catch (t: Throwable) {
                Log.w(TAG, "setSpeakerphoneOn fallback failed", t)
            }
        }
    }

    private fun describeType(t: Int): String =
        when (t) {
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "BUILTIN_SPEAKER"
            AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "BUILTIN_EARPIECE"
            else -> "T$t"
        }

    /**
     * Voice transport just dropped (network swap, TLS reset, ping
     * watchdog). If the operator is mid-burst, play the cutoff tone
     * via the SCO route so the speakermic surfaces "comms lost" and
     * force-stop TX so captured PCM stops being encoded into a dead
     * pipe. The plugin-side reconnect path will follow up — this is
     * about closing the operator's perception loop, not about the
     * session state itself (canTransmit gating is handled separately
     * via [setMumbleSessionLive]).
     */
    fun handleTransportLost() {
        if (!txController.isTxActive()) {
            // Not mid-burst — nothing to cut off. The plugin's own
            // status-tone path plays the quieter "voice lost" chirp
            // out-loud since SCO is presumably down anyway.
            Log.i(TAG, "handleTransportLost: not transmitting — no cutoff to play")
            return
        }
        Log.w(TAG, "handleTransportLost: TX in flight — playing cutoff tone and stopping")
        // playTimeoutCutoff already pins to BT_SCO via pinDeviceForTone,
        // so the cutoff lands on the speakermic the operator is talking
        // into. Fire BEFORE pttDispatcher.release so the cutoff doesn't
        // race against the stop tearing down the audio path.
        tptPlayer.playTimeoutCutoff()
        pttDispatcher.release()
    }

    fun setMumbleSessionLive(live: Boolean) {
        mumbleSessionLive = live
        // Joining/leaving a channel changes whether canTransmit is true,
        // so revisit Hot Mic arming. Disarms automatically if we just
        // left the channel.
        reconcileHotMic()
        // Session-scope mic pre-arm: hold AudioRecord open for the
        // whole Mumble-connected lifetime so PTT-down sees a hot mic.
        // Drops PTT-down → TPT-audible latency from 200-400 ms to
        // ~50 ms by skipping the AudioRecord allocation + mic-chipset
        // cold-start that dominate PRIMING on the non-SCO path. Distinct
        // from Hot Mic (which keeps SCO warm) — this keeps the mic
        // hardware warm even on phone-speaker routes where there's no
        // SCO link to hold.
        //
        // GATED off by default since 2026-05-21: holding AudioRecord
        // open across the whole Mumble session blocks Google Assistant
        // (Gemini) and any other voice app from acquiring the mic,
        // because Android's AudioRecord exclusivity model rejects
        // their request rather than queueing it — Gemini sees "mic in
        // use" and refuses to start. The auto-yield logic in
        // TxController works for apps that DO try to acquire (we
        // release on AudioRecordingCallback), but Gemini refuses to
        // try in the first place. Operators who don't use Assistant
        // can re-enable for the latency benefit via the debug intent
        // SET_PRE_ARM --es enabled true (a Settings toggle ships in a
        // follow-up). Default OFF so Assistant works out of the box.
        if (live && sessionMicPreArmEnabled) {
            txController.armSessionMic()
        } else {
            txController.disarmSessionMic()
        }
    }

    /** Operator preference for session-scope mic pre-arm. See
     *  [setMumbleSessionLive] for the trade-off. Default OFF so Google
     *  Assistant / Gemini can acquire the mic without conflict. */
    @Volatile
    var sessionMicPreArmEnabled: Boolean = false
        set(value) {
            val changed = field != value
            field = value
            if (!changed) return
            Log.i(TAG, "sessionMicPreArmEnabled → $value")
            // Reconcile immediately: if Mumble is live and pre-arm just
            // got enabled, arm now. If just disabled, release now.
            if (mumbleSessionLive && value) {
                txController.armSessionMic()
            } else if (!value) {
                txController.disarmSessionMic()
            }
        }

    fun setCanSpeakOnSlot(
        slot: Int,
        canSpeak: Boolean,
    ) {
        if (slot in canSpeakOnSlot.indices) {
            canSpeakOnSlot[slot] = canSpeak
            Log.i(TAG, "setCanSpeakOnSlot slot=$slot canSpeak=$canSpeak")
            reconcileHotMic()
        }
    }

    /** Audit L4: forwarded from the plugin's onChannelChanged hook so
     *  AudioPlayback can drop late RX frames from the old channel that
     *  the server already had on the wire at the moment of the move. */
    fun notifyChannelMoved(slot: Int) {
        Log.i(TAG, "notifyChannelMoved slot=$slot — debouncing RX")
        audioPlayback.notifyChannelMoved()
    }

    /**
     * Hot Mic preference toggle from the operator. Reconciles
     * immediately: turning ON arms SCO if we're authorized to TX
     * right now; turning OFF disarms unless something else (active
     * burst, RX SCO_HOT) is keeping the link warm.
     */
    fun setHotMicEnabled(enabled: Boolean) {
        if (hotMicEnabled == enabled) return
        hotMicEnabled = enabled
        Log.i(TAG, "setHotMicEnabled($enabled)")
        reconcileHotMic()
    }

    /**
     * Decide whether SCO should be warm-held right now. Called whenever
     * a relevant input changes (Hot Mic toggle, canSpeak update,
     * Mumble session state). Idempotent — TxController.armHotMic /
     * disarmHotMic are safe to call when nothing changes.
     */
    private fun reconcileHotMic() {
        // Hot mic is a slot-0 (primary) concept — VS2 doesn't have its
        // own SCO. Arm only when the operator has both Hot Mic on AND
        // can transmit on the primary right now.
        val shouldArm = hotMicEnabled && canTransmitOnSlot(0)
        if (shouldArm) {
            txController.armHotMic()
        } else {
            txController.disarmHotMic()
        }
    }

    fun playTptPreview() {
        tptPlayer.play(currentTptTone, useScoRoute = false) {}
    }

    // ---- AINA ----

    fun connectAina(
        mac: String?,
        name: String?,
        kind: String?,
    ) {
        // Quick-win: log the resolved kind at the call site so field
        // logs distinguish "auto leaked through" from "operator
        // override applied". Routed via the MAC redactor per CLAUDE.md
        // sensitive-content rules.
        Log.i(
            TAG,
            "connectAina kind=$kind mac=${com.atakmap.android.xv.aina.redactMac(mac)}",
        )
        disconnectAina()
        val adapter = android.bluetooth.BluetoothAdapter.getDefaultAdapter()
        if (adapter == null) {
            Log.w(TAG, "connectAina: no Bluetooth adapter")
            return
        }
        val device =
            try {
                if (!mac.isNullOrBlank()) {
                    adapter.getRemoteDevice(mac)
                } else if (!name.isNullOrBlank()) {
                    val needle = name.lowercase()
                    adapter.bondedDevices?.firstOrNull {
                        (it.name ?: "").lowercase().contains(needle)
                    }
                } else {
                    null
                }
            } catch (t: Throwable) {
                Log.w(TAG, "connectAina: resolve threw", t)
                null
            }
        if (device == null) {
            Log.w(TAG, "connectAina: no device for mac=$mac name=$name")
            return
        }
        // Tell AudioRouter which BT device to prefer when multiple are
        // connected (e.g. AINA + AirPods). Without this hint,
        // preferredDevice() picks "first BT" which is non-deterministic.
        // The operator's BT-audio-override picker still wins over this
        // — it's an opt-in explicit override.
        router.preferredBtMacHint = device.address
        connectedAinaMac = device.address
        val resolvedKind =
            if (kind == "auto" || kind.isNullOrBlank()) {
                when (device.type) {
                    android.bluetooth.BluetoothDevice.DEVICE_TYPE_LE,
                    android.bluetooth.BluetoothDevice.DEVICE_TYPE_DUAL,
                    -> "v2"
                    else -> "v1"
                }
            } else {
                kind
            }
        // Per-reader onConn so the disconnect edge can tag the correct
        // [PttSource] when releasing a stuck PTT-down. Field bug
        // 2026-07-08: operator keyed AINA V2, turned BT off mid-TX,
        // burst never terminated because the reader's onConnectionState(
        // false) only flipped the UI dot — the button-down state in
        // PttDispatcher was left in place, so the OR-gate never saw an
        // empty held set and TxController.stop() was never called.
        // Passing the reader's own source into a synthetic forgetSource
        // on the down-edge of connection state ends the burst cleanly
        // (encoder flushes end-frame, TxController returns to IDLE),
        // and the operator has to re-key on a working transport (screen
        // PTT / phone mic) to speak again. We deliberately do NOT try
        // to reroute the in-flight burst to a different transport —
        // that produces audible mid-word swaps peers hate.
        val onConn: (source: PttSource, up: Boolean) -> Unit = { source, up ->
            Log.i(TAG, "AINA connection up=$up source=$source")
            callbacks.onAinaConnectionChanged(up)
            if (!up) {
                releaseStuckPttOnTransportDrop(source)
            }
        }
        when (resolvedKind) {
            "v1", "spp" -> {
                val r =
                    AinaSppReader(
                        context,
                        primaryAinaEvent(PttSource.AINA_V1),
                        { up -> onConn(PttSource.AINA_V1, up) },
                    )
                ainaSpp = r
                r.connect(device)
            }
            "v2", "ble" -> {
                val r =
                    AinaBleReader(
                        context,
                        primaryAinaEvent(PttSource.AINA_V2),
                        { up -> onConn(PttSource.AINA_V2, up) },
                    )
                ainaBle = r
                r.connect(device)
            }
            "ble-hid", "hid" -> {
                // Pryme BT-PTT-Z et al. — bonded as BLE peripheral but
                // not registered with Android's HidHostService, so
                // MediaSession capture sees nothing. Connect via GATT
                // directly and read the vendor button-state
                // characteristic (service 00420000-8f59-…, sourced from
                // VX 2.1.0). Mirrors AinaBleReader's connect / retry
                // strategy. Fires VS1 only.
                val r =
                    PrymeBleReader(
                        context,
                        primaryAinaEvent(PttSource.PRYME_BLE),
                        { up -> onConn(PttSource.PRYME_BLE, up) },
                    )
                prymeBle = r
                r.connect(device)
            }
            else -> Log.w(TAG, "unknown AINA kind '$resolvedKind'")
        }
    }

    /**
     * Connect an EXTERNAL BUTTON PTT input. Use case: motorcyclist
     * with an AINA V2 helmet speakermic AND a Pryme BLE PTT puck
     * mounted on the handlebar — they need both to be able to key
     * VS1 without one tearing the other down. Hard-locked to slot 0
     * (primary channel) and ignores PTTS/PTTE because the typical
     * handlebar-button use case is "talk on the main channel"; a
     * real second-channel split / emergency input would be its own
     * redesign. Independent disconnect via [disconnectExternalButton]
     * so the operator can swap the external button independently of
     * the primary.
     */
    fun connectExternalButton(
        mac: String?,
        name: String?,
        kind: String?,
    ) {
        Log.i(
            TAG,
            "connectExternalButton kind=$kind mac=${com.atakmap.android.xv.aina.redactMac(mac)}",
        )
        disconnectExternalButton()
        val adapter = android.bluetooth.BluetoothAdapter.getDefaultAdapter()
        if (adapter == null) {
            Log.w(TAG, "connectExternalButton: no Bluetooth adapter")
            return
        }
        val device =
            try {
                if (!mac.isNullOrBlank()) {
                    adapter.getRemoteDevice(mac)
                } else if (!name.isNullOrBlank()) {
                    val needle = name.lowercase()
                    adapter.bondedDevices?.firstOrNull {
                        (it.name ?: "").lowercase().contains(needle)
                    }
                } else {
                    null
                }
            } catch (t: Throwable) {
                Log.w(TAG, "connectExternalButton: resolve threw", t)
                null
            }
        if (device == null) {
            Log.w(TAG, "connectExternalButton: no device for mac=$mac name=$name")
            return
        }
        val resolvedKind =
            if (kind == "auto" || kind.isNullOrBlank()) {
                when (device.type) {
                    android.bluetooth.BluetoothDevice.DEVICE_TYPE_LE,
                    android.bluetooth.BluetoothDevice.DEVICE_TYPE_DUAL,
                    -> "v2"
                    else -> "v1"
                }
            } else {
                kind
            }
        // Mirror the primary path's per-source disconnect handling —
        // when the external-button transport drops mid-TX, forget the
        // held button so the burst can terminate. See the field-bug
        // note in [connectAina] above; the same failure mode applies
        // to a handlebar Pryme puck as to a helmet AINA.
        val onConn: (source: PttSource, up: Boolean) -> Unit = { source, up ->
            Log.i(TAG, "External button connection up=$up source=$source")
            // External button doesn't drive the AINA-connected dot in
            // the UI (that's the primary speakermic's indicator).
            // Logged only.
            if (!up) {
                releaseStuckPttOnTransportDrop(source)
            }
        }
        when (resolvedKind) {
            "v1", "spp" -> {
                val r =
                    AinaSppReader(
                        context,
                        externalButtonEvent(PttSource.AINA_V1),
                        { up -> onConn(PttSource.AINA_V1, up) },
                    )
                externalButtonSpp = r
                r.connect(device)
            }
            "v2", "ble" -> {
                val r =
                    AinaBleReader(
                        context,
                        externalButtonEvent(PttSource.AINA_V2),
                        { up -> onConn(PttSource.AINA_V2, up) },
                    )
                externalButtonBle = r
                r.connect(device)
            }
            "ble-hid", "hid" -> {
                val r =
                    PrymeBleReader(
                        context,
                        externalButtonEvent(PttSource.PRYME_BLE),
                        { up -> onConn(PttSource.PRYME_BLE, up) },
                    )
                externalButtonPrymeBle = r
                r.connect(device)
            }
            else -> Log.w(TAG, "connectExternalButton: unknown kind '$resolvedKind'")
        }
    }

    fun disconnectExternalButton() {
        externalButtonBle?.disconnect()
        externalButtonBle = null
        externalButtonSpp?.disconnect()
        externalButtonSpp = null
        // dispose() (not just disconnect()) so any late GATT callback
        // still queued behind the OS's binder cannot fire back into
        // VoicePlant after we swap in a fresh reader — otherwise a
        // stale up=false from the OLD PrymeBleReader can echo through
        // and log-spam / confuse mid-TX state. See fix/reader-
        // duplicate-disconnect-suppression: dispose() sets a sticky
        // flag on BitmaskGattPttReader that short-circuits both the
        // connection-state and button-event dispatch paths.
        externalButtonPrymeBle?.dispose()
        externalButtonPrymeBle = null
        // Clear any held-source bookkeeping the external button left
        // behind mid-burst — otherwise the OR-gate would think a press
        // is still in flight on a now-disconnected source.
        try {
            // Multiple sources might match; clear all the external-
            // button ones to be safe. Idempotent inside PttDispatcher.
            pttDispatcher.forgetSource(PttSource.AINA_V1)
            pttDispatcher.forgetSource(PttSource.AINA_V2)
            pttDispatcher.forgetSource(PttSource.PRYME_BLE)
        } catch (t: Throwable) {
            Log.w(TAG, "forgetSource on external-button disconnect threw", t)
        }
    }

    fun isExternalButtonConnected(): Boolean =
        externalButtonBle != null || externalButtonSpp != null || externalButtonPrymeBle != null

    /**
     * Start the Samsung ruggedized-device Active Key reader. Registers
     * a broadcast receiver for `HARD_KEY_REPORT` and routes press /
     * release edges through [pttDown] / [pttUp] with
     * [PttSource.SAMSUNG_ACTIVE_KEY]. Idempotent — repeated calls are
     * no-ops if the reader is already running.
     *
     * The plugin gates the call on
     * [com.atakmap.android.xv.util.SamsungActiveKey.isSupported] +
     * the operator's persisted toggle, so this method itself does not
     * re-verify hardware capability — starting on a non-Samsung device
     * is harmless (the broadcast just never fires).
     */
    fun startSamsungActiveKey() {
        if (samsungActiveKey != null) {
            Log.i(TAG, "startSamsungActiveKey: already running — ignoring")
            return
        }
        val reader =
            SamsungActiveKeyReader(context) { isDown, source ->
                if (isDown) {
                    pttDown(slot = 0, source = source)
                } else {
                    pttUp(slot = 0, source = source)
                }
            }
        if (reader.start()) {
            samsungActiveKey = reader
        } else {
            Log.w(TAG, "startSamsungActiveKey: reader.start() failed — leaving disabled")
        }
    }

    /**
     * Stop the Samsung Active Key reader (if running) and drop any
     * pending held-source bookkeeping from the dispatcher so a
     * mid-press "toggle-off" doesn't leave the OR-gate thinking the
     * button is still down. Idempotent.
     */
    fun stopSamsungActiveKey() {
        val reader = samsungActiveKey ?: return
        try {
            reader.stop()
        } catch (t: Throwable) {
            Log.w(TAG, "stopSamsungActiveKey: reader.stop() threw", t)
        }
        samsungActiveKey = null
        try {
            pttDispatcher.forgetSource(PttSource.SAMSUNG_ACTIVE_KEY)
        } catch (t: Throwable) {
            Log.w(TAG, "forgetSource(SAMSUNG_ACTIVE_KEY) threw", t)
        }
    }

    /** True while the Samsung Active Key reader is registered. */
    fun isSamsungActiveKeyRunning(): Boolean = samsungActiveKey?.isRunning() == true

    // ============================================================
    // Sonim ruggedized-device dedicated hardware buttons
    // ============================================================

    /**
     * Start the Sonim dedicated PTT side button reader. Registers a
     * broadcast receiver for `com.sonim.intent.action.PTT_KEY_DOWN/_UP`
     * and routes press / release edges through [pttDown] / [pttUp]
     * with [PttSource.SONIM_PTT]. Idempotent — repeated calls are
     * no-ops if the reader is already running.
     *
     * The plugin gates the call on
     * [com.atakmap.android.xv.util.SonimHardwareButtons.isSupported] +
     * the operator's persisted toggle, so this method itself does not
     * re-verify hardware capability — starting on a non-Sonim device
     * is harmless (the broadcast just never fires).
     */
    fun startSonimPttButton() {
        if (sonimPtt != null) {
            Log.i(TAG, "startSonimPttButton: already running — ignoring")
            return
        }
        val reader =
            SonimPttButtonReader(context) { isDown, source ->
                if (isDown) {
                    pttDown(slot = 0, source = source)
                } else {
                    pttUp(slot = 0, source = source)
                }
            }
        if (reader.start()) {
            sonimPtt = reader
        } else {
            Log.w(TAG, "startSonimPttButton: reader.start() failed — leaving disabled")
        }
    }

    /**
     * Stop the Sonim PTT reader (if running) and drop any pending
     * held-source bookkeeping from the dispatcher so a mid-press
     * "toggle-off" doesn't leave the OR-gate thinking the button is
     * still down. Idempotent.
     */
    fun stopSonimPttButton() {
        val reader = sonimPtt ?: return
        try {
            reader.stop()
        } catch (t: Throwable) {
            Log.w(TAG, "stopSonimPttButton: reader.stop() threw", t)
        }
        sonimPtt = null
        try {
            pttDispatcher.forgetSource(PttSource.SONIM_PTT)
        } catch (t: Throwable) {
            Log.w(TAG, "forgetSource(SONIM_PTT) threw", t)
        }
    }

    /** True while the Sonim PTT broadcast receiver is registered. */
    fun isSonimPttButtonRunning(): Boolean = sonimPtt?.isRunning() == true

    /**
     * Start the Sonim dedicated Emergency / SOS button reader.
     * Registers a broadcast receiver for `android.intent.action.SOS.down`
     * / `_up` and routes press / release edges through [pttDown] /
     * [pttUp] with [PttSource.SONIM_EMERGENCY]. Idempotent.
     *
     * Currently a plain PTT source with a distinct log tag / source
     * enum value — a future PR may promote it to fire an emergency
     * CoT event or SOS broadcast without disturbing the plain PTT
     * path.
     */
    fun startSonimEmergencyButton() {
        if (sonimEmergency != null) {
            Log.i(TAG, "startSonimEmergencyButton: already running — ignoring")
            return
        }
        val reader =
            SonimEmergencyButtonReader(context) { isDown, source ->
                if (isDown) {
                    pttDown(slot = 0, source = source)
                } else {
                    pttUp(slot = 0, source = source)
                }
            }
        if (reader.start()) {
            sonimEmergency = reader
        } else {
            Log.w(TAG, "startSonimEmergencyButton: reader.start() failed — leaving disabled")
        }
    }

    /** Stop the Sonim Emergency reader (if running). Idempotent. */
    fun stopSonimEmergencyButton() {
        val reader = sonimEmergency ?: return
        try {
            reader.stop()
        } catch (t: Throwable) {
            Log.w(TAG, "stopSonimEmergencyButton: reader.stop() threw", t)
        }
        sonimEmergency = null
        try {
            pttDispatcher.forgetSource(PttSource.SONIM_EMERGENCY)
        } catch (t: Throwable) {
            Log.w(TAG, "forgetSource(SONIM_EMERGENCY) threw", t)
        }
    }

    /** True while the Sonim Emergency broadcast receiver is registered. */
    fun isSonimEmergencyButtonRunning(): Boolean = sonimEmergency?.isRunning() == true

    fun disconnectAina() {
        disconnectAinaReaderOnly()
        // Drop the BT routing hint — no AINA selected means no implicit
        // BT preference. Operator's explicit override (if any) still wins.
        router.preferredBtMacHint = null
        connectedAinaMac = null
        // Notify the plugin so the UI dot can clear and the listener
        // chain (incl. any future reconnect attempts) sees the drop.
        try {
            callbacks.onAinaConnectionChanged(false)
        } catch (t: Throwable) {
            Log.w(TAG, "onAinaConnectionChanged(false) on disconnect threw", t)
        }
    }

    /**
     * Drop the primary AINA button reader ONLY. Leaves
     * [router.preferredBtMacHint] and [connectedAinaMac] intact so
     * the BT audio route the operator is on stays put. Used when the
     * operator flips the button-input protocol on the currently-
     * connected primary AINA to "no buttons / on-screen only" —
     * they still want the speakermic's HFP audio.
     *
     * Also used as the shared teardown step by [disconnectAina] and
     * the respawn-reader path (see [connectAina]) so all three call
     * sites drop the same reader fields and clear the same held-PTT
     * sources.
     */
    fun disconnectAinaReaderOnly() {
        ainaBle?.disconnect()
        ainaBle = null
        ainaSpp?.disconnect()
        ainaSpp = null
        // dispose() rather than disconnect() — see the analogous
        // block in [disconnectExternalButton] for the rationale.
        prymeBle?.dispose()
        prymeBle = null
        // Mirror [disconnectExternalButton]: if the operator (or a wholesale
        // BT-adapter-off cascade) is tearing us down mid-burst, drop any
        // held button state from the primary source so the OR-gate can
        // see an empty set and TxController.stop() runs. Idempotent
        // inside PttDispatcher; safe when nothing was held.
        try {
            pttDispatcher.forgetSource(PttSource.AINA_V1)
            pttDispatcher.forgetSource(PttSource.AINA_V2)
            pttDispatcher.forgetSource(PttSource.PRYME_BLE)
        } catch (t: Throwable) {
            Log.w(TAG, "forgetSource on primary reader teardown threw", t)
        }
    }

    /**
     * Reader-level release path: called from a reader's connection
     * callback when the transport dies (BLE GATT STATE_DISCONNECTED,
     * SPP RFCOMM socket closed / IOException) and the reader is NOT
     * doing an intentional disconnect. If [source] had a live PTT-down
     * on the dispatcher, this drops it — which through the OR-gate
     * fires TxController.stop() and lands a clean end-of-burst marker
     * on the wire.
     *
     * Fire-and-forget: this returns immediately so the reader's
     * teardown / reconnect scheduling isn't blocked. All the heavy
     * work inside PttDispatcher.forgetSource is a non-blocking
     * check-and-clear plus a single txController.stop() dispatch.
     *
     * Field bug 2026-07-08: AINA V2 held, BT toggled off mid-TX. The
     * reader's onConnectionStateChange(STATE_DISCONNECTED) fired but
     * the button-down state stayed pinned in PttDispatcher.heldButtons
     * — the OR-gate never saw an empty set, TxController stayed in
     * TRANSMITTING, and the transmit burst never ended. This helper
     * closes that gap.
     */
    private fun releaseStuckPttOnTransportDrop(source: PttSource) {
        try {
            Log.w(
                TAG,
                "PTT source $source died mid-transmission — forcing release of slot 0",
            )
            pttDispatcher.forgetSource(source)
        } catch (t: Throwable) {
            Log.w(TAG, "forgetSource on reader disconnect threw", t)
        }
    }

    /**
     * Plugin-level cascade for `BluetoothAdapter.STATE_TURNING_OFF` —
     * called from [com.atakmap.android.xv.plugin.XvMapComponent]'s
     * adapter-state receiver. In principle each active reader will
     * observe its own transport dying and fire the reader-level
     * release; in practice the OS can tear the BT stack down as a
     * whole before the readers get their individual GATT / RFCOMM
     * disconnect edges, so we cascade here as a belt-and-suspenders
     * safety net.
     *
     * Idempotent: forgetSource is a no-op when no PTT-down is
     * outstanding for that source. Never blocks — the receiver
     * fires on the main thread and this method returns immediately.
     */
    fun releaseAllBtSourcedPtt() {
        try {
            Log.w(
                TAG,
                "BluetoothAdapter STATE_TURNING_OFF — cascading PTT release across all BT sources",
            )
            pttDispatcher.forgetSource(PttSource.AINA_V1)
            pttDispatcher.forgetSource(PttSource.AINA_V2)
            pttDispatcher.forgetSource(PttSource.PRYME_BLE)
        } catch (t: Throwable) {
            Log.w(TAG, "releaseAllBtSourcedPtt threw", t)
        }
    }

    fun isAinaConnected(): Boolean = ainaBle != null || ainaSpp != null || prymeBle != null

    // Per-source button-handler factory for the primary input. Captures
    // [source] so PttDispatcher's OR-gate can distinguish concurrent
    // presses from different physical buttons (primary AINA + screen
    // PTT, primary AINA + external handlebar puck, etc.). MFB
    // (Voice Responder's multifunction call button) fires on RELEASE
    // only — single tap toggles the call state: answer if currently
    // ringing, hang up if currently active. Press-down is ignored so
    // there's no risk of half-press accidental dispatch.
    private fun primaryAinaEvent(source: PttSource): (AinaButton, Boolean) -> Unit =
        { btn, down ->
            Log.i(TAG, "${logPrefixForPttSource(source)} $btn down=$down source=$source")
            when (btn) {
                AinaButton.PTTE -> callbacks.onEmergencyButton(down)
                AinaButton.PTT -> if (down) pttDown(0, source) else pttUp(0, source)
                AinaButton.PTTS -> if (down) pttDown(1, source) else pttUp(1, source)
                AinaButton.MFB -> if (!down) callbacks.onCallButtonTapped()
                else -> { /* unmapped */ }
            }
        }

    // External-button input handler factory. Hard-locked to slot 0
    // (primary channel) and ignores PTTS / PTTE / MFB because the
    // typical external-button use case is a motorcyclist's handlebar
    // puck: "let me key the main channel from a second physical button
    // I already have on the bike." Anything more nuanced (a real
    // second input with full per-channel/emergency capability) is a
    // separate redesign — out of scope for the "low-risk multi-PTT"
    // the user asked for.
    private fun externalButtonEvent(source: PttSource): (AinaButton, Boolean) -> Unit =
        { btn, down ->
            Log.i(TAG, "${logPrefixForPttSource(source)} $btn down=$down source=$source")
            when (btn) {
                AinaButton.PTT -> if (down) pttDown(0, source) else pttUp(0, source)
                else -> { /* external button does not drive PTTS/PTTE/MFB */ }
            }
        }

    // ---- RX path ----

    // Per-(slot, speakerName) decoder. Opus state is conversation-
    // specific — feeding frames from speaker A through a decoder that
    // last decoded speaker B's frames produces garbage. The original
    // in-plugin MumbleTransport keyed decoders by (slot, speakerSession)
    // — we mirror that here using the speakerName string the plugin
    // pushes across, which is "mumble:<slot>:<sessionId>".
    private val perSpeakerDecoders: MutableMap<String, OpusDecoder> =
        java.util.concurrent.ConcurrentHashMap()

    fun onRxOpus(
        slot: Int,
        opus: ByteArray,
        speakerName: String?,
    ) {
        val key = speakerName ?: "slot:$slot"
        val dec = perSpeakerDecoders.getOrPut(key) { opusDecoderFactory() }
        val pcm =
            try {
                dec.decode(opus)
            } catch (t: Throwable) {
                Log.w(TAG, "decode failed for $key", t)
                return
            }
        if (pcm.isNotEmpty()) {
            audioPlayback.playPcm(pcm)
        }
    }

    // ---- Lifecycle ----

    fun shutdown() {
        try {
            context.unregisterReceiver(bondStateReceiver)
        } catch (_: Throwable) {
        }
        try {
            com.atakmap.android.xv.telecom.ActiveCallRegistry
                .removeRouteListener(telecomRouteListener)
        } catch (_: Throwable) {
        }
        try {
            pttDispatcher.release()
        } catch (_: Throwable) {
        }
        try {
            txController.shutdown()
        } catch (_: Throwable) {
        }
        try {
            audioPlayback.shutdown()
        } catch (_: Throwable) {
        }
        try {
            tptPlayer.stop()
        } catch (_: Throwable) {
        }
        try {
            disconnectAina()
        } catch (_: Throwable) {
        }
        try {
            disconnectExternalButton()
        } catch (_: Throwable) {
        }
        try {
            stopSamsungActiveKey()
        } catch (_: Throwable) {
        }
        try {
            stopSonimPttButton()
        } catch (_: Throwable) {
        }
        try {
            stopSonimEmergencyButton()
        } catch (_: Throwable) {
        }
        try {
            router.stop()
        } catch (_: Throwable) {
        }
        try {
            scoLink.forceStop()
        } catch (_: Throwable) {
        }
        try {
            // De-register before tearing down btPolicy so we don't
            // race with a final fan-out on shutdown.
            btPolicy.removeConnectListener(ainaA2dpWiring)
        } catch (_: Throwable) {
        }
        try {
            ainaA2dpWiring.stop()
        } catch (_: Throwable) {
        }
        try {
            // Restores A2DP policy on every device we forbade during
            // this lifetime. Leaving the AINA permanently forbidden
            // after XV unloads would be a hostile side effect.
            ainaA2dpController.stop()
        } catch (_: Throwable) {
        }
        try {
            btPolicy.stop()
        } catch (_: Throwable) {
        }
        try {
            audioController.shutdown()
        } catch (_: Throwable) {
        }
        perSpeakerDecoders.values.forEach {
            try {
                it.close()
            } catch (_: Throwable) {
            }
        }
        perSpeakerDecoders.clear()
    }

    companion object {
        private const val TAG = "XvVoicePlant"

        // Operator-facing message for the cellular-call PTT gate. Kept
        // short and imperative — a Toast has ~2-3 seconds of attention
        // and needs to say what to do, not just what happened.
        internal const val CELLULAR_BLOCK_TOAST_MSG =
            "Cellular call active — hang up before XV PTT"
    }
}
