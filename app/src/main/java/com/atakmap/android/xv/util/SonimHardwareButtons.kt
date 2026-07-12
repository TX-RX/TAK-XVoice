package com.atakmap.android.xv.util

import android.content.Context
import android.os.Build
import android.view.KeyEvent

/**
 * Capability detection + KeyEvent classification for the two dedicated
 * hardware buttons on Sonim ruggedized Android devices — specifically
 * the Sonim XP10 (Build.MODEL = `XP9900`) which carries:
 *
 *   - a **side PTT button** — the oversized Push-to-Talk key, and
 *   - an **Emergency / SOS button** — a distinct red key intended for
 *     lone-worker / distress use.
 *
 * Both buttons are exposed to third-party apps by the Sonim framework
 * (no Sonim SDK or partner entitlement required to observe the events)
 * via TWO complementary paths:
 *
 *   1) **Broadcast intents**, when the operator maps the button to the
 *      target app in Settings → Programmable keys. This path is
 *      backgrounded-safe. Sonim device intents (well-documented on
 *      Sonim SDK / community reference sites):
 *        - PTT press / release (classic Sonim-namespaced):
 *            `com.sonim.intent.action.PTT_KEY_DOWN`
 *            `com.sonim.intent.action.PTT_KEY_UP`
 *        - PTT press / release (MCX / MCPTT mode, AT&T carrier firmware):
 *            `com.mcx.intent.action.CRITICAL_COMMUNICATION_CONTROL_KEY`
 *            Uses a `"state"` integer extra: 1 = pressed, 0 = released.
 *            First observed on XP9900 (AT&T carrier, Android 12) where
 *            the Sonim SDK policy engine checks for this action rather
 *            than the classic `PTT_KEY_DOWN` / `PTT_KEY_UP` strings.
 *        - SOS press / release:
 *            `android.intent.action.SOS.down`
 *            `android.intent.action.SOS.up`
 *      `SonimPttButtonReader` registers for all three PTT actions so
 *      both classic and MCX-mode firmware work without separate code
 *      paths. `PttDispatcher`'s source-based OR-gate prevents double-
 *      fire if a firmware emits more than one of the three actions for
 *      a single physical press.
 *
 *   2) **Plain [KeyEvent]s** delivered to the foreground activity via
 *      `PhoneWindowManager.interceptKeyTq` → `InputDispatcher`. This
 *      path is foreground-only (works only while ATAK is what the
 *      operator is looking at) but requires no per-app mapping from
 *      the operator — the events arrive whenever the app is on top.
 *      Keycodes chosen based on best-effort research:
 *        - PTT: `KEYCODE_HEADSETHOOK` = 79 is the most commonly
 *          reported Sonim-family PTT keycode; TODO: verify on-device
 *          against XP10 shipping firmware. If the XP10 emits a
 *          different keycode, plumb the alternative into
 *          [PTT_KEY_CODE_ALT] and adjust [handlePttKeyEvent]
 *          accordingly.
 *        - SOS / Emergency: `KEYCODE_SOS` = 1079 (Android platform
 *          constant added for lone-worker devices; the value used
 *          consistently in Sonim SOS broadcasts and Ruggear-family
 *          KeyEvent traffic).
 *
 * Both paths coexist and dedupe via the central
 * [com.atakmap.android.xv.audio.PttDispatcher]'s source-based OR-gate:
 * a duplicate down edge for [com.atakmap.android.xv.audio.PttSource.SONIM_PTT]
 * (or [com.atakmap.android.xv.audio.PttSource.SONIM_EMERGENCY]) while
 * the source is already held is ignored, so a firmware that happens to
 * emit both the broadcast and the KeyEvent for the same press produces
 * exactly one TX engage.
 *
 * ---
 *
 * ### Why no Sonim SDK dependency
 *
 * The Sonim SPCC (Sonim Public Communication Component) SDK exists but
 * is aimed at deeper integrations (device management, secure enterprise
 * flows). Observing the two hardware buttons does NOT require it —
 * the broadcast intents named above are dispatched to any registered
 * receiver on qualifying hardware, and the KeyEvent path is a plain
 * Android input primitive. We deliberately do NOT vendor the Sonim
 * SDK jar; keeping the plugin dependency-clean is important for the
 * public Apache-2.0 licensing story.
 *
 * ### Sources (2026-07)
 *
 *   - Android hardware-button reference (devtut.github.io, Sonim
 *     PTT_KEY_DOWN / PTT_KEY_UP):
 *     https://devtut.github.io/android/hardware-button-events-intents-ptt-lwp-etc.html
 *   - B4X forum thread on Sonim / ruggedized SOS broadcast + KEYCODE
 *     integer (`android.intent.action.SOS.down/up` + `KEYCODE_SOS = 1079`):
 *     https://www.b4x.com/android/forum/threads/capture-hardware-button-press-using-an-intent.165462/
 *   - Sonim XP10 product page (confirms the XP10's commercial model
 *     number is `XP9900`):
 *     https://www.sonimtech.com/products/phones/xp10
 *   - DeviceAtlas Sonim XP9900 entry (identifies the device to
 *     external tooling as vendor=Sonim, model=XP9900):
 *     https://deviceatlas.com/device-data/devices/sonim/xp9900/72408881
 *   - Sonim SPCC overview (confirms the SDK is optional and buttons
 *     have a programmable API, not an SDK-only path):
 *     https://developer.firstnet.com/firstnet/apis-sdks/sonim-spcc
 *   - On-device validation (XP9900 AT&T carrier, Android 12, 2026-07-11):
 *     Confirmed `Build.MODEL = XP9900`, `Build.BRAND = Sonim`,
 *     `Build.MANUFACTURER = Sonimtech`. AT&T carrier firmware activates
 *     the MCX/MCPTT policy engine — PTT button fires under
 *     `com.mcx.intent.action.CRITICAL_COMMUNICATION_CONTROL_KEY` with
 *     a `"state"` extra (1 = pressed, 0 = released) rather than the
 *     classic Sonim-namespaced `PTT_KEY_DOWN` / `PTT_KEY_UP` actions.
 *     `isSupported()` correctly returns `true` (BRAND check catches
 *     `Sonimtech` manufacturer via the OR path). Prerequisite: operator
 *     must set Settings → System → Buttons (Programmable keys) → PTT
 *     key → No Action to release the button from AT&T Dispatch Hub.
 *
 * ---
 *
 * ### Curated-hardware policy (per CLAUDE.md README rules)
 *
 * This helper gates two Settings rows that only appear on devices
 * that actually carry the Sonim PTT / SOS keys. Non-Sonim devices
 * are a zero-cost path — both rows are hidden with `View.GONE` in
 * the drawer code so operators never see "greyed-out features they
 * can't use". The model-prefix allow-list starts conservatively at
 * the XP10 (`XP9900`) and grows only as additional Sonim ruggedized
 * models are validated end-to-end.
 */
