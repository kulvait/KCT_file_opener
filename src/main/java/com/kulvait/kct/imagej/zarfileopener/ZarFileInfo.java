/*******************************************************************************
 * Project : KCT ImageJ plugin to open DEN files
 * Author: Vojtěch Kulvait
 * Licence: GNU GPL3
 * Description : Class to get information about legacy or extended DEN
 * Date: 2026
 ******************************************************************************/

package com.kulvait.kct.imagej.zarfileopener;

// Java imports
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.Buffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.Arrays;
import java.util.List;
import java.util.zip.ZipFile;
import java.util.zip.ZipEntry;
import java.util.Enumeration;
// Logging imports
import java.util.logging.Level;
import java.util.logging.Logger;
// Java NIO imports for file handling
import java.nio.file.Path;
import java.nio.file.Files;
// Javax imports
import javax.swing.tree.DefaultMutableTreeNode;
// Zarr Java library imports
import dev.zarr.zarrjava.core.Array;
import dev.zarr.zarrjava.core.ArrayMetadata;
import dev.zarr.zarrjava.store.ReadOnlyZipStore;
import dev.zarr.zarrjava.store.FilesystemStore;
import dev.zarr.zarrjava.store.StoreHandle;
import dev.zarr.zarrjava.ZarrException;

public class ZarFileInfo {
    private static final Logger logger = Logger.getLogger(ZarFileInfo.class.getName());
    private File f;
    private boolean validZarr = true;
    private ZarrRootNode rootNode = null;

    private boolean checkIfZip(Path path) {
        try (FileChannel fileChannel = FileChannel.open(path)) {
            MappedByteBuffer buffer = fileChannel.map(
                    FileChannel.MapMode.READ_ONLY, 0, Math.min(4, fileChannel.size()));
            return buffer.getInt() == 0x504b0304; // ZIP file signature
        } catch (IOException e) {
            return false;
        }
    }

    private boolean checkIfPathContainsZarrMetadata(Path path) {
        try {
            if (path.toFile().isFile()) {
                // If it's a file, we check if it's a ZIP file, if not, we consider it not a valid Zarr container
                if (checkIfZip(path)) {
                    try (ZipFile zipFile = new ZipFile(path.toFile())) {
                        Enumeration<? extends ZipEntry> entries = zipFile.entries();
                        while (entries.hasMoreElements()) {
                            ZipEntry entry = entries.nextElement();
                            String name = entry.getName();

                            // Check only top-level files (ignore directories inside ZIP)
                            if (!entry.isDirectory() && !name.contains("/")) {
                                if (name.equals(".zgroup") || name.equals(".zarray") || name.equals("zarr.json")) {
                                    return true;
                                }
                            }
                        }
                    } catch (IOException e) {
                        logger.log(Level.WARNING, "Error reading zip file: " + e.getMessage(), e);
                    }
                    return false; // ZIP file but no Zarr metadata found


                }
            } else if (path.toFile().isDirectory()) {
                // If it's a directory, we check if it contains Zarr metadata files
                return Files.list(path).anyMatch(p -> p.getFileName().toString().equals(
                        ".zgroup") || p.getFileName().toString().equals(".zarray") || p.getFileName().toString().equals(
                                "zarr.json"));
            }
            return false;
        } catch (IOException e) {
            logger.log(Level.WARNING, "Error checking directory contents: " + e.getMessage(), e);
            return false;
        }
    }

    ZarFileInfo(File f) {
        this.f = f;
        Path path = f.toPath();

        // Check if it's a file or folder
        if (!Files.exists(path)) {
            System.out.println("File does not exist: " + path);
            validZarr = false;
            return;
        }
        boolean isDirectory = Files.isDirectory(path);
        boolean isFile = Files.isRegularFile(path);
        boolean isZip = false;
        if (isFile) {
            isZip = checkIfZip(path);
        }
        // This is not to build expensive structure on no metadata
        boolean hasZarrMetadata = checkIfPathContainsZarrMetadata(path);
        if (!hasZarrMetadata) {
            System.out.println("No Zarr metadata found in: " + path);
            validZarr = false;
            return;
        }
        StoreHandle store = null;
        try {
            if (isZip) {
                System.out.println("Opening ZIP store: " + path);
                store = new ReadOnlyZipStore(path).resolve(); // root handle
            } else if (isDirectory) {
                System.out.println("Opening folder store: " + path);
                store = new StoreHandle(new FilesystemStore(path));
            } else if (isFile) {
                String msg = String.format(
                        "File %s is not a ZIP file nor directory, not a Zarr container%n", path);
                logger.log(Level.INFO, msg);
                validZarr = false;
            }
        } catch (Exception e) {
            logger.log(Level.INFO, "Error opening store: " + e.getMessage(), e);
            validZarr = false;
            return;
        }

        if (validZarr) {
            try {
                rootNode = new ZarrRootNode(store);
            } catch (Exception e) {
                logger.log(Level.WARNING, "Error reading Zarr metadata: " + e.getMessage(), e);
                validZarr = false;
            }
        }
    }

    public ZarrRootNode getRoot() {
        return rootNode;
    }

    public boolean isValidZarr() {
        return validZarr;
    }

    public ZarrRootNode getRootNode() {
        return rootNode;
    }

    public boolean isZipZarr() {
        if (rootNode == null) {
            return false;
        }
        return rootNode.isZip();
    }

    public boolean isTopLevelArray() {
        if (rootNode == null) {
            return false;
        }
        return rootNode.isArray();

    }

    private void populateGroupContent(ZarrNode node, DefaultMutableTreeNode jTreeNode) {
        if (node != null) {
            List<ZarrNode> children = node.getChildren();
            for (ZarrNode child : children) {
                DefaultMutableTreeNode childNode = new DefaultMutableTreeNode(child);
                jTreeNode.add(childNode);
                populateGroupContent(child, childNode);
            }
        }
    }

    /** Method to get the content of the root group and populate the provided DefaultMutableTreeNode */
    public void getGroupContent(DefaultMutableTreeNode jTreeNode) {
        if (rootNode != null) {
            long startTime = System.currentTimeMillis();
            logger.log(Level.INFO, "Getting root children for group content for file: " + f.getName());
            List<ZarrNode> rootChildren = rootNode.getChildren();
            long endTime = System.currentTimeMillis();
            long duration = endTime - startTime;
            logger.log(Level.INFO, "Time to get root children: " + duration + " ms");
            for (ZarrNode child : rootChildren) {
                DefaultMutableTreeNode childNode = new DefaultMutableTreeNode(child);
                jTreeNode.add(childNode);
                ZarrNodeType type = child.getType();
                if (type == ZarrNodeType.GROUP) {
                    populateGroupContent(child, childNode);
                }
            }
        }
    }

    public DefaultMutableTreeNode getJTreeRootNode() {
        if (rootNode == null) {
            return null;
        }
        DefaultMutableTreeNode jTreeRoot = new DefaultMutableTreeNode(rootNode);
        getGroupContent(jTreeRoot);
        return jTreeRoot;
    }

    public File getFile() {
        return f;
    }

}
