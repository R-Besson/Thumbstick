package com.thumbstick.controller;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import net.minecraft.client.resources.I18n;

import com.thumbstick.Thumbstick;
import com.thumbstick.ThumbstickConfig;
import com.thumbstick.client.HudRenderer;

/**
 * Owns the connected devices and the active controller's state. The device is polled every frame so short taps
 * are never missed, and button edges are evaluated once per client tick from everything seen since the last one.
 */
public final class ControllerManager {

    // Declared before INSTANCE, which sizes its arrays with it
    private static final int N = GamepadButton.COUNT;
    public static final ControllerManager INSTANCE = new ControllerManager();

    /** Value of ThumbstickConfig.selectedController that disables controllers */
    public static final String NONE = "<none>";

    /** How often the background scanner checks whether devices were plugged or unplugged */
    private static final long SCAN_INTERVAL_MS = 1000L;
    /** Extra scans after a device change, in case DirectInput sees a new device later than Raw Input */
    private static final int FOLLOW_UP_SCANS = 2;
    /** Outside Windows rescans leak resources, so they are only retried for a while after a disconnect */
    private static final long RESCAN_INTERVAL_MS = 5000L;
    private static final int MAX_AUTO_RESCANS = 60;

    private List<Device> devices = Collections.emptyList();
    private Device active;
    private ControllerMapping.Resolved mapping;

    private final float[] axes = new float[GamepadAxis.COUNT];
    private final boolean[] rawDown = new boolean[N], latched = new boolean[N], tickDown = new boolean[N],
        suppressed = new boolean[N], down = new boolean[N], prevDown = new boolean[N], prevRawDown = new boolean[N];

    private int failedPolls;
    /**
     * Whether the player is using the controller right now: set by new controller input, cleared by keyboard or
     * mouse. Only changes count, so holding a stick while typing does not flip it back and forth.
     */
    private boolean usingController, leftStickWasActive, rightStickWasActive;

    /** Background DirectInput scanning on Windows, null when falling back to one off scans */
    private ScheduledExecutorService scanner;
    private JInputBackend.ScanResult currentScan;
    /** A scan with a different set of devices, waiting to be adopted on the main thread */
    private final AtomicReference<JInputBackend.ScanResult> pendingScan = new AtomicReference<>();
    /** Device list of the last scan handed to the main thread, cleared when a device stops responding */
    private volatile String publishedSignature;
    /** Scanner thread state: last HID device list and pending follow up scans */
    private String lastHidSignature;
    private int followUpScans, slowTicks;
    /** Read by the scanner thread to avoid scanning while a controller is in use, when it cannot tell */
    private volatile boolean hasActiveController;

    private boolean lostController;
    private int autoRescans;
    private long lastRescan;

    private ControllerManager() {}

    public List<Device> getDevices() { return devices; }

    public Device getActiveDevice() { return active; }

    public boolean isConnected() { return mapping != null; }

