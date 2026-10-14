package com.thumbstick.controller;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import net.java.games.input.Component;
import net.java.games.input.Controller;
import net.java.games.input.ControllerEnvironment;

import com.thumbstick.Thumbstick;

/**
 * Uses JInput directly (it ships with Minecraft 1.7.10). On Windows each scan creates its own DirectInput plugin,
 * the only way to see controllers plugged in later, and it can run on a background thread. JInput frees a plugin's
 * devices only in a shutdown hook, so release does it by hand once a scan is unused.
 */
final class JInputBackend {

    private static final String DIRECT_INPUT_PLUGIN = "net.java.games.input.DirectInputEnvironmentPlugin";
    /** Held so the level below is not lost when the logger is garbage collected */
    private static final Logger JINPUT_LOGGER = Logger.getLogger("net.java.games.input");

    static {
        // Every scan logs each keyboard and mouse it cannot open
        JINPUT_LOGGER.setLevel(Level.WARNING);
    }

    private static Method enumerateRawDevices, rawDeviceHandle, rawDeviceType;
    private static boolean rawUnavailable;

    private JInputBackend() {}

    /** The devices found by one scan and the JInput environment owning them */
    static final class ScanResult {

        final List<Device> devices;
        final String signature;
        private final ControllerEnvironment environment;

        ScanResult(List<Device> devices, ControllerEnvironment environment) {
            this.devices = devices;
            this.environment = environment;
            StringBuilder sb = new StringBuilder();
            for (Device d : devices) sb.append(d.getName()).append('\n');
            signature = sb.toString();
        }
    }

