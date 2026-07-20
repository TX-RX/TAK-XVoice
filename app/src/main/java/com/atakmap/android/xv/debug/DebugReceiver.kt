package com.atakmap.android.xv.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

// adb-driven debug surface. Phase 1 only — not a public API.
//
//   START_MULTICAST  --es group "239.0.1.1" --ei port 6001 [--es label "..."]
//   STOP_MULTICAST
//   MESH_VOICE --es enabled "true|false"
//     Master mesh-voice (multicast) toggle — same pref as the settings
//     switch. Persisted; the mesh manager re-reads it on its ~1 Hz
//     tick, so legs come up/down within a second, no reconnect needed.
//   MESH_STATUS
//     Logs mesh-voice state: enabled flag, active legs with resolved
//     group:port endpoints, failover TX state, bridge role, and any
//     channels discovered via peer beacons.
//   MESH_PLAN_EXPORT [--es passphrase "..."]
//     Logs the current channel set (primary + persisted directory,
//     with any stored per-channel configs) as a comms-plan carrier
//     string — cleartext XVCP1 by default, passphrase-locked XVCP2
//     when a passphrase is given. Paste into MESH_PLAN_IMPORT on a
//     peer to provision it without a server.
//   MESH_PLAN_IMPORT --es plan "<carrier text>" [--es passphrase "..."]
//     Imports a comms-plan carrier: persists each channel's config,
//     installs any pre-shared keys into the mesh key registries, and
//     lands on the plan's first channel when none is selected yet.
//   AUDIO_STATE
//   AINA_LIST_BONDED
//     Logs all paired Bluetooth devices (name + MAC + type) so the user
//     can identify their AINA without seeing it in the system UI.
//   AINA_CONNECT  [--es mac "AA:BB:CC:DD:EE:FF" | --es name "APTT301448"] --es kind "v1"|"v2"
//     Either MAC or name (substring, case-insensitive); MAC takes precedence.
//     v1 = Bluetooth Classic SPP (RFCOMM ASCII frames)
//     v2 = BLE GATT button-mask
//   AINA_DISCONNECT
//   MUMBLE_LIST_TAK
//     Logs the TAK servers ATAK has configured (and whether each is up).
//     The Mumble host = a TAK server's host (OTS hosts both on the same box).
//   MUMBLE_CONNECT  [--es tak "<desc-or-host-substring>" | --es host "ots.example.com"] \
//                   [--ei port 64738] [--es channel "MyChannel"] \
//                   [--es secondary "VS2"] \
//                   [--es vxcompat "off|hybrid|strict"]
//     If `tak` is given (or both omitted), XV looks up the matching connected
//     TAK server and uses its host. If only `host` is given, that's used as
//     the Mumble host directly. TLS uses the ATAK enrollment cert; username
//     is "<callsign>---<uuid>" per OTS PR 297 contract.
//     `secondary` is the channel name to bind PTTS (VS2) to. XV stays joined
//     to `channel` and registers a Mumble VoiceTarget so PTTS audio reaches
//     listeners in the secondary channel. Omit / leave blank to disable VS2.
//     vxcompat overrides the persistent default for this connect only — used
//     to test which Mumble Version.release string makes XV "callable" from
//     VX clients. See VxCompat enum doc.
//   MUMBLE_SET_SECONDARY  --es channel "VS2"
//     Re-target VS2 on the active connection without reconnecting. Resolves
//     the channel name in the server's directory and re-registers the
//     VoiceTarget. Pass an empty string to clear (PTTS falls back to
//     primary channel TX).
//   MUMBLE_DISCONNECT
//   MUMBLE_VX_COMPAT --es mode "off|hybrid|strict"
//     Set the persistent VX-compat handshake default. Takes effect on the
//     next connect (do MUMBLE_DISCONNECT then MUMBLE_CONNECT to apply).
//     off    = release="XV/<ver>", os="Android", no comment beacon (default)
//     hybrid = release="ATAK_Vx XV/<ver>", os="ATAK", comment=deviceUid
//     strict = release="ATAK_Vx", os="ATAK", comment=deviceUid
//   MUMBLE_AUTO_ACCEPT --es enabled "true|false"
//     Toggle the private-call auto-accept stub. When true, an incoming
//     `[TAK MxVx : REQUEST_CALL ]<id>` causes XV to immediately join the
//     temp channel — confirms our audio path on a private channel without
//     a UI yet. Takes effect immediately, no reconnect needed.
//   PRESENCE_DUMP
//     Logs the current XV peer registry — every device we've seen
//     advertise a <__xv> CoT detail, with version, capabilities, server,
//     channel list, and freshness. Empty registry is normal until a
//     second XV-running device shows up in the mission.
//   MUMBLE_JOIN  [--es channel "REACT" | --ei channelId 6]
//     Move XV's session into the named/numbered Mumble channel.
//     Substring match by name (case-insensitive); channelId wins if both given.
//   SET_AUDIO_ROUTE --es route "auto|speaker|earpiece|wired"
//     Override the persisted user output-route preference. Bluetooth still
//     wins automatically when a BT device is connected.
//   TX_START
//     Manually start TX (PTT-down equivalent). Use TX_STOP to release.
//   TX_STOP
//     Stop an active TX (PTT-up equivalent).
//   TX_LOOPBACK
//     Sends 100 frames of test audio with target=31 (Mumble server
//     loopback). Server echoes the packets back to us only — if our
//     wire format is right, AudioPlayback will play them through our
//     own RX path. Used to isolate "wire format ok" vs "server-side
//     routing wrong" bugs.
//   MUMBLE_CHAT_TEST
//     Sends a TextMessage to the current channel. If it shows up in
//     other clients' chat windows, the TCP/protobuf path is healthy;
//     remaining issue is voice-packet specific.
class DebugReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val handler = handler
        if (handler == null) {
            Log.w(TAG, "no handler installed; ignoring ${intent.action}")
            return
        }
        when (intent.action) {
            ACTION_START_MULTICAST -> {
                val group = intent.getStringExtra("group") ?: DEFAULT_GROUP
                val port = intent.getIntExtra("port", DEFAULT_PORT)
                val label = intent.getStringExtra("label") ?: "debug"
                Log.i(TAG, "START_MULTICAST group=$group port=$port")
                handler.startMulticast(group, port, label)
            }

            ACTION_STOP_MULTICAST -> {
                Log.i(TAG, "STOP_MULTICAST")
                handler.stopMulticast()
            }

            ACTION_MESH_VOICE -> {
                val raw = intent.getStringExtra("enabled")?.lowercase()
                val enabled =
                    when (raw) {
                        "true", "1", "on", "yes" -> true
                        "false", "0", "off", "no" -> false
                        else -> {
                            Log.w(TAG, "MESH_VOICE: bad/missing --es enabled '$raw' (use true|false)")
                            return
                        }
                    }
                Log.i(TAG, "MESH_VOICE enabled=$enabled")
                handler.setMeshVoiceEnabled(enabled)
            }

            ACTION_MESH_STATUS -> {
                Log.i(TAG, "MESH_STATUS")
                handler.dumpMeshStatus()
            }

            ACTION_MESH_PLAN_EXPORT -> {
                Log.i(TAG, "MESH_PLAN_EXPORT")
                handler.exportCommsPlan(intent.getStringExtra("passphrase"))
            }

            ACTION_MESH_PLAN_IMPORT -> {
                val plan = intent.getStringExtra("plan")
                if (plan.isNullOrBlank()) {
                    Log.w(TAG, "MESH_PLAN_IMPORT needs --es plan \"<carrier text>\"")
                    return
                }
                Log.i(TAG, "MESH_PLAN_IMPORT (${plan.length} chars)")
                handler.importCommsPlan(plan, intent.getStringExtra("passphrase"))
            }

            ACTION_AUDIO_STATE -> {
                Log.i(TAG, "AUDIO_STATE: ${handler.describeAudioState()}")
            }

            ACTION_AINA_CONNECT -> {
                val mac = intent.getStringExtra("mac")
                val name = intent.getStringExtra("name")
                val kind = (intent.getStringExtra("kind") ?: "v2").lowercase()
                if (mac == null && name == null) {
                    Log.w(TAG, "AINA_CONNECT needs --es mac or --es name")
                    return
                }
                Log.i(TAG, "AINA_CONNECT mac=$mac name=$name kind=$kind")
                handler.connectAina(mac, name, kind)
            }

            ACTION_AINA_DISCONNECT -> {
                Log.i(TAG, "AINA_DISCONNECT")
                handler.disconnectAina()
            }

            ACTION_AINA_LIST_BONDED -> {
                Log.i(TAG, "AINA_LIST_BONDED")
                handler.listBonded()
            }

            ACTION_AINA_SET_PROTOCOL -> {
                val mac = intent.getStringExtra("mac")
                val proto = intent.getStringExtra("proto")?.lowercase()
                if (mac.isNullOrBlank()) {
                    Log.w(TAG, "AINA_SET_PROTOCOL needs --es mac")
                    return
                }
                Log.i(TAG, "AINA_SET_PROTOCOL mac=$mac proto='$proto' (null/blank = clear)")
                handler.setAinaProtocolOverride(mac, proto)
            }

            ACTION_MUMBLE_CONNECT -> {
                val host = intent.getStringExtra("host")
                val takPattern = intent.getStringExtra("tak")
                val port = intent.getIntExtra("port", 64738)
                val channel = intent.getStringExtra("channel") ?: ""
                val secondary = intent.getStringExtra("secondary")
                val vxCompat = intent.getStringExtra("vxcompat")
                Log.i(
                    TAG,
                    "MUMBLE_CONNECT host=$host tak='$takPattern' port=$port channel='$channel' " +
                        "secondary='$secondary' vxcompat='$vxCompat'",
                )
                handler.connectMumble(host, takPattern, port, channel, secondary, vxCompat)
            }

            ACTION_MUMBLE_SET_SECONDARY -> {
                val channel = intent.getStringExtra("channel") ?: ""
                Log.i(TAG, "MUMBLE_SET_SECONDARY channel='$channel'")
                handler.setSecondaryChannel(channel)
            }

            ACTION_MUMBLE_VX_COMPAT -> {
                val mode = intent.getStringExtra("mode")
                Log.i(TAG, "MUMBLE_VX_COMPAT mode='$mode'")
                handler.setDefaultVxCompat(mode)
            }

            ACTION_PRESENCE_DUMP -> {
                Log.i(TAG, "PRESENCE_DUMP")
                handler.dumpPresence()
            }

            ACTION_MUMBLE_AUTO_ACCEPT -> {
                val raw = intent.getStringExtra("enabled")?.lowercase()
                val enabled =
                    when (raw) {
                        "true", "1", "on", "yes" -> true
                        "false", "0", "off", "no" -> false
                        null -> {
                            Log.w(TAG, "MUMBLE_AUTO_ACCEPT: missing --es enabled true|false")
                            return
                        }
                        else -> {
                            Log.w(TAG, "MUMBLE_AUTO_ACCEPT: bad value '$raw' (use true|false)")
                            return
                        }
                    }
                Log.i(TAG, "MUMBLE_AUTO_ACCEPT enabled=$enabled")
                handler.setAutoAcceptPrivateCalls(enabled)
            }

            ACTION_MUMBLE_DISCONNECT -> {
                Log.i(TAG, "MUMBLE_DISCONNECT")
                handler.disconnectMumble()
            }

            ACTION_MUMBLE_LIST_TAK -> {
                Log.i(TAG, "MUMBLE_LIST_TAK")
                handler.listTakServers()
            }

            ACTION_MUMBLE_JOIN -> {
                val channel = intent.getStringExtra("channel")
                val channelId = intent.getIntExtra("channelId", -1)
                Log.i(TAG, "MUMBLE_JOIN channel='$channel' channelId=$channelId")
                handler.joinMumbleChannel(channel, channelId)
            }

            ACTION_SET_AUDIO_ROUTE -> {
                val routeName = intent.getStringExtra("route") ?: ""
                Log.i(TAG, "SET_AUDIO_ROUTE route='$routeName'")
                handler.setAudioRoute(routeName)
            }

            ACTION_TX_START -> {
                Log.i(TAG, "TX_START")
                handler.startTx()
            }

            ACTION_TX_STOP -> {
                Log.i(TAG, "TX_STOP")
                handler.stopTx()
            }
        }
    }

    interface DebugCommandHandler {
        fun startMulticast(
            group: String,
            port: Int,
            label: String,
        )

        fun stopMulticast()

        // Persist the mesh-voice master toggle (same pref as the
        // settings switch). Legs reconcile on the manager's next tick.
        fun setMeshVoiceEnabled(enabled: Boolean)

        // Log mesh-voice state: enabled, legs + endpoints, failover
        // TX state, bridge role, discovered channels.
        fun dumpMeshStatus()

        // Log the current channel set as a comms-plan carrier string
        // (cleartext, or KDF-locked when a passphrase is given).
        fun exportCommsPlan(passphrase: String?)

        // Import a comms-plan carrier: persist channel configs,
        // install pre-shared keys, land on the first channel when
        // none is selected.
        fun importCommsPlan(
            planText: String,
            passphrase: String?,
        )

        fun describeAudioState(): String

        fun connectAina(
            mac: String?,
            name: String?,
            kind: String,
        )

        fun disconnectAina()

        fun listBonded()

        // Persist a per-MAC button-protocol override consulted by
        // `connectAinaInternal` before auto-detect. Pass `proto` =
        // "v1" / "v2" / "ble-hid" to force, or null/blank to clear and
        // fall back to auto-detect. Survives plugin reload.
        fun setAinaProtocolOverride(
            mac: String,
            proto: String?,
        )

        fun connectMumble(
            host: String?,
            takPattern: String?,
            port: Int,
            channel: String,
            // null/blank = no secondary channel; PTTS falls back to primary
            // channel TX. Otherwise the channel name to bind VS2 to via
            // Mumble VoiceTarget slot 1.
            secondaryChannel: String? = null,
            // null/blank = use the persistent defaultVxCompat. "off"|"hybrid"|"strict"
            // override for this connect only.
            vxCompat: String? = null,
        )

        // Re-target VS2 on an already-connected session. Empty/null clears.
        fun setSecondaryChannel(channelName: String)

        // Set the persistent default for VX-compat handshake. Takes effect on
        // the next connect (call disconnect+connect to apply immediately).
        fun setDefaultVxCompat(mode: String?)

        // Toggle the Phase-1 private-call auto-accept stub. Takes effect
        // immediately (no reconnect needed).
        fun setAutoAcceptPrivateCalls(enabled: Boolean)

        // Log the current XV peer registry contents (Layer-2 CoT
        // presence). One line per known peer; empty if none seen yet.
        fun dumpPresence()

        fun disconnectMumble()

        fun listTakServers()

        fun joinMumbleChannel(
            channelName: String?,
            channelId: Int,
        )

        fun setAudioRoute(routeName: String)

        fun startTx()

        fun stopTx()
    }

    companion object {
        private const val TAG = "XvDebugReceiver"

        const val ACTION_START_MULTICAST = "com.atakmap.android.xv.debug.START_MULTICAST"
        const val ACTION_STOP_MULTICAST = "com.atakmap.android.xv.debug.STOP_MULTICAST"
        const val ACTION_MESH_VOICE = "com.atakmap.android.xv.debug.MESH_VOICE"
        const val ACTION_MESH_STATUS = "com.atakmap.android.xv.debug.MESH_STATUS"
        const val ACTION_MESH_PLAN_EXPORT = "com.atakmap.android.xv.debug.MESH_PLAN_EXPORT"
        const val ACTION_MESH_PLAN_IMPORT = "com.atakmap.android.xv.debug.MESH_PLAN_IMPORT"
        const val ACTION_AUDIO_STATE = "com.atakmap.android.xv.debug.AUDIO_STATE"
        const val ACTION_AINA_CONNECT = "com.atakmap.android.xv.debug.AINA_CONNECT"
        const val ACTION_AINA_DISCONNECT = "com.atakmap.android.xv.debug.AINA_DISCONNECT"
        const val ACTION_AINA_LIST_BONDED = "com.atakmap.android.xv.debug.AINA_LIST_BONDED"
        const val ACTION_AINA_SET_PROTOCOL = "com.atakmap.android.xv.debug.AINA_SET_PROTOCOL"
        const val ACTION_MUMBLE_CONNECT = "com.atakmap.android.xv.debug.MUMBLE_CONNECT"
        const val ACTION_MUMBLE_DISCONNECT = "com.atakmap.android.xv.debug.MUMBLE_DISCONNECT"
        const val ACTION_MUMBLE_SET_SECONDARY = "com.atakmap.android.xv.debug.MUMBLE_SET_SECONDARY"
        const val ACTION_MUMBLE_VX_COMPAT = "com.atakmap.android.xv.debug.MUMBLE_VX_COMPAT"
        const val ACTION_MUMBLE_AUTO_ACCEPT = "com.atakmap.android.xv.debug.MUMBLE_AUTO_ACCEPT"
        const val ACTION_PRESENCE_DUMP = "com.atakmap.android.xv.debug.PRESENCE_DUMP"
        const val ACTION_MUMBLE_LIST_TAK = "com.atakmap.android.xv.debug.MUMBLE_LIST_TAK"
        const val ACTION_MUMBLE_JOIN = "com.atakmap.android.xv.debug.MUMBLE_JOIN"
        const val ACTION_SET_AUDIO_ROUTE = "com.atakmap.android.xv.debug.SET_AUDIO_ROUTE"
        const val ACTION_TX_START = "com.atakmap.android.xv.debug.TX_START"
        const val ACTION_TX_STOP = "com.atakmap.android.xv.debug.TX_STOP"

        const val DEFAULT_GROUP = "239.0.1.1"
        const val DEFAULT_PORT = 6001

        @Volatile
        var handler: DebugCommandHandler? = null
    }
}
