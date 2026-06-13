package com.atakmap.android.xv.transport

/**
 * Wire-level voice transport. Mumble (server-based) and Multicast
 * (LAN UDP) implement this same interface so audio routing, focus
 * management, PTT handling, emergency, and TPT all live above it
 * unchanged. See project_xv_unified_channel_ux.md for the design.
 *
 * Lifecycle: connect -> (sendFrame / onVoiceFrame events) -> disconnect.
 * A transport is single-use; create a new instance to reconnect.
 */
interface VoiceTransport {
    val config: TransportConfig

    val isConnected: Boolean

    /**
     * Open the connection. Returns immediately; success/failure is
     * reported via [TransportListener.onConnected] /
     * [TransportListener.onConnectionFailed].
     */
    fun connect(listener: TransportListener)

    /**
     * Send one Opus frame to the channel. Behaviour while disconnected
     * is implementation-defined (typically dropped with a warning log).
     */
    fun sendFrame(frame: VoiceFrame)

    /**
     * Tear down the connection. After this returns, [isConnected] is
     * false and no further listener callbacks will fire.
     */
    fun disconnect()
}
