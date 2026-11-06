package com.kulvait.kct.imagej.zarfileopener;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Parses a Zarr store path followed by an array path, e.g. store.zar:/group/array. */
public final class ZarrPathSpec {
    private final File storeFile;
    private final String[] arrayPath;

    private ZarrPathSpec(File storeFile, String[] arrayPath) {
        this.storeFile = storeFile;
        this.arrayPath = arrayPath;
    }

    public static ZarrPathSpec parse(String value) {
        if (value == null) {
            return null;
        }
        int separator = value.lastIndexOf(":/");
        if (separator <= 0 || separator + 2 > value.length()) {
            return null;
        }

        File storeFile = new File(value.substring(0, separator));
        if (!isZarrStore(storeFile)) {
            return null;
        }

        String path = value.substring(separator + 2);
        List<String> segments = new ArrayList<>();
        for (String segment : path.split("/")) {
            if (!segment.isEmpty()) {
                segments.add(segment);
            }
        }
        return new ZarrPathSpec(storeFile, segments.toArray(new String[0]));
    }

    private static boolean isZarrStore(File file) {
        if (file.isDirectory()) {
            return new File(file, ".zgroup").isFile() || new File(file, ".zarray").isFile() || new File(file, "zarr.json").isFile();
        }
        if (!file.isFile()) {
            return false;
        }
        String name = file.getName().toLowerCase(Locale.ROOT);
        return name.endsWith(".zar") || name.endsWith(".zarr") || name.endsWith(".zip");
    }

    public File getStoreFile() {
        return storeFile;
    }

    public String[] getArrayPath() {
        return arrayPath.clone();
    }
}
