package com.kulvait.kct.imagej;

import java.io.File;
import ij.Prefs;
import ij.io.OpenDialog;

/** Shared directory preference for the DEN and Zarr openers. */
public final class FileOpenerDirectory {
    private FileOpenerDirectory() {
    }

    public static File getLastDirectory() {
        String[] candidates = {Prefs.getString(
                ".options.denlastdir"), OpenDialog.getLastDirectory(), OpenDialog.getDefaultDirectory()
        };
        for (String candidate : candidates) {
            if (candidate != null && !candidate.isBlank()) {
                File directory = new File(candidate);
                if (directory.isDirectory()) {
                    return directory;
                }
            }
        }
        return null;
    }

    public static String remember(File openedFile) {
        File parent = openedFile.getAbsoluteFile().getParentFile();
        if (parent == null) {
            throw new IllegalArgumentException("Opened file has no parent: " + openedFile);
        }
        String directory = parent.getPath() + File.separator;
        OpenDialog.setLastDirectory(directory);
        Prefs.set("options.denlastdir", directory);
        Prefs.savePreferences();
        return directory;
    }
}
