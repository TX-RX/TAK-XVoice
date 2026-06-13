package com.atakmap.android.xv.transport

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import com.atakmap.android.xv.audio.AudioPlayback
import com.atakmap.android.xv.audio.OpusDecoder
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import kotlin.concurrent.thread

/**
 * UDP multicast voice transport. Joins a multicast group, decodes raw
 * Opus frames carried in the datagram payload, pushes PCM to [playback].
 *
 * Wire format note: this implementation assumes each datagram is exactly
 * one Opus payload with no framing prefix. If we discover at runtime that
 * the existing fleet uses a different framing, this is the spot to add a
 * header parser; see project_xv_interop_scope.md for the interop scope.
 */
@SuppressLint("MissingPermission")
class MulticastTransport(
    override val config: TransportConfig.Multicast,
    private val context: Context,
    private val playback: AudioPlayback,
    private val opusDecoderFactory: () -> OpusDecoder,
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

    // One decoder per peer (identified by source address). Multicast
    // framing has no session id; we synthesize one from packet.address.
    private val decoders = mutableMapOf<String, OpusDecoder>()

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
            val sock = MulticastSocket(config.port)
            socket = sock
            val group = InetAddress.getByName(config.groupAddress)
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
                val peerId = packet.address?.hostAddress ?: "unknown"
                val opusPayload = packet.data.copyOfRange(0, packet.length)
                if (opusPayload.isEmpty()) continue

                val decoder = decoders.getOrPut(peerId) { opusDecoderFactory() }
                val pcm =
                    try {
                        decoder.decode(opusPayload)
                    } catch (e: Throwable) {
                        Log.w(TAG, "decode failed from $peerId: ${e.message}")
                        continue
                    }
                playback.playPcm(pcm)
                l.onVoiceFrame(
                    VoiceFrame(
                        opusPayload = opusPayload,
                        senderId = peerId,
                        monotonicTimestampMs = System.nanoTime() / 1_000_000,
                    ),
                )
                if (talkingPeers.add(peerId)) {
                    l.onPeerStartedTalking(peerId)
                }
                // talkingPeers stoppedTalking is best-effort: we don't
                // have a "frame N is the last" signal in raw multicast.
                // The audio playback's silence timer is the truth here.
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

    override fun sendFrame(frame: VoiceFrame) {
        if (!connected) return
        // TX is Phase 2 work. Audit L9: previously logged Log.w each
        // call ("ignoring"), which was misleading — at ~100 frames/sec
        // during PTT it suggested transient drops rather than an
        // unimplemented surface. Log Log.e ONCE per session so the
        // operator's debug capture surfaces "you tried to TX on a
        // multicast transport and nothing went out" loudly, then drop
        // silently. Real fix is to expose canTransmit() on
        // VoiceTransport and have PttDispatcher bonk before keying.
        if (txWarnLogged.compareAndSet(false, true)) {
            Log.e(TAG, "sendFrame — multicast TX is unimplemented (Phase 2); subsequent frames silently dropped")
        }
    }

    private val txWarnLogged = java.util.concurrent.atomic.AtomicBoolean(false)

    override fun disconnect() {
        connected = false
        socket?.close()
        receiveThread?.interrupt()
        receiveThread = null
        listener?.onDisconnected("disconnect requested")
        listener = null
    }

    private fun cleanup() {
        decoders.values.forEach { runCatching { it.close() } }
        decoders.clear()
        try {
            socket?.close()
        } catch (_: Throwable) {
        }
        socket = null
        releaseMulticastLock()
    }

    private fun acquireMulticastLock() {
        val wifi = context.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return
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
        // bytes is conservative; voice frames are typically <200 bytes.
        private const val MAX_DATAGRAM_BYTES = 1500
    }
}
