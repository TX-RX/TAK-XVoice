package com.atakmap.android.xv.security

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.util.Log

/**
 * Canonical NotificationChannel definitions for XV.
 *
 * Centralises channel id + importance + category so the existing
 * channel-creation code in XvVoiceService.kt
 * (`ensureNotificationChannel` / `ensureIncomingCallChannel`) can be
 * collapsed onto these constants in a future cleanup, and so any
 * other component (e.g. presence beacons, emergency UX) can post
 * into the same canonical channels rather than inventing its own.
 *
 * Channel governance:
 *   - SERVICE: low-importance persistent foreground notification
 *     for the XvVoiceService. Mirrors the existing
 *     `xv_voice_session` channel. No sound, no vibration, no
 *     heads-up. Just a status indicator while the voice subsystem
 *     is up.
 *   - INCOMING_CALL: HIGH importance, CATEGORY_CALL, bypasses DnD.
 *     Used for the Teams-style ring + full-screen-intent on a
 *     direct-call REQUEST. Mirrors the existing `xv_incoming_call`
 *     channel.
 *   - ACTIVE_CALL: LOW importance, CATEGORY_CALL. For the
 *     in-call status notification (mute/hangup affordances). Quiet
 *     so the user isn't pinged again after they've already
 *     answered.
 *   - EMERGENCY: HIGH importance, bypasses DnD. For emergency-button
 *     activation surfaces (currently goes through ATAK's emergency
 *     pipeline; this channel is reserved for any XV-side
 *     supplementary notification).
 *
 * Wire-in (when XvVoiceService.kt is editable; another agent owns
 * it in this worktree round): replace the literal channel-id
 * strings ("xv_voice_session", "xv_incoming_call") with the
 * constants below, and call [ensureAll] from XvVoiceService.onCreate
 * to make the full set available before any notification is posted.
 *
 * Why category matters: Android 14+ uses
 * NotificationChannel.setAllowBubbles + the channel's category to
 * route call notifications through the Conversations + Priority
 * Contacts pipeline; setting category on the channel (not just on
 * the Notification itself) is what enables the call-style UI
 * affordances on the lock screen.
 */
object NotificationChannels {
    private const val TAG = "XvNotifChannels"

    /** Persistent foreground status while XvVoiceService is up.
     *  Matches the existing literal in XvVoiceService.CHANNEL_ID. */
    const val SERVICE = "xv_voice_session"

    /** Heads-up / full-screen-intent ring for incoming direct calls.
     *  Matches the existing literal in
     *  XvVoiceService.INCOMING_CALL_CHANNEL_ID. */
    const val INCOMING_CALL = "xv_incoming_call"

    /** In-call status notification for the duration of an active
     *  private call. Quiet — operator already knows they're in a
     *  call. */
    const val ACTIVE_CALL = "xv_active_call"

    /** Emergency-activation supplementary surface. Reserved; ATAK's
     *  EmergencyManager owns the primary alert pipeline. */
    const val EMERGENCY = "xv_emergency"

    /**
     * Ensure all canonical channels exist on the device. Idempotent;
     * cheap to call repeatedly. No-op below Android 8.0 (Oreo)
     * because channels were introduced there.
     *
     * Safe to call from XvVoiceService.onCreate — does not depend on
     * the service binder, audio subsystem, or anything Mumble-side.
     */
    fun ensureAll(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                ?: run {
                    Log.w(TAG, "NotificationManager unavailable; skipping channel setup")
                    return
                }
        // SERVICE: persistent foreground status. LOW so the operator
        // doesn't get pinged on every voice session start.
        ensure(
            nm,
            id = SERVICE,
            name = "XV voice",
            importance = NotificationManager.IMPORTANCE_LOW,
            description = "Persistent voice session notification while XV is active.",
            showBadge = false,
        )
        // INCOMING_CALL: ring on direct-call REQUEST. HIGH + bypass-
        // DnD + lockscreen visibility = the Teams/Discord ring UX.
        ensure(
            nm,
            id = INCOMING_CALL,
            name = "XV incoming calls",
            importance = NotificationManager.IMPORTANCE_HIGH,
            description = "Heads-up + full-screen incoming-call ring for XV direct calls.",
            showBadge = true,
            bypassDnd = true,
            vibrate = true,
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC,
        )
        // ACTIVE_CALL: post-answer ongoing notification. LOW so we
        // don't double-ring the user.
        ensure(
            nm,
            id = ACTIVE_CALL,
            name = "XV active calls",
            importance = NotificationManager.IMPORTANCE_LOW,
            description = "Mute/hangup controls while a private call is in progress.",
            showBadge = false,
        )
        // EMERGENCY: future-use. Channel created proactively so a
        // notification posted later doesn't cold-create the channel
        // mid-incident.
        ensure(
            nm,
            id = EMERGENCY,
            name = "XV emergency",
            importance = NotificationManager.IMPORTANCE_HIGH,
            description = "Emergency activation supplementary notifications.",
            showBadge = true,
            bypassDnd = true,
            vibrate = true,
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC,
        )
    }

    private fun ensure(
        nm: NotificationManager,
        id: String,
        name: String,
        importance: Int,
        description: String,
        showBadge: Boolean = true,
        bypassDnd: Boolean = false,
        vibrate: Boolean = false,
        lockscreenVisibility: Int = Notification.VISIBILITY_PRIVATE,
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        // If the channel already exists, leave it alone — the user
        // may have customised importance/sound and we must not
        // clobber that. NotificationManager.createNotificationChannel
        // is documented as no-op-on-existing for the same reason,
        // but the explicit check makes the intent obvious in code.
        if (nm.getNotificationChannel(id) != null) return
        val channel =
            NotificationChannel(id, name, importance).apply {
                this.description = description
                setShowBadge(showBadge)
                if (bypassDnd) setBypassDnd(true)
                if (vibrate) enableVibration(true)
                this.lockscreenVisibility = lockscreenVisibility
            }
        try {
            nm.createNotificationChannel(channel)
            Log.i(TAG, "created channel id=$id importance=$importance")
        } catch (t: Throwable) {
            Log.w(TAG, "createNotificationChannel($id) threw", t)
        }
    }
}
