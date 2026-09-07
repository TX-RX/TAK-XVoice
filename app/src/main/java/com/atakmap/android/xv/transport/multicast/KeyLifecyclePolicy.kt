package com.atakmap.android.xv.transport.multicast

/**
 * How long a mesh channel key may live before it is force-rotated
 * (#92 / #95).
 *
 * Operator decision (2026-07-25): a mesh secret's maximum lifetime is
 * **5 days**. Beyond that a key is either stale or an unnecessary
 * exposure, so the mesh manager rotates it at the first idle gap once
 * it ages past this window. The ceiling is **absolute from install**,
 * not a sliding idle window — the goal is to bound the *total* exposure
 * of a secret regardless of how much it was used, and a device that sat
 * unused for a week should re-enroll rather than silently rejoin on an
 * ancient key (see [ChannelKeyRegistry.isCurrentKeyExpired]).
 *
 * The window is configurable so a more sensitive channel can dial it
 * *shorter*, but it can never be dialed *longer* than [HARD_CEILING_MS]:
 * [clampMs] enforces the ceiling, and the constructor rejects a
 * non-positive window outright. This makes "never exceed 5 days" a
 * property of the type, not a convention a caller has to remember.
 */
data class KeyLifecyclePolicy(
    val maxKeyAgeMs: Long = DEFAULT_MAX_KEY_AGE_MS,
) {
    init {
        require(maxKeyAgeMs in 1..HARD_CEILING_MS) {
            "maxKeyAgeMs must be in 1..$HARD_CEILING_MS (the 5-day ceiling), got $maxKeyAgeMs"
        }
    }

    companion object {
        const val ONE_DAY_MS: Long = 24L * 60 * 60 * 1000

        /** The absolute ceiling: no mesh key may live longer than 5 days. */
        const val HARD_CEILING_MS: Long = 5 * ONE_DAY_MS

        /** Default window when the operator hasn't dialed it shorter. */
        const val DEFAULT_MAX_KEY_AGE_MS: Long = HARD_CEILING_MS

        /** Clamp an arbitrary (e.g. persisted or operator-entered) value
         *  into the valid range: at least 1 ms, never past the ceiling. */
        fun clampMs(ms: Long): Long = ms.coerceIn(1L, HARD_CEILING_MS)

        /** Build a policy from a day count, clamped to the ceiling. */
        fun ofDays(days: Double): KeyLifecyclePolicy = KeyLifecyclePolicy(clampMs((days * ONE_DAY_MS).toLong()))

        /** The default 5-day policy. */
        val DEFAULT: KeyLifecyclePolicy = KeyLifecyclePolicy()
    }
}
