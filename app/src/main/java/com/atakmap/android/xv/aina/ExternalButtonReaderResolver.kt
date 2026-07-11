package com.atakmap.android.xv.aina

/**
 * Pure decision logic for how to react to an operator config change on
 * the External Button PTT slot. Mirrors [AinaReaderKindResolver] for
 * the primary AINA slot — same NO_OP / TEARDOWN_ONLY / RESPAWN matrix
 * routed through the shared [PttReaderRespawnDecision] — but the
 * domain input is a MAC address rather than a button-protocol enum
 * because the External Button slot's kind is auto-derived from the
 * BluetoothDevice classification at connect time, not set by the
 * operator.
 *
 * Reader lifecycle background: the External Button reader
 * (AinaSppReader / AinaBleReader / PrymeBleReader) is spun up inside
 * the voice service on every connectExternalButton call. The picker's
 * on-select handler filters out same-MAC picks before calling into
 * [Controller.setSelectedExternalButton], but any programmatic caller
 * (post-restart auto-connect, debug intent, future automation) can
 * hand the plugin a MAC that already matches the running reader OR a
 * MAC that differs from the running one. This resolver centralises
 * the "should we tear down the current reader?" decision so those
 * callers all agree.
 *
 * Field bug it closes: on 2026-07-11 (Pixel 9 Pro, Pryme BT-PTT-Z puck)
 * the picker showed the puck as "Connected" but physical button
 * presses did NOT reach [XvVoicePlant] — no `external button PTT
 * down=true` log lines. Only toggling the picker to "(none)" and back
 * to the puck restarted the reader. Same class of reader-lifecycle
 * race PR #35 fixed for the primary AINA — the External Button slot
 * was left with the pre-existing behaviour and now exhibits the same
 * bug shape. Mirror the fix: give the External slot the same
 * config-change respawn path so a same-MAC re-select (or any
 * programmatic re-attach) reliably re-runs the connect flow.
 *
 * A blank/null MAC means "no reader" — the operator picked "(none)"
 * or hasn't picked anything. Any non-blank MAC demands a reader.
 */
object ExternalButtonReaderResolver {
    /**
     * Historical alias for [PttReaderRespawnDecision.Decision]. Kept
     * so the External Button caller can reference a slot-local type
     * name without cross-package churn if the shared decision moves
     * in the future.
     */
    typealias Decision = PttReaderRespawnDecision.Decision

    /**
     * Decide what to do given [currentMac] (the MAC the running
     * External Button reader — if any — was constructed under),
     * [newMac] (the MAC the operator just picked, or null for
     * "(none)"), and [isConnected] (whether the External Button slot
     * is currently connected — i.e. whether there IS a live reader
     * to touch).
     *
     * MAC comparison is case-insensitive to match the rest of XV's
     * MAC-comparison surface (adapter.getRemoteDevice normalises to
     * uppercase; picker input can be either case; the collision
     * check against the primary in
     * XvMapComponent.setSelectedExternalButtonInternal is
     * case-insensitive).
     *
     * External Button vs. primary AINA — connection-edge semantics
     * diverge:
     *
     * The primary AINA slot binds to devices that Android's headset
     * profile service brokers: the OS pushes a connect edge when the
     * peer powers on, and only then does the reader spin up. There
     * the "no live reader + new MAC" case correctly maps to NO_OP —
     * persist the pick, wait for the OS.
     *
     * The External Button slot binds to BLE peripherals that DO NOT
     * push a connect edge to the phone. BLE PTT pucks (Pryme,
     * PTT-Z-family, generic HM10 buttons, third-party Amazon buttons)
     * advertise passively; XV has to open the GATT connection itself.
     * If we return NO_OP when no reader exists yet, we never open the
     * GATT connection, no button events ever arrive, and the picker
     * status stays "Connected" (from the OS bond state) while
     * physical presses do nothing.
     *
     * Field-observed 2026-07-11 on Pixel 9 Pro (Pryme puck +
     * separate Amazon BLE button, neither reader-attached at
     * pick time): setSelectedExternalButtonInternal fired with
     * currentMac=null / isConnected=false, the shared
     * PttReaderRespawnDecision returned NO_OP on the !isConnected
     * short-circuit, and the reader was never spawned. Toggling
     * the picker to (none) and back to the same puck did not help
     * because the same NO_OP path fired again.
     *
     * Fix: for the External Button slot specifically, when the
     * operator picks a real MAC and there is no live reader, always
     * RESPAWN — that spawns the reader whose GATT connect attempt
     * is how the BLE peripheral becomes reachable in the first
     * place. All other transitions continue to delegate to the
     * shared decision matrix. Primary AINA behaviour is unchanged
     * because that resolver still uses the plain shared decision.
     */
    fun shouldRespawnReader(
        currentMac: String?,
        newMac: String?,
        isConnected: Boolean,
    ): PttReaderRespawnDecision.Decision {
        // BLE peripherals need XV to initiate GATT connect — no
        // external connect edge is coming to trigger auto-spawn.
        // If the operator picked a real MAC and there is no reader
        // yet, spawn one so the connection can be attempted.
        if (!isConnected && hasReader(newMac)) return PttReaderRespawnDecision.Decision.RESPAWN
        return PttReaderRespawnDecision.decide(
            currentHasReader = hasReader(currentMac),
            newHasReader = hasReader(newMac),
            currentEqualsNew = macsEqual(currentMac, newMac),
            isConnected = isConnected,
        )
    }

    /**
     * True iff the given MAC represents a live external-button
     * reader — i.e. it's non-null and non-blank. Blank / null both
     * mean "operator picked (none)" — no reader.
     */
    private fun hasReader(mac: String?): Boolean = !mac.isNullOrBlank()

    /**
     * Case-insensitive MAC equality, tolerant of null/blank on either
     * side. Blank strings are treated as null for this comparison so
     * `"" == null` is true (both mean "no MAC").
     */
    private fun macsEqual(
        a: String?,
        b: String?,
    ): Boolean {
        val na = a?.takeIf { it.isNotBlank() }
        val nb = b?.takeIf { it.isNotBlank() }
        if (na == null && nb == null) return true
        if (na == null || nb == null) return false
        return na.equals(nb, ignoreCase = true)
    }
}
