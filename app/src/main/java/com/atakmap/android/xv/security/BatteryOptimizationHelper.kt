package com.atakmap.android.xv.security

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.util.Log

/**
 * Helper for the Doze / App Standby exemption flow.
 *
 * Why XV needs this:
 *   - XvVoiceService is a foreground service (microphone +
 *     connectedDevice + phoneCall during a call), but Doze can still
 *     suspend its sockets when the screen has been off for a few
 *     minutes and the device classifies XV as "non-essential". On
 *     Android 12+ this manifests as Mumble TCP/UDP packets stalling
 *     for 30-60s, then a transient reconnect storm when the screen
 *     comes back.
 *   - The fix is to ask the user to mark XV as "Unrestricted" battery
 *     usage. Android phrases this as "Ignore battery optimizations"
 *     in the underlying system intent.
 *
 * Design:
 *   - [isExempt] is a pure status check; safe to call from any thread,
 *     no UI side effects.
 *   - [requestExemption] fires the system dialog, which Android
 *     surfaces as "Allow XV to use battery without restriction?".
 *     The user can answer Yes / No. We do not retry.
 *
 * Recommended wire-in (XvMapComponent.onCreate, after permissions
 * are granted):
 *
 *   if (!BatteryOptimizationHelper.isExempt(context)) {
 *       // Show a one-shot coachmark or toast explaining why,
 *       // then on user-confirm:
 *       BatteryOptimizationHelper.requestExemption(activity)
 *   }
 *
 * The coachmark is important — Google guidance is to never ask for
 * this exemption silently. A "tap here to enable always-on voice"
 * affordance with a short explanation is the right pattern.
 *
 * Related observation (not in scope for this helper, but flagged
 * because it lives in the same neighborhood): NetworkAvailability-
 * Watcher.kt currently doesn't null-check the return of
 * [android.net.ConnectivityManager.getActiveNetwork], which can
 * return null when Wi-Fi is off and cellular is unavailable. The
 * watcher uses NetworkCallback (which is the correct API) so this
 * is moot in practice, but if a future change adds a one-shot
 * getActiveNetwork() probe, that path needs a defensive null guard.
 */
object BatteryOptimizationHelper {
    private const val TAG = "XvBatteryOpt"

    /**
     * True when the OS has flagged this app as exempt from Doze /
     * App Standby battery restrictions. Equivalent to the user
     * having set XV's battery usage to "Unrestricted" in Settings.
     *
     * Returns true on devices where PowerManager isn't available
     * (none in practice on Android 6+) so the caller doesn't loop
     * trying to request exemption.
     */
    fun isExempt(context: Context): Boolean {
        val pm =
            context.getSystemService(Context.POWER_SERVICE) as? PowerManager
                ?: run {
                    Log.w(TAG, "PowerManager unavailable; treating as exempt")
                    return true
                }
        return try {
            pm.isIgnoringBatteryOptimizations(context.packageName)
        } catch (t: Throwable) {
            Log.w(TAG, "isIgnoringBatteryOptimizations threw — assuming not exempt", t)
            false
        }
    }

    /**
     * Fire the system dialog asking the user to grant battery-opt
     * exemption. The OS shows its own confirmation UI; we don't
     * provide a fallback because if the user declines, badgering
     * them is the wrong UX. Caller is responsible for showing a
     * coachmark BEFORE invoking this so the user has context.
     *
     * Must be called with an [Activity] so the system dialog has a
     * task to attach to (FLAG_ACTIVITY_NEW_TASK would also work but
     * launching system Settings cross-task is jarring; prefer the
     * activity-rooted version).
     *
     * Returns true if the intent was successfully fired, false if
     * something blew up. False does NOT mean the user declined —
     * we have no callback for the dialog result; query [isExempt]
     * later to confirm.
     */
    fun requestExemption(activity: Activity): Boolean {
        if (isExempt(activity)) {
            Log.i(TAG, "already exempt; skipping request")
            return true
        }
        return try {
            val pkgUri = Uri.parse("package:${activity.packageName}")
            val intent =
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkgUri)
            activity.startActivity(intent)
            Log.i(TAG, "battery-opt exemption requested for ${activity.packageName}")
            true
        } catch (t: Throwable) {
            // ActivityNotFoundException happens on a handful of
            // OEM ROMs (notably some Chinese-market builds) that
            // strip the standard battery-opt UI. Fall back to the
            // generic battery settings page so the user can at
            // least navigate to it manually.
            Log.w(TAG, "ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS unavailable; falling back to settings", t)
            try {
                activity.startActivity(
                    Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
                )
                true
            } catch (t2: Throwable) {
                Log.e(TAG, "no battery-opt settings UI on this device", t2)
                false
            }
        }
    }
}
