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
 * Class representing a chunk node in the Zarr file structure.
 * This class will store information about chunks related to a given array, such as the byte size of the chunk.
 * zarrPath thus corresponds to the path to the array to which the chunk belongs, and not to the chunk itself.
 */
public class ZarrChunkNode extends ZarrNode {
    private long byteSize = -1;

    public ZarrChunkNode(String[] zarrPath, ZarrNode parent, ZarrFactory factory) {
        super(zarrPath, parent, factory, ZarrNodeType.CHUNK);
    }

    public ZarrChunkNode(String[] zarrPath, ZarrNode parent, ZarrFactory factory, long byteSize_in) {
        super(zarrPath, parent, factory, ZarrNodeType.CHUNK);
        this.byteSize = byteSize_in;
    }

    public long getByteSize() {
        return byteSize;
    }
}
