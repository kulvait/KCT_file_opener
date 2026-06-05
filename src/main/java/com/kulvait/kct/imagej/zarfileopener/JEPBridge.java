
package com.kulvait.kct.imagej.zarfileopener;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.Logger;

import jep.Interpreter;
import jep.JepConfig;
import jep.MainInterpreter;
import jep.SharedInterpreter;
import jep.NamingConventionClassEnquirer;


/**
 * Bridge to CPython via JEP, backed by a dedicated mamba/conda environment
 * that provides numpy + zarr. Used as a fallback when zarr-java cannot read
 * an array. All Python access is confined to a single worker thread because
 * JEP interpreters are thread-confined.
 */
public class JEPBridge implements AutoCloseable {
    private static final Logger logger = Logger.getLogger(JEPBridge.class.getName());

    public JEPBridge(String envPrefix) {
        this.envPrefix = envPrefix;
    }

    private static volatile JEPBridge INSTANCE;
    private static volatile boolean mainConfigured = false;


    /** Result of a slice read coming back from Python. */
    public record Result(String dtype, long[] shape, byte[] bytes) {
    }

    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "jep-zarr-bridge");
        t.setDaemon(true);
        return t;
    });

    private Interpreter interp; // created on (and owned by) the worker thread
    private final String envPrefix;


