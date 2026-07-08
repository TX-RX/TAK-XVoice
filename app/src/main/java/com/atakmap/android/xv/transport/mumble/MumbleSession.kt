package com.atakmap.android.xv.transport.mumble

import android.util.Log
import androidx.annotation.VisibleForTesting
import com.atakmap.android.xv.transport.VxCompat
import com.google.protobuf.MessageLite
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.net.SocketException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLSocket
import mumble.MumbleProto

// Phase 2 Mumble session: TLS connect (via ATAK enrollment cert), Version +
// Authenticate, then a read loop that decodes incoming protobuf frames and
// hands them to the listener. Voice TX/RX comes next phase.
//
// Threading: connect() spawns a single read thread that owns the socket and
// blocks in DataInputStream.readFully. Listener callbacks fire on that thread.
//
// Wire framing (Mumble TCP control channel):
//   uint16 BE type | uint32 BE length | <length> bytes protobuf payload
class MumbleSession(
    private val host: String,
    private val port: Int,
    private val listener: Listener,
    private val takServerHost: String = host,
    // Deterministic per-(device, slot) suffix supplied by the caller.
    // Used both as the username suffix on Mumble (see
    // MumbleAuth.mumbleUsername) and as the thread name suffix for
    // diagnostic logs. VS1 and VS2 pass two distinct suffixes from
    // MumbleInstallId.primarySuffix / .secondarySuffix so each slot is
    // an independent stable identity on Murmur.
    private val slotSuffix: String = "VS1",
    private val vxCompat: VxCompat = VxCompat.OFF,
    // ATAK device UID, published as Mumble UserState.comment after
    // ServerSync when vxCompat != OFF. VX clients use this to map a Mumble
    // session back to an ATAK contact for the call-button UI.
    private val deviceUid: String? = null,
) {
    interface Listener {
        fun onConnecting() {}

        fun onConnected(welcomeText: String?)

        fun onChannel(channel: MumbleProto.ChannelState)

        /**
         * A channel was removed server-side (Murmur fires this when a
         * channel is deleted, including temp channels Mumble auto-cleans
         * after the last member leaves). Listeners should drop any
         * cached state keyed on this channelId. Default no-op for
         * back-compat with listeners that don't care.
         */
        fun onChannelRemove(channelId: Int) {}

        fun onUser(user: MumbleProto.UserState)

        fun onUserRemove(remove: MumbleProto.UserRemove)

        fun onTextMessage(msg: MumbleProto.TextMessage)

        fun onReject(reject: MumbleProto.Reject)

        fun onPermissionDenied(denied: MumbleProto.PermissionDenied)

        fun onDisconnected(reason: String?)

        fun onError(t: Throwable)

        // Voice frame from a peer. opusPayload is one Mumble Opus subframe
        // (variable length, 10-60ms of 48kHz mono audio depending on the
        // sender's encoder settings). speakerSession identifies the talker.
        fun onVoice(
            speakerSession: Long,
            sequence: Long,
            opusPayload: ByteArray,
        ) {}

        // VX-compatible private-call signaling carried in Mumble TextMessage.
        // Wire format (verified 2026-05-06 against live VX → OTS):
        //   body = "[TAK MxVx : <ACTION> ]<payload>"
        // Actions (enum atakplugin/vx/hz):
        //   REQUEST_CALL <channelId>  — invitation; payload is the temp channel id
        //   CANCEL_CALL               — caller aborted before answer, or hung up
        //   REJECT_CALL               — callee declined
        //   PLAY_TONE <args>          — ringback request
        //   LINK_CHANNEL <args>       — unrelated cross-channel-bridge feature
        // fromSession is the Mumble session of the sender (look up name/comment
        // for who's calling).
        fun onPrivateCallSignal(
            action: String,
            payload: String,
            fromSession: Long,
        ) {}
    }

    @Volatile
    private var socket: SSLSocket? = null

    @Volatile
    private var input: DataInputStream? = null

    @Volatile
    private var output: DataOutputStream? = null

    @Volatile
    private var thread: Thread? = null

    // Background threads spawned by startPing(). Held here (not just
    // anonymously started) so disconnect() can interrupt() them and
    // they exit on the InterruptedException their loops already catch,
    // instead of waiting up to 30 s for the next sleep wake-up to poll
    // the running flag. Without this they hold a strong ref to the
    // MumbleSession via the lambda capture and keep the session GC-
    // rooted past disconnect.
    @Volatile
    private var pingHeartbeatThread: Thread? = null

    @Volatile
    private var permissionRefreshThread: Thread? = null

    private val running = AtomicBoolean(false)
    private val pingThread = AtomicBoolean(false)

    // Wall-clock ms of the last byte received from the server (any
    // message type). Used by the ping watchdog to detect silent link
    // death (wifi associated but no packets flowing) faster than the
    // 30s socket SO_TIMEOUT — that takes a full read-cycle to surface,
    // this catches it within one ping interval + grace.
    @Volatile
    private var lastServerActivityMs: Long = 0L

    /**
     * Lifecycle state of this session, observable by the wrapper. The
     * wrapper consults [currentState] before issuing a new [connect]
     * (only IDLE or DISCONNECTED accepted) so two MumbleSession
     * instances for the same slot can never have parallel TLS
     * handshakes in flight. Transitions:
     *
     *   IDLE        — fresh instance, never connected.
     *   CONNECTING  — runConnection() entered; TLS handshake in flight.
     *   AUTHENTICATING — TLS up, Version/Authenticate sent, waiting
     *                    for ServerSync.
     *   READY       — ServerSync received; session is operational.
     *   DISCONNECTING — disconnect() invoked; teardown in progress.
     *   DISCONNECTED — teardown complete OR runConnection() exited.
     */
    enum class ConnectState { IDLE, CONNECTING, AUTHENTICATING, READY, DISCONNECTING, DISCONNECTED }

    private val connectState = AtomicReference(ConnectState.IDLE)

    fun currentState(): ConnectState = connectState.get()

    // Single-threaded executor for all outbound TCP writes. Keeps message
    // framing serialized AND keeps SSL writes off whatever thread happened
    // to call us — important because joinChannel/sendFrame can be invoked
    // from main looper (broadcast receiver) and ATAK has StrictMode set,
    // which throws NetworkOnMainThreadException for SSL writes.
    private val writeExecutor: ExecutorService =
        Executors.newSingleThreadExecutor { r -> Thread(r, "xv-mumble-write-$slotSuffix") }

    // Server-assigned session for our connection (received via ServerSync).
    @Volatile
    private var ourSession: Int = 0

    /** What we know about a channel from server-pushed ChannelState
     *  messages plus the response to our PermissionQuery. Until a query
     *  response lands, [participation] stays UNKNOWN and the picker
     *  treats the channel as best-case (probably enterable) — the
     *  query result resolves it to one of the three real states. */
    data class ChannelInfo(
        val id: Int,
        val name: String,
        val participation: Participation,
    ) {
        /** Three permission tiers derived from the Mumble Enter (0x4)
         *  and Speak (0x8) ACL bits returned by PermissionQuery:
         *    PARTICIPATE — can join AND transmit (both bits set)
         *    LISTEN      — can join, can't speak (Enter only; ACL or
         *                  direction-enforcement suppress)
         *    UNAUTHORIZED — can't join (no Enter)
         *    UNKNOWN     — query not yet answered. Treated as
         *                  probably-enterable for sort purposes; the
         *                  picker shows them with the rest until the
         *                  real verdict arrives, then re-renders. */
        enum class Participation {
            PARTICIPATE,
            LISTEN,
            UNAUTHORIZED,
            UNKNOWN,
        }
    }

    // Channel directory populated from ChannelState messages. The server
    // sends partial ChannelStates (an update for one field at a time),
    // so we merge incoming fields into the existing entry rather than
    // overwriting wholesale — otherwise a permission update with no name
    // would erase the name we already had.
    private val channels = java.util.concurrent.ConcurrentHashMap<Int, ChannelInfo>()

    fun ourSessionId(): Int = ourSession

    fun channelIdByName(name: String): Int? {
        // FIX 9: exact-match only (case-insensitive). Substring fallback
        // caused VS1 and VS2 to land on the same channel via different
        // name paths — e.g. "REACT" matched "REACT (operations)" via
        // contains() so spinUpSecondary's collision check passed for
        // distinct typed names that resolved to the same channel id.
        // Operators picking from the directory always see exact channel
        // names; substring resolution was a misfeature, not a UX
        // affordance.
        val needle = name.lowercase()
        return channels.entries.firstOrNull { it.value.name.lowercase() == needle }?.key
    }

    fun channelNameById(id: Int): String? = channels[id]?.name

    /** Snapshot of the channel directory as currently known. Reflects
     *  every ChannelState we've received this session; channels removed
     *  mid-session via ChannelRemove are evicted from this map and from
     *  permission/participation caches. */
    fun allChannels(): Map<Int, ChannelInfo> = HashMap(channels)

    private fun sendPermissionQuery(channelId: Int) {
        if (ourSession == 0) return
        val q =
            MumbleProto.PermissionQuery
                .newBuilder()
                .setChannelId(channelId)
                .setFlush(false)
                .build()
        sendMessage(MumbleMessageType.PERMISSION_QUERY, q)
    }

    private fun queueInitialPermissionQueries() {
        // ChannelStates almost always arrive before ServerSync; this
        // catches the bulk of them in one pass right after we get our
        // session id. New ChannelStates arriving later are queried
        // individually in the CHANNEL_STATE dispatch path.
        val ids = channels.keys.toList()
        Log.i(TAG, "queueInitialPermissionQueries: probing ${ids.size} channel(s)")
        for (id in ids) {
            sendPermissionQuery(id)
        }
    }

    private fun applyPermissionsResult(
        channelId: Int,
        perms: Int,
    ) {
        val hasEnter = (perms and PERM_ENTER) != 0
        val hasSpeak = (perms and PERM_SPEAK) != 0
        val level =
            when {
                !hasEnter -> ChannelInfo.Participation.UNAUTHORIZED
                hasSpeak -> ChannelInfo.Participation.PARTICIPATE
                else -> ChannelInfo.Participation.LISTEN
            }
        val prev = channels[channelId]
        if (prev == null) {
            // Permission verdict for a channel we haven't named yet —
            // stash it so the next ChannelState can pick it up. Rare;
            // logged so we notice if it becomes common.
            Log.i(TAG, "PermissionQuery result for unknown channel=$channelId perms=0x${Integer.toHexString(perms)}")
            return
        }
        if (prev.participation == level) return
        channels[channelId] =
            prev.copy(participation = level)
        Log.i(
            TAG,
            "channel '${prev.name}' (id=$channelId) → $level " +
                "(perms=0x${Integer.toHexString(perms)} enter=$hasEnter speak=$hasSpeak)",
        )
    }

    // session id → display name. Populated from UserState messages so
    // RX UI ("who's talking") can resolve a session id to a callsign.
    private val users = java.util.concurrent.ConcurrentHashMap<Int, String>()

    fun userNameById(id: Int): String? = users[id]

    internal fun rememberUserName(
        id: Int,
        name: String,
    ) {
        if (name.isNotBlank()) users[id] = name
    }

    internal fun forgetUser(id: Int) {
        users.remove(id)
    }

    // registerSecondaryVoiceTarget + SECONDARY_TARGET_ID removed.
    // VS2 runs as a second full Mumble session and channel-speaks via
    // target=0 (normal-talk in its own joined channel) — no whisper
    // target needed. The old machinery was misleading dead code.

    /**
     * Register the direct-call VoiceTarget so frames sent with
     * target=[DIRECT_CALL_TARGET_ID] are whispered to [peerSession]
     * instead of channel-routed. Re-callable to retarget.
     *
     * Returns false if not yet authenticated. No positive ack from the
     * server — observable failure is PermissionDenied or silent drop.
     */
    fun registerDirectCallTarget(peerSession: Int): Boolean {
        if (ourSession == 0) {
            Log.w(TAG, "registerDirectCallTarget($peerSession) before ServerSync — deferring")
            return false
        }
        val target =
            MumbleProto.VoiceTarget.Target
                .newBuilder()
                .addSession(peerSession)
                .build()
        val vt =
            MumbleProto.VoiceTarget
                .newBuilder()
                .setId(DIRECT_CALL_TARGET_ID)
                .addTargets(target)
                .build()
        Log.i(TAG, "VoiceTarget id=$DIRECT_CALL_TARGET_ID → session=$peerSession (direct call)")
        sendMessage(MumbleMessageType.VOICE_TARGET, vt)
        return true
    }

    /** Clear the direct-call VoiceTarget so frames sent with
     *  target=DIRECT_CALL_TARGET_ID after this return don't reach the
     *  peer we were whispering to.
     *
     *  FIX 8: send a VoiceTarget with id=2 AND a single Target whose
     *  session id is 0 (an invalid Mumble session — sessions are
     *  assigned 1+ at registration). Some Murmur builds interpret a
     *  VoiceTarget message with an empty `targets` list as a no-op
     *  (silently keeping the prior registration), rather than the
     *  intended "drop the registration." A target containing only an
     *  invalid session id leaves the registration in place but
     *  routes audio nowhere — server-side equivalent of a
     *  null-routed target. Verified that the protobuf accepts this
     *  shape (Target.session is repeated uint32; 0 is a legal field
     *  value, just not a legal session id at the application layer).
     *
     *  The "ideal" fix would be a Murmur-side bug report and an
     *  empty-targets clear, but that's many releases away from
     *  reaching production deployments.
     */
    fun clearDirectCallTarget(): Boolean {
        if (ourSession == 0) return false
        val nullTarget =
            MumbleProto.VoiceTarget.Target
                .newBuilder()
                .addSession(0)
                .build()
        val vt =
            MumbleProto.VoiceTarget
                .newBuilder()
                .setId(DIRECT_CALL_TARGET_ID)
                .addTargets(nullTarget)
                .build()
        Log.i(TAG, "VoiceTarget id=$DIRECT_CALL_TARGET_ID cleared (null-route via session=0 target)")
        sendMessage(MumbleMessageType.VOICE_TARGET, vt)
        return true
    }

    private val txSequence =
        java.util.concurrent.atomic
            .AtomicLong(0)

    // Reset the voice-packet sequence at the start of each PTT burst.
    // Mumble servers expect monotonic sequence within an audio burst;
    // continuing from the previous burst's high number can cause some
    // server implementations to flag packets as out-of-order/duplicate.
    fun resetVoiceSequence() {
        val prev = txSequence.getAndSet(0)
        Log.i(TAG, "voice sequence reset (was $prev, now 0)")
    }

    // Diagnostic: send a TextMessage to the current channel. If this
    // appears in other clients' chat windows, our TLS + protobuf send
    // path is healthy — narrows the voice-packet failure to wire format
    // or server-side voice routing.
    fun sendChatTest(
        message: String,
        channelId: Int,
    ) {
        if (ourSession == 0) return
        val m =
            MumbleProto.TextMessage
                .newBuilder()
                .setMessage(message)
                .addChannelId(channelId)
                .build()
        Log.i(TAG, "send TextMessage to channel $channelId: '$message'")
        sendMessage(MumbleMessageType.TEXT_MESSAGE, m)
    }

    /**
     * Send a VX-format private-call signal as a Mumble TextMessage.
     * Body becomes `[TAK MxVx : <ACTION> ]<payload>` — the exact wire
     * format VX clients emit + parse. Set [toSession] to peer-unicast
     * (REQUEST_CALL, REJECT_CALL targeted at the caller); leave null to
     * channel-broadcast on [channelId] (CANCEL_CALL announced to the
     * temp channel's other party).
     *
     * Returns false if we're not yet authenticated (no session) — caller
     * should retry after onConnected fires.
     */
    fun sendPrivateCallSignal(
        action: String,
        payload: String,
        toSession: Int? = null,
        channelId: Int? = null,
    ): Boolean {
        if (ourSession == 0) {
            Log.w(TAG, "sendPrivateCallSignal($action) before ServerSync — dropped")
            return false
        }
        if (toSession == null && channelId == null) {
            Log.w(TAG, "sendPrivateCallSignal($action) needs either toSession or channelId")
            return false
        }
        val body = "[TAK MxVx : $action ]$payload"
        val builder =
            MumbleProto.TextMessage
                .newBuilder()
                .setMessage(body)
        if (toSession != null) builder.addSession(toSession)
        if (channelId != null) builder.addChannelId(channelId)
        Log.i(
            TAG,
            "send VX signal action=$action payload='$payload' " +
                "toSession=$toSession channelId=$channelId",
        )
        sendMessage(MumbleMessageType.TEXT_MESSAGE, builder.build())
        return true
    }

    /**
     * Ask the server to create a new channel. Used by Phase E direct
     * calls to spin up a temporary 2-person private channel matching
     * VX's wire shape: `parent=0`, `temporary=true`, `maxUsers=2`,
     * `enterRestricted=true`. Server may reject (OTS currently
     * disallows user-initiated channel creation); caller must observe
     * the eventual ChannelState (or its absence) to know if it landed.
     *
     * Returns false if we're not authenticated. True only means we
     * sent the request — server-side success is asynchronous.
     */
    fun sendChannelState(
        name: String,
        parent: Int = 0,
        description: String = "",
        temporary: Boolean = false,
        maxUsers: Int = 0,
        enterRestricted: Boolean = false,
    ): Boolean {
        if (ourSession == 0) {
            Log.w(TAG, "sendChannelState('$name') before ServerSync — dropped")
            return false
        }
        val builder =
            MumbleProto.ChannelState
                .newBuilder()
                .setParent(parent)
                .setName(name)
                .setTemporary(temporary)
        if (description.isNotEmpty()) builder.setDescription(description)
        if (maxUsers > 0) builder.setMaxUsers(maxUsers)
        if (enterRestricted) builder.setIsEnterRestricted(true)
        Log.i(
            TAG,
            "send ChannelState create: name='$name' parent=$parent temp=$temporary " +
                "maxUsers=$maxUsers enterRestricted=$enterRestricted",
        )
        sendMessage(MumbleMessageType.CHANNEL_STATE, builder.build())
        return true
    }

    // Send one Opus voice frame to the current Mumble channel. Server
    // routes it to other users in our channel based on target=0
    // (normal talk). Caller is responsible for SCO state — we just frame
    // and ship.
    fun sendVoice(
        opusPayload: ByteArray,
        target: Int = MumbleVoicePacket.TARGET_NORMAL_TALK,
        terminator: Boolean = false,
    ) {
        if (ourSession == 0) {
            Log.e(TAG, "sendVoice DROPPED — ourSession=0 (not authenticated yet)")
            return
        }
        val seq = txSequence.incrementAndGet()
        val packetBytes = MumbleVoicePacket.buildOutbound(target, seq, opusPayload, terminator)
        // Per-frame send logs were Log.i — at ~100 frames/sec while
        // transmitting that's 200 INFO lines/sec for a routine PTT
        // burst, drowning every other XV log in the buffer. Drop to
        // Log.v (filtered out by default on production loggers).
        // Field-debug capture still gets them via `adb logcat
        // XvMumble:V *:I` when needed. Audit M17.
        if (Log.isLoggable(TAG, Log.VERBOSE)) {
            Log.v(TAG, "sendVoice: seq=$seq target=$target opus=${opusPayload.size}B terminator=$terminator ourSession=$ourSession")
            val hex = packetBytes.take(Math.min(32, packetBytes.size)).joinToString(" ") { "%02X".format(it) }
            Log.v(
                TAG,
                "TX frame seq=$seq target=$target opus=${opusPayload.size}B " +
                    "wireBytes=${packetBytes.size} hex=[$hex]",
            )
        }
        // UDPTunnel is special: send raw voice packet bytes, NOT a protobuf wrapper.
        // See Humla's HumlaTCP.sendMessage(byte[], int, HumlaTCPMessageType) for reference.
        sendRawMessage(MumbleMessageType.UDP_TUNNEL, packetBytes)
    }

    fun joinChannel(channelId: Int) {
        if (ourSession == 0) {
            Log.w(TAG, "joinChannel($channelId) before ServerSync — ignoring")
            return
        }
        Log.i(TAG, "join channel id=$channelId")
        // CLIENT-side UserState only sets self_* fields. mute/deaf/suppress
        // are admin-set; sending those from a non-admin client makes the
        // server reject the whole UserState. Mumla/Plumble keep this minimal.
        val u =
            MumbleProto.UserState
                .newBuilder()
                .setSession(ourSession)
                .setChannelId(channelId)
                .setSelfMute(false)
                .setSelfDeaf(false)
                .build()
        try {
            sendMessage(MumbleMessageType.USER_STATE, u)
        } catch (t: Throwable) {
            Log.w(TAG, "joinChannel send failed", t)
        }
    }

    fun connect() {
        // The wrapper's executor pre-checks currentState before calling
        // here, but defend in depth — a callsite that doesn't go through
        // the executor still gets one-shot semantics.
        val prior = connectState.getAndSet(ConnectState.CONNECTING)
        if (prior != ConnectState.IDLE && prior != ConnectState.DISCONNECTED) {
            Log.w(TAG, "connect() called in state=$prior — refusing")
            connectState.set(prior)
            return
        }
        if (running.getAndSet(true)) {
            Log.w(TAG, "already running")
            connectState.set(prior)
            return
        }
        thread = Thread({ runConnection() }, "xv-mumble-$slotSuffix").also { it.start() }
    }

    fun disconnect() {
        // Idempotent: a second disconnect after the first has fully
        // settled is a no-op rather than an error.
        val prior = connectState.getAndSet(ConnectState.DISCONNECTING)
        if (prior == ConnectState.DISCONNECTING || prior == ConnectState.DISCONNECTED) {
            // Still ensure quiescence — caller may be polling
            // awaitFullyDisconnected() after this returns.
            Log.i(TAG, "disconnect() in state=$prior — no-op (already torn down)")
            connectState.set(ConnectState.DISCONNECTED)
            return
        }
        Log.i(TAG, "disconnect() called — shutting down session for $host:$port (was $prior)")
        running.set(false)
        pingThread.set(false)

        // Wake the heartbeat + permission-refresh threads out of their
        // long Thread.sleep loops so they exit promptly. Without these
        // interrupts they'd stay alive (holding a strong ref to this
        // session via lambda capture) until the next sleep wake-up,
        // which on the permission-refresh thread is up to 30 s away
        // — long enough that a quick connect-disconnect-connect cycle
        // accumulates ghost threads pumping stale state. Their loops
        // already catch InterruptedException.
        pingHeartbeatThread?.interrupt()
        pingHeartbeatThread = null
        permissionRefreshThread?.interrupt()
        permissionRefreshThread = null

        // Close the socket first to wake up any blocked reads
        try {
            socket?.close()
            Log.i(TAG, "socket closed")
        } catch (t: Throwable) {
            Log.w(TAG, "socket close failed: ${t.message}")
        }

        // Interrupt the read thread and wait for it to exit. 500ms gives
        // a blocked TLS read time to surface its EOF / SocketException
        // and unwind through the readLoop's finally — important because
        // the wrapper sequences "wait for VS1 fully gone -> connect VS2"
        // and a short join lets the read thread linger past wrapper
        // acquire, producing the very parallel-handshake the server-
        // wide-kick incident traced to.
        val readThread = thread
        readThread?.interrupt()
        try {
            readThread?.join(READ_THREAD_JOIN_MS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }

        // Shutdown the write executor (gives pending writes 400ms — long
        // enough for the final LEAVE_CHANNEL / disconnect-courtesy writes
        // to flush over a slow TLS link). Once this returns the
        // executor's task queue is empty AND its worker thread has
        // exited — disconnect() callers therefore observe a truly-
        // quiesced session and don't need their own settle-sleep.
        try {
            writeExecutor.shutdown()
            if (!writeExecutor.awaitTermination(
                    WRITE_EXECUTOR_AWAIT_MS,
                    java.util.concurrent.TimeUnit.MILLISECONDS,
                )
            ) {
                writeExecutor.shutdownNow()
            }
            Log.i(TAG, "write executor stopped")
        } catch (t: Throwable) {
            Log.w(TAG, "write executor shutdown failed: ${t.message}")
            writeExecutor.shutdownNow()
        }

        // Belt-and-braces: force the socket closed even if an exception
        // earlier in this method left it open. The runConnection's
        // finally also closes, but if disconnect() raced ahead of that
        // (typical case — disconnect on UI thread while read thread is
        // unwinding) the read-thread join above can return after the
        // close-in-finally has already run OR before; either way a
        // double close is harmless and a single guaranteed close here
        // prevents a leaked FD.
        try {
            socket?.close()
        } catch (_: Throwable) {
        }

        // Clear ourSession AND socket so isConnected()/ourSessionId()
        // return their "torn down" answers immediately, even before
        // the read thread's finally clause has run.
        ourSession = 0
        socket = null
        input = null
        output = null

        connectState.set(ConnectState.DISCONNECTED)
        Log.i(TAG, "disconnect() complete")
    }

    /**
     * Block the caller (up to [timeoutMs]) until this session is fully
     * torn down: state == DISCONNECTED AND ourSessionId() == 0 AND
     * !isConnected(). The wrapper uses this before starting VS2 to make
     * sure VS1's prior session is genuinely gone server-side too
     * (otherwise VS2's auth races into a still-tracked username).
     *
     * Returns true on full quiescence, false on timeout (the caller
     * should treat that as "best effort" and proceed).
     */
    fun awaitFullyDisconnected(timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (connectState.get() == ConnectState.DISCONNECTED &&
                ourSession == 0 &&
                !isConnected()
            ) {
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
            "awaitFullyDisconnected: timed out after ${timeoutMs}ms — " +
                "state=${connectState.get()} ourSession=$ourSession isConnected=${isConnected()}",
        )
        return false
    }

    fun isConnected(): Boolean {
        val st = connectState.get()
        if (st == ConnectState.DISCONNECTING || st == ConnectState.DISCONNECTED) return false
        val s = socket ?: return false
        return s.isConnected && !s.isClosed
    }

    private fun runConnection() {
        try {
            listener.onConnecting()
            val callsign = MumbleAuth.deviceCallsign()
            // slotSuffix is the deterministic per-(device, slot) suffix
            // supplied by the caller — see
            // MumbleInstallId.primarySuffix / .secondarySuffix.
            val username =
                MumbleAuth.mumbleUsername(
                    callsign = callsign,
                    slotSuffix = slotSuffix,
                )
            Log.i(TAG, "connect $host:$port as '$username'")

            val sock =
                MumbleAuth.connectTls(host, port, takServerHost).apply {
                    // SO_TIMEOUT tightened from 30 s to 20 s after the
                    // 2026-06 handoff hardening: the read side now
                    // surfaces dead links via SO_TIMEOUT in ~20 s
                    // instead of ~30 s, halving the worst-case freeze
                    // when the stale-link watchdog isn't first to fire
                    // (e.g. the server happens to send one byte right
                    // at the swap moment, pushing lastServerActivityMs
                    // forward and resetting the STALE_LINK timer just
                    // before the link actually dies).
                    soTimeout = SOCKET_SO_TIMEOUT_MS
                    startHandshake()
                }
            // Critical race guard: if disconnect() was called while we
            // were in the multi-second TLS handshake, [running] is now
            // false. Bail before sendVersion/sendAuthenticate so the
            // stale handshake doesn't register a second user with our
            // stable username — that would put two parallel sessions
            // for the same operator on Murmur, and Murmur kicks both
            // (and can disrupt other users on the server during the
            // duplicate-cleanup cascade). Discovered 2026-05-10 after
            // a server-wide kick incident traced to two MumbleSession
            // instances racing through their handshake during a fast
            // reconnect cycle.
            if (!running.get()) {
                Log.w(TAG, "runConnection: disconnected during TLS handshake — aborting before Version")
                try {
                    sock.close()
                } catch (_: Throwable) {
                }
                return
            }
            socket = sock
            input = DataInputStream(sock.inputStream)
            output = DataOutputStream(sock.outputStream)
            Log.i(TAG, "TLS handshake complete; cipher=${sock.session.cipherSuite}")
            // CONNECTING -> AUTHENTICATING. The wrapper observes
            // currentState() between this point and ServerSync to gate
            // re-entry; only DISCONNECTED/IDLE accept a new connect.
            connectState.compareAndSet(ConnectState.CONNECTING, ConnectState.AUTHENTICATING)

            sendVersion()
            sendAuthenticate(username)

            startPing()
            readLoop()
        } catch (t: Throwable) {
            Log.e(TAG, "connection error", t)
            listener.onError(t)
        } finally {
            running.set(false)
            pingThread.set(false)
            try {
                socket?.close()
            } catch (_: Throwable) {
            }
            socket = null
            input = null
            output = null
            // Final state regardless of how we got here. Idempotent with
            // disconnect()'s own DISCONNECTED set.
            connectState.set(ConnectState.DISCONNECTED)
            listener.onDisconnected("read loop exit")
        }
    }

    private fun sendVersion() {
        // Advertise as Mumble client v1.3.4 (0x010304) so Murmur 1.3 doesn't
        // gate features. release/os carry the VX-compat decision: VX's
        // call-button UI gates on Version.release == "ATAK_Vx" (verified
        // 2026-05-06 against live OTS).
        val release =
            when (vxCompat) {
                VxCompat.OFF -> "XV/$XV_VERSION"
                VxCompat.HYBRID -> "ATAK_Vx XV/$XV_VERSION"
                VxCompat.STRICT -> "ATAK_Vx"
            }
        val osStr = if (vxCompat == VxCompat.OFF) "Android" else "ATAK"
        Log.i(TAG, "sendVersion: vxCompat=$vxCompat release='$release' os='$osStr'")
        val v =
            MumbleProto.Version
                .newBuilder()
                .setVersion(0x010304)
                .setRelease(release)
                .setOs(osStr)
                .setOsVersion(android.os.Build.VERSION.RELEASE ?: "unknown")
                .build()
        sendMessage(MumbleMessageType.VERSION, v)
    }

    private fun maybeDispatchVxSignal(m: MumbleProto.TextMessage) {
        val parsed = parseVxSignal(m.message) ?: return
        val (action, payload) = parsed
        Log.i(TAG, "VX signal: action='$action' payload='$payload' from=${m.actor}")
        try {
            listener.onPrivateCallSignal(action, payload, m.actor.toLong())
        } catch (t: Throwable) {
            Log.w(TAG, "onPrivateCallSignal listener threw", t)
        }
    }

    // Push our ATAK device UID as Mumble UserState.comment so other VX
    // clients can resolve session→UID→ATAK-contact for the call-button UI.
    // VX itself bundles this with channel-move UserStates, which leaves
    // freshly-connected users with empty comment until they touch a channel
    // (verified — DhirenTablet had comment='' until moved). XV publishes
    // unconditionally right after ServerSync to avoid that race.
    private fun publishCommentBeacon() {
        if (vxCompat == VxCompat.OFF) return
        val uid = deviceUid
        if (uid.isNullOrBlank()) {
            Log.w(TAG, "publishCommentBeacon skipped — no deviceUid")
            return
        }
        if (ourSession == 0) {
            Log.w(TAG, "publishCommentBeacon skipped — ourSession=0")
            return
        }
        val u =
            MumbleProto.UserState
                .newBuilder()
                .setSession(ourSession)
                .setComment(uid)
                .build()
        Log.i(TAG, "publishCommentBeacon: comment='$uid'")
        try {
            sendMessage(MumbleMessageType.USER_STATE, u)
        } catch (t: Throwable) {
            Log.w(TAG, "publishCommentBeacon send failed", t)
        }
    }

    private fun sendAuthenticate(username: String) {
        val a =
            MumbleProto.Authenticate
                .newBuilder()
                .setUsername(username)
                .setPassword("") // TLS cert is the credential; this field unused
                .setOpus(true)
                .build()
        sendMessage(MumbleMessageType.AUTHENTICATE, a)
    }

    private fun startPing() {
        if (!pingThread.compareAndSet(false, true)) return
        pingHeartbeatThread =
            Thread({
                try {
                    while (pingThread.get() && running.get()) {
                        Thread.sleep(PING_INTERVAL_MS)
                        if (!running.get()) break
                        // Silent-link-death watchdog: if we've sent
                        // pings but not heard ANY byte from the server
                        // for STALE_LINK_TIMEOUT_MS, the TCP is wedged
                        // (wifi associated but routing broken, IMS-only
                        // cell with no internet, captive-portal redirect
                        // back into a tunnel that drops our traffic).
                        // Close the socket so the read loop unwinds and
                        // the reconnect path kicks in immediately —
                        // otherwise we wait up to 30 s for SO_TIMEOUT.
                        val sinceRx = System.currentTimeMillis() - lastServerActivityMs
                        if (lastServerActivityMs > 0 && sinceRx > STALE_LINK_TIMEOUT_MS) {
                            Log.w(
                                TAG,
                                "stale link: ${sinceRx}ms since last server byte — closing socket to force reconnect",
                            )
                            try {
                                socket?.close()
                            } catch (_: Throwable) {
                            }
                            break
                        }
                        val p =
                            MumbleProto.Ping
                                .newBuilder()
                                .setTimestamp(System.currentTimeMillis() * 1000)
                                .build()
                        sendMessage(MumbleMessageType.PING, p)
                    }
                } catch (_: InterruptedException) {
                } catch (t: Throwable) {
                    Log.w(TAG, "ping thread error", t)
                }
            }, "xv-mumble-ping-$slotSuffix").also { it.start() }

        // Periodic permission re-evaluation. Catches admin-side ACL
        // grants/revocations (Enter/Speak bits) without requiring the
        // operator to reconnect. NOTE: OTS direction enforcement
        // (suppress flag) is per-channel-membership and only refreshes
        // when we actually rejoin the channel, so this re-query refreshes
        // ACL truth but not OTS direction state — that one still needs
        // a channel-level rejoin to update.
        permissionRefreshThread =
            Thread({
                try {
                    while (pingThread.get() && running.get()) {
                        Thread.sleep(PERMISSION_REFRESH_INTERVAL_MS)
                        if (!running.get() || ourSession == 0) continue
                        val ids = channels.keys.toList()
                        if (ids.isEmpty()) continue
                        Log.i(TAG, "periodic permission refresh: re-querying ${ids.size} channel(s)")
                        for (id in ids) {
                            sendPermissionQuery(id)
                        }
                    }
                } catch (_: InterruptedException) {
                } catch (t: Throwable) {
                    Log.w(TAG, "permission refresh thread error", t)
                }
            }, "xv-mumble-permrefresh-$slotSuffix").also { it.start() }
    }

    private val sentPacketCounters = java.util.concurrent.ConcurrentHashMap<Int, java.util.concurrent.atomic.AtomicLong>()

    private fun sendMessage(
        type: Int,
        msg: MessageLite,
    ) {
        val payload = msg.toByteArray()
        sendRawMessage(type, payload)
    }

    // Send raw bytes as the message body (used for UDPTunnel voice packets
    // which are NOT protobuf-serialized — the body IS the raw UDP packet).
    private fun sendRawMessage(
        type: Int,
        payload: ByteArray,
    ) {
        writeExecutor.execute {
            val out =
                output ?: run {
                    Log.e(TAG, "send ${MumbleMessageType.nameOf(type)} dropped — output closed")
                    return@execute
                }
            try {
                out.writeShort(type)
                out.writeInt(payload.size)
                out.write(payload)
                out.flush()
                val counter =
                    sentPacketCounters.computeIfAbsent(type) {
                        java.util.concurrent.atomic
                            .AtomicLong(0)
                    }
                val n = counter.incrementAndGet()
                // Log first 10 voice packets and every 50th after, all other types on first + every 100
                val shouldLog =
                    if (type == MumbleMessageType.UDP_TUNNEL) {
                        n <= 10L || n % 50 == 0L
                    } else {
                        n == 1L || n % 100 == 0L
                    }
                if (shouldLog) {
                    Log.i(TAG, "wrote ${MumbleMessageType.nameOf(type)} #$n (${payload.size + 6}B on wire)")
                }
            } catch (t: IOException) {
                Log.e(TAG, "send ${MumbleMessageType.nameOf(type)} FAILED: ${t.message}", t)
                Log.w(TAG, "send ${MumbleMessageType.nameOf(type)} failed: ${t.message}")
            } catch (t: Throwable) {
                Log.w(TAG, "send ${MumbleMessageType.nameOf(type)} unexpected error", t)
            }
        }
    }

    private fun readLoop() {
        val ins = input ?: return
        lastServerActivityMs = System.currentTimeMillis()
        while (running.get()) {
            val type =
                try {
                    ins.readUnsignedShort()
                } catch (_: EOFException) {
                    break
                } catch (e: SocketException) {
                    if (running.get()) Log.w(TAG, "socket read: ${e.message}")
                    break
                }
            val length = ins.readInt()
            if (length < 0 || length > MAX_MSG_SIZE) {
                Log.e(TAG, "absurd payload length $length on type=$type — bailing")
                break
            }
            val payload = ByteArray(length)
            ins.readFully(payload)
            lastServerActivityMs = System.currentTimeMillis()
            dispatch(type, payload)
        }
    }

    /**
     * Test seam: drive the dispatcher with hand-crafted protobuf bytes
     * without standing up the socket or read thread. Used by
     * MumbleSessionDispatchTest to pin message → listener-callback
     * routing.
     */
    @VisibleForTesting
    internal fun dispatchForTest(
        type: Int,
        payload: ByteArray,
    ) = dispatch(type, payload)

    /**
     * Test seam: force the watchdog's "last server byte at" timestamp.
     * Used by MumbleSessionStaleLinkTest to drive a fake clock without
     * standing up a real socket or read thread.
     */
    @VisibleForTesting
    internal fun setLastServerActivityForTest(epochMs: Long) {
        lastServerActivityMs = epochMs
    }

    /**
     * Test seam: ask the watchdog if the link is currently considered
     * stale, given an injected "now" wall-clock. Mirrors the exact
     * condition in [startPing]'s heartbeat loop so a regression in
     * either the constant or the comparison surface here too.
     *
     * Returns true iff a non-zero [lastServerActivityMs] has been seen
     * AND the gap to [nowMs] has exceeded [STALE_LINK_TIMEOUT_MS]. The
     * production loop uses the same guard (`lastServerActivityMs > 0`)
     * so a freshly-opened session that hasn't reached readLoop yet is
     * not falsely declared stale.
     */
    @VisibleForTesting
    internal fun isLinkStaleForTest(nowMs: Long): Boolean {
        val last = lastServerActivityMs
        if (last <= 0L) return false
        return (nowMs - last) > STALE_LINK_TIMEOUT_MS
    }

    private fun dispatch(
        type: Int,
        payload: ByteArray,
    ) {
        try {
            when (type) {
                MumbleMessageType.VERSION -> {
                    val v = MumbleProto.Version.parseFrom(payload)
                    Log.i(TAG, "server Version ${v.release} (proto=${Integer.toHexString(v.version)})")
                }
                MumbleMessageType.SERVER_SYNC -> {
                    val s = MumbleProto.ServerSync.parseFrom(payload)
                    Log.i(TAG, "ServerSync session=${s.session} max_bw=${s.maxBandwidth}")
                    ourSession = s.session
                    // AUTHENTICATING -> READY. The wrapper waits for
                    // currentState() == READY before starting VS2 so
                    // VS2's auth can never overlap VS1's still-pending
                    // server-side accept.
                    connectState.compareAndSet(ConnectState.AUTHENTICATING, ConnectState.READY)
                    publishCommentBeacon()
                    // Server has typically already pushed every visible
                    // ChannelState before ServerSync. Probe permissions
                    // for each so the dropdown can show the
                    // Participate / Listen / Unauthorized verdict.
                    queueInitialPermissionQueries()
                    listener.onConnected(s.welcomeText.takeIf { s.hasWelcomeText() })
                }
                MumbleMessageType.CHANNEL_STATE -> {
                    val c = MumbleProto.ChannelState.parseFrom(payload)
                    if (c.hasChannelId()) {
                        // Server sends partial updates — merge into the
                        // existing entry. A permission-only update has no
                        // name field, but we must not overwrite the name
                        // we already cached. Participation is preserved
                        // across updates; it's only set by PermissionQuery
                        // responses, never by ChannelState.
                        val prev = channels[c.channelId]
                        val merged =
                            ChannelInfo(
                                id = c.channelId,
                                name = if (c.hasName()) c.name else prev?.name ?: "",
                                participation = prev?.participation ?: ChannelInfo.Participation.UNKNOWN,
                            )
                        if (merged.name.isNotEmpty()) {
                            channels[c.channelId] = merged
                        }
                        // Probe permissions for any newly-seen channel
                        // (or if this is the first one we've heard of).
                        // Defers until ourSession is set — Murmur ignores
                        // PermissionQuery sent before ServerSync.
                        if (prev == null && ourSession != 0) {
                            sendPermissionQuery(c.channelId)
                        }
                    }
                    listener.onChannel(c)
                }
                MumbleMessageType.CHANNEL_REMOVE -> {
                    val r = MumbleProto.ChannelRemove.parseFrom(payload)
                    if (r.hasChannelId()) {
                        val removed = channels.remove(r.channelId)
                        if (removed != null) {
                            Log.i(TAG, "ChannelRemove id=${r.channelId} name='${removed.name}'")
                        }
                        listener.onChannelRemove(r.channelId)
                    }
                }
                MumbleMessageType.PERMISSION_QUERY -> {
                    val pq = MumbleProto.PermissionQuery.parseFrom(payload)
                    if (pq.hasChannelId() && pq.hasPermissions()) {
                        applyPermissionsResult(pq.channelId, pq.permissions)
                    }
                }
                MumbleMessageType.USER_STATE -> {
                    val u = MumbleProto.UserState.parseFrom(payload)
                    listener.onUser(u)
                }
                MumbleMessageType.USER_REMOVE -> {
                    val r = MumbleProto.UserRemove.parseFrom(payload)
                    listener.onUserRemove(r)
                }
                MumbleMessageType.TEXT_MESSAGE -> {
                    val m = MumbleProto.TextMessage.parseFrom(payload)
                    listener.onTextMessage(m)
                    maybeDispatchVxSignal(m)
                }
                MumbleMessageType.REJECT -> {
                    val r = MumbleProto.Reject.parseFrom(payload)
                    Log.w(TAG, "REJECT type=${r.type} reason='${r.reason}'")
                    listener.onReject(r)
                }
                MumbleMessageType.PERMISSION_DENIED -> {
                    val d = MumbleProto.PermissionDenied.parseFrom(payload)
                    Log.w(TAG, "PermissionDenied type=${d.type} reason='${d.reason}'")
                    listener.onPermissionDenied(d)
                }
                MumbleMessageType.PING -> {
                    // Server ping reply — fine, no-op
                }
                MumbleMessageType.UDP_TUNNEL -> {
                    val v = MumbleVoicePacket.parse(payload) ?: return
                    listener.onVoice(v.session, v.sequence, v.opusPayload)
                }
                MumbleMessageType.CRYPT_SETUP -> {
                    // OCB key exchange for UDP voice; we run audio over TCP
                    // tunnel (UDP_TUNNEL) for now so this is informational.
                }
                else -> {
                    Log.d(TAG, "rx ${MumbleMessageType.nameOf(type)} (${payload.size}B) — unhandled")
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "dispatch ${MumbleMessageType.nameOf(type)} failed", t)
        }
    }

    companion object {
        private const val TAG = "XvMumble"

        // Maximum payload size we'll accept on a single Mumble control
        // message. Was 8 MiB; that was over-generous — real Mumble
        // protocol messages top out at ~64 KiB for the largest
        // PermissionQuery / ChannelState bursts on busy servers. A
        // malicious or buggy server claiming `length=8_000_000` would
        // have us pre-allocate 8 MB for a single frame; a sustained
        // stream of those drives the heap into GC pressure or OOM
        // within a few hundred ms. 256 KiB is generous headroom over
        // real traffic and tight enough to make a runaway server
        // produce a single OOM-or-disconnect signal instead of
        // long-tail heap pressure.
        private const val MAX_MSG_SIZE = 256 * 1024
        private const val XV_VERSION = "0.1.5"
        private const val VX_TAG_PREFIX = "[TAK MxVx : "
        private const val VX_TAG_SUFFIX = " ]"

        /**
         * Parse a VX-compat private-call signal out of a Mumble
         * TextMessage body. Wire format: `[TAK MxVx : <ACTION> ]<payload>`.
         * Returns (action, payload) on success; null when the text is
         * not a VX signal (most messages aren't) or when the format is
         * malformed.
         *
         * Pure function — extracted so MumbleSessionDispatchTest can
         * pin the wire-format parse without standing up a MumbleSession
         * instance.
         */
        @VisibleForTesting
        internal fun parseVxSignal(text: String?): Pair<String, String>? {
            if (text == null) return null
            if (!text.startsWith(VX_TAG_PREFIX)) return null
            val end = text.indexOf(VX_TAG_SUFFIX, VX_TAG_PREFIX.length)
            if (end < 0) return null
            val action = text.substring(VX_TAG_PREFIX.length, end)
            val payload = text.substring(end + VX_TAG_SUFFIX.length)
            return action to payload
        }

        // Teardown budgets. Read-thread join must be long enough for a
        // blocked TLS read to surface its exception and unwind through
        // the readLoop finally — 500ms covers a slow link where the
        // socket close hasn't propagated to the kernel yet. Write-
        // executor await gives in-flight TCP writes time to flush over
        // the same slow link. The wrapper's awaitFullyDisconnected
        // polls every 20ms so its overall deadline is the gating
        // budget — these are inner per-resource caps.
        private const val READ_THREAD_JOIN_MS: Long = 500L
        private const val WRITE_EXECUTOR_AWAIT_MS: Long = 400L

        // VoiceTarget slot for direct (private) calls. The session voice
        // is whispered to a single peer's session id, so the call audio
        // bypasses channel routing — the caller's tactical channel
        // members never hear the call. This is what VX does (per
        // wire-trace against live OTS): caller never enters their own
        // temp channel, just creates it as a visual handshake and
        // whispers to the recipient's session.
        const val DIRECT_CALL_TARGET_ID = 2

        // Mumble channel-permission bits (from src/ACL.h in upstream
        // Mumble). PermissionQuery responses carry a bitmask of these.
        // Enter (0x4) is "may join this channel"; Speak (0x8) is "may
        // transmit voice once joined." OTS direction enforcement
        // (IN/OUT) flips Speak on/off via the suppress flag rather than
        // ACL — so a server with direction=OUT will still report
        // Speak=true here, and the operator only discovers listen-only
        // status by trying to PTT. Acceptable first-pass; refine if
        // OTS exposes direction in the wire protocol later.
        private const val PERM_ENTER = 0x4
        private const val PERM_SPEAK = 0x8

        // How often to re-poll PermissionQuery for every known channel.
        // Picks up admin-side ACL grants/revocations (Enter/Speak)
        // without requiring an operator-initiated reconnect. 30 s is
        // a balance: most permission changes propagate to the operator
        // within the next minute; bandwidth is tiny (~10 B per channel
        // per query, scales with channel count).
        private const val PERMISSION_REFRESH_INTERVAL_MS = 30_000L

        // Ping cadence. Tightened from 15 s -> 8 s in the 2026-06
        // handoff hardening pass. The Mumble client convention is
        // 15 s, but we're not a desktop client — we're a hand-held on
        // a cellular/wifi seam where silent link death is the common
        // failure mode, not server-side load. 8 s lets the watchdog
        // catch a wedged socket inside ~20 s of the actual disconnect
        // (one missed ping + one grace ping = 16 s, vs the prior
        // ~50 s). Uplink cost is ~3 extra small pings per minute per
        // session — negligible.
        private const val PING_INTERVAL_MS: Long = 8_000L

        // Watchdog ceiling. If the read side hasn't seen ANY byte from
        // the server for this long, close the socket so reconnect kicks
        // in. Tightened from 35 s -> 18 s in the 2026-06 handoff
        // hardening pass; sized at ~2× ping interval + grace (8 + 8 +
        // 2). One missed ping + round-trip slack still covers a
        // transient cell handoff that resumes mid-flight, but a true
        // dead link is detected in ~18 s instead of ~50 s. Compares
        // favorably against the 20 s socket SO_TIMEOUT (which only
        // fires after the read attempt itself times out, so worst-case
        // detection is bounded by min(STALE_LINK, SO_TIMEOUT + last
        // successful read window)).
        private const val STALE_LINK_TIMEOUT_MS: Long = 18_000L

        /** Test accessor for the watchdog ceiling — gives the unit
         *  suite a single source of truth, so dropping the constant
         *  later (or raising it) does not silently invalidate the
         *  stale-link test's threshold math. */
        @VisibleForTesting
        internal fun staleLinkTimeoutMsForTest(): Long = STALE_LINK_TIMEOUT_MS

        /** Test accessor for the ping cadence — paired with
         *  [staleLinkTimeoutMsForTest] so the test can prove the
         *  watchdog still fires inside ~2× ping cadence + grace. */
        @VisibleForTesting
        internal fun pingIntervalMsForTest(): Long = PING_INTERVAL_MS

        /** Test accessor for the SSL socket SO_TIMEOUT applied in
         *  [runConnection]. Lets the test suite assert the tightened
         *  20 s value without parsing the source. */
        @VisibleForTesting
        internal fun socketSoTimeoutMsForTest(): Int = SOCKET_SO_TIMEOUT_MS

        // Socket read timeout. Tightened from 30 s -> 20 s in the
        // 2026-06 handoff hardening pass so the read side surfaces
        // dead links inside ~20 s instead of ~30 s.
        private const val SOCKET_SO_TIMEOUT_MS: Int = 20_000
    }
}
