package com.atakmap.android.xv.emergency

import android.os.Handler
import android.os.Looper

interface DelayScheduler {
    fun schedule(
        delayMillis: Long,
        task: Runnable,
    ): Any

    fun cancel(handle: Any)
}

class HandlerDelayScheduler(
    private val handler: Handler = Handler(Looper.getMainLooper()),
) : DelayScheduler {
    override fun schedule(
        delayMillis: Long,
        task: Runnable,
    ): Any {
        handler.postDelayed(task, delayMillis)
        return task
    }

    override fun cancel(handle: Any) {
        if (handle is Runnable) handler.removeCallbacks(handle)
    }
}
