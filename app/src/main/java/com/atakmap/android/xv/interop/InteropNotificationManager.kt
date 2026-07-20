package com.atakmap.android.xv.interop

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.atakmap.android.xv.R
import com.atakmap.android.xv.presence.ChannelCryptoPolicy
import com.atakmap.android.xv.presence.XvPresence
import com.atakmap.android.xv.presence.XvPresenceRegistry
import com.atakmap.android.xv.transport.multicast.ChannelMulticastConfig
import com.atakmap.android.xv.transport.multicast.CryptoPolicy
import com.atakmap.android.xv.transport.multicast.WireFormat

/**
 * Watches [XvPresenceRegistry] for legacy VX users appearing on a channel
 * that is configured with [ChannelCryptoPolicy.PREFER_ENCRYPTION].
 *
 * When a VX peer is detected (XV peer without an enrolled cert / no direct-call
 * capability), a **persistent** notification is shown with two actions:
 *
 *  - **Accept** — downgrades the channel to [ChannelCryptoPolicy.CLEARTEXT],
 *    which sets [CryptoPolicy.CLEARTEXT] + [WireFormat.VX_COMPAT] on the leg.
 *  - **Reject** — dismisses the notification and records the peer's UID in a
 *    local deny-set for the duration of the session (no persist; resets at restart).
 *
 * The decision is intentionally per-notification. Future appearances of the
 * same peer on a newly-configured channel will prompt again.
 *
 * ## Thread safety
 * [XvPresenceRegistry] callbacks fire from arbitrary threads. All state changes
 * here are synchronized on `this`.
 */
class InteropNotificationManager(
    private val pluginContext: Context,
    private val registry: XvPresenceRegistry,
    private val cryptoPolicyForChannel: (String) -> ChannelCryptoPolicy,
    private val onChannelDowngrade: (channelName: String) -> Unit,
) {
    /** UIDs the operator has explicitly rejected for this session. */
    private val rejected = mutableSetOf<String>()

    /** Notification IDs that are currently visible, keyed by peer UID. */
    private val activeNotificationIds = mutableMapOf<String, Int>()

    private val presenceListener: (XvPresence) -> Unit = { presence ->
        handlePresenceUpdate(presence)
    }

    fun start() {
        ensureNotificationChannel()
        registry.addListener(presenceListener)
    }

    fun stop() {
        registry.removeListener(presenceListener)
        cancelAll()
    }

    /** Handle Accept intent from the notification action PendingIntent. */
    @Synchronized
    fun onAccept(channelName: String, peerUid: String) {
        val notifId = activeNotificationIds.remove(peerUid)
        if (notifId != null) {
            NotificationManagerCompat.from(pluginContext).cancel(notifId)
        }
        Log.i(TAG, "operator accepted VX interop on channel=$channelName peer=$peerUid")
        onChannelDowngrade(channelName)
    }

    /** Handle Reject intent from the notification action PendingIntent. */
    @Synchronized
    fun onReject(channelName: String, peerUid: String) {
        rejected.add(peerUid)
        val notifId = activeNotificationIds.remove(peerUid)
        if (notifId != null) {
            NotificationManagerCompat.from(pluginContext).cancel(notifId)
        }
        Log.i(TAG, "operator rejected VX interop on channel=$channelName peer=$peerUid")
    }

    @Synchronized
    private fun handlePresenceUpdate(presence: XvPresence) {
        // We're looking for legacy VX users: they have an XV presence entry
        // (they broadcast __xv CoT) but lack the "direct-call" capability,
        // which was introduced alongside XV's E2E encryption.
        val isLegacyVx = "direct-call" !in presence.capabilities || presence.certFingerprint == null
        if (!isLegacyVx) return

        // Only act if already rejected-set is clear for this peer.
        if (presence.deviceUid in rejected) return

        // Only act if peer is already visible in a notification
        if (presence.deviceUid in activeNotificationIds) return

        // Find the first channel this peer is on where we use PREFER_ENCRYPTION.
        val targetChannel = presence.channels
            .map { it.name }
            .firstOrNull { cryptoPolicyForChannel(it) == ChannelCryptoPolicy.PREFER_ENCRYPTION }
            ?: return

        showNotification(presence, targetChannel)
    }

    private fun showNotification(presence: XvPresence, channelName: String) {
        val displayName = presence.callsign ?: presence.deviceUid.take(12)
        val notifId = presence.deviceUid.hashCode() and 0x7FFF_FFFF

        val acceptIntent = makeActionIntent(ACTION_ACCEPT, channelName, presence.deviceUid)
        val rejectIntent = makeActionIntent(ACTION_REJECT, channelName, presence.deviceUid)

        val notification = NotificationCompat.Builder(pluginContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.xv_tool_icon)
            .setContentTitle("Unencrypted user detected")
            .setContentText("$displayName is on channel \"$channelName\" without encryption.")
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText(
                        "$displayName is joining channel \"$channelName\" using the legacy " +
                            "voice protocol (no encryption). Accept to open the channel to " +
                            "unencrypted users, or Reject to keep encryption-only access.",
                    ),
            )
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOngoing(true) // Sticky — operator must make a decision
            .setAutoCancel(false)
            .addAction(
                android.R.drawable.ic_menu_share,
                "Accept",
                PendingIntent.getBroadcast(
                    pluginContext,
                    notifId + 1,
                    acceptIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            .addAction(
                android.R.drawable.ic_delete,
                "Reject",
                PendingIntent.getBroadcast(
                    pluginContext,
                    notifId + 2,
                    rejectIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            .build()

        activeNotificationIds[presence.deviceUid] = notifId
        NotificationManagerCompat.from(pluginContext).notify(notifId, notification)
        Log.i(TAG, "showed interop notification for uid=${presence.deviceUid} channel=$channelName")
    }

    private fun makeActionIntent(action: String, channelName: String, peerUid: String): Intent =
        Intent(action).apply {
            setPackage(pluginContext.packageName)
            putExtra(EXTRA_CHANNEL_NAME, channelName)
            putExtra(EXTRA_PEER_UID, peerUid)
        }

    private fun ensureNotificationChannel() {
        val nm = pluginContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            val ch = NotificationChannel(
                CHANNEL_ID,
                "Voice Interoperability",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "Alerts when an unencrypted legacy voice user joins a channel"
            }
            nm.createNotificationChannel(ch)
        }
    }

    private fun cancelAll() {
        val nm = NotificationManagerCompat.from(pluginContext)
        activeNotificationIds.values.forEach { nm.cancel(it) }
        activeNotificationIds.clear()
    }

    companion object {
        private const val TAG = "InteropNotifMgr"
        const val CHANNEL_ID = "xv_interop_alerts"
        const val ACTION_ACCEPT = "com.atakmap.android.xv.INTEROP_ACCEPT"
        const val ACTION_REJECT = "com.atakmap.android.xv.INTEROP_REJECT"
        const val EXTRA_CHANNEL_NAME = "channel_name"
        const val EXTRA_PEER_UID = "peer_uid"

        /**
         * Produce an updated [ChannelMulticastConfig] that downgrades the
         * channel's primary leg to cleartext + VX-compat wire format.
         * Called from [XvDropDownReceiver] when the operator accepts.
         */
        fun cleartextConfigFor(existing: ChannelMulticastConfig): ChannelMulticastConfig =
            existing.copy(
                wireFormat = WireFormat.VX_COMPAT,
                cryptoPolicy = CryptoPolicy.CLEARTEXT,
            )
    }
}
