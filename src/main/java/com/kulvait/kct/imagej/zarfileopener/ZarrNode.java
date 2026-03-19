/*******************************************************************************
 * Project : KCT ImageJ plugin to open Zarr files
 * Author: Vojtěch Kulvait
 * Licence: GNU GPL3
 * Description : Based on the implementation of the DEN file opener plugin
 * https://github.com/kulvait/KCT_den_file_opener.
 * Date: 2026
 ******************************************************************************/

package com.kulvait.kct.imagej.zarfileopener;

import java.util.List;
import java.util.ArrayList;
import java.util.stream.Stream;
import java.util.stream.Collectors;
import java.io.IOException;
// Zarr Java library imports
import dev.zarr.zarrjava.core.Array;
import dev.zarr.zarrjava.core.Group;
import dev.zarr.zarrjava.core.ArrayMetadata;
import dev.zarr.zarrjava.store.ReadOnlyZipStore;
import dev.zarr.zarrjava.store.FilesystemStore;
import dev.zarr.zarrjava.store.StoreHandle;
import dev.zarr.zarrjava.store.Store;
import dev.zarr.zarrjava.ZarrException;
// Java logging
import java.util.logging.Logger;
import java.util.logging.Level;


public abstract class ZarrNode {
    private static final Logger logger = Logger.getLogger(ZarrNode.class.getName());
    protected String[] zarrPath;
    protected String name;
    protected ZarrNode parent;
    protected ZarrRootNode root;
    protected ZarrNodeType type;

    // NEW: List of child nodes
    protected List<ZarrNode> children = new ArrayList<>();
    protected boolean isChildrenLoaded = false; // Flag to track if children have been loaded

    public ZarrNode(String[] zarrPath, ZarrNode parent, ZarrRootNode root, ZarrNodeType type) {
        this.zarrPath = zarrPath;
        this.parent = parent;
        this.root = root;
        this.type = type;
        this.name = zarrPath.length > 0 ? zarrPath[zarrPath.length - 1] : "/";
    }

    public String getName() {
        return name;
    }

    public ZarrNode getParent() {
        return parent;
    }

    public ZarrRootNode getRoot() {
        return root;
    }

    public ZarrNodeType getType() {
        return type;
    }

    public String getFullPath() {
        return "/" + String.join("/", zarrPath);
    }

    public String[] getZarrPath() {
        return zarrPath;
    }

    public List<ZarrNode> getChildren() {
        if (!isChildrenLoaded) {
            createZarrTree(-1, false, false); // Load children if not already loaded
        }
        return children;
    }

