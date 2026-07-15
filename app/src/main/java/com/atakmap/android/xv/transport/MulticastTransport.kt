package com.atakmap.android.xv.transport

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import com.atakmap.android.xv.transport.multicast.ControlPacket
import com.atakmap.android.xv.transport.multicast.MulticastWireCodec
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * UDP multicast voice transport — one instance per (channel, group,
 * port) leg. Frames are framed/unframed by an injected
 * [MulticastWireCodec] pair, so this class is wire-format-agnostic:
 * XV-native (RTP + optional ChaCha20-Poly1305) and OpenMANET-compat
 * (raw Opus) legs differ only in the codecs the factory hands over.
 *
 * RX: datagrams are classified by [rxCodec] into voice (forwarded as
 * raw Opus via [onIncomingOpus], matching the Mumble transport's
 * pattern — decode + playback happen service-side over AIDL), control
 * packets (forwarded via [onControlMessage] for key election / bridge
 * election / discovery), or drops (counted per reason).
 *
 * TX: [sendFrame] runs the Opus payload through [txCodec] and sends
 * the resulting datagram to the group. A null encode (e.g. crypto
 * policy REQUIRED with no key yet) drops the frame and counts it.
 * [beginVoiceBurst] resets the codec's burst-relative sequence on the
 * PTT-down edge — same edge the Mumble leg resets on, which is what
 * makes cross-leg RX dedup line up.
 *
 * Loopback: our own TX datagrams may be delivered back to us (the
 * OS-level IP_MULTICAST_LOOP suppression is unreliable across Android
 * vendors), so RX drops any voice frame whose speaker key equals
 * [localSpeakerKey] before it reaches the caller.
 */
