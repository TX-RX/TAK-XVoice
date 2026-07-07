package com.atakmap.android.xv.aina

import android.content.Context
import java.util.UUID

/**
 * BLE GATT button reader for Zello-convention PTT buttons.
 *
 * Zello's Hardware Partner Technical Integration document specifies
 * that a compatible BLE PTT accessory:
 *
 *   1. Advertises with a vendor-specific Service UUID (Zello whitelists
 *      per-{advertised-name, service UUID, characteristic UUID}).
 *   2. Exposes a notify characteristic that carries a **bitmask byte**
 *      describing which buttons are currently held:
 *
 *      | Bit  | Function            |
 *      |------|---------------------|
 *      | 0x01 | PTT (primary)       |
 *      | 0x02 | SOS                 |
 *      | 0x04 | Secondary PTT       |
 *      | 0x08 | Channel Down        |
 *      | 0x10 | Channel Up          |
 *
 * Because Zello whitelists each device individually, the actual
 * Service and Characteristic UUIDs are hardware-specific — a
 * `B0DHZDRH3B` on a motorcyclist's bike will not necessarily share
 * UUIDs with a Zello-branded headset.
 *
 * ## Populating the UUIDs
 *
 * Until we have real UUIDs from an on-device probe (nRF Connect
 * against the physical device — enumerate services + notify
 * characteristics, press the button, capture the payload), the
 * [ZELLO_CANDIDATE_SERVICE_UUIDS] list is intentionally empty. That
 * makes `getService(uuid)` fail closed during service discovery and
 * emits the standard "no known service" log line, so a caller who
 * wires the reader up before UUIDs are known will see the mismatch
 * loudly instead of silently swallowing button events.
 *
 * When real UUIDs land, add them here — same pattern as
 * [PrymeBleReader.PRYME_SERVICE_UUIDS] — and drop the placeholder
 * warning below.
 *
 * The [decodeZelloMask] function IS complete and unit-tested; the
 * mask semantics are stable across Zello-conformant devices, so the
 * decoder can be validated ahead of hardware bring-up.
 *
 * ## Button mapping onto AinaButton
 *
 * XV's existing button vocabulary ([AinaButton]) is AINA-derived and
 * doesn't have first-class entries for "channel up" / "channel down".
 * The Zello channel-switch bits (0x08, 0x10) are intentionally NOT
 * mapped today — the goal for the first cut is primary + secondary
 * PTT so a motorcyclist can key channel 1 from either their AINA
 * speakermic or the Zello button. Channel switching from a physical
 * button is future work; when we add it, extend [AinaButton] with
 * CH_UP / CH_DOWN and light up those bits here.
 *
 * Mapping today:
 *   0x01 → [AinaButton.PTT]  (primary — primary PTT input)
 *   0x02 → [AinaButton.PTTE] (emergency PTT — closest existing analog to SOS)
 *   0x04 → [AinaButton.PTTS] (secondary PTT — secondary PTT input)
 *   0x08 → unmapped (Channel Down)
 *   0x10 → unmapped (Channel Up)
 */
class ZelloBleReader(
    context: Context,
    onEvent: (AinaButton, isDown: Boolean) -> Unit,
    onConnectionState: (Boolean) -> Unit = {},
) : BitmaskGattPttReader(
    context = context,
    config = ZELLO_CONFIG,
    onEvent = onEvent,
    onConnectionState = onConnectionState,
) {
    companion object {
        /**
         * Populate from an on-device nRF Connect probe of the target
         * hardware. Until then, service discovery will match nothing
         * and the reader will log the mismatch.
         *
         * TODO(UUID): capture and add the actual Service UUID(s) of
         *   the operator's Zello-convention BLE PTT button. Follow
         *   the same "priority order, first match wins" contract
         *   [PrymeBleReader.PRYME_SERVICE_UUIDS] uses so a single
         *   reader can cover firmware revisions that only differ in
         *   advertised UUID.
         */
        val ZELLO_CANDIDATE_SERVICE_UUIDS: List<UUID> = emptyList()

        /**
         * Zello Hardware Partner protocol bitmask. Stable across
         * Zello-conformant devices, so this decoder can be pinned by
         * unit tests before any hardware is available.
         *
         * Bit 0x08 (Channel Down) and 0x10 (Channel Up) are decoded
         * as absent from the result set — see the class KDoc for why
         * channel-switching is deferred.
         */
        fun decodeZelloMask(mask: Int): Set<AinaButton> {
            val out = mutableSetOf<AinaButton>()
            if (mask and 0x01 != 0) out += AinaButton.PTT
            if (mask and 0x02 != 0) out += AinaButton.PTTE
            if (mask and 0x04 != 0) out += AinaButton.PTTS
            // 0x08 (Channel Down) — intentionally unmapped, see KDoc.
            // 0x10 (Channel Up)   — intentionally unmapped, see KDoc.
            return out
        }

        private val ZELLO_CONFIG =
            Config(
                tag = "XvZelloBle",
                candidateServiceUuids = ZELLO_CANDIDATE_SERVICE_UUIDS,
                decodeMask = ::decodeZelloMask,
            )
    }
}
