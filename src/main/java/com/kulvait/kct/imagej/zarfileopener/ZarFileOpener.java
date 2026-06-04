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
import dev.zarr.zarrjava.core.DataType;
import dev.zarr.zarrjava.store.ReadOnlyFilesystemZipStore;
import dev.zarr.zarrjava.store.FilesystemStore;
import dev.zarr.zarrjava.store.StoreHandle;
import dev.zarr.zarrjava.ZarrException;
// DEN file opener
import com.kulvait.kct.imagej.denfileopener.DenFileInfo;
import com.kulvait.kct.imagej.denfileopener.DenDataType;
import com.kulvait.kct.imagej.denfileopener.DenVirtualStack;

/**
 * Uses the JFileChooser from Swing to open one or more raw images. The "Open
 * All Files in Folder" check box in the dialog is ignored.
 */
public class ZarFileOpener implements PlugIn {
    private static final Logger logger = Logger.getLogger(ZarFileOpener.class.getName());

    static private String directory;
    private File file;
    private ZarOpenerAccessory zarAccessory = null;

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
            String[] path = null; // Placeholder for potential path argument parsing
            boolean useVirtualStack;
            if (arg.equals("")) {
                if (openFilesDialog() == false) {
                    return;
                }
                useVirtualStack = zarAccessory.isBoxSelected();
                path = zarAccessory.getSelectedZarrPath();
            } else {
                file = new File(arg);
                directory = file.getParent() + File.separator;
                useVirtualStack = true;
            }
            openZar(path, useVirtualStack);
        } catch (IOException e) {
            System.out.printf("%s ERROR", e.toString());
        }
    }

    private boolean isDENFileOrZarrStore(File file) {
        DenFileInfo inf = new DenFileInfo(file);
        if (inf.isValidDEN()) {
            return true;
        }
//Zarr store check is more expensive, so we only do it if the DEN check fails
        ZarFileInfo zarInf;
        //Test if the object was created in ZarOpenerAccessory to reuse resources
        if (zarAccessory != null) {
            zarInf = zarAccessory.getSelectedZarrFileInfo();
            File zarFile = zarInf.getFile();
            if (!zarFile.equals(file)) {
                zarInf = new ZarFileInfo(file);
            }
        } else {
            zarInf = new ZarFileInfo(file);
        }
        return zarInf.isValidZarr();
    }

    public boolean openFilesDialog() {
        try {
            EventQueue.invokeAndWait(new Runnable() {

                public void run() {
                    JFileChooser fc = new JFileChooser() {
                        @Override
                        public void approveSelection() {
                            File selectedFile = getSelectedFile();
                            if (isDENFileOrZarrStore(selectedFile)) {
                                // If the selected file is a DEN file or a Zarr store, approve the selection
                                super.approveSelection();
                                return;
                            }
                            if (selectedFile.isDirectory()) {
                                setCurrentDirectory(selectedFile);
                                return;
                            }
                        }
                    };
                    fc.setDialogTitle("Open Zarr/DEN file ...");
                    //This might crash accessory not implemented!
                    fc.setFileSelectionMode(JFileChooser.FILES_AND_DIRECTORIES);
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

    private void openZar(String[] path, boolean useVirtualStack) throws IOException {
        if (path == null) {
            logger.log(Level.WARNING, "Path is null, defaulting to root");
            path = new String[0];
        }
        String zarrPath = "/" + String.join("/", path);
        ZarFileInfo zarInf;
        //Test if the object was created in ZarOpenerAccessory to reuse resources
        if (zarAccessory != null) {
            zarInf = zarAccessory.getSelectedZarrFileInfo();
            File zarFile = zarInf.getFile();
            if (!zarFile.equals(file)) {
                zarInf = new ZarFileInfo(file);
            }
        } else {
            zarInf = new ZarFileInfo(file);
        }
        ImagePlus img;
        if (zarInf.isValidZarr()) {
            ZarrNode node = zarInf.getRootNode().getDescendant(path);
            if (node != null && node.getType() == ZarrNodeType.ARRAY) {
                logger.log(Level.INFO, String.format("Opening Zarr array %s:%s", file.getName(), zarrPath));
                ZarrArrayNode arrayNode = (ZarrArrayNode) node;
                long[] shape = arrayNode.getShape();
                int[] chunkShape = arrayNode.getChunkShape();
                DataType dtype = arrayNode.getDataType();
                if (shape.length > 3) {
                    String msg = String.format(
                            "Array at path %s has shape %s, which has more than 3 dimensions!",
                            "/" + "".join("/", path), Arrays.toString(shape));
                    logger.log(Level.SEVERE, msg);
                }
                ZarVirtualStack vstack = new ZarVirtualStack(zarInf, path);
                int frameCount = vstack.getFrameCount();
                img = new ImagePlus(file.getName() + zarrPath, vstack);
                if (img != null) {
                    if (IJ.getVersion().compareTo("1.50e") >= 0)
                        img.setIJMenuBar(true);
                    img.show();
                    img.setZ((int) ((frameCount + 1) / 2));
                    img.updateAndDraw();
                }
            } else {
                String msg = String.format("Path %s in %s is not a Zarr array, cannot open as image.", "/" + "".join(
                        "/", path), file.getName());
                logger.log(Level.SEVERE, msg);
                openFilesDialog();
                useVirtualStack = zarAccessory.isBoxSelected();
                path = zarAccessory.getSelectedZarrPath();
                openZar(path, useVirtualStack);
            }
        } else {
            DenFileInfo inf = new DenFileInfo(file);
            if (!inf.isValidDEN()) {
                throw new RuntimeException(String.format("File %s is not valid DEN!", file.getName()));
            } else {
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
            fi.width = (int) inf.getDimx();
            fi.height = (int) inf.getDimy();
            fi.offset = (int) inf.getDataByteOffset();
            fi.nImages = (int) inf.getDimz();
            fi.gapBetweenImages = 0;
            fi.intelByteOrder = true; // little endian
            fi.whiteIsZero = false; // can be adjusted
            DenDataType typ = inf.getElementType();
            if (typ == DenDataType.UINT8) {
                fi.fileType = FileInfo.GRAY8;
            } else if (typ == DenDataType.UINT16) {
                fi.fileType = FileInfo.GRAY16_UNSIGNED;
            } else if (typ == DenDataType.FLOAT32) {
                fi.fileType = FileInfo.GRAY32_FLOAT;
            } else if (typ == DenDataType.FLOAT64) {
                fi.fileType = FileInfo.GRAY64_FLOAT;
            } else if (typ == DenDataType.UINT32) {
                fi.fileType = FileInfo.GRAY32_UNSIGNED;
            } else {
                throw new RuntimeException(
                        String.format("The type %s is not implemented yet!", typ.name()));
            }
            if (useVirtualStack) {
                img = new ImagePlus(file.getName(), new DenVirtualStack(file));
            } else {
                FileOpener fo = new FileOpener(fi);
                img = fo.open(false);
            }
            if (img != null) {
                if (IJ.getVersion().compareTo("1.50e") >= 0)
                    img.setIJMenuBar(true);
                img.show();
                img.setZ((int) ((inf.getDim(2) + 1) / 2));
                img.updateAndDraw();
            }
        }
    }
}
