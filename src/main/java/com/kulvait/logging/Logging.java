package com.kulvait.logging;

import java.util.Locale;
import java.util.logging.ConsoleHandler;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class Logging {
    public static final String PLUGIN_LEVEL_PROPERTY = "kct.log.level";
    public static final String ZARR_JAVA_LEVEL_PROPERTY = "kct.zarrjava.log.level";

    // Strong references keep logger levels from being discarded by the LogManager.
    private static final Logger PLUGIN_LOGGER = Logger.getLogger("com.kulvait");
    private static final Logger ZARR_JAVA_LOGGER = Logger.getLogger("dev.zarr.zarrjava");

    private Logging() {
    }

    public static synchronized void configure() {
        String pluginLevelValue = System.getProperty(PLUGIN_LEVEL_PROPERTY, "INFO");
        String zarrJavaLevelValue = System.getProperty(ZARR_JAVA_LEVEL_PROPERTY, "WARNING");
        Level pluginLevel = parseLevel(pluginLevelValue, Level.INFO);
        Level zarrJavaLevel = parseLevel(zarrJavaLevelValue, Level.WARNING);

        for (Handler handler : PLUGIN_LOGGER.getHandlers()) {
            if (handler.getFormatter() != null && handler.getFormatter().getClass().getName().equals(
                    LineNumberFormatter.class.getName())) {
                PLUGIN_LOGGER.removeHandler(handler);
            }
        }
        ConsoleHandler handler = new ConsoleHandler();
        handler.setFormatter(new LineNumberFormatter());
        handler.setLevel(pluginLevel);
        PLUGIN_LOGGER.addHandler(handler);
        PLUGIN_LOGGER.setUseParentHandlers(false);
        PLUGIN_LOGGER.setLevel(pluginLevel);
        ZARR_JAVA_LOGGER.setLevel(zarrJavaLevel);

        Logger logger = Logger.getLogger(Logging.class.getName());
        if (parseLevel(pluginLevelValue, null) == null) {
            logger.log(Level.WARNING, "Invalid {0}={1}; using INFO",
                    new Object[]{PLUGIN_LEVEL_PROPERTY, pluginLevelValue});
        }
        if (parseLevel(zarrJavaLevelValue, null) == null) {
            logger.log(Level.WARNING, "Invalid {0}={1}; using WARNING",
                    new Object[]{ZARR_JAVA_LEVEL_PROPERTY, zarrJavaLevelValue});
        }
        logger.log(Level.FINE, "Logging configured: plugin={0}, zarr-java={1}",
                new Object[]{pluginLevel, zarrJavaLevel});
    }

    private static Level parseLevel(String value, Level fallback) {
        try {
            return Level.parse(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }
}
