/*******************************************************************************
 * Project : KCT ImageJ plugin to open Zarr files
 * Author: Vojtěch Kulvait
 * Licence: GNU GPL3
 * Description : Based on the implementation of the DEN file opener plugin
 * https://github.com/kulvait/KCT_den_file_opener.
 * Date: 2026
 ******************************************************************************/

package com.kulvait.kct.imagej.zarfileopener;

/**
 * Enum representing the type of a Zarr node.
 */

// Java imports
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.logging.Logger;
import java.util.logging.Level;
import java.util.stream.Stream;
import java.util.stream.Collectors;
// Java NIO imports for file handling
import java.nio.file.Path;
import java.nio.file.Files;
// Zarr Java library imports
import dev.zarr.zarrjava.ZarrException;
import dev.zarr.zarrjava.core.Array;
import dev.zarr.zarrjava.core.Group;
import dev.zarr.zarrjava.core.ArrayMetadata;
import dev.zarr.zarrjava.store.BufferedZipStore;
import dev.zarr.zarrjava.store.FilesystemStore;
import dev.zarr.zarrjava.store.ReadOnlyZipStore;
import dev.zarr.zarrjava.store.StoreHandle;
import dev.zarr.zarrjava.store.Store;
import dev.zarr.zarrjava.store.ZipStore;


public class ZarrRootNode extends ZarrNode {
    StoreHandle handle;
    private String storePath; // folder, zip, or URI
    private String storeURI; // optional, for remote
    private boolean isZip;
    private boolean isTopLevelArray;
    private boolean allKeysListCreated = false; // Flag to indicate if allKeys has been populated
    List<String[]> allKeys = null; // List of all keys in the store, to avoid repeated listing
    private boolean allKeysDirectoryCreated = false; // Flag to indicate if the directory tree has been built
    DirectoryNode directoryTree = null; // In-memory representation of the directory structure

    public ZarrRootNode(StoreHandle handle) {
        super(new String[0], null, null, ZarrNodeType.ROOT);
        System.out.println("Creating ZarrRootNode with provided StoreHandle");
        this.handle = handle;
        try {
            Path storePathIN = handle.toPath();
            this.storePath = storePathIN.toString();
            System.out.println(
                    "Creating ZarrRootNode for Zarr store in " + this.storePath);
            this.storeURI = null;
        } catch (Exception e) {
            System.out.println(
                    "Creating ZarrRootNode for Zarr store with non-path handle, using URI if available");
            this.storePath = null;
            this.storeURI = null;
        }
        if (handle.store instanceof ReadOnlyZipStore || handle.store instanceof BufferedZipStore || handle.store instanceof ZipStore) {
            this.isZip = true;
        } else {
            this.isZip = false;
        }
        this.isTopLevelArray = this.isArray(new String[0]);// Check if the root itself is an array
        this.root = this; // root points to itself
        buildDirectoryTree(); // Build the directory tree for efficient lookups
        logger.info(
                "ZarrRootNode created for store: " + getFullPath() + ", isZip: " + isZip + ", isTopLevelArray: " + isTopLevelArray);
    }

//Class for node transition
    public class DirectoryNode {
        Map<String, DirectoryNode> children = new HashMap<>();
        String name;

        public DirectoryNode(String name) {
            this.name = name;
        }

        public void addChild(String[] path) {
            if (path.length == 0) {
                return; // No more path segments to process
            }
            String currentSegment = path[0];
            DirectoryNode childNode = children.get(currentSegment);
            if (childNode == null) {
                childNode = new DirectoryNode(currentSegment);
                children.put(currentSegment, childNode);
            }
            childNode.addChild(Arrays.copyOfRange(path, 1, path.length)); // Recurse with the remaining path
        }

