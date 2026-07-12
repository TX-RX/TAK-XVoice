package com.atakmap.android.xv.aina

/**
 * Pure decision logic for the reader "kind" string XV assigns to the
 * External Button slot at connect time. Extracted from
 * XvMapComponent.resolveConnectKind so the precedence rules can be
 * covered by ordinary JUnit — the live [resolveConnectKind] wraps a
 * BluetoothAdapter call and isn't unit-testable on its own.
 *
 * Precedence — highest wins:
 *
 * 1. **Known-BLE-PTT membership.** If the MAC appears in
 *    [XvSettings.knownBlePttDevices] (the set of pucks the operator
 *    scanned in via "Scan for BLE PTT device"), the reader kind is
 *    always `"ble-hid"`. This wins over any Bluetooth classification
 *    result because HM-10 / PTT-Z-family pucks don't expose their
 *    vendor UUID over BR/EDR SDP, so the classifier can misclassify
 *    them (or return "auto" for unbonded devices) even when the
 *    operator has explicitly declared them as BLE PTT buttons.
 *
 * 2. **Classifier result.** Whatever the SDP-based classifier
 *    produced from the BluetoothDevice at bond time — `"v1"` (SPP),
 *    `"v2"` (BLE GATT vendor UUID), or `"ble-hid"` (BLE HID puck).
 *    Blank/null classifier result means the device wasn't
 *    classifiable (adapter unavailable, MAC not bonded, SDP threw).
 *
 * 3. **Persisted hint** (last-known kind for this MAC, if any). A
 *    stale hint is better than falling through to `"auto"` because
 *    `"auto"` at connect time on the External Button slot resolves
 *    to "no reader" — the operator's puck presses would be silently
 *    dropped. The hint acts as a "last resort" recovery for devices
 *    the classifier can no longer see (e.g. a bonded puck that
 *    Android has forgotten how to enumerate over SDP after a system
 *    update).
 *
 * 4. `"auto"` — the caller signals "no known reader kind." Whether
 *    the reader-lifecycle logic upstream treats that as no-reader or
 *    as a retry hint is its problem, not this resolver's.
 *
 * Field bug 2026-07-11 (Pixel 9 Pro, Pryme BT-PTT-Z puck): operator
 * added a puck via BOTH the Android BT Settings bond dialog AND
 * XV Settings → "Scan for BLE PTT device". Picker (correctly)
 * showed the bonded entry only. Operator picked the bonded entry
 * BEFORE the scan-and-add. Persisted kind was whatever the
 * classifier derived. Operator then scanned the same MAC into
 * knownBlePtt; the persisted kind was NOT refreshed. On next cold
 * launch, `connectSavedExternalButton` used the stale persisted
 * kind, not `"ble-hid"`, and physical presses silently dropped.
 *
 * The fix: `connectSavedExternalButton` computes the kind fresh via
 * this resolver every time instead of reading a cached
 * `persistedExternalButtonKind()`. Cache staleness surface goes to
 * zero because there is no cache in the connect path — the
 * knownBlePtt list IS the source of truth.
 *
 * Persisted kind is retained as a hint only; it seeds the "last
 * resort" fallback above for the case where the classifier can no
 * longer see the device.
 */
object ExternalButtonKindResolver {
    /**
     * Compute the reader kind string for a MAC given the current
     * knownBlePtt membership + a classifier result + an optional
     * persisted hint. Returns one of `"ble-hid"` / `"v1"` / `"v2"` /
     * or the persisted hint (if any and no better answer is
     * available), falling back to `"auto"`.
     *
     * All inputs are treated case-insensitively where applicable —
     * the caller is expected to pass MAC-based membership already
     * computed against a case-insensitive comparison; this function
     * does not re-check MAC equality.
     *
     * @param isInKnownBlePtt True iff the target MAC is present in
     *   the operator's known-BLE-PTT set. When true, the answer is
     *   always `"ble-hid"`.
     * @param classifierResult The SDP-based classifier's answer for
     *   this MAC — `"v1"`, `"v2"`, `"ble-hid"`, `"auto"`, or null /
     *   blank if the classifier could not run at all (adapter null,
     *   device not bonded, threw).
     * @param persistedHint The last-known kind string persisted for
     *   this MAC (from XvSettings.persistedExternalButtonKind), or
     *   null / blank if none. Used only as a last-resort fallback so
     *   we don't collapse to `"auto"` (which means "no reader") on a
     *   device the classifier can no longer see.
     */
    fun resolve(
        isInKnownBlePtt: Boolean,
        classifierResult: String?,
        persistedHint: String?,
    ): String {
        if (isInKnownBlePtt) return "ble-hid"
        val classifier = classifierResult?.trim()?.takeIf { it.isNotBlank() }
        // "auto" from the classifier is a punt — treat it like a
        // classifier miss so the persisted hint gets a chance to
        // recover the reader. A concrete "v1" / "v2" / "ble-hid"
        // wins over any hint.
        if (classifier != null && !classifier.equals("auto", ignoreCase = true)) {
            return classifier
        }
        val hint = persistedHint?.trim()?.takeIf { it.isNotBlank() }
        if (hint != null && !hint.equals("auto", ignoreCase = true)) {
            return hint
        }
        return "auto"
    }
}
