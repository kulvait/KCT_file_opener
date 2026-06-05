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
// Logging
import java.util.logging.Logger;
import java.util.logging.Level;

import ij.ImageStack;
import ij.process.FloatProcessor;
import ij.process.ByteProcessor;
import java.awt.image.ColorModel;
import ij.process.ShortProcessor;
import ij.process.ImageProcessor;
// zarr Java imports
import ucar.ma2.DataType;
import dev.zarr.zarrjava.core.Array;

// Ideas based on
// https://github.com/tischi/imagej-open-stacks-as-virtualstack/blob/master/open_stacks_as_virtual_stack_maven/src/main/java/bigDataTools/VirtualStackOfStacks.java
// ImageJ processes just these four bit depths and corresponding types 8=byte, 16=short, 24=RGB,
// 32=float, see https://forum.image.sc/t/how-to-obtain-an-integer-image/1401

public class ZarVirtualStack extends ImageStack {
    protected final Logger logger = Logger.getLogger(ZarVirtualStack.class.getName());
    File f;
    ZarFileInfo zarInf;
    String[] path;
    ZarrArrayNode arrayNode;
    int dimx, dimy, dimz;
    int frameSize;
    DataType dtype;
    int[] chunkShape;
    long[] shape;
    long[] frameShape; // Shape of array to read into buffer
    long[] multiblockSize;// For 4D and higher dimensional arrays we need to compute size of multiblocks to compute offset

    private FloatProcessor cachedFloatProcessor = null;
    private ShortProcessor cachedShortProcessor = null;
    private ByteProcessor cachedByteProcessor = null;
    float[] pixelFloatArray;
    short[] pixelShortArray;
    byte[] pixelByteArray;
    byte[] byteBuffer;


    public ZarVirtualStack(File f, String[] path) throws IOException {
        this(new ZarFileInfo(f), path);
    }

