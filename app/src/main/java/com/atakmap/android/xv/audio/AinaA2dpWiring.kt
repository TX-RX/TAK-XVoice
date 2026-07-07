package com.atakmap.android.xv.audio

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.Intent
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.atakmap.android.xv.aina.AinaDeviceClassifier

/**
 * Wires [AinaA2dpController] into the BT-device lifecycle so AINA-class
 * devices get A2DP forbidden as soon as they connect — preventing phone
 * media (Spotify, YouTube, system sounds) from routing through the
 * speakermic the operator wears on their shoulder.
 *
 * Root cause of the field bug this fixes: [AinaA2dpController] existed
 * as a self-contained mechanism but was never instantiated or invoked.
 * The reflection-based `setConnectionPolicy(device, FORBIDDEN)` call
 * was dead code, so every connected AINA continued to advertise A2DP
 * as a valid media sink and Spotify / YouTube / TPT happily routed
 * `STREAM_MUSIC` there in addition to (or instead of) the phone
 * speaker.
 *
 * Pixel 13+/AOSP 14+ specifics: `BluetoothA2dp.setConnectionPolicy`
 * requires `BLUETOOTH_PRIVILEGED`, which is signature-only on those
 * builds. The reflective call returns `Boolean=false` and our
 * controller propagates [AinaA2dpController.ForbidResult.REFLECTION_FAILED].
 * On that path we post a persistent notification on the
 * [DIAG_CHANNEL_ID] channel telling the operator to manually disable
 * "Media audio" in BT settings — that's the only knob left for a
 * non-system app on Pixel 14+.
 *
 * Constructor parameters are open for the unit test to substitute a
 * spy / fake controller and a stub classifier so the wiring logic
 * itself (call once, on AINA only, no double-forbid) can be pinned
 * without standing up Robolectric.
 */
