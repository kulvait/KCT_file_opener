/*******************************************************************************
 * Project : KCT ImageJ plugin to open DEN files
 * Author: Vojtěch Kulvait
 * Licence: GNU GPL3
 * Description : Class to get information about legacy or extended DEN
 * Date: 2023
 ******************************************************************************/

package com.kulvait.kct.imagej.denfileopener;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.Arrays;
// Logging imports
import java.util.logging.Level;
import java.util.logging.Logger;

public class DenFileInfo {
    // The DEX format is inspired by DEN
    // It is format to store n-dimensional arrays
    // In 2-byte headers is encoded the following HEADER1(YMAJOR,TYPE)
    // HEADER2(DIM,ELMLEN)

    private static final Logger logger = Logger.getLogger(DenFileInfo.class.getName());

    final int DIM_EXTHEADER1_MASK = 0xf;
    final long ELMLEN_EXTHEADER1_MASK = 0xffff0000L;
    final int FILETYPE_EXTHEADER2_MASK = 0xff; // For extended DEN
    final short UINT16_EXTHEADER2 = 0;
    final short INT16_EXTHEADER2 = 1;
    final short UINT32_EXTHEADER2 = 2;
    final short INT32_EXTHEADER2 = 3;
    final short UINT64_EXTHEADER2 = 4;
    final short INT64_EXTHEADER2 = 5;
    final short FLOAT32_EXTHEADER2 = 6;
    final short FLOAT64_EXTHEADER2 = 7;
    final int YMAJOR_EXTHEADER2_MASK = 0x100;

    File f;
    private final boolean verbose;
    long byteSize;
    long elementCount;
    long elementSize;
    DenDataType elementType;
    boolean validDEN;
    boolean extendedDEN;
    long dataByteOffset;
    int header0, header1, header2;
    int DIMCOUNT;
    long[] dim;
    long dimx, dimy, dimz;
    boolean xmajor;

    public DenFileInfo(File f) {
        this(f, false);
    }

