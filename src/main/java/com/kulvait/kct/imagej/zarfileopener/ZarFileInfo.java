/*******************************************************************************
 * Project : KCT ImageJ plugin to open DEN files
 * Author: Vojtěch Kulvait
 * Licence: GNU GPL3
 * Description : Class to get information about legacy or extended DEN
 * Date: 2026
 ******************************************************************************/

package com.kulvait.kct.imagej.zarfileopener;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.Buffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.Arrays;
// Java NIO imports for file handling
import java.nio.file.Path;
import java.nio.file.Files;
// Zarr Java library imports
import dev.zarr.zarrjava.core.Array;
import dev.zarr.zarrjava.core.ArrayMetadata;
import dev.zarr.zarrjava.store.ReadOnlyZipStore;
import dev.zarr.zarrjava.store.FilesystemStore;
import dev.zarr.zarrjava.store.StoreHandle;
import dev.zarr.zarrjava.ZarrException;


public class ZarFileInfo {
    private File f;
    private boolean validZarr = true;
    private ZarrRootNode rootNode = null;

    private boolean checkIfZip(Path path) {
        try (FileChannel fileChannel = FileChannel.open(path)) {
            MappedByteBuffer buffer = fileChannel.map(
                    FileChannel.MapMode.READ_ONLY, 0, Math.min(4, fileChannel.size()));
            return buffer.getInt() == 0x504b0304; // ZIP file signature
        } catch (IOException e) {
            return false;
        }
    }

    ZarFileInfo(File f) {
        this.f = f;
        Path path = f.toPath();

        // Check if it's a file or folder
        if (!Files.exists(path)) {
            System.out.println("File does not exist: " + path);
            validZarr = false;
            return;
        }
        boolean isDirectory = Files.isDirectory(path);
        boolean isFile = Files.isRegularFile(path);
        boolean isZip = false;
        if (isFile) {
            isZip = checkIfZip(path);
        }
        StoreHandle store;
        if (isZip) {
            System.out.println("Opening ZIP store: " + path);
            store = new ReadOnlyZipStore(path).resolve(); // root handle
        } else if (isDirectory) {
            System.out.println("Opening folder store: " + path);
            store = new StoreHandle(new FilesystemStore(path));
        } else if (isFile) {
            System.out.println(
                    "File %s is not a ZIP file nor directory, not a Zarr container%n");
            validZarr = false;
            return;
        }

    }

    public boolean isValidZarr() {
        return validZarr;
    }

}
