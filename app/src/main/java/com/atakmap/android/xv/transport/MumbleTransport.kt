package com.atakmap.android.xv.transport

import android.util.Log
import com.atakmap.android.xv.audio.AudioPlayback
import com.atakmap.android.xv.audio.OpusDecoder
import com.atakmap.android.xv.transport.mumble.FatalMumbleException
import com.atakmap.android.xv.transport.mumble.MumbleSession
import com.atakmap.android.xv.transport.mumble.SelfKickedException
import com.atakmap.android.xv.transport.mumble.UsernameInUseException
import mumble.MumbleProto

// Mumble transport — VX-style dual-session model.
//
// Each XV "channel slot" (VS1 = primary, VS2 = secondary) opens its own
// full Mumble session. Both sessions present the same TAK enrollment
// cert and the same callsign; only the UUID suffix of the username
// differs (so the OTS authenticator sees them as distinct users — VX's
// "two GUIDs after the callsign" trick). Result: full TX **and** RX on
// both channels at once. Voice from either session feeds the same
// AudioPlayback so the operator hears both nets mixed.
//
// Single-session deployments (no secondaryChannelName) still work — the
// secondary slot is simply absent.
//
// Direct-call signaling (`[TAK MxVx : REQUEST_CALL ]…`) is processed on
// the primary session only; the secondary identity has no call presence.

// One member of a Mumble channel as seen by [MumbleTransport.channelMembers].
// Pure Mumble-roster fact — XV presence / device-uid / jump-channel data is
// stitched in on the UI side via XvPresenceRegistry.
//
// sessionId is the Mumble per-session id (unique per server connection,
// not stable across reconnects). callsign has any `---<uuid>` suffix
// stripped. channelId is always the slot's currently-joined channel id
// — passed back so the caller can correlate without re-querying.
// isTalking is true if the peer has delivered a voice frame within
// the TALKING_TTL_MS window (lights the "now talking" indicator).
data class MumbleMember(
    val sessionId: Int,
    val callsign: String,
    val channelId: Int,
    val isTalking: Boolean,
)

