package com.atakmap.android.xv.ui

import android.content.Context
import android.content.Intent
import android.util.Log
import com.atakmap.android.maps.MapItem
import com.atakmap.android.menu.MapMenuButtonWidget
import com.atakmap.android.menu.MapMenuHandler
import com.atakmap.android.menu.MapMenuWidget
import com.atakmap.android.xv.plugin.XvTool
import com.atakmap.android.xv.presence.XvPresenceRegistry

class XvMapMenuHandler(
    private val ctx: Context,
    private val registry: XvPresenceRegistry
) : MapMenuHandler {

    override fun updateMenu(item: MapItem?, menuWidget: MapMenuWidget?) {
        if (item == null || menuWidget == null) return
        val uid = item.uid ?: return

        // Find presence
        val presence = registry.get(uid)
        if (presence == null || presence.channels.isEmpty()) return

        try {
            val btn = MapMenuButtonWidget(ctx)
            btn.text = "Join Channel"

            btn.onButtonClickHandler = object : gov.tak.api.widgets.IMapMenuButtonWidget.OnButtonClickHandler {
                override fun isSupported(obj: Any?): Boolean = true
                override fun performAction(obj: Any?) {
                    Log.i(TAG, "Join channel button clicked for uid $uid")

                    val i = Intent(XvTool.SHOW_XV)
                    i.putExtra("JOIN_PEER_UID", uid)
                    com.atakmap.android.ipc.AtakBroadcast.getInstance().sendBroadcast(i)
                }
            }
            // Add the button to the widget
            menuWidget.addWidget(btn)
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to add X-Voice menu button", t)
        }
    }

    companion object {
        private const val TAG = "XvMapMenuHandler"
    }
}
