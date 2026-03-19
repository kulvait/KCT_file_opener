/*******************************************************************************
 * Project : KCT ImageJ plugin to open files
 * Author: Vojtěch Kulvait
 * Licence: GNU GPL3
 * Description : Class to add preview of Zarr arrays in JFileChooser when opening them.
 * Date: 2026
 ******************************************************************************/

package com.kulvait.kct.imagej.zarfileopener;

import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.io.File;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JTextArea;
import javax.swing.JTree;
import javax.swing.JScrollPane;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import com.kulvait.kct.imagej.denfileopener.DenFileInfo;

public class ZarOpenerAccessory extends JComponent implements PropertyChangeListener {

    private static final long serialVersionUID = 1L;

    File selectedFile = null;

    JLabel nameInfo;
    JLabel typeInfo;
    JLabel dimInfo;
    JLabel noInfo;
    JLabel debugInfo;
    JCheckBox virtualCheckBox;
//Add Jtree and scroll pane for metadata display
    private JTree arrayTree;
    private JScrollPane treeScrollPane;

    boolean checkBoxInit = false;
    int preferredWidth = 250;
    int preferredHeight = 500; // Mostly ignored as it is
    int checkBoxPosX = 5;
    int checkBoxPosY = 20;
    int checkBoxWidth = preferredWidth;
    int checkBoxHeight = 20;

    public ZarOpenerAccessory() {
        setPreferredSize(new Dimension(preferredWidth, preferredHeight));
        setLayout(new GridBagLayout());
        // Component initialization
        nameInfo = new JLabel();
        typeInfo = new JLabel();
        dimInfo = new JLabel();
        debugInfo = new JLabel();
        noInfo = new JLabel();
        virtualCheckBox = new JCheckBox("Virtual stack", checkBoxInit);
        virtualCheckBox.setBounds(checkBoxPosX, checkBoxPosY, checkBoxWidth, checkBoxHeight);

        JTextArea abc = new JTextArea();
        abc.setPreferredSize(new Dimension(preferredWidth, preferredHeight));
        abc.setLineWrap(true);
        abc.setWrapStyleWord(true);
        abc.setEditable(false);
        abc.setText("DEN.");

        DefaultMutableTreeNode root = new DefaultMutableTreeNode("Root");

// Level 1
        DefaultMutableTreeNode fruits = new DefaultMutableTreeNode("Fruits");
        DefaultMutableTreeNode vegetables = new DefaultMutableTreeNode("Vegetables");
        DefaultMutableTreeNode mushroom = new DefaultMutableTreeNode("Mushroom");

        root.add(fruits);
        root.add(vegetables);
        root.add(mushroom);

// Level 2
        fruits.add(new DefaultMutableTreeNode("Apple"));
        fruits.add(new DefaultMutableTreeNode("Banana"));
        fruits.add(new DefaultMutableTreeNode("Orange"));

        DefaultMutableTreeNode leafy = new DefaultMutableTreeNode("Leafy");
        DefaultMutableTreeNode rootVeg = new DefaultMutableTreeNode("Root vegetables");

        vegetables.add(leafy);
        vegetables.add(rootVeg);

// Level 3
        leafy.add(new DefaultMutableTreeNode("Spinach"));
        leafy.add(new DefaultMutableTreeNode("Lettuce"));

        rootVeg.add(new DefaultMutableTreeNode("Carrot"));
        rootVeg.add(new DefaultMutableTreeNode("Potato"));

// Create tree
        arrayTree = new JTree(root);
        arrayTree.setRootVisible(false);
        arrayTree.setShowsRootHandles(true);
        treeScrollPane = new JScrollPane(arrayTree);
        treeScrollPane.setPreferredSize(new Dimension(preferredWidth, 0)); // Height will be adjusted automatically

        // Positioning
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.ipady = 10;
        // gbc.insets = new Insets(0, 0, 0, 0);
        // c.gridwidth = GridBagConstraints.REMAINDER;
        // c.anchor = GridBagConstraints.WEST;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        this.add(nameInfo, gbc);
        gbc.gridy = 1;
        gbc.ipady = 0;
        this.add(typeInfo, gbc);
        gbc.gridy = 2;
        this.add(dimInfo, gbc);
        gbc.gridy = 3;
        gbc.ipady = 10;
        this.add(virtualCheckBox, gbc);
        gbc.gridy = 4;
        gbc.weighty = 0;
        this.add(noInfo, gbc);
        gbc.gridy = 5;
        gbc.weighty = 0;
        this.add(debugInfo, gbc);
        debugInfo.setVisible(false);
        this.setVisible(false);
        gbc.gridy = 6;
        gbc.weighty = 1;
        gbc.fill = GridBagConstraints.BOTH;
        gbc.anchor = GridBagConstraints.NORTH;
        this.add(treeScrollPane, gbc);
    }

