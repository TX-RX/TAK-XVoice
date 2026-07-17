package com.atakmap.android.xv.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pinned coverage for the 2026-07-10 cold-SCO start-of-burst polish
 * (see the "Cold-SCO start-of-burst polish" block in TxController's
 * companion object for the field capture that motivated this).
 *
 * Field observation: on cold-start TX after app launch OR after a long
 * idle, the Pixel Always-On Compute audio mixer under-runs its uplink
 * buffer during SCO warm-up for ~30 ms. PRIMING's "mic ready" gate is
 * itself correct — the mic IS reading valid audio — but the SCO
 * uplink modem is still stabilizing past that point, so real mic
 * frames land inside the underrun window and encode to garbage on
 * the receiving peer.
 *
 * Two complementary defenses, both scoped to cold-SCO only:
 *
 *  1. [TxController.computePrimingHoldMs] — extend the PRIMING → TPT
 *     handoff by an extra 200 ms on the cold-SCO path so the modem
 *     stabilizes before the operator hears the go-ahead tone. Warm
 *     SCO and non-SCO paths are unaffected.
 *
 *  2. [TxController.shouldDropStartFrame] — silently discard the
 *     first N=3 TX frames on the cold-SCO path, mirroring the
 *     existing trailing-frame swallow at TX-stop that eats the PTT
 *     release click. Peer misses ~60 ms of the leading edge
 *     (typically a breath or click), not intelligible speech.
 *
 * Both helpers are pure functions on TxController's companion —
 * exercised directly without needing to stand up ScoLink /
 * AudioCapture / TptPlayer.
 */
class TxControllerColdScoWarmupTest {
    // ============================================================
    // Part 1 — shouldDropStartFrame contract
    // ============================================================

    @Test
    fun `cold-SCO frame #1 is dropped — start of the AOC underrun window`() {
        assertTrue(
            TxController.shouldDropStartFrame(
                frameNumber = 1,
                route = TxRoute.SCO,
                cold = true,
                dropCount = 3,
            ),
        )
    }

    @Test
    fun `cold-SCO frame #3 is dropped — last frame inside the N=3 drop window`() {
        assertTrue(
            TxController.shouldDropStartFrame(
                frameNumber = 3,
                route = TxRoute.SCO,
                cold = true,
                dropCount = 3,
            ),
        )
    }

    @Test
    fun `cold-SCO frame #4 is kept — first frame past the drop window`() {
        assertFalse(
            TxController.shouldDropStartFrame(
                frameNumber = 4,
                route = TxRoute.SCO,
                cold = true,
                dropCount = 3,
            ),
        )
    }

    @Test
    fun `warm-SCO burst never drops — modem is already stable`() {
        assertFalse(
            "warm bursts must not lose leading audio",
            TxController.shouldDropStartFrame(
                frameNumber = 1,
                route = TxRoute.SCO,
                cold = false,
                dropCount = 3,
            ),
        )
    }

    @Test
    fun `non-SCO route never drops even when cold — no AOC modem involvement`() {
        assertFalse(
            "OFFLOAD route has no AOC modem, so no start-of-burst underrun to work around",
            TxController.shouldDropStartFrame(
                frameNumber = 1,
                route = TxRoute.OFFLOAD,
                cold = true,
                dropCount = 3,
            ),
        )
    }

    @Test
    fun `dropCount 0 disables the feature entirely on cold-SCO`() {
        assertFalse(
            "dropCount=0 must short-circuit the gate for A/B comparison",
            TxController.shouldDropStartFrame(
                frameNumber = 1,
                route = TxRoute.SCO,
                cold = true,
                dropCount = 0,
            ),
        )
    }

    @Test
    fun `negative dropCount is treated as feature-disabled`() {
        // Defensive: a future refactor that plumbs the drop count from
        // a settings knob shouldn't be able to drop *every* frame just
        // because someone typed -1.
        assertFalse(
            TxController.shouldDropStartFrame(
                frameNumber = 1,
                route = TxRoute.SCO,
                cold = true,
                dropCount = -1,
            ),
        )
    }

    @Test
    fun `frame number 0 is not counted as a drop — 1-based numbering contract`() {
        // The production caller increments txFrameNumber BEFORE checking
        // the gate, so frame #0 never reaches this helper. Pin that
        // contract so a future refactor that pre-increments elsewhere
        // doesn't accidentally drop off-by-one.
        assertFalse(
            TxController.shouldDropStartFrame(
                frameNumber = 0,
                route = TxRoute.SCO,
                cold = true,
                dropCount = 3,
            ),
        )
    }

    @Test
    fun `large frame numbers are always kept on cold-SCO`() {
        // Sanity: 1000 frames into a burst, the AOC underrun is long
        // gone. Never drop live-burst audio.
        assertFalse(
            TxController.shouldDropStartFrame(
                frameNumber = 1000,
                route = TxRoute.SCO,
                cold = true,
                dropCount = 3,
            ),
        )
    }

    @Test
    fun `production drop count matches the field-tuned N=6`() {
        // If someone bumps COLD_SCO_START_DROP_FRAMES away from 6, they
        // should update the block comment in TxController explaining
        // why. Pin the current value here so a change is visible in
        // the same PR.
        //
        // History: N=3 was the original tuning (2026-07-08 field
        // capture). Widened to N=6 on 2026-07-11 after peer reported
        // residual screech at the head of cold-SCO bursts — 60 ms of
        // drop covered the underrun burst itself but the SILK encoder
        // was still catching the not-yet-clean tail. 120 ms clears it.
        assertEquals(6, TxController.COLD_SCO_START_DROP_FRAMES)
    }

