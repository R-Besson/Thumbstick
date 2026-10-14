package com.thumbstick.controller;

/** A raw input device. Inputs are read by index and have names that stay stable across restarts for mappings */
public interface Device {

    String getName();

    /** "jinput" or "lwjgl", built in mappings differ per backend since element names do */
    String getBackendId();

    /** Refreshes the state, false once disconnected */
    boolean poll();

    int getButtonCount();
    String getButtonName(int index);
    boolean isButtonDown(int index);

    int getAxisCount();
    String getAxisName(int index);
    /** From -1 to 1 */
    float getAxisValue(int index);

    int getHatCount();
    String getHatName(int index);
    /** -1 left, 0 or 1 right */
    int getHatX(int index);
    /** -1 up, 0 or 1 down */
    int getHatY(int index);
}
