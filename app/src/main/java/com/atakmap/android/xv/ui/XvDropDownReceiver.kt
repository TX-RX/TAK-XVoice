package com.atakmap.android.xv.ui

import android.content.Context
import android.content.Intent
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
    private fun wireSettingsTabs(v: View) {
        val tabTxRx = v.findViewById<Button>(R.id.xv_tab_txrx)
        val tabCalls = v.findViewById<Button>(R.id.xv_tab_calls)
        val tabPrefs = v.findViewById<Button>(R.id.xv_tab_prefs)
        val sectionTxRx = v.findViewById<View>(R.id.xv_section_txrx)
        val sectionCalls = v.findViewById<View>(R.id.xv_section_calls)
        val sectionPrefs = v.findViewById<View>(R.id.xv_section_prefs)
        val tabs = listOf(tabTxRx, tabCalls, tabPrefs)
        val sections = listOf(sectionTxRx, sectionCalls, sectionPrefs)

        fun select(idx: Int) {
            sections.forEachIndexed { i, s -> s.visibility = if (i == idx) View.VISIBLE else View.GONE }
            tabs.forEachIndexed { i, b -> b.alpha = if (i == idx) 1.0f else 0.55f }
        }
        tabTxRx.setOnClickListener { select(0) }
        tabCalls.setOnClickListener { select(1) }
        tabPrefs.setOnClickListener { select(2) }
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

    // Replaces the old Connect / Disconnect buttons. Lists every bonded
    // device whose name matches an AINA pattern, annotated with V1 (SPP)
    // or V2 (BLE). Selecting one connects to it via the Controller's
    // setSelectedAina(); selecting "(none)" disconnects. The selection
    // persists across launches via SharedPreferences (XvMapComponent).
    private fun wireAinaPicker(v: View) {
        val spinner = v.findViewById<Spinner>(R.id.xv_spinner_aina)
        val statusLabel = v.findViewById<TextView>(R.id.xv_label_aina_status)
        val devices = controller.availableAinaDevices()
        val labels = mutableListOf(AINA_NONE_LABEL)
        labels.addAll(devices.map { it.displayLabel() })
        spinner.adapter =
            ArrayAdapter(pluginContext, R.layout.xv_spinner_item, labels)
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

        spinner.onItemSelectedListener =
            object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    p: android.widget.AdapterView<*>?,
                    vw: View?,
                    pos: Int,
                    id: Long,
                ) {
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