    public ZarVirtualStack(ZarFileInfo inf, String[] path) throws IOException {
        this.f = inf.getFile();
        this.zarInf = inf;
        this.path = path;
        String zarrPath = String.join("/", path);
        boolean isValidZarr = inf.isValidZarr();
        if (!isValidZarr) {
            logger.log(Level.SEVERE, String.format("File %s is not valid Zarr!", f.getName()));
            throw new RuntimeException(String.format("File %s is not valid Zarr!", f.getName()));
        }
        ZarrFactory factory = zarInf.getFactory();
        ZarrNode root = zarInf.getRootNode();
        if (!factory.isArray(path)) {
            logger.log(Level.SEVERE, String.format("Path %s is not a Zarr array!", String.join("/", path)));
            throw new RuntimeException(String.format("Path %s is not a Zarr array!", String.join("/", path)));
        }
        logger.log(Level.INFO, String.format("Creating ZarVirtualStack for array %s:%s", f.getName(),
                zarrPath));
        arrayNode = (ZarrArrayNode) root.getDescendant(path);
        shape = arrayNode.getShape();
        chunkShape = arrayNode.getChunkShape();
        dtype = arrayNode.getDataType().getMA2DataType();
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
            } else if (i == shape.length - 2) {
// y dim
                frameShape[i] = shape[i];
            } else {
                frameShape[i] = 1;
            }
        }
        frameSize = dimx * dimy;
        if (dtype == DataType.BYTE || dtype == DataType.UBYTE) {
            pixelByteArray = new byte[frameSize];
        } else if (dtype == DataType.SHORT || dtype == DataType.USHORT) {
            pixelShortArray = new short[frameSize];
            byteBuffer = new byte[frameSize * 2];// We need byte buffer to read short data as byte array and then convert it to short array
        } else {
            pixelFloatArray = new float[frameSize];
            byteBuffer = new byte[frameSize * 4];// We need byte buffer to read float data as byte array and then convert it to float array
        }

        //typ = inf.getElementType();
        //pixelFloatArray = new float[frameSize];
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
        getPixels(n);
        if (dtype == DataType.BYTE || dtype == DataType.UBYTE) {
            if (cachedByteProcessor == null) {
                cachedByteProcessor = new ByteProcessor(dimx, dimy, pixelByteArray);
            }
            return cachedByteProcessor;
        } else if (dtype == DataType.SHORT || dtype == DataType.USHORT) {
            if (cachedShortProcessor == null) {
                ColorModel cm = null;
                cachedShortProcessor = new ShortProcessor(dimx, dimy, pixelShortArray, cm);
            }
            return cachedShortProcessor;
        } else {
            if (cachedFloatProcessor == null) {
                cachedFloatProcessor = new FloatProcessor(dimx, dimy, pixelFloatArray);
            } else {
                //cachedFloatProcessor.resetMinAndMax();
                //cachedFloatProcessor.setPixels(pixelFloatArray);
            }
            return cachedFloatProcessor;
        }

    }

    public Object getPixels(int n) {
        if (pixelFloatArray == null || pixelFloatArray.length != frameSize)
            pixelFloatArray = new float[frameSize];
        long startTime = System.currentTimeMillis();
        int frameIndex = n - 1;// ImageJ is 1 based, so we need to subtract 1
        if (frameIndex < 0 || frameIndex >= dimz) {
            throw new RuntimeException(String.format(
                    "Illegal acces to the slice %d/%d", n - 1, dimz));
        }
        if (frameShape.length == 3) {
            // For 1D, 2D and 3D arrays we can directly calculate
            if (dtype == DataType.BYTE || dtype == DataType.UBYTE) {
                arrayNode.readFrame(frameIndex, pixelByteArray);
            } else if (dtype == DataType.SHORT || dtype == DataType.USHORT) {
                arrayNode.readFrame(frameIndex, pixelShortArray);
            } else {
                arrayNode.readFrame(frameIndex, pixelFloatArray);
            }
        } else {
            // Computing multiindex is difficult so we precompute sizes of multiblocks
            long[] offset = new long[frameShape.length];
            offset[0] = frameIndex;
            long extendedIndex = frameIndex;
            for (int i = 0; i < frameShape.length - 2; i++) {
                offset[i] = extendedIndex / multiblockSize[i];
                extendedIndex = extendedIndex % multiblockSize[i];
            }
            ucar.ma2.Array slice = arrayNode.readArray(offset, frameShape);
            if (slice == null) {
                throw new RuntimeException(String.format(
                        "Can not read slice %d/%d", n - 1, dimz));
            }
            if (dtype == DataType.BYTE || dtype == DataType.UBYTE) {
                byte[] raw = (byte[]) slice.get1DJavaArray(byte.class);
                System.arraycopy(raw, 0, pixelByteArray, 0, raw.length);
            } else if (dtype == DataType.SHORT || dtype == DataType.USHORT) {
                short[] raw = (short[]) slice.get1DJavaArray(short.class);
                System.arraycopy(raw, 0, pixelShortArray, 0, raw.length);
            } else {
                float[] raw = (float[]) slice.get1DJavaArray(float.class);
                System.arraycopy(raw, 0, pixelFloatArray, 0, raw.length);
            }
        }
        // We can read directly into buffer to avoid copying, but we need to use correct byte
        long diffTime = System.currentTimeMillis() - startTime;
        logger.log(Level.INFO, String.format("Time to read slice %d/%d: %d ms", n - 1, dimz, diffTime));
        return pixelFloatArray;
    }

    /**
     * 8=byte, 16=short, 24=RGB, 32=float
     */
    public int getBitDepth() {
        if (dtype == DataType.BYTE || dtype == DataType.UBYTE) {
            return 8;
        } else if (dtype == DataType.SHORT || dtype == DataType.USHORT) {
            return 16;
        } else
            if (dtype == DataType.INT || dtype == DataType.UINT || dtype == DataType.FLOAT || dtype == DataType.DOUBLE || dtype == DataType.LONG || dtype == DataType.ULONG) {
                return 32;// We use float as internal representation, so it is always 32 bit depth
            } else {
                throw new RuntimeException(String.format("Unsupported data type %s!", dtype));
            }
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
        //return String.format("z=%d/%d", n - 1, (int) dimz);
        return String.format("z=%d/%d", n - 1);
    }

    public boolean isVirtual() {
        return true;
    }
}
