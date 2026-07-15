package com.atakmap.android.xv.plugin

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import android.widget.Toast
import com.atakmap.android.ipc.AtakBroadcast
import com.atakmap.android.maps.AbstractMapComponent
import com.atakmap.android.maps.MapView
import com.atakmap.android.xv.aina.AinaBleReader
import com.atakmap.android.xv.aina.AinaButton
import com.atakmap.android.xv.aina.AinaSppReader
import com.atakmap.android.xv.audio.AudioRouter
import com.atakmap.android.xv.audio.OutputRoute
import com.atakmap.android.xv.audio.TptPlayer
import com.atakmap.android.xv.audio.TptTone
import com.atakmap.android.xv.debug.DebugReceiver
import com.atakmap.android.xv.emergency.AtakEmergencyDispatcher
import com.atakmap.android.xv.emergency.EmergencyController
import com.atakmap.android.xv.mission.MissionChannelProvisioner
import com.atakmap.android.xv.presence.XvChannel
import com.atakmap.android.xv.presence.XvCotListener
import com.atakmap.android.xv.presence.XvCotPublisher
import com.atakmap.android.xv.presence.XvPresenceRegistry
import com.atakmap.android.xv.transport.MumbleTransport
import com.atakmap.android.xv.transport.TransportConfig
import com.atakmap.android.xv.transport.TransportListener
import com.atakmap.android.xv.transport.VoiceFrame
import com.atakmap.android.xv.transport.VoiceTransport
import com.atakmap.android.xv.transport.VxCompat
import com.atakmap.android.xv.transport.multicast.MeshVoiceManager
import com.atakmap.android.xv.transport.multicast.MulticastMeshLeg
import com.atakmap.android.xv.transport.mumble.MumbleAuth
import com.atakmap.android.xv.transport.mumble.TakServerDiscovery
import com.atakmap.android.xv.ui.XvDropDownReceiver

class XvMapComponent : AbstractMapComponent() {
    // Voice path lives in XvVoiceService (XV's UID). The plugin only
    // keeps a thin wrapper for things that genuinely belong on the ATAK
    // side: route preference (UI spinner), A2DP forbid (Lock-AINA
    // toggle), and status-tone playback (channel join/leave chirps go
    // through USAGE_MEDIA in this process). Everything else
    // (AudioController / AudioPlayback / BtAudioPolicy / ScoLink /
    // TxController / PttDispatcher) lives in the service — see
    // VoicePlant. The plugin-side shadows used to exist as dormant
    // duplicates from the cross-UID migration; deleted in the H11
    // cleanup.
    private var audioRouter: AudioRouter? = null

    // Self-managed Telecom bridge. Lives in the plugin process; its
    // counterpart (XvConnectionService) lives in our APK's process.
    // When non-null and TelecomManager is available, Mumble channel
    // join → Telecom call placement, channel leave → call end. The
    // system handles audio focus + media pause + BT route privileges
    // for the duration. Null on devices/builds where Telecom rejected
    // self-managed registration (rare).
    private var callBridge: com.atakmap.android.xv.telecom.XvCallBridge? = null

    // Plugin-side facade for XvVoiceService. The service runs in our
    // APK's process (separate UID from ATAK) and hosts the voice
    // plant — AudioRecord, AudioTrack, SCO, AINA readers, codec, PTT
    // state machine. We bind to it on plugin load so AudioRecord runs
    // in a UID with FOREGROUND_SERVICE_TYPE_MICROPHONE granted, which
    // is what makes background PTT actually work.
    //
    // Constructed in onCreate, started + bound there. Migration of
    // audio code into the service is incremental — for the first
    // landing, the service is alive (foreground) but most audio is
    // still in-plugin. Subsequent commits move classes into the
    // service.
    private var voiceClient: com.atakmap.android.xv.service.XvVoiceClient? = null
    private var tptPlayer: TptPlayer? = null
    private var statusTones: com.atakmap.android.xv.audio.StatusTones? = null
    private var dropDown: XvDropDownReceiver? = null

    // Persistent settings (SharedPreferences accessors). Constructed
    // with a prefs-provider lambda so accesses gracefully degrade to
    // defaults during the brief window before heldMapView is attached.
    // Extracted from XvMapComponent during the L5+L6 split.
    private val settings: XvSettings =
        XvSettings(
            prefsProvider = {
                heldMapView?.context?.getSharedPreferences(XvSettings.PREFS_NAME, Context.MODE_PRIVATE)
            },
        )

    // Last audio route label pushed by the service. Updated on every
    // onAudioRouteChanged callback; read by the controller's
    // currentAudioRouteLabel() so the main view's audio indicator can
    // render synchronously without round-tripping AIDL on every refresh.
    @Volatile
    private var lastAudioRouteLabel: String = "Auto"

    // Last label we toasted, used to dedupe rapid identical updates
    // (e.g. the route listener firing twice during a single transition).
    @Volatile
    private var lastToastedRouteLabel: String = ""

    // Forces ReconnectingMumbleTransport to retry now (or tear down
    // an orphaned live socket) when Android's default network handle
    // changes — wifi→LTE handoff, IP rotation, etc. Without it, voice
    // sits silently on a dead TCP socket for 30-60s until the kernel
    // gives up. Lifecycle is bound to startMumbleInternal /
    // stopActiveTransport so we don't leak a callback after disconnect.
    private var networkWatcher: com.atakmap.android.xv.transport.NetworkAvailabilityWatcher? = null

    @Volatile
    private var currentTptTone: TptTone = TptTone.DEFAULT

    @Volatile
    private var lastAinaMac: String? = null

    // Last external-button (AINA / Pryme / BLE PTT) MAC the plugin
    // asked the service to bind, and a best-effort cached connect
    // state for the UI. The service is the source of truth; we just
    // need a snapshot for the picker status row so it doesn't have to
    // round-trip AIDL on every refresh.
    @Volatile
    private var lastExternalButtonMac: String? = null

    @Volatile
    private var lastExternalButtonConnected: Boolean = false

    // Resolved BluetoothDevice for the currently-connected AINA, set in
    // connectAinaInternal and cleared in disconnectAinaInternal.
    @Volatile
    private var currentAinaDevice: BluetoothDevice? = null

    // Resolved reader kind ("v1" / "v2" / "ble-hid") the current
    // primary AINA reader was constructed under. Null when no reader
    // is running (either disconnected or torn down via
    // [voiceClient.disconnectAinaReaderOnly] because the operator
    // flipped button-protocol to "audio only"). Set at the tail of
    // [connectAinaInternal] and cleared in [disconnectAinaInternal] /
    // the teardown-only branch. Read by [setAinaButtonProtocolInternal]
    // to decide whether an operator kind flip needs a reader respawn.
    @Volatile
    private var currentAinaReaderKind: String? = null

    // Re-entry guard for [setAinaButtonProtocolInternal]. The kind-
    // change path calls [connectAinaInternal] whose SPP-→-BLE probe
    // (AinaProtocolProbe) can itself re-enter [connectAinaInternal]
    // when the probe resolves BLE. Without this guard, an operator
    // kind flip that races the probe could double-teardown /
    // double-respawn the reader. Reset in a finally so an exception
    // in the connect path doesn't wedge the guard on.
    @Volatile
    private var reInitingReaderKind: Boolean = false

    // Re-entry guard for [setSelectedExternalButtonInternal]. Same
    // rationale as [reInitingReaderKind] but scoped to the External
    // Button slot: the RESPAWN branch tears the old reader down
    // (via voiceClient.disconnectExternalButton) and immediately
    // re-attaches under the new MAC (via voiceClient.connectExternalButton),
    // and we don't want a concurrent picker tap racing that sequence
    // to double-teardown or leave a stale reader stitched to a
    // disposed GATT client. Reset in a finally so an exception in
    // the tear-down / respawn path doesn't wedge the guard on.
    @Volatile
    private var reInitingExternalButtonReader: Boolean = false

    // V2-AINA SDP-cache-gap probe — see [AinaProtocolProbe]. Lazily
    // constructed against the held plugin context.
    @Volatile
    private var ainaProbe: com.atakmap.android.xv.aina.AinaProtocolProbe? = null

    // The most recently-joined slot-0 channel name + id, formatted as
    // "<name>#<id>" for use as the Telecom call's caller-id. Null when
    // no channel is joined. The Telecom call only spans active voice,
    // not channel membership — see onChannelChanged.
    @Volatile
    private var currentChannelTag: String? = null

    // Slot the operator most recently requested TX on (via Controller.
    // startTx). Captured at PTT-down so when the service-side
    // onPttStateChanged(true) edge fires we know which slot is on the
    // air. -1 = nobody pressed yet.
    @Volatile
    private var lastRequestedTxSlot: Int = -1

    // Whichever slot is currently transmitting after PRIMING+TPT
    // complete, or -1 if idle. Set in the AIDL listener's
    // onPttStateChanged(true) edge and cleared on the false edge.
    // Read by isTransmittingOnSlot for UI feedback.
    @Volatile
    private var txActiveSlot: Int = -1

    // Caller-side answer timeout. Posted right after the outgoing
    // REQUEST_CALL is sent; cleared when the callee joins the temp
    // channel (onPeerAcceptedCall) OR when the caller hangs up locally
    // OR when CANCEL_CALL/REJECT_CALL arrives. If the timeout actually
    // fires, the callee never answered and we tear the call down our
    // own side (sending CANCEL_CALL to clean up the peer's ring state
    // for free). Without this, the operator was stuck on the in-call
    // screen indefinitely waiting for a callee who never picked up.
    @Volatile
    private var pendingAnswerTimeoutCancel: (() -> Unit)? = null

    private fun cancelPendingAnswerTimeout(reason: String) {
        val c = pendingAnswerTimeoutCancel ?: return
        pendingAnswerTimeoutCancel = null
        try {
            c()
            Log.i(TAG, "answer timeout cancelled: $reason")
        } catch (t: Throwable) {
            Log.w(TAG, "cancelPendingAnswerTimeout threw", t)
        }
    }

    // Caller-side ringback cancel hook. Stored as a field so ANY
    // teardown path can stop the 3s ringback loop (system hangup,
    // CANCEL_CALL/REJECT_CALL from peer, answer-timeout fire, etc.) —
    // previously the cancel only lived in closures scoped to
    // startDirectCallInternal, so a Telecom-side hangup left the
    // ringback looping forever (verified 2026-05-11: Surface caller
    // stuck firing CALL_RINGBACK every 6s for 50+ seconds after the
    // call dropped).
    @Volatile
    private var pendingRingbackCancel: (() -> Unit)? = null

    private fun cancelPendingRingback(reason: String) {
        val c = pendingRingbackCancel ?: return
        pendingRingbackCancel = null
        try {
            c()
            Log.i(TAG, "ringback cancelled: $reason")
        } catch (t: Throwable) {
            Log.w(TAG, "cancelPendingRingback threw", t)
        }
    }

    // CoT call addressing — peer's device UID for the active call. Set
    // when an outgoing call is dialed (startDirectCallInternal) OR an
    // incoming CoT REQUEST is received. Cleared on call-end so the
    // next outgoing CANCEL doesn't address a stale peer. Used to fire
    // CANCEL / REJECT CoT signals from the teardown paths, which only
    // have the Mumble session id otherwise. The Mumble session is fine
    // for the call audio (channel-speak inside the temp), but call
    // signaling rides CoT now and CoT addresses by deviceUid.
    @Volatile
    private var currentCallPeerCotUid: String? = null

    @Volatile
    private var currentCallTempChannelName: String? = null

    @Volatile
    private var currentCallTempChannelId: Int? = null

    /**
     * Handle a route-change push from the service. Updates the cached
     * label (so the main view's controller-side getter returns the
     * fresh value on its next paint), forces an immediate UI refresh,
     * and surfaces a toast on the transition when it's an
     * operationally-relevant change — Mumble has to be connected (no
     * point yelling about BT plug/unplug when XV is idle) and the
     * label has to have actually moved from what we last toasted.
     */
    private fun onAudioRouteChangedFromService(label: String) {
        val prev = lastAudioRouteLabel
        lastAudioRouteLabel = label
        // Refresh the main view immediately. The dropdown might be on
        // a different screen state right now; refreshNow no-ops in
        // that case.
        try {
            dropDown?.refreshNow()
        } catch (t: Throwable) {
            Log.w(TAG, "route change: refreshNow threw", t)
        }
        // Toast only when XV is actually doing something. Plugin's
        // mumbleTransport() returns non-null when there's a live
        // session. We could also gate on "in a channel" but plug/unplug
        // during reconnect is meaningful information too.
        if (mumbleTransport() == null) return
        if (label == lastToastedRouteLabel) return
        if (prev == label) return
        lastToastedRouteLabel = label
        val ctx = heldPluginContext ?: heldMapView?.context ?: return
        val message =
            if (isExternalDeviceLabel(prev) && !isExternalDeviceLabel(label)) {
                // External device just went away. Most surprising
                // transition — call it out explicitly.
                "$prev disconnected — audio: $label"
            } else if (!isExternalDeviceLabel(prev) && isExternalDeviceLabel(label)) {
                "$label connected"
            } else {
                "Audio: $label"
            }
        Log.i(TAG, "route toast: '$prev' → '$label' ($message)")
        try {
            Toast.makeText(ctx, message, Toast.LENGTH_SHORT).show()
        } catch (t: Throwable) {
            Log.w(TAG, "route toast threw", t)
        }
    }

    private fun isExternalDeviceLabel(label: String): Boolean =
        // Built-in routes the OS picks when nothing better is around.
        // Everything else (named BT product, wired headset, USB) is
        // operator-attached.
        label != "Auto" && label != "Speaker" && label != "Earpiece"

    private fun sendCallSignal(action: String): Boolean {
        val peerUid = currentCallPeerCotUid
        val channelName = currentCallTempChannelName
        if (peerUid.isNullOrBlank() || channelName.isNullOrBlank()) {
            Log.d(TAG, "sendCallSignal($action): no active CoT call context — skipping")
            return false
        }
        val ourCallsign =
            try {
                com.atakmap.android.maps.MapView
                    .getMapView()
                    ?.deviceCallsign
            } catch (_: Throwable) {
                null
            } ?: "XV"
        val ourUid =
            com.atakmap.android.xv.transport.mumble.MumbleAuth
                .deviceUid() ?: "XV-UNKNOWN"
        return com.atakmap.android.xv.calling.XvCallSignals.send(
            com.atakmap.android.xv.calling.XvCallSignals.Signal(
                action = action,
                tempChannelName = channelName,
                tempChannelId = currentCallTempChannelId,
                callerUid = ourUid,
                calleeUid = peerUid,
                callerCallsign = ourCallsign,
            ),
        )
    }

    private fun clearCallSignalContext() {
        currentCallPeerCotUid = null
        currentCallTempChannelName = null
        currentCallTempChannelId = null
    }

    /**
     * Handle an inbound `b-x-xv-call` CoT signal addressed to us.
     * Called on the main thread by the [XvCotListener] hop.
     */
    private fun handleIncomingCallSignal(signal: com.atakmap.android.xv.calling.XvCallSignals.Signal) {
        when (signal.action) {
            com.atakmap.android.xv.calling.XvCallSignals.ACTION_REQUEST -> {
                handleIncomingCotRequest(signal)
            }
            com.atakmap.android.xv.calling.XvCallSignals.ACTION_CANCEL,
            com.atakmap.android.xv.calling.XvCallSignals.ACTION_REJECT,
            -> {
                Log.i(
                    TAG,
                    "incoming CoT ${signal.action} from=${signal.callerUid} " +
                        "channel='${signal.tempChannelName}' — ending local call",
                )
                // Either the caller hung up (CANCEL) or peer declined
                // our outgoing call (REJECT). Either way, tear down
                // locally. endChannelCall is idempotent.
                voiceClient?.ifBound { it.endChannelCall() }
            }
            else -> {
                Log.w(TAG, "incoming CoT call signal: unknown action '${signal.action}'")
            }
        }
    }

    private fun handleIncomingCotRequest(signal: com.atakmap.android.xv.calling.XvCallSignals.Signal) {
        // Dedupe: TAK Server can deliver the same CoT REQUEST twice
        // (retransmission via separate inputs/outputs). Without this
        // guard, the second REQUEST creates a second Telecom incoming
        // connection, the XvConnectionService tears down the first
        // ("tearing down prior private connection before ringing"),
        // and the resulting half-torn-down state means the operator's
        // Answer / Decline taps land on a dead connection. Verified
        // 2026-05-11 Pixel: two REQUEST events 54 ms apart for the
        // same temp channel.
        //
        // Match by tempChannelName — the unique discriminator that's
        // stable across retransmissions of the same call invitation.
        // A subsequent CANCEL/REJECT will clear the context.
        if (currentCallTempChannelName == signal.tempChannelName) {
            Log.d(
                TAG,
                "incoming CoT REQUEST for '${signal.tempChannelName}' — already handling, ignoring duplicate",
            )
            return
        }
        val transport = mumbleTransport()
        if (transport == null) {
            Log.w(TAG, "incoming CoT REQUEST: no Mumble transport — auto-rejecting")
            // We can't join the temp channel; let the caller know.
            val ourCallsign =
                try {
                    com.atakmap.android.maps.MapView
                        .getMapView()
                        ?.deviceCallsign
                } catch (_: Throwable) {
                    null
                } ?: "XV"
            val ourUid =
                com.atakmap.android.xv.transport.mumble.MumbleAuth
                    .deviceUid() ?: "XV-UNKNOWN"
            com.atakmap.android.xv.calling.XvCallSignals.send(
                com.atakmap.android.xv.calling.XvCallSignals.Signal(
                    action = com.atakmap.android.xv.calling.XvCallSignals.ACTION_REJECT,
                    tempChannelName = signal.tempChannelName,
                    tempChannelId = signal.tempChannelId,
                    callerUid = ourUid,
                    calleeUid = signal.callerUid,
                    callerCallsign = ourCallsign,
                ),
            )
            return
        }
        // Resolve the temp channel id. The caller's signal carries
        // both the name and (best-effort) the id — names are stable
        // across Mumble reconnects, ids are not, so we re-resolve
        // by name now to be safe.
        val resolvedId =
            transport.primarySession()?.channelIdByName(signal.tempChannelName)
                ?: signal.tempChannelId
        if (resolvedId == null || resolvedId < 0) {
            Log.w(
                TAG,
                "incoming CoT REQUEST: temp channel '${signal.tempChannelName}' not yet in roster — " +
                    "deferring (Mumble may catch up via ChannelState soon)",
            )
            // TODO: queue + retry on awaitChannelByName. For now, the
            // caller's 30s ring timeout protects against this.
            return
        }
        // Track this call's CoT context so subsequent
        // REJECT / CANCEL replies can be addressed correctly.
        currentCallPeerCotUid = signal.callerUid
        currentCallTempChannelName = signal.tempChannelName
        currentCallTempChannelId = resolvedId
        // Find which slot tracks this channel — same routing the old
        // VX TextMessage path used (REQUEST_CALL's Mumble session
        // determined which slot was the call-holder). With CoT we
        // don't have a session-id binding yet, so we default to VS1
        // primary unless the slot picker exposes a name-based lookup.
        // Slot 0 is sufficient until VS2-targeted calls return.
        val callSlotIdx = 0
        val callerCallsign =
            signal.callerCallsign.takeIf { it.isNotBlank() } ?: signal.callerUid.take(8)
        Log.i(
            TAG,
            "incoming CoT REQUEST: caller='$callerCallsign' uid=${signal.callerUid} " +
                "channel='${signal.tempChannelName}' id=$resolvedId — ringing operator on slot=$callSlotIdx",
        )
        // Note the call to MumbleTransport so the audio plant gates
        // PTT etc. We're the CALLEE here. preCallChannelId is our
        // current channel — restored on hangup.
        val preCall = transport.joinedChannelId()
        transport.notePrivateCallStarted(
            callSlotIdx = callSlotIdx,
            peerSession = -1, // CoT signaling doesn't carry the Mumble session id
            tempChannelId = resolvedId,
            preCallChannelId = preCall,
            isCaller = false,
        )
        // Fire the Telecom incoming-call UI. The voice service's
        // existing notifyIncomingCall path drives the activity +
        // ringtone + answer/decline broadcast plumbing.
        voiceClient?.ifBound {
            it.notifyIncomingCall(callerCallsign, resolvedId, -1)
        }
    }

    // Last-known secondary (VS2) channel name. Volatile-only for now;
    // promoting to SharedPreferences when the settings UI lands.
    @Volatile
    private var lastSecondaryChannel: String? = null

    @Volatile
    private var heldMapView: MapView? = null

    @Volatile
    private var heldPluginContext: Context? = null

    // Deterministic per-(device, slot) suffix used as the `---<suffix>`
    // tail of our Mumble usernames. Suffix is a function of
    // MapView.getDeviceUid() so the same device produces the same
    // identity across reconnects AND app reinstalls — eliminating the
    // historical "ghost user" pile-up where each fresh APK install
    // minted a new random UUID. We use TWO slots (VS1/VS2) so the
    // primary and secondary Mumble sessions have distinct identities;
    // both ride the same TAK enrollment cert and callsign.
    private val mumblePrimarySlotSuffix: String
        get() =
            com.atakmap.android.xv.transport.mumble.MumbleInstallId
                .primarySuffix()
    private val mumbleSecondarySlotSuffix: String
        get() =
            com.atakmap.android.xv.transport.mumble.MumbleInstallId
                .secondarySuffix()

    // Last channel name we saw each Mumble slot in, used to publish a
    // combined `<__xv><channels>` list on CoT. Maintained from the
    // (slot, id, name) callback in MumbleTransport.
    private val joinedChannelsBySlot = java.util.concurrent.ConcurrentHashMap<Int, com.atakmap.android.xv.presence.XvChannel>()

    // Per-slot listen-only state. True when the server has us
    // suppressed on that slot (OTS direction OUT or admin mute).
    // Driven by MumbleTransport.onSelfSuppressedChanged. The UI uses
    // this to show "(listen-only)" badges on the PTT card header.
    private val selfSuppressedBySlot = java.util.concurrent.ConcurrentHashMap<Int, Boolean>()
    private var debugReceiver: DebugReceiver? = null
    private var showReceiver: BroadcastReceiver? = null
    private var missionReceiver: BroadcastReceiver? = null

    // BluetoothAdapter STATE_ON receiver — re-runs the auto-connect
    // paths for AINA + External Button when the operator toggles BT
    // back on (or after a device reboot completes and BT settles).
    // Field-observed 2026-07-11: after a BT off → on cycle, XV sat
    // idle indefinitely waiting for someone to nudge reconnect —
    // operator saw the speakermic + external button appear "connected"
    // in system Bluetooth but neither delivered PTT events to XV until
    // a picker touch or app restart forced the auto-connect flow.
    // XvVoiceService handles the OFF direction (releaseAllBtSourcedPtt
    // on STATE_TURNING_OFF); this handles the ON direction.
    private var btAdapterStateReceiver: BroadcastReceiver? = null
    private val autoReconnectHandler = android.os.Handler(android.os.Looper.getMainLooper())

    // Debounce: some OEM BT stacks fire ACTION_STATE_CHANGED with
    // STATE_ON more than once as adapter init completes. Also gives
    // the profile proxies + bond cache a moment to settle before we
    // ask the readers to attach.
    private val autoReconnectRunnable =
        Runnable {
            try {
                Log.i(TAG, "BT STATE_ON — re-running auto-connect for AINA + External Button")
                autoConnectAina()
                autoConnectExternalButton()
            } catch (t: Throwable) {
                Log.w(TAG, "auto-reconnect on BT STATE_ON threw", t)
            }
        }
    private var activeTransport: VoiceTransport? = null

    // Mesh-voice activation layer (multicast failover, bridge election,
    // offline discovery). Null until [startMeshVoice] runs; survives
    // Mumble teardown by design (that IS the failover case). Owns its
    // own multicast legs, key elections, and the ~1 Hz tick below.
    private var meshVoiceManager: MeshVoiceManager? = null
    private val meshTickHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val meshTickRunnable =
        object : Runnable {
            override fun run() {
                try {
                    feedMumbleLivenessToMesh()
                } catch (t: Throwable) {
                    Log.w(TAG, "mumble liveness feed threw", t)
                }
                try {
                    meshVoiceManager?.tick()
                } catch (t: Throwable) {
                    Log.w(TAG, "mesh tick threw", t)
                }
                try {
                    reconcileMissionChannels()
                } catch (t: Throwable) {
                    Log.w(TAG, "mission-channel reconcile threw", t)
                }
                try {
                    snapshotChannelDirectory()
                } catch (t: Throwable) {
                    Log.w(TAG, "channel-directory snapshot threw", t)
                }
                meshTickHandler.postDelayed(this, MESH_TICK_MS)
            }
        }

    // Auto-provisions the primary voice channel from the operator's
    // active ATAK mission (opt-in via settings). Pure policy lives in
    // MissionChannelProvisioner; this plugin drives it from the 1 Hz
    // tick above and executes its actions against the Mumble transport.
    private var missionChannelProvisioner: MissionChannelProvisioner? = null

    // Ordered active-mission names, primary first. Fed by the documented
    // SET_MISSIONS broadcast (fleet automation / the ATAK Data Sync hook
    // publish it); empty means "no mission", which the provisioner treats
    // as "leave the operator's channel alone".
    @Volatile
    private var activeMissionNames: List<String> = emptyList()

    // Last Unavailable channel we toasted, so a server that keeps
    // refusing creation doesn't re-toast every reconcile cycle.
    private var lastMissionUnavailableToast: String? = null

    // Host string of the currently-running Mumble transport, or null when
    // disconnected. Used by the multi-server picker to short-circuit a
    // reconnect when the operator picks the host we're already on.
    private var activeMumbleHost: String? = null
    private var ainaBle: AinaBleReader? = null
    private var ainaSpp: AinaSppReader? = null
    private var emergency: EmergencyController? = null

    // Foreground-KeyEvent fallback for the Samsung Active Key. Attached
    // to the MapView's OnKeyListener only on Samsung ruggedized
    // hardware AND when the operator has enabled the feature. On any
    // non-Samsung device this stays null and the OnKeyListener is
    // never added. Required for Tab Active5 / SM-X308U-class firmware
    // where the HARD_KEY_REPORT broadcast is not emitted — the plain
    // KeyEvent is the only signal. Foreground-only by construction
    // (InputDispatcher routes non-broadcast keys to the top activity);
    // the broadcast path in the service handles background PTT on
    // firmware that emits it.
    @Volatile
    private var samsungActiveKeyFg: com.atakmap.android.xv.ptt.SamsungActiveKeyForegroundReader? = null

    // Foreground-KeyEvent fallback readers for the two Sonim
    // ruggedized-device dedicated hardware buttons (XP10 / XP9900 and
    // XP-family peers). Attached to the MapView's OnKeyListener only
    // on Sonim hardware AND when the operator has enabled the
    // corresponding toggle. On any non-Sonim device these stay null
    // and no OnKeyListener is ever added. Required because Sonim
    // firmware may deliver the dedicated PTT / SOS keys via KeyEvent
    // regardless of the operator's Programmable Keys mapping —
    // catching that path means "just works" for the operator's first
    // press without visiting Sonim system settings. Foreground-only
    // by construction (InputDispatcher routes non-broadcast keys to
    // the top activity); the broadcast paths in the service handle
    // background PTT on firmware that emits them.
    @Volatile
    private var sonimPttFg: com.atakmap.android.xv.ptt.SonimPttForegroundReader? = null

    @Volatile
    private var sonimEmergencyFg: com.atakmap.android.xv.ptt.SonimEmergencyForegroundReader? = null

    // Assigned-app broadcast reader (Sonim Programmable Keys → ATAK
    // mode). Runs in ATAK's process so the pkg-scoped intents Sonim
    // fires (pkg=com.atakmap.app.civ) reach it. Dispatch is
    // unconditional — there is no XV toggle for the assigned-app path;
    // the phone's Programmable Keys → app assignment is the
    // authoritative on/off (if a key isn't assigned to ATAK no broadcast
    // arrives and the callback never fires). A single reader instance
    // covers both PTT (Yellow) and Emergency (SOS+Kodiak) routing. See
    // SonimAssignedAppReader kdoc for the full mapping.
    @Volatile
    private var sonimAssignedApp: com.atakmap.android.xv.ptt.SonimAssignedAppReader? = null