class MumbleTransport(
    override val config: TransportConfig.Mumble,
    private val playback: AudioPlayback? = null,
    private val opusDecoderFactory: (() -> OpusDecoder)? = null,
    // When non-null this takes precedence over the [playback]/[opusDecoderFactory]
    // path: incoming Opus frames are forwarded raw (no decode in plugin)
    // for downstream playback in another process. Wired up in the
    // service-split architecture so the voice plant in our APK's UID
    // owns AudioTrack + decoding instead of the plugin process.
    private val onIncomingOpus: ((slot: Int, opus: ByteArray, speakerSession: Long, sequence: Long) -> Unit)? = null,
    // Distinct deterministic per-(device, slot) suffixes from
    // MumbleInstallId. Same device produces the same suffixes forever,
    // so reconnects reuse the same Mumble identity and don't pile up
    // "ghost users" server-side that the OTS authenticator has to expire.
    private val primarySlotSuffix: String = "VS1",
    private val secondarySlotSuffix: String = "VS2",
    // Read on every signal so a runtime toggle takes effect on the next
    // call without a reconnect. Only the primary session bridge consults
    // this — secondary identity ignores call signaling.
    private val autoAcceptPrivateCalls: () -> Boolean = { false },
    // Fires whenever EITHER slot's joined channel changes (server-confirmed
    // via UserState). The CoT presence publisher uses this to keep its
    // `<__xv><channels>` list current.
    // `byAdmin = true` when the move wasn't initiated by our own join
    // call within the last SELF_JOIN_GRACE_MS. UI surfaces a toast
    // ("you were moved by an admin") to disambiguate from the
    // operator's own action. Audit L1.
    private val onChannelChanged: (slot: Int, channelId: Int, channelName: String?, byAdmin: Boolean) -> Unit = { _, _, _, _ -> },
    // Fires when the server flips the suppress flag on our own
    // UserState for either slot. OTS direction enforcement triggers
    // this on channel join when the matching group's direction is
    // OUT (listen-only). The plugin uses this to push the per-slot
    // gate to the service-side TxController so PTT bonks instead of
    // TPT-ing.
    private val onSelfSuppressedChanged: (slot: Int, suppressed: Boolean) -> Unit = { _, _ -> },
    // Phase E: fired on the primary slot when a peer's REQUEST_CALL
    // TextMessage arrives AND autoAcceptPrivateCalls is false. The
    // plugin invokes XvCallBridge.notifyIncomingCall to drive the
    // system Telecom incoming-call ring UI. Caller-callsign is best-
    // effort: looked up from the Mumble user roster by session id;
    // empty string when not yet known.
    private val onIncomingCallRequest: (callerCallsign: String, tempChannelId: Int, callerSession: Int) -> Unit = { _, _, _ -> },
    // Phase E: peer requested we play a call-progress tone. Args is
    // the raw payload string after the action name (VX uses tokens
    // like "ringback" / "busy" / "callwait"; we map known tokens to
    // StatusToneKind, log unknowns at WARN). Plugin plays the tone
    // via its existing StatusTones path.
    private val onPlayToneRequest: (args: String) -> Unit = { _ -> },
    // Phase E: transport detected the peer ended the call (peer left
    // the temp channel, or peer's session disconnected, or CANCEL_CALL
    // arrived). Plugin uses this to tear down the Telecom call so
    // the active-call activity dismisses + the auto-engaged latched
    // TX + audio mode unwind. Without this hook the transport state
    // clears but the operator's UI / mic stay engaged.
    private val onPrivateCallTransportTeardown: () -> Unit = {},
    // Phase E: caller-side acceptance detection. Fires when the call's
    // peer (callee) joins the temp channel — that's the implicit
    // accept signal in the whisper architecture (callee never sends
    // an explicit ACCEPT message). Plugin uses this to engage the
    // mic JUST IN TIME — without it, the caller's mic would be hot
    // from the moment they hit "Call" instead of from when the
    // callee picks up. Phone behavior expects ringing-then-mic-on,
    // not mic-on-from-tap. No-op for the callee side (callee already
    // engaged on accept).
    private val onPeerAcceptedCall: () -> Unit = {},
) : VoiceTransport {
    // Per-slot state. Primary is always present; secondary is only
    // populated when config.secondaryChannelName is set AND it's not the
    // same name as primary (collision guard — VS1 ≠ VS2).
    private inner class Slot(
        val idx: Int, // 0 = VS1 (primary), 1 = VS2 (secondary)
        // Deterministic suffix (e.g. "a3f8b2VS1"). Held as a val
        // because suffixes are now derived from device identity —
        // there's nothing to rotate to on UsernameInUse. The retry
        // path backs off with the same suffix and waits for Murmur's
        // ghost timeout to clear.
        val slotSuffix: String,
        val initialChannel: String,
    ) {
        @Volatile var session: MumbleSession? = null

        @Volatile var connected: Boolean = false

        // Secondary-only: number of consecutive UsernameInUse retries
        // we've attempted with rotated UUIDs. Reset to 0 on a clean
        // connect; bumped on each failed startSlot retry. After
        // SECONDARY_MAX_RETRIES the slot is marked deadWithError so
        // the UI can render an error chip without the wrapper-level
        // reconnect kicking in (which would tear down the primary
        // too).
        @Volatile var secondaryRetryCount: Int = 0

        // Secondary-only fatal flag. Set when the retry path has
        // exhausted attempts. Surfaced to the UI via secondaryDead().
        // Never set on primary — primary failures propagate to the
        // wrapper.
        @Volatile var deadWithError: Boolean = false

        // Channel ID this slot's session is currently in. -1 until first
        // UserState arrives confirming our channel.
        @Volatile var joinedChannelId: Int = -1

        // Server-side mute (suppress flag on our own UserState). OTS's
        // DirectionEnforcementCallback sets this when our group's
        // direction is OUT (listen-only). Mumble admins can also set it
        // manually. When true, voice frames we send are dropped server-
        // side, so XV bonks instead of TPT-ing.
        @Volatile var suppressed: Boolean = false

        // Wall-clock timestamp of the most recent self-initiated join
        // attempt for this slot. Compared against the incoming
        // channel-change event in the onUser handler so we can tell
        // operator-initiated moves from admin-initiated moves.
        // 0 = no recent join (treat as admin). Audit L1.
        @Volatile var lastSelfJoinAttemptMs: Long = 0L

        // Per-slot decoder map. Speaker session ids are independent across
        // Mumble sessions, so two separate maps prevent any chance of a
        // numeric collision corrupting decoder state.
        val decoders = java.util.concurrent.ConcurrentHashMap<Long, OpusDecoder>()
        val talkingPeers = java.util.concurrent.ConcurrentHashMap<Long, Long>()

        // Per-session → channel id cache. Maintained from UserState
        // dispatches in SessionBridge. Used by peersInPrimaryChannel()
        // to enumerate everyone sharing our channel without depending
        // on CoT presence (so VX peers — which don't publish <__xv>
        // — are still discoverable for outgoing direct calls).
        val channelBySession = java.util.concurrent.ConcurrentHashMap<Int, Int>()

        // Private-call state. Either slot can receive a call now, so
        // both slots track their own per-call state.
        //
        // Phase E uses Mumble whisper (VoiceTarget) for call audio,
        // matching VX's wire protocol. Caller stays in their tactical
        // channel and whispers to the callee's session id; only the
        // callee joins the temp channel (visual "I picked up" handshake).
        // Both sides register a VoiceTarget so audio bypasses channel
        // routing — tactical channel members never hear the call.
        //
        // privateCallChannelId is the temp channel id (set on both
        // sides). joinedChannelId == privateCallChannelId only on the
        // callee side. directCallPeerSession is the peer's Mumble
        // session id we whisper to. directCallIsCaller distinguishes
        // the two sides for end-of-call cleanup (callee leaves temp +
        // returns to preCallChannelId; caller stays put).
        @Volatile var privateCallChannelId: Int = -1

        @Volatile var preCallChannelId: Int = -1

        @Volatile var directCallPeerSession: Int = -1

        @Volatile var directCallIsCaller: Boolean = false

        // True while another slot is the active call holder. RX from
        // this slot is suppressed (no audio playback / no decode) so
        // the operator only hears the call. TX should also be denied
        // — handled at the PTT path via canSpeakOnSlot semantics.
        @Volatile var rxMutedForCall: Boolean = false

        fun isPrimary() = idx == 0

        fun joinedChannelName(): String? {
            val s = session ?: return null
            val id = joinedChannelId
            if (id < 0) return null
            // Surface Root (id=0) as "Lobby" unconditionally. We can't
            // depend on the channel directory having an entry for id=0
            // ready by the time this is queried — Murmur sometimes
            // delivers Root with a blank name, sometimes the cache
            // hasn't populated yet on first ServerSync. Returning null
            // there made the UI display "(tap to pick)" even though
            // we'd already landed at Root and were ready to talk on it.
            if (id == 0) return LOBBY_DISPLAY_NAME
            val raw = s.channelNameById(id) ?: return null
            return raw
        }
    }

    private val primary = Slot(idx = 0, slotSuffix = primarySlotSuffix, initialChannel = config.channelName)

    @Volatile
    private var secondary: Slot? = null

    // Main-looper handler for scheduling the secondary slot's
    // UsernameInUse-rotation retries (FIX 2). Lazy-init so unit
    // tests that don't touch the secondary slot don't need a
    // looper. Null on JVM tests; secondary retries no-op there.
    private val handler: android.os.Handler? =
        try {
            android.os.Looper
                .getMainLooper()
                ?.let { android.os.Handler(it) }
        } catch (_: Throwable) {
            null
        }

    // Cache of the resolved secondary channel name. Mutated by
    // setSecondaryChannel at runtime; persisted by the caller in
    // XvMapComponent's lastSecondaryChannel.
    @Volatile
    private var activeSecondaryName: String? = config.secondaryChannelName?.takeIf { it.isNotBlank() }

    // Per-session cache of channel IDs we've been observed as
    // suppressed on. PermissionQuery only sees ACL bits, so a
    // direction=OUT channel where the operator has ACL Speak=true
    // still reports as PARTICIPATE pre-join. Once the operator
    // joins and the server sends suppress=true, we add the channel
    // here so the picker shows it as LISTEN even after they move
    // away. Cleared when suppress flips false (admin removed
    // direction=OUT). Session-scoped — resets on reconnect, which
    // is fine because the join cycle re-populates it.
    private val suppressedChannelIds: MutableSet<Int> =
        java.util.concurrent.ConcurrentHashMap
            .newKeySet()

    private var transportListener: TransportListener? = null

    /** Returns true once the PRIMARY session has reached ServerSync.
     *  Secondary status is surfaced separately via [secondaryConnected]. */
    override val isConnected: Boolean
        get() = primary.connected

    /** Whether VS2 has a live, joined Mumble session. The UI uses this
     *  to dim the VS2 PTT button until the secondary identity is
     *  actually keyable. */
    fun secondaryConnected(): Boolean {
        val s = secondary ?: return false
        return s.connected && s.joinedChannelId >= 0
    }

    /** True when the secondary slot's UsernameInUse-rotation retry path
     *  has exhausted its attempts. UI uses this to render an error
     *  chip on VS2 (e.g. "ghost session"). Distinct from "no
     *  secondary configured" — that's [secondaryConnected] returning
     *  false with [activeSecondaryName] null. */
    fun secondaryDead(): Boolean = secondary?.deadWithError == true

    fun isInChannel(): Boolean = primary.joinedChannelId >= 0

    fun joinedChannelId(): Int = primary.joinedChannelId

    fun joinedChannelName(): String? = primary.joinedChannelName()

    /**
     * Our authenticated session ID on the primary slot, or null if
     * Mumble hasn't completed ServerSync yet. Used by the CoT presence
     * publisher to advertise `<__xv mumbleSession="N">` so XV peers
     * can address direct-call TextMessages straight to us.
     */
    fun primarySessionId(): Int? {
        val s = primary.session?.ourSessionId() ?: return null
        return if (s == 0) null else s
    }

    /**
     * Our Mumble session id for the given slot (VS1=0, VS2=1), or null
     * if the slot isn't connected / pre-ServerSync. Used by the channel-
     * members picker to mark the local-operator row as XV without
     * waiting for the CoT echo to round-trip.
     */
    fun ourSessionIdForSlot(slotIdx: Int): Int? {
        val s = slotFor(slotIdx)?.session?.ourSessionId() ?: return null
        return if (s == 0) null else s
    }

    /**
     * Direct handle to the primary slot's [MumbleSession], or null when
     * not connected. Exposed so the plugin can call session-level
     * helpers (sendPrivateCallSignal, sendChannelState) for VX direct-
     * call signaling without re-implementing wire-format wrappers in
     * the transport. Don't cache the returned reference — it changes
     * across reconnects.
     */
    fun primarySession(): com.atakmap.android.xv.transport.mumble.MumbleSession? = primary.session

    // Phase E: pending one-shot resolutions of "this channel name's id
    // just landed on the wire." The direct-call orchestrator registers
    // here right before sending a ChannelState create so it can learn
    // the server-assigned id once the server's reply arrives. Map from
    // lower-cased name → callback. CopyOnWriteArrayList / lock-free
    // would be overkill — channel-state arrival is single-threaded
    // (the read loop) and we synchronize on the map.
    private val pendingChannelByName: MutableMap<String, (Int) -> Unit> = mutableMapOf()

    /**
     * Register a one-shot callback that fires when a ChannelState with
     * the given [name] arrives on the primary slot's wire. If [name]
     * is already in the cache (e.g. the channel pre-existed), fires
     * synchronously. Otherwise the listener is held until the next
     * ChannelState matches; if it never matches the operator's call
     * orchestrator must enforce its own timeout and call
     * [cancelChannelByName] to clean up.
     *
     * Used by the direct-call flow: send a sendChannelState create,
     * register here for the resulting id, and join + send REQUEST_CALL
     * once the id is known.
     */
    fun awaitChannelByName(
        name: String,
        onResolved: (channelId: Int) -> Unit,
    ) {
        val key = name.lowercase()
        val existingId = primary.session?.channelIdByName(name)
        if (existingId != null) {
            Log.i(TAG, "awaitChannelByName('$name') resolved synchronously to id=$existingId")
            try {
                onResolved(existingId)
            } catch (t: Throwable) {
                Log.w(TAG, "awaitChannelByName synchronous callback threw", t)
            }
            return
        }
        synchronized(pendingChannelByName) {
            pendingChannelByName[key] = onResolved
        }
        Log.i(TAG, "awaitChannelByName('$name') registered (pending)")
    }

    /** Cancel a previously registered awaitChannelByName when the
     *  caller's timeout elapses without a matching ChannelState. */
    fun cancelChannelByName(name: String) {
        val key = name.lowercase()
        synchronized(pendingChannelByName) {
            pendingChannelByName.remove(key)
        }
    }

    private fun fireChannelByNameMatch(
        channelId: Int,
        name: String,
    ) {
        val key = name.lowercase()
        val cb =
            synchronized(pendingChannelByName) {
                pendingChannelByName.remove(key)
            } ?: return
        Log.i(TAG, "channel-by-name match: '$name' -> id=$channelId, firing pending callback")
        try {
            cb(channelId)
        } catch (t: Throwable) {
            Log.w(TAG, "awaitChannelByName callback threw", t)
        }
    }

    /** Whether the slot's current channel allows us to transmit. False
     *  means the server has suppressed our session (OTS direction OUT
     *  / Mumble admin mute) OR this slot is the non-call slot during
     *  an active private call. PTT against either condition is
     *  rejected with a bonk instead of TPT. Slot 0 = primary, 1 =
     *  secondary. */
    fun canSpeakOnSlot(slot: Int): Boolean =
        when (slot) {
            0 -> !primary.suppressed && !primary.rxMutedForCall
            1 -> secondary?.let { !it.suppressed } ?: false
            else -> false
        }

    /** Channel id the secondary session is in, or -1 if no secondary. */
    fun secondaryChannelId(): Int = secondary?.joinedChannelId ?: -1

    /** True if EITHER slot is currently in a Phase E private call.
     *  Keys on the per-slot peer-session being set, NOT on
     *  joinedChannelId — the caller side stays in its tactical
     *  channel during the call (it whispers via VoiceTarget rather
     *  than joining the temp channel) so the joined-channel check
     *  would miss caller-side calls. */
    fun isInPrivateCall(): Boolean {
        if (primary.directCallPeerSession >= 0) return true
        val sec = secondary
        if (sec != null && sec.directCallPeerSession >= 0) return true
        return false
    }

    /** Returns the slot index (0 = VS1, 1 = VS2) currently holding a
     *  private call on [tempChannelId], or -1 if neither matches.
     *  Used by the answer/reject paths to route the join + REJECT_CALL
     *  send through the slot whose identity actually received the
     *  REQUEST_CALL. */
    fun slotForCallChannel(tempChannelId: Int): Int {
        if (primary.privateCallChannelId == tempChannelId) return 0
        val sec = secondary
        if (sec != null && sec.privateCallChannelId == tempChannelId) return 1
        return -1
    }

    /** Snapshot of the currently-active private call, if any. Returns
     *  null when no slot has a tracked call. Used by the plugin's
     *  hangup path to send CANCEL_CALL to the peer (caller side) and
     *  to know which slot's session to send it from. */
    data class ActiveCallContext(
        val slotIdx: Int,
        val peerSession: Int,
        val tempChannelId: Int,
        val isCaller: Boolean,
    )

    fun activeCallContext(): ActiveCallContext? {
        if (primary.directCallPeerSession >= 0) {
            return ActiveCallContext(
                slotIdx = 0,
                peerSession = primary.directCallPeerSession,
                tempChannelId = primary.privateCallChannelId,
                isCaller = primary.directCallIsCaller,
            )
        }
        val sec = secondary
        if (sec != null && sec.directCallPeerSession >= 0) {
            return ActiveCallContext(
                slotIdx = 1,
                peerSession = sec.directCallPeerSession,
                tempChannelId = sec.privateCallChannelId,
                isCaller = sec.directCallIsCaller,
            )
        }
        return null
    }

    /** Direct handle to the [MumbleSession] for the slot specified. Use
     *  this from the call-answer/reject paths to send a TextMessage on
     *  the same identity that received the REQUEST_CALL. */
    fun sessionForSlot(slotIdx: Int): com.atakmap.android.xv.transport.mumble.MumbleSession? = slotFor(slotIdx)?.session

    /** Move the SPECIFIED slot into a different channel. Used by the
     *  answer-call path so the slot whose identity received the call
     *  is the one that joins the temp channel. */
    fun joinChannelForSlot(
        channelId: Int,
        slotIdx: Int,
    ): Boolean {
        val slot = slotFor(slotIdx) ?: return false
        val s = slot.session ?: return false
        slot.lastSelfJoinAttemptMs = System.currentTimeMillis()
        s.joinChannel(channelId)
        return true
    }

    /**
     * Mark the start of a private call on the specified slot. Records
     * the call context AND registers the Mumble VoiceTarget so audio
     * sent with target=DIRECT_CALL_TARGET_ID is whispered to the peer.
     *
     * Caller side ([isCaller]=true): does NOT join the temp channel —
     * the caller stays in their tactical channel and whispers.
     * The temp channel is just a visual handshake the callee joins.
     *
     * Callee side ([isCaller]=false): the join-temp-channel is done
     * separately (XvMapComponent.onIncomingCallAnswered), and this
     * method records the pre-call channel for restoration on hangup.
     *
     * Either way the OTHER slot is disabled for the call duration:
     *   - Call on VS1 (primary) → tear down VS2 (cheap to respin).
     *   - Call on VS2 → RX-mute VS1 (can't tear down primary without
     *     killing the whole transport).
     *
     * Idempotent — calling twice for the same call is safe.
     */
    fun notePrivateCallStarted(
        callSlotIdx: Int,
        peerSession: Int,
        tempChannelId: Int,
        preCallChannelId: Int,
        isCaller: Boolean,
    ) {
        val callSlot =
            slotFor(callSlotIdx) ?: run {
                Log.w(TAG, "notePrivateCallStarted: invalid slot=$callSlotIdx")
                return
            }
        if (callSlot.directCallPeerSession == peerSession) {
            // Idempotent — second invocation from a race (outgoing path
            // notes start, then echo-back of our own join also notes
            // start).
            return
        }
        callSlot.privateCallChannelId = tempChannelId
        callSlot.preCallChannelId = preCallChannelId
        callSlot.directCallPeerSession = peerSession
        callSlot.directCallIsCaller = isCaller
        // OPTION B (both-join-temp) — no whisper VoiceTarget needed.
        // Both caller AND callee are in the temp channel, so normal
        // channel-speak (target=0) reaches the peer. Dropped the
        // whisper machinery from Phase E because it fought Murmur's
        // creator-auto-join behavior and added complexity to no end.
        Log.i(
            TAG,
            "notePrivateCallStarted(slot=$callSlotIdx, peer=$peerSession, temp=$tempChannelId, " +
                "preCall=$preCallChannelId, isCaller=$isCaller)",
        )
        if (callSlotIdx == 0) {
            // Call on VS1 — tear down VS2.
            if (secondary != null && preCallSecondaryName == null) {
                val saved = activeSecondaryName
                preCallSecondaryName = saved
                Log.i(
                    TAG,
                    "notePrivateCallStarted(VS1, temp=$tempChannelId): tearing down VS2 (was '$saved') for call duration",
                )
                tearDownSecondary("private call started on VS1")
                // Keep activeSecondaryName clear while torn down so any
                // intervening retargetSecondary doesn't try to spin VS2
                // back up before the call ends.
                activeSecondaryName = null
            }
        } else {
            // Call on VS2 — RX-mute VS1 instead of tearing down.
            primary.rxMutedForCall = true
            Log.i(
                TAG,
                "notePrivateCallStarted(VS2, temp=$tempChannelId): muting VS1 RX for call duration",
            )
        }
    }

    /** Clear all private-call state and restore whichever "other slot"
     *  was disabled at the start of the call. Also clears the Mumble
     *  VoiceTarget so any spurious post-hangup voice doesn't whisper
     *  to a stale peer.
     *
     *  CALLEE-side: also leaves the temp channel + rejoins the
     *  pre-call channel. CALLER never joined the temp so no leave is
     *  needed there.
     *
     *  Safe if no call is active. */
    fun notePrivateCallEnded() {
        // Capture the call slot's leave-temp target BEFORE clearing
        // state. The slot's session is needed to send the joinChannel
        // request, and we lose direct access to the call slot once
        // the loop below resets fields.
        //
        // tempCh is captured too — Murmur auto-joins the creator
        // (caller) to the temp channel, so we need to detect "am I
        // still in the temp?" by comparing slot.joinedChannelId to
        // the temp id at end-of-call, regardless of caller/callee.
        data class CallSlotInfo(
            val slot: Slot,
            val returnCh: Int,
            val tempCh: Int,
        )
        val callSlotInfo: CallSlotInfo? =
            run {
                if (primary.directCallPeerSession >= 0) {
                    return@run CallSlotInfo(primary, primary.preCallChannelId, primary.privateCallChannelId)
                }
                val sec = secondary
                if (sec != null && sec.directCallPeerSession >= 0) {
                    return@run CallSlotInfo(sec, sec.preCallChannelId, sec.privateCallChannelId)
                }
                null
            }
        // No VoiceTarget to clear — Option B uses normal channel
        // speak inside the temp channel; whisper machinery removed.
        primary.privateCallChannelId = -1
        primary.preCallChannelId = -1
        primary.directCallPeerSession = -1
        primary.directCallIsCaller = false
        secondary?.let {
            it.privateCallChannelId = -1
            it.preCallChannelId = -1
            it.directCallPeerSession = -1
            it.directCallIsCaller = false
        }
        // Unmute VS1 if it was muted (call was on VS2).
        if (primary.rxMutedForCall) {
            Log.i(TAG, "notePrivateCallEnded: unmuting VS1 RX")
            primary.rxMutedForCall = false
        }
        // Leave the temp channel (if we're currently in it) and rejoin
        // the channel we were in before the call. This applies to both
        // sides because Murmur auto-joins the creator (caller) to the
        // new temp channel — verified on OTS 2026-05-11. Without this
        // the caller stays stuck in TAK PRIVATE after a hang-up or a
        // peer-side decline.
        if (callSlotInfo != null) {
            val (slot, returnCh, tempCh) = callSlotInfo
            val stillInTemp = tempCh >= 0 && slot.joinedChannelId == tempCh
            if (stillInTemp) {
                if (returnCh >= 0) {
                    Log.i(TAG, "notePrivateCallEnded: leaving temp $tempCh → ch=$returnCh")
                    slot.lastSelfJoinAttemptMs = System.currentTimeMillis()
                    slot.session?.joinChannel(returnCh)
                } else {
                    val fallback = config.channelName
                    Log.i(TAG, "notePrivateCallEnded: leaving temp $tempCh → fallback='$fallback'")
                    if (slot.idx == 0) {
                        joinChannel(fallback)
                    } else {
                        // Secondary slot fell into the call somehow —
                        // rejoin its configured channel target.
                        val secName = activeSecondaryName ?: fallback
                        slot.session?.let { s ->
                            s.channelIdByName(secName)?.let { id -> s.joinChannel(id) }
                        }
                    }
                }
            } else {
                Log.d(TAG, "notePrivateCallEnded: slot not in temp (joined=${slot.joinedChannelId}, temp=$tempCh) — no rejoin")
            }
        }
        // Respin VS2 if it was torn down (call was on VS1).
        val toRestore = preCallSecondaryName
        preCallSecondaryName = null
        if (toRestore != null && toRestore.isNotBlank()) {
            Log.i(TAG, "notePrivateCallEnded: restoring VS2 to '$toRestore'")
            activeSecondaryName = toRestore
            spinUpSecondary(toRestore, "restore after private call")
        }
    }

    @Volatile
    private var preCallSecondaryName: String? = null

    /**
     * Mumble users currently sharing EITHER VS1's or VS2's channel
     * with us. De-duplicated by callsign (with the `---<uuid>`
     * suffix stripped) — VX opens two sockets per device, both
     * suffixed with different UUIDs, but they're the same operator
     * so they should appear once. UUIDs are NEVER exposed in the
     * GUI: the displayName field is always the bare callsign.
     *
     * Returns one MumblePeer per unique callsign. The sessionId is
     * the FIRST observed session for that callsign; the call signal
     * goes to that session and Mumble routes voice through it.
     *
     * Excludes our own sessions on both VS1 and VS2 (we don't want
     * to call ourselves).
     */
    fun callablePeers(): List<MumblePeer> {
        val ourPrimary = primary.session?.ourSessionId() ?: 0
        val ourSecondary = secondary?.session?.ourSessionId() ?: 0
        val seenByCallsign = mutableMapOf<String, MumblePeer>()
        // Walk VS1 first so VS1's session id wins on dedup ties (VS1
        // is the canonical call origination identity).
        addPeersFromSlot(primary, ourPrimary, ourSecondary, seenByCallsign)
        secondary?.let { addPeersFromSlot(it, ourPrimary, ourSecondary, seenByCallsign) }
        return seenByCallsign.values.sortedBy { it.displayName.lowercase() }
    }

    private fun addPeersFromSlot(
        slot: Slot,
        ourPrimary: Int,
        ourSecondary: Int,
        out: MutableMap<String, MumblePeer>,
    ) {
        val s = slot.session ?: return
        if (slot.joinedChannelId < 0) return
        // Enumerate ALL peers the server has told us about — not just
        // those in our own channel. Mumble whisper (VoiceTarget) routes
        // voice peer-to-peer regardless of channel membership, so the
        // call directory should show everyone on the server.
        //
        // BUT only include peers running XV or VX. Generic Mumble
        // clients (Mumla, mumble-desktop, etc.) have no way to handle
        // our [TAK MxVx : REQUEST_CALL ] TextMessage — they receive it
        // as a chat line in their UI and ignore it. Calling them from
        // XV would create a temp channel that the peer never joins,
        // leaving the caller "ringing" indefinitely until the 30s
        // answer-timeout fires.
        //
        // XV/VX clients use the username convention "<callsign>---<uuid>"
        // (verified in our MumbleAuth, and observed against VX users on
        // OTS). Presence of the "---" separator is a strong-enough
        // heuristic: generic clients pick plain names like "alice" or
        // "Mumla-User-3" without the suffix. Operator could in theory
        // create a username with "---" in it, but that's a deliberate
        // collision and acceptable false-positive cost.
        slot.channelBySession.entries
            .asSequence()
            .filter { it.key != ourPrimary && it.key != ourSecondary }
            .forEach { entry ->
                val raw = s.userNameById(entry.key) ?: return@forEach
                if (!raw.contains("---")) return@forEach
                val callsign = stripUuidSuffix(raw)
                if (!out.containsKey(callsign)) {
                    out[callsign] = MumblePeer(sessionId = entry.key, displayName = callsign)
                }
            }
    }

    /** @deprecated Use [callablePeers] — also enumerates VS2 + dedupes
     *  by callsign + strips UUIDs. Kept as a thin alias for callers
     *  that haven't been migrated yet. */
    @Deprecated("Use callablePeers()", ReplaceWith("callablePeers()"))
    fun peersInPrimaryChannel(): List<MumblePeer> = callablePeers()

    data class MumblePeer(
        val sessionId: Int,
        val displayName: String,
    )

    /** Friendly display name for a Mumble session id, with the
     *  `---<uuid>` suffix stripped. Returns "session:N" if the
     *  session has no recorded username (rare — usually arrives
     *  before voice). Used by the call UI to show "INDY" instead
     *  of "mumble:53" or "INDY---f9e9c97a-...". */
    fun peerDisplayName(sessionId: Int): String {
        val s = primary.session ?: return "session:$sessionId"
        val raw = s.userNameById(sessionId) ?: return "session:$sessionId"
        return stripUuidSuffix(raw)
    }

    fun secondaryChannelName(): String? = secondary?.joinedChannelName() ?: activeSecondaryName

    /**
     * Active speakers on the given slot — peers that have delivered a
     * voice frame within [TALKING_TTL_MS]. Names are resolved from the
     * UserState cache; falls back to "session:<id>" if the name hasn't
     * been seen yet (rare — usually arrives before the first voice).
     */
    fun activeSpeakers(slotIdx: Int): List<String> {
        val slot = slotFor(slotIdx) ?: return emptyList()
        val s = slot.session ?: return emptyList()
        val now = System.currentTimeMillis()
        return slot.talkingPeers.entries
            .asSequence()
            .filter { now - it.value <= TALKING_TTL_MS }
            .map { (sessionId, _) ->
                s.userNameById(sessionId.toInt())?.let { stripUuidSuffix(it) }
                    ?: "session:$sessionId"
            }.toList()
    }

    /**
     * Snapshot of every Mumble user currently in the channel that
     * [slotIdx] is joined to (VS1=0, VS2=1). Includes the local
     * operator's own slot identity. Empty when the slot isn't
     * connected or hasn't joined a channel yet.
     *
     * Backs the UI's "members on my channel" picker (Recents → two-
     * column dialog and per-PTT 👥 button). The presence registry
     * adds the deviceUid + jump-channel data on the UI side; this
     * accessor only knows about Mumble-roster facts.
     */
    fun channelMembers(slotIdx: Int): List<MumbleMember> {
        val slot = slotFor(slotIdx) ?: return emptyList()
        val s = slot.session ?: return emptyList()
        val joinedId = slot.joinedChannelId
        if (joinedId < 0) return emptyList()
        val now = System.currentTimeMillis()
        return slot.channelBySession.entries
            .asSequence()
            .filter { it.value == joinedId }
            .map { (sessionId, channelId) ->
                val rawName = s.userNameById(sessionId)
                val callsign = rawName?.let { stripUuidSuffix(it) } ?: "session:$sessionId"
                val lastTalkMs = slot.talkingPeers[sessionId.toLong()] ?: 0L
                val talkingNow = lastTalkMs > 0 && (now - lastTalkMs) <= TALKING_TTL_MS
                MumbleMember(
                    sessionId = sessionId,
                    callsign = callsign,
                    channelId = channelId,
                    isTalking = talkingNow,
                )
            }.toList()
    }

    /** Trim the `---<uuid>` part off a Mumble username so the UI shows
     *  just the callsign ("INDY" instead of "INDY---f6f92fc2-..."). */
    private fun stripUuidSuffix(username: String): String {
        val idx = username.indexOf("---")
        return if (idx > 0) username.substring(0, idx) else username
    }

    /** Snapshot of the server's channel directory (from the primary
     *  session's view; both sessions see the same set on the same
     *  server). Returns ALL channels — sorted by participation tier
     *  (PARTICIPATE+UNKNOWN → LISTEN → UNAUTHORIZED) then by name.
     *  Empty until ChannelStates have arrived.
     *
     *  Overrides the participation tier on any channel where we're
     *  currently suppressed (OTS direction-OUT enforcement, Mumble
     *  admin mute). PermissionQuery only sees ACL bits — the
     *  suppress flag on our own UserState is the runtime truth.
     *  Without this lift, a channel with ACL Speak=true but
     *  direction=OUT would sort to the top as PARTICIPATE and
     *  display without the 🔇 icon. */
    fun availableChannels(): List<com.atakmap.android.xv.transport.mumble.MumbleSession.ChannelInfo> {
        // Snapshot both inputs upfront. Without the snapshot, a
        // concurrent channel-state update mid-iteration could change
        // the participation tier under our feet (PARTICIPATE briefly
        // appears as LISTEN, or vice versa), and the picker flickers
        // between renderings. ConcurrentHashMap + ConcurrentHashMap.
        // newKeySet() are safe to iterate but offer no consistency
        // across two reads — explicit snapshot fixes that. Audit M19.
        val map = primary.session?.allChannels() ?: return emptyList()
        val suppressed = suppressedChannelIds.toSet()
        val listen = com.atakmap.android.xv.transport.mumble.MumbleSession.ChannelInfo.Participation.LISTEN
        val participate = com.atakmap.android.xv.transport.mumble.MumbleSession.ChannelInfo.Participation.PARTICIPATE
        val cooked =
            map.values.map { ch ->
                // Relabel "Root" → "Lobby" everywhere the picker /
                // call UI reads. Server-side channel name is still
                // "Root" in Mumble's directory, but operators
                // experience it as the lobby they land in before
                // joining a tactical channel. Internal lookups in
                // join-by-name still match against the original
                // "Root" via channelIdByName so the rename is purely
                // cosmetic — channelId 0 is unchanged.
                val relabeled =
                    if (ch.id == 0 && ch.name.equals("Root", ignoreCase = true)) {
                        ch.copy(name = LOBBY_DISPLAY_NAME)
                    } else {
                        ch
                    }
                // Listen-only override: any channel we've ever been
                // observed as suppressed on this session, regardless
                // of whether we're currently joined to it. Without
                // this, a direction-OUT channel showed PARTICIPATE in
                // the picker the moment the operator moved away —
                // PermissionQuery sees only ACL bits, not OTS
                // direction enforcement.
                if (suppressed.contains(relabeled.id) && relabeled.participation == participate) {
                    relabeled.copy(participation = listen)
                } else {
                    relabeled
                }
            }
        // Lobby (id=0) is always sorted to the top regardless of
        // participation tier — it's the universal landing channel,
        // not a tactical one. The rest sort by tier + name.
        return cooked.sortedWith(
            compareBy(
                { if (it.id == 0) 0 else 1 },
                { participationOrder(it.participation) },
                { it.name.lowercase() },
            ),
        )
    }

    private fun participationOrder(p: com.atakmap.android.xv.transport.mumble.MumbleSession.ChannelInfo.Participation): Int =
        when (p) {
            // Tier sort: full → listen-only → denied. UNKNOWN
            // (PermissionQuery hasn't responded yet) is grouped
            // with full — best-case until proven otherwise; the
            // tier resolves and the row re-renders the moment the
            // verdict arrives. Secondary sort is alphabetical
            // within each tier.
            com.atakmap.android.xv.transport.mumble.MumbleSession.ChannelInfo.Participation.PARTICIPATE -> 0
            com.atakmap.android.xv.transport.mumble.MumbleSession.ChannelInfo.Participation.UNKNOWN -> 0
            com.atakmap.android.xv.transport.mumble.MumbleSession.ChannelInfo.Participation.LISTEN -> 1
            com.atakmap.android.xv.transport.mumble.MumbleSession.ChannelInfo.Participation.UNAUTHORIZED -> 2
        }

    override fun connect(listener: TransportListener) {
        // Single-call entry-point: bring up VS1 and (if configured) VS2
        // immediately. The wrapper (ReconnectingMumbleTransport) drives
        // the staged path via connectPrimaryOnly / awaitPrimaryReady /
        // connectSecondary instead — this entry-point exists for
        // tests + non-wrapped callers.
        transportListener = listener
        startSlot(primary)
        val sec = activeSecondaryName
        if (!sec.isNullOrBlank()) {
            if (sec.equals(config.channelName, ignoreCase = true)) {
                Log.w(TAG, "VS2 ('$sec') matches VS1 by name — refusing to spin up secondary session (VS1==VS2)")
            } else {
                spinUpSecondary(sec, "initial connect")
            }
        }
    }

    /**
     * Wrapper-facing staged-connect entry: brings up VS1 only. The
     * wrapper waits for [awaitPrimaryReady] before calling
     * [connectSecondary], so VS2's auth can never overlap VS1's still-
     * pending server-side accept (the parallel-handshake racing into
     * UsernameInUse mid-auth was a server-wide-kick trigger we no
     * longer want to ever reach).
     */
    fun connectPrimaryOnly(listener: TransportListener) {
        transportListener = listener
        startSlot(primary)
    }

    /**
     * Wrapper-facing: now that VS1 is READY, start VS2 if configured.
     * Validates VS1==VS2 name + active-call mute the same way the
     * single-call connect() does.
     */
    fun connectSecondary() {
        val sec = activeSecondaryName
        if (sec.isNullOrBlank()) {
            Log.i(TAG, "connectSecondary: no secondary configured — skipping")
            return
        }
        if (sec.equals(config.channelName, ignoreCase = true)) {
            Log.w(TAG, "VS2 ('$sec') matches VS1 by name — refusing to spin up secondary session (VS1==VS2)")
            return
        }
        spinUpSecondary(sec, "wrapper-staged secondary start")
    }

    /**
     * Wrapper-facing: poll until VS1's MumbleSession reports READY, or
     * the timeout elapses. Returns true if ready, false on timeout.
     * Used to sequence VS2 start strictly after VS1's ServerSync.
     */
    fun awaitPrimaryReady(timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val s = primary.session
            if (s != null && s.currentState() == com.atakmap.android.xv.transport.mumble.MumbleSession.ConnectState.READY) {
                return true
            }
            try {
                Thread.sleep(20)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
        Log.w(
            TAG,
            "awaitPrimaryReady($timeoutMs): timed out (state=${primary.session?.currentState()})",
        )
        return false
    }

    /**
     * Wrapper-facing: wait for BOTH slots to be fully torn down before
     * starting a fresh inner. Prevents a stale TLS handshake on VS2
     * from racing the fresh inner's VS1 auth (parallel sessions for the
     * same username triggers Murmur's duplicate-cleanup cascade, which
     * has historically kicked unrelated users).
     */
    fun awaitFullyDisconnected(timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        val primarySession = primary.session
        val secondarySession = secondary?.session
        val primaryOk =
            primarySession?.awaitFullyDisconnected(timeoutMs)
                ?: true // no session ever → already torn down
        val remaining = (deadline - System.currentTimeMillis()).coerceAtLeast(0)
        val secondaryOk =
            secondarySession?.awaitFullyDisconnected(remaining)
                ?: true
        if (!primaryOk || !secondaryOk) {
            Log.w(
                TAG,
                "awaitFullyDisconnected: primary=$primaryOk secondary=$secondaryOk after ${timeoutMs}ms",
            )
        }
        return primaryOk && secondaryOk
    }

    private fun startSlot(slot: Slot) {
        val tag = if (slot.isPrimary()) "primary" else "secondary"
        Log.i(TAG, "starting $tag session suffix=${slot.slotSuffix} channel='${slot.initialChannel}'")
        val s =
            MumbleSession(
                host = config.host,
                port = config.port,
                takServerHost = config.host, // OTS hosts Mumble + TAK on the same hostname
                // slot.slotSuffix is the deterministic per-(device, slot)
                // suffix for THIS slot — see XvMapComponent /
                // MumbleInstallId. Username on the wire becomes
                // "<callsign>---<suffix>" (suffix includes the VS1/VS2
                // marker so the two slots are distinct identities).
                slotSuffix = slot.slotSuffix,
                listener = SessionBridge(slot),
                vxCompat = config.vxCompat,
                deviceUid = config.deviceUid,
            )
        slot.session = s
        s.connect()
    }

    private fun spinUpSecondary(
        name: String,
        reason: String,
    ) {
        if (secondary != null) {
            Log.w(TAG, "spinUpSecondary($name, $reason): secondary already exists — tearing down first")
            tearDownSecondary("re-spin")
        }
        // Same-channel guard against the primary's currently-known channel
        // id. If primary hasn't joined yet, the in-bridge ChannelState /
        // UserState handlers will fail-safe by name comparison. "Lobby"
        // is XV's display alias for Mumble's Root channel (id=0) —
        // resolve it here too so a literal name lookup doesn't miss.
        val isLobby = name.equals(LOBBY_DISPLAY_NAME, ignoreCase = true)
        val primarySession = primary.session
        if (primarySession != null) {
            val secondaryId = if (isLobby) 0 else primarySession.channelIdByName(name)
            if (secondaryId != null && primary.joinedChannelId == secondaryId) {
                Log.w(TAG, "spinUpSecondary($name): id=$secondaryId == primary's joined channel — refusing")
                return
            }
        }
        val sec = Slot(idx = 1, slotSuffix = secondarySlotSuffix, initialChannel = name)
        secondary = sec
        activeSecondaryName = name
        Log.i(TAG, "spinning up secondary session for '$name' suffix=$secondarySlotSuffix ($reason)")
        startSlot(sec)
    }

    private fun tearDownSecondary(reason: String) {
        val sec = secondary ?: return
        Log.i(TAG, "tearing down secondary session ($reason)")
        // Cancel any pending UsernameInUse-rotation retry so a tear-down
        // initiated mid-backoff (e.g. private call started, user cleared
        // VS2) doesn't fire a respin after we've torn the slot down.
        try {
            handler?.removeCallbacks(secondaryRetryRunnable)
        } catch (_: Throwable) {
        }
        // Graceful stream-end: emit a Voice terminator on VS2 before
        // closing the socket so the peer's Opus decoder gets the
        // proper end-of-stream marker. Without it, an in-flight TX
        // interrupted by a graceful disconnect leaves the peer's PLC
        // (packet-loss-concealment) extrapolating from the last good
        // frame — that's what produces the brief "insane noise"
        // observed when ATAK is restarted mid-burst. Wrapped in
        // try/catch because the session may be in any disconnect-
        // adjacent state by now; failure here doesn't block teardown.
        if (sec.connected) {
            try {
                sec.session?.sendVoice(ByteArray(0), target = 0, terminator = true)
                Log.i(TAG, "tearDownSecondary: emitted VS2 terminator before teardown")
            } catch (t: Throwable) {
                Log.w(TAG, "secondary terminator on teardown threw", t)
            }
        }
        try {
            sec.session?.disconnect()
        } catch (t: Throwable) {
            Log.w(TAG, "secondary disconnect threw", t)
        }
        sec.decoders.values.forEach { it.close() }
        sec.decoders.clear()
        sec.talkingPeers.clear()
        sec.session = null
        sec.connected = false
        sec.joinedChannelId = -1
        secondary = null
    }

    // FIX 2: rotate the secondary slot's UUID and retry startSlot with
    // capped backoff. Triggered by SessionBridge.onReject when the
    // server rejects with UsernameInUse (stale ghost session for our
    // VS2 identity), and by SessionBridge.onUserRemove when the
    // server self-kicks our VS2 (FIX 3). The wrapper-level reconnect
    // path is wired only to primary failures; without this method,
    // VS2 just disappears with no recovery and no error surface.
    //
    // Backoff schedule: 2s, 4s, 8s, 16s, 32s (~62s total) — sized to
    // outlast Murmur's TCP ghost-timeout. After SECONDARY_MAX_RETRIES failed
    // attempts the slot is marked deadWithError so the UI can render
    // an error chip, and we stop retrying — VX-style ghost sessions
    // expire server-side in ~30-60s and the operator can manually
    // re-target VS2 via the channel picker once they do.
    //
    // NEVER tears down the primary slot or surfaces failure to the
    // wrapper-level transportListener — secondary failures are
    // strictly localized.
    private val secondaryRetryRunnable: Runnable = Runnable { runSecondaryRetryAttempt() }

    private fun scheduleSecondaryRetry(reason: String) {
        val sec =
            secondary ?: run {
                Log.w(TAG, "scheduleSecondaryRetry($reason): no secondary slot — ignoring")
                return
            }
        if (sec.deadWithError) {
            Log.w(TAG, "scheduleSecondaryRetry($reason): already dead — ignoring")
            return
        }
        sec.secondaryRetryCount++
        if (sec.secondaryRetryCount > SECONDARY_MAX_RETRIES) {
            Log.w(
                TAG,
                "scheduleSecondaryRetry($reason): retry limit ($SECONDARY_MAX_RETRIES) " +
                    "exhausted — marking VS2 dead with error",
            )
            sec.deadWithError = true
            // Tear down the live (failed) session so we don't leak
            // sockets / threads. Keep `secondary` non-null so
            // secondaryDead() can return true for the UI; clear the
            // session inside the slot.
            try {
                sec.session?.disconnect()
            } catch (t: Throwable) {
                Log.w(TAG, "secondary disconnect on dead-mark threw", t)
            }
            sec.session = null
            sec.connected = false
            sec.joinedChannelId = -1
            return
        }
        // 2s, 4s, 8s, 16s, 32s. Suffix is deterministic — no rotation;
        // the backoff is sized to outlast Murmur's TCP ghost-timeout
        // (~30-60s) so a stale session of OUR own previous incarnation
        // clears before we give up.
        val delayMs = SECONDARY_RETRY_BASE_MS shl (sec.secondaryRetryCount - 1)
        Log.i(
            TAG,
            "scheduleSecondaryRetry($reason): attempt ${sec.secondaryRetryCount}/$SECONDARY_MAX_RETRIES " +
                "in ${delayMs}ms with suffix=${sec.slotSuffix}",
        )
        val h = handler
        if (h == null) {
            // No looper (unit test) — fire synchronously so deterministic
            // tests still observe the rotation behavior.
            runSecondaryRetryAttempt()
        } else {
            h.removeCallbacks(secondaryRetryRunnable)
            h.postDelayed(secondaryRetryRunnable, delayMs.toLong())
        }
    }

    private fun runSecondaryRetryAttempt() {
        val sec = secondary ?: return
        if (sec.deadWithError) return
        // Tear down any zombie session left from the prior failed attempt
        // before we spin a fresh one. This is in-line — tearDownSecondary
        // would null `secondary` out which we don't want here.
        try {
            sec.session?.disconnect()
        } catch (t: Throwable) {
            Log.w(TAG, "pre-retry secondary disconnect threw", t)
        }
        sec.session = null
        sec.connected = false
        sec.joinedChannelId = -1
        sec.decoders.values.forEach { it.close() }
        sec.decoders.clear()
        sec.talkingPeers.clear()
        Log.i(TAG, "runSecondaryRetryAttempt: re-starting VS2 with suffix=${sec.slotSuffix}")
        startSlot(sec)
    }

    /** Move the PRIMARY session into a different channel. */
    fun joinChannel(channelId: Int) {
        primary.lastSelfJoinAttemptMs = System.currentTimeMillis()
        primary.session?.joinChannel(channelId)
    }

    fun joinChannel(name: String): Boolean {
        val trimmed = name.trim()
        // "Lobby" is XV's display name for Mumble's Root channel
        // (id=0). Reverse the rename for the join — server-side name
        // is "Root" but operators see it as "Lobby" in the picker.
        if (trimmed.equals(LOBBY_DISPLAY_NAME, ignoreCase = true)) {
            primary.lastSelfJoinAttemptMs = System.currentTimeMillis()
            primary.session?.joinChannel(0)
            return true
        }
        // Symmetric guard with retargetSecondary: VS1 must never share a
        // channel with VS2. Without this, the operator could move VS1
        // onto VS2's channel from the picker and the two slots would
        // duplicate every voice frame on that channel (each slot sends
        // its own copy of TX, and both decode incoming frames once each).
        val secName = activeSecondaryName
        if (!secName.isNullOrBlank() && secName.equals(trimmed, ignoreCase = true)) {
            Log.w(TAG, "joinChannel('$trimmed'): would collide with VS2 ('$secName') — refusing")
            return false
        }
        val s = primary.session ?: return false
        val id =
            s.channelIdByName(trimmed) ?: run {
                Log.w(TAG, "joinChannel('$trimmed') — no channel by that name")
                return false
            }
        s.joinChannel(id)
        return true
    }

    /** Reset the voice sequence on the slot the next burst will use.
     *  Called by TxController on PTT-down so each burst starts at seq=1. */
    fun beginVoiceBurst(slot: Int = 0) {
        slotFor(slot)?.session?.resetVoiceSequence()
    }

    /**
     * Re-target VS2 at runtime. If no secondary session exists yet,
     * spins one up. If one exists, moves it into [name] (joinChannel on
     * the secondary session). Empty/blank tears down the secondary
     * session entirely (VS2 cleared).
     *
     * Refuses if [name] would collide with the primary's current channel.
     */
    fun retargetSecondary(name: String): Boolean {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) {
            activeSecondaryName = null
            tearDownSecondary("cleared by user")
            return true
        }
        // Resolve "Lobby" (XV's display alias) to Mumble's Root channel
        // id=0. Symmetric with the primary's joinChannel(name) at the
        // top of this file — channelIdByName() looks up by server-side
        // name "Root", not the display alias, so a literal "Lobby"
        // lookup misses.
        val isLobby = trimmed.equals(LOBBY_DISPLAY_NAME, ignoreCase = true)
        // Collision check against primary's joined channel id.
        val primarySession = primary.session
        if (primarySession != null) {
            val newId = if (isLobby) 0 else primarySession.channelIdByName(trimmed)
            if (newId != null && primary.joinedChannelId == newId) {
                Log.w(TAG, "retargetSecondary('$trimmed'): would collide with primary's channel — refusing")
                return false
            }
        }
        val sec = secondary
        if (sec == null) {
            // No secondary yet — spin one up. spinUpSecondary applies the
            // same collision check against primary's known state.
            spinUpSecondary(trimmed, "retarget on idle secondary")
            return true
        }
        // Live secondary — move it. Use the secondary's own channel
        // directory in case it's seen channels the primary hasn't (rare;
        // both should converge). joinChannel(name) on session resolves
        // the id directly.
        val secSession = sec.session
        if (secSession == null) {
            Log.w(TAG, "retargetSecondary('$trimmed'): secondary has no session — re-spinning")
            tearDownSecondary("missing session")
            spinUpSecondary(trimmed, "re-spin after missing session")
            return true
        }
        val id =
            if (isLobby) {
                0
            } else {
                (
                    secSession.channelIdByName(trimmed) ?: run {
                        Log.w(TAG, "retargetSecondary('$trimmed') — name not in secondary's directory")
                        return false
                    }
                    )
            }
        Log.i(TAG, "retargetSecondary: moving secondary session into '$trimmed' (id=$id)")
        activeSecondaryName = trimmed
        secSession.joinChannel(id)
        return true
    }

    private fun slotFor(idx: Int): Slot? =
        when (idx) {
            0 -> primary
            1 -> secondary
            else -> null
        }

    override fun sendFrame(frame: VoiceFrame) {
        if (!primary.connected) {
            Log.w(TAG, "sendFrame dropped — primary not connected")
            return
        }
        val slot = pickSlotForSend(frame.targetSlot)
        val s = slot.session
        if (s == null) {
            Log.e(TAG, "sendFrame slot=${slot.idx} dropped — session null")
            return
        }
        // Channel-speak (target=0) — Option B keeps both call sides in
        // the temp channel, so normal channel routing reaches the peer.
        // The temp channel's maxUsers=2 enforces 2-person scope; no
        // whisper VoiceTarget required.
        s.sendVoice(frame.opusPayload, target = 0)
    }

    /**
     * Resolve which Slot a TX frame for [requested] should go through.
     * Slot 0 always uses primary. Slot 1 uses secondary if it's live,
     * otherwise falls back to primary so PTTS still keys *something*
     * rather than going silent (a UI state will already show VS2 is
     * not active).
     */
    private fun pickSlotForSend(requested: Int): Slot {
        if (requested <= 0) return primary
        val sec = secondary
        val reason: SlotFallbackReason? =
            when {
                sec == null -> SlotFallbackReason.NO_SECONDARY
                !sec.connected || sec.joinedChannelId < 0 -> SlotFallbackReason.SECONDARY_NOT_JOINED
                sec.joinedChannelId == primary.joinedChannelId && primary.joinedChannelId >= 0 ->
                    SlotFallbackReason.SAME_CHANNEL
                else -> null
            }
        if (reason == null) {
            if (lastSlotFallbackReason != null) {
                Log.i(TAG, "sendFrame slot=$requested: secondary now usable — routing to VS2")
                lastSlotFallbackReason = null
            }
            return sec!!
        }
        // Fires per-frame at audio rates (~100/s) on a slot-1 burst when
        // the secondary isn't routable — so log only on reason transitions
        // (start of fallback + reason flip) to keep the buffer readable.
        // Reverts to silence once a clean send happens above.
        if (lastSlotFallbackReason != reason) {
            val msg =
                when (reason) {
                    SlotFallbackReason.NO_SECONDARY ->
                        "sendFrame slot=$requested but no secondary slot — falling back to primary"
                    SlotFallbackReason.SECONDARY_NOT_JOINED ->
                        "sendFrame slot=$requested but secondary not yet joined — falling back to primary"
                    SlotFallbackReason.SAME_CHANNEL ->
                        "sendFrame slot=$requested but VS1==VS2 channel id — falling back to primary"
                }
            Log.w(TAG, msg)
            lastSlotFallbackReason = reason
        }
        return primary
    }

    private enum class SlotFallbackReason { NO_SECONDARY, SECONDARY_NOT_JOINED, SAME_CHANNEL }

    @Volatile private var lastSlotFallbackReason: SlotFallbackReason? = null

    /**
     * End-of-utterance terminator. Slot must match the burst's slot or
     * the receiving client leaves a stuck talker indicator on whichever
     * session's user was speaking.
     */
    fun sendTerminator(targetSlot: Int = 0) {
        if (!primary.connected) return
        val slot = pickSlotForSend(targetSlot)
        // Terminator over channel target — matches the burst it ends.
        slot.session?.sendVoice(ByteArray(0), target = 0, terminator = true)
    }

    override fun disconnect() {
        Log.i(TAG, "MumbleTransport.disconnect() — tearing down both slots")
        // FIX 10: clear pending awaitChannelByName callbacks so they
        // don't leak. The direct-call orchestrator registers here
        // right before sending a ChannelState create; if we tear the
        // transport down between request + response, the callback
        // would otherwise sit in the map forever (and a subsequent
        // re-connect's matching ChannelState would fire it against a
        // stale closure capturing the dead transport's session).
        synchronized(pendingChannelByName) {
            if (pendingChannelByName.isNotEmpty()) {
                Log.i(
                    TAG,
                    "MumbleTransport.disconnect: dropping ${pendingChannelByName.size} " +
                        "pending awaitChannelByName callback(s)",
                )
                pendingChannelByName.clear()
            }
        }
        tearDownSecondary("transport disconnect")
        // Graceful stream-end on VS1: emit a Voice terminator before
        // the socket closes so the peer's Opus decoder gets the proper
        // end-of-stream marker rather than having to wait for PLC
        // timeout (which produces audible noise from extrapolation
        // against the last good frame). Same rationale as the VS2
        // hook in tearDownSecondary — addresses the "insane noise on
        // ATAK restart" complaint by ensuring all graceful disconnect
        // paths quiesce the stream cleanly. (SIGKILL via force-stop
        // still can't run this code — the kernel just closes the
        // socket — but every developer / user-initiated reload hits
        // this path.)
        if (primary.connected) {
            try {
                primary.session?.sendVoice(ByteArray(0), target = 0, terminator = true)
                Log.i(TAG, "MumbleTransport.disconnect: emitted VS1 terminator before teardown")
            } catch (t: Throwable) {
                Log.w(TAG, "primary terminator on disconnect threw", t)
            }
        }
        try {
            // MumbleSession.disconnect joins the read thread + awaits the
            // write executor, so it returns only after the session is
            // fully quiesced. No additional settle-sleep needed (the
            // legacy 100 ms sleep here was masking a non-blocking
            // disconnect that has since been fixed).
            primary.session?.disconnect()
        } catch (t: Throwable) {
            Log.w(TAG, "primary disconnect threw", t)
        }
        primary.session = null
        primary.connected = false
        primary.joinedChannelId = -1
        primary.decoders.values.forEach { it.close() }
        primary.decoders.clear()
        primary.talkingPeers.clear()
        transportListener = null
        Log.i(TAG, "MumbleTransport.disconnect() complete")
    }

    private inner class SessionBridge(
        private val slot: Slot,
    ) : MumbleSession.Listener {
        private val tag get() = if (slot.isPrimary()) "VS1" else "VS2"

        // Cache of every UserState's last-seen channel id, by session.
        // Populated on every onUser arrival regardless of whether the
        // server has confirmed our own session id yet. Used by
        // onConnected to retroactively detect our channel when the
        // server's initial UserState for us arrived BEFORE ServerSync
        // (which is the normal case — Murmur sends ChannelStates +
        // UserStates first, then ServerSync). Without this, the
        // post-ServerSync onUser may not include hasChannelId() (e.g.
        // when it's just echoing a comment update), so onChannelChanged
        // never fires, mumbleSessionLive stays false, and every PTT
        // gets denied with a deny tone.
        // Hoisted to slot.channelBySession so the outer transport's
        // peersInPrimaryChannel() can read it. Keeping the local alias
        // for readability inside this bridge.
        private val channelBySession get() = slot.channelBySession

        override fun onConnecting() {
            Log.i(TAG, "[$tag] connecting to ${config.host}:${config.port}")
        }

        override fun onConnected(welcomeText: String?) {
            slot.connected = true
            // Clean ServerSync — reset secondary retry state so the next
            // failure starts the backoff schedule from scratch.
            slot.secondaryRetryCount = 0
            slot.deadWithError = false
            Log.i(TAG, "[$tag] ServerSync received; welcome='${welcomeText ?: ""}'")
            // Only the primary fires the public TransportListener.onConnected —
            // single transport-level event, secondary status surfaces via
            // secondaryConnected() polling.
            if (slot.isPrimary()) {
                transportListener?.onConnected()
            }
            // Retroactive channel detection from the pre-ServerSync
            // UserState cache. The server's initial broadcast usually
            // tells us our channel BEFORE we know we are us (ourSessionId
            // is 0 until ServerSync). Now that ourSessionId is set,
            // look ourselves up and synthesize the missed
            // onChannelChanged. Without this firing, the plugin's
            // setMumbleSessionState(true) never reaches the service,
            // mumbleSessionLive stays false, canTransmit returns false,
            // and every PTT plays the deny tone instead of TXing.
            val s = slot.session
            val ourId = s?.ourSessionId() ?: 0
            val cachedChannel = if (ourId != 0) channelBySession[ourId] else null
            if (cachedChannel != null && slot.joinedChannelId != cachedChannel) {
                val prev = slot.joinedChannelId
                slot.joinedChannelId = cachedChannel
                val name = s?.channelNameById(cachedChannel)
                Log.i(
                    TAG,
                    "[$tag] post-ServerSync retroactive channel: $prev → $cachedChannel ('$name')",
                )
                try {
                    // Initial landing (self-UserState arrived before
                    // ServerSync) — not an admin move, by definition.
                    onChannelChanged(slot.idx, cachedChannel, name, false)
                } catch (t: Throwable) {
                    Log.w(TAG, "[$tag] onChannelChanged callback threw", t)
                }
            } else if (slot.joinedChannelId < 0) {
                // No UserState arrived for our session before ServerSync —
                // some Murmur builds (and OTS) defer the self-UserState
                // until after ServerSync. Assume Root (channelId 0), which
                // is where Mumble lands every freshly-authed user by
                // protocol contract. Without this, the UI sits on
                // "(tap to pick)" / "(joining…)" indefinitely until the
                // operator manually picks a channel, even though they're
                // really already at Root and can talk on it.
                //
                // Field complaint 2026-05-19: "VS2 is struggling to get
                // into the lobby." Prior to this, the fallback was
                // gated on slot.isPrimary() — so VS2 sitting on Root
                // never got its onChannelChanged fired, secondary-
                // Connected() returned false (joinedChannelId stayed
                // at -1), and the VS2 PTT card rendered "(joining…)"
                // forever even though VS2 was perfectly connected and
                // sitting in Lobby. Removing the isPrimary() gate
                // covers both slots.
                slot.joinedChannelId = 0
                Log.i(TAG, "[$tag] post-ServerSync default landing: assuming Root (id=0 / Lobby)")
                try {
                    // Initial Lobby landing isn't an admin-initiated
                    // move — it's the connect-time default.
                    onChannelChanged(slot.idx, 0, LOBBY_DISPLAY_NAME, false)
                } catch (t: Throwable) {
                    Log.w(TAG, "[$tag] onChannelChanged callback for default-Root threw", t)
                }
            }
            // Auto-join this slot's configured channel right after ServerSync.
            val target = slot.initialChannel
            if (target.isNotBlank()) {
                Log.i(TAG, "[$tag] auto-join target='$target'")
                if (s != null) {
                    // "Lobby" is XV's display name for Mumble's Root
                    // (id=0); channelIdByName matches against the raw
                    // server-side directory which still calls it "Root",
                    // so a persisted "Lobby" wouldn't resolve without
                    // this special-case. Mirrors the equivalent path in
                    // joinChannel(name).
                    val id =
                        if (target.equals(LOBBY_DISPLAY_NAME, ignoreCase = true)) {
                            0
                        } else {
                            s.channelIdByName(target)
                        }
                    if (id != null) {
                        s.joinChannel(id)
                    } else {
                        Log.w(TAG, "[$tag] auto-join '$target' did not resolve in directory")
                    }
                }
            }
        }

        override fun onChannel(channel: MumbleProto.ChannelState) {
            Log.i(
                TAG,
                "[$tag] channel id=${channel.channelId} parent=${channel.parent} name='${channel.name}'",
            )
            // If our initial-channel name finally arrived in the directory,
            // try the join now (server delivers ChannelStates around
            // ServerSync, but order isn't strictly defined).
            val want = slot.initialChannel
            if (want.isNotBlank() &&
                slot.joinedChannelId < 0 &&
                channel.hasName() &&
                channel.name.equals(want, ignoreCase = true)
            ) {
                Log.i(TAG, "[$tag] late ChannelState for '$want' arrived — joining")
                slot.lastSelfJoinAttemptMs = System.currentTimeMillis()
                slot.session?.joinChannel(channel.channelId)
            }
            // Phase E: fire any pending awaitChannelByName resolution.
            // Only on the primary slot — direct-call orchestration runs
            // exclusively there.
            if (slot.isPrimary() && channel.hasName()) {
                fireChannelByNameMatch(channel.channelId, channel.name)
            }
        }

        override fun onChannelRemove(channelId: Int) {
            // If the temp call channel we'd planned to fall back to from
            // a private call has been auto-cleaned by Murmur, clear our
            // rejoin target — otherwise we'd send a join for a dead
            // channel id on hangup and Murmur would silently park us at
            // root.
            if (slot.preCallChannelId == channelId) {
                Log.i(TAG, "[$tag] preCallChannelId $channelId removed server-side — clearing rejoin target")
                slot.preCallChannelId = -1
            }
        }

        override fun onUser(user: MumbleProto.UserState) {
            val s = slot.session
            // Cache every UserState's channel by session — pre-ServerSync
            // these arrive before we know our session id, and the
            // post-ServerSync retroactive lookup reads from here.
            // Capture the prior channel BEFORE updating so we can detect
            // peer-departures from our private call.
            //
            // First-sight lobby fix: Mumble's UserState.channel_id is
            // optional with default 0 (Root/Lobby). Murmur (and OTS) omit
            // the field on the initial sync for users that are at Root.
            // Without the fallback below, peers sitting in Lobby never
            // landed in channelBySession, so channelMembers(slot=0) and
            // callablePeers() both returned empty for Lobby — operators
            // saw "(no one here)" in the members picker and the Recents
            // call directory while teammates were standing right there.
            // For an UNKNOWN session with no channel_id we treat as 0;
            // for a session we already know, we don't overwrite — a
            // hasChannelId()=false update is a name/comment/mute change,
            // not a channel move.
            val effectiveChannelId: Int? =
                when {
                    user.hasChannelId() -> user.channelId
                    !channelBySession.containsKey(user.session) -> 0
                    else -> null
                }
            val priorChannel =
                if (effectiveChannelId != null) channelBySession[user.session] else null
            if (effectiveChannelId != null) {
                channelBySession[user.session] = effectiveChannelId
            }
            // Caller-side accept detection: callee joining the temp
            // channel is the implicit accept signal in the whisper
            // architecture (no explicit ACCEPT TextMessage in VX's
            // protocol). Fires before the broader move-detection
            // below — that one ENDS the call on a peer move; this
            // one notes the START.
            if (slot.directCallPeerSession >= 0 &&
                slot.directCallIsCaller &&
                slot.privateCallChannelId >= 0 &&
                user.session.toLong() == slot.directCallPeerSession.toLong() &&
                user.hasChannelId() &&
                user.channelId == slot.privateCallChannelId &&
                priorChannel != slot.privateCallChannelId
            ) {
                Log.i(
                    TAG,
                    "[$tag] callee session=${user.session} joined temp channel ${slot.privateCallChannelId} — call accepted",
                )
                try {
                    onPeerAcceptedCall()
                } catch (t: Throwable) {
                    Log.w(TAG, "onPeerAcceptedCall threw", t)
                }
            }
            // Peer-moves-during-call detection. Role-aware logic:
            //
            //   - CALLER: we're in our tactical channel; peer (callee)
            //     joined the temp on accept and leaves it on hangup —
            //     fires on that specific move.
            //   - CALLEE: we're in the temp; peer (caller) was NEVER
            //     in the temp (whisper architecture — they stayed in
            //     their tactical channel). Any channel move by the
            //     peer is treated as a hangup signal: VX intermediate
            //     state moves the caller to Root then back to original,
            //     so the first move tells us they ended the call.
            //
            // Without the callee-side branch, a VX caller's hangup
            // never reaches us — they don't send CANCEL_CALL, they
            // just stop whispering and move channels.
            if (slot.directCallPeerSession >= 0 &&
                slot.privateCallChannelId >= 0 &&
                user.session.toLong() == slot.directCallPeerSession.toLong() &&
                user.hasChannelId() &&
                priorChannel != null &&
                priorChannel != user.channelId
            ) {
                val isCaller = slot.directCallIsCaller
                val isCallerCalleeLeavingTemp =
                    isCaller &&
                        priorChannel == slot.privateCallChannelId &&
                        user.channelId != slot.privateCallChannelId
                val isCalleeCallerMoving = !isCaller
                if (isCallerCalleeLeavingTemp || isCalleeCallerMoving) {
                    val why =
                        if (isCallerCalleeLeavingTemp) {
                            "callee left temp channel"
                        } else {
                            "caller moved channels during call (callee-side hangup detection)"
                        }
                    Log.i(
                        TAG,
                        "[$tag] peer session=${user.session} moved $priorChannel → ${user.channelId} — ending call ($why)",
                    )
                    endLocalPrivateCall(why)
                    // fall through so the rest of this UserState still
                    // updates routing caches consistently
                }
            }
            val isUs = s?.let { user.session == it.ourSessionId() } ?: false
            if (user.hasName() && user.name.isNotBlank()) {
                s?.rememberUserName(user.session, user.name)
            }
            if (isUs && user.hasChannelId()) {
                val prev = slot.joinedChannelId
                slot.joinedChannelId = user.channelId
                Log.i(TAG, "[$tag] *** WE MOVED: channel $prev → ${user.channelId} ***")
                if (prev != user.channelId) {
                    val name = s?.channelNameById(user.channelId)
                    // L1: byAdmin if the move didn't come from a
                    // self-initiated join within the recent grace
                    // window. Self-joins set lastSelfJoinAttemptMs in
                    // joinSelf(); admin moves leave it stale.
                    val sinceSelfJoin = System.currentTimeMillis() - slot.lastSelfJoinAttemptMs
                    val byAdmin = slot.lastSelfJoinAttemptMs == 0L || sinceSelfJoin > SELF_JOIN_GRACE_MS
                    if (byAdmin) {
                        Log.i(TAG, "[$tag] admin-initiated channel move (no self-join within ${SELF_JOIN_GRACE_MS}ms)")
                    }
                    try {
                        onChannelChanged(slot.idx, user.channelId, name, byAdmin)
                    } catch (t: Throwable) {
                        Log.w(TAG, "[$tag] onChannelChanged callback threw", t)
                    }
                }
            }
            // Track our own suppress flag — drives the listen-only UX
            // (deny tone on TX attempt, mute icon on the channel
            // header). Server sends UserState updates with hasSuppress()
            // set whenever the field actually changed; until then we
            // keep the previous value. A re-send with hasSuppress()
            // unset is therefore a no-op, not a clear-to-false.
            if (isUs && user.hasSuppress()) {
                val prev = slot.suppressed
                slot.suppressed = user.suppress
                // Update the picker-cache so the channel keeps its
                // listen-only status visible even after we move away.
                val joinedId = slot.joinedChannelId
                if (joinedId >= 0) {
                    if (user.suppress) {
                        suppressedChannelIds.add(joinedId)
                    } else {
                        suppressedChannelIds.remove(joinedId)
                    }
                }
                if (prev != user.suppress) {
                    Log.i(TAG, "[$tag] self suppress flag → ${user.suppress}")
                    try {
                        onSelfSuppressedChanged(slot.idx, user.suppress)
                    } catch (t: Throwable) {
                        Log.w(TAG, "[$tag] onSelfSuppressedChanged threw", t)
                    }
                }
            }
            val flags =
                buildString {
                    if (user.hasMute() && user.mute) append(" mute")
                    if (user.hasDeaf() && user.deaf) append(" deaf")
                    if (user.hasSuppress() && user.suppress) append(" SUPPRESSED")
                    if (user.hasSelfMute() && user.selfMute) append(" self-mute")
                    if (user.hasSelfDeaf() && user.selfDeaf) append(" self-deaf")
                    if (user.hasPrioritySpeaker() && user.prioritySpeaker) append(" priority")
                }
            Log.i(
                TAG,
                "[$tag] user session=${user.session} name='${user.name}' channel=${user.channelId}${if (isUs) " (← us)" else ""}$flags",
            )
        }

        override fun onUserRemove(remove: MumbleProto.UserRemove) {
            // Self-kick detection (H6). Server may UserRemove our own
            // session for an admin kick / ban OR for "username in use"
            // when the prior session's ghost is still tracked. Both
            // slots flag self-kick now: primary as onConnectionFailed
            // (the wrapper will classify SelfKickedException as fatal
            // and stop retrying); secondary as onSecondaryFailed so
            // the wrapper can run its ghost-cleanup-with-retry sequence
            // without tearing down VS1.
            val mySession = slot.session?.ourSessionId() ?: 0
            val isSelf = mySession != 0 && remove.session == mySession
            if (isSelf) {
                val banned = remove.hasBan() && remove.ban
                Log.w(
                    TAG,
                    "[$tag] self-${if (banned) "BAN" else "kick"} reason='${remove.reason}' " +
                        "actor=${remove.actor} — surfacing",
                )
                slot.connected = false
                slot.joinedChannelId = -1
                val ex =
                    SelfKickedException(
                        reason = remove.reason?.takeIf { it.isNotBlank() },
                        banned = banned,
                        byActorSession = if (remove.hasActor()) remove.actor else 0,
                    )
                if (slot.isPrimary()) {
                    transportListener?.onConnectionFailed(ex)
                } else {
                    transportListener?.onSecondaryFailed(ex)
                }
                return
            }
            Log.i(TAG, "[$tag] user gone session=${remove.session} reason='${remove.reason}'")
            // Peer-disconnects-during-call detection: if a session that
            // was in our temp call channel just disconnected (UserRemove
            // implies they're gone from the server, not just the
            // channel), and we're still in the temp channel, end the
            // call from our side too. Same rationale as the move-away
            // detector in onUser: leaves the temp channel empty so
            // Murmur can auto-clean and the OTHER party (if they're
            // somehow still alive) isn't trapped.
            val priorChannel = channelBySession.remove(remove.session)
            // Peer-disconnects detection. Same role-aware reasoning as
            // the onUser path — fires when the call's peer session
            // disappears entirely (their app crashed, they unplugged,
            // network drop, etc.). Caller side: callee was in temp,
            // disconnected → end call. Callee side: caller was in
            // their tactical channel, disconnected → still want to
            // end since the caller is unreachable.
            if (slot.directCallPeerSession >= 0 &&
                remove.session.toLong() == slot.directCallPeerSession.toLong()
            ) {
                Log.i(
                    TAG,
                    "[$tag] peer session=${remove.session} disconnected from temp channel " +
                        "${slot.privateCallChannelId} — ending call",
                )
                endLocalPrivateCall("peer disconnected during call")
            }
            slot.decoders.remove(remove.session.toLong())?.close()
            slot.talkingPeers.remove(remove.session.toLong())
            slot.session?.forgetUser(remove.session)
        }

        /**
         * Local-side teardown of an active private call. Same effect
         * as receiving CANCEL_CALL from the peer: leave the temp
         * channel, return to the pre-call channel (or fallback to the
         * configured primary), and clear call state. Idempotent —
         * called from both peer-departure detection and explicit
         * user-initiated hang-up paths.
         */
        private fun endLocalPrivateCall(reason: String) {
            val tempCh = slot.privateCallChannelId
            Log.i(TAG, "endLocalPrivateCall($reason): tempCh=$tempCh joined=${slot.joinedChannelId}")
            // notePrivateCallEnded clears the slot state, the
            // VoiceTarget, leaves the temp (both sides — Murmur
            // auto-joins the caller too), and respins VS2 if it was
            // torn down by notePrivateCallStarted.
            notePrivateCallEnded()
            // Tell the plugin to tear down the Telecom call + audio
            // mode + active-call activity. Without this hook the wire
            // state clears but the operator's UI / mic stay engaged
            // because the service-side never learns the call is over.
            try {
                onPrivateCallTransportTeardown()
            } catch (t: Throwable) {
                Log.w(TAG, "onPrivateCallTransportTeardown threw", t)
            }
        }

        override fun onTextMessage(msg: MumbleProto.TextMessage) {
            Log.i(TAG, "[$tag] text: ${msg.message}")
        }

        override fun onReject(reject: MumbleProto.Reject) {
            slot.connected = false
            val type = reject.type
            val reason = reject.reason?.takeIf { it.isNotBlank() }
            val failure: Throwable =
                when (type) {
                    MumbleProto.Reject.RejectType.WrongUserPW,
                    MumbleProto.Reject.RejectType.WrongServerPW,
                    MumbleProto.Reject.RejectType.InvalidUsername,
                    MumbleProto.Reject.RejectType.NoCertificate,
                    MumbleProto.Reject.RejectType.AuthenticatorFail,
                    -> {
                        Log.e(TAG, "[$tag] FATAL REJECT $type reason='$reason'")
                        FatalMumbleException(type, reason)
                    }
                    MumbleProto.Reject.RejectType.UsernameInUse -> {
                        Log.w(TAG, "[$tag] REJECT UsernameInUse — wrapper handles retry/rotation")
                        UsernameInUseException(reason)
                    }
                    else -> {
                        Log.w(TAG, "[$tag] transient REJECT $type reason='$reason' — retry sane")
                        IllegalStateException("Mumble REJECT $type: $reason")
                    }
                }
            // Primary REJECTs go through onConnectionFailed (wrapper-managed
            // backoff + UUID-stable retry). Secondary REJECTs go through
            // onSecondaryFailed so the wrapper can run its
            // ghost-cleanup-with-retry ladder without disturbing primary.
            if (slot.isPrimary()) {
                transportListener?.onConnectionFailed(failure)
            } else {
                Log.w(TAG, "[$tag] secondary REJECT — surfacing to wrapper, tearing down VS2")
                tearDownSecondary("VS2 REJECT: ${reject.reason}")
                transportListener?.onSecondaryFailed(failure)
            }
        }

        override fun onPermissionDenied(denied: MumbleProto.PermissionDenied) {
            Log.w(TAG, "[$tag] permission denied: ${denied.reason}")
        }

        override fun onDisconnected(reason: String?) {
            slot.connected = false
            slot.joinedChannelId = -1
            if (slot.isPrimary()) {
                transportListener?.onDisconnected(reason)
            } else {
                Log.i(TAG, "[$tag] disconnected: $reason — primary unaffected")
            }
        }

        override fun onError(t: Throwable) {
            slot.connected = false
            if (slot.isPrimary()) {
                transportListener?.onConnectionFailed(t)
            } else {
                Log.w(TAG, "[$tag] error: ${t.message} — leaving primary running", t)
            }
        }

        override fun onPrivateCallSignal(
            action: String,
            payload: String,
            fromSession: Long,
        ) {
            // VX-compat TextMessage call signaling is DROPPED for XV.
            // Direct calls go through CoT now (b-x-xv-call event type)
            // — see XvCallSignals + XvMapComponent.onCotCallSignal.
            // VX-XV direct-call interop is intentionally out of scope;
            // group-channel VX interop is unaffected.
            //
            // We still consume incoming PLAY_TONE and LINK_CHANNEL
            // because those layer on top of group-channel interop;
            // they're not direct-call signals.
            when (action) {
                "REQUEST_CALL", "CANCEL_CALL", "REJECT_CALL" -> {
                    Log.i(
                        TAG,
                        "[$tag] VX direct-call signal ignored (action='$action' " +
                            "fromSession=$fromSession) — XV uses CoT for direct calls",
                    )
                    return
                }
            }
            Log.i(
                TAG,
                "[$tag] private-call signal: action='$action' payload='$payload' fromSession=$fromSession",
            )
            when (action) {
                "REQUEST_CALL" -> {
                    val channelId = payload.trim().toIntOrNull()
                    if (channelId == null || channelId <= 0) {
                        // Root (id=0) and negative ids are illegal targets
                        // for a private call — VX always creates a fresh
                        // temp child channel and puts its (positive) id in
                        // the payload. A zero/negative would otherwise
                        // walk us into Root or wedge the slot bookkeeping
                        // (privateCallChannelId = 0 collides with the
                        // "no call active" sentinel).
                        Log.w(TAG, "REQUEST_CALL: invalid channelId in payload='$payload'")
                        return
                    }
                    // FIX 5: id=0 is Lobby/Root — accepting it would
                    // dump every operator into the lobby on auto-accept,
                    // which is never a private call destination. Guard
                    // against zero AND negative ids (shouldn't appear
                    // on the wire, but defense-in-depth).
                    if (channelId <= 0) {
                        Log.w(
                            TAG,
                            "REQUEST_CALL: invalid channel id $channelId in payload='$payload' " +
                                "(0 = Lobby/Root, ≤0 not a valid private channel)",
                        )
                        return
                    }
                    if (!autoAcceptPrivateCalls()) {
                        // Operator-confirms mode: route to the system
                        // Telecom incoming-call UI. Pre-call channel is
                        // recorded NOW so that on accept/reject we can
                        // restore it; if the operator rejects, the
                        // restore is a no-op (we never left). Disables
                        // the OTHER slot for the call duration. Also
                        // registers the VoiceTarget pointing at the
                        // caller so when the operator answers we can
                        // immediately whisper back.
                        notePrivateCallStarted(
                            callSlotIdx = slot.idx,
                            peerSession = fromSession.toInt(),
                            tempChannelId = channelId,
                            preCallChannelId = if (slot.joinedChannelId >= 0) slot.joinedChannelId else -1,
                            isCaller = false,
                        )
                        // Use peerDisplayName so the UUID/install-id
                        // suffix in the Mumble username ("alice---<uuid>")
                        // is stripped before the value lands in the
                        // incoming-call notification + active-call
                        // header — operators want callsigns, not the
                        // wire-protocol identifiers.
                        val callerName = peerDisplayName(fromSession.toInt())
                        Log.i(
                            TAG,
                            "REQUEST_CALL: ringing operator — channelId=$channelId " +
                                "callerCallsign='$callerName' callerSession=$fromSession",
                        )
                        try {
                            onIncomingCallRequest(callerName, channelId, fromSession.toInt())
                        } catch (t: Throwable) {
                            Log.w(TAG, "onIncomingCallRequest threw", t)
                        }
                        return
                    }
                    val preCall = if (slot.joinedChannelId >= 0) slot.joinedChannelId else -1
                    notePrivateCallStarted(
                        callSlotIdx = slot.idx,
                        peerSession = fromSession.toInt(),
                        tempChannelId = channelId,
                        preCallChannelId = preCall,
                        isCaller = false,
                    )
                    Log.i(
                        TAG,
                        "REQUEST_CALL: auto-accepting on $tag — joining channel id=$channelId " +
                            "(caller session=$fromSession, will return to ch=$preCall on hangup)",
                    )
                    slot.lastSelfJoinAttemptMs = System.currentTimeMillis()
                    slot.session?.joinChannel(channelId)
                }
                "CANCEL_CALL", "REJECT_CALL" -> {
                    // Same teardown as endLocalPrivateCall — caller
                    // side just clears state, callee side also leaves
                    // the temp channel.
                    endLocalPrivateCall("$action received from peer")
                }
                "PLAY_TONE" -> {
                    Log.i(TAG, "PLAY_TONE requested by peer: '$payload'")
                    try {
                        onPlayToneRequest(payload)
                    } catch (t: Throwable) {
                        Log.w(TAG, "onPlayToneRequest threw", t)
                    }
                }
                "LINK_CHANNEL" -> {
                    // Unrelated cross-channel-bridge feature. Logged
                    // for protocol-trace completeness; XV doesn't
                    // implement channel linking and ignoring is the
                    // correct behavior.
                    Log.d(TAG, "LINK_CHANNEL signal ignored (feature not implemented): '$payload'")
                }
                else -> Log.w(TAG, "private-call signal: unknown action '$action'")
            }
        }

        override fun onVoice(
            speakerSession: Long,
            sequence: Long,
            opusPayload: ByteArray,
        ) {
            // Drop incoming audio if this slot is the non-call slot
            // during an active private call. Skips both the forward
            // path AND the in-process playback below — the operator
            // hears only the call's slot for the duration. No update
            // to talkingPeers either, so the UI doesn't surface a
            // muted-channel speaker.
            if (slot.rxMutedForCall) return
            // Forward path: if a service-side voice plant is wired up,
            // ship raw Opus across to it (decode + AudioTrack happen in
            // our APK's UID where they have the privileges to keep
            // working when ATAK is backgrounded). Skips the in-process
            // decode + playback below.
            onIncomingOpus?.let { forward ->
                forward(slot.idx, opusPayload, speakerSession, sequence)
                transportListener?.onVoiceFrame(
                    VoiceFrame(
                        opusPayload = opusPayload,
                        senderId = "mumble:${slot.idx}:$speakerSession",
                        monotonicTimestampMs = System.nanoTime() / 1_000_000,
                    ),
                )
                val now = System.currentTimeMillis()
                val prev = slot.talkingPeers.put(speakerSession, now)
                if (prev == null) {
                    transportListener?.onPeerStartedTalking("mumble:${slot.idx}:$speakerSession")
                }
                return
            }
            // Legacy in-plugin decode + playback path. Used when no
            // forward callback is configured (still useful for offline
            // tests that don't bind the service).
            val pb = playback ?: return
            val factory = opusDecoderFactory ?: return
            val decoder = slot.decoders.computeIfAbsent(speakerSession) { factory() }
            val pcm =
                try {
                    decoder.decode(opusPayload)
                } catch (t: Throwable) {
                    Log.w(TAG, "[$tag] opus decode from session=$speakerSession failed: ${t.message}")
                    return
                }
            // Both slots feed the same playback — the operator hears VS1
            // and VS2 mixed, just like a multi-VS radio.
            pb.playPcm(pcm)
            transportListener?.onVoiceFrame(
                VoiceFrame(
                    opusPayload = opusPayload,
                    senderId = "mumble:${slot.idx}:$speakerSession",
                    monotonicTimestampMs = System.nanoTime() / 1_000_000,
                ),
            )
            val now = System.currentTimeMillis()
            val prev = slot.talkingPeers.put(speakerSession, now)
            if (prev == null) {
                transportListener?.onPeerStartedTalking("mumble:${slot.idx}:$speakerSession")
            }
        }
    }

    companion object {
        private const val TAG = "XvMumble"

        // How long after a peer's last voice frame we still consider them
        // "talking" for the UI indicator. Mumble Opus frames are typically
        // 20ms so 600ms = ~30 frames; long enough to ride out a brief
        // pause mid-utterance, short enough to drop off snappily on
        // PTT-up. The terminator frame would let us be more precise but
        // arrival isn't guaranteed (lost frame = stuck indicator).
        private const val TALKING_TTL_MS = 600L

        // Display name for Mumble's Root channel (id=0). Operators
        // see it as "Lobby" — the always-available pre-tactical
        // staging channel. Server-side name is unchanged.
        const val LOBBY_DISPLAY_NAME = "Lobby"

        // L1: window after a self-initiated joinChannel during which
        // any incoming channel-change UserState is treated as
        // self-initiated (no admin-move toast). Set generously: real
        // server move-acks land within 100-500 ms, but a slow OTS
        // under load can push that to a couple seconds. 5s leaves
        // generous margin without conflating admin moves that happen
        // to land seconds after the operator's own join.
        private const val SELF_JOIN_GRACE_MS: Long = 5_000L

        // FIX 2: secondary slot UsernameInUse-rotation retry schedule.
        // Three attempts at 2s/4s/8s before giving up and marking the
        // slot dead-with-error.
        private const val SECONDARY_MAX_RETRIES = 5
        private const val SECONDARY_RETRY_BASE_MS = 2_000
    }
}
