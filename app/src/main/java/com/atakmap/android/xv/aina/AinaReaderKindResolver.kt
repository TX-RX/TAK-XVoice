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
 *
 * The AINA-specific "kind → hasReader" collapse lives here; the actual
 * NO_OP / TEARDOWN_ONLY / RESPAWN decision matrix lives in the shared
 * [PttReaderRespawnDecision] object so the External Button slot can
 * reuse the same matrix without duplicating the truth table (see
 * PR mirroring #35 for the External slot).
 */
object AinaReaderKindResolver {
    /**
     * Historical alias for [PttReaderRespawnDecision.Decision]. Kept
     * so existing call sites and tests continue to compile
     * unchanged. New callers should reference the shared type
     * directly.
     */
    typealias Decision = PttReaderRespawnDecision.Decision

    /**
     * Decide what to do given [currentKind] (the kind the running
     * reader — if any — was constructed under), [newKind] (the value
     * the operator just picked), and [isConnected] (whether the
     * primary AINA is currently connected — i.e. whether there IS a
     * live reader).
     *
     * If [isConnected] is false, the decision is always
     * [PttReaderRespawnDecision.Decision.NO_OP] regardless of the
     * kinds: the persistence write is the only side effect, and the
     * next connect edge will pick up the new value via the existing
     * per-MAC override path.
     */
    fun shouldRespawnReader(
        currentKind: AinaDeviceInfo.ButtonProtocol?,
        newKind: AinaDeviceInfo.ButtonProtocol?,
        isConnected: Boolean,
    ): PttReaderRespawnDecision.Decision =
        PttReaderRespawnDecision.decide(
            currentHasReader = hasReader(currentKind),
            newHasReader = hasReader(newKind),
            currentEqualsNew = currentKind == newKind,
            isConnected = isConnected,
        )

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
