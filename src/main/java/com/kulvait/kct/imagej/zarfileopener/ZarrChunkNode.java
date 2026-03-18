/*******************************************************************************
 * Project : KCT ImageJ plugin to open Zarr files
 * Author: Vojtěch Kulvait
 * Licence: GNU GPL3
 * Description : Based on the implementation of the DEN file opener plugin
 * https://github.com/kulvait/KCT_den_file_opener.
 * Date: 2026
 ******************************************************************************/

package com.kulvait.kct.imagej.zarfileopener;

public class ZarrChunkNode extends ZarrNode {
    private long byteSize;

    public ZarrChunkNode(String[] zarrPath, ZarrNode parent, ZarrRootNode root, long byteSize) {
        super(zarrPath, parent, root, ZarrNodeType.CHUNK);
        this.byteSize = byteSize;
    }

    public long getByteSize() {
        return byteSize;
    }
}
