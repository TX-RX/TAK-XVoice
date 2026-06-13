package com.atakmap.android.xv.transport.mumble

// Mumble TCP control-channel message type IDs. The wire framing is:
//   uint16 BE type | uint32 BE length | <length> bytes payload
// where type is one of these values. Source: Mumble.proto / Connection.cpp.
object MumbleMessageType {
    const val VERSION = 0
    const val UDP_TUNNEL = 1
    const val AUTHENTICATE = 2
    const val PING = 3
    const val REJECT = 4
    const val SERVER_SYNC = 5
    const val CHANNEL_REMOVE = 6
    const val CHANNEL_STATE = 7
    const val USER_REMOVE = 8
    const val USER_STATE = 9
    const val BAN_LIST = 10
    const val TEXT_MESSAGE = 11
    const val PERMISSION_DENIED = 12
    const val ACL = 13
    const val QUERY_USERS = 14
    const val CRYPT_SETUP = 15
    const val CONTEXT_ACTION_MODIFY = 16
    const val CONTEXT_ACTION = 17
    const val USER_LIST = 18
    const val VOICE_TARGET = 19
    const val PERMISSION_QUERY = 20
    const val CODEC_VERSION = 21
    const val USER_STATS = 22
    const val REQUEST_BLOB = 23
    const val SERVER_CONFIG = 24
    const val SUGGEST_CONFIG = 25

    fun nameOf(type: Int): String =
        when (type) {
            VERSION -> "Version"
            UDP_TUNNEL -> "UDPTunnel"
            AUTHENTICATE -> "Authenticate"
            PING -> "Ping"
            REJECT -> "Reject"
            SERVER_SYNC -> "ServerSync"
            CHANNEL_REMOVE -> "ChannelRemove"
            CHANNEL_STATE -> "ChannelState"
            USER_REMOVE -> "UserRemove"
            USER_STATE -> "UserState"
            BAN_LIST -> "BanList"
            TEXT_MESSAGE -> "TextMessage"
            PERMISSION_DENIED -> "PermissionDenied"
            ACL -> "ACL"
            QUERY_USERS -> "QueryUsers"
            CRYPT_SETUP -> "CryptSetup"
            CONTEXT_ACTION_MODIFY -> "ContextActionModify"
            CONTEXT_ACTION -> "ContextAction"
            USER_LIST -> "UserList"
            VOICE_TARGET -> "VoiceTarget"
            PERMISSION_QUERY -> "PermissionQuery"
            CODEC_VERSION -> "CodecVersion"
            USER_STATS -> "UserStats"
            REQUEST_BLOB -> "RequestBlob"
            SERVER_CONFIG -> "ServerConfig"
            SUGGEST_CONFIG -> "SuggestConfig"
            else -> "Unknown($type)"
        }
}
