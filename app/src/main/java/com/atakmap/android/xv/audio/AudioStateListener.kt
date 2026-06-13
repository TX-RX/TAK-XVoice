package com.atakmap.android.xv.audio

fun interface AudioStateListener {
    fun onStateChanged(
        from: AudioState,
        to: AudioState,
    )
}
