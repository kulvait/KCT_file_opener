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

// Zarr Java library imports
import dev.zarr.zarrjava.ZarrException;
import dev.zarr.zarrjava.core.Array;
import dev.zarr.zarrjava.core.Group;
import dev.zarr.zarrjava.core.ArrayMetadata;
import dev.zarr.zarrjava.store.BufferedZipStore;
import dev.zarr.zarrjava.store.FilesystemStore;
import dev.zarr.zarrjava.store.ReadOnlyZipStore;
import dev.zarr.zarrjava.store.StoreHandle;
import dev.zarr.zarrjava.store.ZipStore;
// Java NIO imports for file handling
import java.nio.file.Path;
import java.nio.file.Files;

public class ZarrRootNode extends ZarrNode {
    StoreHandle handle;
    private String storePath; // folder, zip, or URI
    private String storeURI; // optional, for remote
    private boolean isZip;
    private boolean isTopLevelArray;

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
