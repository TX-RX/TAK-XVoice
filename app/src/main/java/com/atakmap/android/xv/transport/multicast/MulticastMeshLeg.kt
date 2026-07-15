package com.atakmap.android.xv.transport.multicast

import android.content.Context
import com.atakmap.android.xv.transport.MulticastTransport
import com.atakmap.android.xv.transport.TransportConfig
import com.atakmap.android.xv.transport.TransportListener
import com.atakmap.android.xv.transport.VoiceFrame

/**
 * Production [MeshLeg]: one [MulticastTransport] joined to the leg's
 * group, with TX/RX codecs built from the channel's config and the
 * shared per-channel key registry.
 *
 * Relay TX keeps per-speaker [XvNativeWireCodec] instances so frames
 * a bridge forwards from the server carry the ORIGINAL speaker's
 * FNV-1a SSRC — mesh receivers attribute and dedup them correctly.
 * On OpenMANET-compat legs the wire has no speaker field at all, so
 * relay degrades to raw Opus (attribution becomes the bridge's IP;
 * inherent to that format).
 */
class MulticastMeshLeg(
    override val config: ChannelMulticastConfig,
    override val endpoint: MulticastEndpoint,
    private val registry: ChannelKeyRegistry,
    private val ourUid: String,
    context: Context?,
    sink: MeshLegSink,
    socketFactory: ((Int) -> java.net.MulticastSocket)? = null,
) : MeshLeg {
    override val channelName: String = config.channelName

    private val ourSsrc = RtpFraming.fnv1aSsrc(ourUid)

    private val effectiveRegistry: ChannelKeyRegistry? =
        if (config.cryptoPolicy == CryptoPolicy.CLEARTEXT) null else registry

    private val transport: MulticastTransport =
        MulticastTransport(
            config =
            TransportConfig.Multicast(
                groupAddress = endpoint.groupAddress,
                port = endpoint.port,
                networkInterfaceName = null,
                channelLabel = config.channelName,
            ),
            context = context,
            txCodec = config.newWireCodec(ourSsrc, effectiveRegistry),
            rxCodec = config.newWireCodec(ourSsrc, effectiveRegistry),
            localSpeakerKey = "ssrc:%08x".format(ourSsrc),
            onIncomingOpus = { opus, speakerKey, seqInBurst ->
                if (!isOwnRelaySpeaker(speakerKey)) {
                    sink.onVoice(config.channelName, opus, speakerKey, seqInBurst)
                }
            },
            onControlMessage = { msg, sourceHost ->
                sink.onControl(config.channelName, msg, sourceHost)
            },
            socketFactory = socketFactory ?: { port -> java.net.MulticastSocket(port) },
        )

    init {
        transport.connect(QuietListener)
    }

    override val encryptedNow: Boolean
        get() = config.cryptoPolicy != CryptoPolicy.CLEARTEXT && registry.hasKey()

    override fun beginVoiceBurst() = transport.beginVoiceBurst()

    override fun sendOpus(opus: ByteArray) {
        transport.sendFrame(
            VoiceFrame(
                opusPayload = opus,
                senderId = "self",
                monotonicTimestampMs = System.nanoTime() / 1_000_000,
                targetSlot = 0,
            ),
        )
    }

    override fun sendControl(msg: ControlPacket.Message) = transport.sendControl(msg)

    private val relayCodecs = HashMap<String, MulticastWireCodec>()

    // Speaker keys of frames WE are relaying from the server onto this
    // leg. Relayed datagrams carry the ORIGINAL speaker's SSRC — not
    // [localSpeakerKey] — so the transport's own-frame loopback filter
    // misses them when the OS delivers our multicast back to us. The
    // bridge then re-ingested its own relay as fresh mesh traffic from
    // an unresolvable speaker and bounced it back onto Mumble as an
    // echo (field repro 2026-07-15: desktop Mumble speaker echoed by
    // the bridging tablet). Rule: traffic is never repeated onto the
    // channel it came from — anything whose SSRC we relay is ours on
    // RX and must be dropped. (OPENMANET_COMPAT relays are raw Opus
    // with source-IP attribution; the OS tags loopback with our own
    // source address, which that format's receivers key on, so this
    // set is XV_NATIVE-only by construction.)
    private val relayedSpeakerKeys = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    internal fun isOwnRelaySpeaker(speakerKey: String): Boolean = speakerKey in relayedSpeakerKeys

    override fun sendRelayOpus(
        speakerUid: String,
        opus: ByteArray,
        burstStart: Boolean,
    ) {
        val codec =
            when (config.wireFormat) {
                WireFormat.OPENMANET_COMPAT -> {
                    // No speaker field on this wire; raw passthrough.
                    transport.sendRaw(opus)
                    return
                }
                WireFormat.XV_NATIVE -> {
                    relayedSpeakerKeys += "ssrc:%08x".format(RtpFraming.fnv1aSsrc(speakerUid))
                    relayCodecs.getOrPut(speakerUid) {
                        XvNativeWireCodec(
                            ssrc = RtpFraming.fnv1aSsrc(speakerUid),
                            cryptoPolicy = config.cryptoPolicy,
                            keyRegistry = effectiveRegistry,
                        )
                    }
                }
            }
        if (burstStart) codec.beginBurst()
        codec.encodeTx(opus)?.let { transport.sendRaw(it) }
    }

    override fun stats(): String = "${transport.diagnosticsLine()} enc=$encryptedNow"

    /** Network handoff nudge — forwards to the transport's swap path. */
    override fun notifyNetworkSwap() = transport.notifyNetworkSwap()

    override fun close() {
        relayCodecs.clear()
        relayedSpeakerKeys.clear()
        transport.disconnect()
    }

    private object QuietListener : TransportListener {
        override fun onConnected() {}

        override fun onDisconnected(reason: String?) {}

        override fun onConnectionFailed(error: Throwable) {
            android.util.Log.w("XvMeshLeg", "mesh leg connect failed", error)
        }

        override fun onVoiceFrame(frame: VoiceFrame) {}

        override fun onPeerStartedTalking(peerId: String) {}

        override fun onPeerStoppedTalking(peerId: String) {}
    }
}