//Curent state of teh intepretter
    private String storePath;
    private boolean isZip;
    private String[] zarrPath;


    /** Lazily create the shared bridge. Throws if the env cannot be located. */
    public static synchronized JEPBridge get(String envPrefix, String storePath, boolean isZip, String[] zarrPath) {
        if (INSTANCE == null) {
            if (envPrefix == null) {
                envPrefix = "/data/hereon/wp/group/laupy/share/mamba_env/minizarr";
            }
            logger.log(Level.INFO, "Initializing JEP bridge with env prefix: %s".formatted(envPrefix));
            try {
                configureMainInterpreter(envPrefix);
                JEPBridge bridge = new JEPBridge(envPrefix);
                bridge.startInterpreter(storePath, isZip, zarrPath);
                INSTANCE = bridge;
            } catch (Exception e) {
                logger.log(Level.SEVERE, "Failed to initialize JEP bridge with env prefix " + envPrefix, e);
                logger.log(Level.SEVERE, e.getMessage());
                e.printStackTrace();
                INSTANCE = null; // ensure we don't return a half-initialized instance
            }
        } else {
            try {
                INSTANCE.updateInterpreter(storePath, isZip, zarrPath);
            } catch (Exception e) {
                logger.log(Level.SEVERE, "Failed to update JEP bridge interpreter with new store path and zarr path",
                        e);
                e.printStackTrace();
            }
        }
        return INSTANCE;
    }

    /**
     * MainInterpreter must be configured exactly once per JVM, before any
     * interpreter is created. We point JEP at the libjep shipped inside the env.
     */
    private static synchronized void configureMainInterpreter(String envPrefix) throws Exception {
        if (mainConfigured) {
            return;
        }
        Path sitePackages = findSitePackages(envPrefix);
        Path libjep = findLibJep(sitePackages);
        MainInterpreter.setJepLibraryPath(libjep.toString());
        // Help the dynamic linker find libpythonX.Y from the env at runtime.
        // (On Linux you may also need LD_LIBRARY_PATH=$PREFIX/lib set externally.)
        mainConfigured = true;
        logger.log(Level.INFO, "JEP configured with libjep at %s".formatted(libjep));
    }

    private static Path findSitePackages(String envPrefix) throws Exception {
        // Linux/macOS: <prefix>/lib/pythonX.Y/site-packages
        Path lib = Paths.get(envPrefix, "lib");
        if (Files.isDirectory(lib)) {
            try (var stream = Files.list(lib)) {
                var py = stream.filter(d -> d.getFileName().toString().startsWith("python")).map(d -> d.resolve(
                        "site-packages")).filter(Files::isDirectory).findFirst();
                if (py.isPresent()) {
                    return py.get();
                }
            }
        }
        // Windows: <prefix>/Lib/site-packages
        Path win = Paths.get(envPrefix, "Lib", "site-packages");
        if (Files.isDirectory(win)) {
            return win;
        }
        throw new IllegalStateException("Could not locate site-packages under " + envPrefix);
    }

    private static Path findLibJep(Path sitePackages) {
        Path jepDir = sitePackages.resolve("jep");
        for (String name : new String[]{"libjep.so", "libjep.jnilib", "jep.dll", "libjep.dll"}) {
            Path candidate = jepDir.resolve(name);
            if (Files.exists(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("libjep not found in " + jepDir + " — is the 'jep' package installed in the env?");
    }

    private void startInterpreter(String storePath, boolean isZip, String[] zarrPath) throws Exception {
        Path sitePackages = findSitePackages(envPrefix);
        this.storePath = storePath;
        this.isZip = isZip;
        this.zarrPath = zarrPath;
        submit(() -> {
            JepConfig cfg = new JepConfig();
            cfg.addIncludePaths(sitePackages.toString());
            cfg.setClassEnquirer(new NamingConventionClassEnquirer(true));
            SharedInterpreter.setConfig(cfg);
            interp = new SharedInterpreter();
            logger.info("import numpy as np");
            interp.exec("import numpy as np");
            logger.info("import zarr");
            interp.exec("import zarr");
            logger.info("import imagecodecs");
            interp.exec("import imagecodecs");
            logger.info("from imagecodecs.numcodecs import register_codecs as register_numcodecs_codecs");
            interp.exec("from imagecodecs.numcodecs import register_codecs as register_numcodecs_codecs");
            logger.info("from imagecodecs.zarr import register_codecs as register_zarr_codecs");
            interp.exec("from imagecodecs.zarr import register_codecs as register_zarr_codecs");
            logger.info("register_numcodecs_codecs()");
            interp.exec("register_numcodecs_codecs()");
            logger.info("register_zarr_codecs()");
            interp.exec("register_zarr_codecs()");

            interp.exec(READ_SLICE_FUNC);
            if (isZip) {
                String cmd = "store = zarr.storage.ZipStore(\"%s\", mode=\"r\")".formatted(storePath);
                logger.info(cmd);
                interp.exec(cmd);
            } else {
                String cmd = "store = \"%s\"".formatted(storePath);
                logger.info(cmd);
                interp.exec(cmd);
            }
            if (zarrPath == null || zarrPath.length == 0) {
                String cmd = "array = zarr.open_array(store=store, mode='r')";
                logger.info(cmd);
                interp.exec(cmd);
            } else {

                String zarrPathStr = String.join("/", zarrPath);
                String cmd1 = "g = zarr.open_group(store=store, mode='r')";
                String cmd2 = "array = g['%s']".formatted(zarrPathStr);
                logger.info(cmd1);
                logger.info(cmd2);
                interp.exec(cmd1);
                interp.exec(cmd2);
            }


            logger.info("JEP zarr bridge interpreter ready (env=" + envPrefix + ")");
            return null;
        });
    }

    private void updateInterpreter(String storePath, boolean isZip, String[] zarrPath) throws Exception {
        this.storePath = storePath;
        this.isZip = isZip;
        this.zarrPath = zarrPath;
        submit(() -> {
            if (isZip) {
                interp.exec("store = zarr.storage.ZipStore(\"%s\", mode=\"r\")".formatted(pyStr(storePath)));
            } else {
                interp.exec("store = \"%s\"".formatted(pyStr(storePath)));
            }
            if (zarrPath == null || zarrPath.length == 0) {
                interp.exec("array = zarr.open_groupt(store=store, mode='r')");
            } else {
                String zarrPathStr = String.join("/", zarrPath);
                interp.exec("g = zarr.open_array(store=store, mode='r')");
                interp.exec("array = g['%s']".formatted(pyStr(zarrPathStr)));
            }
            return null;
        });
    }

    /** Reads a slice via Python/zarr and returns raw little-endian bytes. */
    public Result readSlice(String storePath, String[] zarrPath, long[] offset, long[] shape) throws Exception {
        if (storePath != null && !storePath.equals(this.storePath) || zarrPath != null && !java.util.Arrays.equals(
                zarrPath, this.zarrPath)) {
            updateInterpreter(storePath, this.isZip, zarrPath);
        }
        return submit(() -> {
            interp.set("store_path", storePath);
            interp.set("zarr_path", String.join("/", zarrPath));
            interp.set("offset", offset);
            interp.set("shape", shape);
            interp.exec("dtype, out_shape, data = _kct_read_slice(store_path, zarr_path, offset, shape)");
            String dtype = (String) interp.getValue("dtype");
            long[] outShape = toLongArray(interp.getValue("out_shape"));
            byte[] bytes = (byte[]) interp.getValue("data");
            return new Result(dtype, outShape, bytes);
        });
    }

    private <T> T submit(Callable<T> task) throws Exception {
        return worker.submit(task).get();
    }


    private static long[] toLongArray(Object value) {
        if (value instanceof long[] l) {
            return l;
        }
        // JEP converts Python list() to java.util.List, not Object[]
        if (value instanceof java.util.List<?> list) {
            long[] out = new long[list.size()];
            for (int i = 0; i < list.size(); i++) {
                out[i] = ((Number) list.get(i)).longValue();
            }
            return out;
        }
        Object[] arr = (Object[]) value;
        long[] out = new long[arr.length];
        for (int i = 0; i < arr.length; i++) {
            out[i] = ((Number) arr[i]).longValue();
        }
        return out;
    }

    private static String pyStr(String s) {
        return "r'''" + s.replace("'''", "") + "'''";
    }

    @Override
    public void close() {
        worker.submit(() -> {
            if (interp != null) {
                interp.close();
            }
        });
        worker.shutdown();
    }

    /** Python helper installed once into the interpreter namespace. */
    /*
    private static final String READ_SLICE_FUNC =
        "def _kct_read_slice(store_path, zarr_path, offset, shape):\n" +
        "    import numpy as np, zarr\n" +
        "    root = zarr.open(store_path, mode='r')\n" +
        "    arr = root[zarr_path] if zarr_path else root\n" +
        "    sl = tuple(slice(int(o), int(o) + int(s)) for o, s in zip(offset, shape))\n" +
        "    data = np.ascontiguousarray(arr[sl])\n" +
        "    # Normalize to little-endian native bytes for the Java side\n" +
        "    if data.dtype.byteorder == '>':\n" +
        "        data = data.astype(data.dtype.newbyteorder('<'))\n" +
        "    return (data.dtype.str, list(int(x) for x in data.shape), data.tobytes())\n";
    */
    private static final String READ_SLICE_FUNC = """
            def _kct_read_slice(store_path, zarr_path, offset, shape):
                data = array[offset[0]]
                data = np.ascontiguousarray(data)
                return (
                    data.dtype.str,
                    list(int(x) for x in data.shape),
                    data.tobytes()
                )
            """;

    private static final String TEST_SLICE_FUNC = """
            def _kct_read_slice(store_path, zarr_path, offset, shape):
                shp = tuple(int(s) for s in shape)
                if len(shp) == 0:
                    data = np.asarray(0, dtype=np.float32)
                else:
                    # Generate a deterministic stripe/ramp pattern for testing Java <-> Python transfer.
                    # This ignores store_path/zarr_path and only uses requested shape.
                    idx = np.indices(shp, dtype=np.int32)
                    data = (idx.sum(axis=0) % 256).astype(np.float32)

                data = np.ascontiguousarray(data)
                return (
                    data.dtype.str,
                    list(int(x) for x in data.shape),
                    data.tobytes()
                )
            """;
}
