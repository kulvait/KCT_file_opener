/*******************************************************************************
 * Project : KCT ImageJ plugin to open Zarr files
 * Author: Vojtěch Kulvait
 * Licence: GNU GPL3
 * Description : Based on the implementation of the DEN file opener plugin
 * https://github.com/kulvait/KCT_den_file_opener.
 * Date: 2026
 ******************************************************************************/

package com.kulvait.kct.imagej.zarfileopener;

import dev.zarr.zarrjava.core.Group;

import java.util.ArrayList;
import java.util.List;

public class ZarrGroupNode extends ZarrNode {
    private Group zarrLibGroup = null;


    public ZarrGroupNode(String[] zarrPath, ZarrNode parent, ZarrFactory factory) {
        super(zarrPath, parent, factory, ZarrNodeType.GROUP);
    }

    public ZarrGroupNode(String[] zarrPath, ZarrNode parent, ZarrFactory factory, Group zarrLibGroup_in) {
        super(zarrPath, parent, factory, ZarrNodeType.GROUP);
        this.zarrLibGroup = zarrLibGroup_in;
    }
}
