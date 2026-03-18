/*******************************************************************************
 * Project : KCT ImageJ plugin to open Zarr files
 * Author: Vojtěch Kulvait
 * Licence: GNU GPL3
 * Description : Based on the implementation of the DEN file opener plugin
 * https://github.com/kulvait/KCT_den_file_opener.
 * Date: 2026
 ******************************************************************************/

package com.kulvait.kct.imagej.zarfileopener;

public class ZarrArrayNode extends ZarrNode {
    private long[] shape;
    private String dtype;
    private boolean valid = true;
    private String errorMessage;

    public ZarrArrayNode(String[] zarrPath, ZarrNode parent, ZarrRootNode root) {
        super(zarrPath, parent, root, ZarrNodeType.ARRAY);
    }

    public void setShape(long[] shape) {
        this.shape = shape;
    }

    public void setDtype(String dtype) {
        this.dtype = dtype;
    }

    public long[] getShape() {
        return shape;
    }

    public String getDtype() {
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
}
