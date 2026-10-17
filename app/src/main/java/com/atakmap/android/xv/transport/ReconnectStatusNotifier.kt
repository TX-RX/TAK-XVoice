package com.atakmap.android.xv.transport

import android.app.NotificationManager
import android.content.Context
import android.util.Log
import androidx.core.app.NotificationCompat
import com.atakmap.android.xv.security.NotificationChannels

/**
 * Persistent, silent "XV has lost the voice server and is still trying"
 * surface in the notification shade.
 *
 * This is the signal that OUTLIVES the audio. The reconnect audio cues
 * deliberately stop after
 * [ReconnectNotificationTracker.DEFAULT_AUDIBLE_UNTIL_MS] so a device
 * nobody is holding stops making noise — but the ladder itself keeps
 * retrying forever (see [ReconnectPolicy.DEFAULT_SCHEDULE]). Without a
 * visual surface, that combination would be indistinguishable from "XV
 * gave up," which is precisely the misread the never-give-up policy
 * exists to prevent. An operator who pulls the shade must be able to see
 * that the radio is still reaching for the server.
 *
 * Live "last attempt Xs ago" without a timer: the notification is posted
 * with `setWhen(attempt time)` + `setUsesChronometer(true)`, so the
 * system renders a self-updating counter. We re-post only when an
 * attempt actually fails — no polling, no wakeups of our own. On the
 * dormant tail that means one cheap re-post every 5 minutes.
 *
 * Threading: [NotificationManager.notify] / `cancel` are thread-safe, so
 * callers may post from the transport listener's thread without
 * synchronisation.
 */
class ReconnectStatusNotifier(
    private val context: Context,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) {
    private val nm: NotificationManager? by lazy {
        context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
    }

    @Volatile
    private var showing: Boolean = false

    /**
     * Record that a reconnect attempt just failed and the outage
     * continues. Posts the notification if it isn't up, and refreshes the
     * "last attempt" chronometer base if it is. Idempotent and cheap.
     */
    fun onOutageContinuing() {
        val mgr =
            nm ?: run {
                Log.w(TAG, "NotificationManager unavailable — no reconnect status surface")
                return
            }
        // Cold-create defensively. ensureAll() normally runs in
        // XvVoiceService.onCreate, but this notifier is driven from the
        // plugin's transport listener, which can outlive or precede the
        // service in edge cases (service crash + restart). Posting to a
        // non-existent channel is silently dropped on API 26+, so a
        // missing channel would make this surface vanish with no error.
        NotificationChannels.ensureAll(context)
        val notification =
            NotificationCompat
                .Builder(context, NotificationChannels.RECONNECT)
                .setSmallIcon(android.R.drawable.stat_sys_warning)
                .setContentTitle(TITLE)
                .setContentText(TEXT)
                .setWhen(nowMs())
                .setShowWhen(true)
                // Counts up from the last failed attempt — the operator's
                // "is this thing still alive?" answer at a glance.
                .setUsesChronometer(true)
                .setOngoing(true)
                // Re-posted on every failed attempt; without this the
                // shade would re-rank/peek each time even on a LOW
                // channel.
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()
        try {
            mgr.notify(NOTIFICATION_ID_RECONNECT, notification)
            showing = true
        } catch (t: Throwable) {
            // Most likely POST_NOTIFICATIONS denied on API 33+. The
            // reconnect ladder is unaffected — it keeps retrying whether
            // or not we can draw a notification — so this is a warning,
            // not a failure path.
            Log.w(TAG, "reconnect status notify threw", t)
        }
    }

    /**
     * Tear the surface down on a successful reconnect (or teardown).
     * Safe to call when nothing is showing.
     */
    fun clear() {
        if (!showing) return
        showing = false
        try {
            nm?.cancel(NOTIFICATION_ID_RECONNECT)
        } catch (t: Throwable) {
            Log.w(TAG, "reconnect status cancel threw", t)
        }
    }

    companion object {
        private const val TAG = "XvReconnectNotif"

        // 4711 = XvVoiceService foreground, 4712/4713 = CallStyleNotifier
        // incoming/active, 4801 = AinaA2dpWiring diag.
        private const val NOTIFICATION_ID_RECONNECT = 4714

        // No server hostname here by policy — the notification shade is a
        // screenshot surface and TAK server FQDNs are sensitive content.
        private const val TITLE = "XV: no voice connection"

        // Phrased to promise exactly what the ladder does. "Still trying"
        // is the load-bearing half: it's what distinguishes this from a
        // client that has given up, and it must stay true — if the retry
        // policy ever gains a stop condition, this string is a lie and
        // has to change with it.
        private const val TEXT = "Reconnecting — XV keeps trying until it gets through."
    }
}
