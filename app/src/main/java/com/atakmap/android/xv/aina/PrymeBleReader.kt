package com.atakmap.android.xv.aina

import android.content.Context
import java.util.UUID

/**
 * BLE GATT button reader for Pryme BT-PTT-Z (and firmware revisions
 * that use the same wire format).
 *
 * The Pryme PTT button does NOT pair as a generic HID-over-GATT
 * input device — Android's HidHostService never claims it (verified
 * via `dumpsys bluetooth_manager`: only proper HoGP devices like
 * MX Master appear in `mInputDevices`; PTT-Z is bonded but absent).
 * Without HID registration the OS won't dispatch keycodes to a
 * MediaSession. The companion app must connect the GATT link and
 * subscribe to the vendor button-state characteristic itself, the
 * same way [AinaBleReader] does for AINA V2.
 *
 * All connect / retry / notify / edge-triggered-mask plumbing lives
 * in [BitmaskGattPttReader]; this class only supplies the vendor
 * config (candidate service UUIDs + mask-decode heuristic).
 *
 * Service UUIDs, in priority order:
 *   - `0000ffe0-…-00805f9b34fb` (HM-10 / TI CC2540 transparent UART)
 *     is what production Pryme BT-PTT-Z units advertise — verified
 *     on-device: their GATT tree exposes only Generic Access (1800),
 *     Generic Attribute (1801), and `ffe0` with characteristic
 *     `ffe1` [WRITE,NOTIFY].
 *   - `00420000-8f59-4420-870d-84f3b617e493` was extracted from VX
 *     2.1.0's dex during protocol study. It doesn't match the units
 *     we have on hand but is kept as a fallback in case a Pryme
 *     firmware revision exposes it instead.
 *
 * Add new UUIDs to [PRYME_SERVICE_UUIDS] as new hardware is tested.
 *
 * Mask decode heuristic: any non-zero first byte on the notify
 * characteristic = PTT held; zero = released. Pryme reports a raw
 * button-state byte and we treat every combination as "PTT is down"
 * until wire-format documentation lets us distinguish per-button
 * bits. Refine [decodePrymeMask] when that documentation arrives.
 */
class PrymeBleReader(
    context: Context,
    onEvent: (AinaButton, isDown: Boolean) -> Unit,
    onConnectionState: (Boolean) -> Unit = {},
) : BitmaskGattPttReader(
    context = context,
    config = PRYME_CONFIG,
    onEvent = onEvent,
    onConnectionState = onConnectionState,
) {
    companion object {
        private val HM10_SERVICE_UUID: UUID = UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb")
        private val PRYME_VENDOR_UUID: UUID = UUID.fromString("00420000-8f59-4420-870d-84f3b617e493")

        val PRYME_SERVICE_UUIDS: List<UUID> = listOf(HM10_SERVICE_UUID, PRYME_VENDOR_UUID)

        /**
         * "Any non-zero first byte = PTT held" — the field heuristic
         * used since Pryme support was first added. Kept as a
         * top-level function so unit tests can pin the exact
         * behavior without touching the Android GATT stack.
         */
        fun decodePrymeMask(mask: Int): Set<AinaButton> =
            if (mask != 0) setOf(AinaButton.PTT) else emptySet()

        private val PRYME_CONFIG =
            Config(
                tag = "XvPrymeBle",
                candidateServiceUuids = PRYME_SERVICE_UUIDS,
                decodeMask = ::decodePrymeMask,
            )
    }
}
