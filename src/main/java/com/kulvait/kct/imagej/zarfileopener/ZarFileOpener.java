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
import javax.swing.TransferHandler;
import javax.swing.JList;
import javax.swing.JTable;
import javax.swing.JComponent;
import java.awt.Component;
import java.awt.Container;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.UnsupportedFlavorException;
import com.kulvait.kct.imagej.FileOpenerDirectory;
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

    private String directory;
    private File file;
    private ZarOpenerAccessory zarAccessory = null;

    public void run(String arg) {

        Logger rootLogger = Logger.getLogger("com.kulvait.kct.imagej.zarfileopener");
        String levelStr = System.getProperty("kct.log.level", "INFO");
        Level consoleLevel;
        try {
            consoleLevel = Level.parse(levelStr.toUpperCase());
        } catch (IllegalArgumentException e) {
            System.out.printf("Invalid log level '%s', defaulting to WARNING%n", levelStr);
            consoleLevel = Level.INFO;
        }

        rootLogger.setLevel(Level.ALL);
        rootLogger.setUseParentHandlers(false);
        // Remove default handlers if needed
        Handler[] handlers = rootLogger.getHandlers();
        for (Handler h : handlers) {
            rootLogger.removeHandler(h);
        }

        // Add console handler that prints all levels
        ConsoleHandler consoleHandler = new ConsoleHandler();
        //consoleHandler.setLevel(Level.FINE); // allow debug messages
        consoleHandler.setLevel(consoleLevel);
        LineNumberFormatter formatter = new LineNumberFormatter();
        consoleHandler.setFormatter(formatter);
        rootLogger.addHandler(consoleHandler);

        logger.info("Logger initialized at FINE level");
        try {
            String[] zarrPath = new String[0]; // Default to root path
            boolean useVirtualStack;
            if (arg.equals("")) {
                if (openFilesDialog() == false) {
                    return;
                }
                useVirtualStack = zarAccessory.isBoxSelected();
                zarrPath = zarAccessory.getSelectedZarrPath();
            } else {
                file = new File(arg);
                directory = file.getAbsoluteFile().getParent() + File.separator;
                useVirtualStack = true;
            }
            openZar(zarrPath, useVirtualStack);
        } catch (IOException e) {
            System.out.printf("%s ERROR", e.toString());
        }
    }

    private boolean isDENFileOrZarrStore(File file) {
        if (file == null || !file.exists()) {
            return false;
        }
        if (file.isFile() && new DenFileInfo(file).isValidDEN()) {
            return true;
        }
//Zarr store check is more expensive, so we only do it if the DEN check fails
        ZarFileInfo zarInf;
        //Test if the object was created in ZarOpenerAccessory to reuse resources
        if (zarAccessory != null) {
            zarInf = zarAccessory.getSelectedZarrFileInfo();
            if (zarInf == null || !zarInf.getFile().equals(file)) {
                zarInf = new ZarFileInfo(file);
            }
        } else {
            zarInf = new ZarFileInfo(file);
        }
        return zarInf.isValidZarr();
    }

    public boolean openFilesDialog() {
        return openFilesDialog(null);
    }

    private boolean openFilesDialog(File initialFile) {
        file = null;
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
                            if (selectedFile != null && selectedFile.isDirectory()) {
                                setCurrentDirectory(selectedFile);
                                return;
                            }
                        }
                    };
                    fc.setDialogTitle("Open Zarr/DEN file ...");
                    fc.setFileSelectionMode(JFileChooser.FILES_AND_DIRECTORIES);
                    zarAccessory = new ZarOpenerAccessory(fc);
                    fc.setAccessory(zarAccessory);
                    fc.setMultiSelectionEnabled(false);
                    File lastDirectory = FileOpenerDirectory.getLastDirectory();
                    if (lastDirectory != null) {
                        fc.setCurrentDirectory(lastDirectory);
                    }
                    installFileDropHandler(fc);
                    if (initialFile != null) {
                        selectFile(fc, initialFile);
                    }
                    int returnVal = fc.showOpenDialog(IJ.getInstance());
                    if (returnVal != JFileChooser.APPROVE_OPTION) {
                        return;
                    }
                    file = fc.getSelectedFile();
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

    private void selectFile(JFileChooser chooser, File selectedFile) {
        File absoluteFile = selectedFile.getAbsoluteFile();
        chooser.setCurrentDirectory(absoluteFile.getParentFile());
        chooser.setSelectedFile(absoluteFile);
    }

    private void installFileDropHandler(JFileChooser chooser) {
        TransferHandler handler = new TransferHandler() {
            @Override
            public boolean canImport(TransferSupport support) {
                return support.isDataFlavorSupported(DataFlavor.javaFileListFlavor);
            }

            @Override
            public boolean importData(TransferSupport support) {
                if (!canImport(support)) {
                    return false;
                }
                try {
                    Object transferred = support.getTransferable().getTransferData(DataFlavor.javaFileListFlavor);
                    if (!(transferred instanceof List<?> files) || files.size() != 1 || !(files.get(
                            0) instanceof File droppedFile)) {
                        IJ.error("Open Zarr/DEN", "Drop one file or directory at a time.");
                        return false;
                    }
                    if (isDENFileOrZarrStore(droppedFile)) {
                        selectFile(chooser, droppedFile);
                    } else if (droppedFile.isDirectory()) {
                        chooser.setSelectedFile(null);
                        chooser.setCurrentDirectory(droppedFile);
                    } else {
                        IJ.error("Open Zarr/DEN", "Not a valid Zarr store or DEN file: " + droppedFile);
                        return false;
                    }
                    return true;
                } catch (IOException | UnsupportedFlavorException e) {
                    logger.log(Level.WARNING, "Cannot read dropped files", e);
                    IJ.error("Open Zarr/DEN", "Cannot read dropped files: " + e.getMessage());
                    return false;
                }
            }
        };
        chooser.setTransferHandler(handler);
        installListDropHandler(chooser, handler);
    }

    private void installListDropHandler(Container container, TransferHandler handler) {
        for (Component component : container.getComponents()) {
            if (component instanceof JList || component instanceof JTable) {
                ((JComponent) component).setTransferHandler(handler);
            }
            if (component instanceof Container child) {
                installListDropHandler(child, handler);
            }
        }
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
            if (zarInf == null || !zarInf.getFile().equals(file)) {
                zarInf = new ZarFileInfo(file);
            }
        } else {
            zarInf = new ZarFileInfo(file);
        }
        ImagePlus img;
        if (zarInf.isValidZarr()) {
            directory = FileOpenerDirectory.remember(file);
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
                File selectedStore = file;
                if (!openFilesDialog(selectedStore)) {
                    return;
                }
                useVirtualStack = zarAccessory.isBoxSelected();
                path = zarAccessory.getSelectedZarrPath();
                openZar(path, useVirtualStack);
            }
        } else {
            DenFileInfo inf = new DenFileInfo(file);
            if (!inf.isValidDEN()) {
                throw new RuntimeException(String.format("File %s is not valid DEN!", file.getName()));
            } else {
                directory = FileOpenerDirectory.remember(file);
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