    private var presenceRegistry: XvPresenceRegistry? = null
    private var presencePublisher: XvCotPublisher? = null
    private var presenceListener: XvCotListener? = null

    override fun onCreate(
        context: Context,
        intent: Intent,
        mapView: MapView,
    ) {
        // ATAK plugin quirk: context.applicationContext is NULL in the
        // plugin loader environment (no plugin Application is
        // instantiated). Use the passed-in `context` directly — it's the
        // plugin's own Context.
        val pluginContext = context
        heldMapView = mapView
        heldPluginContext = pluginContext

        // primary_channel is read on connect (connectMumbleWithDefaults)
        // to restore the operator's last-joined channel and override the
        // server-side default placement. Don't wipe it here — that was
        // the old behavior when this design rule had been deleted, and
        // it left operators landing in REACT after every restart.

        // ATAK plugin context's applicationContext is NULL — that breaks
        // AudioManager.setMode (NPE on getOpPackageName) which silently
        // leaves us in MODE_NORMAL and starves the BT SCO mic of capture
        // flow. Use the MapView's (ATAK Activity) context for everything
        // audio-related. Resolved up front so the AudioController and
        // every other audio-touching object below get the working
        // context — building AudioController with pluginContext crashed
        // on PTT-down once enterTx() was wired in (NPE inside setMode).
        val atakContext = mapView.context

        // atakContext (not pluginContext) for the same SharedPreferences
        // ownership reason as prefs() — plugin context can't write to its
        // own data dir from inside ATAK's process.
        val router = AudioRouter(atakContext)
        audioRouter = router

        // TptPlayer in the plugin process is for STATUS chirps only
        // (channel join / leave / voice-lost). They route via
        // USAGE_MEDIA so they don't need SCO and play through
        // whichever speaker is current. The voice TPT (the "permit"
        // tone before TX) lives in the service alongside the rest of
        // the audio plant.
        val tpt = TptPlayer(context = atakContext)
        tptPlayer = tpt
        // Prime the USAGE_MEDIA AudioTrack pipeline so the first
        // status chirp doesn't pay HAL cold-start latency.
        tpt.primeMediaPath()
        statusTones =
            com.atakmap.android.xv.audio.StatusTones(
                tptPlayer = tpt,
                enabled = { settings.persistedStatusTonesEnabled() },
            )

        emergency = EmergencyController(AtakEmergencyDispatcher())

        // Foreground voice plant in our APK's process. Bound now so
        // it's alive and promoted before any AINA / PTT activity. The
        // service hosts AudioRecord (and the rest of the voice plant
        // as the migration progresses) in a UID that holds the
        // microphone foreground privilege — which is the entire point
        // of the split process model.
        val client =
            com.atakmap.android.xv.service
                .XvVoiceClient(atakContext)
        client.start()
        voiceClient = client

        // Self-managed Telecom bridge — proxies through voiceClient
        // because XvConnectionService is BIND_TELECOM_CONNECTION_-
        // SERVICE-gated (Telecom-only access from outside processes)
        // and the plugin's UID doesn't hold MANAGE_OWN_CALLS. Both
        // Telecom operations land inside our service's UID, where
        // both privileges actually apply.
        callBridge =
            com.atakmap.android.xv.telecom
                .XvCallBridge(client)

        // Service → plugin callbacks: TX Opus the plugin pushes onto
        // the Mumble TCP socket (still in plugin since cert lookup
        // needs ATAK runtime), terminator frames, PTT-state edges.
        val listenerStub =
            object : com.atakmap.android.xv.service.IXvVoiceListener.Stub() {
                override fun onTxOpus(
                    slot: Int,
                    opus: ByteArray?,
                ) {
                    if (opus == null) return
                    try {
                        sendOpusToActiveTransport(opus, slot)
                    } catch (th: Throwable) {
                        Log.w(TAG, "sendOpus from service threw", th)
                    }
                }

                override fun onTxTerminator(slot: Int) {
                    try {
                        sendTerminatorToActiveTransport(slot)
                    } catch (th: Throwable) {
                        Log.w(TAG, "sendTerminator from service threw", th)
                    }
                }

                override fun onPttStateChanged(
                    transmitting: Boolean,
                    slot: Int,
                ) {
                    if (transmitting) {
                        beginMumbleVoiceBurst()
                        // Lock in the UI's "transmitting" state on whichever
                        // slot the service reports going hot. The service
                        // passes the slot directly through the AIDL callback
                        // so BT-speakermic / BLE-button PTTs (which never
                        // route through the plugin's Controller.startTx and
                        // therefore never populate lastRequestedTxSlot) still
                        // light the correct on-screen indicator. Field-
                        // observed 2026-07-07 on the umbrella branch: BT
                        // speakermic TX left the "HOLD TO TX" label
                        // showing instead of "● TRANSMITTING" because
                        // lastRequestedTxSlot was stale at -1.
                        //
                        // Slot 1 (secondary) without a live VS2 session
                        // is a legitimate operator setup — the transport
                        // already handles it in pickSlotForSend by
                        // routing the audio to the primary. Mirror that
                        // fallback here so the on-screen VS1 button
                        // lights instead of leaving no indicator at all
                        // (the VS2 button is hidden / disabled when
                        // secondaryConnected() is false). Keeps the UI
                        // in sync with the wire behavior.
                        val secondaryLive =
                            mumbleTransport()?.secondaryConnected() == true
                        txActiveSlot =
                            if (slot == 1 && !secondaryLive) 0 else slot
                    } else {
                        txActiveSlot = -1
                    }
                    // Force immediate UI refresh — the dropdown is on a 2s
                    // poll which felt sluggish for the TX-state edge. The
                    // PTT button needs to flip to "● TRANSMITTING" the
                    // moment audio actually goes on the air.
                    dropDown?.refreshNow()
                    // Telecom call placement / teardown is now handled
                    // SERVICE-side (synchronously with pttDown / TX-stop)
                    // so it claims media focus before SCO comes up.
                    // Plugin still does the Mumble-protocol burst-start
                    // signaling via beginMumbleVoiceBurst() above.
                }

                override fun onAinaConnectionChanged(connected: Boolean) {
                    Log.i(TAG, "service: AINA connection changed connected=$connected")
                }

                override fun onRxActivity() {
                    // Legacy hook from when capture pre-warm was driven
                    // by RX activity. Service-side capture handles its
                    // own lifecycle now; nothing to do.
                }

                override fun onAudioStateText(text: String?) {
                    Log.d(TAG, "service: audio state=$text")
                }

                override fun onEmergencyButton(down: Boolean) {
                    // EmergencyController lives on the plugin side because
                    // its dispatcher (AtakEmergencyDispatcher) calls into
                    // ATAK's EmergencyManager — that API is reachable only
                    // from ATAK's own UID.
                    Log.i(TAG, "service: emergency button down=$down")
                    try {
                        emergency?.onEmergencyButton(down)
                    } catch (t: Throwable) {
                        Log.w(TAG, "emergency dispatch threw", t)
                    }
                }

                override fun onIncomingCallAnswered(
                    tempChannelId: Int,
                    callerSession: Int,
                ) {
                    val transport = mumbleTransport()
                    if (transport == null) {
                        Log.w(TAG, "answer: no Mumble transport — operator must reconnect")
                        return
                    }
                    // Look up which slot's identity the REQUEST_CALL was
                    // addressed to — that's the slot whose session has
                    // to JOIN the temp channel. Without this, joining
                    // through primary even when VS2 received the call
                    // means the wrong identity ends up on the call's
                    // roster and the caller's REQUEST_CALL target sees
                    // their callee as still-not-joined.
                    val callSlotIdx = transport.slotForCallChannel(tempChannelId)
                    if (callSlotIdx < 0) {
                        Log.w(
                            TAG,
                            "answer: no slot tracking tempChannelId=$tempChannelId — " +
                                "falling back to VS1",
                        )
                        transport.joinChannel(tempChannelId)
                        return
                    }
                    Log.i(
                        TAG,
                        "service: incoming call answered on slot=$callSlotIdx — " +
                            "tempChannelId=$tempChannelId callerSession=$callerSession",
                    )
                    transport.joinChannelForSlot(tempChannelId, callSlotIdx)
                }

                override fun onIncomingCallRejected(
                    tempChannelId: Int,
                    callerSession: Int,
                ) {
                    val transport = mumbleTransport()
                    if (transport == null) {
                        Log.w(TAG, "reject: no live Mumble transport — caller will time out")
                        return
                    }
                    Log.i(
                        TAG,
                        "service: incoming call rejected — sending CoT REJECT to caller " +
                            "uid=${currentCallPeerCotUid ?: "<unknown>"}",
                    )
                    // CoT REJECT — the caller's plugin listens on the
                    // same CoT pipeline and tears down. callerSession
                    // is the legacy Mumble session id (kept in the
                    // signature for compatibility but unused now).
                    sendCallSignal(com.atakmap.android.xv.calling.XvCallSignals.ACTION_REJECT)
                    // Clear the slot's call state since we never joined.
                    transport.notePrivateCallEnded()
                    clearCallSignalContext()
                }

                override fun onAudioRouteChanged(label: String?) {
                    val l = label ?: "Auto"
                    this@XvMapComponent.onAudioRouteChangedFromService(l)
                }

                override fun onCaptureError(reason: String?) {
                    // Audit H5 (tail). Surface mic-capture failure
                    // (almost always RECORD_AUDIO revoked) as an
                    // operator-actionable Toast. Without this the
                    // operator just hears dead air — the failure is
                    // silent at the UI layer even though logcat has
                    // the cause.
                    val msg = "XV mic: ${reason ?: "capture failed"}"
                    Log.w(TAG, "onCaptureError from service: $msg")
                    val target = MapView.getMapView() ?: return
                    try {
                        target.post {
                            android.widget.Toast
                                .makeText(target.context, msg, android.widget.Toast.LENGTH_LONG)
                                .show()
                        }
                    } catch (t: Throwable) {
                        Log.w(TAG, "capture-error toast threw", t)
                    }
                }

                override fun onPttBlockedByCellularCall(reason: String?) {
                    // Service side suppressed a PTT press because the
                    // cellular telephony stack reports an active or
                    // ringing call. Without this Toast the operator
                    // sees no visible feedback for the press and
                    // reasonably assumes XV is broken. Service already
                    // throttles the fire so we do not stack toasts on
                    // rapid button mashes.
                    val msg = reason ?: "Cellular call active — hang up before XV PTT"
                    Log.i(TAG, "onPttBlockedByCellularCall: $msg")
                    val target = MapView.getMapView() ?: return
                    try {
                        target.post {
                            android.widget.Toast
                                .makeText(target.context, msg, android.widget.Toast.LENGTH_LONG)
                                .show()
                        }
                    } catch (t: Throwable) {
                        Log.w(TAG, "cellular-block toast threw", t)
                    }
                }

                override fun onPrivateCallEnded() {
                    cancelPendingAnswerTimeout("call ended")
                    cancelPendingRingback("call ended")
                    val transport = mumbleTransport()
                    if (transport == null) {
                        Log.w(TAG, "onPrivateCallEnded: no transport — nothing to clean up")
                        return
                    }
                    val ctx = transport.activeCallContext()
                    if (ctx == null) {
                        Log.d(TAG, "onPrivateCallEnded: no active call to clean up")
                        return
                    }
                    Log.i(
                        TAG,
                        "onPrivateCallEnded: slot=${ctx.slotIdx} peer=${ctx.peerSession} " +
                            "isCaller=${ctx.isCaller} — sending CANCEL_CALL + clearing state",
                    )
                    // CoT CANCEL — both sides notify each other on
                    // hangup. The other side's CoT listener tears down
                    // their Telecom call. Idempotent on the receiving
                    // side: if they've already detected our leave via
                    // UserState observation, the duplicate CANCEL is
                    // a no-op.
                    sendCallSignal(com.atakmap.android.xv.calling.XvCallSignals.ACTION_CANCEL)
                    transport.notePrivateCallEnded()
                    clearCallSignalContext()
                    // Reset currentChannelTag so the next outgoing PTT
                    // doesn't mistakenly re-place a private-call Telecom
                    // call against the (now ended) peer.
                    if (currentChannelTag?.startsWith("→ ") == true ||
                        currentChannelTag?.startsWith("← ") == true
                    ) {
                        currentChannelTag = null
                    }
                    // Belt-and-suspenders UI refresh — the transport's
                    // rejoin-to-pre-call-channel will trigger
                    // onChannelChanged which already calls refreshNow,
                    // but the rejoin's UserState round-trip can land
                    // 50-200ms after this listener fires. Push an
                    // immediate refresh so the operator doesn't see
                    // "TAK PRIVATE - …" lingering on the PTT card
                    // during the gap.
                    dropDown?.refreshNow()
                }
            }
        client.setListener(listenerStub)

        // Push the persisted settings into the service so its
        // PttDispatcher / StatusTones / TPT etc. start in the same
        // state the user left them. setPersistent caches each value
        // under a stable name so a binderDied → fresh-bind sequence
        // replays them automatically — without that, a service crash
        // would leave the new instance at hard-coded defaults until
        // the operator next touched a setting.
        client.setPersistent("latchedMode") { it.setLatchedMode(settings.persistedLatchedMode()) }
        client.setPersistent("pttTimeoutSec") { it.setPttTimeoutSec(settings.persistedPttTimeout()) }
        client.setPersistent("latchedTimeoutSec") { it.setLatchedTimeoutSec(settings.persistedLatchedTimeout()) }
        client.setPersistent("statusTonesEnabled") { it.setStatusTonesEnabled(settings.persistedStatusTonesEnabled()) }
        // Seed the in-memory TPT preference from disk before pushing it
        // to the service. Without this, every fresh plugin process
        // started at TptTone.DEFAULT (ASTRO_25) regardless of what the
        // operator had picked in Settings.
        currentTptTone = settings.persistedTptTone()
        client.setPersistent("tptTone") { it.setTptTone(currentTptTone.name) }
        client.setPersistent("hotMicMode") { it.setHotMicEnabled(settings.persistedHotMicMode()) }
        // One-shot read so the audio-route indicator paints correctly
        // on first show without waiting for the first
        // onAudioRouteChanged push. The service-side listener still
        // drives subsequent updates.
        client.ifBound { svc ->
            try {
                val label = svc.audioRouteLabel ?: "Auto"
                lastAudioRouteLabel = label
                dropDown?.refreshNow()
            } catch (t: Throwable) {
                Log.w(TAG, "initial audioRouteLabel read threw", t)
            }
        }

        // The DebugReceiver surface is debug-only. The release manifest
        // doesn't declare the receiver and we skip the dynamic register
        // here too — any caller broadcasting xv.debug.* against a
        // release APK gets nothing. Without this gate, any installed
        // app could broadcast MUMBLE_CONNECT / TX_START / AINA_CONNECT
        // and drive XV remotely.
        if (!com.atakmap.android.xv.BuildConfig.DEBUG) {
            Log.i(TAG, "release build — skipping DebugReceiver registration")
        } else {
            DebugReceiver.handler =
                object : DebugReceiver.DebugCommandHandler {
                    override fun startMulticast(
                        group: String,
                        port: Int,
                        label: String,
                    ) = startMulticastInternal(pluginContext, group, port, label)

                    override fun stopMulticast() = stopActiveTransport()

                    override fun setMeshVoiceEnabled(enabled: Boolean) {
                        settings.persistMeshVoiceEnabled(enabled)
                        Log.i(TAG, "meshVoiceEnabled = $enabled (legs reconcile on next tick)")
                    }

                    override fun dumpMeshStatus() {
                        val m = meshVoiceManager
                        if (m == null) {
                            Log.i(TAG, "mesh: manager not started (no device UID at init?)")
                            return
                        }
                        Log.i(
                            TAG,
                            "mesh: enabled=${settings.persistedMeshVoiceEnabled()} " +
                                "txActive=${m.isMeshTxActive()} bridging=${m.isBridging()} " +
                                "badge=${m.statusBadge() ?: "-"}",
                        )
                        val legs = m.activeLegs()
                        val stats = m.legStats()
                        if (legs.isEmpty()) {
                            Log.i(TAG, "mesh: no active legs")
                        } else {
                            legs.forEach { (channel, ep) ->
                                Log.i(
                                    TAG,
                                    "mesh: leg '$channel' → ${ep.groupAddress}:${ep.port} ${stats[channel].orEmpty()}",
                                )
                            }
                        }
                        stats.keys
                            .filter { it !in legs.keys }
                            .forEach { Log.i(TAG, "mesh: leg '$it' ${stats[it]}") }
                        m.discoveredChannels().forEach {
                            Log.i(TAG, "mesh: discovered '${it.name}' ${it.group}:${it.port} via ${it.viaCallsign} (${it.viaUid})")
                        }
                    }

                    override fun exportCommsPlan(passphrase: String?) = exportCommsPlanInternal(passphrase)

                    override fun importCommsPlan(
                        planText: String,
                        passphrase: String?,
                    ) = importCommsPlanInternal(planText, passphrase)

                    override fun describeAudioState(): String = "service"

                    override fun connectAina(
                        mac: String?,
                        name: String?,
                        kind: String,
                    ) = connectAinaInternal(pluginContext, mac, name, kind)

                    override fun disconnectAina() = disconnectAinaInternal()

                    override fun listBonded() = listBondedInternal()

                    override fun setAinaProtocolOverride(
                        mac: String,
                        proto: String?,
                    ) {
                        settings.persistAinaProtocolOverride(mac, proto)
                        // If the caller is targeting the currently-
                        // selected primary AINA, route through the
                        // reader-lifecycle path so a live device gets
                        // its reader respawned under the new
                        // protocol immediately. Non-primary MACs
                        // (secondary picks, unbonded scratch devices)
                        // still get the pure persist path — they'll
                        // pick up the override on the next connect
                        // edge. Otherwise the debug intent path had
                        // the same "persistence-only" bug as the UI
                        // spinner: setting the override on a live
                        // primary needed a manual disconnect / reconnect
                        // to take effect.
                        val primary = settings.persistedAinaMac()
                        if (primary != null && primary.equals(mac, ignoreCase = true)) {
                            val kindEnum =
                                when (proto?.lowercase()) {
                                    "v1", "spp" ->
                                        com.atakmap.android.xv.aina.AinaDeviceInfo.ButtonProtocol.SPP
                                    "v2", "ble" ->
                                        com.atakmap.android.xv.aina.AinaDeviceInfo.ButtonProtocol.BLE
                                    "ble-hid", "hid" ->
                                        com.atakmap.android.xv.aina.AinaDeviceInfo.ButtonProtocol.BLE_HID
                                    // null / blank / "auto" clear the override; the
                                    // reader-lifecycle path treats null as "no reader"
                                    // and either tears the reader down or leaves it
                                    // alone. On a subsequent connect edge, "auto"
                                    // classification runs.
                                    else -> null
                                }
                            setAinaButtonProtocolInternal(kindEnum)
                        }
                        Log.i(
                            TAG,
                            "setAinaProtocolOverride($mac, $proto): persisted; effective on next connect",
                        )
                    }

                    override fun connectMumble(
                        host: String?,
                        takPattern: String?,
                        port: Int,
                        channel: String,
                        secondaryChannel: String?,
                        vxCompat: String?,
                    ) = startMumbleInternal(
                        explicitHost = host,
                        takPattern = takPattern,
                        port = port,
                        channel = channel,
                        secondaryChannel = secondaryChannel,
                        vxCompatOverride = parseVxCompat(vxCompat),
                    )

                    override fun setSecondaryChannel(channelName: String) = setSecondaryChannelInternal(channelName)

                    override fun setDefaultVxCompat(mode: String?) {
                        val parsed = parseVxCompat(mode)
                        if (parsed == null) {
                            Log.w(TAG, "MUMBLE_VX_COMPAT: bad mode '$mode' (use off|hybrid|strict)")
                            return
                        }
                        defaultVxCompat = parsed
                        Log.i(TAG, "defaultVxCompat = $parsed (takes effect next connect)")
                    }

                    override fun setAutoAcceptPrivateCalls(enabled: Boolean) {
                        autoAcceptPrivateCalls = enabled
                        Log.i(TAG, "autoAcceptPrivateCalls = $enabled (live)")
                    }

                    override fun dumpPresence() {
                        val reg = presenceRegistry
                        if (reg == null) {
                            Log.i(TAG, "presence: registry not initialized")
                            return
                        }
                        val peers = reg.all().toList()
                        val now = System.currentTimeMillis()
                        Log.i(TAG, "presence: ${peers.size} peer(s) known")
                        if (peers.isEmpty()) return
                        for (p in peers.sortedBy { it.deviceUid }) {
                            val ageS = (now - p.lastSeenMs) / 1000
                            val freshness = if (reg.isFresh(p.deviceUid, now)) "FRESH" else "STALE"
                            val chans =
                                if (p.channels.isEmpty()) {
                                    "[]"
                                } else {
                                    p.channels.joinToString(prefix = "[", postfix = "]") {
                                        "${it.name}(id=${it.id}${if (it.keyEpoch > 0) ",ke=${it.keyEpoch}" else ""})"
                                    }
                                }
                            val fp = p.certFingerprint?.take(16)?.let { "$it…" } ?: "-"
                            Log.i(
                                TAG,
                                "  $freshness uid=${p.deviceUid} ver=${p.version} caps=${p.capabilities} " +
                                    "server=${p.server ?: "-"} certFp=$fp channels=$chans age=${ageS}s",
                            )
                        }
                    }

                    override fun disconnectMumble() = stopActiveTransport()

                    override fun listTakServers() = TakServerDiscovery.logAll()

                    override fun joinMumbleChannel(
                        channelName: String?,
                        channelId: Int,
                    ) = joinMumbleChannelInternal(channelName, channelId)

                    override fun setAudioRoute(routeName: String) {
                        audioRouter?.route = OutputRoute.fromName(routeName)
                    }

                    override fun startTx() {
                        dispatchPttDown(slot = 0)
                    }

                    override fun stopTx() {
                        dispatchPttUp(slot = 0)
                    }
                }
            debugReceiver = DebugReceiver()
            val filter =
                IntentFilter().apply {
                    addAction(DebugReceiver.ACTION_START_MULTICAST)
                    addAction(DebugReceiver.ACTION_STOP_MULTICAST)
                    addAction(DebugReceiver.ACTION_MESH_VOICE)
                    addAction(DebugReceiver.ACTION_MESH_STATUS)
                    addAction(DebugReceiver.ACTION_MESH_PLAN_EXPORT)
                    addAction(DebugReceiver.ACTION_MESH_PLAN_IMPORT)
                    addAction(DebugReceiver.ACTION_AUDIO_STATE)
                    addAction(DebugReceiver.ACTION_AINA_CONNECT)
                    addAction(DebugReceiver.ACTION_AINA_DISCONNECT)
                    addAction(DebugReceiver.ACTION_AINA_LIST_BONDED)
                    addAction(DebugReceiver.ACTION_AINA_SET_PROTOCOL)
                    addAction(DebugReceiver.ACTION_MUMBLE_CONNECT)
                    addAction(DebugReceiver.ACTION_MUMBLE_DISCONNECT)
                    addAction(DebugReceiver.ACTION_MUMBLE_SET_SECONDARY)
                    addAction(DebugReceiver.ACTION_MUMBLE_VX_COMPAT)
                    addAction(DebugReceiver.ACTION_MUMBLE_AUTO_ACCEPT)
                    addAction(DebugReceiver.ACTION_PRESENCE_DUMP)
                    addAction(DebugReceiver.ACTION_MUMBLE_LIST_TAK)
                    addAction(DebugReceiver.ACTION_MUMBLE_JOIN)
                    addAction(DebugReceiver.ACTION_SET_AUDIO_ROUTE)
                    addAction(DebugReceiver.ACTION_TX_START)
                    addAction(DebugReceiver.ACTION_TX_STOP)
                }
            @Suppress("UnspecifiedRegisterReceiverFlag")
            pluginContext.registerReceiver(debugReceiver, filter, Context.RECEIVER_EXPORTED)
        } // end if (BuildConfig.DEBUG)

        // Tool-drawer tap → main XV control panel via DropDownReceiver.
        // Inflation uses the plugin's own Context (NOT mapView.getContext)
        // so resources resolve from XV's APK.
        val dd = XvDropDownReceiver(mapView, pluginContext, buildController())
        dropDown = dd
        showReceiver =
            object : BroadcastReceiver() {
                override fun onReceive(
                    c: Context,
                    i: Intent,
                ) {
                    dd.show()
                }
            }
        AtakBroadcast.getInstance().registerReceiver(
            showReceiver,
            AtakBroadcast.DocumentedIntentFilter(XvTool.SHOW_XV, "Show XV's main panel"),
        )

        // Mission-context input for auto-channels. A fleet MDM, the ATAK
        // Data Sync tool, or a companion plugin broadcasts the operator's
        // active missions (ordered, primary first) so XV can drive the
        // primary voice channel to match. Extras: "missions" as a
        // String[] or a delimited String (newline / comma / semicolon).
        missionReceiver =
            object : BroadcastReceiver() {
                override fun onReceive(
                    c: Context,
                    i: Intent,
                ) {
                    if (i.action != SET_MISSIONS) return
                    val list =
                        i.getStringArrayExtra("missions")?.toList()
                            ?: i.getStringExtra("missions")?.split('\n', ',', ';')
                            ?: emptyList()
                    setActiveMissions(list)
                }
            }
        AtakBroadcast.getInstance().registerReceiver(
            missionReceiver,
            AtakBroadcast.DocumentedIntentFilter(SET_MISSIONS, "Set XV's active missions for auto-channel provisioning"),
        )

        btAdapterStateReceiver =
            object : BroadcastReceiver() {
                override fun onReceive(
                    ctx: Context,
                    intent: Intent,
                ) {
                    if (intent.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
                    val state =
                        intent.getIntExtra(
                            BluetoothAdapter.EXTRA_STATE,
                            BluetoothAdapter.ERROR,
                        )
                    if (state != BluetoothAdapter.STATE_ON) return
                    // Debounce: coalesce repeated STATE_ON broadcasts
                    // (OEM stack quirk) into a single reconnect pass,
                    // and give profile proxies + bond cache ~800 ms
                    // to settle before the readers try to attach.
                    autoReconnectHandler.removeCallbacks(autoReconnectRunnable)
                    autoReconnectHandler.postDelayed(autoReconnectRunnable, BT_STATE_ON_RECONNECT_DELAY_MS)
                }
            }
        try {
            // Explicit RECEIVER_NOT_EXPORTED — on API 34+ (target 34
            // here), registering a 2-arg receiver for a system
            // broadcast without a flag throws SecurityException.
            // Field-observed 2026-07-11: the flag-less call was
            // silently caught by our try/catch and the receiver
            // never registered, so STATE_ON never triggered the
            // auto-reconnect path.
            androidx.core.content.ContextCompat.registerReceiver(
                pluginContext,
                btAdapterStateReceiver!!,
                IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
                androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            Log.i(TAG, "btAdapterStateReceiver registered — will auto-reconnect readers on STATE_ON")
        } catch (t: Throwable) {
            Log.w(TAG, "registerReceiver(btAdapterStateReceiver) threw", t)
        }

        // XV-native peer discovery via CoT detail. Publishes a `<__xv>`
        // element on our self-CoT and listens for the same element on
        // others' CoT to build a registry of XV-callable peers.
        // Independent of Mumble — works even when Mumble is down.
        startPresenceLayer()
        // Mesh voice sits alongside presence — both work Mumble-down.
        // The manager no-ops internally until the operator enables the
        // mesh-voice master toggle, so starting it unconditionally is
        // free and lets a mid-session enable bring legs up on the next
        // tick without a plugin reload.
        startMeshVoice()

        Log.i(TAG, "XV loaded — voice plant lives in service")
        // One-shot diagnostic at load: dump the bonded-device picker
        // result so the next logcat pull shows what the operator's
        // device sees without needing UI navigation. Cheap (one BT
        // adapter call); the noise is bounded by however many BT
        // devices the operator has paired.
        try {
            val snap = listBondedAinaDevices()
            Log.i(TAG, "load-time picker snapshot: ${snap.size} input device(s) selectable")
            for (d in snap) Log.i(TAG, "  picker: ${d.displayLabel()} mac=${d.mac}")
        } catch (t: Throwable) {
            Log.w(TAG, "load-time picker snapshot threw", t)
        }
        Toast
            .makeText(
                context,
                "XV ${com.atakmap.android.xv.BuildConfig.VERSION_NAME} loaded",
                Toast.LENGTH_SHORT,
            ).show()

        // Auto-connect to default Mumble server/channel
        autoConnectMumble()

        // Auto-connect to a bonded AINA speakermic if one is present.
        // V1/V2 generation is auto-detected by BluetoothDevice.getType()
        // inside connectAinaInternal. Without this, on a fresh ATAK launch
        // the user has to manually open Settings → "Connect AINA" before
        // the speakermic's PTT / PTTE buttons produce events — that's
        // wrong for hardware the user has explicitly bonded.
        autoConnectAina()
        // Restore the operator's external button (e.g. handlebar
        // Pryme puck for a motorcyclist whose helmet AINA is the
        // primary). No-op when no external button is persisted.
        autoConnectExternalButton()

        // Samsung ruggedized-device Active Key. Zero-cost on any
        // non-Samsung device: `SamsungActiveKey.isSupported()` returns
        // false, this method exits before touching the service, and
        // the corresponding Settings row stays hidden. On Samsung
        // Tab Active5 / XCover6 Pro / etc. + operator opt-in, the
        // service registers the `HARD_KEY_REPORT` broadcast receiver
        // and the side key keys slot 0 through the normal dispatcher.
        autoStartSamsungActiveKeyIfEnabled()

        // Sonim ruggedized-device dedicated hardware buttons (XP10 /
        // XP9900 and XP-family peers). Zero-cost on any non-Sonim
        // device: SonimHardwareButtons.isSupported() returns false,
        // this method exits before touching the service, and the
        // corresponding Settings rows stay hidden. On Sonim hardware +
        // operator opt-in, the service registers the broadcast
        // receivers and the ATAK-process foreground reader attaches
        // its OnKeyListener so both signal paths coexist.
        autoStartSonimButtonsIfEnabled()

        // Trigger runtime permission prompts for our own UID. The
        // MapComponent runs in ATAK's process; checkSelfPermission here
        // would query ATAK's grants, masking the fact that XV's own UID
        // hasn't been granted. PermissionRequestActivity runs in our UID
        // and prompts for what's actually missing for us.
        maybeRequestPermissions(pluginContext, mapView.context)
    }

    /**
     * Permissions XV declares + needs that are NOT currently granted to
     * our UID, mapped to short user-friendly labels for surfacing in the
     * dropdown banner. Empty when everything is granted. Called on
     * dropdown open as the H5 "permission revocation" surface — silent
     * SecurityException at TX time was confusing operators who'd
     * removed mic/BT permission via Android settings between sessions.
     */
    private fun toast(message: String) {
        val ctx = heldMapView?.context ?: heldPluginContext ?: return
        try {
            Toast.makeText(ctx, message, Toast.LENGTH_LONG).show()
        } catch (t: Throwable) {
            Log.w(TAG, "toast failed: ${t.message}")
        }
    }

    private fun currentlyMissingPermissions(): List<String> {
        val ctx = heldPluginContext ?: return emptyList()
        val pm = ctx.packageManager
        val pkg = ctx.packageName
        val checks =
            mutableListOf<Pair<String, String>>(
                Manifest.permission.RECORD_AUDIO to "Microphone",
                Manifest.permission.CALL_PHONE to "Phone",
            )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            checks += Manifest.permission.BLUETOOTH_CONNECT to "Bluetooth"
            checks += Manifest.permission.BLUETOOTH_SCAN to "Bluetooth scan"
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            checks += Manifest.permission.POST_NOTIFICATIONS to "Notifications"
        }
        return checks
            .filter {
                pm.checkPermission(it.first, pkg) != PackageManager.PERMISSION_GRANTED
            }.map { it.second }
    }

    private fun maybeRequestPermissions(
        pluginContext: Context,
        launcherContext: Context,
    ) {
        val pm = pluginContext.packageManager
        val want = mutableListOf<String>()
        want += Manifest.permission.RECORD_AUDIO
        // CALL_PHONE — Telecom's placeCall enforces this on Android
        // 14+ even for self-managed VoIP accounts. Without it, our
        // service's TelecomManager.placeCall throws SecurityException.
        want += Manifest.permission.CALL_PHONE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            want += Manifest.permission.BLUETOOTH_CONNECT
            want += Manifest.permission.BLUETOOTH_SCAN
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            want += Manifest.permission.POST_NOTIFICATIONS
        }
        val pkg = pluginContext.packageName
        val anyMissing =
            want.any {
                pm.checkPermission(it, pkg) != PackageManager.PERMISSION_GRANTED
            }
        if (!anyMissing) return
        try {
            val i =
                Intent(pluginContext, PermissionRequestActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            launcherContext.startActivity(i)
        } catch (t: Throwable) {
            Log.w(TAG, "could not launch PermissionRequestActivity via launcher; trying plugin ctx", t)
            try {
                val i =
                    Intent(pluginContext, PermissionRequestActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                pluginContext.startActivity(i)
            } catch (t2: Throwable) {
                Log.e(TAG, "PermissionRequestActivity failed via both contexts", t2)
            }
        }
    }

    override fun onDestroyImpl(
        context: Context,
        mapView: MapView,
    ) {
        // Foreground-KeyEvent fallback paths: detach the OnKeyListeners
        // BEFORE we drop heldMapView. Each also fires a defensive up()
        // through the AIDL so the service's dispatcher can't strand
        // SAMSUNG_ACTIVE_KEY / SONIM_PTT / SONIM_EMERGENCY in
        // heldButtons if the plugin unloads while the operator was
        // mid-press.
        try {
            stopSamsungActiveKeyForeground()
        } catch (_: Throwable) {
        }
        try {
            stopSonimPttForeground()
        } catch (_: Throwable) {
        }
        try {
            stopSonimEmergencyForeground()
        } catch (_: Throwable) {
        }
        try {
            stopSonimAssignedApp()
        } catch (_: Throwable) {
        }
        stopMeshVoice()
        presenceListener?.stop()
        presenceListener = null
        presencePublisher?.stop()
        presencePublisher = null
        presenceRegistry?.clear()
        presenceRegistry = null
        stopActiveTransport()
        disconnectAinaInternal()
        DebugReceiver.handler = null
        debugReceiver?.let {
            try {
                // Same plugin-context-only rule that applies in onCreate:
                // do NOT use context.applicationContext (it's null in the
                // plugin loader env).
                context.unregisterReceiver(it)
            } catch (_: IllegalArgumentException) {
                // Already unregistered.
            }
        }
        showReceiver?.let {
            try {
                AtakBroadcast.getInstance().unregisterReceiver(it)
            } catch (_: IllegalArgumentException) {
            }
        }
        missionReceiver?.let {
            try {
                AtakBroadcast.getInstance().unregisterReceiver(it)
            } catch (_: IllegalArgumentException) {
            }
        }
        missionReceiver = null
        btAdapterStateReceiver?.let {
            try {
                context.unregisterReceiver(it)
            } catch (_: IllegalArgumentException) {
                // Not registered — attach-fail path or double-detach; ignore.
            }
        }
        autoReconnectHandler.removeCallbacks(autoReconnectRunnable)
        btAdapterStateReceiver = null
        showReceiver = null
        debugReceiver = null
        // End any active Telecom call + unregister our PhoneAccount
        // before audio teardown — Telecom needs to release focus +
        // route ownership while audio plumbing is still around.
        callBridge?.shutdown()
        callBridge = null
        // Unbind the voice plant but DO NOT stopService. XvVoiceService
        // has a 30 s orphan-grace timer on onUnbind — if the plugin
        // reloads within that window (typical ATAK plugin-reload latency
        // is 1-3 s), the new bind cancels the timer and the service
        // keeps running. Audio plant (AudioRecord, AudioTrack, SCO,
        // capture, codec, PTT state) stays alive across the reload,
        // eliminating the 3-5 s dark window. Audit L2.
        voiceClient?.unbindForReload()
        voiceClient = null
        // Plugin-side residue: status tones (USAGE_MEDIA chirps from
        // the ATAK process), the route preference object, and the A2DP
        // controller (which restores A2DP on every device we forbade).
        tptPlayer?.stop()
        tptPlayer = null
        statusTones = null
        audioRouter?.stop()
        audioRouter = null
    }

    private fun startMulticastInternal(
        context: Context,
        group: String,
        port: Int,
        label: String,
    ) {
        // Multicast transport is currently dormant — the playback plant
        // moved into the service in the H11 cleanup, and the
        // MulticastTransport still expects a plugin-side AudioPlayback.
        // Phase 8 (per-channel multicast failover) will rewire this
        // path through the service; for now, this DebugReceiver entry
        // point is a no-op so we don't pretend to start a transport
        // that won't render audio.
        Log.w(TAG, "startMulticastInternal($group:$port/$label) — disabled until Phase 8 rewires through XvVoiceService")
    }

    /** Unwrap whichever of [MumbleTransport] or
     *  [com.atakmap.android.xv.transport.ReconnectingMumbleTransport]
     *  is current. Call sites that need MumbleTransport-specific
     *  operations (joinChannel, retargetSecondary, availableChannels,
     *  canSpeakOnSlot) go through this so they keep working when
     *  the transport is wrapped for reconnect support. The result
     *  may be null between reconnect attempts. */
    private fun mumbleTransport(): MumbleTransport? =
        when (val t = activeTransport) {
            is MumbleTransport -> t
            is com.atakmap.android.xv.transport.ReconnectingMumbleTransport -> t.current()
            else -> null
        }

    /** Snapshot the roster of a given Mumble slot's joined channel +
     *  enrich each row with XV presence (deviceUid, advertised
     *  channels) for map-pan + jump-channel actions. Used by the
     *  Channel Members picker (title-bar two-column dialog) and the
     *  per-PTT 👥 single-column variant. Returns null when the slot
     *  isn't connected to a channel. */
    private fun buildSlotMembersSnapshot(
        slot: Int,
        transport: MumbleTransport,
    ): XvDropDownReceiver.SlotMembers? {
        val joinedCh = joinedChannelsBySlot[slot] ?: return null
        val members = transport.channelMembers(slot)
        // sessionId → XvPresence: VS1 and VS2 of the same operator
        // publish DIFFERENT mumbleSessions so this map naturally has
        // both. Drop entries that haven't published a session id yet
        // (their CoT presence reached us before they finished
        // connecting to Mumble — happens at startup).
        val bySession: Map<Int, com.atakmap.android.xv.presence.XvPresence> =
            presenceRegistry
                ?.all()
                .orEmpty()
                .filter { it.mumbleSession != null }
                .associateBy { it.mumbleSession!! }
        // Local PARTICIPATE filter for jump-channel suggestions. Only
        // channels we have ENTER permission on are offered. (The
        // remote peer's permissions are unknowable — we can only
        // gate on what we can see.) availableChannels() folds in
        // suppressedChannelIds for OTS direction enforcement so a
        // direction-OUT channel we can technically join but not
        // speak in isn't suggested either.
        val allChannels = transport.availableChannels()
        val canParticipate: (Int) -> Boolean = { id ->
            allChannels.firstOrNull { it.id == id }?.participation ==
                com.atakmap.android.xv.transport.mumble.MumbleSession.ChannelInfo.Participation.PARTICIPATE
        }
        // Don't suggest jumping to a channel we're already on (in
        // either slot) — meaningless action. localChannelIds covers
        // both slots in one set.
        val localChannelIds = joinedChannelsBySlot.values.map { it.id }.toSet()
        // Local-operator shortcut: our own row is always XV, regardless
        // of whether the registry has caught up with the self-CoT echo
        // (which the listener intentionally drops to avoid double-
        // counting, so without this the operator sees themselves as a
        // non-XV peer). Also covers VS2 where the publisher only carries
        // the primary session id and a registry hit would miss.
        val ourSessionForSlot = transport.ourSessionIdForSlot(slot)
        val rows =
            members.map { m ->
                val presence = bySession[m.sessionId]
                val isUs = ourSessionForSlot != null && m.sessionId == ourSessionForSlot
                val isXv = presence != null || isUs
                val jumps =
                    presence
                        ?.channels
                        .orEmpty()
                        .filter { it.id !in localChannelIds && canParticipate(it.id) }
                        .map { XvDropDownReceiver.JumpChannel(it.id, it.name) }
                XvDropDownReceiver.ChannelMember(
                    mumbleSessionId = m.sessionId,
                    callsign = m.callsign,
                    slot = slot,
                    isXvPeer = isXv,
                    deviceUid =
                    presence?.deviceUid
                        ?: if (isUs) {
                            com.atakmap.android.xv.transport.mumble.MumbleAuth.deviceUid()
                        } else {
                            null
                        },
                    talkingNow = m.isTalking,
                    availableJumpChannels = jumps,
                )
            }
        return XvDropDownReceiver.SlotMembers(
            slot = slot,
            channelName = joinedCh.name,
            members = rows,
        )
    }

    /**
     * Phase E outgoing direct call to a peer identified by their ATAK
     * device UID. Steps (in order, all on the plugin side):
     *
     *   1. Resolve peer via XvPresenceRegistry — must have a known
     *      mumbleSession to be addressable.
     *   2. Generate `tempName = "TAK PRIVATE - <hex12>"` matching VX's
     *      naming convention so OTS's DirectionEnforcementCallback
     *      applies the conference ACL on creation.
     *   3. Register a one-shot ChannelState listener for tempName.
     *   4. Send sendChannelState(parent=0, name=tempName, temp=true,
     *      maxUsers=2, enterRestricted=true). On a properly-configured
     *      OTS (OTS_MUMBLE_ENABLE_CONFERENCE_CALLS=True) the server
     *      grants MakeTempChannel to authenticated users at Root and
     *      this succeeds. On a server without that, the listener
     *      times out and we surface a toast.
     *   5. On listener fire (server assigned an id):
     *        - Send `[TAK MxVx : REQUEST_CALL ]<id>` TextMessage
     *          targeted to peer.mumbleSession.
     *        - joinChannel(id) — leaves current, joins temp. The
     *          existing channel-join → onChannelChanged path lights
     *          up the Telecom outgoing call with tag "→ <peer>".
     *   6. Timeout (5s): cancel the listener, surface a toast hinting
     *      at the OTS configuration requirement.
     */
    private fun startDirectCallInternal(peerUid: String) {
        val ctx = heldPluginContext
        val transport = mumbleTransport()
        val session = transport?.primarySession()
        val registry = presenceRegistry
        if (transport == null || session == null) {
            Log.w(TAG, "startDirectCall($peerUid): no live Mumble session — toast and abort")
            ctx?.let { Toast.makeText(it, "Connect to Mumble first", Toast.LENGTH_SHORT).show() }
            return
        }
        // Clear our LOCAL bookkeeping for any prior call attempt.
        // Deliberately NOT touching Telecom state here — calling
        // teardownLocal() on a live Connection while Telecom is
        // mid-binding our service can drop into a system_server NPE
        // in ConnectionServiceWrapper.onSuccess (verified 2026-05-11
        // Surface Duo: pulled system_server down, rebooted the
        // phone). Telecom-side teardown happens through endChannelCall
        // → setDisconnected via the standard binder path; let it
        // race naturally with any new placeCall, the system handles
        // that case.
        cancelPendingAnswerTimeout("starting new outgoing call")
        cancelPendingRingback("starting new outgoing call")
        clearCallSignalContext()
        try {
            voiceClient?.ifBound { it.endChannelCall() }
        } catch (t: Throwable) {
            Log.w(TAG, "endChannelCall pre-clean threw", t)
        }
        // Mumble-roster peer: peerUid is the synthetic "mumble:<sessionId>"
        // for VX peers (no <__xv> CoT presence). Skip the registry lookup
        // and address them directly by Mumble session id.
        val peerSession: Int =
            if (peerUid.startsWith(MUMBLE_PEER_PREFIX)) {
                val parsed = peerUid.removePrefix(MUMBLE_PEER_PREFIX).toIntOrNull()
                if (parsed == null) {
                    Log.w(TAG, "startDirectCall($peerUid): malformed mumble peer uid")
                    return
                }
                parsed
            } else {
                if (registry == null) {
                    Log.w(TAG, "startDirectCall($peerUid): presence registry not ready")
                    return
                }
                val peer = registry.get(peerUid)
                if (peer == null) {
                    Log.w(TAG, "startDirectCall($peerUid): no fresh __xv presence record")
                    ctx?.let { Toast.makeText(it, "Peer not visible (no XV presence)", Toast.LENGTH_SHORT).show() }
                    return
                }
                peer.mumbleSession ?: run {
                    Log.w(
                        TAG,
                        "startDirectCall($peerUid): peer has no published mumbleSession — " +
                            "may be on a different server, or older XV",
                    )
                    ctx?.let { Toast.makeText(it, "Peer not callable on this server", Toast.LENGTH_SHORT).show() }
                    return
                }
            }
        val tempHex =
            java.lang.Long
                .toHexString(java.security.SecureRandom().nextLong())
                .take(12)
        val tempName = "TAK PRIVATE - $tempHex"
        Log.i(TAG, "startDirectCall: peer=$peerUid mumbleSession=$peerSession temp='$tempName'")

        // Caller-side ringback: synthesized PSTN dual-tone, looped
        // every 3s until the call resolves. Phone-system convention
        // gives operators an instant intuition for "they haven't
        // picked up yet" without requiring them to look at the screen.
        //
        // Stash the cancel as a field so every teardown path
        // (channel-ack, channel-timeout, send-fail, answer-timeout,
        // peer-accept, CANCEL_CALL/REJECT_CALL, system-hangup) can
        // stop it. Without this, a hangup before the channel ack
        // leaves the local handler looping forever (verified
        // 2026-05-11 Surface Duo stuck firing CALL_RINGBACK for 50+s).
        cancelPendingRingback("starting new outgoing call")
        val ringbackHandler = android.os.Handler(android.os.Looper.getMainLooper())
        // Use an atomic-flag gate INSIDE the runnable in addition to
        // Handler.removeCallbacks below. Pure Handler-side cancel was
        // observed to lose the re-post in flight (Pixel 9 Pro 2026-05-11
        // log showed the loop continuing 3s after "ringback cancelled"
        // landed). The flag closes that race — once it flips, every
        // future iteration short-circuits before re-posting itself.
        val ringbackCancelled =
            java.util.concurrent.atomic
                .AtomicBoolean(false)
        val ringbackLoop =
            object : Runnable {
                override fun run() {
                    if (ringbackCancelled.get()) return
                    statusTones?.play(com.atakmap.android.xv.audio.StatusToneKind.CALL_RINGBACK)
                    if (ringbackCancelled.get()) return
                    ringbackHandler.postDelayed(this, 3_000L)
                }
            }
        ringbackHandler.post(ringbackLoop)
        pendingRingbackCancel = {
            ringbackCancelled.set(true)
            ringbackHandler.removeCallbacks(ringbackLoop)
        }

        // Register the listener BEFORE sending the create — the server
        // can echo the ChannelState back in microseconds and we don't
        // want to race it.
        val timeoutHandle = android.os.Handler(android.os.Looper.getMainLooper())
        val timeoutToken = Any()
        val resolved =
            java.util.concurrent.atomic
                .AtomicBoolean(false)
        transport.awaitChannelByName(tempName) { channelId ->
            if (!resolved.compareAndSet(false, true)) return@awaitChannelByName
            timeoutHandle.removeCallbacksAndMessages(timeoutToken)
            // Ringback continues until the callee actually JOINS the
            // temp channel (we observe their UserState arrival via the
            // existing channel-roster path) — but for now, kill the
            // loop on channel-create-acked so we at least stop after
            // the local "the request is on its way" milestone. A
            // follow-up could keep ringing through the join wait.
            cancelPendingRingback("temp channel acked")
            Log.i(TAG, "startDirectCall: temp '$tempName' assigned id=$channelId — warming up before CoT REQUEST")
            val ourCallsign =
                try {
                    com.atakmap.android.maps.MapView
                        .getMapView()
                        ?.deviceCallsign
                } catch (_: Throwable) {
                    null
                } ?: "XV"
            val ourUid =
                com.atakmap.android.xv.transport.mumble.MumbleAuth
                    .deviceUid() ?: "XV-UNKNOWN"
            // If the peer is roster-only (mumble:<sessionId>), there's
            // no CoT UID to address — VX call interop is dropped, so
            // we just skip CoT here. The temp channel exists; if the
            // peer is XV-capable they need a CoT presence record to
            // be reachable.
            if (peerUid.startsWith(MUMBLE_PEER_PREFIX)) {
                Log.w(
                    TAG,
                    "startDirectCall: peer is roster-only (no CoT presence) — CoT REQUEST not sent. " +
                        "Direct calls require XV peers visible on the map.",
                )
                ctx?.let {
                    Toast
                        .makeText(
                            it,
                            "Peer must have XV presence on the map for direct calls",
                            Toast.LENGTH_LONG,
                        ).show()
                }
                cancelPendingRingback("peer not CoT-reachable")
                transport.cancelChannelByName(tempName)
                return@awaitChannelByName
            }
            // Stash the call context so CANCEL / REJECT can address
            // the peer over CoT later, regardless of how the call
            // ends (system hangup, peer decline, answer timeout).
            currentCallPeerCotUid = peerUid
            currentCallTempChannelName = tempName
            currentCallTempChannelId = channelId
            //
            // ───── CALLER WARMUP, THEN RING ─────
            //
            // Per operator request 2026-05-11: pre-prepare the channel
            // during the caller's ringback, only invite the recipient
            // once everything is hot. Net effect: when the callee taps
            // Answer, voice flows immediately on both sides — no
            // 80-150 ms AudioTrack startup gap, no comm-device race,
            // no mid-syllable cut-in.
            //
            // Order:
            //   1. Mumble state (notePrivateCallStarted). Both slots
            //      arbitrate around the active call from this point.
            //   2. Telecom call active (startChannelCall) — sets
            //      MODE_IN_COMMUNICATION, comm device, speakerphone,
            //      foreground promotion, AND pre-warms the playback
            //      AudioTrack via VoicePlant.warmupCallPlayback().
            //   3. Open the caller's mic NOW (auto-engage). The temp
            //      channel only has the caller, so frames are sent into
            //      a one-occupant channel — Murmur drops them with no
            //      listeners. Cost: ~2 KB/s of wasted bandwidth during
            //      the 5-30 s ring window. Benefit: AudioRecord is
            //      hot, ANR-free, post-SCO-setup before the callee
            //      arrives.
            //   4. After WARMUP_DELAY_MS, send the CoT REQUEST. The
            //      callee's ring starts ~roundtrip-CoT later; by then
            //      our side is fully realtime.
            //
            val peerDisplay = transport.peerDisplayName(peerSession)
            currentChannelTag = "→ $peerDisplay"
            val callerPreCall = transport.joinedChannelId()
            transport.notePrivateCallStarted(
                callSlotIdx = 0,
                peerSession = peerSession,
                tempChannelId = channelId,
                preCallChannelId = callerPreCall,
                isCaller = true,
            )
            // Engage Telecom + audio mode. The service's
            // engagePrivateCallAudioMode pre-warms the playback track
            // via VoicePlant.warmupCallPlayback() so the AudioTrack
            // is allocated before the first peer frame arrives.
            voiceClient?.ifBound { it.startChannelCall("→ $peerDisplay") }
            // NOTE: do NOT engagePrivateCallMic here. The plant's
            // pttDownForPrivateCall path also calls
            // onPlaceTelecomCall("voice") which would attempt a SECOND
            // Telecom call concurrent with the "→ peer" call we just
            // placed. Result: Telecom sees two simultaneous outgoing
            // calls, refuses with "another call connecting", AND in
            // the worst case (Surface Duo 2 2026-05-11) crashes
            // system_server with a NullPointerException inside
            // ConnectionServiceWrapper.onSuccess — taking the whole
            // phone down. Mic stays cold until the callee accepts
            // (onPeerAcceptedCall fires engagePrivateCallMic).
            // Send REQUEST after a brief warmup window. The delay
            // covers AudioRecord allocation (~80 ms) + Telecom audio
            // mode propagation (~50-100 ms). 300 ms gives a small
            // margin without making the operator wait perceptibly.
            val requestDispatcher = android.os.Handler(android.os.Looper.getMainLooper())
            requestDispatcher.postDelayed({
                Log.i(TAG, "startDirectCall: warmup complete — sending CoT REQUEST to peer=$peerUid")
                val sent =
                    com.atakmap.android.xv.calling.XvCallSignals.send(
                        com.atakmap.android.xv.calling.XvCallSignals.Signal(
                            action = com.atakmap.android.xv.calling.XvCallSignals.ACTION_REQUEST,
                            tempChannelName = tempName,
                            tempChannelId = channelId,
                            callerUid = ourUid,
                            calleeUid = peerUid,
                            callerCallsign = ourCallsign,
                        ),
                    )
                if (!sent) {
                    Log.w(TAG, "startDirectCall: CoT REQUEST send failed")
                }
            }, CALL_WARMUP_DELAY_MS)

            // Answer timeout: 30 s. If the callee hasn't joined the
            // temp channel within this window, the call is presumed
            // unanswered — tear it down locally and send CANCEL_CALL
            // to clean up any stale ring state on the peer's side.
            // Cancelled when the callee actually joins (onPeerAcceptedCall)
            // or when the caller hangs up locally first.
            val answerTimeoutHandler = android.os.Handler(android.os.Looper.getMainLooper())
            val answerTimeoutToken = Any()
            val answerTimeoutRunnable =
                Runnable {
                    Log.w(
                        TAG,
                        "answer timeout: callee session=$peerSession never joined temp $channelId — " +
                            "ending call locally and sending CANCEL_CALL",
                    )
                    pendingAnswerTimeoutCancel = null
                    cancelPendingRingback("answer timeout")
                    try {
                        sendCallSignal(com.atakmap.android.xv.calling.XvCallSignals.ACTION_CANCEL)
                    } catch (t: Throwable) {
                        Log.w(TAG, "answer-timeout CoT CANCEL send threw", t)
                    }
                    statusTones?.play(com.atakmap.android.xv.audio.StatusToneKind.CALL_BUSY)
                    // Tear down the Telecom call so the in-call activity
                    // dismisses + voice plant unwinds. endChannelCall is
                    // idempotent on the service side.
                    voiceClient?.ifBound { it.endChannelCall() }
                }
            answerTimeoutHandler.postAtTime(
                answerTimeoutRunnable,
                answerTimeoutToken,
                android.os.SystemClock.uptimeMillis() + ANSWER_TIMEOUT_MS,
            )
            pendingAnswerTimeoutCancel = {
                answerTimeoutHandler.removeCallbacksAndMessages(answerTimeoutToken)
            }
        }

        // Parent = Root (id 0). XV requires the OpenTAKServer
        // `feature/mumble-vx-private-call-acl` (or equivalent) which
        // grants MakeTempChannel @auth at the Root level. With that
        // ACL in place, callers can dial from anywhere — including
        // Lobby (Root) itself, where the operator may not be a member
        // of any tactical channel yet.
        //
        // Without the ACL, sub-channel-parent (caller's current
        // channel) is a workaround but breaks the "call from anywhere"
        // requirement: a user sitting in Lobby with no tactical
        // channel can't create a sub-channel because Lobby IS Root,
        // and they don't have Root's MakeTempChannel either way.
        //
        // The temp lives at the same level as standard channels.
        // maxUsers=2 enforces 2-person scope; temporary=true ensures
        // Murmur garbage-collects it when both parties leave.
        val parentChannelId = 0
        Log.i(TAG, "sendChannelState parent=$parentChannelId (Root — requires OTS global MakeTempChannel ACL)")
        val ok =
            session.sendChannelState(
                name = tempName,
                parent = parentChannelId,
                description = "Private Call",
                temporary = true,
                maxUsers = 2,
                // enterRestricted intentionally false: setting it to true
                // makes Murmur try to write a channel ACL on our behalf,
                // and the channel creator (us) doesn't have ACL-write
                // permission on a freshly-created temp channel. The
                // resulting "not allowed to Write ACL in TAK PRIVATE - X"
                // is logged on EVERY private call (verified 2026-05-10
                // against tak.example.com mumble-server.log) and may be
                // contributing to server-stability cascades. The
                // temporary=true + maxUsers=2 combination already enforces
                // 2-person scope, which is the actual security model. We
                // pass false explicitly here to document the choice.
                enterRestricted = false,
            )
        if (!ok) {
            transport.cancelChannelByName(tempName)
            cancelPendingRingback("sendChannelState failed")
            ctx?.let {
                Toast.makeText(it, "Mumble session not ready", Toast.LENGTH_SHORT).show()
            }
            return
        }
        // 5s timeout. Most OTS responses land in <100ms; 5s catches
        // the case where the server REJECTS the create (no
        // MakeTempChannel grant) without bouncing us off the wire.
        // OTS configures this via OTS_MUMBLE_ENABLE_CONFERENCE_CALLS.
        timeoutHandle.postAtTime(
            {
                if (!resolved.compareAndSet(false, true)) return@postAtTime
                transport.cancelChannelByName(tempName)
                cancelPendingRingback("channel-create timeout")
                statusTones?.play(com.atakmap.android.xv.audio.StatusToneKind.CALL_BUSY)
                Log.w(
                    TAG,
                    "startDirectCall: timeout waiting for ChannelState '$tempName' — " +
                        "OTS may have OTS_MUMBLE_ENABLE_CONFERENCE_CALLS=False",
                )
                ctx?.let {
                    Toast
                        .makeText(
                            it,
                            "Server didn't allow temp channel — check OTS_MUMBLE_ENABLE_CONFERENCE_CALLS",
                            Toast.LENGTH_LONG,
                        ).show()
                }
            },
            timeoutToken,
            android.os.SystemClock.uptimeMillis() + 5_000L,
        )
    }

    private fun stopActiveTransport() {
        // Tear the network watcher down BEFORE the transport so a
        // last-second swap event can't race with disconnect and
        // re-trigger a connect against an activeTransport=null.
        networkWatcher?.stop()
        networkWatcher = null
        val t = activeTransport
        if (t != null) {
            Log.i(TAG, "stopActiveTransport: disconnecting ${t.javaClass.simpleName}")
            val hadAnyChannel = joinedChannelsBySlot.isNotEmpty()
            // End the Telecom call BEFORE tearing down the transport
            // so Telecom releases audio focus + BT route ownership
            // cleanly. No-op if no call is active.
            callBridge?.endChannelCall()
            // Tell the listener this is a deliberate teardown so the
            // resulting onDisconnected() doesn't fire the "voice lost"
            // warning chirp at the operator.
            deliberateDisconnectInProgress = true
            try {
                t.disconnect()
            } finally {
                deliberateDisconnectInProgress = false
            }
            activeTransport = null
            activeMumbleHost = null
            joinedChannelsBySlot.clear()
            // Tell mesh voice the server leg is gone. Legs deliberately
            // survive — this is the failover trigger, not a teardown.
            try {
                meshVoiceManager?.onChannelsCleared()
            } catch (th: Throwable) {
                Log.w(TAG, "mesh onChannelsCleared threw", th)
            }
            // Clear the published Mumble session id; peers should not
            // try to call us via a stale session number after we've
            // dropped off the server.
            presencePublisher?.setChannels(emptyList())
            presencePublisher?.setMumbleSession(null)
            if (hadAnyChannel) {
                statusTones?.play(com.atakmap.android.xv.audio.StatusToneKind.CHANNEL_LEAVE)
            }
            // No settle-sleep here: VoiceTransport.disconnect() is now
            // synchronous (MumbleSession joins its read thread and
            // awaits its write executor). If a future transport adds
            // async teardown, expose an onTeardownComplete callback
            // rather than reintroducing a UI-thread sleep that ANRs.
            Log.i(TAG, "stopActiveTransport: cleanup complete")
        }
    }

    private fun sendOpusToActiveTransport(
        opus: ByteArray,
        targetSlot: Int,
    ) {
        // Mumble copy: unconditional when a transport is live. A dead
        // transport drops it; the mesh leg carries the burst instead.
        activeTransport?.sendFrame(
            com.atakmap.android.xv.transport.VoiceFrame(
                opusPayload = opus,
                senderId = "self",
                monotonicTimestampMs = System.nanoTime() / 1_000_000,
                targetSlot = targetSlot,
            ),
        )
        // Mesh copy: the manager decides per channel mode + failover
        // state whether the frame also goes on the multicast leg.
        try {
            meshVoiceManager?.sendTxOpus(opus, targetSlot)
        } catch (t: Throwable) {
            Log.w(TAG, "mesh sendTxOpus threw", t)
        }
    }

    private fun sendTerminatorToActiveTransport(targetSlot: Int) {
        mumbleTransport()?.sendTerminator(targetSlot)
    }

    private fun beginMumbleVoiceBurst() {
        val t = mumbleTransport()
        if (t != null) {
            Log.i(TAG, "beginMumbleVoiceBurst() called - resetting sequence")
            t.beginVoiceBurst()
        } else {
            Log.w(TAG, "beginMumbleVoiceBurst() called but no live Mumble transport")
        }
        // Reset every mesh leg's burst state on the same PTT-down edge
        // so cross-leg RX dedup sequence numbers stay aligned.
        try {
            meshVoiceManager?.beginTxBurst()
        } catch (th: Throwable) {
            Log.w(TAG, "mesh beginTxBurst threw", th)
        }
    }

    // Builds the bridge between the DropDownReceiver UI and the plugin's
    // service objects. Keeps the UI layer free of direct ATAK SDK / audio
    // class references.
    private fun buildController(): XvDropDownReceiver.Controller =
        object : XvDropDownReceiver.Controller {
            override fun isMumbleConnected(): Boolean = activeTransport?.isConnected == true

            override fun isInCall(): Boolean {
                // True when a private-call temp channel is currently
                // active on the primary slot. Picker uses this to
                // lock channel-switching during a call.
                val t = mumbleTransport() ?: return false
                return t.isInPrivateCall()
            }

            override fun mumbleReconnectInfo(): XvDropDownReceiver.ReconnectInfo? {
                val t =
                    activeTransport as? com.atakmap.android.xv.transport.ReconnectingMumbleTransport
                        ?: return null
                if (!t.isReconnecting()) return null
                return XvDropDownReceiver.ReconnectInfo(
                    attempt = t.reconnectAttempt(),
                    nextDelayMs = t.nextScheduledDelayMs(),
                )
            }

            override fun currentChannelName(): String? {
                mumbleTransport()?.let { t ->
                    // Prefer the live joined channel (truth) over the
                    // configured one — the user may have moved.
                    t.joinedChannelName()?.let { return it }
                }
                val cfg = activeTransport?.config
                return if (cfg is com.atakmap.android.xv.transport.TransportConfig.Mumble) {
                    cfg.channelName.takeIf { it.isNotBlank() }
                } else {
                    null
                }
            }

            override fun secondaryChannelName(): String? = lastSecondaryChannel?.takeIf { it.isNotBlank() }

            override fun secondaryRegistered(): Boolean {
                val t = mumbleTransport() ?: return false
                // VS2 PTT lights up only when the secondary session has
                // a live, joined Mumble session — i.e. there's a real
                // second identity on the server keyable from this device.
                return t.secondaryConnected()
            }

            override fun canSpeakOnSlot(slot: Int): Boolean {
                // Default true means "we don't know yet" or "we're
                // not in a channel" both render as not-listen-only.
                // The UI ALSO checks isMumbleConnected before
                // displaying the badge, so the false case here only
                // gets surfaced once we actually have a session and
                // have heard back from the server.
                val t = mumbleTransport() ?: return true
                return t.canSpeakOnSlot(slot)
            }

            override fun availableChannels(): List<com.atakmap.android.xv.transport.mumble.MumbleSession.ChannelInfo> {
                val t = mumbleTransport() ?: return emptyList()
                return t.availableChannels()
            }

            override fun activeSpeakers(slot: Int): List<String> {
                val mumble = mumbleTransport()?.activeSpeakers(slot).orEmpty()
                if (slot != 0) return mumble
                // Mesh talkers ride the primary channel row too —
                // callsign for ATAK/XV peers, Mumble username for
                // server clients heard via a bridge. The dedup layer
                // already guarantees one leg plays per speaker, and
                // resolved names collide across lists only when they
                // genuinely refer to the same person — distinct()
                // keeps the row clean either way.
                val mesh = meshVoiceManager?.meshActiveSpeakers().orEmpty()
                return (mumble + mesh).distinct()
            }

            override fun connectedTakHost(): String? =
                com.atakmap.android.xv.transport.mumble.TakServerDiscovery
                    .pickPreferred(settings.persistedPreferredTakHost())
                    ?.host

            override fun availableTakHosts(): List<com.atakmap.android.xv.ui.XvDropDownReceiver.TakHostInfo> {
                val preferred = settings.persistedPreferredTakHost()
                return com.atakmap.android.xv.transport.mumble.TakServerDiscovery.all().map { h ->
                    com.atakmap.android.xv.ui.XvDropDownReceiver.TakHostInfo(
                        description = h.description,
                        host = h.host,
                        connected = h.connected,
                        isPreferred = !preferred.isNullOrBlank() && h.host.equals(preferred, ignoreCase = true),
                    )
                }
            }

            override fun preferredTakHost(): String? = settings.persistedPreferredTakHost()

            override fun setPreferredTakHost(host: String?) {
                val previous = settings.persistedPreferredTakHost()
                settings.persistPreferredTakHost(host)
                Log.i(TAG, "TAK server preference: ${previous ?: "(auto)"} → ${host ?: "(auto)"}")
                // Reconnect Mumble if the new pick resolves to a different
                // host than whatever is live right now. Operator picks a
                // server in Settings expecting traffic to move; a silent
                // pref change without reconnect would leave the radio
                // still talking to the old box until next plugin start.
                val resolved =
                    com.atakmap.android.xv.transport.mumble.TakServerDiscovery
                        .pickPreferred(host)
                        ?.host
                if (resolved != null && resolved != activeMumbleHost) {
                    Log.i(TAG, "TAK server change → reconnecting Mumble to $resolved")
                    connectMumbleWithDefaults()
                }
            }

            override fun audioStateText(): String = "service"

            override fun routeText(): String {
                // BT classification lives in the service now (BtAudioPolicy
                // moved with the voice plant). The dropdown only renders
                // the route preference here; live BT state surfaces via
                // service-side log lines and the join/leave chirps.
                return audioRouter?.route?.name ?: "AUTO"
            }

            override fun tptTone(): TptTone = currentTptTone

            override fun setTptTone(tone: TptTone) {
                currentTptTone = tone
                settings.persistTptTone(tone)
                Log.i(TAG, "TPT preference set to $tone (persisted)")
                voiceClient?.setPersistent("tptTone") { it.setTptTone(tone.name) }
            }

            override fun outputRoute(): OutputRoute = audioRouter?.route ?: OutputRoute.SPEAKER

            override fun setOutputRoute(route: OutputRoute) {
                audioRouter?.route = route
                voiceClient?.setPersistent("outputRoute") { it.setOutputRoute(route.name) }
            }

            override fun availableBtOutputs(): List<com.atakmap.android.xv.audio.AudioRouter.BtOutput> =
                audioRouter?.availableBtOutputs() ?: emptyList()

            override fun outputBtOverrideMac(): String? = audioRouter?.outputBtOverrideMac

            override fun setOutputBtOverrideMac(mac: String?) {
                audioRouter?.outputBtOverrideMac = mac
                voiceClient?.setPersistent("outputBtOverride") { it.setOutputBtOverride(mac ?: "") }
            }

            override fun startTx(slot: Int) {
                Log.i(TAG, "Controller.startTx(slot=$slot)")
                lastRequestedTxSlot = slot
                dispatchPttDown(slot = slot)
            }

            override fun isTransmittingOnSlot(slot: Int): Boolean = txActiveSlot == slot

            override fun stopTx() {
                // Slot is meaningless on the up-edge in momentary mode
                // (we just release TX) and ignored in latched mode.
                dispatchPttUp(slot = 0)
            }

            override fun playTptPreview() {
                voiceClient?.ifBound { it.playTptPreview() }
            }

            override fun connectMumble() {
                Log.i(TAG, "Controller.connectMumble() — reconnecting with default params")
                connectMumbleWithDefaults()
            }

            override fun disconnectMumble() {
                stopActiveTransport()
            }

            override fun connectAina() {
                val ctx = heldPluginContext ?: return
                val mac = lastAinaMac
                // "auto" lets connectAinaInternal pick V1 (SPP-ASCII) vs
                // V2 (BLE GATT) by inspecting BluetoothDevice.getType().
                // V1 voice responders are BR/EDR-only (Classic SPP);
                // V2 voice responders are dual-mode (Classic for HFP +
                // LE for the button-mask characteristic). Per AINA APTT
                // Communication Protocol v18.
                if (mac != null) {
                    connectAinaInternal(ctx, mac, null, "auto")
                } else {
                    // First-time connect: try to discover by name pattern.
                    connectAinaInternal(ctx, null, "APTT", "auto")
                }
            }

            override fun disconnectAina() {
                disconnectAinaInternal()
            }

            override fun setPrimaryChannel(name: String) {
                val trimmed = name.trim()
                if (trimmed.isEmpty()) return
                // Defense in depth — UI grays out collisions but a debug
                // intent or future caller could still try. Refuse early so
                // the user gets a toast instead of a silent transport-layer
                // rejection.
                val sec = lastSecondaryChannel
                if (!sec.isNullOrBlank() && sec.equals(trimmed, ignoreCase = true)) {
                    Log.w(TAG, "Controller.setPrimaryChannel('$trimmed'): collides with VS2 — rejected")
                    toast("'$trimmed' is in use by VS2 — clear it first")
                    return
                }
                Log.i(TAG, "Controller.setPrimaryChannel('$trimmed')")
                joinMumbleChannelInternal(trimmed, -1)
            }

            override fun setSecondaryChannel(name: String) {
                val trimmed = name.trim()
                // Defense in depth: same symmetric check VS1 has. The
                // transport's retargetSecondary refuses too but we want a
                // clear toast at the UI layer.
                if (trimmed.isNotEmpty()) {
                    val pri =
                        mumbleTransport()?.joinedChannelName()
                            ?: joinedChannelsBySlot[0]?.name
                    if (!pri.isNullOrBlank() && pri.equals(trimmed, ignoreCase = true)) {
                        Log.w(TAG, "Controller.setSecondaryChannel('$trimmed'): collides with VS1 — rejected")
                        toast("'$trimmed' is in use by VS1 — pick another or change VS1 first")
                        return
                    }
                }
                Log.i(TAG, "Controller.setSecondaryChannel('$name')")
                setSecondaryChannelInternal(name)
            }

            override fun availableAinaDevices(): List<com.atakmap.android.xv.aina.AinaDeviceInfo> = listBondedAinaDevices()

            override fun selectedAinaMac(): String? = settings.persistedAinaMac()

            override fun ainaConnectionUp(): Boolean = isAinaConnected()

            override fun setSelectedAina(mac: String?) {
                Log.i(TAG, "Controller.setSelectedAina('$mac')")
                setSelectedAinaInternal(mac)
            }

            override fun setAinaButtonProtocol(kind: com.atakmap.android.xv.aina.AinaDeviceInfo.ButtonProtocol?) {
                Log.i(TAG, "Controller.setAinaButtonProtocol('$kind')")
                setAinaButtonProtocolInternal(kind)
            }

            override fun availableExternalButtonDevices(): List<com.atakmap.android.xv.aina.AinaDeviceInfo> {
                // The external button is a BUTTON-ONLY slot — a
                // handlebar puck, hand-held BLE PTT, or similar
                // hardware whose sole job is to key the primary
                // channel when the operator can't easily reach the
                // speakermic (motorcyclist scenario: AINA on the vest
                // + BLE puck on the bar). Speakermics (AINA V1 SPP +
                // AINA V2 BLE, both audio-carrying) are deliberately
                // hidden — the operator wears at most one speakermic
                // at a time, so a "second speakermic" pick is
                // confusing UX with no real use case. Field-observed
                // 2026-07-07: operator saw AINA entries in the
                // external-button picker and asked why speakermics
                // were listed there when the label already promised
                // "Optional second button (e.g. a Pryme handlebar
                // puck)".
                //
                // Also hides the device the operator picked as PRIMARY
                // so they can't accidentally double-connect to the
                // same MAC (which would race for BLE GATT / SPP
                // socket ownership).
                val primary = settings.persistedAinaMac()
                return listBondedAinaDevices().filter { info ->
                    info.buttonProtocol == com.atakmap.android.xv.aina.AinaDeviceInfo.ButtonProtocol.BLE_HID &&
                        !(primary != null && info.mac.equals(primary, ignoreCase = true))
                }
            }

            override fun selectedExternalButtonMac(): String? = settings.persistedExternalButtonMac()

            override fun externalButtonConnectionUp(): Boolean = lastExternalButtonConnected

            override fun addBlePttDevice(
                mac: String,
                name: String?,
            ): String? {
                val normalized = normalizeAndValidateMac(mac) ?: return "Invalid MAC format (expected AA:BB:CC:DD:EE:FF)"
                Log.i(
                    TAG,
                    "addBlePttDevice: mac=${com.atakmap.android.xv.aina.redactMac(normalized)} name=$name",
                )
                // Record in the known-BLE-PTT store so both the primary
                // and external-button pickers surface this device. Which
                // slot it goes into is the operator's call from the
                // picker. Existing "primary MAC != external-button MAC"
                // enforcement in setSelectedExternalButtonInternal
                // continues to guard against collisions.
                settings.addKnownBlePttDevice(normalized, name)
                return null
            }

            override fun knownBlePttDevices(): List<com.atakmap.android.xv.aina.AinaDeviceInfo> =
                settings.knownBlePttDevices().map { (mac, name) ->
                    com.atakmap.android.xv.aina.AinaDeviceInfo(
                        mac = mac,
                        name = name ?: mac,
                        buttonProtocol = com.atakmap.android.xv.aina.AinaDeviceInfo.ButtonProtocol.BLE_HID,
                    )
                }

            override fun removeBlePttDevice(mac: String): String? {
                val normalized = normalizeAndValidateMac(mac) ?: return "Invalid MAC format"
                Log.i(
                    TAG,
                    "removeBlePttDevice: mac=${com.atakmap.android.xv.aina.redactMac(normalized)}",
                )
                // Clear from primary slot if it matches — this also
                // triggers a disconnect via setSelectedAinaInternal(null).
                val primary = settings.persistedAinaMac()
                if (primary != null && primary.equals(normalized, ignoreCase = true)) {
                    setSelectedAinaInternal(null)
                }
                val externalButton = settings.persistedExternalButtonMac()
                if (externalButton != null && externalButton.equals(normalized, ignoreCase = true)) {
                    setSelectedExternalButtonInternal(null)
                }
                settings.removeKnownBlePttDevice(normalized)
                return null
            }

            override fun setSelectedExternalButton(mac: String?) {
                Log.i(TAG, "Controller.setSelectedExternalButton('$mac')")
                setSelectedExternalButtonInternal(mac)
            }

            override fun autoConnectBtEnabled(): Boolean = settings.persistedAutoConnectBtEnabled()

            override fun setAutoConnectBtEnabled(enabled: Boolean) {
                Log.i(TAG, "Controller.setAutoConnectBtEnabled($enabled)")
                settings.persistAutoConnectBtEnabled(enabled)
            }

            override fun samsungActiveKeySupported(): Boolean {
                val ctx = heldMapView?.context ?: heldPluginContext ?: return false
                return com.atakmap.android.xv.util.SamsungActiveKey.isSupported(ctx)
            }

            override fun samsungActiveKeyEnabled(): Boolean =
                settings.persistedSamsungActiveKeyEnabled()

            override fun setSamsungActiveKeyEnabled(enabled: Boolean) {
                Log.i(TAG, "Controller.setSamsungActiveKeyEnabled($enabled)")
                settings.persistSamsungActiveKeyEnabled(enabled)
                // Only push the service call on hardware that actually
                // has the key. Redundant defence — the row shouldn't
                // be visible on non-Samsung devices — but cheap and
                // keeps the operator's persisted pref honest if a
                // future firmware change (or an operator flipping a
                // debug feature) somehow surfaced the toggle.
                val ctx = heldMapView?.context ?: heldPluginContext ?: return
                if (!com.atakmap.android.xv.util.SamsungActiveKey.isSupported(ctx)) {
                    Log.w(
                        TAG,
                        "setSamsungActiveKeyEnabled($enabled) — device does not have the Active Key; " +
                            "persisting pref but skipping service call",
                    )
                    return
                }
                voiceClient?.setPersistent("samsungActiveKey") {
                    it.setSamsungActiveKeyEnabled(enabled)
                }
                // Foreground-KeyEvent fallback path (attached in ATAK's
                // process). Runs in parallel with the service-side
                // broadcast reader — the dispatcher's OR-gate collapses
                // any duplicate down / up if both paths happen to fire
                // for the same press. Started / stopped in lockstep
                // with the toggle so a mid-session flip cleans up
                // consistently across both paths.
                if (enabled) startSamsungActiveKeyForeground() else stopSamsungActiveKeyForeground()
            }

            override fun samsungActiveKeyBgServiceEnabled(): Boolean {
                val ctx = heldMapView?.context ?: heldPluginContext ?: return false
                return isSamsungActiveKeyAccessibilityServiceEnabled(ctx)
            }

            override fun openAccessibilitySettings() {
                val ctx = heldMapView?.context ?: heldPluginContext ?: return
                try {
                    val intent = android.content.Intent(
                        android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS,
                    ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    ctx.startActivity(intent)
                } catch (t: Throwable) {
                    Log.w(TAG, "openAccessibilitySettings: startActivity threw", t)
                }
            }

            override fun sonimHardwareButtonsSupported(): Boolean {
                val ctx = heldMapView?.context ?: heldPluginContext ?: return false
                return com.atakmap.android.xv.util.SonimHardwareButtons.isSupported(ctx)
            }

            override fun sonimPttButtonEnabled(): Boolean =
                settings.persistedSonimPttButtonEnabled()

            override fun setSonimPttButtonEnabled(enabled: Boolean) {
                Log.i(TAG, "Controller.setSonimPttButtonEnabled($enabled)")
                settings.persistSonimPttButtonEnabled(enabled)
                val ctx = heldMapView?.context ?: heldPluginContext ?: return
                if (!com.atakmap.android.xv.util.SonimHardwareButtons.isSupported(ctx)) {
                    Log.w(
                        TAG,
                        "setSonimPttButtonEnabled($enabled) — device is not a supported Sonim " +
                            "ruggedized model; persisting pref but skipping service call",
                    )
                    return
                }
                voiceClient?.setPersistent("sonimPttButton") {
                    it.setSonimPttButtonEnabled(enabled)
                }
                // Foreground-KeyEvent fallback path (attached in ATAK's
                // process). Runs in parallel with the service-side
                // broadcast reader — the dispatcher's OR-gate collapses
                // any duplicate down / up if both paths happen to fire
                // for the same press. Started / stopped in lockstep
                // with the toggle so a mid-session flip cleans up
                // consistently across both paths.
                if (enabled) startSonimPttForeground() else stopSonimPttForeground()
            }

            override fun sonimEmergencyButtonEnabled(): Boolean =
                settings.persistedSonimEmergencyButtonEnabled()

            override fun setSonimEmergencyButtonEnabled(enabled: Boolean) {
                Log.i(TAG, "Controller.setSonimEmergencyButtonEnabled($enabled)")
                settings.persistSonimEmergencyButtonEnabled(enabled)
                val ctx = heldMapView?.context ?: heldPluginContext ?: return
                if (!com.atakmap.android.xv.util.SonimHardwareButtons.isSupported(ctx)) {
                    Log.w(
                        TAG,
                        "setSonimEmergencyButtonEnabled($enabled) — device is not a supported Sonim " +
                            "ruggedized model; persisting pref but skipping service call",
                    )
                    return
                }
                voiceClient?.setPersistent("sonimEmergencyButton") {
                    it.setSonimEmergencyButtonEnabled(enabled)
                }
                if (enabled) startSonimEmergencyForeground() else stopSonimEmergencyForeground()
            }

            override fun latchedMode(): Boolean = settings.persistedLatchedMode()

            override fun setLatchedMode(enabled: Boolean) {
                settings.persistLatchedMode(enabled)
                voiceClient?.setPersistent("latchedMode") { it.setLatchedMode(enabled) }
            }

            override fun pttTimeoutSec(): Int = settings.persistedPttTimeout()

            override fun setPttTimeoutSec(seconds: Int) {
                settings.persistPttTimeout(seconds)
                voiceClient?.setPersistent("pttTimeoutSec") { it.setPttTimeoutSec(seconds) }
            }

            override fun latchedTimeoutSec(): Int = settings.persistedLatchedTimeout()

            override fun setLatchedTimeoutSec(seconds: Int) {
                settings.persistLatchedTimeout(seconds)
                voiceClient?.setPersistent("latchedTimeoutSec") { it.setLatchedTimeoutSec(seconds) }
            }

            override fun hotMicMode(): Boolean = settings.persistedHotMicMode()

            override fun setHotMicMode(enabled: Boolean) {
                Log.i(TAG, "Controller.setHotMicMode($enabled)")
                settings.persistHotMicMode(enabled)
                voiceClient?.setPersistent("hotMicMode") { it.setHotMicEnabled(enabled) }
            }

            override fun meshVoiceEnabled(): Boolean = settings.persistedMeshVoiceEnabled()

            override fun setMeshVoiceEnabled(enabled: Boolean) {
                Log.i(TAG, "Controller.setMeshVoiceEnabled($enabled)")
                settings.persistMeshVoiceEnabled(enabled)
                // No push needed — MeshVoiceManager re-reads the pref
                // in reconcileLegs() on its next ~1 Hz tick.
            }

            override fun missionChannelsEnabled(): Boolean = settings.persistedMissionChannelsEnabled()

            override fun setMissionChannelsEnabled(enabled: Boolean) {
                Log.i(TAG, "Controller.setMissionChannelsEnabled($enabled)")
                settings.persistMissionChannelsEnabled(enabled)
                // Reconciled on the next ~1 Hz mesh/mission tick.
            }

            override fun meshChannelCandidates(): List<String> {
                // canonical → first display spelling seen. Last-joined
                // first, then the persisted server directory, then
                // channels heard in peer discovery beacons.
                val out = LinkedHashMap<String, String>()
                fun add(name: String) {
                    val canonical =
                        com.atakmap.android.xv.transport.multicast.MulticastGroupDerivation
                            .canonicalChannelName(name)
                    if (canonical.isNotBlank()) out.putIfAbsent(canonical, name)
                }
                settings
                    .persistedPrimaryChannel()
                    .takeIf { it.isNotBlank() && !it.startsWith("TAK PRIVATE - ") }
                    ?.let { add(it) }
                settings.persistedKnownChannels().forEach { add(it) }
                meshVoiceManager?.discoveredChannels()?.forEach { add(it.name) }
                return out.values.toList()
            }

            override fun meshActiveChannelCanonical(): String? =
                meshVoiceManager
                    ?.activeLegs()
                    ?.keys
                    ?.firstOrNull()

            override fun selectMeshChannel(name: String) {
                Log.i(TAG, "Controller.selectMeshChannel($name)")
                // A channel known only from peer beacons (fully
                // offline: no server identity to derive from) must be
                // pinned to the ADVERTISED endpoint or the leg can
                // never resolve one. For channels this device can
                // derive itself, the pin and the derivation agree —
                // the advertiser derived it the same way.
                val canonical =
                    com.atakmap.android.xv.transport.multicast.MulticastGroupDerivation
                        .canonicalChannelName(name)
                val discovered =
                    meshVoiceManager
                        ?.discoveredChannels()
                        ?.firstOrNull {
                            com.atakmap.android.xv.transport.multicast.MulticastGroupDerivation
                                .canonicalChannelName(it.name) == canonical
                        }
                if (discovered != null && settings.channelMulticastConfigFor(name) == null) {
                    val pinned =
                        com.atakmap.android.xv.transport.multicast.ChannelMulticastConfig
                            .defaultFor(name)
                            .copy(pinnedGroup = discovered.group, pinnedPort = discovered.port)
                    if (pinned.validate() == null) {
                        settings.persistChannelMulticastConfig(pinned)
                        Log.i(TAG, "selectMeshChannel: pinned discovered endpoint for '$canonical'")
                    }
                }
                // Persist as the primary so a later Mumble reconnect
                // lands on the same channel the operator picked while
                // offline; the mesh leg rebinds on the next tick.
                settings.persistPrimaryChannel(name)
                meshVoiceManager?.onChannelJoined(0, name)
            }

            override fun meshStatus(): XvDropDownReceiver.MeshStatus? {
                val snap = meshVoiceManager?.statusSnapshot() ?: return null
                val label =
                    buildString {
                        append(if (snap.active) "MESH ACTIVE" else "MESH READY")
                        if (snap.bridging) append(" · BRIDGE")
                        if (snap.cleartext) append(" · CLEAR")
                    }
                return XvDropDownReceiver.MeshStatus(label = label, cleartext = snap.cleartext)
            }

            override fun missingPermissionLabels(): List<String> = currentlyMissingPermissions()

            override fun requestMissingPermissions() {
                val ctx = heldPluginContext ?: return
                val launcher = heldMapView?.context ?: ctx
                maybeRequestPermissions(ctx, launcher)
            }

            override fun currentAudioRouteLabel(): String = lastAudioRouteLabel

            override fun channelMembersBySlot(): Map<Int, XvDropDownReceiver.SlotMembers> {
                val transport = mumbleTransport() ?: return emptyMap()
                val result = mutableMapOf<Int, XvDropDownReceiver.SlotMembers>()
                for (slot in listOf(0, 1)) {
                    val sm = buildSlotMembersSnapshot(slot, transport) ?: continue
                    result[slot] = sm
                }
                return result
            }

            override fun channelMembersForSlot(slot: Int): XvDropDownReceiver.SlotMembers? {
                val transport = mumbleTransport() ?: return null
                return buildSlotMembersSnapshot(slot, transport)
            }

            override fun findOnMap(deviceUid: String) {
                val mv = heldMapView
                if (mv == null) {
                    Log.w(TAG, "findOnMap($deviceUid): no MapView held")
                    return
                }
                // The CoT layer keeps the peer's marker keyed by their
                // outer event.uid, which is the same string XV publishes
                // in <__xv uid="..."> (we deliberately bind to the
                // outer uid for spoof resistance — see XvCotListener).
                val item = mv.rootGroup.deepFindUID(deviceUid)
                if (item == null) {
                    Log.w(TAG, "findOnMap($deviceUid): no MapItem with that uid")
                    return
                }
                // Self-CoT markers (a-f-G-U-C and friends) are
                // PointMapItems; non-point markers (areas, lines) won't
                // appear here for an XV peer but defend anyway.
                val pmi = item as? com.atakmap.android.maps.PointMapItem
                if (pmi == null) {
                    Log.w(TAG, "findOnMap($deviceUid): marker is not a PointMapItem")
                    return
                }
                val point = pmi.point
                if (point == null) {
                    Log.w(TAG, "findOnMap($deviceUid): marker has no point")
                    return
                }
                try {
                    mv.mapController?.panTo(point, true)
                    Log.i(TAG, "findOnMap($deviceUid): panned to ${point.latitude},${point.longitude}")
                } catch (t: Throwable) {
                    Log.w(TAG, "findOnMap: panTo threw", t)
                }
            }

            override fun endCall() {
                // Always-visible "End Call" bar in the dropdown. Drives
                // the same teardown path the CallStyle notification's
                // Hang Up action does — endChannelCall() routes through
                // AIDL to XvVoiceService, which tears down the Telecom
                // Connection; the externalTeardownListener then unwinds
                // Mumble + voice plant.
                Log.i(TAG, "Controller.endCall — in-app escape hatch tapped")
                callBridge?.endChannelCall()
            }
        }

    private fun startPresenceLayer() {
        val deviceUid = MumbleAuth.deviceUid()
        if (deviceUid.isNullOrBlank()) {
            Log.w(TAG, "presence layer: no device UID available — skipping CoT publish/listen")
            return
        }
        val registry = XvPresenceRegistry()
        presenceRegistry = registry

        // Cert fingerprint is best-effort: only available once a TAK
        // server is enrolled. We attempt a lookup against the connected
        // server (if any); null is fine — the CoT detail just won't carry
        // certFp until the encryption layer needs it.
        val takPick =
            try {
                TakServerDiscovery.pick(null)
            } catch (t: Throwable) {
                null
            }
        val server = takPick?.host
        val certFp =
            server?.let { host ->
                MumbleAuth.loadTakIdentity(host)?.let { id -> MumbleAuth.certFingerprint(id.leaf) }
            }
        val publisher =
            XvCotPublisher(
                deviceUid = deviceUid,
                version = com.atakmap.android.xv.BuildConfig.VERSION_NAME,
                capabilities = setOf("direct-call"),
                certFingerprint = certFp,
                server = server,
                onSelfPublished = { selfPresence -> registry.upsert(selfPresence) },
            )
        publisher.setCallsignSupplier {
            try {
                com.atakmap.android.maps.MapView
                    .getMapView()
                    ?.deviceCallsign
            } catch (_: Throwable) {
                null
            }
        }
        publisher.start()
        presencePublisher = publisher

        // Direct-call feature is retired — the CoT listener registers
        // only the __xv presence detail and ignores anything else. No
        // onCallSignal callback is provided, so __xvcall events
        // (if any peer still emits them) are dropped silently.
        val listener =
            XvCotListener(
                ourUid = deviceUid,
                registry = registry,
            )
        listener.start()
        presenceListener = listener

        Log.i(TAG, "presence layer started: uid=$deviceUid server=$server certFp=${certFp?.take(16)}…")
    }

    // Stand up the mesh-voice manager. Idempotent — a second call
    // rebuilds against fresh identity/settings (e.g. after a re-enroll).
    // Pure-Kotlin core: every Android/ATAK touchpoint is injected as a
    // lambda so the decision surface stays unit-tested (MeshVoiceManagerTest).
    private fun startMeshVoice() {
        val deviceUid = MumbleAuth.deviceUid()
        if (deviceUid.isNullOrBlank()) {
            Log.w(TAG, "mesh voice: no device UID — not starting")
            return
        }
        stopMeshVoice()
        val ctx = heldPluginContext
        val takHost =
            try {
                TakServerDiscovery.pick(null)?.host
            } catch (_: Throwable) {
                null
            }
        val takIdentity = takHost?.let { MumbleAuth.loadTakIdentity(it) }
        val ourCertDer = takIdentity?.leaf?.encoded
        val ourPrivateKey = takIdentity?.privateKey

        val manager =
            MeshVoiceManager(
                ourUid = deviceUid,
                ourCallsign = {
                    try {
                        MapView.getMapView()?.deviceCallsign
                    } catch (_: Throwable) {
                        null
                    }
                },
                meshEnabled = { settings.persistedMeshVoiceEnabled() },
                configForChannel = { channel ->
                    settings.channelMulticastConfigFor(channel)
                        ?: com.atakmap.android.xv.transport.multicast.ChannelMulticastConfig
                            .defaultFor(channel)
                },
                serverIdentity = {
                    // Live Mumble host when connected; otherwise the
                    // configured TAK server (same box for OTS). The
                    // fallback is what makes cold-start failover work:
                    // with the server unreachable at plugin load there
                    // is no activeMumbleHost, but derivation only needs
                    // the hostname the team configured, not a live
                    // connection to it.
                    val host =
                        activeMumbleHost
                            ?: try {
                                TakServerDiscovery.pickPreferred(settings.persistedPreferredTakHost())?.host
                            } catch (_: Throwable) {
                                null
                            }
                    host?.let {
                        runCatching {
                            com.atakmap.android.xv.transport.multicast.ServerIdentity
                                .fromHostname(it)
                        }.getOrNull()
                    }
                },
                mumbleConnected = { activeTransport?.isConnected == true },
                legFactory = { cfg, endpoint, registry, sink ->
                    MulticastMeshLeg(
                        config = cfg,
                        endpoint = endpoint,
                        registry = registry,
                        ourUid = deviceUid,
                        context = ctx,
                        sink = sink,
                    )
                },
                onRxOpus = { opus, speakerLabel ->
                    voiceClient?.ifBound {
                        try {
                            it.onRxOpus(0, opus, speakerLabel)
                        } catch (t: Throwable) {
                            Log.w(TAG, "mesh onRxOpus to service threw", t)
                        }
                    }
                },
                relayToMumble = { opus, burstStart ->
                    // Bridge relay: a server-less mesh speaker's frame
                    // goes onto the Mumble channel over OUR session.
                    if (burstStart) beginMumbleVoiceBurst()
                    sendOpusToActiveTransport(opus, targetSlot = 0)
                },
                onMeshTxStateChanged = { meshTxActive ->
                    // Server-less TX must stay allowed: while mesh is
                    // the active leg the plant's canTransmit gate (keyed
                    // on Mumble session) would otherwise bonk every PTT.
                    if (meshTxActive) {
                        voiceClient?.ifBound { it.setMumbleSessionState(true) }
                    }
                    dropDown?.refreshNow()
                },
                deviceUidForMumbleSession = { session ->
                    presenceRegistry?.all()?.firstOrNull { it.mumbleSession == session }?.deviceUid
                },
                uidMumbleConnected = { uid ->
                    presenceRegistry?.get(uid)?.mumbleSession != null
                },
                callsignForUid = { uid -> presenceRegistry?.get(uid)?.callsign },
                mumbleUsernameForSession = { session ->
                    mumbleTransport()?.peerDisplayName(session)?.takeIf { !it.startsWith("session:") }
                },
                knownPeerUids = { presenceRegistry?.all()?.map { it.deviceUid }.orEmpty() },
                certFpForUid = { uid -> presenceRegistry?.get(uid)?.certFingerprint },
                ourCertDer = { ourCertDer },
                unwrapKey = { wrapped ->
                    ourPrivateKey?.let {
                        try {
                            com.atakmap.android.xv.transport.multicast.TakCertCryptoBox
                                .unwrapChannelKey(wrapped, it)
                        } catch (t: Throwable) {
                            Log.w(TAG, "mesh key unwrap failed", t)
                            null
                        }
                    }
                },
                wrapKeyFor = { recipientCertDer, key ->
                    try {
                        val cert =
                            java.security.cert.CertificateFactory
                                .getInstance("X.509")
                                .generateCertificate(recipientCertDer.inputStream())
                                as java.security.cert.X509Certificate
                        com.atakmap.android.xv.transport.multicast.TakCertCryptoBox
                            .wrapChannelKey(key, cert)
                    } catch (t: Throwable) {
                        Log.w(TAG, "mesh key wrap failed", t)
                        null
                    }
                },
            )
        meshVoiceManager = manager
        // Seed the primary channel: the live Mumble join when there is
        // one, else the persisted last channel. The persisted fallback
        // is the cold-start failover case — plugin loads while the
        // server is unreachable, no Mumble join ever fires, but the
        // operator's channel is known and the mesh leg must come up
        // anyway.
        val liveJoined = joinedChannelsBySlot[0]?.name?.takeIf { it.isNotBlank() }
        val persistedSeed =
            settings
                .persistedPrimaryChannel()
                .takeIf { it.isNotBlank() && !it.startsWith("TAK PRIVATE - ") }
        (liveJoined ?: persistedSeed)?.let { manager.onChannelJoined(0, it) }
        // Mission auto-channels share the same 1 Hz tick. The channel
        // name simply IS the mission name (deterministic + shared across
        // the team), so mesh derivation lands everyone on the same group.
        missionChannelProvisioner = MissionChannelProvisioner()
        meshTickHandler.removeCallbacks(meshTickRunnable)
        meshTickHandler.postDelayed(meshTickRunnable, MESH_TICK_MS)
        Log.i(TAG, "mesh voice started: uid=$deviceUid enrolled=${ourCertDer != null}")
    }

    // ---- comms-plan provisioning (debug carrier; UI carriers reuse these) ----

    // Snapshot the channel set as a carrier string the operator can
    // hand to a peer. No pre-shared keys are exported here — the
    // clear carrier refuses them by design, and key distribution for
    // REQUIRED-crypto offline channels is a deliberate future step
    // (the import side already installs PSKs when a plan carries them).
    private fun exportCommsPlanInternal(passphrase: String?) {
        val channelNames = LinkedHashSet<String>()
        settings
            .persistedPrimaryChannel()
            .takeIf { it.isNotBlank() && !it.startsWith("TAK PRIVATE - ") }
            ?.let { channelNames += it }
        channelNames += settings.persistedKnownChannels()
        if (channelNames.isEmpty()) {
            Log.w(TAG, "MESH_PLAN_EXPORT: no channels known — connect once or select a channel first")
            return
        }
        val channels =
            channelNames.map { name ->
                com.atakmap.android.xv.provisioning.CommsPlan.Channel(
                    displayName = name,
                    config =
                    settings.channelMulticastConfigFor(name)
                        ?: com.atakmap.android.xv.transport.multicast.ChannelMulticastConfig
                            .defaultFor(name),
                )
            }
        val identity =
            try {
                TakServerDiscovery.pickPreferred(settings.persistedPreferredTakHost())?.host?.let {
                    com.atakmap.android.xv.transport.multicast.ServerIdentity.fromHostname(it).value
                }
            } catch (_: Throwable) {
                null
            }
        val plan =
            com.atakmap.android.xv.provisioning.CommsPlan(
                planId = java.util.UUID.randomUUID().toString(),
                name = "XV export",
                createdAtMs = System.currentTimeMillis(),
                serverIdentity = identity,
                channels = channels,
            )
        val text =
            try {
                if (passphrase.isNullOrBlank()) {
                    com.atakmap.android.xv.provisioning.CommsPlanCarrier.encodeClear(plan)
                } else {
                    com.atakmap.android.xv.provisioning.CommsPlanCarrier
                        .encodeLocked(plan, passphrase.toCharArray())
                }
            } catch (t: Throwable) {
                Log.w(TAG, "MESH_PLAN_EXPORT failed: ${t.message}")
                return
            }
        Log.i(TAG, "MESH_PLAN_EXPORT (${channels.size} channel(s)):")
        Log.i(TAG, text)
    }

    private fun importCommsPlanInternal(
        planText: String,
        passphrase: String?,
    ) {
        val plan =
            try {
                com.atakmap.android.xv.provisioning.CommsPlanCarrier.decode(
                    planText.trim(),
                    passphrase?.takeIf { it.isNotBlank() }?.toCharArray(),
                )
            } catch (t: Throwable) {
                // Imports are explicit operator actions — fail loudly.
                Log.w(TAG, "MESH_PLAN_IMPORT failed: ${t.message}")
                return
            }
        plan.channels.forEach { ch ->
            settings.persistChannelMulticastConfig(ch.config)
            ch.preSharedKey?.let { key ->
                meshVoiceManager?.installPresharedKey(ch.config.channelName, key)
            }
        }
        // Land on the plan's first channel when the operator has
        // nothing selected — the fully server-less first-run case.
        if (settings.persistedPrimaryChannel().isBlank()) {
            plan.channels.firstOrNull()?.let { first ->
                settings.persistPrimaryChannel(first.config.channelName)
                meshVoiceManager?.onChannelJoined(0, first.config.channelName)
            }
        }
        Log.i(TAG, "MESH_PLAN_IMPORT ok: '${plan.name}' — ${plan.channels.size} channel(s) installed")
    }

    // Failover health: any inbound server byte (ping acks count) means
    // Mumble is alive. Without this feed the failover policy only ever
    // heard about voice frames, so a connected-but-silent channel read
    // as a dead server and the policy stuck on the mesh leg forever
    // (observed on-device 2026-07-15: txActive=true with Mumble
    // connected and idle). Window is sized to the session watchdog's
    // stale threshold (8 s ping cadence + grace).
    private fun feedMumbleLivenessToMesh() {
        if (activeTransport?.isConnected != true) return
        val lastRx = mumbleTransport()?.primarySession()?.lastServerActivityAtMs() ?: 0L
        if (lastRx <= 0L) return
        if (System.currentTimeMillis() - lastRx <= MUMBLE_LIVENESS_WINDOW_MS) {
            meshVoiceManager?.observeMumbleHealth()
        }
    }

    // Refresh the persisted channel-directory snapshot while Mumble is
    // connected (write-on-change only). Registration thereby configures
    // every server channel for offline use: the picker and mesh
    // failover keep the full channel set when the server goes dark.
    private var lastKnownChannelsSnapshot: Set<String>? = null

    private fun snapshotChannelDirectory() {
        if (activeTransport?.isConnected != true) return
        val t = mumbleTransport() ?: return
        val names =
            t
                .availableChannels()
                .map { it.name }
                .filter { it.isNotBlank() && !it.startsWith("TAK PRIVATE - ") }
                .toSet()
        if (names.isEmpty() || names == lastKnownChannelsSnapshot) return
        lastKnownChannelsSnapshot = names
        settings.persistKnownChannels(names)
        Log.i(TAG, "channel directory snapshot: ${names.size} channel(s) persisted for offline use")
    }

    private fun stopMeshVoice() {
        meshTickHandler.removeCallbacks(meshTickRunnable)
        meshVoiceManager?.let {
            try {
                it.shutdown()
            } catch (t: Throwable) {
                Log.w(TAG, "mesh shutdown threw", t)
            }
        }
        meshVoiceManager = null
        missionChannelProvisioner?.reset()
        missionChannelProvisioner = null
    }

    /**
     * Publish the operator's active ATAK missions (ordered, primary
     * first) to the mission-channel provisioner. Called from the
     * SET_MISSIONS broadcast so a fleet MDM, the ATAK Data Sync tool, or
     * a companion plugin can drive which mission owns the voice channel.
     * Empty list = no active mission.
     */
    private fun setActiveMissions(missions: List<String>) {
        val cleaned = missions.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        activeMissionNames = cleaned
        Log.i(TAG, "active missions set: $cleaned")
    }

    // Drive one reconcile of the primary voice channel toward the active
    // mission. No-op unless the feature is enabled AND a Mumble session
    // is live (mission channels are a server-side concept; the multicast
    // failover leg follows automatically once we're joined).
    private fun reconcileMissionChannels() {
        if (!settings.persistedMissionChannelsEnabled()) return
        val provisioner = missionChannelProvisioner ?: return
        val transport = mumbleTransport() ?: return
        val directory = transport.availableChannels().map { it.name }
        val action =
            provisioner.reconcile(
                activeMissions = activeMissionNames,
                directoryContains = { name -> directory.any { it.equals(name, ignoreCase = true) } },
                joinedChannel = transport.joinedChannelName(),
                allowCreate = true,
            )
        when (action) {
            MissionChannelProvisioner.Action.NoOp -> {}
            is MissionChannelProvisioner.Action.Create -> {
                Log.i(TAG, "mission channel: creating '${action.channelName}'")
                // Non-temporary top-level channel; the next tick's Join
                // fires once its ChannelState lands (bounded retry inside
                // the provisioner covers a server that silently refuses).
                transport.primarySession()?.sendChannelState(action.channelName)
            }
            is MissionChannelProvisioner.Action.Join -> {
                Log.i(TAG, "mission channel: joining '${action.channelName}'")
                joinMumbleChannelInternal(action.channelName, -1)
                lastMissionUnavailableToast = null
            }
            is MissionChannelProvisioner.Action.Unavailable -> {
                if (lastMissionUnavailableToast != action.channelName) {
                    lastMissionUnavailableToast = action.channelName
                    Log.w(TAG, "mission channel '${action.channelName}' unavailable: ${action.reason}")
                    try {
                        MapView.getMapView()?.let { mv ->
                            mv.post {
                                android.widget.Toast
                                    .makeText(
                                        mv.context,
                                        "Mission voice channel '${action.channelName}' unavailable — ${action.reason}",
                                        android.widget.Toast.LENGTH_LONG,
                                    ).show()
                            }
                        }
                    } catch (t: Throwable) {
                        Log.w(TAG, "mission unavailable toast threw", t)
                    }
                }
            }
        }
    }

    private fun parseVxCompat(mode: String?): VxCompat? =
        when (mode?.lowercase()) {
            null, "" -> null
            "off" -> VxCompat.OFF
            "hybrid" -> VxCompat.HYBRID
            "strict" -> VxCompat.STRICT
            else -> null
        }

    // Start the service-side Samsung Active Key listener iff both
    // the capability check AND the persisted operator toggle agree.
    // Gate ordering (capability first) matters: on non-Samsung
    // hardware we never even ask the service to start the reader, so
    // there is zero runtime cost — no broadcast receiver, no wasted
    // Binder round-trip.
    //
    // A separate isSupported() short-circuit here in the plugin
    // (versus letting the service reject the call) also means the
    // Samsung Active Key toggle in Settings can be authoritatively
    // gated on the same helper — no divergence between "will we act
    // on it" and "will we show the toggle".
    private fun autoStartSamsungActiveKeyIfEnabled() {
        val ctx = heldMapView?.context ?: return
        if (!com.atakmap.android.xv.util.SamsungActiveKey.isSupported(ctx)) {
            Log.i(TAG, "autoStartSamsungActiveKey: device does not have the Active Key — skipping")
            return
        }
        if (!settings.persistedSamsungActiveKeyEnabled()) {
            Log.i(TAG, "autoStartSamsungActiveKey: operator toggle OFF — skipping")
            return
        }
        Log.i(TAG, "autoStartSamsungActiveKey: enabling Samsung Active Key PTT")
        voiceClient?.setPersistent("samsungActiveKey") { it.setSamsungActiveKeyEnabled(true) }
        // Also start the foreground-KeyEvent fallback. Required on
        // Tab Active5-class firmware that doesn't emit HARD_KEY_REPORT;
        // harmless additive path on firmware that does (dispatcher's
        // OR-gate collapses duplicate edges by source).
        startSamsungActiveKeyForeground()
    }

    // Start the service-side Sonim button broadcast receivers AND the
    // plugin-side foreground KeyEvent readers iff both the device
    // capability check AND the operator's persisted toggle agree.
    // Gate ordering (capability first) matters: on non-Sonim hardware
    // we never even ask the service to start the readers, so there is
    // zero runtime cost — no broadcast receiver, no OnKeyListener, no
    // wasted Binder round-trip.
    //
    // The PTT + Emergency toggles are independent — the operator may
    // enable one but not the other — so we check them separately.
    private fun autoStartSonimButtonsIfEnabled() {
        val ctx = heldMapView?.context ?: return
        if (!com.atakmap.android.xv.util.SonimHardwareButtons.isSupported(ctx)) {
            Log.i(TAG, "autoStartSonimButtons: device is not a supported Sonim ruggedized model — skipping")
            return
        }
        // No XV-local toggle gate — the phone's own Programmable Keys
        // menu is the source of truth for whether XV should catch a
        // given key. Start all three readers unconditionally on
        // supported Sonim hardware; if the operator hasn't assigned a
        // key to ATAK, no broadcast / KeyEvent arrives and the
        // corresponding reader silently sits idle. Removes the dev-
        // iteration foot-gun where `adb -Uninstall` wiped the toggle
        // and left the operator confused about why a hardware button
        // "stopped working."
        Log.i(TAG, "autoStartSonimButtons: starting Sonim readers (PTT foreground, Emergency foreground, AssignedApp broadcast)")
        voiceClient?.setPersistent("sonimPttButton") { it.setSonimPttButtonEnabled(true) }
        startSonimPttForeground()
        voiceClient?.setPersistent("sonimEmergencyButton") { it.setSonimEmergencyButtonEnabled(true) }
        startSonimEmergencyForeground()
        startSonimAssignedApp()
    }

    /**
     * Attach the [com.atakmap.android.xv.ptt.SamsungActiveKeyForegroundReader]
     * to the MapView. Idempotent. The reader translates a foreground
     * `KEYCODE == 1015` down / up into a
     * `PttSource.SAMSUNG_ACTIVE_KEY` edge dispatched to the service
     * via [IXvVoice.notifySamsungActiveKeyEdge]. Runs in parallel with
     * the service-side broadcast reader — the dispatcher's OR-gate
     * dedupes if both paths ever fire for the same press.
     *
     * Foreground-only by design: `InputDispatcher` routes non-broadcast
     * keys to the top activity, so this reader only sees events while
     * ATAK is what the operator is looking at. The broadcast path
     * ([com.atakmap.android.xv.ptt.SamsungActiveKeyReader], in the
     * service) is still the ideal for backgrounded PTT, but on
     * firmware that doesn't emit `HARD_KEY_REPORT` (verified on
     * Tab Active5 / SM-X308U 2026-07-10), this KeyEvent path is the
     * ONLY signal the Active Key produces.
     */
    private fun startSamsungActiveKeyForeground() {
        val mapView = heldMapView ?: return
        val ctx = mapView.context
        if (!com.atakmap.android.xv.util.SamsungActiveKey.isSupported(ctx)) {
            // Same device gate as the broadcast path — no OnKeyListener
            // on non-Samsung hardware, zero cost on Pixel / Motorola /
            // etc.
            return
        }
        if (samsungActiveKeyFg != null) {
            Log.i(TAG, "startSamsungActiveKeyForeground: already attached — ignoring")
            return
        }
        val reader =
            com.atakmap.android.xv.ptt.SamsungActiveKeyForegroundReader { isDown, _ ->
                // We deliberately drop the source arg here — the AIDL
                // hop is source-implicit (the receiver on the service
                // side always tags SAMSUNG_ACTIVE_KEY). The Boolean is
                // enough to preserve edge direction across the process
                // boundary.
                voiceClient?.ifBound { it.notifySamsungActiveKeyEdge(isDown) }
            }
        if (reader.start(mapView)) {
            samsungActiveKeyFg = reader
        } else {
            Log.w(TAG, "startSamsungActiveKeyForeground: reader.start() failed — leaving detached")
        }
    }

    /**
     * Detach the foreground KeyEvent reader from the MapView.
     * Idempotent — safe to call if never started. If the operator
     * toggles the feature off mid-press, we also fire a defensive
     * `notifySamsungActiveKeyEdge(isDown = false)` so the service-side
     * dispatcher doesn't hold a stranded source in its OR-gate. On the
     * broadcast side the service already runs `forgetSource(...)` on
     * `stopSamsungActiveKey()`; this mirrors that guarantee for the
     * foreground path.
     */
    private fun stopSamsungActiveKeyForeground() {
        val reader = samsungActiveKeyFg ?: return
        try {
            reader.stop(heldMapView)
        } catch (t: Throwable) {
            Log.w(TAG, "stopSamsungActiveKeyForeground: reader.stop() threw", t)
        }
        samsungActiveKeyFg = null
        // Defensive release: if the operator toggled the feature off
        // with the Active Key held down, the service still thinks
        // SAMSUNG_ACTIVE_KEY is a live source. Sending an up() here is
        // idempotent when the source isn't held.
        try {
            voiceClient?.ifBound { it.notifySamsungActiveKeyEdge(false) }
        } catch (t: Throwable) {
            Log.w(TAG, "defensive notifySamsungActiveKeyEdge(false) threw", t)
        }
    }

    /**
     * Returns true when the [SamsungActiveKeyAccessibilityService] is
     * currently listed in the system's enabled-accessibility-services
     * set. Uses [android.view.accessibility.AccessibilityManager] so
     * the check reflects the actual OS-level state rather than any
     * XV-side persisted flag.
     *
     * Safe to call on any thread; [AccessibilityManager] is thread-safe.
     */
    private fun isSamsungActiveKeyAccessibilityServiceEnabled(context: android.content.Context): Boolean {
        return try {
            val am = context.getSystemService(android.content.Context.ACCESSIBILITY_SERVICE)
                as? android.view.accessibility.AccessibilityManager
                ?: return false
            val enabled = am.getEnabledAccessibilityServiceList(
                android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK,
            )
            val targetComponent =
                com.atakmap.android.xv.ptt.SamsungActiveKeyAccessibilityService.COMPONENT_NAME
            enabled.any { info ->
                info.resolveInfo?.serviceInfo?.let { si ->
                    android.content.ComponentName(si.packageName, si.name).flattenToString() == targetComponent
                } ?: false
            }
        } catch (t: Throwable) {
            Log.w(TAG, "isSamsungActiveKeyAccessibilityServiceEnabled: query threw", t)
            false
        }
    }

    /**
     * Attach the [com.atakmap.android.xv.ptt.SonimPttForegroundReader]
     * to the MapView. Idempotent. Foreground-only by design —
     * complements the backgrounded-safe broadcast reader in the
     * service. Both paths coexist and dedupe via
     * [com.atakmap.android.xv.audio.PttDispatcher]'s source-based
     * OR-gate.
     */
    private fun startSonimPttForeground() {
        val mapView = heldMapView ?: return
        val ctx = mapView.context
        if (!com.atakmap.android.xv.util.SonimHardwareButtons.isSupported(ctx)) {
            return
        }
        if (sonimPttFg != null) {
            Log.i(TAG, "startSonimPttForeground: already attached — ignoring")
            return
        }
        val reader =
            com.atakmap.android.xv.ptt.SonimPttForegroundReader { isDown, _ ->
                // Source is source-implicit across the AIDL — the
                // receiver on the service side always tags SONIM_PTT.
                voiceClient?.ifBound { it.notifySonimPttEdge(isDown) }
            }
        if (reader.start(mapView)) {
            sonimPttFg = reader
        } else {
            Log.w(TAG, "startSonimPttForeground: reader.start() failed — leaving detached")
        }
    }

    /**
     * Detach the Sonim PTT foreground reader from the MapView.
     * Idempotent. Fires a defensive `notifySonimPttEdge(false)` on
     * detach so the service dispatcher doesn't strand SONIM_PTT in
     * its held-source set.
     */
    private fun stopSonimPttForeground() {
        val reader = sonimPttFg ?: return
        try {
            reader.stop(heldMapView)
        } catch (t: Throwable) {
            Log.w(TAG, "stopSonimPttForeground: reader.stop() threw", t)
        }
        sonimPttFg = null
        try {
            voiceClient?.ifBound { it.notifySonimPttEdge(false) }
        } catch (t: Throwable) {
            Log.w(TAG, "defensive notifySonimPttEdge(false) threw", t)
        }
    }

    /** Attach the Sonim Emergency foreground reader. See [startSonimPttForeground]. */
    private fun startSonimEmergencyForeground() {
        val mapView = heldMapView ?: return
        val ctx = mapView.context
        if (!com.atakmap.android.xv.util.SonimHardwareButtons.isSupported(ctx)) {
            return
        }
        if (sonimEmergencyFg != null) {
            Log.i(TAG, "startSonimEmergencyForeground: already attached — ignoring")
            return
        }
        val reader =
            com.atakmap.android.xv.ptt.SonimEmergencyForegroundReader { isDown, _ ->
                voiceClient?.ifBound { it.notifySonimEmergencyEdge(isDown) }
            }
        if (reader.start(mapView)) {
            sonimEmergencyFg = reader
        } else {
            Log.w(TAG, "startSonimEmergencyForeground: reader.start() failed — leaving detached")
        }
    }

    /** Detach the Sonim Emergency foreground reader. See [stopSonimPttForeground]. */
    private fun stopSonimEmergencyForeground() {
        val reader = sonimEmergencyFg ?: return
        try {
            reader.stop(heldMapView)
        } catch (t: Throwable) {
            Log.w(TAG, "stopSonimEmergencyForeground: reader.stop() threw", t)
        }
        sonimEmergencyFg = null
        try {
            voiceClient?.ifBound { it.notifySonimEmergencyEdge(false) }
        } catch (t: Throwable) {
            Log.w(TAG, "defensive notifySonimEmergencyEdge(false) threw", t)
        }
    }

    /**
     * Start the SonimAssignedAppReader — the receiver for pkg-scoped
     * broadcasts Sonim fires when the operator assigns Programmable
     * Keys → ATAK. Runs in ATAK's process because the intents carry
     * `pkg=com.atakmap.app.civ` and pkg-scoped delivery only reaches
     * receivers in that package's process. Field-verified on the AT&T
     * XP9900 (2026-07-14): Yellow key emits YELLOW_KEY_DOWN/_UP and
     * SOS key emits SOS_KEY_DOWN/_UP + KODIAK_SOS.
     *
     * Kept live from plugin load through destroy; the callbacks gate
     * on the individual Sonim toggle settings so a single reader
     * covers both PTT and Emergency routing. Idempotent.
     */
    @Suppress("ReturnCount")
    private fun startSonimAssignedApp() {
        val ctx = heldMapView?.context ?: return
        if (!com.atakmap.android.xv.util.SonimHardwareButtons.isSupported(ctx)) {
            return
        }
        if (sonimAssignedApp != null) {
            Log.i(TAG, "startSonimAssignedApp: already registered — ignoring")
            return
        }
        val reader =
            com.atakmap.android.xv.ptt.SonimAssignedAppReader(
                context = ctx,
                onPttKeyEdge = { isDown ->
                    // Assigned-app PTT — delivered as the YELLOW_KEY
                    // broadcast (Sonim's API naming is backwards; the
                    // "Yellow" action is the physical PTT button).
                    // Source-implicit across the AIDL — the service side
                    // tags SONIM_PTT, same as SonimPttForegroundReader.
                    voiceClient?.ifBound { it.notifySonimPttEdge(isDown) }
                },
                onSosKeyEdge = { isDown ->
                    // SOS key → emergency-alert path (matches
                    // AINA-PTTE parity from commit 4e12933). Not
                    // gated on an XV settings toggle — the phone's
                    // Programmable Keys → app assignment is the
                    // authoritative on/off (if the operator hasn't
                    // assigned SOS to ATAK, no broadcast arrives and
                    // this callback never fires).
                    voiceClient?.ifBound { it.notifySonimEmergencyEdge(isDown) }
                },
            )
        if (reader.start()) {
            sonimAssignedApp = reader
        } else {
            Log.w(TAG, "startSonimAssignedApp: reader.start() failed — leaving detached")
        }
    }

    /** Idempotent. */
    private fun stopSonimAssignedApp() {
        val reader = sonimAssignedApp ?: return
        try {
            reader.stop()
        } catch (t: Throwable) {
            Log.w(TAG, "stopSonimAssignedApp: reader.stop() threw", t)
        }
        // Defensive release so a PTT held at teardown doesn't strand
        // SONIM_PTT in the dispatcher's held-source set (mirrors
        // stopSonimPttForeground).
        try {
            voiceClient?.ifBound { it.notifySonimPttEdge(false) }
        } catch (t: Throwable) {
            Log.w(TAG, "stopSonimAssignedApp: defensive notifySonimPttEdge(false) threw", t)
        }
        sonimAssignedApp = null
    }

    private fun autoConnectMumble() {
        // Slight delay so the rest of plugin init (cert lookup, presence
        // layer registration) is settled before we open the TLS sockets.
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
            { connectMumbleWithDefaults() },
            1000,
        )
    }

    @SuppressWarnings("MissingPermission")
    private fun autoConnectAina() {
        // Slight delay (matches Mumble) so the BT adapter is settled. Also
        // lets the BluetoothHeadset profile proxy in BtAudioPolicy attach,
        // since classify() is keyed off it for the HFP_ONLY decision.
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            val ctx = heldPluginContext ?: return@postDelayed
            // Startup-picker inputs. We compute the picker list up
            // front so both branches (restore-saved and auto-pick-
            // first-available) can consult live reachability rather
            // than blindly reconnecting to a MAC that's bonded but
            // powered off. This is the fix for the 2026-07-08 field
            // report: "on startup XV was connecting to a device that
            // wasn't even available."
            val candidates = listBondedAinaDevices()
            val savedMac = settings.persistedAinaMac()
            if (savedMac != null) {
                // Explicit operator pick wins IF that device is
                // currently reachable. Falling through to auto-pick
                // when the saved MAC is bonded-but-off lets the
                // operator's session actually route to whatever puck
                // they turned on, instead of stalling on a stale
                // preference. The saved MAC is deliberately NOT
                // cleared — when the operator's preferred device
                // powers back on, the picker refresh + next launch
                // will restore it as first-class.
                val saved = candidates.firstOrNull { it.mac.equals(savedMac, ignoreCase = true) }
                if (saved != null && saved.available) {
                    Log.i(
                        TAG,
                        "autoConnectAina: saved selection $savedMac is reachable — restoring",
                    )
                    connectSavedAinaPrimary(ctx, savedMac)
                    return@postDelayed
                }
                if (saved != null && !saved.available) {
                    Log.i(
                        TAG,
                        "autoConnectAina: saved selection $savedMac bonded but not currently reachable — " +
                            "considering first-available fallback (saved pref preserved)",
                    )
                } else {
                    Log.i(
                        TAG,
                        "autoConnectAina: saved MAC $savedMac not in picker list — " +
                            "considering first-available fallback",
                    )
                }
                // Fall through to auto-pick below, but only if the
                // operator hasn't disabled auto-connect. That toggle
                // is the "I'll pick manually" opt-out for operators
                // running multiple speakermics for testing — respect
                // it even when the saved device is off.
                if (!settings.persistedAutoConnectBtEnabled()) {
                    Log.i(
                        TAG,
                        "autoConnectAina: auto-connect disabled — leaving saved pref in place, no connect",
                    )
                    return@postDelayed
                }
            } else if (!settings.persistedAutoConnectBtEnabled()) {
                Log.i(TAG, "autoConnectAina: no saved selection and auto-connect disabled — skipping")
                return@postDelayed
            }
            // Auto-pick: prefer the first AVAILABLE speakermic-class
            // device (SPP / BLE / AUDIO_ONLY) in the picker list.
            // BLE_HID pucks are button-only and are handled by
            // autoConnectExternalButton — they must never win the
            // primary slot even when they're the only "available"
            // candidate. BLE_HID availability is unobservable so
            // those rows are always marked available=true; without
            // the filter, a bonded-but-off Pryme puck was winning
            // firstOrNull { available } whenever the operator's AINA
            // was powered down at plugin load (reported 2026-07-10).
            if (candidates.isEmpty()) {
                Log.i(TAG, "autoConnectAina: no compatible bonded device found — nothing to auto-pick")
                return@postDelayed
            }
            val picked = com.atakmap.android.xv.aina.AinaDeviceClassifier.pickPrimary(candidates)
            if (picked == null) {
                Log.i(
                    TAG,
                    "autoConnectAina: ${candidates.size} bonded candidate(s) but no available speakermic " +
                        "(button-only pucks are ineligible for primary) — waiting for a speakermic to come up",
                )
                return@postDelayed
            }
            Log.i(
                TAG,
                "autoConnectAina: auto-picked first-available device → ${picked.name} ${picked.mac} " +
                    "(${picked.buttonProtocol})",
            )
            // Persist so it sticks across launches AND so the picker UI
            // shows the auto-picked device as the current selection.
            // Operator can change the pick later; "(none)" in the
            // picker then disconnects, and the next launch's auto-pick
            // is suppressed because savedMac is now blank-but-set if
            // we choose to write a "auto-pick locked off" sentinel.
            // For now: just persist the MAC like a manual pick would.
            settings.persistAinaMac(picked.mac)
            lastAinaMac = picked.mac
            connectAinaInternal(ctx, picked.mac, null, "auto")
        }, 1200)
    }