    static boolean isWindows() { return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win"); }

    static boolean canUseDirectInput() {
        try {
            if (!isWindows()) return false;
            Class.forName(DIRECT_INPUT_PLUGIN);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static ControllerEnvironment create(String className) throws Exception {
        Constructor<?> c = Class.forName(className).getDeclaredConstructor();
        c.setAccessible(true);
        return (ControllerEnvironment) c.newInstance();
    }

    /** Lists controllers with a new DirectInput plugin. Call from the release thread, the plugin's window is tied to it */
    static ScanResult scanDirectInput() throws Exception {
        ControllerEnvironment env = create(DIRECT_INPUT_PLUGIN);
        return new ScanResult(collect(env), env);
    }

    /**
     * Lists attached HID devices through Raw Input, which is cheap and opens nothing. DirectInput scans leak a few
     * dozen OS handles inside DirectInput itself, so they only run when this changes.
     *
     * @return a string identifying the attached HID devices, or null without Raw Input
     */
    static String hidDeviceSignature() {
        if (rawUnavailable) return null;
        try {
            if (enumerateRawDevices == null) {
                Class<?> device = Class.forName("net.java.games.input.RawDevice");
                enumerateRawDevices = Class.forName("net.java.games.input.RawInputEnvironmentPlugin")
                    .getDeclaredMethod("enumerateDevices", Class.forName("net.java.games.input.RawInputEventQueue"), List.class);
                rawDeviceHandle = device.getDeclaredMethod("getHandle");
                rawDeviceType = device.getDeclaredMethod("getType");
                enumerateRawDevices.setAccessible(true);
                rawDeviceHandle.setAccessible(true);
                rawDeviceType.setAccessible(true);
            }
            List<Object> devices = new ArrayList<>();
            enumerateRawDevices.invoke(null, null, devices);
            StringBuilder sb = new StringBuilder();
            for (Object d : devices) {
                sb.append(Long.toHexString((Long) rawDeviceHandle.invoke(d))).append(':').append(rawDeviceType.invoke(d)).append(' ');
            }
            return sb.toString();
        } catch (Throwable t) {
            Thumbstick.LOG.debug("Raw Input device listing is unavailable", t);
            rawUnavailable = true;
            return null;
        }
    }

    private static Object call(Object target, String method) throws Exception {
        Method m = target.getClass().getDeclaredMethod(method);
        m.setAccessible(true);
        return m.invoke(target);
    }

    /** Frees the devices and window of a DirectInput scan, its devices must not be used afterwards */
    static void release(ScanResult result) {
        if (result.environment == null) return;
        try {
            Class<?> clazz = result.environment.getClass();
            Field devicesField = clazz.getDeclaredField("active_devices");
            devicesField.setAccessible(true);
            List<?> devices = (List<?>) devicesField.get(result.environment);
            for (Object d : devices) call(d, "release");
            // Cleared so the plugin's shutdown hook does not release them again
            devices.clear();
            Field windowField = clazz.getDeclaredField("window");
            windowField.setAccessible(true);
            Object window = windowField.get(result.environment);
            if (window != null) call(window, "destroy");
        } catch (Throwable t) {
            Thumbstick.LOG.debug("Could not release a DirectInput scan", t);
        }
    }

    /** Scan used outside Windows: the default environment, or a fresh one to find new devices */
    static ScanResult scanDefault(boolean fresh) {
        ControllerEnvironment env = null;
        if (fresh) {
            try {
                env = create("net.java.games.input.DefaultControllerEnvironment");
            } catch (Throwable t) {
                Thumbstick.LOG.warn("Could not create a fresh JInput environment", t);
            }
        }
        if (env == null) env = ControllerEnvironment.getDefaultEnvironment();
        return new ScanResult(collect(env), null);
    }

    private static List<Device> collect(ControllerEnvironment env) {
        List<Device> devices = new ArrayList<>();
        for (Controller c : env.getControllers()) collect(c, devices);
        return devices;
    }

    private static void collect(Controller c, List<Device> out) {
        Controller[] children = c.getControllers();
        if (children.length > 0) {
            for (Controller child : children) collect(child, out);
            return;
        }
        Controller.Type t = c.getType();
        if (t == Controller.Type.KEYBOARD || t == Controller.Type.MOUSE || t == Controller.Type.TRACKBALL || t == Controller.Type.TRACKPAD) return;
        JInputDevice d = new JInputDevice(c);
        if (d.buttons.length >= 4 && (d.axes.length >= 2 || d.hats.length >= 1)) out.add(d);
    }

    private static final class JInputDevice implements Device {

        private final Controller controller;
        private final Component[] buttons, axes, hats;
        private final String[] buttonNames, axisNames, hatNames;

        JInputDevice(Controller controller) {
            this.controller = controller;
            List<Component> b = new ArrayList<>(), a = new ArrayList<>(), h = new ArrayList<>();
            for (Component c : controller.getComponents()) {
                Component.Identifier id = c.getIdentifier();
                if (id == Component.Identifier.Axis.POV) h.add(c);
                else if (id instanceof Component.Identifier.Button || id instanceof Component.Identifier.Key) b.add(c);
                else if (c.isAnalog()) a.add(c);
            }
            buttons = b.toArray(new Component[0]);
            axes = a.toArray(new Component[0]);
            hats = h.toArray(new Component[0]);
            buttonNames = uniqueNames(buttons);
            axisNames = uniqueNames(axes);
            hatNames = uniqueNames(hats);
        }

        /** Identifier names (like 0, x, rz, pov), suffixed when a device repeats one */
        private static String[] uniqueNames(Component[] components) {
            String[] names = new String[components.length];
            Map<String, Integer> seen = new HashMap<>();
            for (int i = 0; i < components.length; i++) {
                String name = components[i].getIdentifier().getName();
                Integer count = seen.get(name);
                seen.put(name, count == null ? 1 : count + 1);
                names[i] = count == null ? name : name + "#" + count;
            }
            return names;
        }

        @Override public String getName() { return controller.getName().trim(); }
        @Override public String getBackendId() { return "jinput"; }
        @Override public boolean poll() { return controller.poll(); }
        @Override public int getButtonCount() { return buttons.length; }
        @Override public String getButtonName(int i) { return buttonNames[i]; }
        @Override public boolean isButtonDown(int i) { return buttons[i].getPollData() > 0.5F; }
        @Override public int getAxisCount() { return axes.length; }
        @Override public String getAxisName(int i) { return axisNames[i]; }
        @Override public float getAxisValue(int i) { return axes[i].getPollData(); }
        @Override public int getHatCount() { return hats.length; }
        @Override public String getHatName(int i) { return hatNames[i]; }

        @Override
        public int getHatX(int i) {
            float v = hats[i].getPollData();
            if (is(v, Component.POV.UP_RIGHT) || is(v, Component.POV.RIGHT) || is(v, Component.POV.DOWN_RIGHT)) return 1;
            if (is(v, Component.POV.UP_LEFT) || is(v, Component.POV.LEFT) || is(v, Component.POV.DOWN_LEFT)) return -1;
            return 0;
        }

        @Override
        public int getHatY(int i) {
            float v = hats[i].getPollData();
            if (is(v, Component.POV.UP_LEFT) || is(v, Component.POV.UP) || is(v, Component.POV.UP_RIGHT)) return -1;
            if (is(v, Component.POV.DOWN_LEFT) || is(v, Component.POV.DOWN) || is(v, Component.POV.DOWN_RIGHT)) return 1;
            return 0;
        }

        private static boolean is(float value, float target) { return Math.abs(value - target) < 0.01F; }
    }
}