@SuppressLint("MissingPermission")
class MulticastTransport(
    override val config: TransportConfig.Multicast,
    private val context: Context?,
    private val txCodec: MulticastWireCodec,
    private val rxCodec: MulticastWireCodec,
    /** Our own RX-side speaker key (e.g. `ssrc:<hex8>`); frames matching it are looped-back TX and get dropped. */
    private val localSpeakerKey: String? = null,
    /** One received voice frame: raw Opus + stable speaker key + burst-relative sequence (null on interop legs). */
    private val onIncomingOpus: ((opus: ByteArray, speakerKey: String, seqInBurst: Int?) -> Unit)? = null,
    /** One received XVMC control-plane message. */
    private val onControlMessage: ((msg: ControlPacket.Message, sourceHost: String) -> Unit)? = null,
    // Test seam — production callers leave this at the default, which
    // builds a real [MulticastSocket] bound to the config port. The
    // unit suite replaces this with a stub-socket factory so the swap
    // path's "close and rebuild on fresh interface" behavior can be
    // verified without touching the real OS networking stack.
    private val socketFactory: (Int) -> MulticastSocket = { port -> MulticastSocket(port) },
) : VoiceTransport {
    @Volatile
    private var connected: Boolean = false

    @Volatile
    private var receiveThread: Thread? = null

    @Volatile
    private var socket: MulticastSocket? = null

    @Volatile
    private var multicastLock: WifiManager.MulticastLock? = null

    private var listener: TransportListener? = null

    @Volatile
    private var groupSocketAddress: InetSocketAddress? = null

    /** Frames dropped by TX policy (null encode). Diagnostics only. */
    val txDroppedByPolicy = AtomicLong(0)

    /** RX datagrams dropped, all reasons combined. Diagnostics only. */
    val rxDropped = AtomicLong(0)

    /** Datagrams successfully handed to the socket. Diagnostics only. */
    val txSent = AtomicLong(0)

    /** Socket sends that threw. Diagnostics only. */
    val txSendFailed = AtomicLong(0)

    // ALL socket sends go through this single thread. Callers arrive
    // from three places — binder threads (voice TX), the RX thread
    // (bridge relay), and the main-thread mesh tick (beacons, key
    // election) — and Android throws NetworkOnMainThreadException on
    // any main-thread send. That exception carries a null message and
    // was swallowed here as "multicast send failed: null", which
    // silently killed the entire mesh control plane in the 2026-07-15
    // field test: no beacons, no key exchange, split-brain channel
    // keys, every encrypted voice frame dropped as BAD_TAG.
    private val txExecutor =
        java.util.concurrent.Executors.newSingleThreadExecutor { r ->
            Thread(r, "XvMulticastTx-${config.port}")
        }

    override val isConnected: Boolean
        get() = connected

    override fun connect(listener: TransportListener) {
        this.listener = listener
        receiveThread =
            thread(start = true, name = "XvMulticast-${config.port}") {
                runReceiveLoop()
            }
    }

    private fun runReceiveLoop() {
        val l = listener ?: return
        try {
            acquireMulticastLock()
            val sock = socketFactory(config.port)
            socket = sock
            val group = InetAddress.getByName(config.groupAddress)
            groupSocketAddress = InetSocketAddress(group, config.port)
            try {
                sock.timeToLive = MULTICAST_TTL
            } catch (t: Throwable) {
                Log.w(TAG, "setting multicast TTL failed (using OS default): ${t.message}")
            }
            val intf =
                config.networkInterfaceName?.let { NetworkInterface.getByName(it) }
                    ?: defaultMulticastInterface()
            if (intf != null) {
                sock.joinGroup(InetSocketAddress(group, config.port), intf)
            } else {
                @Suppress("DEPRECATION")
                sock.joinGroup(group)
            }
            connected = true
            l.onConnected()
            Log.i(TAG, "Joined ${config.groupAddress}:${config.port} on ${intf?.name ?: "default"}")

            val talkingPeers = mutableSetOf<String>()
            val buf = ByteArray(MAX_DATAGRAM_BYTES)
            val packet = DatagramPacket(buf, buf.size)

            while (connected && !Thread.currentThread().isInterrupted) {
                packet.length = buf.size
                try {
                    sock.receive(packet)
                } catch (_: java.net.SocketException) {
                    // Socket closed during disconnect; exit cleanly.
                    break
                }
                val sourceHost = packet.address?.hostAddress ?: "unknown"
                val datagram = packet.data.copyOfRange(0, packet.length)
                when (val r = rxCodec.decodeRx(datagram, sourceHost)) {
                    is MulticastWireCodec.RxResult.Voice -> {
                        if (localSpeakerKey != null && r.speakerKey == localSpeakerKey) {
                            // Our own TX looped back by the OS — not a peer.
                            continue
                        }
                        onIncomingOpus?.invoke(r.opus, r.speakerKey, r.seqInBurst)
                        l.onVoiceFrame(
                            VoiceFrame(
                                opusPayload = r.opus,
                                senderId = r.speakerKey,
                                monotonicTimestampMs = System.nanoTime() / 1_000_000,
                            ),
                        )
                        if (talkingPeers.add(r.speakerKey)) {
                            l.onPeerStartedTalking(r.speakerKey)
                        }
                        // stoppedTalking is best-effort: raw multicast has
                        // no "frame N is the last" signal. The playback
                        // side's silence timer is the truth here.
                    }
                    is MulticastWireCodec.RxResult.Control -> {
                        onControlMessage?.invoke(r.message, sourceHost)
                    }
                    is MulticastWireCodec.RxResult.Dropped -> {
                        rxDropped.incrementAndGet()
                        // Per-reason counts would spam at line rate; one
                        // aggregate counter + occasional sampled log is
                        // enough to notice a misconfigured peer.
                        if (rxDropped.get() % DROP_LOG_SAMPLE == 1L) {
                            Log.d(TAG, "RX drop (${r.reason}) from $sourceHost — total ${rxDropped.get()}")
                        }
                    }
                }
            }
        } catch (t: Throwable) {
            if (connected) {
                Log.e(TAG, "Multicast receive loop error", t)
                l.onConnectionFailed(t)
            }
        } finally {
            cleanup()
        }
    }

    /**
     * Reset burst-relative TX state (RTP sequence, marker bit) on the
     * PTT-down edge. Mirrors MumbleTransport.beginVoiceBurst so both
     * legs' sequence numbers stay aligned for cross-leg RX dedup.
     */
    fun beginVoiceBurst() {
        txCodec.beginBurst()
    }

    override fun sendFrame(frame: VoiceFrame) {
        if (!connected) return
        // Multicast legs are bound to the primary channel; VS2 traffic
        // (slot 1) stays Mumble-only.
        if (frame.targetSlot != 0) return
        val datagram = txCodec.encodeTx(frame.opusPayload)
        if (datagram == null) {
            txDroppedByPolicy.incrementAndGet()
            return
        }
        sendRaw(datagram)
    }

    /**
     * Send pre-framed bytes to the group. Used for control-plane
     * messages ([sendControl]) and by the bridge relay path, whose
     * per-speaker codecs frame outside this class so relayed frames
     * carry the ORIGINAL speaker's SSRC rather than ours.
     */
    fun sendRaw(datagram: ByteArray) {
        try {
            txExecutor.execute { sendOnTxThread(datagram) }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            // Racing a disconnect; the leg is going away. Drop.
        }
    }

    private fun sendOnTxThread(datagram: ByteArray) {
        val sock = socket ?: return
        val target = groupSocketAddress ?: return
        try {
            sock.send(DatagramPacket(datagram, datagram.size, target))
            txSent.incrementAndGet()
        } catch (t: Throwable) {
            // Send failures during interface flaps are expected; the
            // swap path rebuilds the socket. Don't tear down for them
            // — but log the exception CLASS, not just the message:
            // the message can be null (NetworkOnMainThreadException
            // was exactly that) and a nameless failure line hid a
            // dead control plane for a full test session.
            val n = txSendFailed.incrementAndGet()
            if (n % SEND_FAIL_LOG_SAMPLE == 1L) {
                Log.w(TAG, "multicast send failed ($n total): $t")
            }
        }
    }

    /** One-line TX/RX health for MESH_STATUS diagnostics. */
    fun diagnosticsLine(): String =
        "tx=${txSent.get()} txFail=${txSendFailed.get()} " +
            "txPolicyDrop=${txDroppedByPolicy.get()} rxDrop=${rxDropped.get()}"

    /** Send one XVMC control-plane message to the group. */
    fun sendControl(msg: ControlPacket.Message) {
        if (!connected) return
        sendRaw(ControlPacket.encode(msg))
    }

    override fun disconnect() {
        connected = false
        txExecutor.shutdown()
        socket?.close()
        receiveThread?.interrupt()
        receiveThread = null
        listener?.onDisconnected("disconnect requested")
        listener = null
    }

    /**
     * External signal that the underlying network link just changed
     * (wifi -> LTE handoff, IP rotation, interface flap).
     *
     * BUG FIX (2026-06-15): without this, after a Wi-Fi -> cellular
     * handoff the MulticastSocket stayed bound to the now-down
     * interface (wlan0). The receive loop blocks in `sock.receive()`
     * indefinitely — there is no SO_TIMEOUT on the multicast socket
     * — so the transport silently delivers nothing and `connected`
     * stays true. Operator perception: voice freezes, no UI signal,
     * no recovery until the next manual reconnect.
     *
     * Fix: flip `connected` false, close the socket (which unblocks
     * `receive()` with SocketException so the loop exits cleanly),
     * interrupt the receive thread, then re-enter [runReceiveLoop]
     * on a fresh thread. The loop rebuilds the MulticastSocket,
     * picks the current default multicast-capable interface, and
     * rejoins the group on it.
     *
     * Idempotent: a swap fired while no listener is installed (we
     * were never connected) is a no-op.
     */
    fun notifyNetworkSwap() {
        val l = listener
        if (l == null) {
            Log.i(TAG, "notifyNetworkSwap — no listener installed, ignoring")
            return
        }
        Log.i(TAG, "notifyNetworkSwap — closing socket and rejoining on fresh interface")
        connected = false
        try {
            socket?.close()
        } catch (_: Throwable) {
        }
        receiveThread?.interrupt()
        // Don't null receiveThread before the fresh one is spawned —
        // we use the local `l` to re-enter directly. Cleanup of the
        // prior thread/socket happens in its own finally block as the
        // old receive() unblocks on the close above.
        receiveThread =
            thread(start = true, name = "XvMulticast-${config.port}-swap") {
                runReceiveLoop()
            }
    }

    private fun cleanup() {
        try {
            socket?.close()
        } catch (_: Throwable) {
        }
        socket = null
        releaseMulticastLock()
    }

    private fun acquireMulticastLock() {
        val wifi = context?.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return
        multicastLock =
            wifi.createMulticastLock("xv-multicast").apply {
                setReferenceCounted(false)
                acquire()
            }
    }

    private fun releaseMulticastLock() {
        try {
            multicastLock?.release()
        } catch (_: Throwable) {
        }
        multicastLock = null
    }

    /**
     * Pick the first non-loopback, up, multicast-capable interface. Wi-Fi
     * is usually wlan0 on phones; Android picks loopback by default for
     * MulticastSocket if you don't specify, which silently breaks LAN
     * delivery.
     */
    private fun defaultMulticastInterface(): NetworkInterface? =
        runCatching {
            NetworkInterface
                .getNetworkInterfaces()
                ?.toList()
                ?.firstOrNull { it.isUp && !it.isLoopback && it.supportsMulticast() }
        }.getOrNull()

    companion object {
        private const val TAG = "XvMulticast"

        // Largest Opus payload we'd expect over Ethernet-like MTU. 1500
        // bytes is conservative; voice frames are typically <200 bytes
        // even with the RTP + AEAD overhead.
        private const val MAX_DATAGRAM_BYTES = 1500

        // 239/8 is org-local scope; TTL 8 lets frames cross a few mesh
        // hops (OpenMANET nodes route multicast) without leaking far.
        private const val MULTICAST_TTL = 8

        private const val DROP_LOG_SAMPLE = 200L

        // First failure always logs (n % SAMPLE == 1), then every
        // SAMPLEth. Beacons send every 5 s, so a fully-dead TX path
        // still surfaces within seconds and re-logs every few minutes.
        private const val SEND_FAIL_LOG_SAMPLE = 50L
    }
}
