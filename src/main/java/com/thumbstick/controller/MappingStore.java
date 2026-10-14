package com.thumbstick.controller;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Properties;

import com.thumbstick.Thumbstick;

/** Saves wizard mappings by device name in a properties file as device name/logical input=source */
public final class MappingStore {

    private static final Properties PROPERTIES = new Properties();
    private static File file;

    private MappingStore() {}

    public static void init(File mappingFile) {
        file = mappingFile;
        PROPERTIES.clear();
        if (!file.isFile()) return;
        try (InputStream in = new FileInputStream(file)) {
            PROPERTIES.load(in);
        } catch (Exception e) {
            Thumbstick.LOG.error("Failed to read controller mappings from {}", file, e);
        }
    }

    public static boolean hasCustomMapping(Device device) {
        String prefix = device.getName() + "/";
        for (String key : PROPERTIES.stringPropertyNames()) if (key.startsWith(prefix)) return true;
        return false;
    }

    /** The saved mapping of the device over its built in profile */
    public static ControllerMapping getMapping(Device device) {
        ControllerMapping mapping = MappingProfiles.forDevice(device);
        for (String key : ControllerMapping.KEYS) {
            String value = PROPERTIES.getProperty(device.getName() + "/" + key);
            if (value != null) mapping.set(key, value);
        }
        return mapping;
    }

    public static void saveMapping(Device device, ControllerMapping mapping) {
        for (String key : ControllerMapping.KEYS) PROPERTIES.setProperty(device.getName() + "/" + key, mapping.get(key));
        write();
    }

    public static void resetMapping(Device device) {
        String prefix = device.getName() + "/";
        for (String key : PROPERTIES.stringPropertyNames()) if (key.startsWith(prefix)) PROPERTIES.remove(key);
        write();
    }

    private static void write() {
        if (file == null) return;
        try {
            File parent = file.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) throw new IllegalStateException("Could not create " + parent);
            try (OutputStream out = new FileOutputStream(file)) {
                PROPERTIES.store(out, "Thumbstick controller mappings (edit in-game: Controller Settings > Controller Mapping)");
            }
        } catch (Exception e) {
            Thumbstick.LOG.error("Failed to save controller mappings to {}", file, e);
        }
    }
}
