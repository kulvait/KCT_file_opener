
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


    /** Lazily create the shared bridge. Throws if the env cannot be located. */
    public static synchronized JEPBridge get(String envPrefix) {
        if (INSTANCE == null) {
            if (envPrefix == null) {
                envPrefix = "/data/hereon/wp/group/laupy/share/mamba_env/minizarr";
            }
            logger.log(Level.INFO, "Initializing JEP bridge with env prefix: {0}", envPrefix);
            try {
                configureMainInterpreter(envPrefix);
                JEPBridge bridge = new JEPBridge(envPrefix);
                bridge.startInterpreter();
                INSTANCE = bridge;
            } catch (Exception e) {
                logger.log(Level.SEVERE, "Failed to initialize JEP bridge with env prefix " + envPrefix, e);
                logger.log(Level.SEVERE, e.getMessage());
                e.printStackTrace();
                INSTANCE = null; // ensure we don't return a half-initialized instance
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
        logger.log(Level.INFO, "JEP configured with libjep at {0}", libjep);
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

    private void startInterpreter() throws Exception {
        Path sitePackages = findSitePackages(envPrefix);
        submit(() -> {
            JepConfig cfg = new JepConfig();
            cfg.addIncludePaths(sitePackages.toString());
            interp = new SharedInterpreter(); // honors MainInterpreter config
            interp.exec("import sys");
            interp.exec("sys.path.insert(0, " + pyStr(sitePackages.toString()) + ")");
            interp.exec("import numpy as np");
            interp.exec("import zarr");
            interp.exec(READ_SLICE_FUNC);
            logger.info("JEP zarr bridge interpreter ready (env=" + envPrefix + ")");
            return null;
        });
    }

    /** Reads a slice via Python/zarr and returns raw little-endian bytes. */
    public Result readSlice(String storePath, String[] zarrPath, long[] offset, long[] shape) throws Exception {
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
    private static final String READ_SLICE_FUNC = "def _kct_read_slice(store_path, zarr_path, offset, shape):\n" + "    import numpy as np\n" + "    shp = tuple(int(s) for s in shape)\n" + "    if len(shp) == 0:\n" + "        data = np.asarray(0, dtype=np.float32)\n" + "    else:\n" + "        # Generate a deterministic stripe/ramp pattern for testing Java <-> Python transfer.\n" + "        # This ignores store_path/zarr_path and only uses requested shape.\n" + "        idx = np.indices(shp, dtype=np.int32)\n" + "        data = (idx.sum(axis=0) % 256).astype(np.float32)\n" + "    data = np.ascontiguousarray(data)\n" + "    return (data.dtype.str, list(int(x) for x in data.shape), data.tobytes())\n";
}
