package com.atakmap.android.xv.plugin

import com.atak.plugins.impl.AbstractPlugin
import com.atak.plugins.impl.PluginContextProvider
import gov.tak.api.plugin.IServiceController

/**
 * Top-level plugin class that ATAK loads via the `IPlugin` interface
 * declared in `assets/plugin.xml`. Mirrors the SDK helloworld
 * Lifecycle: passes both an IToolbarItem ([XvTool]) and the
 * [XvMapComponent] so XV appears in ATAK's tool drawer with an icon.
 *
 * The 3-arg AbstractPlugin constructor is the version that registers a
 * toolbar entry; the 2-arg version only loads the MapComponent and
 * leaves the plugin invisible to the user. Always use 3-arg unless
 * the plugin is explicitly background-only (the bridge is the
 * only XV-adjacent example of that pattern).
 */
class XvPlugin(
    serviceController: IServiceController,
) : AbstractPlugin(
    serviceController,
    XvTool(
        serviceController.getService(PluginContextProvider::class.java).pluginContext,
    ),
    XvMapComponent(),
)