    @SuppressWarnings("MissingPermission")
    private fun connectSavedAinaPrimary(
        ctx: Context,
        savedMac: String,
    ) {
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return
        val bonded =
            try {
                adapter.bondedDevices ?: emptySet()
            } catch (t: Throwable) {
                Log.w(TAG, "connectSavedAinaPrimary: bondedDevices threw", t)
                return
            }
        val device =
            bonded.firstOrNull { it.address.equals(savedMac, ignoreCase = true) }
                ?: run {
                    Log.w(TAG, "connectSavedAinaPrimary: saved MAC $savedMac no longer bonded")
                    return
                }
        Log.i(TAG, "connectSavedAinaPrimary: saved selection → ${device.name} ${device.address}")
        lastAinaMac = device.address
        connectAinaInternal(ctx, device.address, null, "auto")
    }

    // ============================================================
    // Settings → Preferences: AINA picker + TX/RX persistence
    // ============================================================

    // Snapshot of MACs currently reachable as communication-audio
    // endpoints. Intersecting the bonded-device set against this on the
    // picker gives operators an at-a-glance "which of my paired devices
    // is actually live right now?" — the field bug we're fixing had the
    // picker showing four bonded speakermics with no visual difference
    // between the one that was powered on and the three that had been
    // off for weeks.
    //
    // Uses [AudioManager.getAvailableCommunicationDevices] on API 31+
    // because bond state alone isn't enough — a bonded HFP device may
    // still not be routable to setCommunicationDevice until its profile
    // is connected in the audio policy. On pre-S (or when AudioManager
    // throws) we return null and the caller marks everything as
    // "available=true" so the picker degrades gracefully to the
    // pre-change behavior.
    //
    // MACs are upper-cased so the caller's lookup is case-insensitive.
    private fun reachableCommunicationMacsSnapshot(): Set<String>? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
        val ctx = heldMapView?.context ?: heldPluginContext ?: return null
        val am =
            try {
                ctx.getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager
            } catch (t: Throwable) {
                Log.w(TAG, "reachableCommunicationMacsSnapshot: AUDIO_SERVICE threw", t)
                null
            } ?: return null
        return try {
            am.availableCommunicationDevices
                .mapNotNull { it.address?.takeIf { addr -> addr.isNotBlank() }?.uppercase() }
                .toSet()
        } catch (t: Throwable) {
            Log.w(TAG, "reachableCommunicationMacsSnapshot: availableCommunicationDevices threw", t)
            null
        }
    }

    @SuppressWarnings("MissingPermission")
    private fun listBondedAinaDevices(): List<com.atakmap.android.xv.aina.AinaDeviceInfo> {
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return emptyList()
        val bonded =
            try {
                adapter.bondedDevices ?: emptySet()
            } catch (t: Throwable) {
                Log.w(TAG, "listBondedAinaDevices: bondedDevices threw", t)
                return emptyList()
            }
        // Live-reachability snapshot (null when we can't ask — pre-S
        // or missing service). null → treat every candidate as
        // "available" so we degrade to the legacy picker rendering.
        val reachableMacs = reachableCommunicationMacsSnapshot()
        // Show every bonded device that's plausibly a speakermic — i.e.
        // anything with HFP / SCO / SPP / known BLE PTT service in its
        // SDP cache, OR anything whose BT major class is AUDIO_VIDEO.
        // (Earlier code tried to additionally restrict to "currently
        // connected" via BluetoothManager.getConnectedDevices, but that
        // API only supports GATT / GATT_SERVER per Android docs and
        // throws UnsupportedOperationException for HEADSET / A2DP /
        // HID_DEVICE — the swallowed exception caused the picker to
        // be permanently empty. Removed.) Annotation of the live
        // connection state is tracked under task #34.
        // Listed devices are sorted by detected button protocol (SPP /
        // BLE / audio-only) so SPP/BLE devices we know how to drive end
        // up at the top.
        Log.i(TAG, "listBondedAinaDevices: scanning ${bonded.size} bonded device(s)")
        val candidates =
            bonded.map { dev ->
                val uuids =
                    try {
                        dev.uuids?.joinToString(prefix = "[", postfix = "]") { it.uuid.toString() }
                    } catch (
                        _: Throwable,
                    ) {
                        "<uuids-threw>"
                    }
                val cls =
                    try {
                        dev.bluetoothClass
                    } catch (_: Throwable) {
                        null
                    }
                val proto = com.atakmap.android.xv.aina.AinaDeviceClassifier.classifyButtonProtocol(dev)
                // PTT INPUT picker = devices that can drive a PTT
                // button. Audio-only or unknown devices are skipped
                // here because they belong in the AUDIO DEVICE picker
                // instead. Pryme BLE PTT (BLE_HID) qualifies even
                // though it has no speaker.
                val plausible = com.atakmap.android.xv.aina.AinaDeviceClassifier.isPlausibleSpeakermic(dev)
                val hasButton =
                    when (proto) {
                        com.atakmap.android.xv.aina.AinaDeviceInfo.ButtonProtocol.SPP,
                        com.atakmap.android.xv.aina.AinaDeviceInfo.ButtonProtocol.BLE,
                        com.atakmap.android.xv.aina.AinaDeviceInfo.ButtonProtocol.BLE_HID,
                        -> true
                        com.atakmap.android.xv.aina.AinaDeviceInfo.ButtonProtocol.AUDIO_ONLY,
                        com.atakmap.android.xv.aina.AinaDeviceInfo.ButtonProtocol.UNKNOWN,
                        -> false
                    }
                val accept = plausible && hasButton
                Log.i(
                    TAG,
                    "  bonded: name='${dev.name ?: "?"}' addr=${dev.address} type=${dev.type} " +
                        "majorClass=${cls?.majorDeviceClass ?: "?"} proto=$proto plausible=$plausible " +
                        "hasButton=$hasButton uuids=$uuids → ${if (accept) "INCLUDE" else "skip"}",
                )
                // V2 SDP-cache gap: opportunistically probe APTT devices
                // that classify as SPP so the next picker open shows BLE
                // if the probe wins. Bounded to once-per-MAC per probe
                // lifetime by [AinaProtocolProbe.probeOpportunistically].
                if (proto == com.atakmap.android.xv.aina.AinaDeviceInfo.ButtonProtocol.SPP) {
                    heldPluginContext?.let { ctx ->
                        ainaProbeFor(ctx).probeOpportunistically(
                            dev,
                            { settings.persistedAinaProtocolOverride(it) },
                            { mac, kind -> settings.persistAinaProtocolOverride(mac, kind) },
                        )
                    }
                }
                Triple(dev, proto, accept)
            }
        val bondedResults =
            candidates
                .filter { it.third }
                .map { (dev, proto, _) ->
                    // BLE-HID buttons never appear in the audio-policy
                    // communication-device list (they're HID over GATT,
                    // not a routable audio endpoint), so we can't ask
                    // AudioManager whether they're reachable. Mark them
                    // as "available=true" so the picker doesn't dim
                    // every BLE PTT puck the operator has ever paired.
                    // For SPP / BLE / AUDIO_ONLY the audio-policy check
                    // is authoritative; when we couldn't take a snapshot
                    // (reachableMacs == null) fall back to "available".
                    val isAvailable =
                        when {
                            reachableMacs == null -> true
                            proto == com.atakmap.android.xv.aina.AinaDeviceInfo.ButtonProtocol.BLE_HID -> true
                            else -> reachableMacs.contains(dev.address.uppercase())
                        }
                    com.atakmap.android.xv.aina.AinaDeviceInfo(
                        mac = dev.address,
                        name = dev.name ?: dev.address,
                        buttonProtocol = proto,
                        available = isAvailable,
                    )
                }
        // Merge manually-added BLE PTT devices (PTT-Z01, Pryme
        // BT-PTT-Z, etc.). HM-10 based buttons frequently don't bond
        // via system BT (PTT-Z01 confirmed on 2026-07-06) or don't
        // expose an SDP UUID set the classifier accepts, so the bonded
        // list alone will not surface them. Persisted entries take
        // precedence for the name (operator-visible label from the
        // scan-and-pick dialog) but we defer to a bonded entry if
        // both exist to preserve any live classifier result.
        //
        // Known BLE PTT are always marked available — they connect via
        // BluetoothAdapter.getRemoteDevice on demand, never appear in
        // AudioManager's comm-device list, and their reachability isn't
        // reliably observable without a full GATT connect.
        val bondedMacs = bondedResults.map { it.mac.uppercase() }.toSet()
        val knownBlePtt =
            settings.knownBlePttDevices().mapNotNull { (mac, name) ->
                if (bondedMacs.contains(mac.uppercase())) return@mapNotNull null
                com.atakmap.android.xv.aina.AinaDeviceInfo(
                    mac = mac,
                    name = name ?: mac,
                    buttonProtocol = com.atakmap.android.xv.aina.AinaDeviceInfo.ButtonProtocol.BLE_HID,
                    available = true,
                )
            }
        // Rank order:
        //   1. Available devices (rendered normal + tappable) first, so
        //      the operator's tap-target is whichever speakermic is
        //      actually live.
        //   2. Then by button protocol so we prefer SPP → BLE →
        //      BLE_HID → audio-only within each availability tier
        //      (unchanged from the pre-change ordering).
        //   3. Then by name for a stable order across refreshes.
        // Inspired by how call apps (Meet, WhatsApp) render device
        // pickers: reachable devices bubble to the top, unreachable
        // ones are still shown so the operator can see the pairing
        // exists but they can't be tapped by mistake.
        return com.atakmap.android.xv.aina.AinaDeviceClassifier
            .rankForPicker(bondedResults + knownBlePtt)
    }

    // Speakermic detection + protocol classification extracted to
    // [com.atakmap.android.xv.aina.AinaDeviceClassifier] during the
    // L5+L6 split. Pure functions of BluetoothDevice — independently
    // unit-testable.

    private fun ainaProbeFor(ctx: Context): com.atakmap.android.xv.aina.AinaProtocolProbe =
        ainaProbe ?: com.atakmap.android.xv.aina.AinaProtocolProbe(ctx).also { ainaProbe = it }

    // Operator-driven External Button slot change (picker on-select,
    // debug-intent MAC set, future automation). Persists the pick,
    // then dispatches through [ExternalButtonReaderResolver] to decide
    // whether the currently-running reader (if any) needs to be torn
    // down and / or respawned under the new MAC.
    //
    // Field bug this closes: on 2026-07-11 (Pixel 9 Pro, Pryme
    // BT-PTT-Z puck) the picker showed the puck as "Connected" but
    // physical button presses did NOT reach XvVoicePlant — no
    // `external button PTT down=true` events. Only after toggling
    // the picker to "(none)" and then re-assigning the puck did
    // events start dispatching. Same class of reader-lifecycle race
    // PR #35 fixed for the primary AINA (button-protocol change on
    // a connected device did not respawn the reader). Mirrors that
    // fix by routing every operator-driven MAC change through the
    // shared [PttReaderRespawnDecision] matrix and calling the same
    // teardown → connect sequence the primary AINA path uses. See
    // [ExternalButtonReaderResolver] for the pure decision.
    //
    // Reentry guarded because the tear-down + respawn is not atomic
    // across the AIDL boundary — a concurrent picker tap racing the
    // sequence could double-teardown or leave a stale reader stitched
    // to a disposed GATT client.
    @SuppressWarnings("MissingPermission")
    private fun setSelectedExternalButtonInternal(mac: String?) {
        // Guarded against reentry from a picker tap racing an
        // in-flight tear-down + respawn on the External Button slot.
        // Same shape as [setAinaButtonProtocolInternal]'s
        // [reInitingReaderKind] guard.
        if (reInitingExternalButtonReader) {
            Log.i(
                TAG,
                "setSelectedExternalButtonInternal: reader re-init already in flight — dropping " +
                    "request for mac=${com.atakmap.android.xv.aina.redactMac(mac)}",
            )
            return
        }
        // Normalise inputs before persist so the primary-collision
        // check and the resolver's MAC comparison see the same
        // string the picker offered up.
        val normalizedNewMac = mac?.trim()?.takeIf { it.isNotBlank() }
        // Refuse silently if the picked MAC collides with the
        // primary — picker already filters it out, but defend in
        // depth. Do this BEFORE persisting so we don't clobber a
        // valid external-button pick with a bad one.
        if (normalizedNewMac != null) {
            val primary = settings.persistedAinaMac()
            if (primary != null && primary.equals(normalizedNewMac, ignoreCase = true)) {
                Log.w(
                    TAG,
                    "setSelectedExternalButtonInternal: ${com.atakmap.android.xv.aina.redactMac(normalizedNewMac)} " +
                        "collides with primary — ignored",
                )
                // Clear any prior external-button pick to avoid a
                // stale MAC pointing at the primary.
                settings.persistExternalButtonMac(null)
                return
            }
        }
        // Persist the pick. If the resolver decides NO_OP below, this
        // is the only side effect — the next connect edge picks it up.
        settings.persistExternalButtonMac(normalizedNewMac)

        // Resolver inputs: [lastExternalButtonMac] is the MAC the
        // plugin last handed to the service (source of truth for
        // "what reader is currently running"), [normalizedNewMac] is
        // the operator's new pick, and [isConnected] is
        // [lastExternalButtonConnected] — the optimistic-cache flag
        // set on connectExternalButton and cleared on disconnect.
        val currentMac = lastExternalButtonMac
        val isConnected = lastExternalButtonConnected
        val decision =
            com.atakmap.android.xv.aina.ExternalButtonReaderResolver.shouldRespawnReader(
                currentMac = currentMac,
                newMac = normalizedNewMac,
                isConnected = isConnected,
            )
        Log.i(
            TAG,
            "setSelectedExternalButtonInternal: currentMac=${com.atakmap.android.xv.aina.redactMac(currentMac)} " +
                "newMac=${com.atakmap.android.xv.aina.redactMac(normalizedNewMac)} " +
                "isConnected=$isConnected → $decision",
        )
        when (decision) {
            com.atakmap.android.xv.aina.PttReaderRespawnDecision.Decision.NO_OP -> {
                // Persist-only path. If the operator picked (none) on
                // an already-disconnected slot, still clear the kind
                // so the next auto-connect doesn't try to reuse a
                // stale kind string. If the operator picked the SAME
                // MAC as the running reader, leave the kind persistence
                // alone.
                if (normalizedNewMac == null) {
                    settings.persistExternalButtonKind(null)
                }
                return
            }
            com.atakmap.android.xv.aina.PttReaderRespawnDecision.Decision.TEARDOWN_ONLY -> {
                // Operator picked (none) on a live reader. Tear down
                // the reader and clear plugin-side bookkeeping. The
                // External Button slot has no audio path, so unlike
                // the primary AINA's TEARDOWN_ONLY there's nothing
                // to preserve — a full [disconnectExternalButton] on
                // the service is exactly right.
                reInitingExternalButtonReader = true
                try {
                    voiceClient?.ifBound { it.disconnectExternalButton() }
                    lastExternalButtonMac = null
                    lastExternalButtonConnected = false
                    settings.persistExternalButtonKind(null)
                } finally {
                    reInitingExternalButtonReader = false
                }
                return
            }
            com.atakmap.android.xv.aina.PttReaderRespawnDecision.Decision.RESPAWN -> {
                // New MAC OR "no reader → live reader" transition on
                // an already-connected slot. Resolve kind fresh (in
                // case the operator scanned in a new BLE PTT device
                // between config edits) and re-attach. VoicePlant's
                // [connectExternalButton] already calls
                // [disconnectExternalButton] at its entry point so
                // we don't need a manual teardown here — one AIDL
                // round-trip handles both edges. Guard covers the
                // window between the plugin's persistExternalButtonKind
                // and the service's reader flip so a concurrent
                // config change can't race it.
                //
                // Precondition: RESPAWN implies newMac is non-null
                // (the "live → no reader" flip lands in TEARDOWN_ONLY
                // above; the "no reader → no reader" flip lands in
                // NO_OP). Guarding with !! to make the contract
                // explicit; a null here would be a decision-matrix
                // bug worth surfacing loudly.
                val newMac = requireNotNull(normalizedNewMac) {
                    "ExternalButtonReaderResolver returned RESPAWN with null newMac"
                }
                val kind = resolveConnectKind(newMac)
                reInitingExternalButtonReader = true
                try {
                    Log.i(
                        TAG,
                        "setSelectedExternalButtonInternal: connecting ${com.atakmap.android.xv.aina.redactMac(newMac)} " +
                            "as kind=$kind",
                    )
                    settings.persistExternalButtonKind(kind)
                    lastExternalButtonMac = newMac
                    voiceClient?.ifBound { it.connectExternalButton(newMac, null, kind) }
                    // No service-side state-edge callback for the
                    // external button yet — optimistically cache
                    // "connected" so the UI shows progress. A future
                    // commit could thread an
                    // onExternalButtonConnectionChanged through the
                    // AIDL listener; for now the operator sees the
                    // picker status flip green on successful connect.
                    lastExternalButtonConnected = true
                } finally {
                    reInitingExternalButtonReader = false
                }
                return
            }
        }
    }

    // Shared MAC-format check for the scan-and-pick flow's primary /
    // external-button Controller methods. Uppercases, normalizes
    // dashes to colons, verifies the standard EUI-48 shape. Returns
    // the canonical form on success or null on invalid input so
    // callers can surface a specific error message.
    private fun normalizeAndValidateMac(mac: String): String? {
        val normalized = mac.trim().uppercase().replace('-', ':')
        val macRegex = Regex("^([0-9A-F]{2}:){5}[0-9A-F]{2}$")
        return if (macRegex.matches(normalized)) normalized else null
    }

    // Guard against the AIDL-bound path and the fallback timer both
    // firing autoConnectExternalButton (rare but possible if the bind
    // races the 3s fallback). At-most-once semantics protect the
    // service from a duplicate connectExternalButton call.
    @Volatile
    private var externalButtonAutoConnectFired: Boolean = false

    @SuppressWarnings("MissingPermission")
    private fun autoConnectExternalButton() {
        // Previously fired unconditionally on a 1400 ms postDelayed
        // handler. Field-observed 2026-07-11 on Pixel 9 Pro: force-stop
        // → cold relaunch left the External Button reader unspawned
        // for ~10 s until the operator manually poked the picker.
        // Root cause: the postDelayed runnable at 1400 ms could fire
        // BEFORE the voice service AIDL bind completed, at which
        // point voiceClient?.ifBound queued the connectExternalButton
        // call for later — but the plugin-side state
        // (lastExternalButtonMac / lastExternalButtonConnected) was
        // set immediately, so subsequent picker reads showed the slot
        // as "connected" while no reader existed. The connectExternalButton
        // call did eventually run once the binder came up, so the
        // reader would spawn a bit later — hence the 10 s recovery
        // window.
        //
        // Fix: wire off the AIDL bind callback via
        // [XvVoiceClient.whenBound]. When the binder is ready, run
        // the auto-connect. If already bound (subsequent plugin
        // reloads or hot restart against a still-running service),
        // it runs synchronously. A belt-and-suspenders 3000 ms
        // fallback runs the auto-connect anyway with a WARN log so a
        // wedged bind doesn't leave the External Button slot dark
        // forever. First-runner wins via [externalButtonAutoConnectFired].
        //
        // The primary AINA path ([autoConnectAina]) still uses its
        // 1400 ms delay — that path relies on OS-brokered SPP
        // auto-connect edges (BluetoothHeadset profile proxy) which
        // are a different problem class, and rewiring it is out of
        // scope for this fix (see PR body).
        val client = voiceClient
        if (client == null) {
            Log.w(TAG, "autoConnectExternalButton: voiceClient is null at scheduling time — cannot arm auto-connect")
            return
        }
        externalButtonAutoConnectFired = false
        client.whenBound("autoConnectExternalButton") {
            runAutoConnectExternalButton(source = "aidl-bound")
        }
        // Fallback so a wedged bind can't strand the External Button
        // reader indefinitely. 3000 ms is well past the observed cold-
        // launch bind latency on Pixel-class hardware (< 800 ms in
        // captures) and past the old 1400 ms timer that the field
        // still saw fire too early. If the bind DOES come in inside
        // that window (the common path), whenBound will have run
        // first and this handler no-ops.
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            if (externalButtonAutoConnectFired) return@postDelayed
            Log.w(
                TAG,
                "autoConnectExternalButton: AIDL bind still not up 3000 ms after schedule — " +
                    "running fallback auto-connect (belt-and-suspenders); pending actions will queue " +
                    "against the client until the bind lands",
            )
            runAutoConnectExternalButton(source = "fallback-timer")
        }, 3000)
    }

    // Actual auto-connect body. Guarded so only the first caller
    // (whenBound-triggered OR the 3 s fallback) runs it. Every branch
    // logs at INFO so a future field logcat shows exactly which path
    // was taken.
    @SuppressWarnings("MissingPermission")
    private fun runAutoConnectExternalButton(source: String) {
        if (externalButtonAutoConnectFired) {
            Log.i(TAG, "autoConnectExternalButton[$source]: already fired — skipping duplicate")
            return
        }
        externalButtonAutoConnectFired = true
        Log.i(TAG, "autoConnectExternalButton[$source]: entry")
        val savedMac = settings.persistedExternalButtonMac()
        if (savedMac != null) {
            Log.i(
                TAG,
                "autoConnectExternalButton[$source]: restoring saved MAC " +
                    com.atakmap.android.xv.aina.redactMac(savedMac),
            )
            connectSavedExternalButton(savedMac)
            Log.i(TAG, "autoConnectExternalButton[$source]: complete (restore path)")
            return
        }
        if (!settings.persistedAutoConnectBtEnabled()) {
            Log.i(TAG, "autoConnectExternalButton[$source]: no saved selection and auto-connect disabled — skipping")
            return
        }
        // Auto-pick an EXTERNAL BUTTON: prefer a BLE_HID puck
        // (typical motorcyclist handlebar button) over an
        // additional speakermic, since the operator's primary is
        // the audio path and a second audio device on slot 0 would
        // compete for SCO. Filter out the primary's MAC so we
        // don't pick the same device twice.
        val primaryMac = settings.persistedAinaMac()
        val candidates =
            listBondedAinaDevices()
                .filterNot {
                    primaryMac != null && it.mac.equals(primaryMac, ignoreCase = true)
                }
        if (candidates.isEmpty()) {
            Log.i(TAG, "autoConnectExternalButton[$source]: no compatible external-button candidate — skipping")
            return
        }
        val picked =
            candidates.firstOrNull {
                it.buttonProtocol == com.atakmap.android.xv.aina.AinaDeviceInfo.ButtonProtocol.BLE_HID
            } ?: run {
                // No BLE_HID puck bonded — don't auto-pick a second
                // speakermic. Audio routing only sensibly engages
                // one BT speakermic, so picking another would
                // create operator-surprise side effects. Operator
                // can still manually pick a second AINA in the
                // Settings → Preferences picker if they want it.
                Log.i(
                    TAG,
                    "autoConnectExternalButton[$source]: no BLE PTT button bonded — leaving external-button slot empty",
                )
                return
            }
        val kind =
            when (picked.buttonProtocol) {
                com.atakmap.android.xv.aina.AinaDeviceInfo.ButtonProtocol.BLE_HID -> "ble-hid"
                com.atakmap.android.xv.aina.AinaDeviceInfo.ButtonProtocol.SPP -> "v1"
                com.atakmap.android.xv.aina.AinaDeviceInfo.ButtonProtocol.BLE -> "v2"
                else -> "auto"
            }
        Log.i(
            TAG,
            "autoConnectExternalButton[$source]: auto-picked → ${picked.name} " +
                "${com.atakmap.android.xv.aina.redactMac(picked.mac)} kind=$kind",
        )
        settings.persistExternalButtonMac(picked.mac)
        settings.persistExternalButtonKind(kind)
        lastExternalButtonMac = picked.mac
        voiceClient?.ifBound { it.connectExternalButton(picked.mac, null, kind) }
        lastExternalButtonConnected = true
        Log.i(TAG, "autoConnectExternalButton[$source]: complete (auto-pick path)")
    }

    @SuppressWarnings("MissingPermission")
    private fun connectSavedExternalButton(savedMac: String) {
        val redactedSaved = com.atakmap.android.xv.aina.redactMac(savedMac)
        val adapter = BluetoothAdapter.getDefaultAdapter()
        if (adapter == null) {
            Log.w(TAG, "connectSavedExternalButton: no BT adapter — cannot restore $redactedSaved")
            return
        }
        // Known-BLE-PTT devices can be picked without a system bond
        // (PTT-Z01 in particular refuses to bond at all). If the saved
        // MAC is in that list, skip the bonded-devices lookup entirely
        // and drive the connect straight into the ble-hid reader path.
        // Without this, a cold launch with only a knownBlePtt-only puck
        // saved would take the "no longer bonded" WARN branch below
        // and never spawn the reader.
        val isKnownBlePtt =
            settings.knownBlePttDevices().any { (m, _) -> m.equals(savedMac, ignoreCase = true) }
        val bondedDevice: BluetoothDevice? =
            try {
                val bonded = adapter.bondedDevices ?: emptySet()
                bonded.firstOrNull { it.address.equals(savedMac, ignoreCase = true) }
            } catch (t: Throwable) {
                Log.w(TAG, "connectSavedExternalButton: bondedDevices threw", t)
                null
            }
        if (bondedDevice == null && !isKnownBlePtt) {
            // Saved MAC has drifted: not in bond table and not in
            // knownBlePtt. Log at WARN so field logcats surface the
            // drift instead of silently dropping the reader attach.
            // Deliberately don't clear the persisted MAC — the
            // operator may be mid-repair (re-bonding the puck) and
            // we don't want to forget for them. A follow-up UI hook
            // could offer to clear it; not in this PR.
            Log.w(
                TAG,
                "connectSavedExternalButton: saved MAC $redactedSaved no longer bonded and not in " +
                    "knownBlePtt — reader will not spawn; persisted MAC left in place for operator repair",
            )
            return
        }
        val primary = settings.persistedAinaMac()
        if (primary != null && primary.equals(savedMac, ignoreCase = true)) {
            Log.w(TAG, "connectSavedExternalButton: $redactedSaved collides with primary — skipping")
            return
        }
        // Live-resolve the kind on every connect. This closes the
        // staleness surface identified 2026-07-11: previously the
        // persisted kind was written once at pick time and never
        // re-checked, so an operator who added the same MAC to
        // knownBlePtt AFTER picking it wound up with a stale kind
        // that misclassified the reader on next cold launch. Live
        // resolve consults knownBlePtt first, then the SDP-based
        // classifier, then the persisted hint — see
        // [ExternalButtonKindResolver] for the precedence contract.
        val kind = resolveConnectKind(savedMac)
        val macForConnect = bondedDevice?.address ?: savedMac
        val nameForConnect = bondedDevice?.name
        Log.i(
            TAG,
            "connectSavedExternalButton: mac=$redactedSaved kind=$kind (knownBlePtt=$isKnownBlePtt, " +
                "bonded=${bondedDevice != null})",
        )
        lastExternalButtonMac = macForConnect
        voiceClient?.ifBound { it.connectExternalButton(macForConnect, nameForConnect, kind) }
        lastExternalButtonConnected = true
    }

    private fun setSelectedAinaInternal(mac: String?) {
        settings.persistAinaMac(mac)
        val ctx = heldPluginContext ?: return
        if (mac == null) {
            disconnectAinaInternal()
            lastAinaMac = null
            return
        }
        if (mac.equals(lastAinaMac, ignoreCase = true) && isAinaConnected()) {
            // Already on this device.
            return
        }
        lastAinaMac = mac
        val kind = resolveConnectKind(mac)
        Log.i(TAG, "setSelectedAinaInternal: connecting $mac as kind=$kind")
        connectAinaInternal(ctx, mac, null, kind)
    }

    // Operator-driven button-protocol flip on the CURRENTLY-selected
    // primary AINA. Persists the per-MAC override so it survives
    // reconnects, then — if the primary is currently connected and
    // the new kind differs from the running reader — either tears
    // the reader down (new kind == "no reader") or respawns it under
    // the new kind. See [AinaReaderKindResolver] for the pure
    // decision matrix.
    //
    // Rationale: the pre-fix setup persisted operator kind picks but
    // only re-read them on the next connect edge. If the operator
    // disconnected the speakermic, flipped kind, and reconnected while
    // the persisted value was different from the auto-detected value
    // for the newly-picked target, the reader spun up under the
    // wrong protocol and PTT buttons went silent until a full
    // disconnect / reconnect cycle. This method closes that gap.
    //
    // Reentry guarded because [connectAinaInternal] can itself re-
    // enter via [AinaProtocolProbe]'s SPP→BLE resolution callback.
    @SuppressWarnings("MissingPermission")
    private fun setAinaButtonProtocolInternal(kind: com.atakmap.android.xv.aina.AinaDeviceInfo.ButtonProtocol?) {
        // Guarded against reentry from AinaProtocolProbe's
        // SPP→BLE resolution callback (see [connectAinaInternal] —
        // the probe re-enters connectAinaInternal when it decides
        // BLE, and if a kind change lands in that window without the
        // guard the reader gets double-torn-down / double-respawned).
        if (reInitingReaderKind) {
            Log.i(
                TAG,
                "setAinaButtonProtocolInternal: reader re-init already in flight — dropping " +
                    "request for kind=$kind",
            )
            return
        }
        val mac = settings.persistedAinaMac()
        if (mac.isNullOrBlank()) {
            Log.i(TAG, "setAinaButtonProtocolInternal: no primary AINA selected — nothing to persist")
            return
        }
        // Persistence: map the enum to the override string XV already
        // uses ("v1" / "v2" / "ble-hid"). Null / AUDIO_ONLY / UNKNOWN
        // all mean "no reader" — clear the override so the next
        // connect edge falls through to auto-detect (which will pick
        // a real reader if the operator's audio device happens to
        // classify as one). This matches the pre-fix persistence
        // semantics — see [XvSettings.persistedAinaProtocolOverride].
        val protoString = protocolOverrideStringFor(kind)
        settings.persistAinaProtocolOverride(mac, protoString)
        val redactedMac = com.atakmap.android.xv.aina.redactMac(mac)
        Log.i(TAG, "setAinaButtonProtocolInternal: mac=$redactedMac kind=$kind proto='$protoString'")

        // Reader-lifecycle decision: currentKind + newKind + isConnected
        // → NO_OP / TEARDOWN_ONLY / RESPAWN. Pure logic lives in
        // [AinaReaderKindResolver] so it's independently unit-tested.
        val currentKind = readerKindStringToEnum(currentAinaReaderKind)
        val isConnected = currentAinaDevice != null
        val decision =
            com.atakmap.android.xv.aina.AinaReaderKindResolver.shouldRespawnReader(
                currentKind = currentKind,
                newKind = kind,
                isConnected = isConnected,
            )
        Log.i(
            TAG,
            "setAinaButtonProtocolInternal: currentKind=$currentKind newKind=$kind " +
                "isConnected=$isConnected → $decision",
        )
        when (decision) {
            com.atakmap.android.xv.aina.AinaReaderKindResolver.Decision.NO_OP -> {
                // Persist-only; nothing else to do. The next connect
                // edge will pick up the new override via
                // [XvSettings.persistedAinaProtocolOverride].
                return
            }
            com.atakmap.android.xv.aina.AinaReaderKindResolver.Decision.TEARDOWN_ONLY -> {
                // Drop the reader but leave the audio route hint in
                // place. Operator flipped to "no buttons" but still
                // wants the speakermic's HFP audio path.
                reInitingReaderKind = true
                try {
                    voiceClient?.ifBound { it.disconnectAinaReaderOnly() }
                    currentAinaReaderKind = null
                } finally {
                    reInitingReaderKind = false
                }
                return
            }
            com.atakmap.android.xv.aina.AinaReaderKindResolver.Decision.RESPAWN -> {
                val ctx = heldPluginContext
                if (ctx == null) {
                    Log.w(TAG, "setAinaButtonProtocolInternal: no plugin context — skipping respawn")
                    return
                }
                // "auto" so [connectAinaInternal] runs its full
                // per-MAC override + classifier stack, which will
                // land on the string we just persisted above (or on
                // the classifier result when we persisted null /
                // AUDIO_ONLY / UNKNOWN → cleared override).
                reInitingReaderKind = true
                try {
                    Log.i(TAG, "setAinaButtonProtocolInternal: respawning reader on $redactedMac")
                    connectAinaInternal(ctx, mac, null, "auto")
                } finally {
                    reInitingReaderKind = false
                }
                return
            }
        }
    }

    // Map a [AinaDeviceInfo.ButtonProtocol] pick from the Controller
    // to the persistent override string XV writes to
    // SharedPreferences. Null / AUDIO_ONLY / UNKNOWN all clear the
    // override (return null); real reader kinds map to the strings
    // [connectAinaInternal] already understands.
    private fun protocolOverrideStringFor(kind: com.atakmap.android.xv.aina.AinaDeviceInfo.ButtonProtocol?): String? =
        when (kind) {
            com.atakmap.android.xv.aina.AinaDeviceInfo.ButtonProtocol.SPP -> "v1"
            com.atakmap.android.xv.aina.AinaDeviceInfo.ButtonProtocol.BLE -> "v2"
            com.atakmap.android.xv.aina.AinaDeviceInfo.ButtonProtocol.BLE_HID -> "ble-hid"
            com.atakmap.android.xv.aina.AinaDeviceInfo.ButtonProtocol.AUDIO_ONLY,
            com.atakmap.android.xv.aina.AinaDeviceInfo.ButtonProtocol.UNKNOWN,
            null,
            -> null
        }

    // Reverse of [protocolOverrideStringFor] for the reader-kind
    // tracker (which stores the resolved string used at the last
    // connectAina). "auto" defensively collapses to UNKNOWN — the
    // reader-lifecycle decider treats UNKNOWN as "no reader" but
    // that's only reachable when a device isn't connected (the
    // resolver's isConnected=false branch NO_OPs anyway).
    private fun readerKindStringToEnum(kindString: String?): com.atakmap.android.xv.aina.AinaDeviceInfo.ButtonProtocol? =
        when (kindString?.lowercase()) {
            "v1", "spp" -> com.atakmap.android.xv.aina.AinaDeviceInfo.ButtonProtocol.SPP
            "v2", "ble" -> com.atakmap.android.xv.aina.AinaDeviceInfo.ButtonProtocol.BLE
            "ble-hid", "hid" -> com.atakmap.android.xv.aina.AinaDeviceInfo.ButtonProtocol.BLE_HID
            null, "", "auto" -> null
            else -> null
        }

    // Resolve the reader "kind" string for a given MAC. Precedence:
    //
    //   1. If the MAC is in `knownBlePttDevices` (operator scanned it in
    //      via "Scan for BLE PTT device"), always "ble-hid" — HM-10 /
    //      PTT-Z pucks don't expose the BLE vendor UUID over BR/EDR SDP
    //      so the classifier can miss it entirely.
    //   2. Otherwise, whatever the SDP-based classifier says: SPP → v1,
    //      BLE → v2, BLE_HID → ble-hid, unknown → falls through.
    //   3. Otherwise, the last-persisted hint for this MAC (retained as
    //      a "last resort" so a bonded device the classifier can no
    //      longer see still spins up a reader instead of collapsing to
    //      "auto" = no reader).
    //   4. Otherwise "auto".
    //
    // Pure decision lives in [ExternalButtonKindResolver] so the
    // precedence rules are unit-testable independent of the Bluetooth
    // stack. This function is the live wrapper that runs the
    // classifier + hint reads and hands the results to the resolver.
    //
    // Field bug 2026-07-11: operator added the same Pryme puck to
    // knownBlePtt AFTER picking it as the External Button, and a
    // stale persisted kind then survived the knownBlePtt add. Fix is
    // in [connectSavedExternalButton] — call this method fresh at
    // connect time instead of reading `persistedExternalButtonKind()`
    // directly.
    private fun resolveConnectKind(mac: String): String {
        val isInKnownBlePtt =
            settings.knownBlePttDevices().any { (m, _) -> m.equals(mac, ignoreCase = true) }
        val classifierResult: String? =
            try {
                val adapter = BluetoothAdapter.getDefaultAdapter()
                val device = adapter?.getRemoteDevice(mac)
                if (device == null) {
                    null
                } else {
                    when (com.atakmap.android.xv.aina.AinaDeviceClassifier.classifyButtonProtocol(device)) {
                        com.atakmap.android.xv.aina.AinaDeviceInfo.ButtonProtocol.SPP -> "v1"
                        com.atakmap.android.xv.aina.AinaDeviceInfo.ButtonProtocol.BLE -> "v2"
                        com.atakmap.android.xv.aina.AinaDeviceInfo.ButtonProtocol.BLE_HID -> "ble-hid"
                        else -> null
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "could not classify $mac for kind, falling back to hint / auto", t)
                null
            }
        val persistedHint = settings.persistedExternalButtonKind()
        return com.atakmap.android.xv.aina.ExternalButtonKindResolver.resolve(
            isInKnownBlePtt = isInKnownBlePtt,
            classifierResult = classifierResult,
            persistedHint = persistedHint,
        )
    }

    private fun isAinaConnected(): Boolean = ainaBle != null || ainaSpp != null

    // SharedPreferences MUST be backed by ATAK's own context, not the
    // plugin context. See the [XvSettings] class for the full extracted
    // surface — the `settings` field above wraps a prefs-provider that
    // resolves heldMapView.context lazily, so the on-disk file lives at
    // /data/user/0/com.atakmap.app.civ/shared_prefs/xv_settings.xml
    // (writable by our process) rather than the unwritable plugin path.

    // Single source of truth for "connect with whatever defaults we'd
    // use on a clean start". Both the auto-connect path and the
    // Settings → Connect button funnel through here so they always
    // resolve to the same host/channel set. Pure data — no hardcoded
    // host or channel. If TakServerDiscovery has no connected/configured
    // server we refuse to connect (no point dialing nowhere; cert
    // lookup would fail anyway). The user picks a channel after
    // connect via the channel picker; we don't pre-select one.
    private fun connectMumbleWithDefaults() {
        val defaultPort = 64738
        // Auto-rejoin the last channel the operator was on. Persisted in
        // PREF_PRIMARY_CHANNEL by onChannelChanged on every slot-0 move.
        // OTS will often place new sessions in a server-side default
        // (e.g. REACT for a particular ACL group) — passing our last
        // channel as initialChannel makes the post-ServerSync auto-join
        // override that placement so the operator lands where they
        // expect. Falls back to "" (Lobby) on first install or after
        // the upgrade-time legacy-cleanup wiped the pref.
        val raw = settings.persistedPrimaryChannel()
        val defaultChannel =
            if (raw.startsWith("TAK PRIVATE - ")) {
                // Defensive: the persistence-skip guard for temp call
                // channels covers go-forward writes, but an older
                // install could have an in-call name baked in. Strip it
                // so we don't try to rejoin a server-deleted temp.
                Log.w(TAG, "PREF_PRIMARY_CHANNEL='$raw' is a stale temp — ignoring")
                ""
            } else {
                raw
            }
        val defaultSecondary = lastSecondaryChannel
        // Honor the operator's explicit pick (PREF_TAK_SERVER_HOST, set via
        // the Settings → Server picker) before falling back to auto. The
        // pref is exact-match by host string; a stale pref (host since
        // unenrolled in ATAK) silently falls back without clearing — the
        // operator may be mid-reconfig and we don't want to forget for
        // them.
        val preferred = settings.persistedPreferredTakHost()
        val pick =
            try {
                TakServerDiscovery.pickPreferred(preferred)
            } catch (t: Throwable) {
                null
            }
        if (pick == null) {
            Log.i(
                TAG,
                "connectMumbleWithDefaults: no TAK server configured in ATAK — skipping " +
                    "(enroll a TAK server first; XV will auto-connect on next plugin load)",
            )
            return
        }
        val source = if (preferred.isNullOrBlank()) "auto" else "pref"
        Log.i(
            TAG,
            "connectMumbleWithDefaults: host=${pick.host} (from=$source) " +
                "port=$defaultPort channel='$defaultChannel' secondary='$defaultSecondary'",
        )
        startMumbleInternal(
            explicitHost = pick.host,
            takPattern = null,
            port = defaultPort,
            channel = defaultChannel,
            secondaryChannel = defaultSecondary,
        )
    }

    private fun startMumbleInternal(
        explicitHost: String?,
        takPattern: String?,
        port: Int,
        channel: String,
        secondaryChannel: String? = null,
        vxCompatOverride: VxCompat? = null,
    ) {
        // Empty channel = no auto-join. Operator picks one via the
        // channel picker after the Mumble session is up. We used to
        // hardcode "REACT" here as a testing convenience — that leaked
        // into clean-install installations and showed up as a fake
        // channel for users who never configured one. Empty string
        // means "stay at Root until the operator picks a channel."
        val effectiveChannel = channel
        stopActiveTransport()
        // Default UX: derive host from the connected TAK server (OTS hosts
        // both TAK and Mumble on the same hostname). Allow explicit host as
        // an override for non-OTS deployments / testing.
        val resolvedHost: String
        if (!explicitHost.isNullOrBlank()) {
            resolvedHost = explicitHost
        } else {
            val pick = TakServerDiscovery.pick(takPattern)
            if (pick == null) {
                Log.w(TAG, "MUMBLE_CONNECT: no host given and no matching TAK server (try MUMBLE_LIST_TAK)")
                return
            }
            resolvedHost = pick.host
        }
        activeMumbleHost = resolvedHost
        val effectiveCompat = vxCompatOverride ?: defaultVxCompat
        val deviceUid = MumbleAuth.deviceUid()
        Log.i(
            TAG,
            "MUMBLE_CONNECT resolved host=$resolvedHost port=$port channel='$effectiveChannel' " +
                "vxCompat=$effectiveCompat deviceUid=$deviceUid",
        )
        val effectiveSecondary = secondaryChannel?.takeIf { it.isNotBlank() } ?: lastSecondaryChannel
        if (effectiveSecondary != null) {
            lastSecondaryChannel = effectiveSecondary
        }
        val cfg =
            TransportConfig.Mumble(
                host = resolvedHost,
                port = port,
                username = "(set-by-MumbleAuth)",
                password = null,
                channelName = effectiveChannel,
                vxCompat = effectiveCompat,
                deviceUid = deviceUid,
                secondaryChannelName = effectiveSecondary,
            )
        // Forward incoming Opus across to the service-side voice plant
        // (decode + AudioTrack happen there, in our APK's UID, where
        // they keep working when ATAK is backgrounded). The legacy
        // playback / opusDecoderFactory args are left null — service
        // owns RX now.
        val voice = voiceClient
        // Wrapped in ReconnectingMumbleTransport so transient drops
        // (network blip, server bounce, ServerFull) auto-recover via
        // capped exponential backoff. The factory builds a fresh
        // MumbleTransport per attempt — slot suffixes are
        // deterministic per (device, slot) so reconnects reuse the
        // same Mumble identity.
        //
        // Per-attempt secondary refresh: the user may have moved VS2
        // to a different channel (Lobby, etc.) after the initial
        // connect via Controller.setSecondaryChannel, which updates
        // lastSecondaryChannel but doesn't mutate the captured `cfg`.
        // Re-read it inside the lambda so reconnect respins VS2 into
        // whatever channel the operator most recently selected, not
        // the channel that was active at original connect time.
        val mumbleFactory: (String, String) -> MumbleTransport = { primarySuffix, secondarySuffix ->
            val freshSecondary = lastSecondaryChannel
            val cfgForAttempt =
                if (freshSecondary != cfg.secondaryChannelName) {
                    cfg.copy(secondaryChannelName = freshSecondary)
                } else {
                    cfg
                }
            MumbleTransport(
                config = cfgForAttempt,
                playback = null,
                opusDecoderFactory = null,
                onIncomingOpus = { slot, opus, speakerSession, _ ->
                    // Cross-leg dedup + bridge relay: the mesh manager
                    // decides whether this Mumble copy plays (it may
                    // have already delivered the mesh copy) and, when
                    // we hold the bridge role, relays it onto the mesh.
                    // A true return (or no mesh manager) plays as before.
                    val play =
                        try {
                            meshVoiceManager?.onMumbleRxFrame(slot, speakerSession.toInt(), opus) ?: true
                        } catch (t: Throwable) {
                            Log.w(TAG, "mesh onMumbleRxFrame threw", t)
                            true
                        }
                    if (play) {
                        voice?.ifBound {
                            try {
                                it.onRxOpus(slot, opus, "mumble:$slot:$speakerSession")
                            } catch (t: Throwable) {
                                Log.w(TAG, "onRxOpus to service threw", t)
                            }
                        }
                    }
                },
                primarySlotSuffix = primarySuffix,
                secondarySlotSuffix = secondarySuffix,
                autoAcceptPrivateCalls = { autoAcceptPrivateCalls },
                onChannelChanged = { slot, channelId, channelName, byAdmin ->
                    val name =
                        channelName
                            ?: if (slot == 0) cfgForAttempt.channelName else cfgForAttempt.secondaryChannelName ?: "?"
                    val previous = joinedChannelsBySlot[slot]
                    if (byAdmin && previous != null) {
                        // L1: surface admin-initiated channel moves so
                        // the operator notices when an admin pulls them
                        // out of REACT into another channel. Only show
                        // toast when we HAD a previous channel — first-
                        // landing isn't a "move."
                        val slotLabel = if (slot == 0) "VS1" else "VS2"
                        val target =
                            MapView.getMapView()
                        if (target != null) {
                            try {
                                target.post {
                                    android.widget.Toast
                                        .makeText(
                                            target.context,
                                            "$slotLabel moved by admin → $name",
                                            android.widget.Toast.LENGTH_LONG,
                                        ).show()
                                }
                            } catch (t: Throwable) {
                                Log.w(TAG, "admin-move toast threw", t)
                            }
                        }
                    }
                    joinedChannelsBySlot[slot] = XvChannel(name, channelId)
                    // Primary-channel change → reconcile the mesh leg
                    // onto the new channel's derived/pinned group.
                    if (slot == 0 && !name.isNullOrBlank()) {
                        try {
                            meshVoiceManager?.onChannelJoined(0, name)
                        } catch (t: Throwable) {
                            Log.w(TAG, "mesh onChannelJoined threw", t)
                        }
                    }
                    // Status-tone events: a slot transitioning from
                    // "unset" or to a different channel id is a JOIN
                    // (new channel reachable); same channel is a no-op
                    // (server's own resync, not a UX event). The 5 s
                    // cooldown inside StatusTones absorbs reconnect
                    // storms for us.
                    val isNewJoin = previous == null || previous.id != channelId
                    if (isNewJoin) {
                        statusTones?.play(com.atakmap.android.xv.audio.StatusToneKind.CHANNEL_JOIN)
                    }
                    // Telecom call is NOT tied to channel membership —
                    // it's tied to active voice (PTT down or sustained
                    // RX). Tying it to channel join would make the OS
                    // think we're "in a call" indefinitely, which
                    // suppresses media playback for the whole channel
                    // session. The PTT-state listener fires
                    // start/endChannelCall as voice activity dictates,
                    // matching the user's expectation: "music plays
                    // while idle, pauses while talking."
                    //
                    // We do remember the current channel tag so the
                    // PTT-driven call placement uses a meaningful
                    // caller-id ("REACT#6", etc).
                    if (slot == 0) {
                        currentChannelTag = if (channelId >= 0) "$name#$channelId" else null
                        // Persist the channel so the next plugin load
                        // auto-rejoins it, overriding any server-side
                        // per-user default placement. Skip only the
                        // temporary private-call channels — Lobby
                        // (channelId 0) is a valid persisted state
                        // because operators legitimately end a session
                        // in Lobby and expect to come back to Lobby
                        // next time, not be force-routed to REACT by
                        // OTS's user-group default.
                        val isPrivateTempChannel = name?.startsWith("TAK PRIVATE - ") == true
                        if (channelId >= 0 && !isPrivateTempChannel && !name.isNullOrBlank()) {
                            try {
                                settings.persistPrimaryChannel(name)
                            } catch (t: Throwable) {
                                Log.w(TAG, "persist primary channel '$name' threw", t)
                            }
                        }
                    }
                    // Tell the service-side voice plant about the
                    // current Mumble session state so its canTransmit
                    // gate (TPT vs bonk) reflects reality. ONLY slot 0
                    // (VS1, primary) drives canTransmit — secondary
                    // (VS2) is always allowed when its bound channel
                    // is reachable, but it doesn't gate canTransmit
                    // for slot 0. Sending the slot=1 state here would
                    // clobber the primary's true state and the next
                    // PTT would bonk-refuse.
                    if (slot == 0) {
                        voiceClient?.ifBound { it.setMumbleSessionState(channelId >= 0) }
                    }
                    // Audit L4: signal AudioPlayback to debounce any
                    // in-flight RX frames from the old channel that
                    // the server already queued at the moment of move.
                    // Fired on every channel change (any slot, any
                    // direction); 100ms debounce window is short
                    // enough to lose at most one syllable from a
                    // legitimate in-channel peer talking at the
                    // boundary.
                    if (previous != null && previous.id != channelId) {
                        voiceClient?.ifBound { it.notifyChannelMoved(slot) }
                    }
                    val combined =
                        joinedChannelsBySlot.entries
                            .sortedBy { it.key }
                            .map { it.value }
                    presencePublisher?.setChannels(combined)
                    // Phase E: piggyback the Mumble session-id push on
                    // channel-change since both observations are gated
                    // on post-ServerSync state. Null means we're back
                    // to disconnected; peers' presence records lose
                    // the field so they fall back to roster lookup.
                    presencePublisher?.setMumbleSession(mumbleTransport()?.primarySessionId())
                    // On every channel move our suppress flag may have
                    // flipped (a different channel maps to a different
                    // OTS group, possibly with a different direction).
                    // The server will send a fresh UserState with the
                    // new suppress value almost immediately; in the
                    // meantime, default to "can speak" so the next PTT
                    // isn't spuriously denied during the cold instant.
                    voiceClient?.ifBound { it.setCanSpeakOnSlot(slot, true) }
                    // Push the UI immediately — without this, the
                    // dropdown sticks on the previous channel name
                    // (notably "TAK PRIVATE - <hex>" after a call ends
                    // and the transport rejoins the operator's pre-call
                    // channel) until the next 2 s poll tick.
                    dropDown?.refreshNow()
                },
                onSelfSuppressedChanged = { slot, suppressed ->
                    Log.i(TAG, "self suppress on slot=$slot → $suppressed")
                    selfSuppressedBySlot[slot] = suppressed
                    voiceClient?.ifBound { it.setCanSpeakOnSlot(slot, !suppressed) }
                },
                onIncomingCallRequest = { callerCallsign, tempChannelId, callerSession ->
                    // Phase E: a peer's REQUEST_CALL arrived. Hand off
                    // to the service so it can register the incoming
                    // Telecom call (system ring UI). The operator's
                    // Accept/Decline decision comes back through
                    // IXvVoiceListener.onIncomingCallAnswered/Rejected.
                    Log.i(
                        TAG,
                        "Mumble REQUEST_CALL: caller='$callerCallsign' " +
                            "tempChannelId=$tempChannelId callerSession=$callerSession",
                    )
                    voiceClient?.ifBound {
                        it.notifyIncomingCall(callerCallsign, tempChannelId, callerSession)
                    }
                },
                onPlayToneRequest = { args ->
                    // Phase E PLAY_TONE handler. Map known VX tone
                    // tokens to our StatusToneKind. Tokens VX uses:
                    //   ringback / ring / outgoing — caller's wait cue
                    //   busy / reject — failure cue
                    //   end / disconnect — call hangup cue
                    // Unknowns log at WARN — not playing a stranger's
                    // arbitrary audio is the safer default.
                    val kind =
                        when (args.trim().lowercase()) {
                            "ringback", "ring", "outgoing" ->
                                com.atakmap.android.xv.audio.StatusToneKind.CALL_RINGBACK
                            "busy", "reject", "fail" ->
                                com.atakmap.android.xv.audio.StatusToneKind.CALL_BUSY
                            "end", "disconnect", "leave" ->
                                com.atakmap.android.xv.audio.StatusToneKind.CHANNEL_LEAVE
                            else -> {
                                Log.w(TAG, "PLAY_TONE: unknown token '$args' — ignoring")
                                null
                            }
                        }
                    if (kind != null) {
                        statusTones?.play(kind)
                    }
                },
                onPrivateCallTransportTeardown = {
                    // Transport detected the peer ended the call (peer
                    // moved channels, peer disconnected, CANCEL_CALL
                    // arrived). The wire-state cleanup already ran in
                    // notePrivateCallEnded; now tear down the Telecom
                    // call so externalTeardownListener fires —
                    // disengaging audio mode, releasing the latched TX,
                    // dismissing XvActiveCallActivity.
                    Log.i(TAG, "transport-detected call end — tearing down Telecom side")
                    voiceClient?.ifBound { it.endChannelCall() }
                },
                onPeerAcceptedCall = {
                    // Caller-side: callee just joined the temp channel
                    // (the implicit accept signal in the whisper
                    // architecture). Now engage the mic so the
                    // operator can talk — outgoing calls deferred this
                    // until accept so the caller's mic wasn't hot
                    // during the ringing phase.
                    Log.i(TAG, "transport reports peer accepted — engaging mic")
                    cancelPendingAnswerTimeout("callee accepted")
                    cancelPendingRingback("callee accepted")
                    voiceClient?.ifBound { it.engagePrivateCallMic() }
                },
            )
        }
        val transport: VoiceTransport =
            com.atakmap.android.xv.transport.ReconnectingMumbleTransport(
                config = cfg,
                primarySlotSuffix = mumblePrimarySlotSuffix,
                secondarySlotSuffix = mumbleSecondarySlotSuffix,
                factory = mumbleFactory,
                onReconnectStateChanged = { attempt, delayMs ->
                    Log.i(TAG, "reconnect attempt $attempt scheduled in ${delayMs}ms")
                },
            )
        activeTransport = transport
        transport.connect(loggingListener)
        // Start the network watcher AFTER connect() so the wrapper is
        // ready to receive notifyNetworkSwap. The watcher itself is
        // edge-only — it skips the initial-network event, so a same-
        // network registration doesn't trigger a spurious swap.
        networkWatcher?.stop()
        val ctxForWatcher = heldPluginContext
        networkWatcher =
            if (ctxForWatcher == null) {
                Log.w(TAG, "no plugin context — network swap detection disabled")
                null
            } else {
                com.atakmap.android.xv.transport
                    .NetworkAvailabilityWatcher(ctxForWatcher) {
                        // Forward swap to whichever transport is currently
                        // installed. Mumble path is the common case; the
                        // multicast branch (currently dormant pending
                        // Phase 8 service-side rewire) needs the same
                        // nudge — without it, the UDP socket stays bound
                        // to the now-down interface and silently delivers
                        // nothing post-handoff (BUG FIX 2026-06-15).
                        when (val t = activeTransport) {
                            is com.atakmap.android.xv.transport.ReconnectingMumbleTransport ->
                                t.notifyNetworkSwap()
                            is com.atakmap.android.xv.transport.MulticastTransport ->
                                t.notifyNetworkSwap()
                            else -> {
                                // No transport, or a transport that
                                // doesn't care about handoffs — nothing
                                // to do.
                            }
                        }
                        // Mesh legs bind their own multicast sockets and
                        // need the same rebind-to-fresh-interface nudge,
                        // or they deliver nothing after a handoff.
                        try {
                            meshVoiceManager?.notifyNetworkSwap()
                        } catch (t: Throwable) {
                            Log.w(TAG, "mesh notifyNetworkSwap threw", t)
                        }
                    }.also { it.start() }
            }
    }

    // Re-target VS2 on the active connection.
    //
    // - Empty/blank tears down the secondary Mumble session entirely
    //   (VS2 cleared; the on-screen VS2 button dims).
    // - Non-empty: if no secondary session exists yet, spins one up;
    //   if one exists, moves it into [channelName] (full join — the
    //   user keys the new channel and ALSO hears traffic on it).
    //
    // Updates lastSecondaryChannel so the next reconnect picks the same
    // VS2. The transport rejects names that collide with VS1.
    private fun setSecondaryChannelInternal(channelName: String) {
        val trimmed = channelName.trim()
        val t = mumbleTransport()
        if (t == null) {
            Log.w(TAG, "MUMBLE_SET_SECONDARY: no active Mumble transport — recording for next connect")
            lastSecondaryChannel = trimmed.takeIf { it.isNotEmpty() }
            return
        }
        val ok = t.retargetSecondary(trimmed)
        if (ok) {
            lastSecondaryChannel = trimmed.takeIf { it.isNotEmpty() }
            val hadSecondary = joinedChannelsBySlot.containsKey(1)
            joinedChannelsBySlot.remove(1) // CoT presence will repopulate on UserState
            if (hadSecondary && trimmed.isEmpty()) {
                statusTones?.play(com.atakmap.android.xv.audio.StatusToneKind.CHANNEL_LEAVE)
            }
            Log.i(TAG, "MUMBLE_SET_SECONDARY: applied '$trimmed' (empty=clear)")
        } else {
            Log.w(TAG, "MUMBLE_SET_SECONDARY: '$trimmed' rejected (collision with primary or unresolvable)")
        }
    }

    private fun joinMumbleChannelInternal(
        channelName: String?,
        channelId: Int,
    ) {
        Log.i(TAG, "JOIN request: channel='$channelName' channelId=$channelId activeTransport=${activeTransport?.javaClass?.simpleName}")
        val t = mumbleTransport()
        if (t == null) {
            Log.w(TAG, "JOIN: no active Mumble transport")
            return
        }
        if (channelId >= 0) {
            t.joinChannel(channelId)
        } else if (!channelName.isNullOrBlank()) {
            val ok = t.joinChannel(channelName)
            Log.i(TAG, "JOIN: '$channelName' resolved=$ok")
        } else {
            Log.w(TAG, "JOIN: need either channel name or id")
        }
    }

    @SuppressWarnings("MissingPermission")
    private fun connectAinaInternal(
        context: Context,
        mac: String?,
        name: String?,
        kind: String,
    ) {
        disconnectAinaInternal()
        val adapter = BluetoothAdapter.getDefaultAdapter()
        if (adapter == null) {
            Log.w(TAG, "no Bluetooth adapter")
            return
        }
        val device =
            try {
                if (mac != null) {
                    adapter.getRemoteDevice(mac)
                } else {
                    val needle = name!!.lowercase()
                    adapter.bondedDevices?.firstOrNull {
                        (it.name ?: "").lowercase().contains(needle)
                    } ?: run {
                        Log.w(TAG, "no bonded device matching name '$name'")
                        return
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "could not resolve AINA device", t)
                return
            }
        Log.i(TAG, "resolved AINA: ${device.name} ${device.address}")
        currentAinaDevice = device
        // Strict button mapping — no per-device overrides allowed. Same
        // physical button on V1 (SPP-ASCII) and V2 (BLE-mask) maps to the
        // same TX slot, so the operator's muscle memory carries across
        // hardware. Press behavior (debounce, latch vs momentary, long-
        // press semantics) DOES differ between V1 and V2 — those live in
        // the per-kind onEvent below, not here.
        //   PTT  (primary)   → TX slot 0  → primary group channel
        //   PTTS (secondary) → TX slot 1  → VS2 (Mumble VoiceTarget)
        //   PTTE             → emergency button
        //   PTTB1/PTTB2/MFB  → unmapped (intentional; no remap UX)
        val onConn: (Boolean) -> Unit = { up ->
            Log.i(TAG, "AINA connection up=$up")
        }
        // "auto" picks the button-input protocol from the SDP-cached
        // UUIDs (definitive when populated), with device.type only as
        // a last-resort fallback. The Surface Duo BT stack reports
        // DEVICE_TYPE_DUAL for V1 devices that have ever been seen
        // over both transports — so device.type alone misclassifies
        // V1 as V2 and we never read PTT events. UUID-based detection
        // is robust to that.
        //
        // A per-MAC operator override (persisted in prefs via
        // [persistAinaProtocolOverride]) takes precedence over auto-
        // detect. This is the fix for AINA V2 hardware whose BLE
        // vendor service `127FACE1-...` is GATT-discoverable but
        // never appears in the BR/EDR SDP record (per spec v18, the
        // V2 service has no SDP entry; SPP exists only as a legacy
        // fallback). Without the override, such V2s misclassify as
        // V1 and lose BLE buttons. V1 devices without an override
        // still flow through auto-detect.
        val overrideKind = settings.persistedAinaProtocolOverride(device.address)
        // Quick-win: always log the override lookup result so field
        // logs can distinguish "no override set" from "override
        // mechanism never ran". MAC routed through the redactor per
        // CLAUDE.md sensitive-content rules.
        val redactedMac = com.atakmap.android.xv.aina.redactMac(device.address)
        if (overrideKind == null) {
            Log.i(TAG, "per-MAC override checked for $redactedMac: ABSENT")
        }
        val resolvedKind =
            if (kind == "auto" && overrideKind != null) {
                Log.i(TAG, "per-MAC override for $redactedMac: protocol='$overrideKind'")
                overrideKind
            } else if (kind == "auto") {
                val classified = com.atakmap.android.xv.aina.AinaDeviceClassifier.classifyButtonProtocol(device)
                // V2 SDP-cache gap: APTT device classified as SPP → kick
                // off a live probe; on BLE persist the override and
                // re-enter connectAinaInternal so the V2 reader binds.
                if (classified == com.atakmap.android.xv.aina.AinaDeviceInfo.ButtonProtocol.SPP &&
                    com.atakmap.android.xv.aina.AinaDeviceClassifier.isAinaByName(device)
                ) {
                    ainaProbeFor(context).probe(device) { resolved ->
                        if (resolved == com.atakmap.android.xv.aina.AinaDeviceInfo.ButtonProtocol.BLE) {
                            settings.persistAinaProtocolOverride(device.address, "v2")
                            val rd = com.atakmap.android.xv.aina.AinaProtocolProbe.redactMac(device.address)
                            Log.i(TAG, "XvAinaProbe: override persisted mac=$rd protocol=BLE")
                            connectAinaInternal(context, device.address, name, "auto")
                        }
                    }
                }
                when (classified) {
                    com.atakmap.android.xv.aina.AinaDeviceInfo.ButtonProtocol.SPP -> {
                        Log.i(TAG, "auto-detect: SPP UUID present → V1 (SPP)")
                        "v1"
                    }
                    com.atakmap.android.xv.aina.AinaDeviceInfo.ButtonProtocol.BLE -> {
                        Log.i(TAG, "auto-detect: AINA BLE UUID present → V2 (BLE)")
                        "v2"
                    }
                    com.atakmap.android.xv.aina.AinaDeviceInfo.ButtonProtocol.BLE_HID -> {
                        Log.i(TAG, "auto-detect: BLE HID-over-GATT (Pryme/generic) → ble-hid")
                        "ble-hid"
                    }
                    else -> {
                        // No usable UUID — fall back to transport
                        // type. CLASSIC → SPP guess; LE/DUAL → BLE
                        // guess. Either may be wrong on devices with
                        // poor SDP cache; the operator can override
                        // by re-pairing or selecting a different
                        // device in Settings.
                        when (device.type) {
                            BluetoothDevice.DEVICE_TYPE_CLASSIC -> {
                                Log.i(TAG, "auto-detect: no UUIDs, device.type=CLASSIC → V1 (SPP)")
                                "v1"
                            }
                            BluetoothDevice.DEVICE_TYPE_LE,
                            BluetoothDevice.DEVICE_TYPE_DUAL,
                            -> {
                                Log.i(TAG, "auto-detect: no UUIDs, device.type=${device.type} → V2 (BLE)")
                                "v2"
                            }
                            else -> {
                                Log.w(TAG, "auto-detect: unknown device.type=${device.type} — defaulting to V1")
                                "v1"
                            }
                        }
                    }
                }
            } else {
                kind
            }
        // Delegate the actual reader (SPP socket / BLE GATT) to the
        // service so the BT subscriptions live in our APK's UID where
        // FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE keeps them alive
        // when ATAK is backgrounded. The plugin keeps:
        //   - currentAinaDevice (set above) for the A2DP-lock toggle
        //   - persistedAinaMac for auto-connect on next plugin load
        //   - emergency button forwarding (still wired through plugin
        //     so the EmergencyController + AtakEmergencyDispatcher can
        //     reach ATAK's EmergencyManager — which is in ATAK's UID)
        // The unused legacy in-plugin reader fields stay for now to
        // avoid a bigger refactor; they're never instantiated.
        voiceClient?.ifBound { it.connectAina(device.address, device.name, resolvedKind) }
        // Track the reader kind we just handed to the service so the
        // operator-driven button-protocol change path
        // ([setAinaButtonProtocolInternal]) can decide whether a new
        // pick actually differs from the running reader.
        currentAinaReaderKind = resolvedKind
    }

    // V1 (SPP / RFCOMM ASCII) button event handler. The SPP parser emits
    // explicit press/release edges from "+PTT=P" / "+PTT=R" frames; the
    // dispatch helpers translate those edges into TX events while
    // respecting latched-mic mode and emergency button semantics.
    private val ainaV1OnEvent: (AinaButton, Boolean) -> Unit = { btn, down ->
        Log.i(TAG, "AINA-V1 button $btn down=$down")
        when (btn) {
            AinaButton.PTTE -> emergency?.onEmergencyButton(down)
            AinaButton.PTT -> if (down) dispatchPttDown(slot = 0) else dispatchPttUp(slot = 0)
            AinaButton.PTTS -> if (down) dispatchPttDown(slot = 1) else dispatchPttUp(slot = 1)
            else -> { /* PTTB1 / PTTB2 / MFB — strict mapping: ignored */ }
        }
    }

    // V2 (BLE GATT mask) button event handler. Same dispatch path as V1.
    private val ainaV2OnEvent: (AinaButton, Boolean) -> Unit = { btn, down ->
        Log.i(TAG, "AINA-V2 button $btn down=$down")
        when (btn) {
            AinaButton.PTTE -> emergency?.onEmergencyButton(down)
            AinaButton.PTT -> if (down) dispatchPttDown(slot = 0) else dispatchPttUp(slot = 0)
            AinaButton.PTTS -> if (down) dispatchPttDown(slot = 1) else dispatchPttUp(slot = 1)
            else -> { /* PTTB1 / PTTB2 / MFB — strict mapping: ignored */ }
        }
    }

    // ---- PTT dispatch ----
    //
    // All input sources funnel through PttDispatcher (constructed in
    // onCreate). These thin helpers exist so call sites read naturally
    // and the dispatcher reference can be null-guarded — during plugin
    // shutdown a stale event from a BLE driver could otherwise NPE.

    // PTT dispatch now flows across the process boundary to the
    // service's PttDispatcher (where AudioCapture has the foreground-
    // microphone privilege to actually run in the background). The
    // in-plugin pttDispatcher is dormant — it's still constructed for
    // the moment to avoid a bigger refactor, but the only event paths
    // that reach it are dead.
    private fun dispatchPttDown(slot: Int) {
        voiceClient?.ifBound { it.pttDown(slot) }
    }

    private fun dispatchPttUp(slot: Int) {
        voiceClient?.ifBound { it.pttUp(slot) }
    }

    private fun disconnectAinaInternal() {
        // Service owns the reader now; tell it to drop. ainaBle/ainaSpp
        // fields below are dormant from the migration but still nulled
        // for cleanliness if any leftover instance exists.
        voiceClient?.ifBound { it.disconnectAina() }
        ainaBle?.disconnect()
        ainaBle = null
        ainaSpp?.disconnect()
        ainaSpp = null
        currentAinaDevice = null
        currentAinaReaderKind = null
    }

    @SuppressWarnings("MissingPermission")
    private fun listBondedInternal() {
        val adapter = BluetoothAdapter.getDefaultAdapter()
        if (adapter == null) {
            Log.w(TAG, "no Bluetooth adapter")
            return
        }
        val bonded = adapter.bondedDevices ?: emptySet()
        Log.i(TAG, "${bonded.size} bonded device(s):")
        for (d in bonded) {
            Log.i(TAG, "  ${d.address}  type=${d.type}  name='${d.name}'")
        }
    }

    // Set true while a deliberate stopActiveTransport() is running, so
    // the disconnect-warning audio cue doesn't fire on user-initiated
    // disconnects (only on unexpected drops).
    @Volatile
    private var deliberateDisconnectInProgress: Boolean = false

    private val loggingListener =
        object : TransportListener {
            override fun onConnected() {
                Log.i(TAG, "transport connected")
            }

            override fun onDisconnected(reason: String?) {
                Log.i(TAG, "transport disconnected: $reason")
                if (!deliberateDisconnectInProgress) {
                    statusTones?.play(com.atakmap.android.xv.audio.StatusToneKind.WARNING_VOICE_LOST)
                    // Tell the voice service so it can cut off any
                    // in-flight TX with the emphatic cutoff tone via
                    // the SCO route — operator stays oriented on the
                    // speakermic instead of discovering the silent
                    // drop on the next PTT release. No-op when not
                    // mid-burst.
                    voiceClient?.ifBound { it.notifyTransportLost() }
                }
            }

            override fun onConnectionFailed(error: Throwable) {
                Log.e(TAG, "transport connect failed", error)
                statusTones?.play(com.atakmap.android.xv.audio.StatusToneKind.WARNING_VOICE_LOST)
                voiceClient?.ifBound { it.notifyTransportLost() }
            }

            override fun onVoiceFrame(frame: VoiceFrame) {
                // PCM playback is handled inline by the transport via the
                // injected AudioPlayback; this hook is for indicators
                // only (who's talking, signal meter, etc.).
            }

            override fun onPeerStartedTalking(peerId: String) {
                Log.i(TAG, "peer started: $peerId")
            }

            override fun onPeerStoppedTalking(peerId: String) {
                Log.i(TAG, "peer stopped: $peerId")
            }
        }

    companion object {
        private const val TAG = "XV"

        // Broadcast that sets XV's active ATAK missions for auto-channel
        // provisioning. Extra "missions": a String[] (ordered, primary
        // first) or a delimited String. See reconcileMissionChannels.
        const val SET_MISSIONS = "com.atakmap.android.xv.SET_MISSIONS"

        // 1 Hz cadence for the mesh-voice + mission-channel reconcile tick.
        private const val MESH_TICK_MS = 1_000L

        // "Server alive" window for the failover health feed. Must
        // exceed the Mumble ping cadence (8 s) + grace so health
        // doesn't flicker between pings; matches the session
        // watchdog's stale-link threshold (~18 s) + slack.
        private const val MUMBLE_LIVENESS_WINDOW_MS = 20_000L

        // Synthetic deviceUid prefix used in CallPeer entries that come
        // from the Mumble channel roster (rather than the <__xv> CoT
        // registry). Lets startDirectCall route VX peers — who don't
        // publish XV's CoT presence — directly via their Mumble session.
        private const val MUMBLE_PEER_PREFIX = "mumble:"

        // How long the caller waits for the callee to join the temp
        // channel after REQUEST_CALL is sent. Matches the cellular
        // "no answer" convention (~30s ring then drop) so operators
        // get familiar UX. Hitting the timeout sends CANCEL_CALL to
        // unstick the peer's ring state if it ever rendered.
        private const val ANSWER_TIMEOUT_MS = 30_000L

        // Caller-side warmup delay before dispatching the CoT REQUEST.
        // During this window we have: Mumble temp channel created +
        // joined, Telecom audio mode engaged, comm device set,
        // AudioRecord allocated, AudioTrack pre-warmed. Sending REQUEST
        // after the warmup means the callee's accept lands on a
        // realtime-ready pipeline. 300ms balances perceptible-snap
        // against full hardware settle time.
        private const val CALL_WARMUP_DELAY_MS = 300L

        // Debounce for the STATE_ON auto-reconnect pass.
        //
        // Original 2026-07-11 morning tuning: 800 ms — coalesces the
        // OEM double-fire of STATE_ON on some stacks and lets
        // BluetoothProfile proxies bind.
        //
        // Field capture 2026-07-11 evening (Pixel 9 Pro / API 35 with
        // AINA V1 as primary): 800 ms fires autoConnectAina BEFORE
        // the AINA finishes its post-STATE_ON SDP publication. The
        // bonded-devices list has the AINA but the "reachable"
        // predicate returns false (SDP not yet re-published), so
        // autoConnectAina bails with "N bonded candidate(s) but no
        // available speakermic — waiting for a speakermic to come
        // up". Nothing re-triggers autoConnect, so the reader stays
        // unspawned until the operator manually touches the picker.
        // Observed: OS-level BT reconnect completed at ~4-5 s after
        // STATE_ON (log: `XvAudioRouter: added: BT_SCO(APTT000000)`
        // at 20:39:54 vs STATE_ON at 20:39:49).
        //
        // 3000 ms gives a safety margin over the observed 4-5 s
        // ceiling. Perceptible cost: an extra 2.2 s between BT-on
        // and PTT working — still a huge win over "never" until
        // manual picker touch, which was the reported field
        // experience.
        //
        // Post-freeze follow-up: replace the fixed delay with a
        // BluetoothDevice.ACTION_ACL_CONNECTED receiver targeted at
        // the saved AINA / External Button MACs so we react to the
        // actual reachability signal instead of a worst-case wall
        // clock guess.
        private const val BT_STATE_ON_RECONNECT_DELAY_MS = 3000L

        // Persistent default for VX-compat handshake. HYBRID is the current
        // operational default: it makes XV "callable" from VX clients via
        // Mumble Version.release substring match (verified 2026-05-06)
        // while keeping our own version visible in `release` for
        // diagnostics. Set via MUMBLE_VX_COMPAT; takes effect on the next
        // connect. Per-call overrides via MUMBLE_CONNECT --es vxcompat win
        // for that call only and don't mutate this default.
        //
        // Note: HYBRID also publishes UserState.comment=deviceUid so VX
        // clients can resolve our Mumble session back to an ATAK contact.
        // This is the foundation that the planned XV-native peer
        // discovery + encrypted-multicast P2P will sit alongside (the
        // Mumble identity stays canonical for VX-interop; XV peers
        // exchange richer presence via a separate CoT detail layer).
        @Volatile
        var defaultVxCompat: VxCompat = VxCompat.HYBRID

        // Phase-1 auto-accept stub for private calls. When true, an incoming
        // REQUEST_CALL TextMessage causes XV to immediately join the temp
        // channel — confirms the audio path works on a private channel
        // without an accept/decline UI yet. Toggled live via
        // MUMBLE_AUTO_ACCEPT --es enabled "true|false" (no reconnect needed).
        @Volatile
        var autoAcceptPrivateCalls: Boolean = false
    }
}
