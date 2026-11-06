/*******************************************************************************
 * Project : KCT ImageJ plugin to open files
 * Author: Vojtěch Kulvait
 * Licence: GNU GPL3
 * Description : Class to add preview of Zarr arrays in JFileChooser when opening them.
 * Date: 2026
 ******************************************************************************/

package com.kulvait.kct.imagej.zarfileopener;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.io.File;
import java.util.Arrays;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTree;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreeSelectionModel;

import com.kulvait.kct.imagej.denfileopener.DenFileInfo;

public class ZarOpenerAccessory extends JComponent implements PropertyChangeListener {
    private static final long serialVersionUID = 1L;
    private static final String NOT_APPLICABLE = "-";
    private static final String ZARR_VIRTUAL_STACK_TOOLTIP = "Zarr currently supports virtual stacks only.";

    private final JLabel formatInfo = new JLabel();
    private final JLabel typeInfo = new JLabel();
    private final JLabel shapeInfo = new JLabel();
    private final JLabel chunksInfo = new JLabel();
    private final JLabel statusInfo = new JLabel();
    private final JLabel directoryMessage = new JLabel("Not a Zarr directory");
    private final JCheckBox virtualCheckBox = new JCheckBox("Virtual stack");
    private final JTree arrayTree = new JTree(emptyTreeModel());
    private final JScrollPane treeScrollPane = new JScrollPane(arrayTree);
    private File selectedFile;
    private ZarFileInfo selectedZarrFileInfo;
    private ZarrNode selectedNode;

    public ZarOpenerAccessory() {
        setPreferredSize(new Dimension(360, 500));
        setLayout(new GridBagLayout());
        addInfoRow("Format:", formatInfo, 0);
        addInfoRow("Type:", typeInfo, 1);
        addInfoRow("Shape:", shapeInfo, 2);
        addInfoRow("Chunks:", chunksInfo, 3);

        GridBagConstraints constraints = new GridBagConstraints();
        constraints.gridx = 0;
        constraints.gridy = 4;
        constraints.gridwidth = 2;
        constraints.fill = GridBagConstraints.HORIZONTAL;
        constraints.insets = new Insets(4, 5, 4, 5);
        add(virtualCheckBox, constraints);
        addInfoRow("Status:", statusInfo, 5);

        arrayTree.setRootVisible(false);
        arrayTree.setShowsRootHandles(true);
        arrayTree.getSelectionModel().setSelectionMode(TreeSelectionModel.SINGLE_TREE_SELECTION);
        arrayTree.addTreeSelectionListener(event -> updateTreeSelection());
        JPanel hierarchyArea = new JPanel(new BorderLayout());
        hierarchyArea.setOpaque(false);
        hierarchyArea.setPreferredSize(new Dimension(360, 0));
        hierarchyArea.add(treeScrollPane, BorderLayout.CENTER);
        constraints.gridy = 6;
        constraints.weightx = 1;
        constraints.weighty = 1;
        constraints.fill = GridBagConstraints.BOTH;
        add(hierarchyArea, constraints);

        constraints.gridy = 0;
        constraints.weighty = 1;
        constraints.anchor = GridBagConstraints.NORTHWEST;
        constraints.fill = GridBagConstraints.NONE;
        add(directoryMessage, constraints);
        resetPreview();
    }

    public ZarOpenerAccessory(JFileChooser chooser) {
        this();
        chooser.addPropertyChangeListener(this);
    }

    private void addInfoRow(String caption, JLabel value, int row) {
        GridBagConstraints constraints = new GridBagConstraints();
        constraints.gridx = 0;
        constraints.gridy = row;
        constraints.anchor = GridBagConstraints.WEST;
        constraints.insets = new Insets(4, 5, 4, 5);
        add(new JLabel(caption), constraints);
        constraints.gridx = 1;
        constraints.weightx = 1;
        constraints.fill = GridBagConstraints.HORIZONTAL;
        add(value, constraints);
    }

    private static DefaultTreeModel emptyTreeModel() {
        return new DefaultTreeModel(new DefaultMutableTreeNode());
    }