    // Static utility method
    public static boolean isZarrArray(StoreHandle store, String[] path) {
//Strip leading slash if present, as Zarr Java library expects relative paths
        try {
            Array.open(store.resolve(path));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean isZarrGroup(StoreHandle store, String[] path) {
        try {
            Group.open(store.resolve(path));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public List<String> listChildren() {
        StoreHandle storeHandle = root.getStoreHandle();
        Store store = storeHandle.store;
        if (store instanceof Store.ListableStore) {
            Store.ListableStore listableStore = (Store.ListableStore) store;
            try (Stream<String> childStream = listableStore.listChildren(zarrPath)) {
                return childStream.collect(Collectors.toList());
            } catch (Exception e) {
                logger.log(Level.SEVERE, "Error listing children for path: " + getFullPath(), e);
                return List.of(); // Return empty list on error
            }
        } else {
            logger.warning(
                    "Store of type %s does not support listing children. Cannot list children for path: %s".formatted(
                            store.getClass().getName(), getFullPath()));
            return List.of(); // Return empty list if store does not support listing
        }
    }

    public boolean isAnnotationPath(String[] path, int groupOrArrayIndex) {
        if (path.length == 0) {
            return false; // Root node cannot be an annotation node
        }
        if (groupOrArrayIndex != path.length - 2) {
            return false; // Invalid index
        }
        String name = path[groupOrArrayIndex + 1];
        if (name.equals(".zarray") || name.equals(".zgroup") || name.equals("zarr.json")) {
            return true; // These are reserved annotation node names in Zarr
        }
        return false; // Not an annotation node
    }

    public boolean isChunkPath(String[] path, int arrayIndex) {

        if (path == null || path.length == 0) {
            return false;
        }

        // arrayIndex must point to array position
        if (arrayIndex < -1 || arrayIndex >= path.length - 1) {
            return false;
        }

        int startIndex = arrayIndex + 1;
        String first = path[startIndex];

        // --- Zarr v2: "0.1.2"
        if (first.matches("\\d+(\\.\\d+)*")) {
            return true;
        }

        // --- Zarr v3: "c/0/1/2"
        if (first.equals("c")) {
            for (int i = startIndex + 1; i < path.length; i++) {
                if (!path[i].matches("\\d+")) {
                    return false;
                }
            }
            return true;
        }
        return false;
    }

    private boolean canHaveChunks(ZarrNodeType nodeType) {
        return nodeType == ZarrNodeType.ARRAY;
    }

    private boolean canHaveAnnotation(ZarrNodeType nodeType) {
        return nodeType == ZarrNodeType.GROUP || nodeType == ZarrNodeType.ARRAY;
    }


    // ===== Abstract tree-building method =====
    /**
     * Recursively build a tree of ZarrNodes.
     * 
     * @param depth                  Max depth (-1 = unlimited)
     * @param includeAnnotationNodes Whether to include annotation nodes
     * @param includeChunkNodes      Whether to include individual chunks
     */
    public void createZarrTree(int depth, boolean includeAnnotationNodes, boolean includeChunkNodes) {
        if (depth == 0) {
            return; // Stop recursion at depth 0
        }
        children.clear(); // Clear existing children before building the tree
        int newDepth = depth == -1 ? -1 : depth - 1; // Decrease depth for child nodes
        List<String> childList = listChildren();
        logger.fine(
                "%s node: depth=%d, childCount=%d".formatted(getFullPath(), depth, childList.size()));

        for (String child : childList) {
            String[] childZarrPath = new String[zarrPath.length + 1];
            System.arraycopy(zarrPath, 0, childZarrPath, 0, zarrPath.length);
            childZarrPath[zarrPath.length] = child;
            String childPath = "/" + String.join("/", childZarrPath);
            if (root.isGroup(childZarrPath)) {
                ZarrGroupNode groupNode = new ZarrGroupNode(childZarrPath, this, root);
                children.add(groupNode);
                groupNode.createZarrTree(newDepth, includeAnnotationNodes, includeChunkNodes); // Recurse into group	
            } else if (root.isArray(childZarrPath)) {
                ZarrArrayNode arrayNode = new ZarrArrayNode(childZarrPath, this, root);
                children.add(arrayNode);
            } else if (canHaveAnnotation(type) && isAnnotationPath(
                    childZarrPath, zarrPath.length - 1)) {
                        // We test if the path corresponds to an annotation node, which are named like ".zarray", ".zgroup" or "zarr.json"
                        if (includeAnnotationNodes) {
                            System.out.printf("%s is an annotation node%n", childPath);
                            ZarrAnnotationNode annotationNode = new ZarrAnnotationNode(childZarrPath, this, root);
                            children.add(annotationNode);
                        } else {
                            String msg = String.format(
                                    "%s is an annotation node, but annotation nodes are not included%n",
                                    childPath);
                            logger.fine(msg);
                        }
                    } else if (canHaveChunks(type) && isChunkPath(childZarrPath, zarrPath.length - 1)) {
                        // We test if the path corresponds to a chunk of an array, which are named like "0.1.2" or "c/0/1/2"
                        if (includeChunkNodes) {
                            System.out.printf("%s is a chunk node%n", childPath);
                            ZarrChunkNode chunkNode = new ZarrChunkNode(childZarrPath, this, root);
                            children.add(chunkNode);
                        } else {
                            String msg = String.format("%s is a chunk node, but chunk nodes are not included%n",
                                    childPath);
                            logger.fine(msg);
                        }
                    } else {
                        System.out.printf("%s is neither a group nor an array%n", childPath);
                    }
            // Optionally handle annotation nodes and chunk nodes here if needed
        }
        isChildrenLoaded = true; // Mark children as loaded after processing
    }
}
