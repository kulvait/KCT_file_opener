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
import java.util.stream.Collectors;
import java.util.stream.Stream;

// Zarr Java library
import dev.zarr.zarrjava.store.StoreHandle;
import dev.zarr.zarrjava.store.Store;
import dev.zarr.zarrjava.core.ArrayMetadata;
import dev.zarr.zarrjava.core.Array;
import dev.zarr.zarrjava.core.DataType;

// Java logging
import java.util.logging.Logger;
import java.util.logging.Level;


public class ZarrArrayNode extends ZarrNode {
    private static final Logger logger = Logger.getLogger(ZarrArrayNode.class.getName());
    private long[] shape;
    private int[] chunkShape;
    private DataType dtype;
    private boolean valid = true;
    private String errorMessage;
    private Array zarrLibArray = null;
    private ArrayMetadata zarLibMetadata = null;
    private boolean areZarrLibObjectsInitialized = false;

    private void initZarrLibObjects() {
        if (areZarrLibObjectsInitialized) {
            return;
        }
        zarrLibArray = factory.getArray(zarrPath);
        if (zarrLibArray != null)
            zarLibMetadata = zarrLibArray.metadata();
        if (zarLibMetadata != null) {
            shape = zarLibMetadata.shape;
            dtype = zarLibMetadata.dataType();
            chunkShape = zarLibMetadata.chunkShape();
        }
        areZarrLibObjectsInitialized = true;
    }

    public ZarrArrayNode(String[] zarrPath, ZarrNode parent, ZarrFactory factory_in) {
        super(zarrPath, parent, factory_in, ZarrNodeType.ARRAY);
    }

    // When caller has dev.zarr.zarrjava.core.Array object ready, we can use it to initialize the ZarrArrayNode directly.
    public ZarrArrayNode(String[] zarrPath, ZarrNode parent, ZarrFactory factory_in, Array zarrLibArray_in) {
        super(zarrPath, parent, factory_in, ZarrNodeType.ARRAY);
        this.zarrLibArray = zarrLibArray_in;
        this.zarLibMetadata = zarrLibArray.metadata();
        if (zarLibMetadata != null) {
            shape = zarLibMetadata.shape;
            dtype = zarLibMetadata.dataType();
            chunkShape = zarLibMetadata.chunkShape();
        }
        areZarrLibObjectsInitialized = true;
    }

    // When dev.zarr.zarrjava.core.Array and dev.zarr.zarrjava.core.ArrayMetadata objects are ready, we can use them to initialize the ZarrArrayNode directly.	
    public ZarrArrayNode(String[] zarrPath, ZarrNode parent, ZarrFactory factory_in, Array zarrLibArray_in, ArrayMetadata zarLibMetadata_in) {
        super(zarrPath, parent, factory_in, ZarrNodeType.ARRAY);
        this.zarrLibArray = zarrLibArray_in;
        this.zarLibMetadata = zarLibMetadata_in;
        if (zarLibMetadata != null) {
            shape = zarLibMetadata.shape;
            dtype = zarLibMetadata.dataType();
            chunkShape = zarLibMetadata.chunkShape();
        }
        areZarrLibObjectsInitialized = true;
    }

    public long[] getShape() {
        initZarrLibObjects();
        return shape;
    }

    public int[] getChunkShape() {
        initZarrLibObjects();
        return chunkShape;
    }

    public DataType getDataType() {
        initZarrLibObjects();
        return dtype;
    }

    public void setValid(boolean valid, String errorMessage) {
        this.valid = valid;
        this.errorMessage = errorMessage;
    }

    public boolean isValid() {
        return valid;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public ucar.ma2.Array readArray(final long[] offset, final long[] shape) {
        initZarrLibObjects();
        try {
            if (zarrLibArray == null) {
                throw new RuntimeException("Array is not initialized");
            }
            return zarrLibArray.read(offset, shape);
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Error reading array data: " + e.getMessage(), e);
            return null;
        }
    }


//Test if name starts with c for chunks of 
    private boolean isChunkKey(String name) {
        // Zarr v2: "0.1.2"
        if (name.matches("\\d+(\\.\\d+)*")) {
            return true;
        }

        // Zarr v3: top-level "c" directory
        if (name.equals("c")) {
            return true;
        }

        return false;
    }

    @Override
    public void createZarrTree(int depth, boolean includeAnnotationNodes, boolean includeChunkNodes) {
        if (depth == 0 || isChildrenLoaded) {
            return; // Stop recursion at depth 0
        }
        if (includeChunkNodes) {
            //throw not implemented exception, because we do not want to load chunk nodes for arrays, as they are not needed to display the tree and can be very heavy to load}
            throw new UnsupportedOperationException("Loading chunk nodes for arrays is not supported, as it can be very heavy to load and is not needed to display the tree.");
        }

        if (includeAnnotationNodes || includeChunkNodes) {
            children.clear();

// We clear the children list to avoid duplicates if this method is called multiple times with different options.

            List<String> childList = listChildren();
            logger.fine(
                    "%s node: depth=%d, childCount=%d".formatted(getFullPath(), depth, childList.size()));
            for (String child : childList) {
                String[] childZarrPath = new String[zarrPath.length + 1];
                System.arraycopy(zarrPath, 0, childZarrPath, 0, zarrPath.length);
                childZarrPath[zarrPath.length] = child;
                String childPath = "/" + String.join("/", childZarrPath);
                if (child.equals(".zarray") || child.equals(".zgroup") || child.equals("zarr.json")) {
                    // We test if the name is .zarray or .zgroup or zarr.json, which are reserved names in Zarr to build AnnotationNodes
                    if (includeAnnotationNodes) {
                        System.out.printf("%s is an annotation node%n", childPath);
                        ZarrAnnotationNode annotationNode = new ZarrAnnotationNode(childZarrPath, this, factory);
                        children.add(annotationNode);
                    } else {
                        String msg = String.format("%s is an annotation node, but annotation nodes are not included%n",
                                childPath);
                        logger.fine(msg);
                    }
                } else if (isChunkKey(child)) {
                    logger.log(Level.FINE, "%s is a chunk key, but chunk nodes are not included%n".formatted(
                            childPath));
                } else {
                    logger.log(Level.WARNING, "%s is neither a group nor an array%n".formatted(childPath));
                }


            }
            isChildrenLoaded = true;
        }
    }
}
