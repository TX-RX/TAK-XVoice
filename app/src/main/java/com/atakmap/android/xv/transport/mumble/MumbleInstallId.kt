package com.atakmap.android.xv.transport.mumble

import android.util.Log
import androidx.annotation.VisibleForTesting
import com.atakmap.android.maps.MapView
import java.security.MessageDigest

/**
 * Per-device suffix appended after the `---` in the Mumble username
 * `<callsign>---<suffix>`. Deterministic function of the ATAK device
 * UID — same device produces the same suffixes forever.
 *
 * Format: `<6hex>VS<n>` where
 *   - `<6hex>` is the leading 24 bits of SHA-256(deviceUid), lowercase
 *   - `<n>`    is 1 for the primary slot, 2 for the secondary
 *
 * Examples: `a3f8b2VS1`, `a3f8b2VS2`
 *
 * Properties:
 *   - **Stable across reinstall.** Derived from `MapView.getDeviceUid()`
 *     (ATAK's SSAID-backed identifier), so an `adb install` of a fresh
 *     APK with the same signing key produces identical suffixes.
 *     Eliminates the historical pile-up of `<callsign>---<random-uuid>`
 *     orphan rows in the Murmur user table after a development cycle.
 *   - **Compact.** ~9 characters where the previous UUID form was 36;
 *     less Mumble-client UI clutter.
 *   - **Privacy-neutral.** `MapView.getDeviceUid()` is already
 *     published on every connect in `UserState.comment` (XV peer
 *     mapping), so this adds no new device-identifying signal that
 *     Murmur didn't already see. The 24-bit truncation makes the
 *     suffix itself one-way (can't recover deviceUid from it).
 *
 * Collision math: 24 bits × birthday bound ≈ 2^-12 for ~70 devices on
 * a single Murmur server. Plus the full username includes the callsign,
 * so a hash collision only matters when two devices share the SAME
 * callsign — and that's already an operator error the system surfaces
 * by other means.
 *
 * Why a `---` separator is still here despite the call-button feature
 * being deprecated: XV's own Mumble roster scanner uses the presence
 * of `---` as its XV-peer detection signal (`MumbleTransport.kt:656`).
 * Dropping the separator would make XV peers invisible to each other.
 */
object MumbleInstallId {
    private const val TAG = "XvMumbleInstallId"

    /** Suffix for the primary Mumble slot (VS1). */
    fun primarySuffix(): String = suffixFor(deviceUidOrFallback(), "VS1")

    /** Suffix for the secondary Mumble slot (VS2). */
    fun secondarySuffix(): String = suffixFor(deviceUidOrFallback(), "VS2")

    /**
     * Pure-function suffix builder — testable without standing up ATAK.
     * Production callsites resolve [deviceUidOrFallback] then pipe through
     * here; tests pin the (uid, slot) → suffix mapping directly.
     */
    @VisibleForTesting
    internal fun suffixFor(
        uid: String,
        slot: String,
    ): String = shortDeviceHashOf(uid) + slot

    /**
     * SHA-256(uid) truncated to 6 lowercase hex chars (leading 24 bits).
     * Pure function — extracted so MumbleInstallIdTest can pin the
     * hash → suffix mapping deterministically without standing up
     * `MapView.getDeviceUid()` (an ATAK runtime call that doesn't
     * resolve under unit tests). Production callsites go through
     * [suffixFor] which feeds in [deviceUidOrFallback].
     */
    @VisibleForTesting
    internal fun shortDeviceHashOf(uid: String): String =
        try {
            // Explicit update + digest pattern (rather than the one-shot
            // digest(byte[]) convenience). Functionally identical, but
            // satisfies Fortify's "Cryptographic hash finalized without
            // update" check — the SCA flow-analysis can't tell that the
            // single-arg overload feeds its input through update()
            // internally, so it flags the call site every scan.
            val md = MessageDigest.getInstance("SHA-256")
            md.update(uid.toByteArray())
            val digest = md.digest()
            digest.take(3).joinToString("") { "%02x".format(it) }
        } catch (t: Throwable) {
            Log.w(TAG, "shortDeviceHashOf failed for uid='$uid' — using zero fallback", t)
            "000000"
        }

    private fun deviceUidOrFallback(): String =
        try {
            MapView.getDeviceUid() ?: run {
                Log.w(TAG, "MapView.getDeviceUid() returned null — using fallback 'unknown'")
                "unknown"
            }
        } catch (t: Throwable) {
            Log.w(TAG, "MapView.getDeviceUid() threw — using fallback 'unknown'", t)
            "unknown"
        }
}
