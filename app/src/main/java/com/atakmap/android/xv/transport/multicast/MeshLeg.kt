package com.atakmap.android.xv.transport.multicast

/**
 * One live multicast leg (channel × group × port) as the mesh manager
 * sees it. The production implementation wraps a
 * [com.atakmap.android.xv.transport.MulticastTransport]; tests use
 * in-memory fakes so the manager's routing/election logic runs without
 * sockets or Android.
 */
interface MeshLeg {
    /** Canonical channel name this leg carries. */
    val channelName: String

    val endpoint: MulticastEndpoint

    val config: ChannelMulticastConfig

    /**
     * True when TX frames actually go out AEAD-wrapped right now —
     * i.e. crypto policy allows it AND a channel key is live. Drives
     * the operator-facing CLEAR badge.
     */
    val encryptedNow: Boolean

    /** PTT-down edge: reset burst-relative TX state. */
    fun beginVoiceBurst()

    /** TX one of OUR OWN Opus frames on this leg. */
    fun sendOpus(opus: ByteArray)

    /** TX one XVMC control-plane message on this leg. */
    fun sendControl(msg: ControlPacket.Message)

    /**
     * Bridge relay TX: frame a server-side speaker's Opus so mesh
     * receivers attribute it to THAT speaker (per-speaker SSRC), not
     * to the bridge operator. [burstStart] resets that speaker's
     * relay sequence.
     */
    fun sendRelayOpus(
        speakerUid: String,
        opus: ByteArray,
        burstStart: Boolean,
    )

    /** Tear the leg down. Idempotent. */
    fun close()
}

/**
 * RX fan-in from a leg back to the mesh manager. Implemented by the
 * manager; called from the leg's receive thread.
 */
interface MeshLegSink {
    fun onVoice(
        channelName: String,
        opus: ByteArray,
        speakerKey: String,
        seqInBurst: Int?,
    )

    fun onControl(
        channelName: String,
        msg: ControlPacket.Message,
        sourceHost: String,
    )
}

/** Builds a live leg. Production creates a MulticastTransport-backed leg. */
fun interface MeshLegFactory {
    fun create(
        config: ChannelMulticastConfig,
        endpoint: MulticastEndpoint,
        registry: ChannelKeyRegistry,
        sink: MeshLegSink,
    ): MeshLeg
}
