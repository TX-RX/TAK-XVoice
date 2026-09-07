package com.atakmap.android.xv.mission

/**
 * Auto-provisions a voice channel for the operator's active ATAK Data
 * Sync mission. Pure decision logic — the ATAK mission observation and
 * the Mumble create/join calls live in the plugin adapter; this class
 * only decides what to do next given the observed state, so the whole
 * policy is unit-testable off-device.
 *
 * The idea: a mission is a team working a shared data picture, so it
 * should have a voice channel and everyone in it should land there with
 * no manual coordination. Because the channel name is derived
 * deterministically from the mission name ([channelNameForMission]) and
 * the multicast group derivation keys off that same channel name, joining
 * the mission channel also brings up its mesh-failover leg for free.
 *
 * Lifecycle per reconcile tick, for the primary (index 0) active mission:
 *   1. No mission → [Action.NoOp]; never pull the operator off a channel
 *      they chose while unmissioned.
 *   2. Already on the mission channel → [Action.NoOp] (synced).
 *   3. Mission channel exists on the server → [Action.Join].
 *   4. Mission channel absent + server allows creation → [Action.Create]
 *      (then a later tick joins it once its ChannelState arrives), with
 *      bounded retries before giving up.
 *   5. Mission channel absent + creation disallowed, or retries
 *      exhausted → [Action.Unavailable] once (many TAK/OTS servers
 *      forbid client-initiated channel creation).
 *
 * Manual override: once the operator has been placed on the mission
 * channel, moving off it yields control — the provisioner stops
 * re-joining until the mission itself changes. Switching missions
 * clears the yield and drives toward the new channel.
 *
 * Not thread-safe; drive from one tick thread (the plugin's mesh/UI
 * scheduler).
 */
class MissionChannelProvisioner(
    /**
     * Mission display name → voice channel name. Must be deterministic
     * and identical across every team member's build so the whole
     * mission converges on one channel. Default is the trimmed mission
     * name (the voice channel simply *is* the mission).
     */
    val channelNameForMission: (String) -> String = { it.trim() },
    private val createRetryTicks: Int = DEFAULT_CREATE_RETRY_TICKS,
    private val maxCreateAttempts: Int = DEFAULT_MAX_CREATE_ATTEMPTS,
) {
    private var tick: Int = 0
    private var target: String? = null
    private var reachedTarget: Boolean = false
    private var yielded: Boolean = false
    private var createAttempts: Int = 0
    private var lastCreateTick: Int = Int.MIN_VALUE
    private var unavailableEmitted: Boolean = false

    /** The channel the provisioner is currently driving toward, or null. */
    fun currentTarget(): String? = target

    /**
     * Compute the next action for the primary voice slot.
     *
     * @param activeMissions ordered active-mission names; index 0 is the
     *   primary that drives the primary voice slot. Empty ⇒ no mission.
     * @param directoryContains does the server channel directory already
     *   hold a channel with this (case-insensitive) name?
     * @param joinedChannel the primary slot's currently-joined channel
     *   name, or null when not on any channel.
     * @param allowCreate whether the server permits client-initiated
     *   channel creation (operator/config signal; conservative default
     *   is true, degrade to [Action.Unavailable] when the server rejects).
     */
    fun reconcile(
        activeMissions: List<String>,
        directoryContains: (String) -> Boolean,
        joinedChannel: String?,
        allowCreate: Boolean,
    ): Action {
        tick++
        val desired =
            activeMissions
                .firstOrNull()
                ?.let { channelNameForMission(it) }
                ?.takeIf { it.isNotBlank() }

        // Mission (or its derived channel) changed → fresh drive cycle.
        if (!sameChannel(desired, target)) {
            target = desired
            reachedTarget = false
            yielded = false
            createAttempts = 0
            lastCreateTick = Int.MIN_VALUE
            unavailableEmitted = false
        }

        if (desired == null) return Action.NoOp

        if (sameChannel(joinedChannel, desired)) {
            reachedTarget = true
            return Action.NoOp
        }

        // Operator deliberately left the mission channel after we placed
        // them on it — stop fighting until the mission changes.
        if (reachedTarget) {
            yielded = true
            return Action.NoOp
        }
        if (yielded) return Action.NoOp

        if (directoryContains(desired)) {
            return Action.Join(desired)
        }

        if (!allowCreate) {
            return unavailableOnce(desired, "server does not allow channel creation")
        }

        // Absent + creatable: fire a create, then wait createRetryTicks
        // for its ChannelState to land before retrying, up to a cap.
        if (createAttempts >= maxCreateAttempts) {
            return unavailableOnce(desired, "channel did not appear after $maxCreateAttempts create attempts")
        }
        val dueForRetry = lastCreateTick == Int.MIN_VALUE || tick - lastCreateTick >= createRetryTicks
        if (!dueForRetry) return Action.NoOp
        createAttempts++
        lastCreateTick = tick
        return Action.Create(desired)
    }

    /** Forget all state (transport teardown / feature disabled). */
    fun reset() {
        target = null
        reachedTarget = false
        yielded = false
        createAttempts = 0
        lastCreateTick = Int.MIN_VALUE
        unavailableEmitted = false
    }

    private fun unavailableOnce(
        channel: String,
        reason: String,
    ): Action =
        if (unavailableEmitted) {
            Action.NoOp
        } else {
            unavailableEmitted = true
            Action.Unavailable(channel, reason)
        }

    private fun sameChannel(
        a: String?,
        b: String?,
    ): Boolean = a?.trim()?.equals(b?.trim(), ignoreCase = true) ?: (b == null)

    sealed class Action {
        /** Nothing to do this tick. */
        data object NoOp : Action()

        /** Ask the server to create the mission channel. */
        data class Create(
            val channelName: String,
        ) : Action()

        /** Join the (now-existing) mission channel. */
        data class Join(
            val channelName: String,
        ) : Action()

        /**
         * The mission channel can't be provisioned — server forbids
         * creation, or creation was requested but the channel never
         * appeared. Emitted once per drive cycle; surface to the operator.
         */
        data class Unavailable(
            val channelName: String,
            val reason: String,
        ) : Action()
    }

    companion object {
        /** Ticks (~seconds at 1 Hz) to wait for a created channel to appear before retrying. */
        const val DEFAULT_CREATE_RETRY_TICKS = 3

        /** Give up creating after this many tries and report Unavailable. */
        const val DEFAULT_MAX_CREATE_ATTEMPTS = 3
    }
}
