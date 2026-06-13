package com.atakmap.android.xv.emergency

import android.os.SystemClock

fun interface Clock {
    fun nowMillis(): Long

    companion object {
        val System: Clock = Clock { SystemClock.elapsedRealtime() }
    }
}
