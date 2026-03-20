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
import dev.zarr.zarrjava.core.Node;
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
    private Array topLevelArray = null; // Cache for the top-level array if it exists
    private Group topLevelGroup = null; // Cache for the top-level group if it exists
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
        try {
            this.topLevelArray = Array.open(handle.resolve(new String[0])); // Try to open the root as an array
            this.isTopLevelArray = true; // If successful, it's a top-level array
        } catch (Exception e) {
            this.isTopLevelArray = false; // If it fails, it's not a top-level array
            try {
                this.topLevelGroup = Group.open(handle.resolve(new String[0])); // Try to open the root as a group
            } catch (Exception ex) {
                // If it also fails, it's neither an array nor a group, which is unexpected
                String errorMessage = "Root of the Zarr store is neither an array nor a group, which is unexpected. Store path: " + getFullPath();
                logger.log(Level.SEVERE, errorMessage, ex);
            }
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

    private String getFullPath(String[] path) {
        return "/" + String.join("/", path); // Join the path segments with "/" to create the full path
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


    public boolean chunkSignature(String[] key) {
        if (key.length == 0) {
            return false;
        }

        String first = key[0];

        // --- Zarr v2: "0.1.2"
        if (key.length == 1 && first.matches("\\d+(\\.\\d+)*")) {
            return true;
        }

        // --- Zarr v3: "c/0/1/2"
        if (first.equals("c")) {
            for (int i = 1; i < key.length; i++) {
                if (!key[i].matches("\\d+")) {
                    return false;
                }
            }
            return true;
        }

        return false;
    }

    public ZarrNodeType estimateNodeType(String[] path) {
        String pathStr = getFullPath(path);
        if (this.isTopLevelArray) {
            if (path.length == 0) {
                return ZarrNodeType.ARRAY; // The root itself is a top-level array
            } else {
                String msg = String.format(
                        "Path %s is not the root, but the root is a top-level array. return ZarrNodeType.NOTFOUND.",
                        pathStr);
                logger.log(Level.WARNING, msg);
                return ZarrNodeType.NOTFOUND;
            }
        } else if (path.length == 0) {
            return ZarrNodeType.GROUP; // The root itself is a group if it's not a top-level array
        }
        DirectoryNode node = directoryTree.getNode(path);
        if (node == null) {
            return ZarrNodeType.NOTFOUND; // If the path does not exist in the directory tree, it's not found
        }
        List<String> children = node.listChildren(new String[0]); // List immediate children of this node
        if (children == null || children.isEmpty()) {
            return ZarrNodeType.UNKNOWN; // If there are no children, we cannot determine if it's an array or group, so we return UNKNOWN
        }
        List<String[]> keys = node.list(new String[0]); // List all keys under this node
//remove empty keys
        keys = keys.stream().filter(k -> k.length > 0).collect(Collectors.toList());
// I will count chunk signatures to help distinguish between arrays and groups, especially for v3 where both can have zarr.json metadata.
// First filter keys which start as if they contain metadata, avoid ".zarray", ".zgroup", ".zattrs" and "zarr.json" as they are not chunk signatures
        List<String[]> keys_nostartmetadata = keys.stream().filter(k -> {
            String firstSegment = k[0];
            return !(firstSegment.equals(".zarray") || firstSegment.equals(".zgroup") || firstSegment.equals(
                    ".zattrs") || firstSegment.equals("zarr.json"));
        }).collect(Collectors.toList());

//Strip metadata leaves
        List<String[]> keys_nometadata = keys_nostartmetadata.stream().filter(k -> {
            String lastSegment = k[k.length - 1];
            return !(lastSegment.equals(".zarray") || lastSegment.equals(".zgroup") || lastSegment.equals(
                    ".zattrs") || lastSegment.equals("zarr.json"));
        }).collect(Collectors.toList());
// If the lenght of keys_nometadata is smaller than keys_nostartmetadata, it is not array and must be group
        if (keys_nometadata.size() < keys_nostartmetadata.size()) {
            if (children.contains("zarr.json") || children.contains(".zgroup")) {
                return ZarrNodeType.GROUP; // If there are keys that look like metadata but do not have "c" as the last segment, it's likely a group
            } else {
                logger.warning(
                        "Node at path " + getFullPath() + " has metadata-like keys but no 'c' chunk metadata, and does not have clear indicators of being a group. This is unexpected for a Zarr node.");
                return ZarrNodeType.UNKNOWN; // If there are no clear indicators, we cannot determine
            }
        }
// In array every key in keys_nometadata should have chunk signature
        int chunkSignatureCount = 0;
        for (String[] key : keys_nometadata) {
            if (chunkSignature(key)) {
                chunkSignatureCount++;
            }
        }
// If it is group it should not have any chunk signatures
        if (chunkSignatureCount == 0) {
            if (children.contains("zarr.json") || children.contains(".zgroup")) {
                return ZarrNodeType.GROUP; // If there are keys that look like metadata but do not have "c" as the last segment, it's likely a group
            } else {
                logger.warning(
                        "Node at path " + getFullPath() + " has no chunk suggesting group but no metadata.");
                return ZarrNodeType.UNKNOWN; // If there are no clear indicators, we cannot determine
            }
        } else if (chunkSignatureCount == keys_nometadata.size()) {
            if (children.contains("zarr.json") || children.contains(".zarray")) {
                return ZarrNodeType.ARRAY; // If all keys that look like metadata have chunk
            } else {
                logger.warning(
                        "Node at path " + getFullPath() + " has only chunk signatures, suggesting array but no metadata.");
                return ZarrNodeType.UNKNOWN; // If there are no clear indicators, we cannot determine
            }
        } else {
            logger.warning(
                    "Node at path " + getFullPath() + " has some chunk signatures indicating array but some other signatures indicating group!");
            return ZarrNodeType.UNKNOWN; // If there are no clear indicators, we cannot determine
        }
    }

    public ZarrNodeType arrayOrGroup(String[] path) {
        if (this.isTopLevelArray) {
            if (path.length == 0) {
                return ZarrNodeType.ARRAY; // The root itself is a top-level array
            } else {
                return ZarrNodeType.UNKNOWN; // Any non-root path cannot be an array if the root is a top-level array, and we cannot determine if it's a group without trying to open it
            }
        } else if (path.length == 0) {
            return ZarrNodeType.GROUP; // The root itself is a group if it's not a top-level array
        }
        try {
            Node node = this.topLevelGroup.get(path);
            if (node instanceof Array) {
                return ZarrNodeType.ARRAY; // If the node can be opened as an array, it's an array
            } else if (node instanceof Group) {
                return ZarrNodeType.GROUP; // If the node can be opened as a group, it's a group
            } else {
                return ZarrNodeType.UNKNOWN; // If it cannot be opened as either, it's unknown
            }
        } catch (Exception e) {
            return ZarrNodeType.NOTFOUND;
        }
    }

    /** Check if a path inside this handle is a Zarr array */
    public boolean isArray(String[] path) {
        if (this.isTopLevelArray) {
            if (path.length == 0) {
                return true; // The root itself is a top-level array
            } else {
                return false; // Any non-root path cannot be an array if the root is a top-level array
            }
        }
        try {
            Node node = this.topLevelGroup.get(path);
            if (node instanceof Array) {
                return true; // If the node can be opened as an array, it's an array
            }
            return false; // If it cannot be opened as an array, it's not an array
        } catch (Exception e) {
            return false; // If there is an error accessing the node, we cannot determine if it's an array, so we return false
        }
        /*
        try {
            Array.open(handle.resolve(path));
            return true;
        } catch (Exception e) {
            return false;
        }
        */
    }

    public ArrayMetadata getArrayMetadata(String[] path) {
        try {
            Array array = Array.open(handle.resolve(path));
            ArrayMetadata metadata = array.metadata();
            return metadata;
        } catch (Exception e) {
            return null;
        }
    }

    /** Check if a path inside this handle is a Zarr group */
    public boolean isGroup(String[] path) {
        if (this.isTopLevelArray) {
            return false; // If the root is a top-level array, there cannot be any groups
        }
        try {
            Node node = this.topLevelGroup.get(path);
            if (node instanceof Group) {
                return true; // If the node can be opened as a group, it's a group
            }
            return false; // If it cannot be opened as a group, it's not a group
        } catch (Exception e) {
            return false; // If there is an error accessing the node, we cannot determine if it's a group, so we return false
        }
        /*
        try {
            Group.open(handle.resolve(path));
            return true;
        } catch (Exception e) {
            return false;
        }*/
    }
}