    // ============================================================
    // Part 2 — computePrimingHoldMs contract
    // ============================================================

    @Test
    fun `cold-SCO extends the PRIMING hold by COLD_SCO_TPT_HOLD_MS`() {
        val held =
            TxController.computePrimingHoldMs(
                route = TxRoute.SCO,
                cold = true,
                baseMs = 0L,
            )
        assertEquals(TxController.COLD_SCO_TPT_HOLD_MS, held)
    }

    @Test
    fun `cold-SCO adds hold on top of a non-zero baseMs`() {
        // baseMs allows the caller to compose the hold on top of any
        // other scheduled delay (e.g. if a future refactor delays
        // startTpt globally by some small amount). Additive composition
        // is the contract.
        val held =
            TxController.computePrimingHoldMs(
                route = TxRoute.SCO,
                cold = true,
                baseMs = 50L,
            )
        assertEquals(50L + TxController.COLD_SCO_TPT_HOLD_MS, held)
    }

    @Test
    fun `warm-SCO burst uses baseMs — no extra hold`() {
        assertEquals(
            0L,
            TxController.computePrimingHoldMs(
                route = TxRoute.SCO,
                cold = false,
                baseMs = 0L,
            ),
        )
        assertEquals(
            50L,
            TxController.computePrimingHoldMs(
                route = TxRoute.SCO,
                cold = false,
                baseMs = 50L,
            ),
        )
    }

    @Test
    fun `non-SCO route uses baseMs even when cold — no AOC modem involvement`() {
        assertEquals(
            0L,
            TxController.computePrimingHoldMs(
                route = TxRoute.OFFLOAD,
                cold = true,
                baseMs = 0L,
            ),
        )
        assertEquals(
            100L,
            TxController.computePrimingHoldMs(
                route = TxRoute.OFFLOAD,
                cold = true,
                baseMs = 100L,
            ),
        )
    }

    @Test
    fun `production hold matches the field-tuned 300 ms`() {
        // 200 ms was the original tuning (2026-07-08 AOC underrun window
        // + margin). Widened to 300 ms on 2026-07-11 after peer-side
        // screech report during TPP validation — the underrun clears
        // quickly but the pipeline needs another ~100 ms to fully
        // settle before mic frames stop encoding to Opus screech.
        // If this constant moves, the block comment above it should
        // move with it.
        assertEquals(300L, TxController.COLD_SCO_TPT_HOLD_MS)
    }

    // ============================================================
    // Part 3 — TxRoute enum shape
    // ============================================================

    @Test
    fun `TxRoute has exactly SCO and OFFLOAD entries`() {
        // Deliberate narrowness: TxRoute encodes only the distinction
        // the cold-SCO mitigation needs. Adding more entries here
        // means the helpers above need to decide how each new route
        // interacts with the AOC modem — pin the shape until then.
        assertEquals(
            listOf("SCO", "OFFLOAD"),
            TxRoute.entries.map { it.name },
        )
    }

    // ============================================================
    // Part 4 — PRIMING gate selection keys off a COLD burst, not "SCO up"
    //
    // 2026-07-13 field repro: SCO was held WARM across a full session
    // (the cool-down tail working as intended), yet every burst still
    // used the 30-frame (~300 ms) cold gate because the selection was
    // tied to "SCO connected." With bursty keying the operator released
    // before TX started and ZERO frames went out. The gates must be
    // slow ONLY for a cold burst; warm-SCO and non-SCO get fast gates.
    //
    // 2026-07-17: the parameter widened from coldSco to coldBurst — a
    // fresh Telecom session (cold-call axis) selects the same
    // ramp-tolerant gates, because the OEM voice DSP re-converges after
    // the VoIP-call reroute exactly like a cold SCO chipset ramps.
    // ============================================================

    @Test
    fun `cold burst uses the ramp-tolerant gates`() {
        assertEquals(30, TxController.primingMinFramesToConfirmAlive(coldBurst = true))
        assertEquals(200, TxController.primingRmsThreshold(coldBurst = true))
        assertEquals(1500L, TxController.primingTimeoutMs(coldBurst = true))
    }

    @Test
    fun `warm burst uses the fast gates`() {
        // The regression guard: coldBurst=false MUST select the fast
        // gates so a warm-held-SCO key reaches the tone in ~50 ms
        // (5 frames), not ~300 ms.
        assertEquals(5, TxController.primingMinFramesToConfirmAlive(coldBurst = false))
        assertEquals(5, TxController.primingRmsThreshold(coldBurst = false))
        assertEquals(500L, TxController.primingTimeoutMs(coldBurst = false))
    }

    @Test
    fun `warm gates are strictly faster to confirm than cold gates`() {
        // Intent invariant independent of the exact tuned values: a warm
        // burst never waits longer than a cold one to declare mic-alive.
        assertTrue(
            TxController.primingMinFramesToConfirmAlive(coldBurst = false) <
                TxController.primingMinFramesToConfirmAlive(coldBurst = true),
        )
        assertTrue(
            TxController.primingTimeoutMs(coldBurst = false) <
                TxController.primingTimeoutMs(coldBurst = true),
        )
    }
}