    /** Finds connected controllers and where possible keeps watching for new ones */
    public void start() {
        if (JInputBackend.canUseDirectInput()) {
            scanner = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "Thumbstick controller scanner");
                t.setDaemon(true);
                return t;
            });
            try {
                // Waited for so controllers work from the main menu on
                adopt(scanner.submit(() -> {
                    lastHidSignature = JInputBackend.hidDeviceSignature();
                    JInputBackend.ScanResult result = JInputBackend.scanDirectInput();
                    publishedSignature = result.signature;
                    return result;
                }).get(10, TimeUnit.SECONDS));
                scanner.scheduleWithFixedDelay(this::backgroundTick, SCAN_INTERVAL_MS, SCAN_INTERVAL_MS, TimeUnit.MILLISECONDS);
                return;
            } catch (Throwable t) {
                Thumbstick.LOG.warn("DirectInput scanning failed, controllers plugged in later will not be detected", t);
                scanner.shutdownNow();
                scanner = null;
            }
        }
        scan(false);
    }

    /** Runs on the scanner thread every second and scans only when the attached devices changed */
    private void backgroundTick() {
        boolean scan = false;
        String hid = JInputBackend.hidDeviceSignature();
        if (hid != null) {
            if (!hid.equals(lastHidSignature)) {
                lastHidSignature = hid;
                followUpScans = FOLLOW_UP_SCANS;
                scan = true;
            } else if (followUpScans > 0) {
                followUpScans--;
                scan = true;
            }
        } else if (!hasActiveController && ++slowTicks % 5 == 0) {
            // No cheap way to notice changes, so look every few seconds while there is no controller
            scan = true;
        }
        if (scan) backgroundScan();
    }

    /** Runs on the scanner thread */
    private void backgroundScan() {
        try {
            JInputBackend.ScanResult result = JInputBackend.scanDirectInput();
            if (result.signature.equals(publishedSignature)) {
                JInputBackend.release(result);
                return;
            }
            publishedSignature = result.signature;
            JInputBackend.ScanResult replaced = pendingScan.getAndSet(result);
            if (replaced != null) JInputBackend.release(replaced);
        } catch (Throwable t) {
            Thumbstick.LOG.debug("Controller scan failed", t);
        }
    }

    /**
     * Looks for controllers right away. With background scanning this only hurries the next scan, otherwise it
     * scans synchronously.
     *
     * @param fresh enumerate devices again instead of reusing what the input library found earlier
     */
    public void scan(boolean fresh) {
        if (scanner != null) {
            scanner.execute(this::backgroundScan);
            return;
        }
        List<Device> found = Collections.emptyList();
        try {
            found = JInputBackend.scanDefault(fresh).devices;
        } catch (Throwable t) {
            Thumbstick.LOG.debug("JInput is unavailable", t);
        }
        if (found.isEmpty()) {
            try {
                found = LwjglBackend.scan();
            } catch (Throwable t) {
                Thumbstick.LOG.debug("LWJGL controllers are unavailable", t);
            }
        }
        lastRescan = System.currentTimeMillis();
        setDevices(found);
        selectPreferred();
    }

    /** Switches to a newer scan, keeping the active controller if it is still connected */
    private void adopt(JInputBackend.ScanResult scan) {
        JInputBackend.ScanResult old = currentScan;
        List<Device> oldDevices = devices;
        currentScan = scan;
        setDevices(scan.devices);
        if (active != null) {
            Device same = findSameDevice(active, oldDevices, scan.devices);
            if (same != null) {
                // Same controller with a new handle, the button state is kept so held buttons stay held
                active = same;
                mapping = new ControllerMapping.Resolved(MappingStore.getMapping(same), same);
                failedPolls = 0;
            } else disconnect();
        }
        if (active == null) selectPreferred();
        if (old != null && scanner != null) scanner.execute(() -> JInputBackend.release(old));
    }

    private void setDevices(List<Device> found) {
        List<String> previous = new ArrayList<>();
        for (Device d : devices) previous.add(d.getName());
        for (Device d : found) {
            if (!previous.remove(d.getName())) {
                Thumbstick.LOG.info("Found controller '{}' ({} buttons, {} axes, {} hats)", d.getName(), d.getButtonCount(),
                    d.getAxisCount(), d.getHatCount());
            }
        }
        devices = found;
    }

    /** Finds the device in a new scan, matching the nth of several controllers with the same name */
    private static Device findSameDevice(Device device, List<Device> oldList, List<Device> newList) {
        int occurrence = 0;
        for (Device other : oldList) {
            if (other == device) break;
            if (other.getName().equals(device.getName())) occurrence++;
        }
        for (Device d : newList) if (d.getName().equals(device.getName()) && occurrence-- == 0) return d;
        return null;
    }

    /** Applies ThumbstickConfig.selectedController, falling back to the first device */
    public void selectPreferred() {
        String preferred = ThumbstickConfig.selectedController;
        Device pick = null;
        if (!NONE.equals(preferred)) {
            for (Device d : devices) {
                if (d.getName().equals(preferred)) {
                    pick = d;
                    break;
                }
            }
            if (pick == null && !devices.isEmpty()) pick = devices.get(0);
        }
        String oldName = active == null ? null : active.getName();
        setActive(pick);
        if (pick != null && !pick.getName().equals(oldName)) {
            lostController = false;
            boolean unknown = !MappingStore.hasCustomMapping(pick)
                && MappingProfiles.detectFamily(pick.getName()) == MappingProfiles.Family.UNKNOWN;
            HudRenderer.INSTANCE.showToast(I18n.format("thumbstick.toast.connected"),
                unknown ? I18n.format("thumbstick.toast.unknown_layout") : pick.getName());
        }
    }

    private void setActive(Device device) {
        active = device;
        hasActiveController = device != null;
        reloadMapping();
    }

    /** Reads the mapping of the active device again, like after Controller Mapping saved one */
    public void reloadMapping() {
        mapping = active == null ? null : new ControllerMapping.Resolved(MappingStore.getMapping(active), active);
        for (boolean[] a : new boolean[][] { rawDown, latched, tickDown, suppressed, down, prevDown }) Arrays.fill(a, false);
        Arrays.fill(axes, 0F);
    }

    /** Called every frame */
    public void poll() {
        JInputBackend.ScanResult scan = pendingScan.getAndSet(null);
        if (scan != null) adopt(scan);

        if (mapping == null) {
            if (scanner == null && lostController && autoRescans < MAX_AUTO_RESCANS
                && System.currentTimeMillis() - lastRescan > RESCAN_INTERVAL_MS) {
                autoRescans++;
                scan(true);
            }
            return;
        }
        if (!active.poll()) {
            if (++failedPolls > 5) {
                disconnect();
                // A quick replug lists the same names, so the next scan must not be skipped as unchanged
                publishedSignature = null;
                selectPreferred();
            }
            return;
        }
        failedPolls = 0;

        for (GamepadAxis a : GamepadAxis.VALUES) axes[a.ordinal()] = mapping.getAxis(a);
        float activeThreshold = Math.max(ThumbstickConfig.deadzone, 0.3F);
        boolean leftActive = magnitude(GamepadAxis.LEFT_X, GamepadAxis.LEFT_Y) > activeThreshold;
        boolean rightActive = magnitude(GamepadAxis.RIGHT_X, GamepadAxis.RIGHT_Y) > activeThreshold;
        boolean newInput = leftActive && !leftStickWasActive || rightActive && !rightStickWasActive;
        leftStickWasActive = leftActive;
        rightStickWasActive = rightActive;
        for (GamepadButton b : GamepadButton.VALUES) {
            int i = b.ordinal();
            boolean isDown = b == GamepadButton.LEFT_TRIGGER ? axes[GamepadAxis.LEFT_TRIGGER.ordinal()] > ThumbstickConfig.triggerThreshold
                : b == GamepadButton.RIGHT_TRIGGER ? axes[GamepadAxis.RIGHT_TRIGGER.ordinal()] > ThumbstickConfig.triggerThreshold
                : mapping.isDown(b);
            rawDown[i] = isDown;
            if (isDown) {
                latched[i] = true;
                if (!prevRawDown[i]) newInput = true;
            }
            prevRawDown[i] = isDown;
        }
        if (newInput) usingController = true;
    }

    /** Drops the active controller after it stopped responding or vanished from a scan */
    private void disconnect() {
        Thumbstick.LOG.info("Controller '{}' disconnected", active.getName());
        HudRenderer.INSTANCE.showToast(I18n.format("thumbstick.toast.disconnected"), active.getName());
        List<Device> remaining = new ArrayList<>(devices);
        remaining.remove(active);
        devices = remaining;
        setActive(null);
        failedPolls = 0;
        lostController = true;
        autoRescans = 0;
        lastRescan = System.currentTimeMillis();
    }

    /** Called once at the start of every client tick */
    public void tick() {
        for (int i = 0; i < N; i++) {
            tickDown[i] = rawDown[i] || latched[i];
            latched[i] = false;
            if (suppressed[i] && !tickDown[i]) suppressed[i] = false;
            prevDown[i] = down[i];
            down[i] = tickDown[i] && !suppressed[i];
        }
    }

    /** Ignores every held button until released, so a press does not act in both contexts when a screen changes */
    public void suppressHeldButtons() {
        for (int i = 0; i < N; i++) {
            if (tickDown[i] || rawDown[i]) {
                suppressed[i] = true;
                down[i] = prevDown[i] = false;
            }
        }
    }

    public boolean isDown(GamepadButton b) { return b != null && down[b.ordinal()]; }

    public boolean isPressed(GamepadButton b) { return b != null && down[b.ordinal()] && !prevDown[b.ordinal()]; }

    public boolean isReleased(GamepadButton b) { return b != null && !down[b.ordinal()] && prevDown[b.ordinal()]; }

    /** The first button pressed this tick, or null */
    public GamepadButton getAnyPressed() {
        for (GamepadButton b : GamepadButton.VALUES) if (isPressed(b)) return b;
        return null;
    }

    /** Sticks with the radial dead zone applied as {x, y}, down is positive */
    public float[] getLeftStick() { return applyDeadzone(axes[GamepadAxis.LEFT_X.ordinal()], axes[GamepadAxis.LEFT_Y.ordinal()]); }

    public float[] getRightStick() { return applyDeadzone(axes[GamepadAxis.RIGHT_X.ordinal()], axes[GamepadAxis.RIGHT_Y.ordinal()]); }

    private static float[] applyDeadzone(float x, float y) {
        float deadzone = ThumbstickConfig.deadzone;
        float magnitude = (float) Math.sqrt(x * x + y * y);
        if (magnitude <= deadzone || magnitude == 0F) return new float[] { 0F, 0F };
        float scaled = (Math.min(magnitude, 1F) - deadzone) / (1F - deadzone);
        return new float[] { x / magnitude * scaled, y / magnitude * scaled };
    }

    private float magnitude(GamepadAxis x, GamepadAxis y) {
        float vx = axes[x.ordinal()], vy = axes[y.ordinal()];
        return (float) Math.sqrt(vx * vx + vy * vy);
    }

    /** True while the controller, rather than keyboard and mouse, is what the player uses */
    public boolean isInUse() { return mapping != null && usingController; }

    /** Called on mouse or keyboard use, hiding controller hints until the pad is touched again */
    public void markOtherInputUsed() { usingController = false; }
}
