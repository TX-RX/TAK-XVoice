package com.atakmap.android.xv.plugin

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.core.content.ContextCompat

/**
 * Tiny launcher activity whose only job is to request XV's runtime
 * permissions. Android 12+ requires runtime grants for Bluetooth even
 * with manifest declarations, and RECORD_AUDIO needs a runtime grant on
 * any version.
 *
 * The MapComponent fires this if it detects missing permissions on plugin
 * load. The activity is also exported so it can be launched from the
 * launcher icon or via `am start` for re-asking after a deny.
 */
class PermissionRequestActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestMissingPermissions()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_CODE) {
            val deniedAny = grantResults.any { it != PackageManager.PERMISSION_GRANTED }
            if (deniedAny) {
                Toast
                    .makeText(
                        this,
                        "XV needs Microphone + Bluetooth to talk on a channel. " +
                            "Grant in Android settings, or re-open this screen.",
                        Toast.LENGTH_LONG,
                    ).show()
            }
            finish()
        }
    }

    private fun requestMissingPermissions() {
        val want = mutableListOf<String>()
        // Microphone — required to TX (Phase 2). Declared now so the user
        // grants once instead of seeing a second prompt later.
        want += Manifest.permission.RECORD_AUDIO
        // CALL_PHONE — required by TelecomManager.placeCall on Android
        // 14+ even for self-managed VoIP. Without it Telecom never
        // takes over audio focus / BT routing for our channel session.
        want += Manifest.permission.CALL_PHONE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            want += Manifest.permission.BLUETOOTH_CONNECT
            want += Manifest.permission.BLUETOOTH_SCAN
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            want += Manifest.permission.POST_NOTIFICATIONS
        }
        val missing =
            want.filter {
                ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
            }
        if (missing.isEmpty()) {
            Toast
                .makeText(this, "XV: permissions already granted", Toast.LENGTH_SHORT)
                .show()
            finish()
            return
        }
        requestPermissions(missing.toTypedArray(), REQ_CODE)
    }

    companion object {
        private const val REQ_CODE = 1
    }
}
