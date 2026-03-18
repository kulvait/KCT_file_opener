/*******************************************************************************
 * Project : KCT ImageJ plugin to open Zarr files
 * Author: Vojtěch Kulvait
 * Licence: GNU GPL3
 * Description : Based on the implementation of the DEN file opener plugin
 * https://github.com/kulvait/KCT_den_file_opener.
 * Date: 2026
 ******************************************************************************/

package com.kulvait.kct.imagej.zarfileopener;

public class ZarrAnnotationNode extends ZarrNode {

    private String annotationType;

    public ZarrAnnotationNode(String[] zarrPath, ZarrNode parent, ZarrRootNode root, String type) {
        super(zarrPath, parent, root, ZarrNodeType.ANNOTATION);
        this.annotationType = type;
    }

    public String getAnnotationType() {
        return annotationType;
    }
}
