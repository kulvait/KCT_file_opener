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


public abstract class ZarrNode {
    protected String[] zarrPath;
    protected String name;
    protected ZarrNode parent;
    protected ZarrRootNode root;
    protected ZarrNodeType type;

    // NEW: List of child nodes
    protected List<ZarrNode> children = new ArrayList<>();

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

        StoreHandle storeHandle = root.getStoreHandle();
        Store store = storeHandle.store;
        Store.ListableStore listableStore;
        if (store instanceof Store.ListableStore) {
            listableStore = (Store.ListableStore) store;
        } else {
            System.err.println("Store does not support listing children: " + store.getClass().getName());
            return; // Cannot list children, so stop recursion
        }

        Stream<String> childArray = listableStore.listChildren(zarrPath);

        List<String> childList;
        Stream<String> childStream = listableStore.listChildren(zarrPath);
        childList = childStream.collect(Collectors.toList());
        System.out.println(
                "Found " + childList.size() + " children for node: " + getFullPath() + " at depth: " + depth);

        for (String child : childList) {
            System.out.println("Processing child: " + child + " of node: " + getFullPath());
            String[] childZarrPath = new String[zarrPath.length + 1];
            System.arraycopy(zarrPath, 0, childZarrPath, 0, zarrPath.length);
            childZarrPath[zarrPath.length] = child;
            String childPath = "/" + String.join("/", childZarrPath);
            if (isZarrGroup(storeHandle, childZarrPath)) {
                System.out.println("Child is a group: " + childPath);
                ZarrGroupNode groupNode = new ZarrGroupNode(childPath.split("/"), this, root);
                children.add(groupNode);
                groupNode.createZarrTree(newDepth, includeAnnotationNodes, includeChunkNodes); // Recurse into group	
            } else if (isZarrArray(storeHandle, childZarrPath)) {
                System.out.println("Child is an array: " + childPath);
                ZarrArrayNode arrayNode = new ZarrArrayNode(childPath.split("/"), this, root);
                children.add(arrayNode);
            } else {
                System.err.println("Unknown child type (not group or array): " + childPath);
            }
            // Optionally handle annotation nodes and chunk nodes here if needed
        }
    }
}