        public DirectoryNode getNode(String[] path) {
            if (path.length == 0) {
                return this; // Return the current node if we've reached the end of the path
            }
            String currentSegment = path[0];
            DirectoryNode childNode = children.get(currentSegment);
            if (childNode != null) {
                return childNode.getNode(Arrays.copyOfRange(path, 1, path.length)); // Recurse with the remaining path
            } else {
                return null; // Path does not exist
            }
        }

        public List<String> listChildren(String[] path) {
            DirectoryNode node = getNode(path);
            if (node != null) {
                return new ArrayList<>(node.children.keySet()); // Return the list of child names
            } else {
                return null; // Path does not exist
            }
        }

        public void collectPaths(String[] currentPath, List<String[]> result) {
            if (children.isEmpty()) {
                result.add(currentPath); // Add the current path to the result if it's a leaf node
            } else {
                for (Map.Entry<String, DirectoryNode> entry : children.entrySet()) {
                    String childName = entry.getKey();
                    DirectoryNode childNode = entry.getValue();
                    String[] newPath = Arrays.copyOf(currentPath, currentPath.length + 1);
                    newPath[newPath.length - 1] = childName; // Append the child name to the current path
                    childNode.collectPaths(newPath, result); // Recurse into the child node
                }
            }
        }

        public List<String[]> list(String[] path) {
            DirectoryNode node = getNode(path);
            if (node != null) {
                List<String[]> result = new ArrayList<>();
                node.collectPaths(path, result); // Collect all paths under the found node
                return result;
            } else {
                return null; // Path does not exist
            }
        }
    }

    private void listAllKeys() {
        if (allKeysListCreated) {
            return; // Already created, no need to list again
        }
        Store store = handle.store;
        if (store instanceof Store.ListableStore) {
            Store.ListableStore listableStore = (Store.ListableStore) store;
            try (Stream<String[]> allKeysStream = listableStore.list(zarrPath)) {
                allKeys = allKeysStream.collect(Collectors.toList());
            } catch (Exception e) {
                logger.log(Level.SEVERE, "Error listing children for path: " + getFullPath(), e);
            }
        } else {
            logger.warning(
                    "Store of type %s does not support listing children. Cannot list children for path: %s".formatted(
                            store.getClass().getName(), getFullPath()));
        }
        allKeysListCreated = true; // Mark that all keys have been listed
    }


    private void buildDirectoryTree() {
        if (allKeysDirectoryCreated) {
            return; // Already created, no need to build again
        }
        logger.info("Building directory tree for Zarr store: " + getFullPath());
        long startTime = System.currentTimeMillis();
        listAllKeys(); // Ensure all keys are listed before building the tree
        directoryTree = new DirectoryNode(null); // Root of the directory tree
        for (String[] keyPath : allKeys) {
            directoryTree.addChild(keyPath); // Add each key path to the directory tree	
        }
        long endTime = System.currentTimeMillis();
        logger.info("Directory tree built in " + (endTime - startTime) + " ms for " + allKeys.size() + " keys");
        allKeysDirectoryCreated = true; // Mark that the directory tree has been built
    }

    public List<String[]> list(String[] path) {
        buildDirectoryTree(); // Ensure the directory tree is built before listing children
        return directoryTree.list(path); // List children using the directory tree for efficient lookup
    }

    public List<String> listChildren(String[] path) {
        buildDirectoryTree(); // Ensure the directory tree is built before listing children
        return directoryTree.listChildren(path); // List children using the directory tree for efficient lookup
    }

    public boolean isZip() {
        return isZip;
    }

    public String getUri() {
        return storeURI;
    }

    public String getStorePath() {
        return storePath;
    }

    public StoreHandle getStoreHandle() {
        return handle;
    }

    public boolean isArray() {
        return isTopLevelArray;
    }

    /** Check if a path inside this handle is a Zarr array */
    public boolean isArray(String[] path) {
        try {
            Array.open(handle.resolve(path));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** Check if a path inside this handle is a Zarr group */
    public boolean isGroup(String[] path) {
        try {
            Group.open(handle.resolve(path));
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
