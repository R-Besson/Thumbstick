package com.thumbstick.controller;

import java.util.HashMap;
import java.util.Map;

/** Maps logical inputs (GamepadButton and GamepadAxis names) to InputSource specs */
public final class ControllerMapping {

    /** Logical inputs in the order the mapping wizard asks for them */
    public static final String[] KEYS = { "A", "B", "X", "Y", "LEFT_BUMPER", "RIGHT_BUMPER", "LEFT_TRIGGER",
        "RIGHT_TRIGGER", "BACK", "START", "GUIDE", "LEFT_STICK", "RIGHT_STICK", "DPAD_UP", "DPAD_DOWN", "DPAD_LEFT",
        "DPAD_RIGHT", "LEFT_X", "LEFT_Y", "RIGHT_X", "RIGHT_Y" };

    private final Map<String, String> sources = new HashMap<>();

    public ControllerMapping() {}

    public ControllerMapping(ControllerMapping other) { sources.putAll(other.sources); }

    public String get(String key) { return sources.getOrDefault(key, ""); }

    public ControllerMapping set(String key, String source) {
        sources.put(key, source == null ? "" : source);
        return this;
    }

    /** A mapping resolved against one device, ready to read values with */
    public static final class Resolved {

        private final Device device;
        private final InputSource[] buttons = new InputSource[GamepadButton.COUNT], axes = new InputSource[GamepadAxis.COUNT];

        public Resolved(ControllerMapping mapping, Device device) {
            this.device = device;
            for (GamepadButton b : GamepadButton.VALUES) {
                buttons[b.ordinal()] = b.isTrigger() ? InputSource.NONE : InputSource.resolve(mapping.get(b.name()), device);
            }
            for (GamepadAxis a : GamepadAxis.VALUES) axes[a.ordinal()] = InputSource.resolve(mapping.get(a.name()), device);
        }

        public boolean isDown(GamepadButton button) { return buttons[button.ordinal()].read(device) > 0.5F; }

        public float getAxis(GamepadAxis axis) {
            float v = axes[axis.ordinal()].read(device);
            return axis.isTrigger() ? Math.max(0F, v) : v;
        }
    }
}