@SuppressLint("MissingPermission")
class AinaA2dpWiring(
    private val context: Context,
    private val controller: AinaA2dpController,
    private val isAina: (BluetoothDevice) -> Boolean =
        { AinaDeviceClassifier.isPlausibleSpeakermic(it) },
    private val notificationManager: NotificationManager? =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager,
    private val audioManager: AudioManager? =
        context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager,
) : BtAudioPolicy.ConnectListener {
    // MACs we have already forbidden during this XV lifetime. Guards
    // against the duplicate-fan-out case: the same AINA can surface
    // through BOTH the HEADSET profile-proxy attach (devices that
    // pre-dated our start) AND a fresh ACL_CONNECTED broadcast if it
    // reconnects later. Calling forbid() twice for the same MAC isn't
    // catastrophic (the controller is idempotent), but it does emit
    // duplicate log lines and re-issues the reflective disconnect,
    // which on some OEM stacks can cause a brief routing flicker.
    private val forbiddenOnce: MutableSet<String> =
        java.util.Collections.synchronizedSet(HashSet())

    /**
     * Called by [BtAudioPolicy] when a BT device becomes connected
     * (either via ACL_CONNECTED broadcast or via the HEADSET profile
     * proxy's `connectedDevices` snapshot when we first bind).
     *
     * Classifies the device and, if it's AINA-class, requests an A2DP
     * forbid. Non-AINA devices are left strictly alone — the operator's
     * AirPods / car kit / portable speaker keeps full media routing.
     */
    override fun onDeviceConnected(device: BluetoothDevice) {
        val mac = device.address ?: return
        if (!isAina(device)) return
        if (!forbiddenOnce.add(mac)) {
            Log.d(TAG, "onDeviceConnected: $mac already forbidden this lifetime — skipping")
            return
        }
        val result =
            try {
                controller.forbid(device)
            } catch (t: Throwable) {
                Log.w(TAG, "controller.forbid threw for $mac", t)
                AinaA2dpController.ForbidResult.REFLECTION_FAILED
            }
        Log.i(TAG, "forbid($mac) result=$result")
        when (result) {
            AinaA2dpController.ForbidResult.OK -> {
                // Programmatic forbid took. No operator intervention
                // needed. If a stale diag notification is up from a
                // prior platform-refused attempt, clear it now.
                cancelDiagNotification()
            }
            AinaA2dpController.ForbidResult.REFLECTION_FAILED,
            AinaA2dpController.ForbidResult.NO_PROXY,
            -> {
                // Pixel 14+ / AOSP signature-permission case (or the
                // A2DP profile proxy hasn't attached yet — rare but
                // can happen if the AINA connects in the first ~50ms
                // of XV startup before our proxy bind completes).
                // Either way, surface the manual-disable prompt so
                // the operator can fix it before they hit play on
                // Spotify mid-event.
                postDiagNotification(mac)
            }
            AinaA2dpController.ForbidResult.NO_DEVICE -> {
                // Device vanished between the classify and the
                // reflective call. Nothing actionable; the next
                // ACL_CONNECTED will retry.
                forbiddenOnce.remove(mac)
            }
        }
    }

    /**
     * Re-evaluate the diag notification. If no AINA-class A2DP output
     * is currently routed, cancel a previously-posted prompt so it
     * doesn't stay up forever once the operator has actioned the
     * manual fix (or once the AINA has powered off). Called by the
     * VoicePlant on any audio-route change.
     *
     * Side-effect-free when no notification is up. Safe to call from
     * the route-change hot path.
     */
    fun reconcileNotification() {
        if (!hasPostedDiag) return
        val am = audioManager ?: return
        val outs =
            try {
                am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            } catch (t: Throwable) {
                Log.w(TAG, "getDevices threw during reconcile", t)
                return
            }
        val stillRoutedToAinaA2dp =
            outs.any { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP }
        if (!stillRoutedToAinaA2dp) {
            Log.i(TAG, "reconcileNotification: no A2DP output present — cancelling diag prompt")
            cancelDiagNotification()
        }
    }

    /**
     * Clear state. Called on plugin teardown.
     */
    fun stop() {
        cancelDiagNotification()
        forbiddenOnce.clear()
    }

    @Volatile
    private var hasPostedDiag: Boolean = false

    private fun postDiagNotification(mac: String) {
        val nm = notificationManager ?: return
        ensureChannel(nm)
        val tapIntent =
            Intent("android.settings.BLUETOOTH_SETTINGS")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pendingFlags =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
        val tap =
            try {
                PendingIntent.getActivity(context, 0, tapIntent, pendingFlags)
            } catch (t: Throwable) {
                Log.w(TAG, "PendingIntent.getActivity threw for BT settings", t)
                null
            }
        val builder =
            NotificationCompat
                .Builder(context, DIAG_CHANNEL_ID)
                .setContentTitle(DIAG_TITLE)
                .setContentText(DIAG_BODY)
                .setStyle(NotificationCompat.BigTextStyle().bigText(DIAG_BODY))
                .setSmallIcon(android.R.drawable.stat_sys_warning)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_ERROR)
        if (tap != null) builder.setContentIntent(tap)
        try {
            nm.notify(DIAG_NOTIFICATION_ID, builder.build())
            hasPostedDiag = true
            Log.w(
                TAG,
                "posted diag notification for $mac — operator must manually disable " +
                    "'Media audio' in BT settings (platform refused programmatic forbid)",
            )
        } catch (t: Throwable) {
            // POST_NOTIFICATIONS revoked, or notif manager refused.
            // Don't loop on it — the operator can still discover the
            // problem via the Log.w above in adb logcat.
            Log.w(TAG, "nm.notify($DIAG_NOTIFICATION_ID) threw — diag notification suppressed", t)
        }
    }

    private fun cancelDiagNotification() {
        val nm = notificationManager ?: return
        if (!hasPostedDiag) return
        try {
            nm.cancel(DIAG_NOTIFICATION_ID)
        } catch (t: Throwable) {
            Log.w(TAG, "nm.cancel($DIAG_NOTIFICATION_ID) threw", t)
        }
        hasPostedDiag = false
    }

    private fun ensureChannel(nm: NotificationManager) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (nm.getNotificationChannel(DIAG_CHANNEL_ID) != null) return
        try {
            val channel =
                NotificationChannel(
                    DIAG_CHANNEL_ID,
                    "XV diagnostics",
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply {
                    description =
                        "Operator-actionable warnings (e.g. media-routing leak when the " +
                        "platform refuses programmatic A2DP forbid)."
                    setShowBadge(true)
                }
            nm.createNotificationChannel(channel)
        } catch (t: Throwable) {
            Log.w(TAG, "createNotificationChannel($DIAG_CHANNEL_ID) threw", t)
        }
    }

    companion object {
        private const val TAG = "XvAinaA2dpWiring"

        /** Diagnostic / operator-actionable notification channel. */
        const val DIAG_CHANNEL_ID: String = "xv_diag"

        /**
         * Notification id for the AINA media-routing prompt. Distinct
         * from the foreground-service notification id (4711) and the
         * incoming-call ids handled by CallStyleNotifier, so cancels
         * here don't tear down unrelated XV surfaces.
         */
        const val DIAG_NOTIFICATION_ID: Int = 4801

        const val DIAG_TITLE: String = "AINA media routing not blocked."
        const val DIAG_BODY: String =
            "Open BT settings → tap AINA → disable 'Media audio'."
    }
}
