package com.atakmap.android.xv.transport

/**
 * Events from a [VoiceTransport] back to the application.
 *
 * Implementations MUST be invoked off the audio-data path (typically a
 * single transport thread); the listener should hand frames off to a
 * jitter buffer or a Looper-bound queue rather than decoding on the
 * delivery thread.
 */
interface TransportListener {
    fun onConnected()

    fun onDisconnected(reason: String?)

    fun onConnectionFailed(error: Throwable)

    /**
     * Secondary-slot-only failure path. Surfaced when VS2 hits a
     * server-side issue (self-kick, UsernameInUse, Reject) that would
     * be fatal-to-the-transport if it came from primary, but localized
     * to secondary needs its own recovery flow (ghost-cleanup retry,
     * UUID rotation after exhaustion). Default no-op so existing
     * listeners aren't disturbed.
     */
    fun onSecondaryFailed(error: Throwable) {}

    /**
     * One incoming voice frame. The listener owns the bytes after the
     * call returns.
     */
    fun onVoiceFrame(frame: VoiceFrame)

    /**
     * A peer started transmitting. Useful for "who's talking" indicators.
     * Multicast transports may not have this; they emit it whenever a
     * frame from a previously-silent source arrives.
     */
    fun onPeerStartedTalking(peerId: String)

    fun onPeerStoppedTalking(peerId: String)
}
