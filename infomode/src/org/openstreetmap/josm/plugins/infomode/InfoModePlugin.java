// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.infomode;

import org.openstreetmap.josm.Main;
import org.openstreetmap.josm.gui.IconToggleButton;
import org.openstreetmap.josm.gui.MapFrame;
import org.openstreetmap.josm.plugins.Plugin;
import org.openstreetmap.josm.plugins.PluginInformation;

public class InfoModePlugin extends Plugin {

    public InfoModePlugin(PluginInformation info) {
        super(info);
    }

    @Override
    public void mapFrameInitialized(MapFrame oldFrame, MapFrame newFrame) {
        if (oldFrame == null && newFrame != null) {
            Main.map.addMapMode(new IconToggleButton(new InfoMode(Main.map)));
        }
    }
}
