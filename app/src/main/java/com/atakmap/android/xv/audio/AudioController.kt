package com.atakmap.android.xv.audio

/**
 * Single point of truth for XV's audio routing state. Implements the
 * Idle / RX / TX / Suspended state machine documented in
 * project_xv_audio_focus_state_machine.md.
 *
 * Transports MUST go through this interface to start/stop playback;
 * they MUST NOT touch AudioManager, SCO, or audio focus directly. The
 * controller is what enforces the audio routing fix (no SCO at idle,
 * SCO only on TX).
 */
interface AudioController {
    val state: AudioState

    /**
     * Request RX state: an incoming voice frame is about to play. Acquires
     * GAIN_TRANSIENT_MAY_DUCK focus and routes to STREAM_MUSIC. Does NOT
     * engage SCO. Idempotent if already in RX.
     *
     * @return true if the controller is in RX after the call (focus granted).
     */
    fun enterRx(): Boolean

    /**
     * Request TX state: the user is keying the mic. Acquires
     * GAIN_TRANSIENT_EXCLUSIVE focus, sets MODE_IN_COMMUNICATION, engages
     * SCO if a BT headset is connected. Preempts RX.
     *
     * @return true if the controller is in TX after the call.
     */
    fun enterTx(): Boolean

    /**
     * Drop back to IDLE: release any held focus, drop SCO, restore mode.
     * Safe to call from any state (no-op if already idle).
     */
    fun returnToIdle()

    /**
     * Symmetric counterpart to [enterTx]. Drops TX focus and restores
     * MODE without tearing down RX state or touching SCO (SCO is owned
     * externally by ScoLink — its ref count manages physical-link
     * lifetime). State transitions: TX → IDLE, RX_TX → RX. No-op from
     * IDLE / RX / SUSPENDED.
     */
    fun exitTx()

    /**
     * Permanent shutdown: release all listeners and any held focus.
     * Controller is unusable after this; create a new one if needed.
     */
    fun shutdown()

    fun addListener(listener: AudioStateListener)

    fun removeListener(listener: AudioStateListener)
}
