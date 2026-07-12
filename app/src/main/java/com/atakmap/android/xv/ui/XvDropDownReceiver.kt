package com.atakmap.android.xv.ui

import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import com.atakmap.android.dropdown.DropDown.OnStateListener
import com.atakmap.android.dropdown.DropDownReceiver
import com.atakmap.android.maps.MapView
import com.atakmap.android.xv.R
import com.atakmap.android.xv.aina.AinaDeviceInfo
import com.atakmap.android.xv.audio.OutputRoute
import com.atakmap.android.xv.audio.TptTone
import com.atakmap.android.xv.transport.mumble.MumbleSession.ChannelInfo.Participation

// Main XV control panel. Inspired by FlexRadio + modern SDR UIs:
//   - Info-density without clutter on the front screen (server/channel,
//     audio route, TPT, current speaker).
//   - One big touch-target for HOLD-TO-TX.
//   - Low-noise typography (monospace for values, dim labels).
// Secondary screens (Settings) carry less-frequent controls.
class XvDropDownReceiver(
    mapView: MapView,
    private val pluginContext: Context,
    private val controller: Controller,
) : DropDownReceiver(mapView),
    OnStateListener {
    // Live reconnect-attempt state surfaced to the UI by
    // ReconnectingMumbleTransport. `attempt` counts attempts since the
    // last clean (re)connect — 1 on the first retry, growing as backoff
    // climbs. `nextDelayMs` is the scheduled wait before the next try.
    data class ReconnectInfo(
        val attempt: Int,
        val nextDelayMs: Long,
    )

    interface Controller {
        fun isMumbleConnected(): Boolean

        // True while a private call is active (Telecom call in
        // ACTIVE/RINGING state for one of our XvConnections). Used by
        // the channel picker to lock channel-switching during calls so
        // the operator can't accidentally drop themselves out by
        // tapping a channel header.
        fun isInCall(): Boolean = false

        // Non-null only while the wrapper is between attempts. Lets
        // refreshMain colour the dot amber and label the channel
        // "reconnecting…" without the operator thinking the session
        // is gone for good.
        fun mumbleReconnectInfo(): ReconnectInfo?

        // Currently-joined channel, the destination of slot-0 (VS1) talk.
        fun currentChannelName(): String?

        // Configured secondary (VS2) channel name. Null = none configured.
        fun secondaryChannelName(): String?

        // True only when the server-side VoiceTarget for VS2 has been
        // registered AND its target channel ≠ the primary's channel.
        // The UI uses this to dim the VS2 PTT button until VS2 is live.
        fun secondaryRegistered(): Boolean

        // True if the operator can transmit on the slot's currently-
        // joined channel. Driven by the suppress flag on our own
        // Mumble UserState (set by OTS direction enforcement when our
        // group's direction is OUT, or by Mumble admin mute). The UI
        // uses this to label the PTT card "(listen only)" and grey
        // the PTT button.
        fun canSpeakOnSlot(slot: Int): Boolean

        /** True while TX is active on the given slot — set on PTT-down
         *  (after PRIMING completes) and cleared on stop(). The UI uses
         *  this to switch the PTT button into the "transmitting" theme:
         *  red background + "● TRANSMITTING" label, so the operator
         *  gets unambiguous visual feedback that they're on the air. */
        fun isTransmittingOnSlot(slot: Int): Boolean = false

        // Channel directory from the server, sorted by participation
        // tier (PARTICIPATE → LISTEN → UNKNOWN → UNAUTHORIZED) then
        // name. Each entry carries the per-channel permission verdict
        // so the picker can colour-code and gate selection. Empty until
        // first ChannelStates arrive.
        fun availableChannels(): List<com.atakmap.android.xv.transport.mumble.MumbleSession.ChannelInfo>

        // Names of peers currently transmitting on the given slot
        // (0=VS1, 1=VS2). Empty list = nothing on the air right now.
        fun activeSpeakers(slot: Int): List<String>

        fun connectedTakHost(): String?

        // ---- Multi-TAK-server picker (Settings → Server) ----
        // Snapshot of every TAK server ATAK has enrolled, with state for
        // the picker UI. Re-queried on every picker open; the underlying
        // TAKServerListener exposes no observer API.
        fun availableTakHosts(): List<TakHostInfo> = emptyList()

        // Currently-saved preference. Null = "auto" (pick first connected,
        // else first configured). When set, [connectMumbleWithDefaults]
        // honors this exact host string; on a no-longer-enrolled host the
        // call silently falls back to auto without clearing the pref (the
        // operator may be mid-reconfig and we don't want to forget for
        // them).
        fun preferredTakHost(): String? = null

        // Persist a TAK host selection. Null = clear / revert to auto.
        // Should also trigger a Mumble reconnect when the picked host
        // differs from the currently-connected one — UI surfaces a toast
        // so the operator knows traffic is moving.
        fun setPreferredTakHost(host: String?) {}

        fun audioStateText(): String

        fun routeText(): String

        fun tptTone(): TptTone

        fun setTptTone(tone: TptTone)

        fun outputRoute(): OutputRoute

        fun setOutputRoute(route: OutputRoute)

        // Optional BT audio override. Lets the operator pick a
        // specific BT audio device (car BT, headphones) that wins
        // over the AINA's HFP audio path. Empty list means no BT
        // audio devices currently connected; null override means
        // "no override — follow PTT device + route preference."
        fun availableBtOutputs(): List<com.atakmap.android.xv.audio.AudioRouter.BtOutput>

        fun outputBtOverrideMac(): String?

        fun setOutputBtOverrideMac(mac: String?)

        // slot 0 = VS1 (primary, current channel), slot 1 = VS2 (secondary).
        fun startTx(slot: Int)

        fun stopTx()

        fun playTptPreview()

        fun connectMumble()

        fun disconnectMumble()

        fun connectAina()

        fun disconnectAina()

        // Move our session into [name]. Updates VS1's destination.
        fun setPrimaryChannel(name: String)

        // Re-target VS2. Empty/blank clears.
        fun setSecondaryChannel(name: String)

        // ---- AINA speakermic picker (Settings → Preferences) ----
        // Bonded devices whose name looks like an AINA. Each entry's
        // generation is derived from BluetoothDevice.getType() so the UI
        // can label V1 vs V2 unambiguously.
        fun availableAinaDevices(): List<AinaDeviceInfo>

        // MAC of the AINA the user has explicitly chosen (or that the
        // saved selection points at). Null = none / disconnected.
        fun selectedAinaMac(): String?

        // True when [selectedAinaMac] is currently connected (BLE GATT
        // up for V2, SPP RFCOMM up for V1). Used by the UI to show a
        // status row under the spinner.
        fun ainaConnectionUp(): Boolean

        // Pick a device by MAC (null = disconnect). Persists across
        // launches; auto-connect on plugin load uses this MAC if set.
        fun setSelectedAina(mac: String?)

        // Change the button-input protocol on the CURRENTLY-selected
        // primary AINA. Nullable to represent "no buttons / on-screen
        // only" — the operator keeps the speakermic's audio path but
        // XV stops listening for button events on that device.
        //
        // Semantics:
        //  - Always persists (via the per-MAC protocol override) so a
        //    later disconnect / reconnect edge picks up the new value.
        //  - If the primary AINA is currently connected AND the new
        //    kind differs from the running reader, tears the current
        //    reader down and (for a real reader kind) spins up a new
        //    one under the new kind — so the operator doesn't have to
        //    disconnect + reconnect to make the change take effect.
        //  - Idempotent under rapid A → B → A toggles (same-kind
        //    writes short-circuit; "no reader → no reader" transitions
        //    don't churn).
        //
        // Intentionally does NOT touch the external-button reader
        // path — that's a separate reader lifecycle.
        fun setAinaButtonProtocol(kind: AinaDeviceInfo.ButtonProtocol?) {}

        // ---- External button (Settings → Preferences) ----
        // Optional BLE PTT puck (Pryme BT-PTT-Z, PTT-Z01, generic
        // BLE-HID) whose button keys slot 0 in parallel with the
        // primary speakermic. Button-only role. Motorcyclist use case:
        // helmet speakermic + handlebar puck both keying VS1 without
        // one cutting the other off. Independent of the primary so
        // either can be swapped without affecting the other.
        fun availableExternalButtonDevices(): List<AinaDeviceInfo> = emptyList()

        fun selectedExternalButtonMac(): String? = null

        fun externalButtonConnectionUp(): Boolean = false

        fun setSelectedExternalButton(mac: String?) {}

        // Assign a scan-discovered BLE PTT button to the primary or
        // external-button PTT slot. Both persist the MAC + kind="ble-hid"
        // and hand off to the existing service-side connect path
        // (IXvVoice.connectAina / connectExternalButton), which uses
        // PrymeBleReader on top of the HM-10 transparent-UART service.
        // The device does NOT need to be bonded — BluetoothAdapter's
        // getRemoteDevice(mac) constructs a BluetoothDevice from the
        // MAC alone and BluetoothGatt.connectGatt works over an
        // unbonded LE link. Returns null on success, or a short
        // operator-actionable error string on failure.
        // Add a scan-discovered BLE PTT button to the known-devices
        // library. Does NOT assign it to a slot — the operator picks
        // it from the primary or external-button picker afterwards,
        // subject to the existing "primary MAC != external-button MAC"
        // rule. Returns null on success or a short operator-actionable
        // error.
        fun addBlePttDevice(
            mac: String,
            name: String?,
        ): String? = "not implemented"

        // BLE PTT devices the operator has added via the scan-and-pick
        // dialog. Surfaced so the "Remove PTT button" dialog can list
        // them without exposing the raw SharedPreferences store. Empty
        // list when nothing has been added.
        fun knownBlePttDevices(): List<AinaDeviceInfo> = emptyList()

        // Clear a persisted BLE PTT device by MAC. Also clears it from
        // the primary / external-button slot if the operator had
        // assigned it there. Returns null on success or an
        // operator-actionable error string. No-op if the MAC isn't in
        // the known-devices store.
        fun removeBlePttDevice(mac: String): String? = "not implemented"

        // ---- BT auto-connect toggle ----
        // When ON (default), XV auto-connects the first compatible
        // bonded speakermic / BLE PTT button it finds on plugin load.
        // When OFF, the operator must pick from the AINA picker by
        // hand. Persists across launches.
        fun autoConnectBtEnabled(): Boolean = true

        fun setAutoConnectBtEnabled(enabled: Boolean) {}

        // ---- Samsung ruggedized-device Active Key ----
        // True only when the current device is a Samsung Tab Active5
        // / XCover6 Pro / XCover7 / Tab Active4 Pro / Tab Active3 that
        // carries the programmable Active Key. Consulted at Settings-
        // row inflation time — the row is hidden (`View.GONE`) on
        // every device where this returns false so operators on non-
        // Samsung hardware never see the toggle at all. Default false
        // so a lazy Controller impl on a non-Samsung dev host still
        // hides the row.
        fun samsungActiveKeySupported(): Boolean = false

        fun samsungActiveKeyEnabled(): Boolean = false

        fun setSamsungActiveKeyEnabled(enabled: Boolean) {}

        // True when the XV accessibility service for background Active Key
        // PTT is currently enabled (as reported by AccessibilityManager).
        // Read-only from the Controller's perspective — the operator must
        // grant / revoke the permission via the system Accessibility dialog.
        fun samsungActiveKeyBgServiceEnabled(): Boolean = false

        // Launch the system Accessibility settings page so the operator
        // can enable or disable the XV accessibility service.
        fun openAccessibilitySettings() {}

        // ---- Sonim ruggedized-device dedicated hardware buttons ----
        // True only when the current device is a Sonim ruggedized
        // model (XP10 / XP9900 and XP-family peers) that carries the
        // dedicated PTT + Emergency keys. Consulted at Settings-row
        // inflation time — both rows are hidden (`View.GONE`) on any
        // device where this returns false so operators on non-Sonim
        // hardware never see the toggles at all. Default false so a
        // lazy Controller impl on a non-Sonim dev host still hides
        // the rows.
        fun sonimHardwareButtonsSupported(): Boolean = false

        fun sonimPttButtonEnabled(): Boolean = false

        fun setSonimPttButtonEnabled(enabled: Boolean) {}

        fun sonimEmergencyButtonEnabled(): Boolean = false

        fun setSonimEmergencyButtonEnabled(enabled: Boolean) {}

        // ---- TX / RX preferences (Settings → TX/RX) ----
        // Latched (full-duplex) call mode. While on, channel stays
        // open in both directions. Off = standard half-duplex PTT.
        fun latchedMode(): Boolean

        fun setLatchedMode(enabled: Boolean)

        // Auto-release a stuck PTT after this many seconds. 0 disables.
        fun pttTimeoutSec(): Int

        fun setPttTimeoutSec(seconds: Int)

        // Auto-end a latched call after this many idle seconds. 0 disables.
        fun latchedTimeoutSec(): Int

        fun setLatchedTimeoutSec(seconds: Int)

        // Hot Mic mode: SCO stays warm for the whole channel session
        // instead of just the 5s post-burst cool-down. Eliminates the
        // 500-1500 ms BT cold-start on the first PTT after a long
        // pause. Cost: media apps stay paused for the entire mission.
        fun hotMicMode(): Boolean

        fun setHotMicMode(enabled: Boolean)

        // ---- H5: permission revocation surface ----
        // User-friendly names of permissions XV needs but doesn't
        // currently have. Empty when everything is granted. Used to
        // surface a toast banner when the dropdown opens so the
        // operator notices the revocation before they try to TX and
        // hit silent SecurityException failures.
        fun missingPermissionLabels(): List<String> = emptyList()

        // Launch the runtime-permission prompt for missing entries.
        fun requestMissingPermissions() {}

        // ---- Channel Members picker ----
        // Per-slot roster of everyone currently on the channel the slot
        // is joined to, enriched with XV presence + jump suggestions.
        // Map key is slot index (0=VS1, 1=VS2); slots that aren't
        // connected are omitted from the map (caller renders an empty
        // column for those). Used by the title-bar 🕒 picker (both
        // slots side-by-side) and the per-PTT 👥 button
        // ([channelMembersForSlot] returns just one slot's list).
        fun channelMembersBySlot(): Map<Int, SlotMembers> = emptyMap()

        // Single-slot variant for the per-PTT 👥 button. Returns the
        // same SlotMembers shape; null when the slot isn't connected.
        fun channelMembersForSlot(slot: Int): SlotMembers? = null

        // Pan + zoom the map to a peer's CoT marker. Operator action
        // from the Recent Users picker — no-op + log if the marker
        // doesn't exist anymore (peer dropped offline, ATAK closed
        // their CoT layer between the picker open and the tap, etc).
        fun findOnMap(deviceUid: String) {}

        // Short human label for the active audio route — "AINA APTT",
        // "Speaker", "Earpiece", "Wired headset", "Auto". The dropdown
        // shows this in the main view so the operator can see at a
        // glance whether they're on AINA or built-in audio without
        // checking the headset LED.
        fun currentAudioRouteLabel(): String = "Auto"

        // In-app escape hatch for an active call. Fired from the
        // always-visible "End Call" bar in xv_main when isInCall()==true,
        // independent of the system CallStyle notification's Hang Up
        // action — so the operator is never stranded if the shade is
        // locked, the heads-up was dismissed, or the OEM dropped the
        // notification. Implementation should drive the same teardown
        // path the notification's Hang Up does (Telecom Connection
        // setDisconnected → externalTeardownListener cleanup).
        fun endCall() {}
    }

    /** One row in the TAK-server picker. `isPreferred` marks the row the
     *  operator explicitly chose (null pref → no row is preferred and the
     *  "(auto)" sentinel row is the active one). `connected` mirrors
     *  ATAK's live state so the picker can show ●/○ without re-querying. */
    data class TakHostInfo(
        val description: String,
        val host: String,
        val connected: Boolean,
        val isPreferred: Boolean,
    )

    /** Display row for the Channel Members picker. One per Mumble user
     *  currently on the slot's joined channel. Non-XV peers (Mumla, VX
     *  clients without a `<__xv>` advertisement) have isXvPeer=false,
     *  deviceUid=null, availableJumpChannels=emptyList — the UI greys
     *  the row and disables tap-to-map / move-to-channel for those. */
    data class ChannelMember(
        val mumbleSessionId: Int,
        val callsign: String,
        val slot: Int,
        val isXvPeer: Boolean,
        val deviceUid: String?,
        val talkingNow: Boolean,
        val availableJumpChannels: List<JumpChannel>,
    )

    /** A channel a peer is on AND that we have PARTICIPATE permission
     *  for. Long-press on a row offers "Move VS1 → [name]" / "Move
     *  VS2 → [name]" for each entry. The same list applies to both
     *  slots in the offered actions; gating is local-ACL only — we
     *  can't verify the REMOTE peer's permissions. */
    data class JumpChannel(
        val channelId: Int,
        val channelName: String,
    )

    /** Bundle returned by [Listener.channelMembersBySlot] /
     *  [Listener.channelMembersForSlot]. [channelName] is the slot's
     *  currently-joined channel; [members] are the people on it (may
     *  be empty if it's just us). */
    data class SlotMembers(
        val slot: Int,
        val channelName: String,
        val members: List<ChannelMember>,
    )

    @Volatile
    private var mainView: View? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Force an immediate refreshMain() pass on the UI thread. Called by
     * the plugin when something fast-moving changes that shouldn't wait
     * for the next 2s poll tick — currently the TX-state edge (PTT-down
     * → "● TRANSMITTING" / PTT-up → "HOLD TO TX") because anything
     * slower than ~50ms there feels sluggish to the operator. Safe to
     * spam: the actual refreshMain work coalesces at the View level
     * (TextView.setText / setBackgroundResource short-circuit when the
     * value is unchanged).
     */
    fun refreshNow() {
        mainHandler.post { refreshMain() }
    }

    private val refreshTask =
        object : Runnable {
            override fun run() {
                refreshMain()
                mainHandler.postDelayed(this, REFRESH_MS)
            }
        }

    fun show() {
        mainHandler.post {
            val v = inflateMain()
            showDropDown(v, FIVE_TWELFTHS_WIDTH, FULL_HEIGHT, FULL_WIDTH, HALF_HEIGHT, this)
            startRefreshLoop()
            warnIfPermissionsRevoked()
        }
    }

    private fun warnIfPermissionsRevoked() {
        val missing = controller.missingPermissionLabels()
        if (missing.isEmpty()) return
        val pretty = missing.joinToString(", ")
        android.widget.Toast
            .makeText(
                pluginContext,
                "XV permissions revoked: $pretty — opening grant prompt",
                android.widget.Toast.LENGTH_LONG,
            ).show()
        controller.requestMissingPermissions()
    }

    override fun disposeImpl() {
        stopRefreshLoop()
        mainView = null
    }

    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        // Tool drawer dispatch — we receive SHOW_XV via AtakBroadcast.
        show()
    }

    override fun onDropDownSelectionRemoved() {}

    override fun onDropDownClose() = stopRefreshLoop()

    override fun onDropDownSizeChanged(
        p0: Double,
        p1: Double,
    ) {}

    override fun onDropDownVisible(visible: Boolean) {
        if (visible) startRefreshLoop() else stopRefreshLoop()
    }

    private fun startRefreshLoop() {
        mainHandler.removeCallbacks(refreshTask)
        mainHandler.post(refreshTask)
    }

    private fun stopRefreshLoop() {
        mainHandler.removeCallbacks(refreshTask)
    }

    private fun inflateMain(): View {
        val inflater = LayoutInflater.from(pluginContext)
        val v = inflater.inflate(R.layout.xv_main, null)
        v.findViewById<Button>(R.id.xv_btn_settings).setOnClickListener { showSettings() }
        v.findViewById<Button>(R.id.xv_btn_call).setOnClickListener { showChannelMembersPicker() }
        // Per-PTT 👥 buttons: VS1 always exists, VS2 only when the
        // secondary slot has been configured (its card visibility
        // tracks the same condition; we wire the listener unconditionally
        // and let the count badge update path no-op when slot=null).
        v.findViewById<Button>(R.id.xv_btn_members_1).setOnClickListener {
            showSlotMembersPicker(0)
        }
        v.findViewById<Button>(R.id.xv_btn_members_2).setOnClickListener {
            showSlotMembersPicker(1)
        }
        v.findViewById<Button>(R.id.xv_btn_end_call).setOnClickListener {
            controller.endCall()
            // Push an immediate refresh so the bar hides without
            // waiting for the 2s poll tick — Telecom teardown is async
            // but the user expects the affordance to disappear right
            // after their tap, otherwise the next tap fires another
            // teardown on a call that's already ending.
            refreshNow()
        }
        v.findViewById<Button>(R.id.xv_btn_ptt).setOnTouchListener(pttListener(slot = 0, label = "VS1"))
        v.findViewById<Button>(R.id.xv_btn_ptt2).setOnTouchListener(pttListener(slot = 1, label = "VS2"))
        // Header bar above each PTT button is the channel picker entry
        // point — keeps "what channel am I keying" and "key the channel"
        // in one self-contained widget instead of two separate cards.
        v.findViewById<View>(R.id.xv_ptt_header_1).setOnClickListener { showChannelPicker(slot = 0) }
        v.findViewById<View>(R.id.xv_ptt_header_2).setOnClickListener { showChannelPicker(slot = 1) }
        // "+ Add second channel" button appears in place of the VS2 card
        // until a secondary is configured. Same picker, same flow.
        v.findViewById<Button>(R.id.xv_btn_add_vs2).setOnClickListener { showChannelPicker(slot = 1) }
        // Auto-size channel labels so long names shrink to fit instead
        // of marquee-scrolling. Marquee was visually jittery and the
        // operator complained that the tail of long names wasn't
        // visible. Range 13–20sp matches the XML default at the upper
        // end so short names look the same as before; long names step
        // down per 1sp until they fit.
        val autosizeMin = 13
        val autosizeMax = 20
        val autosizeStep = 1
        val unitSp = android.util.TypedValue.COMPLEX_UNIT_SP
        v
            .findViewById<TextView>(R.id.xv_ptt_channel_1)
            .setAutoSizeTextTypeUniformWithConfiguration(autosizeMin, autosizeMax, autosizeStep, unitSp)
        v
            .findViewById<TextView>(R.id.xv_ptt_channel_2)
            .setAutoSizeTextTypeUniformWithConfiguration(autosizeMin, autosizeMax, autosizeStep, unitSp)
        mainView = v
        refreshMain()
        return v
    }

    // PTT touch handler factory. Slot is fixed at the time the listener
    // is wired (one per button), so a stray slot-1 press while VS2 is
    // unregistered still calls startTx(1) — the transport's resolveTarget
    // falls back to primary, which is the desired graceful behavior.
    private fun pttListener(
        slot: Int,
        label: String,
    ): View.OnTouchListener =
        View.OnTouchListener { btn, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    android.util.Log.i("XV_PTT", ">>> ON-SCREEN $label (slot=$slot) DOWN <<<")
                    // Manually flip the pressed state so xv_button_bg's
                    // state_pressed selector fires immediately. Returning
                    // true below consumes the event and prevents Android
                    // from setting isPressed via the View's own touch
                    // dispatch, so without this the operator sees no red
                    // feedback until refreshMain() eventually swaps the
                    // background to xv_button_bg_transmitting — a lag of
                    // up to ~700 ms while mic priming, TPT playback, and
                    // Telecom-call transition all serialize. That looks
                    // like a broken button. Field-observed 2026-07-07.
                    btn.isPressed = true
                    controller.startTx(slot)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    android.util.Log.i("XV_PTT", ">>> ON-SCREEN $label (slot=$slot) UP <<<")
                    btn.isPressed = false
                    controller.stopTx()
                    btn.performClick()
                    true
                }
                else -> false
            }
        }

    private fun refreshMain() {
        val v = mainView ?: return
        // In-call escape bar — surfaces above everything else when a
        // private call is up so the operator always has a visible exit
        // path even if the system CallStyle notification didn't render.
        val inCall = controller.isInCall()
        v.findViewById<View>(R.id.xv_in_call_bar).visibility =
            if (inCall) View.VISIBLE else View.GONE
        val connected = controller.isMumbleConnected()
        val reconnect = if (connected) null else controller.mumbleReconnectInfo()
        val dot = v.findViewById<View>(R.id.xv_status_dot)
        // Three states: green=live, amber=reconnecting (transient drop
        // being retried by ReconnectingMumbleTransport), dim=down.
        val dotColor =
            when {
                connected -> R.color.xv_ok
                reconnect != null -> R.color.xv_warn
                else -> R.color.xv_text_dim
            }
        dot.background?.colorFilter =
            PorterDuffColorFilter(
                pluginContext.resources.getColor(dotColor, null),
                PorterDuff.Mode.SRC_IN,
            )
        v.findViewById<TextView>(R.id.xv_server_label).text =
            when {
                reconnect != null -> {
                    val seconds = (reconnect.nextDelayMs + 999L) / 1000L
                    "reconnecting in ${seconds}s (#${reconnect.attempt})"
                }
                else -> controller.connectedTakHost() ?: "no TAK server"
            }

        // Audio route indicator (right side of the connection line).
        // Glyph + label so it reads at a glance: 🎙️ for external
        // mic-bearing devices (BT/wired/USB headsets), 🔊 for built-in
        // speaker, 📞 for earpiece.
        val routeLabel = controller.currentAudioRouteLabel()
        val routeGlyph =
            when (routeLabel) {
                "Speaker", "Auto" -> "🔊"
                "Earpiece" -> "📞"
                else -> "🎙️"
            }
        v.findViewById<TextView>(R.id.xv_audio_route).text = "$routeGlyph $routeLabel"

        // VS1 PTT card — channel name lives on the header above the
        // PTT button. RX speaker indicator is appended when peers are
        // active so the operator can tell from the same widget that
        // someone's transmitting. When the server has us suppressed
        // on this channel (OTS direction OUT), append a "(listen
        // only)" badge and grey the PTT button so the operator knows
        // before they press.
        val primary = controller.currentChannelName()
        val vs1Speakers = controller.activeSpeakers(0)
        val vs1CanSpeak = primary != null && controller.canSpeakOnSlot(0)
        val vs1ListenOnly = primary != null && !vs1CanSpeak
        val vs1Label =
            buildChannelRowText(
                channel = primary,
                speakers = vs1Speakers,
                connected = connected,
                idleText = "(tap to pick)",
            )
        v.findViewById<TextView>(R.id.xv_ptt_channel_1).text =
            if (vs1ListenOnly) "🤐  $vs1Label" else vs1Label
        val ptt1 = v.findViewById<Button>(R.id.xv_btn_ptt)
        val vs1Transmitting = controller.isTransmittingOnSlot(0)
        ptt1.text =
            when {
                vs1ListenOnly -> "LISTEN ONLY"
                vs1Transmitting -> "● TRANSMITTING"
                else -> "HOLD TO TX"
            }
        ptt1.alpha = if (vs1ListenOnly) 0.5f else 1.0f
        // Background flips to the red "active" theme color while we're
        // on the air. Press-state (xv_button_bg selector) already
        // changes color on touch, but that's only while the finger is
        // down — it goes back instantly on release while the actual TX
        // burst continues through the cooldown. This makes the button
        // stay red across the entire transmit window so the operator
        // sees unambiguous "you are on the air" feedback.
        ptt1.setBackgroundResource(
            if (vs1Transmitting) R.drawable.xv_button_bg_transmitting else R.drawable.xv_button_bg,
        )

        // VS2 card visibility — only render when a secondary channel
        // is configured. Until then, show a compact "+ Add VS2" button
        // in the same vertical slot. This keeps the Surface Duo
        // layout from running off-screen on the common (no-VS2) case.
        val secondaryName = controller.secondaryChannelName()
        val secondaryLive = controller.secondaryRegistered()
        val vs2Speakers = controller.activeSpeakers(1)
        val vs2Card = v.findViewById<View>(R.id.xv_ptt_card_2)
        val addVs2Btn = v.findViewById<View>(R.id.xv_btn_add_vs2)
        if (secondaryName.isNullOrBlank()) {
            vs2Card.visibility = View.GONE
            addVs2Btn.visibility = View.VISIBLE
        } else {
            vs2Card.visibility = View.VISIBLE
            addVs2Btn.visibility = View.GONE
            val secondaryLabel = v.findViewById<TextView>(R.id.xv_ptt_channel_2)
            val vs2CanSpeak = secondaryLive && controller.canSpeakOnSlot(1)
            val vs2ListenOnly = secondaryLive && !vs2CanSpeak
            val baseLabel =
                if (secondaryLive) {
                    buildChannelRowText(
                        channel = secondaryName,
                        speakers = vs2Speakers,
                        connected = true,
                        idleText = secondaryName,
                    )
                } else {
                    "$secondaryName (joining…)"
                }
            secondaryLabel.text =
                if (vs2ListenOnly) "🤐  $baseLabel" else baseLabel
            secondaryLabel.setTextColor(
                pluginContext.resources.getColor(
                    if (secondaryLive) R.color.xv_text else R.color.xv_text_dim,
                    null,
                ),
            )
            val ptt2 = v.findViewById<Button>(R.id.xv_btn_ptt2)
            ptt2.isEnabled = secondaryLive
            val vs2Transmitting = secondaryLive && controller.isTransmittingOnSlot(1)
            ptt2.text =
                when {
                    vs2ListenOnly -> "LISTEN ONLY"
                    vs2Transmitting -> "● TRANSMITTING"
                    else -> "HOLD TO TX"
                }
            ptt2.alpha =
                when {
                    !secondaryLive -> 0.4f
                    vs2ListenOnly -> 0.5f
                    else -> 1.0f
                }
            ptt2.setBackgroundResource(
                if (vs2Transmitting) R.drawable.xv_button_bg_transmitting else R.drawable.xv_button_bg,
            )
        }

        // Route / audio-state / TPT debug labels and the standalone
        // speaker indicator have been removed from the operational view
        // — they live in Settings. Active-speaker info still surfaces
        // via buildChannelRowText on each PTT card's channel name.

        // Per-PTT 👥 button count badges. Live count of who's on the
        // slot's joined channel. Updated on every refreshMain (2s
        // poll + immediate refreshes on TX state changes etc.) so
        // joins/leaves show without any operator action. Shows 0
        // when the slot isn't connected; the button stays clickable
        // (operator gets the "empty" picker view).
        val vs1Members = controller.channelMembersForSlot(0)?.members?.size ?: 0
        v.findViewById<Button>(R.id.xv_btn_members_1).text = "👥 $vs1Members"
        val vs2Members = controller.channelMembersForSlot(1)?.members?.size ?: 0
        v.findViewById<Button>(R.id.xv_btn_members_2).text = "👥 $vs2Members"
    }

    private fun showSettings() {
        val inflater = LayoutInflater.from(pluginContext)
        val v = inflater.inflate(R.layout.xv_settings, null)
        wireSettings(v)
        showDropDown(v, FIVE_TWELFTHS_WIDTH, FULL_HEIGHT, FULL_WIDTH, HALF_HEIGHT, this)
    }

    // Channel picker overlay. Replaces the previous Settings → spinner
    // workflow for the most-frequent operational change. Big touch
    // targets (56dp), dark-on-dark styled, "● now" marker on the active
    // channel, and a "(none)" sentinel for the secondary slot so the
    // user can clear VS2 without leaving primary.
    private fun showChannelPicker(slot: Int) {
        // Block channel switching while a private call is active —
        // accidentally tapping a header during a call would leave the
        // call's temp channel and silently drop the operator out.
        // Telecom's call lifecycle is the source of truth; XvCallBridge
        // surfaces it via Controller.isInCall.
        if (controller.isInCall()) {
            android.widget.Toast
                .makeText(
                    pluginContext,
                    "Channel selector is locked during a call — hang up first",
                    android.widget.Toast.LENGTH_SHORT,
                ).show()
            return
        }
        val inflater = LayoutInflater.from(pluginContext)
        val v = inflater.inflate(R.layout.xv_channel_picker, null)
        v.findViewById<TextView>(R.id.xv_picker_title).text =
            if (slot == 0) "Select primary channel (VS1)" else "Select secondary channel (VS2)"
        v.findViewById<Button>(R.id.xv_picker_back).setOnClickListener {
            mainHandler.post { inflateMainAndShow() }
        }

        val list = v.findViewById<android.widget.LinearLayout>(R.id.xv_picker_list)
        list.removeAllViews()
        val channels = controller.availableChannels()
        val current =
            if (slot == 0) {
                controller.currentChannelName()
            } else {
                controller.secondaryChannelName()
            }

        // Secondary picker leads with "(none)" so VS2 can be cleared
        // from the same screen.
        if (slot == 1) {
            list.addView(
                buildChannelButton(
                    label = "(none)",
                    isCurrent = current.isNullOrBlank(),
                    participation = Participation.PARTICIPATE,
                ) {
                    controller.setSecondaryChannel("")
                    mainHandler.post { inflateMainAndShow() }
                },
            )
        }
        if (channels.isEmpty()) {
            val tv =
                TextView(pluginContext).apply {
                    text = "No channels yet — Mumble must connect first."
                    setTextColor(pluginContext.resources.getColor(R.color.xv_text_dim, null))
                    textSize = 13f
                    setPadding(16, 24, 16, 24)
                }
            list.addView(tv)
        } else {
            // The currently-joined channel may have its true tier
            // downgraded by OTS direction enforcement (suppress flag
            // on our UserState) — PermissionQuery only sees ACL bits,
            // not direction. Override to LISTEN if the runtime
            // suppress signal says we can't speak. Only valid for
            // the joined channel; other listed channels keep their
            // ACL-derived tier until we'd actually join them.
            val currentSuppressed = !controller.canSpeakOnSlot(slot)
            // The other slot's channel — used to lock it out of THIS
            // picker so VS1 and VS2 can never share a channel (would
            // duplicate every voice frame).
            val otherSlotChannel =
                if (slot == 0) {
                    controller.secondaryChannelName()
                } else {
                    controller.currentChannelName()
                }
            val otherSlotLabel = if (slot == 0) "VS2" else "VS1"
            for (ch in channels) {
                val isCurrent = ch.name.equals(current, ignoreCase = true)
                val isOnOtherSlot =
                    !otherSlotChannel.isNullOrBlank() &&
                        ch.name.equals(otherSlotChannel, ignoreCase = true)
                val effectiveTier =
                    when {
                        // Channel locked to the other slot → render as
                        // unauthorized-style (greyed, disabled).
                        isOnOtherSlot -> Participation.UNAUTHORIZED
                        isCurrent && currentSuppressed && ch.participation == Participation.PARTICIPATE ->
                            Participation.LISTEN
                        else -> ch.participation
                    }
                val labelOverride =
                    if (isOnOtherSlot) "${ch.name}   (in use by $otherSlotLabel)" else ch.name
                list.addView(
                    buildChannelButton(
                        label = labelOverride,
                        isCurrent = isCurrent,
                        participation = effectiveTier,
                    ) {
                        if (effectiveTier == Participation.UNAUTHORIZED) {
                            // No-op — UNAUTHORIZED, or locked by the other
                            // slot. The button is disabled but a stray tap
                            // through the disabled state still falls here.
                            return@buildChannelButton
                        }
                        if (slot == 0) {
                            controller.setPrimaryChannel(ch.name)
                        } else {
                            controller.setSecondaryChannel(ch.name)
                        }
                        mainHandler.post { inflateMainAndShow() }
                    },
                )
            }
        }
        showDropDown(v, FIVE_TWELFTHS_WIDTH, FULL_HEIGHT, FULL_WIDTH, HALF_HEIGHT, this)
    }

    // Channel Members picker — historical doc block from when this was
    // the "Recent Users (last 15 min)" picker; the data semantics moved
    // to "everyone on my channel right now" (replaces presence-cache
    // lookup with the live Mumble roster). Empty-state distinguishes
    // "no peers in window" (cold start, no XV traffic) from "no map"
    // (shouldn't normally happen) so the operator has a clear next
    // action.
    //
    // Title-bar 🕒 Recent entry — two-column dialog showing who's on
    // the VS1 channel and who's on the VS2 channel. Either column is
    // hidden when its slot isn't connected so the live one expands.
    private fun showChannelMembersPicker() {
        val byslot = controller.channelMembersBySlot()
        showChannelMembersDialog(
            vs1 = byslot[0],
            vs2 = byslot[1],
        )
    }

    /** Per-PTT 👥 entry — same two-column layout but the opposite slot's
     *  column is hidden, so the live slot takes the full width. */
    private fun showSlotMembersPicker(slot: Int) {
        val sm = controller.channelMembersForSlot(slot)
        if (slot == 0) {
            showChannelMembersDialog(vs1 = sm, vs2 = null)
        } else {
            showChannelMembersDialog(vs1 = null, vs2 = sm)
        }
    }

    private fun showChannelMembersDialog(
        vs1: SlotMembers?,
        vs2: SlotMembers?,
    ) {
        val inflater = LayoutInflater.from(pluginContext)
        val v = inflater.inflate(R.layout.xv_channel_members, null)
        v.findViewById<Button>(R.id.xv_picker_back).setOnClickListener {
            mainHandler.post { inflateMainAndShow() }
        }
        // Title reflects which columns are visible: both = "Channel
        // Members", single = "[slot] Channel Members" so the operator
        // knows which slot they tapped on.
        v.findViewById<TextView>(R.id.xv_picker_title).text =
            when {
                vs1 != null && vs2 != null -> "Channel Members"
                vs1 != null -> "VS1 Channel Members"
                vs2 != null -> "VS2 Channel Members"
                else -> "Channel Members"
            }
        populateMembersColumn(
            colRoot = v.findViewById(R.id.xv_members_col_vs1),
            headerView = v.findViewById(R.id.xv_members_header_vs1),
            listView = v.findViewById(R.id.xv_members_list_vs1),
            data = vs1,
            slotLabel = "VS1",
        )
        populateMembersColumn(
            colRoot = v.findViewById(R.id.xv_members_col_vs2),
            headerView = v.findViewById(R.id.xv_members_header_vs2),
            listView = v.findViewById(R.id.xv_members_list_vs2),
            data = vs2,
            slotLabel = "VS2",
        )
        showDropDown(v, FIVE_TWELFTHS_WIDTH, FULL_HEIGHT, FULL_WIDTH, HALF_HEIGHT, this)
    }

    private fun populateMembersColumn(
        colRoot: View,
        headerView: TextView,
        listView: android.widget.LinearLayout,
        data: SlotMembers?,
        slotLabel: String,
    ) {
        if (data == null) {
            // Slot not connected → hide column entirely. The remaining
            // column's layout_weight=1 makes it occupy the full row.
            colRoot.visibility = View.GONE
            return
        }
        colRoot.visibility = View.VISIBLE
        headerView.text = "$slotLabel · ${data.channelName} (${data.members.size})"
        listView.removeAllViews()
        if (data.members.isEmpty()) {
            val tv =
                TextView(pluginContext).apply {
                    text = "Empty — you're the only one here."
                    setTextColor(pluginContext.resources.getColor(R.color.xv_text_dim, null))
                    textSize = 12f
                    setPadding(8, 16, 8, 16)
                }
            listView.addView(tv)
            return
        }
        for (member in data.members) {
            listView.addView(buildChannelMemberRow(member))
        }
    }

    /** Per-row button. Tap = pan map to peer's marker (XV peers only).
     *  Long-press = bottom sheet with "Move VSn → channel" actions if
     *  the peer's <__xv> advertises channels the local operator can
     *  enter. Non-XV peers get a greyed style + a toast on tap, and
     *  no long-press actions. */
    private fun buildChannelMemberRow(member: ChannelMember): Button {
        val btn = Button(pluginContext)
        val talkingDot = if (member.talkingNow) "$RX_DOT " else "    "
        val tag = if (member.isXvPeer) "" else "   (non-XV)"
        btn.text = "$talkingDot${member.callsign}$tag"
        btn.textSize = 14f
        btn.isAllCaps = false
        btn.gravity = android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL
        btn.setPadding(12, 0, 12, 0)
        val textColor =
            if (member.isXvPeer) {
                R.color.xv_text
            } else {
                R.color.xv_text_dim
            }
        btn.setTextColor(pluginContext.resources.getColor(textColor, null))
        btn.setBackgroundResource(R.drawable.xv_button_bg)
        val lp =
            android.widget.LinearLayout
                .LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    (44 * pluginContext.resources.displayMetrics.density).toInt(),
                ).apply {
                    topMargin = (4 * pluginContext.resources.displayMetrics.density).toInt()
                }
        btn.layoutParams = lp
        btn.setOnClickListener {
            val uid = member.deviceUid
            if (uid == null) {
                android.widget.Toast
                    .makeText(
                        pluginContext,
                        "No map fix for non-XV peer ${member.callsign}",
                        android.widget.Toast.LENGTH_SHORT,
                    ).show()
                return@setOnClickListener
            }
            controller.findOnMap(uid)
            try {
                closeDropDown()
            } catch (t: Throwable) {
                mainHandler.post { inflateMainAndShow() }
            }
        }
        if (member.availableJumpChannels.isNotEmpty()) {
            btn.setOnLongClickListener {
                showJumpChannelSheet(member)
                true
            }
        }
        return btn
    }

    /** Long-press follow-up. Lists "Move VS1 → [channel]" / "Move VS2 →
     *  [channel]" for every channel the peer is on that the local
     *  operator has PARTICIPATE permission on. Tap to issue the join. */
    private fun showJumpChannelSheet(member: ChannelMember) {
        val actions = mutableListOf<Pair<String, () -> Unit>>()
        for (jc in member.availableJumpChannels) {
            // Two actions per channel — one per local slot. Operator
            // picks which slot to move. Channel name surfaces in the
            // action label so the operator doesn't have to remember
            // peer-channel-mapping at a glance.
            actions +=
                "Move VS1 → ${jc.channelName}" to {
                    controller.setPrimaryChannel(jc.channelName)
                }
            actions +=
                "Move VS2 → ${jc.channelName}" to {
                    controller.setSecondaryChannel(jc.channelName)
                }
        }
        val labels = actions.map { it.first }.toTypedArray()
        android.app.AlertDialog
            .Builder(mapViewContext())
            .setTitle("Follow ${member.callsign} →")
            .setItems(labels) { dlg, which ->
                actions[which].second()
                dlg.dismiss()
                try {
                    closeDropDown()
                } catch (t: Throwable) {
                    mainHandler.post { inflateMainAndShow() }
                }
            }.setNegativeButton("Cancel", null)
            .show()
    }

    private fun mapViewContext(): Context =
        com.atakmap.android.maps.MapView
            .getMapView()
            ?.context ?: pluginContext

    private fun buildChannelButton(
        label: String,
        isCurrent: Boolean,
        participation: Participation,
        onPick: () -> Unit,
    ): Button {
        val btn = Button(pluginContext)
        // Icons only mark the *denied* tiers — clean labels for
        // channels the operator can fully use (would otherwise add
        // noise on every row). Trailing "● now" still wins when
        // this is the joined channel.
        //   PARTICIPATE / UNKNOWN — no icon, normal style
        //   LISTEN — 🤐 zipper-mouth, "can't talk" (single-char,
        //            renders cleanly on every Android version)
        //   UNAUTHORIZED — 🔒 lock, can't even enter
        val prefix =
            when (participation) {
                Participation.LISTEN -> "🤐  "
                Participation.UNAUTHORIZED -> "🔒  "
                else -> ""
            }
        val suffix = if (isCurrent) "   $RX_DOT now" else ""
        btn.text = "$prefix$label$suffix"
        btn.textSize = 16f
        btn.isAllCaps = false
        btn.gravity = android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL
        btn.setPadding(20, 0, 20, 0)
        val textColor =
            when (participation) {
                Participation.UNAUTHORIZED -> R.color.xv_text_dim
                else -> R.color.xv_text
            }
        btn.setTextColor(pluginContext.resources.getColor(textColor, null))
        btn.alpha = if (participation == Participation.UNAUTHORIZED) 0.45f else 1.0f
        btn.setBackgroundResource(
            if (isCurrent) R.drawable.xv_button_active_bg else R.drawable.xv_button_bg,
        )
        val lp =
            android.widget.LinearLayout
                .LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    (56 * pluginContext.resources.displayMetrics.density).toInt(),
                ).apply {
                    topMargin = (6 * pluginContext.resources.displayMetrics.density).toInt()
                }
        btn.layoutParams = lp
        // Even though UNAUTHORIZED is no-op'd in the click handler, also
        // disable the button so the system gives no haptic / ripple
        // feedback that suggests it's actionable.
        btn.isEnabled = participation != Participation.UNAUTHORIZED
        btn.setOnClickListener { onPick() }
        return btn
    }

    private fun wireSettings(v: View) {
        v.findViewById<Button>(R.id.xv_settings_back).setOnClickListener {
            // Re-open main panel
            mainHandler.post { inflateMainAndShow() }
        }

        wireSettingsTabs(v)
        wireTxRxSection(v)
        wireChannelSelectors(v)
        wirePreferencesSection(v)
        wireBtOffBanner(v)

        val takLabel = v.findViewById<TextView>(R.id.xv_tak_server_label)
        renderTakServerLabel(takLabel)
        // Tappable affordance — opens the picker that lets the operator
        // override the auto-pick when multiple TAK servers are enrolled.
        // Always tappable (even with 0 or 1 servers) so the operator has
        // a discoverable place to learn about the multi-server case.
        takLabel.isClickable = true
        takLabel.isFocusable = true
        takLabel.setOnClickListener { showTakServerPicker(takLabel) }

        v.findViewById<Button>(R.id.xv_btn_mumble_connect).setOnClickListener { controller.connectMumble() }
        v.findViewById<Button>(R.id.xv_btn_mumble_disconnect).setOnClickListener { controller.disconnectMumble() }
    }

    private fun renderTakServerLabel(label: TextView) {
        val host = controller.connectedTakHost()
        val pref = controller.preferredTakHost()
        val tag =
            when {
                host == null -> "no TAK server enrolled"
                !pref.isNullOrBlank() -> "$host  (pinned)"
                else -> "$host  (auto)"
            }
        label.text = "$tag  ▾"
    }

    // Single-choice picker: "(auto)" sentinel + one row per enrolled server.
    // ● prefix when ATAK reports the server connected; ○ when configured-
    // but-down. Selecting a row persists the pick and reconnects Mumble
    // when the new pick resolves to a different host than what's live.
    private fun showTakServerPicker(labelToRefresh: TextView) {
        val hosts = controller.availableTakHosts()
        if (hosts.isEmpty()) {
            android.widget.Toast
                .makeText(
                    pluginContext,
                    "No TAK servers enrolled in ATAK — enroll one in Settings → Network",
                    android.widget.Toast.LENGTH_LONG,
                ).show()
            return
        }
        // Sentinel for "auto" sits at index 0; real hosts shift to 1..n.
        val labels = mutableListOf("(auto — pick first connected)")
        val resolvedHosts = mutableListOf<String?>(null)
        val currentPref = controller.preferredTakHost()
        var checkedIdx = if (currentPref.isNullOrBlank()) 0 else -1
        hosts.forEachIndexed { i, h ->
            val dot = if (h.connected) "●" else "○"
            val descLine = if (h.description != h.host) "${h.description}  (${h.host})" else h.host
            labels += "$dot  $descLine"
            resolvedHosts += h.host
            if (h.isPreferred) checkedIdx = i + 1
        }
        android.app.AlertDialog
            .Builder(mapViewContext())
            .setTitle("Mumble TAK server")
            .setSingleChoiceItems(labels.toTypedArray(), checkedIdx) { dialog, which ->
                val picked = resolvedHosts[which]
                controller.setPreferredTakHost(picked)
                renderTakServerLabel(labelToRefresh)
                dialog.dismiss()
                android.widget.Toast
                    .makeText(
                        pluginContext,
                        if (picked == null) {
                            "Auto — XV picks the first connected TAK server"
                        } else {
                            "XV → $picked"
                        },
                        android.widget.Toast.LENGTH_SHORT,
                    ).show()
            }.setNegativeButton("Cancel", null)
            .show()
    }

    // Three-tab settings layout (TX/RX, Channels, Preferences). Tabs are
    // plain Buttons swapping the visibility of three sibling LinearLayouts —
    // simpler than a TabLayout/ViewPager for a panel this small. The
    // selected tab is highlighted via alpha.
    /**
     * Banner at the top of the Devices tab that shows only when the
     * Bluetooth adapter is disabled. Tapping opens the system BT
     * settings so the operator can turn it back on without leaving
     * XV.
     *
     * Wiring lifecycle: registers a broadcast receiver for
     * `BluetoothAdapter.ACTION_STATE_CHANGED` when the settings view
     * is attached to the window and unregisters on detach. That's
     * scoped to the dropdown's lifetime — no receiver leaks past the
     * moment the operator closes the panel.
     *
     * Rationale: previously, opening the AINA picker with BT off left
     * the operator staring at an empty spinner with no explanation
     * (`availableAinaDevices()` returns nothing when the adapter is
     * off). This banner is the "why is the dropdown empty" answer,
     * one tap away from the fix.
     */
    private fun wireBtOffBanner(v: View) {
        val banner = v.findViewById<TextView>(R.id.xv_prefs_bt_off_banner) ?: return
        banner.setOnClickListener {
            // Prefer the in-place system prompt over dropping the
            // operator into full Bluetooth settings — the prompt is a
            // one-tap "Allow XV to turn on Bluetooth?" dialog that
            // leaves the operator on XV's screen. Falls back to the
            // system Bluetooth settings screen only if the prompt
            // intent isn't resolvable (stripped ROM, unusual OEM
            // policy). Field-observed 2026-07-07: operator flagged
            // the ACTION_BLUETOOTH_SETTINGS detour as more UX
            // friction than necessary.
            val requested =
                try {
                    val enableIntent =
                        Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        }
                    pluginContext.startActivity(enableIntent)
                    true
                } catch (_: Throwable) {
                    false
                }
            if (!requested) {
                try {
                    val fallback =
                        Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        }
                    pluginContext.startActivity(fallback)
                } catch (_: Throwable) {
                }
            }
        }

        fun refresh() {
            val adapter =
                try {
                    val bm = pluginContext.getSystemService(Context.BLUETOOTH_SERVICE)
                        as? android.bluetooth.BluetoothManager
                    bm?.adapter ?: BluetoothAdapter.getDefaultAdapter()
                } catch (_: Throwable) {
                    null
                }
            banner.visibility = if (adapter?.isEnabled == true) View.GONE else View.VISIBLE
        }

        val stateReceiver =
            object : BroadcastReceiver() {
                override fun onReceive(
                    context: Context?,
                    intent: Intent?,
                ) {
                    if (intent?.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
                    refresh()
                    // Repopulate the AINA + external-button pickers so
                    // newly-visible (or newly-hidden) bonded devices
                    // show up without the operator having to close
                    // and reopen the Settings panel. wireAinaPicker /
                    // wireExternalButtonPicker are idempotent — each
                    // just resets adapter + selection + listener on
                    // the same spinner view. Field-observed 2026-07-07:
                    // after re-enabling Bluetooth from the banner tap,
                    // the AINA picker was stuck on "Screen-only PTT"
                    // even though the operator's bonded AINA was
                    // back in system BT state — the picker had been
                    // populated once at Settings-open time when the
                    // adapter reported no devices.
                    val state =
                        intent.getIntExtra(
                            BluetoothAdapter.EXTRA_STATE,
                            BluetoothAdapter.ERROR,
                        )
                    if (state == BluetoothAdapter.STATE_ON ||
                        state == BluetoothAdapter.STATE_OFF
                    ) {
                        try {
                            wireAinaPicker(v)
                        } catch (t: Throwable) {
                            android.util.Log.w("XvSettings", "wireAinaPicker refresh after BT state change threw", t)
                        }
                        try {
                            wireExternalButtonPicker(v)
                        } catch (t: Throwable) {
                            android.util.Log.w("XvSettings", "wireExternalButtonPicker refresh after BT state change threw", t)
                        }
                    }
                }
            }

        v.addOnAttachStateChangeListener(
            object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(view: View) {
                    try {
                        pluginContext.registerReceiver(
                            stateReceiver,
                            IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
                        )
                    } catch (_: Throwable) {
                    }
                    refresh()
                }

                override fun onViewDetachedFromWindow(view: View) {
                    try {
                        pluginContext.unregisterReceiver(stateReceiver)
                    } catch (_: IllegalArgumentException) {
                        // Not registered — attach-fail path or double-detach; ignore.
                    } catch (_: Throwable) {
                    }
                }
            },
        )
        // Set initial visibility BEFORE attach fires so the first
        // frame of the panel is already correct — avoids a brief
        // "banner missing then appears" flash if the adapter is off.
        refresh()
    }

    private fun wireSettingsTabs(v: View) {
        val tabTxRx = v.findViewById<Button>(R.id.xv_tab_txrx)
        val tabCalls = v.findViewById<Button>(R.id.xv_tab_calls)
        val tabPrefs = v.findViewById<Button>(R.id.xv_tab_prefs)
        val sectionTxRx = v.findViewById<View>(R.id.xv_section_txrx)
        val sectionCalls = v.findViewById<View>(R.id.xv_section_calls)
        val sectionPrefs = v.findViewById<View>(R.id.xv_section_prefs)
        // List order MUST match the tab strip's visual order so
        // select(0) puts the first tab up. Operator-visible ordering
        // is Devices (prefs) | TX/RX | Server (calls) — Devices leads
        // because it carries the AINA / external-button / audio-device
        // pickers operators touch every session. The XML id names
        // still say "prefs" for backward compatibility with the
        // findViewById calls above.
        val tabs = listOf(tabPrefs, tabTxRx, tabCalls)
        val sections = listOf(sectionPrefs, sectionTxRx, sectionCalls)

        fun select(idx: Int) {
            sections.forEachIndexed { i, s -> s.visibility = if (i == idx) View.VISIBLE else View.GONE }
            tabs.forEachIndexed { i, b -> b.alpha = if (i == idx) 1.0f else 0.55f }
        }
        tabPrefs.setOnClickListener { select(0) }
        tabTxRx.setOnClickListener { select(1) }
        tabCalls.setOnClickListener { select(2) }
        select(0)
    }

    // TX/RX tab: latched mode toggle + PTT/latched timeout sliders.
    // The latched mode and timeouts are persisted via the Controller —
    // they take effect immediately for any future TX entry.
    private fun wireTxRxSection(v: View) {
        val sw = v.findViewById<Switch>(R.id.xv_switch_latched)
        sw.isChecked = controller.latchedMode()
        sw.setOnCheckedChangeListener { _, on -> controller.setLatchedMode(on) }

        val pttLabel = v.findViewById<TextView>(R.id.xv_label_ptt_timeout)
        val pttSlider = v.findViewById<SeekBar>(R.id.xv_slider_ptt_timeout)
        // Slider XML pins min=20, max=90. Persisted values from older
        // installs (pre-clamp) may fall outside; coerce so the slider
        // doesn't render beyond its track.
        pttSlider.progress = controller.pttTimeoutSec().coerceIn(pttSlider.min, pttSlider.max)
        pttLabel.text = sliderTimeoutLabel(pttSlider.progress)
        pttSlider.setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(
                    s: SeekBar?,
                    p: Int,
                    fromUser: Boolean,
                ) {
                    pttLabel.text = sliderTimeoutLabel(p)
                }

                override fun onStartTrackingTouch(s: SeekBar?) {}

                override fun onStopTrackingTouch(s: SeekBar?) {
                    controller.setPttTimeoutSec(s?.progress ?: 0)
                }
            },
        )

        val latLabel = v.findViewById<TextView>(R.id.xv_label_latched_timeout)
        val latSlider = v.findViewById<SeekBar>(R.id.xv_slider_latched_timeout)
        latSlider.progress = controller.latchedTimeoutSec().coerceIn(0, latSlider.max)
        latLabel.text = sliderTimeoutLabel(latSlider.progress)
        latSlider.setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(
                    s: SeekBar?,
                    p: Int,
                    fromUser: Boolean,
                ) {
                    latLabel.text = sliderTimeoutLabel(p)
                }

                override fun onStartTrackingTouch(s: SeekBar?) {}

                override fun onStopTrackingTouch(s: SeekBar?) {
                    controller.setLatchedTimeoutSec(s?.progress ?: 0)
                }
            },
        )

        val hotMicSw = v.findViewById<Switch>(R.id.xv_switch_hot_mic)
        hotMicSw.isChecked = controller.hotMicMode()
        hotMicSw.setOnCheckedChangeListener { _, on -> controller.setHotMicMode(on) }
    }

    private fun sliderTimeoutLabel(s: Int): String = if (s == 0) "off" else "$s s"

    // Preferences tab: TPT, output route, AINA picker.
    private fun wirePreferencesSection(v: View) {
        // Output route spinner.
        val routeSpinner = v.findViewById<Spinner>(R.id.xv_spinner_route)
        val routes = OutputRoute.entries.toList()
        routeSpinner.adapter =
            ArrayAdapter(pluginContext, R.layout.xv_spinner_item, routes.map { it.name })
        routeSpinner.setSelection(routes.indexOf(controller.outputRoute()).coerceAtLeast(0))
        routeSpinner.onItemSelectedListener =
            object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    p: android.widget.AdapterView<*>?,
                    v: View?,
                    pos: Int,
                    id: Long,
                ) {
                    controller.setOutputRoute(routes[pos])
                }

                override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
            }

        // TPT spinner.
        val tptSpinner = v.findViewById<Spinner>(R.id.xv_spinner_tpt)
        val tones = TptTone.entries.toList()
        tptSpinner.adapter =
            ArrayAdapter(pluginContext, R.layout.xv_spinner_item, tones.map { it.displayName })
        tptSpinner.setSelection(tones.indexOf(controller.tptTone()).coerceAtLeast(0))
        tptSpinner.onItemSelectedListener =
            object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    p: android.widget.AdapterView<*>?,
                    v: View?,
                    pos: Int,
                    id: Long,
                ) {
                    controller.setTptTone(tones[pos])
                }

                override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
            }

        v.findViewById<Button>(R.id.xv_btn_test_tpt).setOnClickListener {
            controller.playTptPreview()
        }

        wireAinaPicker(v)
        wireExternalButtonPicker(v)
        wireBlePttScanButton(v)
        wireAutoConnectBtSwitch(v)
        wireSamsungActiveKeySwitch(v)
        wireSamsungActiveKeyBgSwitch(v)
        wireSonimPttButtonSwitch(v)
        wireSonimEmergencyButtonSwitch(v)
        wireBtAudioOverridePicker(v)
    }

    // BT-audio-override picker: lets the operator choose a specific
    // BT audio device (car BT, headphones) that overrides the AINA's
    // HFP audio path while keeping AINA buttons. "(none)" is the
    // default — audio follows the AINA / route preference.
    //
    // List is rebuilt every time the dropdown opens so newly-paired
    // devices show up without a plugin reload, and disappeared
    // devices drop out automatically.
    private fun wireBtAudioOverridePicker(v: View) {
        val spinner = v.findViewById<Spinner>(R.id.xv_spinner_bt_audio_override) ?: return
        rebuildBtOverrideAdapter(spinner)
        spinner.onItemSelectedListener =
            object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    p: android.widget.AdapterView<*>?,
                    vw: View?,
                    pos: Int,
                    id: Long,
                ) {
                    val items = btOverrideItems
                    if (pos < 0 || pos >= items.size) return
                    val mac = items[pos].mac
                    if (mac == controller.outputBtOverrideMac()) return // no-op
                    controller.setOutputBtOverrideMac(mac)
                }

                override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
            }
        // Refresh on tap so the list is current. Cheap (synchronous
        // call into AudioManager).
        spinner.setOnTouchListener { vw, ev ->
            if (ev.action == android.view.MotionEvent.ACTION_DOWN) {
                rebuildBtOverrideAdapter(spinner)
            }
            vw.performClick()
            false
        }
    }

    private data class BtOverrideItem(
        val mac: String?,
        val label: String,
    )

    private var btOverrideItems: List<BtOverrideItem> = emptyList()

    private fun rebuildBtOverrideAdapter(spinner: Spinner) {
        val devices = controller.availableBtOutputs()
        val items = mutableListOf<BtOverrideItem>()
        items += BtOverrideItem(mac = null, label = "Auto — follow PTT input device")
        for (d in devices) {
            val typeTag =
                when (d.type) {
                    android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "HFP"
                    android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "A2DP"
                    else -> "BT"
                }
            items += BtOverrideItem(mac = d.address, label = "${d.displayName} · $typeTag")
        }
        btOverrideItems = items
        spinner.adapter =
            ArrayAdapter(pluginContext, R.layout.xv_spinner_item, items.map { it.label })
        val current = controller.outputBtOverrideMac()
        val pos = items.indexOfFirst { it.mac == current }.coerceAtLeast(0)
        spinner.setSelection(pos)
    }

    // Builds an ArrayAdapter shared by the primary + external-button
    // pickers. Row 0 is the "no external button" sentinel; subsequent
    // rows correspond 1:1 with `devices` (so callers convert
    // spinner-pos to a device via pos-1). Unavailable rows carry a
    // trailing " · unavailable" suffix in the label AND are painted
    // dim; `isEnabled(pos)` returns false for them so a tap in the
    // dropdown popup does nothing.
    //
    // Design note (why an ArrayAdapter subclass over a "(unavailable)"
    // suffix alone): text-suffix-only picks lose in accessibility (a
    // screen reader has no way to know the row is disabled) AND in
    // touch response (an operator can still tap the row and trigger
    // a connect to a device that we already know is unreachable).
    // The custom adapter is ~20 lines of code but gives us both the
    // color + tap-guard properties the leading call-picker UIs use.
    private fun buildAinaPickerAdapter(devices: List<AinaDeviceInfo>): ArrayAdapter<String> {
        // Row 0 sentinel is always tappable/enabled.
        val entries =
            mutableListOf<PickerRow>().apply {
                add(PickerRow(label = AINA_NONE_LABEL, available = true))
                for (d in devices) {
                    val suffix = if (d.available) "" else "  ·  unavailable"
                    add(PickerRow(label = d.displayLabel() + suffix, available = d.available))
                }
            }
        return object : ArrayAdapter<String>(
            pluginContext,
            R.layout.xv_spinner_item,
            entries.map { it.label },
        ) {
            override fun isEnabled(position: Int): Boolean =
                entries.getOrNull(position)?.available ?: true

            override fun getDropDownView(
                position: Int,
                convertView: View?,
                parent: android.view.ViewGroup,
            ): View {
                val view = super.getDropDownView(position, convertView, parent)
                val row = entries.getOrNull(position)
                if (view is TextView) {
                    if (row?.available == false) {
                        // Half-alpha to visually indicate "still shown,
                        // but not tappable." Follows the AOSP
                        // material picker convention for disabled
                        // items.
                        view.alpha = 0.4f
                    } else {
                        view.alpha = 1.0f
                    }
                }
                return view
            }
        }
    }

    private data class PickerRow(
        val label: String,
        val available: Boolean,
    )

    // Replaces the old Connect / Disconnect buttons. Lists every bonded
    // device whose name matches an AINA pattern, annotated with V1 (SPP)
    // or V2 (BLE). Selecting one connects to it via the Controller's
    // setSelectedAina(); selecting "(none)" disconnects. The selection
    // persists across launches via SharedPreferences (XvMapComponent).
    //
    // Availability rendering: rows for devices that AudioManager doesn't
    // currently list as reachable (bonded but powered off / out of
    // range) are painted dim and marked non-selectable in the dropdown
    // popup — same approach modern call-picker UIs (Meet, WhatsApp
    // Calls, the AOSP call chooser) take. The label carries a trailing
    // " · unavailable" suffix so operators reading a screenshot still
    // see the state without needing the color cue. This is snapshot-in-
    // time only: the availability info is baked into the adapter when
    // the picker opens; a mid-view BT connect won't re-color the row
    // until the operator re-opens Settings (see AinaPickerAvailabilityListener
    // wiring in [showSettings] which triggers a rebuild on ACL edges).
    private fun wireAinaPicker(v: View) {
        val spinner = v.findViewById<Spinner>(R.id.xv_spinner_aina)
        val statusLabel = v.findViewById<TextView>(R.id.xv_label_aina_status)
        val devices = controller.availableAinaDevices()
        spinner.adapter = buildAinaPickerAdapter(devices)
        val selectedMac = controller.selectedAinaMac()
        val selectedIdx =
            if (selectedMac == null) {
                0
            } else {
                (devices.indexOfFirst { it.mac.equals(selectedMac, ignoreCase = true) } + 1)
                    .coerceAtLeast(0)
            }
        spinner.setSelection(selectedIdx)
        statusLabel.text = formatAinaStatus(devices, selectedMac, controller.ainaConnectionUp())

        // Suppress the spurious onItemSelected that Android fires after
        // setAdapter / setSelection. That first fire is Android draining
        // its own queued event, NOT a user pick — but the callback runs
        // with the pos we programmatically landed on, which may or may
        // not match the operator's persisted selection depending on
        // whether the persisted MAC survived into the current devices
        // list. In the case where the persisted MAC ISN'T in devices
        // (list was refreshed while BT was momentarily missing that
        // entry, or a prior selection is now unbonded), setSelection
        // falls back to pos=0 and the spurious fire writes null back
        // to the persisted MAC. That clobbered the operator's primary
        // AINA selection 2026-07-07 and left the speakermic disconnected
        // on the next startup. Ignore the first fire per picker.
        var suppressFirstFire = true
        spinner.onItemSelectedListener =
            object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    p: android.widget.AdapterView<*>?,
                    vw: View?,
                    pos: Int,
                    id: Long,
                ) {
                    if (suppressFirstFire) {
                        suppressFirstFire = false
                        return
                    }
                    val pickedMac =
                        if (pos == 0) {
                            null
                        } else {
                            devices.getOrNull(pos - 1)?.mac
                        }
                    val current = controller.selectedAinaMac()
                    if (pickedMac != current) {
                        controller.setSelectedAina(pickedMac)
                        statusLabel.text = formatAinaStatus(devices, pickedMac, controller.ainaConnectionUp())
                    }
                }

                override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
            }
    }

    // External-button picker. Same shape as wireAinaPicker but routes
    // through setSelectedExternalButton. The Controller's
    // availableExternalButtonDevices already filters out the primary
    // so the operator can't double-pick.
    private fun wireExternalButtonPicker(v: View) {
        val spinner = v.findViewById<Spinner>(R.id.xv_spinner_aina_secondary) ?: return
        val statusLabel = v.findViewById<TextView>(R.id.xv_label_aina_secondary_status)
        val devices = controller.availableExternalButtonDevices()
        spinner.adapter = buildAinaPickerAdapter(devices)
        val selectedMac = controller.selectedExternalButtonMac()
        val selectedIdx =
            if (selectedMac == null) {
                0
            } else {
                (devices.indexOfFirst { it.mac.equals(selectedMac, ignoreCase = true) } + 1)
                    .coerceAtLeast(0)
            }
        spinner.setSelection(selectedIdx)
        statusLabel?.text =
            formatAinaStatus(devices, selectedMac, controller.externalButtonConnectionUp())
        // See wireAinaPicker for the same first-fire suppression rationale.
        // A spurious pos=0 fire from setAdapter/setSelection would write
        // null back to persisted-external-button and disconnect the reader.
        var suppressFirstFire = true
        spinner.onItemSelectedListener =
            object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    p: android.widget.AdapterView<*>?,
                    vw: View?,
                    pos: Int,
                    id: Long,
                ) {
                    if (suppressFirstFire) {
                        suppressFirstFire = false
                        return
                    }
                    val pickedMac =
                        if (pos == 0) {
                            null
                        } else {
                            devices.getOrNull(pos - 1)?.mac
                        }
                    val current = controller.selectedExternalButtonMac()
                    if (pickedMac != current) {
                        controller.setSelectedExternalButton(pickedMac)
                        statusLabel?.text =
                            formatAinaStatus(devices, pickedMac, controller.externalButtonConnectionUp())
                    }
                }

                override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
            }
    }

    // BLE PTT discovery button. Scans for HM-10 / vendor-service PTT
    // buttons (Pryme BT-PTT-Z, PTT-Z01, similar HM-10-based hardware
    // that won't pair via the phone's system Bluetooth settings),
    // shows results in a live-updating dialog, then asks the operator
    // whether to assign the picked device to the primary or external-
    // button PTT slot. Delegates the actual connect to the Controller.
    private fun wireBlePttScanButton(v: View) {
        val btn = v.findViewById<Button>(R.id.xv_btn_scan_ble_ptt) ?: return
        btn.setOnClickListener { showBlePttScanDialog(v) }
        val removeBtn = v.findViewById<Button>(R.id.xv_btn_remove_ble_ptt) ?: return
        removeBtn.setOnClickListener { showBlePttRemoveDialog(v) }
    }

    private fun showBlePttRemoveDialog(rootView: View) {
        val devices = controller.knownBlePttDevices()
        if (devices.isEmpty()) {
            android.widget.Toast
                .makeText(
                    pluginContext,
                    "No BLE PTT devices to remove — none added yet.",
                    android.widget.Toast.LENGTH_SHORT,
                ).show()
            return
        }
        val labels = devices.map { "${it.name}  ·  ${it.mac}" }.toTypedArray()
        android.app.AlertDialog
            .Builder(mapView.context)
            .setTitle("Remove which BLE PTT device?")
            .setItems(labels) { _, which ->
                val picked = devices.getOrNull(which) ?: return@setItems
                android.app.AlertDialog
                    .Builder(mapView.context)
                    .setTitle("Remove ${picked.name}?")
                    .setMessage(
                        "This will forget ${picked.mac} and unassign it from any PTT slot. " +
                            "You can re-add it later with Scan for BLE PTT device.",
                    ).setPositiveButton("Remove") { _, _ ->
                        val err = controller.removeBlePttDevice(picked.mac)
                        android.widget.Toast
                            .makeText(
                                pluginContext,
                                err ?: "Removed ${picked.name}",
                                if (err == null) android.widget.Toast.LENGTH_SHORT else android.widget.Toast.LENGTH_LONG,
                            ).show()
                        // Rebuild the primary + external-button pickers
                        // so the removed device disappears immediately
                        // without waiting for a settings re-open.
                        wireAinaPicker(rootView)
                        wireExternalButtonPicker(rootView)
                    }.setNegativeButton("Cancel", null)
                    .show()
            }.setNegativeButton("Cancel", null)
            .show()
    }

    private fun showBlePttScanDialog(rootView: View) {
        val found =
            mutableListOf<com.atakmap.android.xv.aina.BlePttScanResult>()
        val labels = mutableListOf<String>()
        // simple_list_item_1 renders as a plain TextView per row — safe
        // in an AlertDialog's list slot, unlike xv_spinner_item which is
        // sized for a Spinner popup and may render as a zero-height row
        // in a dialog. Field-observed 2026-07-07: scan found PTT-Z01 in
        // the log but the dialog's rows never appeared.
        val adapter =
            ArrayAdapter(mapView.context, android.R.layout.simple_list_item_1, labels)

        var scanner: com.atakmap.android.xv.aina.BlePttScanner? = null

        // NOTE: AlertDialog.setMessage AND setAdapter are mutually
        // exclusive — providing setAdapter replaces the message slot
        // with the list. Encoding the "press-the-button" hint into the
        // title so the operator still sees it while the list renders.
        val dialog =
            android.app.AlertDialog
                .Builder(mapView.context)
                .setTitle("Scanning… press-and-hold the button now")
                .setAdapter(adapter) { _, which ->
                    val picked = found.getOrNull(which)
                    scanner?.stop("device picked")
                    if (picked != null) addPickedBlePttDevice(rootView, picked)
                }.setNegativeButton("Cancel") { d, _ ->
                    scanner?.stop("operator cancelled")
                    d.dismiss()
                }.create()
        dialog.setOnDismissListener {
            scanner?.stop("dialog dismissed")
        }
        dialog.show()

        scanner =
            com.atakmap.android.xv.aina.BlePttScanner(
                context = pluginContext,
                onDeviceFound = { result ->
                    // De-dupe by MAC (scanner also de-dupes but the
                    // adapter would otherwise show duplicates on rapid
                    // rescans).
                    if (found.any { it.mac.equals(result.mac, ignoreCase = true) }) return@BlePttScanner
                    found.add(result)
                    labels.add(result.displayLabel())
                    adapter.notifyDataSetChanged()
                    // Update the title once at least one match lands so
                    // the operator sees "n found" instead of the
                    // scanning hint that could imply nothing arrived.
                    if (dialog.isShowing) {
                        dialog.setTitle("Found ${found.size} — pick one, or wait")
                    }
                },
                onScanEnded = { reason ->
                    if (dialog.isShowing) {
                        dialog.setTitle(
                            if (found.isEmpty()) "No BLE PTT buttons found" else "Scan complete — pick one (${found.size})",
                        )
                    }
                },
            )
        scanner.start(timeoutMs = 10_000L)
    }

    private fun addPickedBlePttDevice(
        rootView: View,
        picked: com.atakmap.android.xv.aina.BlePttScanResult,
    ) {
        val displayName = picked.name?.takeIf { it.isNotBlank() } ?: picked.mac
        val err = controller.addBlePttDevice(picked.mac, picked.name)
        val msg =
            err ?: "Added $displayName — now pick it from the Primary PTT or External button dropdown."
        android.widget.Toast
            .makeText(
                pluginContext,
                msg,
                if (err == null) android.widget.Toast.LENGTH_LONG else android.widget.Toast.LENGTH_LONG,
            ).show()
        if (err == null) {
            // Rebuild both pickers so the new device appears in the
            // dropdowns immediately without a settings re-open.
            wireAinaPicker(rootView)
            wireExternalButtonPicker(rootView)
        }
    }

    private fun wireAutoConnectBtSwitch(v: View) {
        val sw = v.findViewById<android.widget.Switch>(R.id.xv_switch_auto_connect_bt) ?: return
        sw.isChecked = controller.autoConnectBtEnabled()
        sw.setOnCheckedChangeListener { _, isChecked ->
            controller.setAutoConnectBtEnabled(isChecked)
        }
    }

    // Samsung ruggedized-device Active Key toggle. The whole row is
    // hidden entirely on any device that doesn't have the key —
    // per operator direction 2026-07-10 we do NOT show a greyed-out
    // row on non-Samsung hardware; the option simply does not appear.
    // On Samsung Tab Active5 / XCover6 Pro / etc. the row is shown,
    // reflects the persisted toggle, and updates the service on flip.
    private fun wireSamsungActiveKeySwitch(v: View) {
        val row = v.findViewById<View>(R.id.xv_row_samsung_active_key) ?: return
        val help = v.findViewById<View>(R.id.xv_label_samsung_active_key_help)
        val sw = v.findViewById<android.widget.Switch>(R.id.xv_switch_samsung_active_key) ?: return
        val supported = controller.samsungActiveKeySupported()
        if (!supported) {
            row.visibility = View.GONE
            help?.visibility = View.GONE
            return
        }
        row.visibility = View.VISIBLE
        help?.visibility = View.VISIBLE
        // Detach any previous listener before pushing state so restoring
        // the persisted value doesn't spuriously call setSamsungActiveKeyEnabled.
        sw.setOnCheckedChangeListener(null)
        sw.isChecked = controller.samsungActiveKeyEnabled()
        sw.setOnCheckedChangeListener { _, isChecked ->
            controller.setSamsungActiveKeyEnabled(isChecked)
        }
    }

    // Samsung Active Key background PTT toggle (Accessibility service).
    // Only visible on Samsung ruggedized hardware — same isSupported()
    // gate as the row above.  The toggle reflects whether the XV
    // accessibility service is currently enabled (via AccessibilityManager)
    // and is read-only in the traditional sense: tapping it opens the
    // system Accessibility settings page rather than toggling directly,
    // because Android does not allow apps to enable / disable accessibility
    // services programmatically.  The switch read-state updates on each
    // inflateSettings call so it stays in sync after the operator returns
    // from the system settings page.
    private fun wireSamsungActiveKeyBgSwitch(v: View) {
        val row = v.findViewById<View>(R.id.xv_row_samsung_active_key_bg) ?: return
        val help = v.findViewById<View>(R.id.xv_label_samsung_active_key_bg_help)
        val sw = v.findViewById<android.widget.Switch>(R.id.xv_switch_samsung_active_key_bg) ?: return
        val supported = controller.samsungActiveKeySupported()
        if (!supported) {
            row.visibility = View.GONE
            help?.visibility = View.GONE
            return
        }
        row.visibility = View.VISIBLE
        help?.visibility = View.VISIBLE
        // Reflect current OS-level enabled state — no listener that
        // would try to set it programmatically (Android disallows that).
        sw.setOnCheckedChangeListener(null)
        sw.isChecked = controller.samsungActiveKeyBgServiceEnabled()
        // Any tap on the row or the switch opens the system Accessibility
        // settings so the operator can enable / disable the service there.
        // Reset the switch to the current OS-reported value before launching
        // Settings — a Switch tap briefly toggles the visual state before
        // the click listener fires, so we must correct it immediately to
        // avoid showing a misleading checked state while Settings opens.
        row.setOnClickListener { controller.openAccessibilitySettings() }
        sw.setOnClickListener {
            sw.isChecked = controller.samsungActiveKeyBgServiceEnabled()
            controller.openAccessibilitySettings()
        }
    }

    // Sonim ruggedized-device dedicated PTT side button toggle. The
    // whole row is hidden entirely on any device that isn't a
    // supported Sonim model — per operator direction, non-Sonim
    // hardware never sees the option. On Sonim XP10 / XP9900 / XP-
    // family peers the row is shown, reflects the persisted toggle,
    // and updates the service on flip.
    private fun wireSonimPttButtonSwitch(v: View) {
        val row = v.findViewById<View>(R.id.xv_row_sonim_ptt_button) ?: return
        val help = v.findViewById<View>(R.id.xv_label_sonim_ptt_button_help)
        val sw = v.findViewById<android.widget.Switch>(R.id.xv_switch_sonim_ptt_button) ?: return
        val supported = controller.sonimHardwareButtonsSupported()
        if (!supported) {
            row.visibility = View.GONE
            help?.visibility = View.GONE
            return
        }
        row.visibility = View.VISIBLE
        help?.visibility = View.VISIBLE
        // Detach any previous listener before pushing state so restoring
        // the persisted value doesn't spuriously call setSonimPttButtonEnabled.
        sw.setOnCheckedChangeListener(null)
        sw.isChecked = controller.sonimPttButtonEnabled()
        sw.setOnCheckedChangeListener { _, isChecked ->
            controller.setSonimPttButtonEnabled(isChecked)
        }
    }

    // Sonim ruggedized-device dedicated Emergency / SOS button toggle.
    // Same gate as the PTT-button switch above. Currently a plain PTT
    // source with a distinct log tag; a follow-up may upgrade it to
    // fire an emergency CoT event.
    private fun wireSonimEmergencyButtonSwitch(v: View) {
        val row = v.findViewById<View>(R.id.xv_row_sonim_emergency_button) ?: return
        val help = v.findViewById<View>(R.id.xv_label_sonim_emergency_button_help)
        val sw = v.findViewById<android.widget.Switch>(R.id.xv_switch_sonim_emergency_button) ?: return
        val supported = controller.sonimHardwareButtonsSupported()
        if (!supported) {
            row.visibility = View.GONE
            help?.visibility = View.GONE
            return
        }
        row.visibility = View.VISIBLE
        help?.visibility = View.VISIBLE
        sw.setOnCheckedChangeListener(null)
        sw.isChecked = controller.sonimEmergencyButtonEnabled()
        sw.setOnCheckedChangeListener { _, isChecked ->
            controller.setSonimEmergencyButtonEnabled(isChecked)
        }
    }

    private fun formatAinaStatus(
        devices: List<AinaDeviceInfo>,
        mac: String?,
        connected: Boolean,
    ): String {
        if (mac == null) return "no speakermic selected"
        val dev = devices.firstOrNull { it.mac.equals(mac, ignoreCase = true) }
        val name = dev?.name ?: mac
        val proto = dev?.buttonProtocol?.display
        val state = if (connected) "connected" else "disconnected"
        return if (proto.isNullOrBlank()) "$name — $state" else "$name ($proto) — $state"
    }

    // wireChannelSelectors removed. VS1/VS2 channel selection lives on
    // the main view's PTT cards (tap the channel name → picker overlay),
    // not under Settings. Operator feedback (2026-05-11): "channels is
    // still showing up in the menus and that is really more of the
    // server config." The Server settings tab now only carries the TAK
    // server selector + Mumble Connect/Disconnect.
    private fun wireChannelSelectors(
        @Suppress("UNUSED_PARAMETER") v: View,
    ) {
        // Intentionally empty — keep the wire-up point so showSettings()
        // callers don't need restructuring during this transition.
    }

    private fun inflateMainAndShow() {
        val v = inflateMain()
        showDropDown(v, FIVE_TWELFTHS_WIDTH, FULL_HEIGHT, FULL_WIDTH, HALF_HEIGHT, this)
        startRefreshLoop()
    }

    // "REACT  ● dhirenparbhoo" while a peer is talking; bare "REACT"
    // otherwise. Multiple concurrent talkers are comma-joined.
    private fun buildChannelRowText(
        channel: String?,
        speakers: List<String>,
        connected: Boolean,
        idleText: String,
    ): String {
        if (channel == null) return if (connected) idleText else ""
        if (speakers.isEmpty()) return channel
        return "$channel  $RX_DOT ${speakers.joinToString(", ")}"
    }

    companion object {
        // Slow tick — push-driven callbacks (channel join, TX/RX
        // edges, suppress changes) handle the operationally-critical
        // updates synchronously. Polling at 500ms was wasteful on
        // Surface Duo idle (constant wakeups for nothing changing);
        // 2 s is fine for slow-moving fields like reconnect countdown.
        private const val REFRESH_MS = 2_000L
        private const val SECONDARY_NONE_LABEL = "(none)"
        private const val AINA_NONE_LABEL = "Screen-only PTT (no external button)"

        // Filled green dot — visually pops against the dim background to
        // show "audio coming in on this channel". UTF-8 only (no drawable
        // changes needed).
        private const val RX_DOT = "●"
    }
}