    public DenFileInfo(File f, boolean verbose) {
        this.verbose = verbose;
        this.f = f;
        byteSize = f.length();
        dim = new long[16];
        Arrays.fill(dim, 1);
        if (byteSize < 6) {
            if (verbose) {
                logger.log(Level.INFO,
                        "File {0} is not DEN: file has {1} bytes, which is less than 6!",
                        new Object[]{f.getName(), byteSize});
            }
            validDEN = false;
            return;
        }
        try (RandomAccessFile df = new RandomAccessFile(f, "r"); FileChannel inChannel = df.getChannel()) {
            MappedByteBuffer buffer = inChannel.map(
                    FileChannel.MapMode.READ_ONLY, 0, Math.min(1024, inChannel.size()));
            buffer.order(ByteOrder.LITTLE_ENDIAN);
            buffer.load();
            header0 = buffer.getShort() & 0xffff;
            header1 = buffer.getShort() & 0xffff;
            header2 = buffer.getShort() & 0xffff;
            if (header0 == 0) {
                if (byteSize < 4096) {
                    if (verbose) {
                        logger.log(Level.INFO,
                                "File {0} is not DEN: file has {1} bytes, which is less than 4096 for extended DEN!",
                                new Object[]{f.getName(), byteSize});
                    }
                    validDEN = false;
                    return;
                }
                extendedDEN = true;
                int header3, header4;
                header3 = buffer.getShort() & 0xffff;
                header4 = buffer.getShort() & 0xffff;
                DIMCOUNT = header1;
                // Validate DIMCOUNT to avoid ArrayIndexOutOfBoundsException
                if (DIMCOUNT < 0 || DIMCOUNT > 16) {
                    if (verbose) {
                        logger.log(Level.INFO,
                                "File {0} is not DEN: DIMCOUNT {1} is out of valid range (0-16) for extended DEN!",
                                new Object[]{f.getName(), DIMCOUNT});
                    }
                    validDEN = false;
                    return;
                }
                elementSize = header2;
                xmajor = (header3 == 0);
                if (header4 < 0 || header4 >= DenDataType.values().length) {
                    if (verbose) {
                        logger.log(Level.INFO,
                                "File {0} is not DEN: element type index {1} is out of valid range for extended DEN!",
                                new Object[]{f.getName(), header4});
                    }
                    validDEN = false;
                    return;
                }
                elementType = DenDataType.values()[header4];
                dataByteOffset = 4096;
                for (int i = 0; i != DIMCOUNT; i++) {
                    dim[i] = (buffer.getInt() & 0xffffffffL);
                }
            } else {
                extendedDEN = false;
                dataByteOffset = 6;
                DIMCOUNT = 3;
                dim[0] = header1;
                dim[1] = header0;
                dim[2] = header2;
                xmajor = true;
            }
            // This is to convert uint16 representation to int as Java does not have
            // signed types
            // int is able to represent uint16s > 32768 as positive numbers, short not
            // It is working due to two's complement representation, see
            // https://en.wikipedia.org/wiki/Two%27s_complement
        } catch (IOException e) {
            logger.log(Level.SEVERE,
                    "Error reading header of file: " + f.getName() + ". Assuming it is not a valid DEN file.",
                    e);
            validDEN = false;
            return;
        }
        if (extendedDEN) {
            if (DIMCOUNT == 0) {
                dimx = 0;
                dimy = 0;
                dimz = 0;
            } else if (DIMCOUNT == 1) {
                dimx = dim[0]; // Height
                dimy = 1;
                dimz = 1;
            } else if (DIMCOUNT == 2) {
                dimx = dim[0]; // Height
                dimy = dim[1]; // Width
                dimz = 1;
            } else {
                dimx = dim[0]; // Height
                dimy = dim[1]; // Width
                dimz = dim[2];
                // Flat indexing for more than 3D arrays
                for (int i = 3; i < DIMCOUNT; i++) {
                    dimz = Math.multiplyExact(dimz, dim[i]); // Use multiplyExact to catch overflow
                }
            }
        } else {
            dimx = header1;
            dimy = header0;
            dimz = header2;
        }
        elementCount = Math.multiplyExact(dimx, Math.multiplyExact(dimy, dimz)); // Use multiplyExact to catch overflow
        if (elementCount == 0 && byteSize - dataByteOffset == 0) {
            if (verbose) {
                logger.log(Level.INFO,
                        "{0} is valid DEN: empty file with zero elements and zero payload size",
                        f.getName());
            }
            validDEN = true;
            return;
        }
        if (elementCount == 0) {
            if (verbose) {
                logger.log(Level.INFO,
                        "{0} is not DEN: zero elements but non-zero payload size {1} bytes",
                        new Object[]{f.getName(), byteSize - dataByteOffset});
            }
            validDEN = false;
            return;
        }

        if (!extendedDEN) {
            if ((byteSize - dataByteOffset) % elementCount != 0) {
                if (verbose) {
                    logger.log(Level.INFO,
                            "{0} is not legacy DEN: payload size {1} is not divisible by element count {2}",
                            new Object[]{f.getName(), byteSize - dataByteOffset, elementCount});
                }
                validDEN = false;
                return;
            }
            elementSize = (int) ((byteSize - dataByteOffset) / elementCount);
            if (elementSize == 2) {
                elementType = DenDataType.UINT16;
            } else if (elementSize == 4) {
                elementType = DenDataType.FLOAT32;
            } else if (elementSize == 8) {
                elementType = DenDataType.FLOAT64;
            } else {
                if (verbose) {
                    logger.log(Level.INFO,
                            "{0} is not legacy DEN: unsupported element size {1} bytes",
                            new Object[]{f.getName(), elementSize});
                }
                validDEN = false;
                return;
            }
        }
        elementSize = elementType.getSize();
        long expectedByteSize = dataByteOffset + Math.multiplyExact(elementSize, elementCount); // Use multiplyExact to catch overflow
        if (expectedByteSize == byteSize) {
            validDEN = true;
            if (verbose) {
                logger.log(Level.INFO,
                        "{0} is valid {1} DEN: {2} elements of size {3} bytes, total payload size {4} bytes, offset {5} bytes, xmajor: {6}, dimensions: {7}",
                        new Object[]{f.getName(), extendedDEN ? "extended" : "legacy", elementCount, elementSize, byteSize - dataByteOffset, dataByteOffset, xmajor, Arrays.toString(
                                Arrays.copyOf(dim, DIMCOUNT))});
            }
        } else {
            if (verbose) {
                logger.log(Level.INFO,
                        "{0} is not DEN: expected {1} bytes (offset {2} + {3} elements x {4} bytes), found {5}",
                        new Object[]{f.getName(), dataByteOffset + elementSize * elementCount, dataByteOffset, elementCount, elementSize, byteSize});
            }
            validDEN = false;
        }
    }

    public int getDIMCOUNT() {
        return DIMCOUNT;
    }

    public long getByteSize() {
        return byteSize;
    }

    public long getElementCount() {
        return elementCount;
    }

    public long getElementSize() {
        return elementSize;
    }

    public DenDataType getElementType() {
        return elementType;
    }

    public boolean isValidDEN() {
        return validDEN;
    }

    public boolean isExtendedDEN() {
        return extendedDEN;
    }

    public long getDataByteOffset() {
        return dataByteOffset;
    }

    public long getDim(int i) {
        return dim[i];
    }

    public long getDimx() {
        return dimx;
    }

    public long getDimy() {
        return dimy;
    }

    public long getDimz() {
        return dimz;
    }

    public boolean isXmajor() {
        return xmajor;
    }
}
