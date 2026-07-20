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

    /**
     * True when this leg has been RECEIVING peers' encrypted frames
     * while holding no key — the "deaf until keyed" state. Drives the
     * operator-facing KEY NEEDED badge; the fix is operator action
     * (import a channel plan or re-enroll with the server). Default
     * false for legs/fakes without an RX drop count.
     */
    val awaitingKey: Boolean get() = false

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

    /** One-line TX/RX/crypto diagnostics for MESH_STATUS; "" when N/A. */
    fun stats(): String = ""

    /**
     * The underlying network link changed (Wi-Fi↔cell handoff, IP
     * rotation). Implementations rebind their socket to the current
     * multicast-capable interface. Default no-op for test fakes.
     */
    fun notifyNetworkSwap() {}

    /**
     * This device lost the bridge role: drop all per-speaker relay
     * state (codecs, relayed-SSRC bookkeeping) so a later re-acquire
     * starts clean and idle entries don't accumulate across role
     * transitions. Default no-op for test fakes / legs that don't relay.
     */
    fun clearRelayState() {}

    /** Tear the leg down. Idempotent. */
    fun close()
}

/**
 * RX fan-in from a leg back to the mesh manager. Implemented by the
 * manager; called from the leg's receive thread.
 */
interface MeshLegSink {
    /**
     * One playable frame off a leg. [sourceHost] is the datagram's
     * source IP — dedup granularity for the bridge-handoff overlap,
     * where two bridges relay the SAME speaker (same SSRC, independent
     * sequences) and only the source address tells the copies apart.
     * Defaults to "" for test fakes that don't model addressing.
     */
    fun onVoice(
        channelName: String,
        opus: ByteArray,
        speakerKey: String,
        seqInBurst: Int?,
        sourceHost: String = "",
        isPatchLeg: Boolean = false,
    )

    fun onControl(
        channelName: String,
        msg: ControlPacket.Message,
        sourceHost: String,
        isPatchLeg: Boolean = false,
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
