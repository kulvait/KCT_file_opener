/*******************************************************************************
 * Project : KCT ImageJ plugin to open Zarr files
 * Author: Vojtěch Kulvait
 * Licence: GNU GPL3
 * Description : Based on the implementation of the DEN file opener plugin
 * https://github.com/kulvait/KCT_den_file_opener.
 * Date: 2026
 ******************************************************************************/

package com.kulvait.kct.imagej.zarfileopener;

import ij.IJ;
import ij.ImagePlus;
import ij.Prefs;
import ij.io.FileInfo;
import ij.io.FileOpener;
import ij.io.OpenDialog;
import ij.plugin.PlugIn;
import java.awt.EventQueue;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import javax.swing.JFileChooser;
// Java NIO imports for file handling
import java.nio.file.Path;
import java.nio.file.Files;
import java.io.InputStream;
import java.util.Arrays;
import java.util.List;
import java.util.ArrayList;
// Java logging imports
import com.kulvait.logging.LineNumberFormatter;
import java.util.logging.Logger;
import java.util.logging.Level;
import java.util.logging.Handler;
import java.util.logging.ConsoleHandler;
// Zarr Java library imports
import dev.zarr.zarrjava.core.Array;
import dev.zarr.zarrjava.core.ArrayMetadata;
import dev.zarr.zarrjava.store.ReadOnlyZipStore;
import dev.zarr.zarrjava.store.FilesystemStore;
import dev.zarr.zarrjava.store.StoreHandle;
import dev.zarr.zarrjava.ZarrException;


/**
 * Uses the JFileChooser from Swing to open one or more raw images. The "Open
 * All Files in Folder" check box in the dialog is ignored.
 */
public class ZarFileOpener implements PlugIn {
    private static final Logger logger = Logger.getLogger(ZarFileOpener.class.getName());

    static private String directory;
    private File file;
    private ZarOpenerAccessory zarAccessory;

    public void run(String arg) {

        Logger rootLogger = Logger.getLogger("com.kulvait.kct.imagej.zarfileopener");
        rootLogger.setLevel(Level.ALL);
        rootLogger.setUseParentHandlers(false);
        // Remove default handlers if needed
        Handler[] handlers = rootLogger.getHandlers();
        for (Handler h : handlers) {
            rootLogger.removeHandler(h);
        }

        // Add console handler that prints all levels
        ConsoleHandler consoleHandler = new ConsoleHandler();
        consoleHandler.setLevel(Level.FINE); // allow debug messages
        LineNumberFormatter formatter = new LineNumberFormatter();
        consoleHandler.setFormatter(formatter);
        rootLogger.addHandler(consoleHandler);

        logger.info("Logger initialized at FINE level");
        try {
            boolean useVirtualStack;
            if (arg.equals("")) {
                if (openFilesDialog() == false) {
                    return;
                }
                useVirtualStack = zarAccessory.isBoxSelected();
            } else {
                file = new File(arg);
                useVirtualStack = true;
            }
            openZar(useVirtualStack);
        } catch (IOException e) {
            System.out.printf("%s ERROR", e.toString());
        }
    }

    public boolean openFilesDialog() {
        try {
            EventQueue.invokeAndWait(new Runnable() {

                public void run() {
                    JFileChooser fc = new JFileChooser();
                    fc.setDialogTitle("Open Zarr file...");
                    zarAccessory = new ZarOpenerAccessory(fc);
                    fc.setAccessory(zarAccessory);
                    fc.setMultiSelectionEnabled(false);
                    if (directory == null) {
                        directory = Prefs.getString(".options.denlastdir");
                    }
                    if (directory == null) {
                        directory = OpenDialog.getLastDirectory();
                    }
                    if (directory == null) {
                        directory = OpenDialog.getDefaultDirectory();
                    }
                    if (directory != null) {
                        fc.setCurrentDirectory(new File(directory));
                        System.out.println(String.format("Directory is %s", directory));
                    } else {
                        System.out.println("Directory is null");
                    }
                    int returnVal = fc.showOpenDialog(IJ.getInstance());
                    if (returnVal != JFileChooser.APPROVE_OPTION) {
                        return;
                    }
                    file = fc.getSelectedFile();
                    //zarAccessory = (CheckBoxAccessory) fc.getAccessory();
                    directory = fc.getCurrentDirectory().getPath() + File.separator;
                }
            });
        } catch (InterruptedException e) {
            System.out.printf("%s ERROR", e.toString());
        } catch (InvocationTargetException e) {
            System.out.printf("%s ERROR", e.toString());
        }
        if (zarAccessory == null || file == null) {
            file = null;
            return false;
        }
        return true;
    }

