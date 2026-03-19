/*******************************************************************************
 * Project : KCT ImageJ plugin to open Zarr files
 * Author: Vojtěch Kulvait
 * Licence: GNU GPL3
 * Description : Based on the implementation of the DEN file opener plugin
 * https://github.com/kulvait/KCT_den_file_opener.
 * Date: 2026
 ******************************************************************************/

package com.kulvait.kct.imagej.zarfileopener;

import java.util.ArrayList;
import java.util.List;

public class ZarrGroupNode extends ZarrNode {
    public ZarrGroupNode(String[] zarrPath, ZarrNode parent, ZarrRootNode root) {
        super(zarrPath, parent, root, ZarrNodeType.GROUP);
    }
}
