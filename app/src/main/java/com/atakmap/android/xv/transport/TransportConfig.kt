package com.atakmap.android.xv.transport

// Transport-specific connection parameters. The application code sees
// these as opaque payloads of the channel/mission picker; transports
// unwrap their own variant.

/**
 * Knob controlling how XV identifies itself in the Mumble Version handshake
 * and whether it advertises the ATAK device UID via UserState.comment.
 *
 * VX's "callable" UI gate (verified against live OTS 2026-05-06) is keyed
 * on Mumble Version.release == "ATAK_Vx". Comment is only used post-detect
 * to map session → device UID for caller display.
 *
 *   OFF     — pure XV identity. release="XV/<ver>", os="Android", no
 *             comment beacon. VX clients won't show a call button for us.
 *   HYBRID  — release="ATAK_Vx XV/<ver>", os="ATAK", comment=deviceUid.
 *             Tests whether VX uses startsWith / contains on release. Lets
 *             us keep our own version visible for diagnostics if so.
 *   STRICT  — release="ATAK_Vx", os="ATAK", comment=deviceUid. Full
 *             impersonation; required if VX does an exact-match check.
 */
enum class VxCompat { OFF, HYBRID, STRICT }

sealed class TransportConfig {
    /**
     * @property host Mumble server hostname or IP.
     * @property port TCP control port (also used for UDP voice).
     *           Standard Mumble default is 64738.
     * @property username display name for this client on the server.
     * @property password optional channel/server password.
     * @property channelName the Mumble channel to join after auth.
     */
    data class Mumble(
        val host: String,
        val port: Int = 64738,
        val username: String,
        val password: String? = null,
        val channelName: String,
        val vxCompat: VxCompat = VxCompat.OFF,
        // ATAK device UID. Used as Mumble UserState.comment when
        // vxCompat != OFF; ignored otherwise.
        val deviceUid: String? = null,
        // Optional secondary channel for VS2 (PTTS key). When set and
        // resolvable, MumbleTransport registers VoiceTarget slot 1 to
        // route to that channel; PTTS-down then keys there without the
        // user leaving the primary channel. Null/blank = secondary
        // disabled (PTTS falls back to primary).
        val secondaryChannelName: String? = null,
    ) : TransportConfig()

    /**
     * @property groupAddress the UDP multicast group (e.g. "239.0.1.1").
     * @property port UDP port for the multicast group.
     * @property networkInterfaceName optional interface to bind to;
     *           null means let the OS pick (usually fine on a single
     *           Wi-Fi interface).
     * @property channelLabel local-only display name; doesn't affect
     *           wire format.
     */
    data class Multicast(
        val groupAddress: String,
        val port: Int,
        val networkInterfaceName: String? = null,
        val channelLabel: String,
    ) : TransportConfig()
}
