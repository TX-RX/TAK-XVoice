package com.atakmap.android.xv.aina

/**
 * Shared, slot-agnostic decision matrix for "operator changed the
 * configuration of a PTT reader slot — what should the caller do with
 * the currently-running reader?" Same NO_OP / TEARDOWN_ONLY / RESPAWN
 * shape as the primary-AINA path added in PR #35, extracted so the
 * External Button slot can reuse it verbatim rather than duplicating
 * the decision table.
 *
 * The inputs are intentionally reduced to two booleans plus the
 * connection state so callers with different "current" / "new" domain
 * types (AINA button-protocol enum, external-button MAC string, some
 * future third slot) can all funnel through the same pure logic. The
 * domain-specific collapse (e.g. "AUDIO_ONLY / UNKNOWN / null all mean
 * no reader" for the AINA slot; "null / blank MAC means no reader" for
 * the external button slot) happens at the caller boundary.
 *
 * Callers should pass:
 *  - [currentHasReader]: true iff a reader is currently constructed
 *    under the caller's "current" value. For the primary AINA slot
 *    this is `hasReader(currentKind)`; for the external button slot
 *    this is `!currentMac.isNullOrBlank()`.
 *  - [newHasReader]: same as above but for the "new" (operator-picked)
 *    value.
 *  - [currentEqualsNew]: true iff the "new" value is functionally
 *    identical to the "current" value — same enum entry for AINA,
 *    same MAC (case-insensitively) for external button. Used to
 *    short-circuit `A → A` idempotently so a rapid toggle
 *    `A → B → A` doesn't churn the reader mid-tap.
 *  - [isConnected]: true iff there IS a live reader to touch. When
 *    false, the decision is always [Decision.NO_OP] because the
 *    persistence write is the only side effect and the next connect
 *    edge will pick up the new value via the existing per-slot connect
 *    path.
 *
 * See [AinaReaderKindResolver] for the primary-AINA specialization and
 * [XvMapComponent.setSelectedExternalButtonInternal] for the external-
 * button specialization.
 */
object PttReaderRespawnDecision {
    /**
     * What the caller should do with the currently-running reader in
     * response to an operator config change on the slot.
     */
    enum class Decision {
        /**
         * Nothing to do. Either the slot isn't connected (so there's
         * no live reader to worry about — the change is persist-only
         * and takes effect on the next connect edge), or the new
         * value is functionally equivalent to the old value (both
         * "no reader" or both the same real reader value).
         */
        NO_OP,

        /**
         * Tear the reader down but keep any audio route hint. Used
         * when the operator flips from "has reader" to "no reader"
         * without disconnecting the device — for the AINA slot this
         * is "SPP / BLE / BLE_HID → AUDIO_ONLY / UNKNOWN / null";
         * for the external button slot this is "picked MAC → (none)"
         * (the external button has no audio path, but the caller
         * still models it as TEARDOWN_ONLY for symmetry).
         */
        TEARDOWN_ONLY,

        /**
         * Tear the current reader down AND spin up a new one under
         * the new configuration. Used for real-reader → real-reader
         * flips (kind changes on the AINA slot, MAC changes on the
         * external button slot) and for "no reader" → real-reader
         * transitions on an already-connected device.
         */
        RESPAWN,
    }

    /**
     * Decide what the caller should do given the slot-agnostic
     * booleans above. Pure — no Android runtime, no BT stack, no
     * service AIDL.
     */
    fun decide(
        currentHasReader: Boolean,
        newHasReader: Boolean,
        currentEqualsNew: Boolean,
        isConnected: Boolean,
    ): Decision {
        if (!isConnected) return Decision.NO_OP
        // "no reader → no reader" is a no-op even when connected;
        // this is the "operator was on (none) and picked (none) again"
        // or "AUDIO_ONLY → UNKNOWN" branch.
        if (!currentHasReader && !newHasReader) return Decision.NO_OP
        // Identical live configuration is a no-op — idempotent so
        // a rapid A → B → A toggle doesn't churn the reader mid-tap.
        if (currentEqualsNew) return Decision.NO_OP
        // Live reader → "no reader": drop the reader, keep any audio
        // route hint the caller layer wants to preserve.
        if (currentHasReader && !newHasReader) return Decision.TEARDOWN_ONLY
        // Everything else (no reader → live, or live → different
        // live) needs a fresh connect path so any probe, retry logic,
        // and callback wiring get re-invoked under the new config.
        return Decision.RESPAWN
    }
}
