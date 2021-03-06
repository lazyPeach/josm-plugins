// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.turnrestrictions.list;

import java.awt.BorderLayout;
import java.util.Collections;

import javax.swing.DefaultListSelectionModel;
import javax.swing.JList;
import javax.swing.JScrollPane;
import javax.swing.ListSelectionModel;

import org.openstreetmap.josm.Main;
import org.openstreetmap.josm.data.SelectionChangedListener;
import org.openstreetmap.josm.data.osm.OsmPrimitive;
import org.openstreetmap.josm.data.osm.event.DatasetEventManager.FireMode;
import org.openstreetmap.josm.data.osm.event.SelectionEventManager;
import org.openstreetmap.josm.gui.layer.MainLayerManager.ActiveLayerChangeListener;

/**
 * This is the view for the list of turn restrictions related to objects in the
 * current selection.
 *
 */
public class TurnRestrictionsInSelectionView extends AbstractTurnRestrictionsListView {

    protected void build() {
        DefaultListSelectionModel selectionModel = new DefaultListSelectionModel();
        model = new TurnRestrictionsInSelectionListModel(selectionModel);
        lstTurnRestrictions = new JList<>(model);
        lstTurnRestrictions.setSelectionModel(selectionModel);
        lstTurnRestrictions.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        lstTurnRestrictions.setCellRenderer(new TurnRestrictionCellRenderer());

        setLayout(new BorderLayout());
        add(new JScrollPane(lstTurnRestrictions), BorderLayout.CENTER);
    }

    protected void registerAsListener() {
        Main.getLayerManager().addActiveLayerChangeListener((ActiveLayerChangeListener) model);
        SelectionEventManager.getInstance().addSelectionListener((SelectionChangedListener) model, FireMode.IN_EDT_CONSOLIDATED);
        TurnRestrictionsInSelectionListModel m = (TurnRestrictionsInSelectionListModel) model;
        if (Main.getLayerManager().getEditLayer() != null) {
            m.initFromSelection(Main.getLayerManager().getEditLayer().data.getSelected());
        } else {
            m.initFromSelection(Collections.<OsmPrimitive>emptyList());
        }
    }

    protected void unregisterAsListener() {
        Main.getLayerManager().removeActiveLayerChangeListener((ActiveLayerChangeListener) model);
        SelectionEventManager.getInstance().removeSelectionListener((SelectionChangedListener) model);
    }

    public TurnRestrictionsInSelectionView() {
        build();
    }
}
