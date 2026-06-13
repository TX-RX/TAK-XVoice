package com.atakmap.android.xv.telecom

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.telecom.PhoneAccount
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.util.Log

// Registers XV with the Android Telecom framework as a self-managed VoIP
// app. CAPABILITY_SELF_MANAGED means the system does NOT show its
// in-call UI, send the call to a Bluetooth headset's call-answer
// button, or otherwise interfere with our PTT UX — XV manages its own
// in-call surface. The system DOES handle audio focus arbitration with
// media apps (Tidal/Spotify pause for active calls), BT route
// privileges (no BLUETOOTH_PRIVILEGED required), and background-audio
// allowances.
//
// One PhoneAccount registers ALL of XV's voice activity. Each Mumble
// channel session creates a separate Connection underneath; the
// PhoneAccount is just the identity tag that says "these calls belong
// to XV." Persists across plugin reloads — registration is idempotent.
object XvPhoneAccount {
    private const val TAG = "XvPhoneAccount"

    // Stable account ID — never changes across XV releases. Telecom
    // dedupes registrations by (componentName, accountId), so reusing
    // this means re-registering doesn't pile up phantom accounts.
    private const val ACCOUNT_ID = "xv_default_voice"

    // URI scheme for our calls. Telecom requires SCHEME_TEL or
    // SCHEME_SIP for self-managed accounts pre-Android 13; from Android
    // 13+ we can use a custom scheme but TEL works on every version
    // and Telecom doesn't care about the URI content for self-managed
    // calls (the system never tries to "dial" it).
    const val SCHEME = PhoneAccount.SCHEME_TEL

    fun handle(context: Context): PhoneAccountHandle =
        PhoneAccountHandle(
            ComponentName(context, XvConnectionService::class.java),
            ACCOUNT_ID,
        )

    /**
     * Register the XV PhoneAccount with Telecom. Idempotent — calling
     * twice replaces the existing registration. Call once at plugin
     * load.
     */
    fun register(context: Context) {
        val tm = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
        if (tm == null) {
            Log.w(TAG, "TelecomManager unavailable — VoIP registration skipped")
            return
        }
        val handle = handle(context)
        val account =
            PhoneAccount
                .builder(handle, "XV")
                // SELF_MANAGED is the only capability we set: VoiceCalling-
                // Indications is now restricted to SIM subscriptions on
                // recent Android (TelecomServiceImpl.enforceRegisterVoice-
                // CallingIndicationCapabilities), so adding it throws
                // SecurityException at registerPhoneAccount time.
                .setCapabilities(PhoneAccount.CAPABILITY_SELF_MANAGED)
                .addSupportedUriScheme(SCHEME)
                .setShortDescription("XV tactical voice")
                .build()
        try {
            tm.registerPhoneAccount(account)
            Log.i(TAG, "registered self-managed PhoneAccount handle=$handle")
        } catch (t: Throwable) {
            // Self-managed registration can throw on devices/builds that
            // restrict Telecom to system apps (rare on stock Android,
            // possible on locked-down enterprise builds). Surface the
            // error so the operator can fall back to foreground audio.
            Log.e(TAG, "registerPhoneAccount failed — Telecom-mediated voice unavailable", t)
        }
    }

    /**
     * Unregister on plugin teardown. Telecom auto-cleans phantom
     * accounts when the package is uninstalled, but explicit unregister
     * keeps the system's account list tidy across plugin reloads.
     */
    fun unregister(context: Context) {
        val tm = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager ?: return
        try {
            tm.unregisterPhoneAccount(handle(context))
            Log.i(TAG, "unregistered PhoneAccount")
        } catch (t: Throwable) {
            Log.w(TAG, "unregisterPhoneAccount threw", t)
        }
    }

    /**
     * Build the EXTRAS bundle for [TelecomManager.placeCall] to wire
     * the outgoing-call attempt to our PhoneAccount. Without this the
     * call goes to the system default (cellular), which fails for
     * self-managed VoIP.
     */
    fun placeCallExtras(
        context: Context,
        callerDisplayName: String,
    ): Bundle =
        Bundle().apply {
            putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, handle(context))
            putString(TelecomManager.EXTRA_CALL_SUBJECT, callerDisplayName)
        }

    /**
     * Build the placeholder URI for [TelecomManager.placeCall]. Telecom
     * requires SOMETHING for self-managed calls; the content is
     * cosmetic.
     */
    fun callUri(channelTag: String): Uri = Uri.fromParts(SCHEME, channelTag, null)

    // Extras keys read by XvConnectionService.onCreateIncomingConnection
    // to recover the originating private-call info that the plugin
    // passed via notifyIncomingCall(). Telecom round-trips the bundle
    // unchanged from addNewIncomingCall to onCreateIncomingConnection.
    const val EXTRA_TEMP_CHANNEL_ID = "com.atakmap.android.xv.tempChannelId"
    const val EXTRA_CALLER_SESSION = "com.atakmap.android.xv.callerSession"
    const val EXTRA_CALLER_CALLSIGN = "com.atakmap.android.xv.callerCallsign"

    /**
     * Build the EXTRAS bundle for [TelecomManager.addNewIncomingCall].
     * Carries the VX private-call context (temp channel + caller session
     * + caller callsign) through Telecom's bundle round-trip into
     * XvConnectionService.onCreateIncomingConnection, where it's read
     * back into the XvConnection.
     */
    fun incomingCallExtras(
        callerCallsign: String,
        tempChannelId: Int,
        callerSession: Int,
    ): Bundle =
        Bundle().apply {
            // EXTRA_INCOMING_CALL_EXTRAS is the canonical envelope for
            // free-form addNewIncomingCall extras. Telecom unpacks it
            // and passes the contents to onCreateIncomingConnection.
            val inner =
                Bundle().apply {
                    putString(EXTRA_CALLER_CALLSIGN, callerCallsign)
                    putInt(EXTRA_TEMP_CHANNEL_ID, tempChannelId)
                    putInt(EXTRA_CALLER_SESSION, callerSession)
                }
            putBundle(TelecomManager.EXTRA_INCOMING_CALL_EXTRAS, inner)
        }
}
