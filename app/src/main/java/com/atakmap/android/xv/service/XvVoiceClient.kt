package com.atakmap.android.xv.service

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.RemoteException
import android.util.Log

// Plugin-side facade for XvVoiceService. Lives in ATAK's UID. Binds
// to the service (which lives in our APK's UID) and exposes its
// IXvVoice methods as plain Kotlin calls so the rest of the plugin
// doesn't have to reason about Binder / nullability / RemoteException.
//
// The client also maintains an optional in-process IXvVoiceListener
// stub that the service uses to deliver TX Opus and state events
// back to us. The plugin's Mumble transport hooks into onTxOpus and
// pushes the bytes onto the wire.
class XvVoiceClient(
    private val context: Context,
) {
    @Volatile
    private var voice: IXvVoice? = null

    @Volatile
    private var serviceBinder: IBinder? = null

    @Volatile
    private var listener: IXvVoiceListener? = null

    @Volatile
    private var pendingListenerRegistration: IXvVoiceListener? = null

    private val pendingActions = java.util.concurrent.ConcurrentLinkedQueue<(IXvVoice) -> Unit>()

    // Soft cap so a long-lived plugin life with a wedged service doesn't
    // grow this queue unboundedly. 256 is generous (a typical session
    // sends ~20 setting writes); past that we drop oldest with a WARN.
    private val pendingActionsCap: Int = 256

    // "Persistent" settings — last value the plugin pushed for a given
    // setting key. On binder reconnect after a service crash we replay
    // these so the new IXvVoice starts in the same state. Keyed by a
    // stable setting name (e.g. "latchedMode") so only the most recent
    // value per setting survives — no duplicate calls. Concurrent so
    // the plugin's UI thread can write while the connection callback
    // (Binder dispatch thread) iterates.
    private val persistentSettings: MutableMap<String, (IXvVoice) -> Unit> =
        java.util.concurrent.ConcurrentHashMap()

    // Death recipient on the service-side binder. onServiceDisconnected
    // only fires for clean unbind sequences; if XvVoiceService's process
    // is killed (low-memory, force-stop, signature update), the system
    // delivers binderDied() instead. Without this, [voice] would keep
    // pointing at a stale proxy and every ifBound() call would throw
    // RemoteException. The service's BIND_AUTO_CREATE flag will spin up
    // a fresh process on the next call, but our cached references would
    // still point at the dead binder until then. Clearing eagerly on
    // binderDied lets pendingActions queue up cleanly for the new bind.
    private val deathRecipient =
        IBinder.DeathRecipient {
            Log.w(TAG, "voice plant binder died — clearing reference")
            serviceBinder = null
            voice = null
        }

    private val connection =
        object : ServiceConnection {
            override fun onServiceConnected(
                name: ComponentName?,
                service: IBinder?,
            ) {
                val v = IXvVoice.Stub.asInterface(service)
                voice = v
                serviceBinder = service
                Log.i(TAG, "onServiceConnected — voice plant bound")
                if (service != null) {
                    try {
                        service.linkToDeath(deathRecipient, 0)
                    } catch (t: RemoteException) {
                        Log.w(TAG, "linkToDeath threw — proceeding without death notification", t)
                    }
                    val remoteVersion =
                        try {
                            v.getApiVersion()
                        } catch (t: RemoteException) {
                            Log.w(TAG, "getApiVersion probe threw", t)
                            -1
                        }
                    if (remoteVersion != EXPECTED_API_VERSION) {
                        Log.w(
                            TAG,
                            "AIDL version mismatch — plugin expects $EXPECTED_API_VERSION, " +
                                "service reports $remoteVersion. Proceeding, but expect breakage.",
                        )
                    } else {
                        Log.i(TAG, "AIDL version OK ($remoteVersion)")
                    }
                }
                pendingListenerRegistration?.let { l ->
                    try {
                        v.registerListener(l)
                        listener = l
                    } catch (t: Throwable) {
                        Log.w(TAG, "registerListener on connect failed", t)
                    }
                }
                // Replay current persistent settings BEFORE draining
                // the one-shot queue so a fresh service comes up in the
                // same configuration we last drove it to. Without this,
                // a binderDied → new-bind sequence would leave the
                // service at hard-coded defaults (latched=off,
                // hotMic=off, no AINA selection) until the next
                // operator setting change.
                if (persistentSettings.isNotEmpty()) {
                    Log.i(TAG, "replaying ${persistentSettings.size} persistent setting(s) to fresh binder")
                    for ((name, apply) in persistentSettings) {
                        try {
                            apply(v)
                        } catch (t: Throwable) {
                            Log.w(TAG, "replay '$name' threw", t)
                        }
                    }
                }
                while (true) {
                    val a = pendingActions.poll() ?: break
                    try {
                        a(v)
                    } catch (t: Throwable) {
                        Log.w(TAG, "pending action threw", t)
                    }
                }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                Log.w(TAG, "onServiceDisconnected — voice plant lost")
                val b = serviceBinder
                serviceBinder = null
                voice = null
                try {
                    b?.unlinkToDeath(deathRecipient, 0)
                } catch (_: Throwable) {
                }
            }
        }

    fun start() {
        val intent = serviceIntent()
        // startForegroundService promotes the service to foreground
        // before bind so AudioRecord allocations on first PTT see
        // the privilege. bindService alone wouldn't promote it.
        try {
            context.startForegroundService(intent)
        } catch (t: Throwable) {
            Log.e(TAG, "startForegroundService threw", t)
        }
        try {
            context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        } catch (t: Throwable) {
            Log.e(TAG, "bindService threw", t)
        }
    }

    /** Full shutdown: tear down the audio plant inside the service AND
     *  stop the service itself. Use this on permanent termination
     *  (plugin uninstall, ATAK shutdown). For plugin reload — where the
     *  goal is to drop the binder cleanly and let the service self-stop
     *  via its own orphan-grace timer so a quick reload can rebind to
     *  the still-running service — use [unbindForReload] instead. */
    fun stop() {
        try {
            voice?.let { v ->
                listener?.let { v.unregisterListener(it) }
                v.teardown()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "teardown threw", t)
        }
        try {
            serviceBinder?.unlinkToDeath(deathRecipient, 0)
        } catch (_: Throwable) {
        }
        try {
            context.unbindService(connection)
        } catch (_: IllegalArgumentException) {
        } catch (t: Throwable) {
            Log.w(TAG, "unbindService threw", t)
        }
        try {
            context.stopService(serviceIntent())
        } catch (t: Throwable) {
            Log.w(TAG, "stopService threw", t)
        }
        serviceBinder = null
        voice = null
    }

    /**
     * Plugin-reload teardown. Drops our binder + listener registration
     * so XvVoiceService sees `onUnbind` and starts its 30 s orphan-grace
     * timer (per the existing service watchdog). If the plugin reloads
     * inside the window, the new bind cancels the timer and the audio
     * plant resumes without dropping SCO / AudioRecord / Telecom call.
     * Eliminates the 3-5 s dark window operators see on every plugin
     * reload. Audit L2.
     *
     * Does NOT call teardown() (which would stop mic / SCO / etc.) and
     * does NOT call stopService(). The service stays alive long enough
     * for the reload to land.
     */
    fun unbindForReload() {
        try {
            voice?.let { v ->
                listener?.let { v.unregisterListener(it) }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "unregisterListener threw", t)
        }
        try {
            serviceBinder?.unlinkToDeath(deathRecipient, 0)
        } catch (_: Throwable) {
        }
        try {
            context.unbindService(connection)
        } catch (_: IllegalArgumentException) {
        } catch (t: Throwable) {
            Log.w(TAG, "unbindService threw", t)
        }
        serviceBinder = null
        voice = null
    }

    fun setListener(l: IXvVoiceListener) {
        pendingListenerRegistration = l
        voice?.let {
            try {
                listener?.let { existing -> it.unregisterListener(existing) }
                it.registerListener(l)
                listener = l
            } catch (t: Throwable) {
                Log.w(TAG, "setListener: register threw", t)
            }
        }
    }

    /** Fire-and-forget call, queued if the binder isn't ready yet. */
    fun ifBound(action: (IXvVoice) -> Unit) {
        val v = voice
        if (v != null) {
            try {
                action(v)
            } catch (t: Throwable) {
                Log.w(TAG, "ifBound action threw", t)
            }
        } else {
            // Cap the queue so a stuck-down service doesn't grow it
            // unboundedly. Drop oldest on overflow — fresh state writes
            // are more relevant than stale ones from minutes ago.
            while (pendingActions.size >= pendingActionsCap) {
                val dropped = pendingActions.poll()
                if (dropped == null) break
                Log.w(
                    TAG,
                    "pendingActions queue at cap ($pendingActionsCap) — dropped oldest action; " +
                        "voice service likely down",
                )
            }
            pendingActions.add(action)
        }
    }

    /**
     * Like [ifBound] but also remembers the last-applied value under
     * [name] so a binder reconnect (binderDied → fresh bind) replays
     * the call against the new IXvVoice. Use for "current setting"
     * pushes (latched mode, hot mic, AINA MAC, route) where the
     * service-side defaults aren't safe to fall back to. Each [name]
     * keeps only the most recent value — repeated calls coalesce.
     */
    fun setPersistent(
        name: String,
        action: (IXvVoice) -> Unit,
    ) {
        persistentSettings[name] = action
        ifBound(action)
    }

    private fun serviceIntent(): Intent =
        Intent()
            // EXPLICIT package + class — the (Context, Class) overload
            // derives the package from the context, but our context
            // is ATAK's (because the plugin runs inside ATAK's
            // process), so it would resolve to com.atakmap.app.civ/
            // ...XvVoiceService — which doesn't exist. The service
            // lives in our APK so we must spell out the package.
            .setComponent(ComponentName(XV_PACKAGE, XV_VOICE_SERVICE))
            .setAction(IXVVOICE_ACTION)

    companion object {
        private const val TAG = "XvVoiceClient"

        // AIDL contract version this client was built against. Logged
        // (warn) but not enforced — a mismatch most likely means the
        // operator has a stale APK installed; we'd rather log loudly
        // and try to limp along than refuse to bind. The version probe
        // also serves as a heartbeat that the binder is responsive.
        private const val EXPECTED_API_VERSION = 1

        // Must match the intent-filter action declared on the service
        // in AndroidManifest.xml.
        private const val IXVVOICE_ACTION = "com.atakmap.android.xv.service.IXvVoice"

        // Our APK's package + the fully-qualified service class name.
        // Hard-coded because we can't trust the calling context's
        // packageName here (see serviceIntent comment).
        private const val XV_PACKAGE = "com.atakmap.android.xv.plugin"
        private const val XV_VOICE_SERVICE =
            "com.atakmap.android.xv.service.XvVoiceService"
    }
}