    private void resetPreview() {
        for (Component component : getComponents()) {
            component.setVisible(component != directoryMessage);
        }
        selectedFile = null;
        selectedZarrFileInfo = null;
        selectedNode = null;
        arrayTree.setModel(emptyTreeModel());
        treeScrollPane.setVisible(false);
        setVisible(false);
        formatInfo.setText(NOT_APPLICABLE);
        typeInfo.setText(NOT_APPLICABLE);
        shapeInfo.setText(NOT_APPLICABLE);
        chunksInfo.setText(NOT_APPLICABLE);
        virtualCheckBox.setSelected(false);
        virtualCheckBox.setEnabled(false);
        virtualCheckBox.setToolTipText(null);
        statusInfo.setText("Select a Zarr store or DEN file");
    }

    private void updateTreeSelection() {
        if (selectedZarrFileInfo == null || !selectedZarrFileInfo.isValidZarr()) {
            return;
        }
        DefaultMutableTreeNode treeNode = (DefaultMutableTreeNode) arrayTree.getLastSelectedPathComponent();
        selectedNode = treeNode == null ? null : (ZarrNode) treeNode.getUserObject();
        showZarrNode(selectedNode == null ? selectedZarrFileInfo.getRootNode() : selectedNode);
    }

    private void showZarrNode(ZarrNode node) {
        shapeInfo.setText(NOT_APPLICABLE);
        chunksInfo.setText(NOT_APPLICABLE);
        if (node instanceof ZarrArrayNode arrayNode) {
            typeInfo.setText("Array - " + arrayNode.getDataType());
            shapeInfo.setText(Arrays.toString(arrayNode.getShape()));
            chunksInfo.setText(Arrays.toString(arrayNode.getChunkShape()));
            statusInfo.setText("Ready to open");
        } else {
            typeInfo.setText("Group");
            statusInfo.setText("Select an array");
        }
    }

    private void showZarrPreview() {
        formatInfo.setText(selectedZarrFileInfo.isZipZarr() ? "Zarr ZIP" : "Zarr folder");
        virtualCheckBox.setSelected(true);
        virtualCheckBox.setToolTipText(ZARR_VIRTUAL_STACK_TOOLTIP);
        ZarrNode root = selectedZarrFileInfo.getRootNode();
        if (selectedZarrFileInfo.isTopLevelArray()) {
            selectedNode = root;
        } else {
            arrayTree.setModel(new DefaultTreeModel(selectedZarrFileInfo.getJTreeRootNode()));
            treeScrollPane.setVisible(true);
        }
        showZarrNode(root);
    }

    private void showDenPreview(DenFileInfo info) {
        formatInfo.setText(info.isExtendedDEN() ? "DEN extended" : "DEN legacy");
        typeInfo.setText(info.getElementType().name());
        long[] shape = new long[info.getDIMCOUNT()];
        for (int i = 0; i < shape.length; i++) {
            shape[i] = info.getDim(i);
        }
        shapeInfo.setText(Arrays.toString(shape));
        virtualCheckBox.setEnabled(true);
        virtualCheckBox.setSelected(info.getElementCount() >= 32768);
        statusInfo.setText("Ready to open");
    }

    public String[] getSelectedZarrPath() {
        return selectedNode == null ? null : selectedNode.getZarrPath();
    }

    public File getSelectedFile() {
        return selectedFile;
    }

    public ZarFileInfo getSelectedZarrFileInfo() {
        return selectedZarrFileInfo;
    }

    public boolean isBoxSelected() {
        return virtualCheckBox.isSelected();
    }

    @Override
    public void propertyChange(PropertyChangeEvent event) {
        String property = event.getPropertyName();
        if (JFileChooser.DIRECTORY_CHANGED_PROPERTY.equals(property)) {
            updateInfo(null);
        } else if (JFileChooser.SELECTED_FILE_CHANGED_PROPERTY.equals(property)) {
            updateInfo((File) event.getNewValue());
        }
    }

    public void updateInfo(File file) {
        resetPreview();
        if (file != null) {
            selectedFile = file;
            selectedZarrFileInfo = new ZarFileInfo(file);
            if (selectedZarrFileInfo.isValidZarr()) {
                showZarrPreview();
                setVisible(true);
            } else if (file.isDirectory()) {
                for (Component component : getComponents()) {
                    component.setVisible(component == directoryMessage);
                }
                setVisible(true);
            } else {
                DenFileInfo info = new DenFileInfo(file);
                if (info.isValidDEN()) {
                    showDenPreview(info);
                    setVisible(true);
                }
            }
        }
        revalidate();
        repaint();
    }
}
