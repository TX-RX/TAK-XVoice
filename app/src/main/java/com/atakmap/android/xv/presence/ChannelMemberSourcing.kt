package com.atakmap.android.xv.presence

/**
 * Pure decisions for how a Channel-Members row is classified as cloud
 * (☁️ server) vs local-mesh (📻) for the 👥 badge and the members
 * picker.
 *
 * The bug this guards: XV *presence* advertisements (CoT) propagate
 * over the TAK network independently of whether the local operator has
 * mesh voice enabled. Before this gate, [buildSlotMembersSnapshot]
 * surfaced any presence-only peer as a 📻 LOCAL_MESH row and upgraded a
 * server member who also advertised presence to BOTH — even with mesh
 * voice OFF. Operators on a cloud-only (mesh-disabled) session then saw
 * a non-zero mesh client count. The split must follow the mesh toggle:
 * with mesh disabled there are no mesh members, so 📻 is 0 and no
 * server member is BOTH.
 */
object ChannelMemberSourcing {
    enum class ServerSource { SERVER, BOTH }

    /**
     * Classify a peer visible on the Mumble *server* roster.
     * [alsoReachableOnMesh] = that peer also advertises XV presence on
     * the joined channel. Upgrades to [ServerSource.BOTH] only when
     * [meshEnabled]; otherwise the mesh leg is not in use and the row
     * is plain [ServerSource.SERVER].
     */
    fun serverSource(alsoReachableOnMesh: Boolean, meshEnabled: Boolean): ServerSource =
        if (meshEnabled && alsoReachableOnMesh) ServerSource.BOTH else ServerSource.SERVER

    /**
     * Whether presence-only peers (known via CoT presence but not on
     * the Mumble server roster) should surface as 📻 LOCAL_MESH rows.
     * Only when [meshEnabled]; with mesh off they are not mesh members
     * and must not be counted.
     */
    fun includeLocalMeshRows(meshEnabled: Boolean): Boolean = meshEnabled
}
