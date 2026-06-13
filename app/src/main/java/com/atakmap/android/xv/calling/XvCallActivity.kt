package com.atakmap.android.xv.calling

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.telecom.CallAudioState
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import com.atakmap.android.xv.R
import com.atakmap.android.xv.telecom.ActiveCallRegistry

/**
 * Full-screen call surface used as the `setFullScreenIntent` target for
 * [CallStyleNotifier]'s incoming + active notifications.
 *
 * Two modes:
 *  - [MODE_INCOMING]: status + callsign + Answer / Decline. Tapping
 *    Answer / Decline broadcasts the same actions the system
 *    CallStyle action buttons do, so `XvVoiceService.incomingDecisionReceiver`
 *    handles both surfaces uniformly.
 *  - [MODE_ACTIVE]: status + callsign + Mute toggle + 3 audio-route
 *    toggles (Earpiece / Speaker / BT) + Hang Up. Audio-route taps
 *    broadcast [CallStyleNotifier.ACTION_SET_AUDIO_ROUTE] which
 *    `XvVoiceService` dispatches via `Connection.setAudioRoute`; the
 *    activity subscribes to [ActiveCallRegistry.addRouteListener] so the
 *    UI reflects the system-arbitrated route in real time (relevant for
 *    BT connect/disconnect mid-call, mute state changes from CallStyle's
 *    own surface, etc).
 *
 * Notification.CallStyle on phone won't render its ring or in-call
 * surface unless either the posting context is a phoneCall-typed
 * foreground service OR the notification carries a fullScreenIntent.
 * XV's service is microphone|connectedDevice typed, so FSI pointing at
 * this activity is the path that works on both incoming and active
 * notifications.
 */
class XvCallActivity : Activity() {
    private var mode: String = MODE_INCOMING
    private val routeListener: (CallAudioState?) -> Unit = { renderRouteAndMute(it) }

    // Proximity wake-lock — held only while the call audio is routed to
    // the earpiece. When the user holds the phone to their ear, the
    // proximity sensor turns the screen off (preventing cheek-presses
    // on Hang Up / Mute / route buttons). Released when the route
    // flips to speaker or BT, or when the activity finishes.
    private var proximityLock: PowerManager.WakeLock? = null

    // Self-finish when the call is torn down externally (peer hung up,
    // Telecom preempted, system suspend). Without this the in-call
    // surface stays up after the other party leaves, presenting Hang Up
    // and audio-route controls for a call that already ended — operator
    // sees "frozen" UI until they manually hit Hang Up which is a
    // no-op at that point.
    private val teardownListener: () -> Unit = {
        Log.i(TAG, "external teardown — finishing activity")
        runOnUiThread { if (!isFinishing) finish() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Belt-and-suspenders for OEMs that don't honor manifest
        // showWhenLocked / turnScreenOn on FSI launches.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            (getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager)
                ?.requestDismissKeyguard(this, null)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            )
        }

        setContentView(R.layout.xv_call)
        bindIntent(intent)
        ActiveCallRegistry.addRouteListener(routeListener)
        ActiveCallRegistry.addExternalTeardownListener(teardownListener)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // singleInstance — a fresh REQUEST_CALL or postActive while we're
        // already up re-renders without recreating.
        setIntent(intent)
        bindIntent(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        ActiveCallRegistry.removeRouteListener(routeListener)
        ActiveCallRegistry.removeExternalTeardownListener(teardownListener)
        releaseProximityLock()
    }

    private fun applyProximityForRoute(route: Int) {
        if (route == CallAudioState.ROUTE_EARPIECE) {
            acquireProximityLock()
        } else {
            releaseProximityLock()
        }
    }

