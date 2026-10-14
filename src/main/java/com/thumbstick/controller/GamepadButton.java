package com.thumbstick.controller;

import java.util.Locale;

/** Logical buttons laid out like an Xbox pad. The ordinal is the icon column in buttons.png, keep the order */
public enum GamepadButton {

    A, B, X, Y, BACK, GUIDE, START, LEFT_STICK, RIGHT_STICK, LEFT_BUMPER, RIGHT_BUMPER, LEFT_TRIGGER, RIGHT_TRIGGER,
    DPAD_UP, DPAD_DOWN, DPAD_LEFT, DPAD_RIGHT;

    public static final GamepadButton[] VALUES = values();
    public static final int COUNT = VALUES.length;

    /** Triggers are read from an axis and become a button past the trigger threshold */
    public boolean isTrigger() { return this == LEFT_TRIGGER || this == RIGHT_TRIGGER; }

    public String getTranslationKey() { return "thumbstick.button." + name().toLowerCase(Locale.ROOT); }

    public static GamepadButton byName(String name) {
        if (name != null) for (GamepadButton b : VALUES) if (b.name().equalsIgnoreCase(name.trim())) return b;
        return null;
    }
}