object SonimHardwareButtons {
    // ============================================================
    // Broadcast intent surface
    // ============================================================

    /**
     * Broadcast action fired by the Sonim framework when the dedicated
     * PTT side key is pressed. Emitted by qualifying Sonim ruggedized
     * hardware (XP10 and XP-series peers) when the operator has
     * assigned the PTT key to the receiving app in Sonim's
     * programmable-keys system settings.
     */
    const val ACTION_PTT_KEY_DOWN: String = "com.sonim.intent.action.PTT_KEY_DOWN"

    /** Broadcast action fired by the Sonim framework when the PTT key is released. */
    const val ACTION_PTT_KEY_UP: String = "com.sonim.intent.action.PTT_KEY_UP"

    /**
     * Broadcast action fired when the Emergency / SOS button is pressed.
     * Uses the Android-style `action.SOS.down` convention rather than
     * a Sonim-namespaced action; this is the form observed on Sonim
     * and other ruggedized OEMs' shipping firmware.
     */
    const val ACTION_SOS_DOWN: String = "android.intent.action.SOS.down"

    /** Broadcast action fired when the SOS button is released. */
    const val ACTION_SOS_UP: String = "android.intent.action.SOS.up"

    /**
     * MCX / MCPTT broadcast action for the PTT side button on
     * carrier-branded Sonim firmware (AT&T XP9900, Android 12). Used
     * by the Sonim SDK policy engine on MCPTT-mode handsets instead of
     * (or in addition to) the classic [ACTION_PTT_KEY_DOWN] /
     * [ACTION_PTT_KEY_UP] pair. Unlike the classic actions, this is a
     * single action that carries a [MCX_EXTRA_STATE] integer extra
     * indicating the button edge: [MCX_STATE_PRESSED] on press,
     * [MCX_STATE_RELEASED] on release.
     *
     * `SonimPttButtonReader` registers for this action alongside the
     * classic Sonim actions so the PTT button works on both firmware
     * variants. `PttDispatcher`'s source-based OR-gate prevents double-
     * fire if a firmware happens to emit both forms for the same press.
     */
    const val ACTION_MCX_KEY: String =
        "com.mcx.intent.action.CRITICAL_COMMUNICATION_CONTROL_KEY"

    /**
     * Intent extra key carried with [ACTION_MCX_KEY] broadcasts.
     * Integer value: [MCX_STATE_PRESSED] when the button is pressed,
     * [MCX_STATE_RELEASED] when the button is released. If the extra
     * is absent the broadcast is ignored (treated as malformed).
     */
    const val MCX_EXTRA_STATE: String = "state"