    @Suppress("WakelockTimeout")
    private fun acquireProximityLock() {
        if (proximityLock?.isHeld == true) return
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
            val lock =
                pm.newWakeLock(
                    PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK,
                    "xv:call-proximity",
                )
            lock.setReferenceCounted(false)
            lock.acquire()
            proximityLock = lock
            Log.i(TAG, "proximity wake-lock acquired (earpiece route)")
        } catch (t: Throwable) {
            Log.w(TAG, "acquireProximityLock threw", t)
        }
    }

    private fun releaseProximityLock() {
        val lock = proximityLock ?: return
        try {
            if (lock.isHeld) lock.release()
        } catch (t: Throwable) {
            Log.w(TAG, "releaseProximityLock threw", t)
        }
        proximityLock = null
    }

    private fun bindIntent(intent: Intent) {
        mode = intent.getStringExtra(EXTRA_MODE) ?: MODE_INCOMING
        val callsign = intent.getStringExtra(EXTRA_CALLSIGN) ?: "Unknown"
        val tempChannelId = intent.getIntExtra(EXTRA_TEMP_CHANNEL_ID, -1)
        val callerSession = intent.getIntExtra(EXTRA_CALLER_SESSION, -1)

        Log.i(
            TAG,
            "bindIntent mode=$mode callsign='$callsign' " +
                "tempChannelId=$tempChannelId callerSession=$callerSession",
        )

        findViewById<TextView>(R.id.xv_call_callsign).text = callsign

        val incomingSection = findViewById<View>(R.id.xv_call_incoming_section)
        val activeSection = findViewById<View>(R.id.xv_call_active_section)
        if (mode == MODE_INCOMING) {
            findViewById<TextView>(R.id.xv_call_status).text = "● Incoming"
            incomingSection.visibility = View.VISIBLE
            activeSection.visibility = View.GONE
            findViewById<Button>(R.id.xv_call_answer).setOnClickListener {
                sendDecision(answered = true, callsign, tempChannelId, callerSession)
                finish()
            }
            findViewById<Button>(R.id.xv_call_decline).setOnClickListener {
                sendDecision(answered = false, callsign, tempChannelId, callerSession)
                finish()
            }
        } else {
            findViewById<TextView>(R.id.xv_call_status).text = "● In call"
            incomingSection.visibility = View.GONE
            activeSection.visibility = View.VISIBLE
            findViewById<Button>(R.id.xv_call_hangup).setOnClickListener {
                sendBroadcast(
                    Intent(CallStyleNotifier.ACTION_HANGUP_REQUESTED)
                        .setPackage(packageName),
                )
                finish()
            }
            findViewById<Button>(R.id.xv_call_mute).setOnClickListener {
                val currentlyMuted =
                    @Suppress("DEPRECATION")
                    (ActiveCallRegistry.activeConnection()?.callAudioState)?.isMuted
                        ?: false
                sendBroadcast(
                    Intent(CallStyleNotifier.ACTION_SET_MUTED)
                        .setPackage(packageName)
                        .putExtra(CallStyleNotifier.EXTRA_MUTED, !currentlyMuted),
                )
            }
            findViewById<Button>(R.id.xv_call_route_earpiece).setOnClickListener {
                requestRoute(CallAudioState.ROUTE_EARPIECE)
            }
            findViewById<Button>(R.id.xv_call_route_speaker).setOnClickListener {
                requestRoute(CallAudioState.ROUTE_SPEAKER)
            }
            findViewById<Button>(R.id.xv_call_route_bt).setOnClickListener {
                requestRoute(CallAudioState.ROUTE_BLUETOOTH)
            }
            // Seed the UI with the current call audio state so highlights
            // are correct on first render (before any onCallAudioStateChanged
            // fires). If there's no active connection yet, leave defaults.
            renderRouteAndMute(@Suppress("DEPRECATION") (ActiveCallRegistry.activeConnection()?.callAudioState))
        }
    }

    private fun requestRoute(route: Int) {
        sendBroadcast(
            Intent(CallStyleNotifier.ACTION_SET_AUDIO_ROUTE)
                .setPackage(packageName)
                .putExtra(CallStyleNotifier.EXTRA_AUDIO_ROUTE, route),
        )
    }

    private fun renderRouteAndMute(state: CallAudioState?) {
        // Skip when the active section isn't visible — incoming mode
        // doesn't show these controls.
        if (mode != MODE_ACTIVE) return
        runOnUiThread {
            val muted = state?.isMuted ?: false
            val activeRoute = state?.route ?: CallAudioState.ROUTE_EARPIECE
            val supportMask =
                state?.supportedRouteMask ?: (
                    CallAudioState.ROUTE_EARPIECE or CallAudioState.ROUTE_SPEAKER
                    )

            findViewById<Button>(R.id.xv_call_mute).text = if (muted) "Unmute" else "Mute"
            applyToggleBg(findViewById(R.id.xv_call_mute), muted)

            val earpieceBtn = findViewById<Button>(R.id.xv_call_route_earpiece)
            val speakerBtn = findViewById<Button>(R.id.xv_call_route_speaker)
            val btBtn = findViewById<Button>(R.id.xv_call_route_bt)
            applyToggleBg(earpieceBtn, activeRoute == CallAudioState.ROUTE_EARPIECE)
            applyToggleBg(speakerBtn, activeRoute == CallAudioState.ROUTE_SPEAKER)
            applyToggleBg(btBtn, activeRoute == CallAudioState.ROUTE_BLUETOOTH)

            // Grey out routes the system reports as unsupported (e.g. BT
            // not connected). Earpiece is always supported on phones;
            // speaker is always supported. BT may come and go.
            btBtn.isEnabled = (supportMask and CallAudioState.ROUTE_BLUETOOTH) != 0
            btBtn.alpha = if (btBtn.isEnabled) 1.0f else 0.4f

            // Apply / release the proximity wake-lock based on route.
            // Earpiece = "holding to ear" → screen off on proximity.
            // Speaker / BT / wired = "not at ear" → screen stays on.
            applyProximityForRoute(activeRoute)
        }
    }

    private fun applyToggleBg(
        btn: Button,
        active: Boolean,
    ) {
        btn.setBackgroundResource(
            if (active) R.drawable.xv_button_bg_transmitting else R.drawable.xv_button_bg,
        )
    }

    private fun sendDecision(
        answered: Boolean,
        callsign: String,
        tempChannelId: Int,
        callerSession: Int,
    ) {
        val out =
            Intent(
                if (answered) {
                    CallStyleNotifier.ACTION_ANSWER
                } else {
                    CallStyleNotifier.ACTION_DECLINE
                },
            ).apply {
                setPackage(packageName)
                putExtra(CallStyleNotifier.EXTRA_CALLER_CALLSIGN, callsign)
                putExtra(CallStyleNotifier.EXTRA_TEMP_CHANNEL_ID, tempChannelId)
                putExtra(CallStyleNotifier.EXTRA_CALLER_SESSION, callerSession)
            }
        sendBroadcast(out)
    }

    companion object {
        private const val TAG = "XvCallActivity"

        const val EXTRA_MODE = "com.atakmap.android.xv.callMode"
        const val EXTRA_CALLSIGN = "com.atakmap.android.xv.callsign"
        const val EXTRA_TEMP_CHANNEL_ID = "com.atakmap.android.xv.tempChannelId"
        const val EXTRA_CALLER_SESSION = "com.atakmap.android.xv.callerSession"

        const val MODE_INCOMING = "incoming"
        const val MODE_ACTIVE = "active"
    }
}