    private boolean isZipFile(Path path) {
        if (!Files.isRegularFile(path)) {
            return false;
        }
        try (InputStream in = Files.newInputStream(path)) {
            byte[] signature = new byte[4];
            if (in.read(signature) == 4) {
                // ZIP files start with "PK\003\004"
                return signature[0] == 'P' && signature[1] == 'K';
            }
        } catch (IOException e) {
            System.out.println("Cannot read file signature: " + e.getMessage());
        }
        return false;
    }
    /*
    private boolean isZarrStore(Path path) {
      // Basic heuristic: check for .zarray or .zgroup metadata files
      if (Files.isDirectory(path)) {
        return Files.exists(path.resolve(".zarray")) || Files.exists(path.resolve(".zgroup"));
      } else if (isZipFile(path)) {
        try {
            ReadOnlyZipStore zipStore = new ReadOnlyZipStore(path);
            return zipStore.exists(".zarray") || zipStore.exists(".zgroup");
        } catch (IOException e) {
            System.out.println("Error reading ZIP store: " + e.getMessage());
        }
      }
      return false;
    }
    */

    private boolean isZarrArray(StoreHandle store, String path) {
        try {
            Array.open(store.resolve(path));
            return true;
        } catch (Exception e) {
            System.out.println("Error checking for Zarr array: " + path + e.getMessage());
            return false;
        }
    }

    private void inspectZarrStore(Path path) {
        try {
            StoreHandle store;
            boolean isZip = isZipFile(path);
            if (isZip) {
                System.out.println("Opening ZIP store: " + path);
                store = new ReadOnlyZipStore(path).resolve(); // root handle
            } else {
                System.out.println("Opening folder store: " + path);
                store = new StoreHandle(new FilesystemStore(path));
            }
            ZarrRootNode root = new ZarrRootNode(store);
            root.createZarrTree(-1, false, false); // depth -1 for full tree, no need to read metadata here
            System.out.println("Store contents:");
            List<String> children = new ArrayList<>();
            String[] childArray = store.listChildren().toArray(String[]::new);
//First print contents and check if they are Zarr arrays, then print all children again for comparison

            for (String child : childArray) {
                System.out.println("  " + child);
            }
            System.out.println("Checking which children are Zarr arrays...");
            for (String child : childArray) {
                if (isZarrArray(store, child)) {
                    System.out.println("  " + child + " (Zarr array)");
                    Array array = Array.open(store.resolve(child));
                    ArrayMetadata meta = array.metadata();
                    System.out.println("Detected Zarr array!");
                    System.out.println("Shape: " + Arrays.toString(meta.shape));
                    System.out.println("Chunk shape: " + Arrays.toString(meta.chunkShape()));
                    System.out.println("Data type: " + meta.dataType());
                    children.add(child);
                } else {
                    System.out.println(" " + child + " (not a Zarr array)");
                }
            }

        } catch (Exception e) {
            System.out.println("Failed to inspect Zarr store: " + e.getMessage());
        }
    }