    public ZarOpenerAccessory(JFileChooser fc) {
        this();
        fc.addPropertyChangeListener(this);
    }

    public boolean isBoxSelected() {
        return virtualCheckBox.isSelected();
    }

    public void propertyChange(PropertyChangeEvent e) {
        boolean update = false;
        String prop = e.getPropertyName();

        // If the directory changed, don't show a preview
        if (JFileChooser.DIRECTORY_CHANGED_PROPERTY.equals(prop)) {
            selectedFile = null;
            nameInfo.setText("N/A");
            this.setVisible(false);
            update = true;
            // If a file became selected, find out which one.
        } else if (JFileChooser.SELECTED_FILE_CHANGED_PROPERTY.equals(prop)) {
            this.setVisible(true);
            selectedFile = (File) e.getNewValue();
            if (selectedFile != null) {
                updateInfo(selectedFile);
                update = true;
            } else {
                this.setVisible(false);
            }
        } else {
            // JOptionPane.showMessageDialog(null, String.format("property=%s",
            // prop));
        }

        // Update the preview accordingly.
        if (update && isShowing()) {
            repaint();
        }
    }

    public void updateInfo(File f) {
        ZarFileInfo zarInf = new ZarFileInfo(f);
        if (zarInf.isValidZarr()) {
            if (zarInf.isZipZarr()) {
                nameInfo.setText(String.format("Zarr zip container"));
            } else {
                nameInfo.setText(String.format("Zarr folder"));
            }
            if (zarInf.isTopLevelArray()) {
                typeInfo.setText(String.format("TL array"));
            } else {
                typeInfo.setText(String.format("Group"));
//Populate Jtree with group content
                DefaultMutableTreeNode root = new DefaultMutableTreeNode("/");
                zarInf.getGroupContent(root);
                DefaultTreeModel model = new DefaultTreeModel(root);
                arrayTree.setModel(model);
                arrayTree.setRootVisible(false);
                arrayTree.setShowsRootHandles(true);
            }
            virtualCheckBox.setVisible(true);


        } else {
            DenFileInfo denInf = new DenFileInfo(f);
            if (denInf.isValidDEN()) {
                if (denInf.isExtendedDEN()) {
                    nameInfo.setText("Extended DEN.");
                } else {
                    nameInfo.setText("Legacy DEN.");
                }
                int DIMCOUNT = denInf.getDIMCOUNT();
                typeInfo.setText(String.format("%dD %s", DIMCOUNT, denInf.getElementType().name()));
                String dimString;
                if (DIMCOUNT == 0) {
                    dimString = "Empty dim";
                } else {
                    dimString = String.format("%d", denInf.getDim(0));
                    for (int i = 1; i < DIMCOUNT; i++) {
                        dimString = String.format("%sx%d", dimString, denInf.getDim(i));
                    }
                }
                if (DIMCOUNT > 2) {
                    virtualCheckBox.setVisible(true);
                }
                if (denInf.getElementCount() < 32768) {
                    virtualCheckBox.setSelected(false);
                } else {
                    virtualCheckBox.setSelected(true);
                }
                dimInfo.setText(dimString);
                treeScrollPane.setVisible(false);
            } else {
                nameInfo.setText("NO DEN/ZAR");
                typeInfo.setText("TYPEINFO");
                dimInfo.setText("DIMINFO");
                debugInfo.setText("Not a valid Zarr or DEN file.");
                noInfo.setText("No preview available.");
                noInfo.setVisible(true);
                virtualCheckBox.setVisible(true);
                treeScrollPane.setVisible(true);
            }
        }
    }

    protected void paintComponent(Graphics g) {
        int componentWidth = getWidth();
        int componentHeight = getHeight();
        String debugStr = String.format("w=%d, h=%d", componentWidth, componentHeight);
        // g.drawString(debugStr, 5, 10);
        debugInfo.setText(debugStr);
    }
}
