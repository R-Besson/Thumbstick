package com.thumbstick.controller;

import java.util.ArrayList;
import java.util.List;

import org.lwjgl.input.Controller;
import org.lwjgl.input.Controllers;

/**
 * Fallback on LWJGL's Controllers API, used when JInput is unusable, like on LWJGL 3 through a compatibility layer.
 * LWJGL 2 cannot rescan, so new controllers only show up after a restart.
 */
final class LwjglBackend {

    private LwjglBackend() {}

    static List<Device> scan() throws Exception {
        if (!Controllers.isCreated()) Controllers.create();
        List<Device> devices = new ArrayList<>();
        for (int i = 0; i < Controllers.getControllerCount(); i++) {
            Controller c = Controllers.getController(i);
            if (c.getButtonCount() < 4) continue;
            for (int axis = 0; axis < c.getAxisCount(); axis++) c.setDeadZone(axis, 0F);
            devices.add(new LwjglDevice(c));
        }
        return devices;
    }

    private static final class LwjglDevice implements Device {

        private final Controller c;

        LwjglDevice(Controller c) { this.c = c; }

        @Override public String getName() { return c.getName().trim(); }
        @Override public String getBackendId() { return "lwjgl"; }

        @Override
        public boolean poll() {
            c.poll();
            // LWJGL queues an event per change, the state is read directly so drop them
            Controllers.clearEvents();
            return true;
        }

        @Override public int getButtonCount() { return c.getButtonCount(); }
        @Override public String getButtonName(int i) { return String.valueOf(i); }
        @Override public boolean isButtonDown(int i) { return c.isButtonPressed(i); }
        @Override public int getAxisCount() { return c.getAxisCount(); }
        @Override public String getAxisName(int i) { return "axis" + i; }
        @Override public float getAxisValue(int i) { return c.getAxisValue(i); }
        @Override public int getHatCount() { return 1; }
        @Override public String getHatName(int i) { return "pov"; }
        @Override public int getHatX(int i) { return (int) Math.signum(c.getPovX()); }
        @Override public int getHatY(int i) { return (int) Math.signum(c.getPovY()); }
    }
}
