package com.thumbstick.controller;

import java.util.Locale;

/**
 * Where a logical input reads from on a raw Device, written as text:
 *
 * <pre>
 * b:NAME        button
 * a:NAME        axis as is (-1..1)
 * a:NAME:inv    axis inverted
 * a:NAME:pos    positive half of an axis (0..1)
 * a:NAME:neg    negative half of an axis (0..1)
 * a:NAME:trig   full range trigger resting at -1 (0..1)
 * a:NAME:trigi  full range trigger resting at +1 (0..1)
 * h:NAME:up     hat direction (also down, left, right)
 * </pre>
 *
 * Candidates can be joined with | and the first one the device has is used.
 */
public final class InputSource {

    public static final InputSource NONE = new InputSource('n', -1, Mode.FULL);

    public enum Mode {
        FULL(""), INVERTED("inv"), POSITIVE("pos"), NEGATIVE("neg"), TRIGGER("trig"), TRIGGER_INVERTED("trigi"),
        UP("up"), DOWN("down"), LEFT("left"), RIGHT("right");

        final String id;

        Mode(String id) { this.id = id; }
    }

    /** b, a or h like in the spec, n for none */
    private final char kind;
    private final int index;
    private final Mode mode;

    private InputSource(char kind, int index, Mode mode) {
        this.kind = kind;
        this.index = index;
        this.mode = mode;
    }

    /** 0..1 for buttons, hats and half axes, -1..1 for full axes */
    public float read(Device d) {
        switch (kind) {
            case 'b': return d.isButtonDown(index) ? 1F : 0F;
            case 'a': {
                float v = d.getAxisValue(index);
                switch (mode) {
                    case INVERTED: return -v;
                    case POSITIVE: return Math.max(0F, v);
                    case NEGATIVE: return Math.max(0F, -v);
                    case TRIGGER: return clamp01((v + 1F) / 2F);
                    case TRIGGER_INVERTED: return clamp01((1F - v) / 2F);
                    default: return v;
                }
            }
            case 'h':
                switch (mode) {
                    case UP: return d.getHatY(index) < 0 ? 1F : 0F;
                    case DOWN: return d.getHatY(index) > 0 ? 1F : 0F;
                    case LEFT: return d.getHatX(index) < 0 ? 1F : 0F;
                    case RIGHT: return d.getHatX(index) > 0 ? 1F : 0F;
                    default: return 0F;
                }
            default: return 0F;
        }
    }

    private static float clamp01(float v) { return v < 0F ? 0F : Math.min(v, 1F); }

    /** Resolves a spec against a device, NONE if nothing matches */
    public static InputSource resolve(String spec, Device device) {
        if (spec == null) return NONE;
        for (String candidate : spec.split("\\|")) {
            InputSource source = resolveSingle(candidate.trim(), device);
            if (source != NONE) return source;
        }
        return NONE;
    }

    private static InputSource resolveSingle(String spec, Device d) {
        if (spec.length() < 3 || spec.charAt(1) != ':') return NONE;
        char type = Character.toLowerCase(spec.charAt(0));
        String rest = spec.substring(2);
        Mode mode = Mode.FULL;
        int colon = rest.lastIndexOf(':');
        if (colon >= 0) {
            String id = rest.substring(colon + 1).toLowerCase(Locale.ROOT);
            for (Mode m : Mode.values()) {
                if (m.id.equals(id)) {
                    mode = m;
                    rest = rest.substring(0, colon);
                    break;
                }
            }
        }
        switch (type) {
            case 'b':
                for (int i = 0; i < d.getButtonCount(); i++) if (d.getButtonName(i).equalsIgnoreCase(rest)) return new InputSource('b', i, Mode.FULL);
                return NONE;
            case 'a':
                for (int i = 0; i < d.getAxisCount(); i++) if (d.getAxisName(i).equalsIgnoreCase(rest)) return new InputSource('a', i, mode);
                return NONE;
            case 'h':
                if (mode.ordinal() < Mode.UP.ordinal()) return NONE;
                for (int i = 0; i < d.getHatCount(); i++) if (d.getHatName(i).equalsIgnoreCase(rest)) return new InputSource('h', i, mode);
                return NONE;
            default: return NONE;
        }
    }

    public static String button(String name) { return "b:" + name; }

    public static String axis(String name, Mode mode) { return mode == Mode.FULL ? "a:" + name : "a:" + name + ":" + mode.id; }

    public static String hat(String name, Mode direction) { return "h:" + name + ":" + direction.id; }
}
