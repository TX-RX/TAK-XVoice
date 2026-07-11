package com.atakmap.android.xv.aina

/**
 * Pure decision logic for how to react to a primary-AINA button-protocol
 * (a.k.a. "kind") change from the operator. Extracted from
 * XvMapComponent so it can be unit-tested without pulling in the
 * Android runtime, the BT stack, or the service AIDL.
 *
 * Reader lifecycle background: the primary-AINA button reader
 * (AinaSppReader / AinaBleReader / PrymeBleReader) is spun up inside
 * the voice service on every connectAina call. Historically the ONLY
 * trigger for a reader respawn was a device-connect edge — the kind
 * was frozen at connect time. If the operator disconnected the
 * speakermic, flipped the button protocol in settings, then
 * reconnected while the persisted kind was different from the newly-
 * picked kind, the reader spun up with the wrong protocol and PTT
 * buttons went silent until the next full disconnect / reconnect
 * cycle. This resolver drives the new "kind changed on an already-
 * connected device — decide what to tear down" branch that closes
 * that gap.
 *
 * A [ButtonProtocol] of `null` (or [AinaDeviceInfo.ButtonProtocol.UNKNOWN]
 * or [AinaDeviceInfo.ButtonProtocol.AUDIO_ONLY]) means "no button
 * reader" — the operator wants the speakermic's audio path only, with
 * on-screen PTT as the sole key path. Any of the three real reader
 * kinds ([AinaDeviceInfo.ButtonProtocol.SPP],
 * [AinaDeviceInfo.ButtonProtocol.BLE], [AinaDeviceInfo.ButtonProtocol.BLE_HID])
 * demands a reader.
 */
object AinaReaderKindResolver {
    /**
     * What the caller should do with the currently-running reader in
     * response to an operator kind flip.
     */
    enum class Decision {
        /**
         * Nothing to do. Either the primary AINA isn't connected (so
         * there's no live reader to worry about — the change is
         * persist-only and takes effect on the next connect edge), or
         * the new kind is functionally equivalent to the old kind
         * (both "no reader" or both the same real reader kind).
         */
        NO_OP,

        /**
         * Tear the reader down but keep the audio route hint. Used
         * when the operator flips from "SPP / BLE / BLE_HID" to
         * "AUDIO_ONLY / UNKNOWN / null" — they still want the
         * speakermic's HFP audio, they just don't want XV listening
         * for button events on it.
         */
        TEARDOWN_ONLY,

        /**
         * Tear the current reader down AND spin up a new one under
         * the new kind. Used for real-reader → real-reader flips
         * (e.g. SPP → BLE when the operator manually corrects an
         * SDP-cache misclassification).
         */
        RESPAWN,
    }

    /**
     * Decide what to do given [currentKind] (the kind the running
     * reader — if any — was constructed under), [newKind] (the value
     * the operator just picked), and [isConnected] (whether the
     * primary AINA is currently connected — i.e. whether there IS a
     * live reader).
     *
     * If [isConnected] is false, the decision is always [Decision.NO_OP]
     * regardless of the kinds: the persistence write is the only side
     * effect, and the next connect edge will pick up the new value via
     * the existing per-MAC override path.
     */
    fun shouldRespawnReader(
        currentKind: AinaDeviceInfo.ButtonProtocol?,
        newKind: AinaDeviceInfo.ButtonProtocol?,
        isConnected: Boolean,
    ): Decision {
        if (!isConnected) return Decision.NO_OP
        val currentHasReader = hasReader(currentKind)
        val newHasReader = hasReader(newKind)
        // "no reader → no reader" and identical reader kinds are both
        // no-ops. Idempotent so a rapid toggle A → B → A doesn't churn
        // the reader mid-tap.
        if (!currentHasReader && !newHasReader) return Decision.NO_OP
        if (currentKind == newKind) return Decision.NO_OP
        // Live reader → "no reader": drop the reader, keep audio.
        if (currentHasReader && !newHasReader) return Decision.TEARDOWN_ONLY
        // Everything else (no reader → live, or live → different live)
        // needs a fresh connect path so the SDP probe, retry logic,
        // and callback wiring all get re-invoked.
        return Decision.RESPAWN
    }

    /**
     * True iff the given kind demands a live button reader. Null +
     * UNKNOWN + AUDIO_ONLY all collapse to "no reader" — the operator
     * still gets the audio path (if the speakermic exposes HFP) but
     * XV doesn't listen for button events.
     */
    private fun hasReader(kind: AinaDeviceInfo.ButtonProtocol?): Boolean =
        when (kind) {
            AinaDeviceInfo.ButtonProtocol.SPP,
            AinaDeviceInfo.ButtonProtocol.BLE,
            AinaDeviceInfo.ButtonProtocol.BLE_HID,
            -> true
            AinaDeviceInfo.ButtonProtocol.AUDIO_ONLY,
            AinaDeviceInfo.ButtonProtocol.UNKNOWN,
            null,
            -> false
        }
}
