package com.atakmap.android.xv.audio

enum class AudioState {
    IDLE,
    RX,
    TX,
    RX_TX, // Full-duplex: receiving and transmitting simultaneously
    SUSPENDED,
}