    /** [MCX_EXTRA_STATE] value indicating the PTT button was pressed. */
    const val MCX_STATE_PRESSED: Int = 1

    /** [MCX_EXTRA_STATE] value indicating the PTT button was released. */
    const val MCX_STATE_RELEASED: Int = 0

    /**
     * Best-effort primary KeyEvent code for the Sonim PTT side button.
     * `KEYCODE_HEADSETHOOK` (79) is the most commonly reported Sonim
     * PTT keycode across community references — chosen as the primary
     * because it is what several Sonim-family phones (XP3plus, XP5s,
     * XP8) route through Android input dispatch. TODO: verify on-device
     * on an actual XP10 that this is what the side PTT emits. If the
     * XP10 shipping firmware uses a different keycode, the
     * [PTT_KEY_CODE_ALT] fallback is checked in parallel so the
     * operator can still get PTT working without a firmware update.
     */
    const val PTT_KEY_CODE_PRIMARY: Int = KeyEvent.KEYCODE_HEADSETHOOK

    /**
     * Alternative KeyEvent code we also treat as a Sonim PTT press.
     * `KEYCODE_CALL` (5) is the second most commonly-reported Sonim
     * PTT mapping (some firmware routes the PTT key through the
     * telephony call keycode when telephony PTT clients aren't
     * installed). Checked in addition to [PTT_KEY_CODE_PRIMARY] so
     * whichever mapping the XP10 firmware actually uses, we catch it.
     * TODO: verify on-device — if only one of the two fires, prune
     * the other to reduce noise in log output.
     */
    const val PTT_KEY_CODE_ALT: Int = KeyEvent.KEYCODE_CALL

    /**
     * KeyEvent code for the Sonim / ruggedized-Android SOS button.
     * `KEYCODE_SOS` (1079) is the Android platform integer used
     * consistently across ruggedized OEMs (Sonim / Ruggear / other
     * lone-worker devices) for the dedicated red SOS key.
     * Cross-referenced against the Android `KeyEvent` constant name
     * and the value emitted alongside `android.intent.action.SOS.down`
     * on shipping ruggedized hardware.
     */
    const val SOS_KEY_CODE: Int = 1079

    // ============================================================
    // Device gate
    // ============================================================

    // Model-prefix allow-list for Sonim ruggedized hardware that
    // carries the dedicated PTT + SOS keys. Populated conservatively
    // per CLAUDE.md's curated-hardware policy — start with what we
    // have integration evidence for and grow the list only after each
    // model is validated end-to-end.
    //
    // XP10 is the XP9900 — Sonim's current 5G flagship. The variant
    // suffix (nothing / -A / regional) can vary; we prefix-match
    // rather than exact-match so a regional variant we haven't seen
    // yet still lights up the toggle.
    //
    // Older Sonim models (XP5, XP7, XP8, XP3plus) also have PTT keys
    // and are candidates for a later PR — deliberately out of scope
    // here so we can validate the XP10 path end-to-end first.
    private val SONIM_MODEL_PREFIXES: List<String> =
        listOf(
            // XP10 — commercial model number XP9900. Regional variants
            // (AT&T / Verizon / T-Mobile / global) share the XP9900
            // prefix in Build.MODEL. Confirmed on-device: AT&T carrier
            // XP9900 (Android 12) reports `Build.MODEL = "XP9900"`.
            "XP9900",
            // Alternative Build.MODEL form observed in Sonim
            // documentation and some community references. Keep as
            // belt-and-suspenders in case a firmware variant reports
            // "XP10" rather than "XP9900" in Build.MODEL.
            "XP10",
        )

