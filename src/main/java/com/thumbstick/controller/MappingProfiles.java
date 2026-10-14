package com.thumbstick.controller;

import java.util.Locale;

/**
 * Built in mappings for common controllers, picked by device name, using the element names JInput reports
 * (DirectInput on Windows, evdev on Linux). Anything else gets the Xbox layout and can use the mapping wizard.
 */
public final class MappingProfiles {

    public enum Family { XBOX, PLAYSTATION, SWITCH, UNKNOWN }

    private MappingProfiles() {}

    private static boolean has(String name, String... parts) {
        for (String p : parts) if (name.contains(p)) return true;
        return false;
    }

    public static Family detectFamily(String deviceName) {
        String n = deviceName.toLowerCase(Locale.ROOT);
        if (has(n, "xbox", "x-box", "xinput") || n.startsWith("controller (")) return Family.XBOX;
        if (has(n, "wireless controller", "dualshock", "dualsense", "playstation", "ps3", "ps4", "ps5", "sony")) return Family.PLAYSTATION;
        if (has(n, "pro controller", "switch", "joy-con")) return Family.SWITCH;
        return Family.UNKNOWN;
    }

    public static ControllerMapping forDevice(Device device) {
        // GLFW gamepad layout, which LWJGL 3 compatibility layers expose
        if ("lwjgl".equals(device.getBackendId())) return of(false, "A=b:0", "B=b:1", "X=b:2", "Y=b:3", "LEFT_BUMPER=b:4",
            "RIGHT_BUMPER=b:5", "BACK=b:6", "START=b:7", "GUIDE=b:8", "LEFT_STICK=b:9", "RIGHT_STICK=b:10",
            "DPAD_UP=b:11|h:pov:up", "DPAD_RIGHT=b:12|h:pov:right", "DPAD_DOWN=b:13|h:pov:down", "DPAD_LEFT=b:14|h:pov:left",
            "LEFT_X=a:axis0", "LEFT_Y=a:axis1", "RIGHT_X=a:axis2", "RIGHT_Y=a:axis3", "LEFT_TRIGGER=a:axis4:trig",
            "RIGHT_TRIGGER=a:axis5:trig");
        // Linux evdev gamepads, the xpad, Sony and Nintendo drivers share button codes
        if (!JInputBackend.isWindows()) return of(true, "A=b:A|b:0", "B=b:B|b:1", "X=b:X|b:2", "Y=b:Y|b:3",
            "LEFT_BUMPER=b:Left Thumb|b:4", "RIGHT_BUMPER=b:Right Thumb|b:5", "BACK=b:Select|b:6", "START=b:Start|b:7",
            "GUIDE=b:Mode|b:8", "LEFT_STICK=b:Left Thumb 3|b:9", "RIGHT_STICK=b:Right Thumb 3|b:10", "LEFT_X=a:x",
            "LEFT_Y=a:y", "RIGHT_X=a:rx", "RIGHT_Y=a:ry", "LEFT_TRIGGER=a:z:trig|b:Left Thumb 2",
            "RIGHT_TRIGGER=a:rz:trig|b:Right Thumb 2");
        return switch (detectFamily(device.getName())) {
            // DualShock 4 and DualSense through DirectInput
            case PLAYSTATION -> of(true, "A=b:1", "B=b:2", "X=b:0", "Y=b:3", "LEFT_BUMPER=b:4", "RIGHT_BUMPER=b:5",
                "BACK=b:8", "START=b:9", "LEFT_STICK=b:10", "RIGHT_STICK=b:11", "GUIDE=b:12", "LEFT_X=a:x", "LEFT_Y=a:y",
                "RIGHT_X=a:z", "RIGHT_Y=a:rz", "LEFT_TRIGGER=a:rx:trig|b:6", "RIGHT_TRIGGER=a:ry:trig|b:7");
            // Switch Pro Controller through DirectInput, positions follow the Xbox layout (A at the bottom)
            case SWITCH -> of(true, "A=b:0", "B=b:1", "X=b:2", "Y=b:3", "LEFT_BUMPER=b:4", "RIGHT_BUMPER=b:5", "BACK=b:8",
                "START=b:9", "LEFT_STICK=b:10", "RIGHT_STICK=b:11", "GUIDE=b:12", "LEFT_X=a:x", "LEFT_Y=a:y",
                "RIGHT_X=a:z|a:rx", "RIGHT_Y=a:rz|a:ry", "LEFT_TRIGGER=b:6", "RIGHT_TRIGGER=b:7");
            // Xbox controllers through DirectInput, both triggers share the Z axis
            default -> of(true, "A=b:0", "B=b:1", "X=b:2", "Y=b:3", "LEFT_BUMPER=b:4", "RIGHT_BUMPER=b:5", "BACK=b:6",
                "START=b:7", "LEFT_STICK=b:8", "RIGHT_STICK=b:9", "GUIDE=", "LEFT_X=a:x", "LEFT_Y=a:y", "RIGHT_X=a:rx",
                "RIGHT_Y=a:ry", "LEFT_TRIGGER=a:z:pos", "RIGHT_TRIGGER=a:z:neg");
        };
    }

    /** Builds a mapping from KEY=source pairs, optionally with the dpad on hat "pov" */
    private static ControllerMapping of(boolean hatDpad, String... pairs) {
        ControllerMapping m = new ControllerMapping();
        for (String p : pairs) m.set(p.substring(0, p.indexOf('=')), p.substring(p.indexOf('=') + 1));
        if (hatDpad) for (String dir : new String[] { "UP", "DOWN", "LEFT", "RIGHT" }) m.set("DPAD_" + dir, "h:pov:" + dir.toLowerCase(Locale.ROOT));
        return m;
    }
}