    private void openZar(boolean useVirtualStack) throws IOException {
        Path path = file.toPath();

        // Check if it's a file or folder
        if (!Files.exists(path)) {
            System.out.println("File does not exist: " + path);
            return;
        }

        boolean isDirectory = Files.isDirectory(path);
        boolean isFile = Files.isRegularFile(path);

        System.out.println("Path: " + path);
        System.out.println("Is directory? " + isDirectory);
        System.out.println("Is file? " + isFile);

        // Try to detect if it's a ZIP file (basic check)
        boolean isZip = false;
        if (isFile) {
            try (InputStream in = Files.newInputStream(path)) {
                byte[] signature = new byte[4];
                if (in.read(signature) == 4) {
                    // ZIP files start with "PK\003\004"
                    isZip = signature[0] == 'P' && signature[1] == 'K' && signature[2] == 3 && signature[3] == 4;
                }
            } catch (IOException e) {
                System.out.println("Cannot read file signature: " + e.getMessage());
            }
        }
        System.out.println("Is ZIP file? " + isZip);

        // Attempt to open as Zarr (v2 or v3 auto-detect)
        try {
            inspectZarrStore(path); // Optional: list store contents before opening
                                   // array
            dev.zarr.zarrjava.core.Array array;
            if (isZip) {
                System.out.println("Attempting to open as Zarr ZIP store...");
                ReadOnlyZipStore zipStore = new ReadOnlyZipStore(path);
                array = dev.zarr.zarrjava.core.Array.open(zipStore.resolve()); // root
                                                                              // handle
            } else {
                array = dev.zarr.zarrjava.core.Array.open(path);
            }

            dev.zarr.zarrjava.core.ArrayMetadata meta = array.metadata();
            final int[] chunkShape = meta.chunkShape();

            System.out.println("Detected Zarr array!");
            System.out.println("Shape: " + Arrays.toString(meta.shape));
            System.out.println("Chunk shape: " + Arrays.toString(chunkShape));
            System.out.println("Data type: " + meta.dataType());

        } catch (IOException e) {
            System.out.println("I/O error while accessing the Zarr store: " + e.getMessage());
        } catch (ZarrException e) {
            System.out.println("Not a valid Zarr array: " + e.getMessage());
        } catch (Exception e) {
            System.out.println("Excerption: " + e.getMessage());
        }
        /*
            DenFileInfo inf = new DenFileInfo(file);
            if(!inf.isValidDEN())
            {
                throw new RuntimeException(String.format("File %s is not valid DEN!", file.getName()));
            } else
            {
                OpenDialog.setLastDirectory(directory);
                Prefs.set("options.denlastdir", directory);
                Prefs.savePreferences();
                System.out.println(
                    String.format("Storing directory %s.", Prefs.getString(".options.denlastdir")));
            }
            FileInfo fi = new FileInfo();
            fi.fileFormat = FileInfo.RAW;
            fi.fileName = file.getName();
            fi.directory = directory;
            fi.width = (int)inf.getDimx();
            fi.height = (int)inf.getDimy();
            fi.offset = (int)inf.getDataByteOffset();
            fi.nImages = (int)inf.getDimz();
            fi.gapBetweenImages = 0;
            fi.intelByteOrder = true; // little endian
            fi.whiteIsZero = false; // can be adjusted
            DenDataType typ = inf.getElementType();
            if(typ == DenDataType.UINT8)
            {
                fi.fileType = FileInfo.GRAY8;
            } else if(typ == DenDataType.UINT16)
            {
                fi.fileType = FileInfo.GRAY16_UNSIGNED;
            } else if(typ == DenDataType.FLOAT32)
            {
                fi.fileType = FileInfo.GRAY32_FLOAT;
            } else if(typ == DenDataType.FLOAT64)
            {
                fi.fileType = FileInfo.GRAY64_FLOAT;
            } else if(typ == DenDataType.UINT32)
            {
                fi.fileType = FileInfo.GRAY32_UNSIGNED;
            } else
            {
                throw new RuntimeException(
                    String.format("The type %s is not implemented yet!", typ.name()));
            }
            ImagePlus img;
            if(useVirtualStack)
            {
                img = new ImagePlus(file.getName(), new DenVirtualStack(file));
            } else
            {
                FileOpener fo = new FileOpener(fi);
                img = fo.open(false);
            }
            if(img != null)
            {
                if(IJ.getVersion().compareTo("1.50e") >= 0)
                    img.setIJMenuBar(true);
                img.show();
                img.setZ((int)((inf.getDim(2) + 1) / 2));
                img.updateAndDraw();
            }*/
    }
}