    /**
     * True when the current device is a Sonim ruggedized model whose
     * hardware layout carries the dedicated PTT + SOS buttons.
     * Consulted at Settings-row inflation time to decide whether to
     * show either of the "Enable Sonim PTT / Emergency button as PTT"
     * toggles at all. On any non-Sonim device this is a fast constant
     * `false`.
     *
     * The check is intentionally lenient — matches on
     * [android.os.Build.MANUFACTURER] `equals "sonim"` OR
     * [android.os.Build.BRAND] `equals "sonim"` AND a model prefix
     * from the allow-list. On-device validation (XP9900 AT&T, Android
     * 12) confirmed `BRAND = "Sonim"` and `MANUFACTURER = "Sonimtech"`.
     * The OR-gate via BRAND means `Sonimtech` is handled without a
     * separate manufacturer entry.
     *
     * [context] is accepted for symmetry with the Android-runtime
     * shape and future extension (a Sonim system feature flag, if
     * one is ever published), but the current check reads only
     * [android.os.Build] and is safe to call from any thread.
     */
    fun isSupported(
        @Suppress("UNUSED_PARAMETER") context: Context,
    ): Boolean =
        isSupportedInternal(
            manufacturer = Build.MANUFACTURER.orEmpty(),
            brand = Build.BRAND.orEmpty(),
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
        brand: String,
        model: String,
        features: Set<String>,
    ): Boolean {
        val isSonim =
            manufacturer.equals("sonim", ignoreCase = true) ||
                brand.equals("sonim", ignoreCase = true)
        if (!isSonim) return false
        val normalizedModel = model.trim()
        if (normalizedModel.isEmpty()) return false
        val prefixMatch =
            SONIM_MODEL_PREFIXES.any { prefix ->
                normalizedModel.startsWith(prefix, ignoreCase = true)
            }
        if (prefixMatch) return true
        // Feature-flag path. Sonim does not currently publish a
        // documented system feature for the programmable keys, but if a
        // future firmware exposes one via PackageManager.hasSystemFeature,
        // we honour it as an additional positive signal so operator
        // hardware we haven't hard-listed still lights up the toggle.
        // The exact string is speculative — accept two commonly-namespaced
        // candidates so a real Sonim feature flag lands in either form
        // without an XV update.
        return features.any { feat ->
            feat.equals("com.sonim.hardware.programmable_keys", ignoreCase = true) ||
                feat.equals("com.sonim.feature.programmable_keys", ignoreCase = true)
        }
    }

    // ============================================================
    // KeyEvent classifier (foreground fallback)
    // ============================================================

    /**
     * Classification returned by [handlePttKeyEvent] and
     * [handleEmergencyKeyEvent] for a single Android [KeyEvent]
     * observed while ATAK is in the foreground. Used by the
     * foreground-KeyEvent fallback readers so the transport layer
     * maps to the same [com.atakmap.android.xv.audio.PttSource]
     * edges that the broadcast path fires — while keeping the
     * decision itself pure and unit-testable (no Android runtime,
     * no Robolectric).
     */
    enum class FallbackAction {
        /** Fire a PTT down edge tagged with the corresponding source. */
        PTT_DOWN,

        /** Fire a PTT up edge tagged with the corresponding source. */
        PTT_UP,

        /** Not a Sonim event we care about — pass through to the next handler. */
        IGNORE,
    }

    /**
     * Pure classification of a single Android [KeyEvent] tuple for the
     * Sonim PTT foreground path. Returns [FallbackAction.PTT_DOWN] on
     * the initial press of either [PTT_KEY_CODE_PRIMARY] or
     * [PTT_KEY_CODE_ALT] (both accepted because on-device confirmation
     * of the exact XP10 mapping is pending), [FallbackAction.PTT_UP] on
     * release, and [FallbackAction.IGNORE] for everything else.
     *
     * Auto-repeat (repeatCount > 0) is dropped so a physically held key
     * doesn't spam the dispatcher with duplicate down edges after the
     * first — belt-and-suspenders (the dispatcher's OR-gate would
     * ignore the duplicates anyway, since [PttSource.SONIM_PTT] is
     * already held), but it also keeps log output readable.
     */
    fun handlePttKeyEvent(
        keyCode: Int,
        action: Int,
        repeatCount: Int,
    ): FallbackAction {
        if (keyCode != PTT_KEY_CODE_PRIMARY && keyCode != PTT_KEY_CODE_ALT) {
            return FallbackAction.IGNORE
        }
        return when (action) {
            KeyEvent.ACTION_DOWN ->
                if (repeatCount == 0) FallbackAction.PTT_DOWN else FallbackAction.IGNORE
            KeyEvent.ACTION_UP -> FallbackAction.PTT_UP
            else -> FallbackAction.IGNORE
        }
    }

    /**
     * Pure classification of a single Android [KeyEvent] tuple for the
     * Sonim Emergency / SOS foreground path. Returns
     * [FallbackAction.PTT_DOWN] on the initial press of [SOS_KEY_CODE],
     * [FallbackAction.PTT_UP] on release, and [FallbackAction.IGNORE]
     * for everything else.
     *
     * Note: this reader currently treats the Emergency button as a
     * plain additional PTT source. A future iteration may upgrade it
     * to fire an emergency CoT event or SOS broadcast; keeping the
     * distinct [PttSource.SONIM_EMERGENCY] tag means that upgrade can
     * be added without disturbing the plain PTT path.
     */
    fun handleEmergencyKeyEvent(
        keyCode: Int,
        action: Int,
        repeatCount: Int,
    ): FallbackAction {
        if (keyCode != SOS_KEY_CODE) return FallbackAction.IGNORE
        return when (action) {
            KeyEvent.ACTION_DOWN ->
                if (repeatCount == 0) FallbackAction.PTT_DOWN else FallbackAction.IGNORE
            KeyEvent.ACTION_UP -> FallbackAction.PTT_UP
            else -> FallbackAction.IGNORE
        }
    }
}
