package com.atakmap.android.xv.transport.multicast

/**
 * Cross-leg RX dedup for the transport set. When a speaker's voice
 * arrives on more than one leg — active-active [MulticastMode.ALWAYS]
 * peers TX on Mumble AND multicast every burst, and a bridge relay can
 * echo a mesh burst back onto the server — playing both copies doubles
 * the audio. This class decides, per incoming frame, whether to play
 * or drop.
 *
 * Model: **first leg wins, per speaker, sticky for the burst.** The
 * leg that delivers a speaker's first frame owns that speaker until
 * the owner goes silent for [ownerSilenceMs] (end of burst, or the
 * owning leg died). Frames for the same speaker from any other leg
 * are dropped while the owner is live. No per-frame sequence matching
 * — legs have different latency profiles, so seq-level pairing would
 * misfire on jitter; burst-level exclusion is what actually prevents
 * double audio.
 *
 * Speaker identity is the caller's problem: callers canonicalize each
 * leg's native key (Mumble session, multicast `ssrc:`/`ip:` key) to a
 * shared identity (the peer's device UID via presence + FNV-1a SSRC
 * derivation) before calling [shouldPlay]; unresolvable keys pass
 * through as-is and simply never collide across legs.
 *
 * Pure logic, no clocks or threads inside; callers pass timestamps.
 * Not thread-safe — confine to one delivery-dispatch thread or lock
 * externally (the plugin funnels both legs' RX through one hop).
 */
class RxDeduper(
    private val ownerSilenceMs: Long = OWNER_SILENCE_MS,
) {
    private data class Owner(
        val legId: String,
        var lastFrameAtMs: Long,
    )

    private val ownerBySpeaker = HashMap<String, Owner>()

    /**
     * One frame for [speaker] arrived on [legId] at [nowMs]. Returns
     * true if this copy should be played, false if it's a cross-leg
     * duplicate to drop.
     */
    fun shouldPlay(
        legId: String,
        speaker: String,
        nowMs: Long,
    ): Boolean {
        val owner = ownerBySpeaker[speaker]
        if (owner == null || nowMs - owner.lastFrameAtMs > ownerSilenceMs) {
            ownerBySpeaker[speaker] = Owner(legId, nowMs)
            return true
        }
        if (owner.legId == legId) {
            owner.lastFrameAtMs = nowMs
            return true
        }
        return false
    }

    /** Drop all ownership state (channel change, transport teardown). */
    fun reset() {
        ownerBySpeaker.clear()
    }

    /**
     * Opportunistic cleanup — callers may invoke on a slow tick so a
     * long session doesn't accumulate one entry per speaker ever heard.
     */
    fun prune(nowMs: Long) {
        ownerBySpeaker.entries.removeAll { nowMs - it.value.lastFrameAtMs > ownerSilenceMs }
    }

    companion object {
        /**
         * A speaker whose owning leg has been silent this long is
         * considered done with the burst; the next frame from any leg
         * re-claims ownership. 750 ms rides out per-leg jitter and
         * short word gaps without bridging two distinct bursts.
         */
        const val OWNER_SILENCE_MS: Long = 750
    }
}
