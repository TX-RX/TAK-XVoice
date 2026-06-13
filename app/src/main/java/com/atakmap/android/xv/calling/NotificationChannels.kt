package com.atakmap.android.xv.calling

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build

/**
 * Centralised registry of XV's notification channels.
 *
 * Migrated from the per-feature `ensureXxxChannel()` helpers that
 * previously lived inside [com.atakmap.android.xv.service.XvVoiceService]
 * so the channel definitions can be shared by [CallStyleNotifier] and
 * the service's foreground status notification.
 *
 * Channels declared here:
 *
 *   - [INCOMING] (`xv_call_incoming`): HIGH importance, CATEGORY_CALL,
 *     system ringtone, bypasses DND, lock-screen visible. Used by the
 *     incoming Notification.CallStyle notification. The OS pops a
 *     full-screen ring + heads-up automatically for HIGH-importance
 *     CallStyle notifications, replacing our prior custom
 *     XvIncomingCallActivity full-screen activity.
 *   - [ACTIVE] (`xv_call_active`): LOW importance, CATEGORY_CALL, no
 *     sound. Used by the ongoing-call CallStyle notification so the
 *     operator has Hang Up available from the shade for the full call
 *     duration without ringing every refresh.
 *   - [SERVICE] (`xv_service`): LOW importance, CATEGORY_SERVICE.
 *     Backs the foreground service status notification; replaces the
 *     legacy `xv_voice_session` channel.
 *
 * On API < 26 (NotificationChannel didn't exist), [ensureAll] is a
 * no-op — pre-O devices use NotificationCompat priority levels
 * instead and CallStyle gracefully falls back via [CallStyleNotifier].
 */
object NotificationChannels {
    const val INCOMING = "xv_call_incoming"
    const val ACTIVE = "xv_call_active"
    const val SERVICE = "xv_service"

    fun ensureAll(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                ?: return
        if (nm.getNotificationChannel(INCOMING) == null) {
            nm.createNotificationChannel(buildIncomingChannel())
        }
        if (nm.getNotificationChannel(ACTIVE) == null) {
            nm.createNotificationChannel(buildActiveChannel())
        }
        if (nm.getNotificationChannel(SERVICE) == null) {
            nm.createNotificationChannel(buildServiceChannel())
        }
    }

    private fun buildIncomingChannel(): NotificationChannel {
        // System ringtone with USAGE_NOTIFICATION_RINGTONE attribute so
        // the OS plays it through the ringer stream (not media), respects
        // the user's silent / vibrate-only preference, and ducks media
        // automatically. CallStyle.forIncomingCall on HIGH-importance
        // channels also triggers the system full-screen ring surface,
        // which is the whole point of this migration (replaces our
        // custom XvIncomingCallActivity).
        val ringtone = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        val audioAttrs =
            AudioAttributes
                .Builder()
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                .build()
        return NotificationChannel(
            INCOMING,
            "XV incoming calls",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "Ring + full-screen surface for XV direct calls."
            enableVibration(true)
            setBypassDnd(true)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            setSound(ringtone, audioAttrs)
            setShowBadge(true)
        }
    }

    private fun buildActiveChannel(): NotificationChannel =
        NotificationChannel(
            ACTIVE,
            "XV active calls",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Ongoing-call controls (mute, speaker, hang up)."
            enableVibration(false)
            setSound(null, null)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            setShowBadge(false)
        }

    private fun buildServiceChannel(): NotificationChannel =
        NotificationChannel(
            SERVICE,
            "XV voice service",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Persistent voice session notification while XV is active."
            enableVibration(false)
            setSound(null, null)
            setShowBadge(false)
        }
}
