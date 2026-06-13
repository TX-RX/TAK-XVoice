package com.atakmap.android.xv.plugin

import android.content.Context
import com.atak.plugins.impl.AbstractPluginTool

/**
 * Toolbar entry for XV. Appears in ATAK's nav-button / tool drawer with
 * the plugin name + icon. Tapping fires [SHOW_XV] which the MapComponent
 * registers a receiver for; the receiver opens XV's UI panel (in Phase 1
 * this just toasts current state).
 *
 * Mirrors the SDK helloworld sample's HelloWorldTool pattern verbatim.
 * The 4-string AbstractPluginTool constructor is:
 *   (context, displayName, description, icon, showActionName)
 */
class XvTool(
    context: Context,
) : AbstractPluginTool(
    context,
    context.getString(com.atakmap.android.xv.R.string.app_name),
    context.getString(com.atakmap.android.xv.R.string.app_desc),
    // Toolbar variant of the brand icon: silhouette on transparent BG.
    // The full mipmap (slate rounded square + silhouette) reads as a
    // solid white box when ATAK's nav-button tints it — every pixel is
    // opaque so the tint covers the whole thing. drawable/xv_tool_icon
    // is just the walkie-talkie shape so the tint colors the silhouette
    // and leaves the surrounding toolbar background showing through.
    context.resources.getDrawable(
        com.atakmap.android.xv.R.drawable.xv_tool_icon,
        context.theme,
    ),
    SHOW_XV,
) {
    companion object {
        const val SHOW_XV = "com.atakmap.android.xv.SHOW_XV"
    }
}
