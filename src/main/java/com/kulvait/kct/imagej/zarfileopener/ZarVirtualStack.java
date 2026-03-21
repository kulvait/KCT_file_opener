/*******************************************************************************
 * Project : KCT ImageJ plugin to open in house den format
 * Author: Vojtěch Kulvait
 * Licence: GNU GPL3
 * Description : Implementation of memory mapped views to DEN files
 * So called virtual stack is created.
 * Memory representation is always float independent of type.
 * Date: 2026
 ******************************************************************************/

package com.kulvait.kct.imagej.zarfileopener;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;

import ij.ImageStack;
import ij.process.FloatProcessor;
import ij.process.ImageProcessor;
// zarr Java imports
import dev.zarr.zarrjava.core.DataType;
import dev.zarr.zarrjava.core.Array;

// Ideas based on
// https://github.com/tischi/imagej-open-stacks-as-virtualstack/blob/master/open_stacks_as_virtual_stack_maven/src/main/java/bigDataTools/VirtualStackOfStacks.java
// ImageJ processes just these four bit depths and corresponding types 8=byte, 16=short, 24=RGB,
// 32=float, see https://forum.image.sc/t/how-to-obtain-an-integer-image/1401

public class ZarVirtualStack extends ImageStack {

    File f;
    ZarFileInfo zarInf;
    String[] path;
    ZarrArrayNode arrayNode;
    int dimx, dimy, dimz;
    int dimImg;
    DataType dtype;
    int[] chunkShape;
    long[] shape;
    long[] frameShape;
    long[] multiblockSize;// For 4D and higher dimensional arrays we need to compute size of multiblocks to compute offset
    float[] pixelArray;


    public ZarVirtualStack(File f, String[] path) throws IOException {
        this(new ZarFileInfo(f), path);
    }

    public ZarVirtualStack(ZarFileInfo inf, String[] path) throws IOException {
        this.f = inf.getFile();
        this.zarInf = inf;
        this.path = path;
        if (!inf.isValidZarr()) {
            throw new RuntimeException(String.format("File %s is not valid Zarr!", f.getName()));
        }
        ZarrRootNode root = zarInf.getRootNode();
        if (!root.isArray(path)) {
            throw new RuntimeException(String.format("Path %s is not a Zarr array!", String.join("/", path)));
        }
        String msg = String.format("Opening Zarr array %s/%s", f.getName(), String.join("/", path));
        arrayNode = (ZarrArrayNode) root.getDescendant(path);
        shape = arrayNode.getShape();
        chunkShape = arrayNode.getChunkShape();
        dtype = arrayNode.getDataType();
// Be aware data alignment in numpy notation z,y,x and chunk shape z,y,x
        multiblockSize = new long[shape.length];
        if (shape.length == 1) {
            dimx = (int) shape[0];
            dimy = 1;
            dimz = 1;
        } else if (shape.length == 2) {
            dimx = (int) shape[1];
            dimy = (int) shape[0];
            dimz = 1;
        } else if (shape.length == 3) {
            dimx = (int) shape[2];
            dimy = (int) shape[1];
            dimz = (int) shape[0];
        } else {
//Here I can make z as multiindex 
            dimx = (int) shape[shape.length - 1];
            dimy = (int) shape[shape.length - 2];
            dimz = (int) shape[shape.length - 3];
            multiblockSize[shape.length - 1] = 1;
            multiblockSize[shape.length - 2] = 1;
            multiblockSize[shape.length - 3] = 1;// We calculate multiblock size as product of excess dimensions
            for (int i = shape.length - 4; i >= 0; i--) {
                multiblockSize[i] = dimz;
                dimz *= (int) shape[i];
            }
        }
//Frame shape is used to retrieve correct block
        frameShape = new long[shape.length];
        for (int i = 0; i < shape.length; i++) {
            if (i == shape.length - 1) {
// x dim
                frameShape[i] = shape[i];
            }
            if (i == shape.length - 2) {
// y dim
                frameShape[i] = shape[i];
            } else {
                frameShape[i] = 1;
            }
        }
        dimImg = dimx * dimy;
        pixelArray = new float[dimImg];

        // Supports fast access, if from undefined dimension these are ones
        //dimx = (int) inf.getDimx();
        //dimy = (int) inf.getDimy();
        //dimz = (int) inf.getDimz();
        //dimImg = dimx * dimy;
        //typ = inf.getElementType();
        //pixelArray = new float[dimImg];
    }

    /**
     * The following methods are intentionally overriden to do nothing as
     * VirtualStack do not support such functionality
     */
    public void addSlice(String sliceLabel, Object pixels) {
    }

    public void addSlice(String sliceLabel, ImageProcessor ip) {
    }

    public void addSlice(String sliceLabel, ImageProcessor ip, int n) {
    }

    public void deleteSlice(int n) {
    }

    public void deleteLastSlice() {
    }

    public void setPixels(Object pixels, int n) {
    }

    public void setSliceLabel(String label, int n) {
    }

    public void trim() {
    }

    public Object[] getImageArray() {
        return null;
    }

    // Theoretically I can derive it from ImagePlus with given offset
    // 1 based n
    public ImageProcessor getProcessor(int n) {
        FloatProcessor fp = new FloatProcessor(dimx, dimy);
        getPixels(n);
        int index;
        for (int y = 0; y != dimy; y++) {
            for (int x = 0; x != dimx; x++) {
                index = y * dimx + x;
                fp.setf(x, y, pixelArray[index]);
            }
        }
        return fp;
    }

    public Object getPixels(int n) {
        if (n > dimz) {
            throw new RuntimeException(String.format(
                    "Illegal acces to the slice %d/%d", n - 1, dimz));
        }
        long[] offset = new long[frameShape.length];
        if (frameShape.length <= 3) {
            // For 1D, 2D and 3D arrays we can directly calculate
            offset[0] = n;//z dim is the first dimension in numpy notation, so it is the last dimension in ImageJ notation
        } else {
            // Computing multiindex is difficult so we precompute sizes of multiblocks
            long frameIndex = n;
            for (int i = 0; i < frameShape.length - 2; i++) {
                offset[i] = frameIndex / multiblockSize[i];
                frameIndex = frameIndex % multiblockSize[i];
            }
        }
        ucar.ma2.Array slice = arrayNode.readArray(offset, frameShape);

        for (int i = 0; i < pixelArray.length; i++) {
            pixelArray[i] = slice.getFloat(i);
        }
        if (pixelArray != null) {
            return pixelArray;
        } else {
            throw new RuntimeException(String.format(
                    "Can not map buffer of the slice %d/%d", n - 1, dimz));
        }
    }

    /**
     * 8=byte, 16=short, 24=RGB, 32=float
     */
    public int getBitDepth() {
        return 32;// We use float as internal representation, so it is always 32 bit depth
    }

    public int getSize() {
        return (int) dimz;
    }

    public int getFrameCount() {
        return (int) dimz;
    }

    public int getWidth() {
        return (int) dimx;
    }

    public int getHeight() {
        return (int) dimy;
    }

    public String getSliceLabel(int n) {
        return String.format("z=%d", n - 1, (int) dimz);
    }

    public boolean isVirtual() {
        return true;
    }
}
