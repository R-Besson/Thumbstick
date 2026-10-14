package com.thumbstick.controller;

/** Sticks range from -1 to 1 (right and down positive), triggers from 0 (released) to 1 */
public enum GamepadAxis {

    LEFT_X, LEFT_Y, RIGHT_X, RIGHT_Y, LEFT_TRIGGER, RIGHT_TRIGGER;

    public static final GamepadAxis[] VALUES = values();
    public static final int COUNT = VALUES.length;

    public boolean isTrigger() { return this == LEFT_TRIGGER || this == RIGHT_TRIGGER; }
}
