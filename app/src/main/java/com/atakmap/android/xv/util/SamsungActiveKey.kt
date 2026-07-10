package com.atakmap.android.xv.util

import android.content.Context
import android.os.Build

/**
 * Capability detection for the programmable Active Key found on Samsung
 * ruggedized tablets and phones (Galaxy Tab Active5, Tab Active4/3,
 * Galaxy XCover6 Pro / XCover7, etc.).
 *
 * When present, pressing / releasing the key causes the Samsung
 * framework to broadcast the system intent
 * `com.samsung.android.knox.intent.action.HARD_KEY_REPORT` with the
 * following extras:
 *   - `com.samsung.android.knox.intent.extra.KEY_CODE`   — int
 *       * 1015 = XCover / Active / PTT key (side)
 *       * 1079 = Top / Emergency key
 *   - `com.samsung.android.knox.intent.extra.KEY_REPORT_TYPE` — int
 *       * 1 = pressed
 *       * 2 = released
 *
 * Despite the `knox` prefix in the action string, the broadcast is
 * emitted by the Samsung framework itself on qualifying hardware and
 * does NOT require the Knox SDK, a Knox license, or any Samsung
 * enterprise entitlement to receive. Any app that registers a
 * [android.content.BroadcastReceiver] for the action gets the events.
 *
 * We therefore do NOT depend on the Knox SDK jar. The reader
 * ([com.atakmap.android.xv.ptt.SamsungActiveKeyReader]) simply
 * registers a normal broadcast receiver against the well-known action
 * string when the operator flips the toggle in Settings.
 *
 * Sources (2026-07):
 *   - Samsung Knox docs, "Unmanaged key mappings":
 *     https://docs.samsungknox.com/dev/knox-sdk/features/independent-software-vendors-da/hardware-key-mappings/unmanaged-key-mappings/
 *   - Samsung Knox docs, "Hardware key mapping":
 *     https://docs.samsungknox.com/dev/knox-sdk/features/independent-software-vendors-da/hardware-key-mappings/hardware-key-mapping/
 *
 * ---
 *
 * Curated-hardware policy (per CLAUDE.md README rules): this helper
 * gates a Settings row that only appears on devices that actually have
 * the key. Non-Samsung devices are a zero-cost path — the row is
 * hidden with `View.GONE` in the drawer code so operators never see a
 * "greyed-out feature they can't use". The detection is intentionally
 * conservative — start with the Samsung ruggedized model prefixes we
 * have integrated evidence for, and grow the list as more hardware is
 * validated end-to-end.
 */
object SamsungActiveKey {
    /**
     * Broadcast action fired by the Samsung framework when the Active
     * Key / XCover key is pressed or released. Stable and documented
     * in the Samsung Knox hardware-key-mapping guide referenced above.
     */
    const val ACTION_HARD_KEY_REPORT: String =
        "com.samsung.android.knox.intent.action.HARD_KEY_REPORT"

    /**
     * Int extra — which key fired. `KEYCODE_PTT` = 1015 is the side
     * Active Key on Tab Active5 / XCover6 Pro / etc.;
     * `KEYCODE_EMERGENCY` = 1079 is the top key on XCovers. We
     * currently listen for the PTT keycode only; the emergency
     * button is out of scope for this reader.
     */
    const val EXTRA_KEY_CODE: String =
        "com.samsung.android.knox.intent.extra.KEY_CODE"

    /**
     * Int extra — 1 = press, 2 = release. Anything else is an
     * unexpected value (log at debug and drop).
     */
    const val EXTRA_KEY_REPORT_TYPE: String =
        "com.samsung.android.knox.intent.extra.KEY_REPORT_TYPE"

    /** Samsung `KEYCODE_PTT` (side Active Key on Tab Active5 etc.). */
    const val KEY_CODE_PTT: Int = 1015

    /** Samsung `KEY_REPORT_TYPE` "pressed". */
    const val KEY_REPORT_TYPE_PRESSED: Int = 1

    /** Samsung `KEY_REPORT_TYPE` "released". */
    const val KEY_REPORT_TYPE_RELEASED: Int = 2

    // Model-prefix allow-list for hardware that carries the
    // programmable Active Key. Populated conservatively — extend as
    // additional ruggedized Samsung models are validated end-to-end
    // per CLAUDE.md's curated-hardware policy.
    //
    // Tab Active5 (SM-X30x): X300 = Wi-Fi, X306 = 5G B/N variants,
    // X308 = 5G U variant.
    // Tab Active4 Pro: SM-T63x.
    // Tab Active3: SM-T57x.
    // XCover7: SM-G556.
    // XCover6 Pro: SM-G736.
    private val ACTIVE_KEY_MODEL_PREFIXES: List<String> =
        listOf(
            // Tab Active5 series
            "SM-X300",
            "SM-X306",
            "SM-X308",
            // Tab Active4 Pro
            "SM-T630",
            "SM-T636",
            // Tab Active3
            "SM-T570",
            "SM-T575",
            "SM-T577",
            // XCover7
            "SM-G556",
            // XCover6 Pro
            "SM-G736",
        )

    /**
     * True when the current device is a Samsung ruggedized model that
     * carries the programmable Active Key. Consulted at Settings-row
     * inflation time to decide whether to show the "Enable Samsung
     * Active Key as PTT" toggle at all. On any non-Samsung device this
     * is a fast constant `false`.
     *
     * [context] is accepted for symmetry with the Android-runtime
     * shape (a future extension may consult
     * [android.content.pm.PackageManager.hasSystemFeature] if Samsung
     * ever exposes a feature flag), but the current check reads only
     * [android.os.Build.MANUFACTURER] + [android.os.Build.MODEL] and
     * is safe to call from any thread.
     */
    fun isSupported(
        @Suppress("UNUSED_PARAMETER") context: Context,
    ): Boolean =
        isSupportedInternal(
            manufacturer = Build.MANUFACTURER.orEmpty(),
            model = Build.MODEL.orEmpty(),
            features = emptySet(),
        )

    /**
     * Pure test seam for [isSupported]. Takes the interesting Build
     * fields + system-feature set as parameters so it can be exercised
     * without an Android runtime. Robolectric is NOT required.
     */
    fun isSupportedInternal(
        manufacturer: String,
        model: String,
        features: Set<String>,
    ): Boolean {
        if (!manufacturer.equals("samsung", ignoreCase = true)) {
            return false
        }
        // Model prefix match. Model strings from Samsung firmware are
        // upper-cased (e.g. "SM-X306B") but be defensive — trim + case-
        // insensitive compare so a lowercase Build.MODEL from some
        // future firmware doesn't mis-detect.
        val normalizedModel = model.trim()
        if (normalizedModel.isEmpty()) return false
        val prefixMatch =
            ACTIVE_KEY_MODEL_PREFIXES.any { prefix ->
                normalizedModel.startsWith(prefix, ignoreCase = true)
            }
        if (prefixMatch) return true
        // Feature-flag path. Samsung does not currently publish an
        // official system feature for the Active Key, but if a future
        // firmware exposes one via PackageManager.hasSystemFeature, we
        // honour it as an additional positive signal so operator
        // hardware we haven't hard-listed still lights up the toggle.
        // The exact string is speculative — we accept two commonly-
        // referenced candidates so a real Samsung feature flag lands
        // in either form without an XV update.
        return features.any { feat ->
            feat.equals("com.samsung.hardware.active_key", ignoreCase = true) ||
                feat.equals("com.samsung.feature.active_key", ignoreCase = true)
        }
    }
}
