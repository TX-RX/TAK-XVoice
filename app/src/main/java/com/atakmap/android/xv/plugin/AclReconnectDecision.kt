package com.atakmap.android.xv.plugin

object AclReconnectDecision {
    fun shouldReconnectOnAcl(connectedMac: String, savedMac: String?): Boolean =
        savedMac != null && connectedMac.equals(savedMac, ignoreCase = true)
}
