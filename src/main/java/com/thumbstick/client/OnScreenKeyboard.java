package com.thumbstick.client;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiChat;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiSleepMP;
import net.minecraft.client.resources.I18n;

import org.lwjgl.input.Keyboard;

import com.thumbstick.ThumbstickConfig;
import com.thumbstick.binding.Action;
import com.thumbstick.controller.ControllerManager;
import com.thumbstick.util.Reflect;

/**
 * Keyboard drawn over chat while the controller is used. Keys go to the screen's keyTyped like real key presses, so
 * tab completion, commands and chat history behave as with a keyboard.
 */
public final class OnScreenKeyboard {
    private static final int HALF = 10, KEY_HEIGHT = 16, GAP = 2, PADDING = 4, ROW_HALVES = 22;
    private static final int REPEAT_DELAY = 8, REPEAT_INTERVAL = 2;
    private static final float STICK_THRESHOLD = 0.5F;
    private static final String[][] CHARACTERS = {
        { "1234567890-", "!@#$%^&*()_" },
        { "qwertyuiop/", "QWERTYUIOP?" },
        { "asdfghjkl;'", "ASDFGHJKL:\"" },
        { "zxcvbnm,.+=", "ZXCVBNM<>[]" } };

    private enum Special { NONE, SHIFT, TAB, SPACE, BACKSPACE, SEND }

    private static final class Key {

        final char normal, shifted;
        final Special special;
        final int x, width;

        Key(char normal, char shifted, Special special, int x, int width) {
            this.normal = normal;
            this.shifted = shifted;
            this.special = special;
            this.x = x;
            this.width = width;
        }

        Key(Special special, int x, int width) { this(' ', ' ', special, x, width); }

        String label(boolean shift) {
            return special == Special.NONE ? String.valueOf(shift ? shifted : normal)
                : I18n.format("thumbstick.keyboard." + special.name().toLowerCase(Locale.ROOT));
        }
    }

    private static final List<Key[]> ROWS = new ArrayList<>();

    static {
        for (String[] row : CHARACTERS) {
            Key[] keys = new Key[row[0].length()];
            for (int i = 0; i < keys.length; i++) keys[i] = new Key(row[0].charAt(i), row[1].charAt(i), Special.NONE, i * 2, 2);
            ROWS.add(keys);
        }
        ROWS.add(new Key[] { new Key(Special.SHIFT, 0, 4), new Key(Special.TAB, 4, 3), new Key(Special.SPACE, 7, 8),
            new Key(Special.BACKSPACE, 15, 3), new Key(Special.SEND, 18, 4) });
    }

    /** Inputs that repeat while held: the four directions, then type, backspace, space and the caret moves */
    private static final int UP = 0, DOWN = 1, LEFT = 2, RIGHT = 3, TYPE = 4, BACKSPACE = 5, SPACE = 6, CARET_LEFT = 7, CARET_RIGHT = 8;

    private final Minecraft mc = Minecraft.getMinecraft();
    private final ControllerManager controller = ControllerManager.INSTANCE;
    private final int[] heldTicks = new int[9];
    private int row = 1, column;
    private boolean shift;

    OnScreenKeyboard() {}

    /** Shown on chat while the controller is used, typing on a real keyboard hides it */
    public boolean isActive(GuiScreen screen) {
        return screen instanceof GuiChat && !(screen instanceof GuiSleepMP) && controller.isInUse();
    }

    void reset() {
        shift = false;
        Arrays.fill(heldTicks, 0);
    }

    void tick(GuiScreen screen) {
        if (pressed(Action.MENU_BACK)) {
            Reflect.keyTyped(screen, (char) 0, Keyboard.KEY_ESCAPE);
            return;
        }
        if (pressed(Action.MENU_RESUME)) {
            Reflect.keyTyped(screen, '\r', Keyboard.KEY_RETURN);
            return;
        }
        float[] stick = ThumbstickConfig.cursorStick == ThumbstickConfig.CursorStick.LEFT ? controller.getLeftStick() : controller.getRightStick();
        boolean horizontal = Math.abs(stick[0]) > Math.abs(stick[1]);
        if (fire(UP, down(Action.MENU_UP) || !horizontal && stick[1] < -STICK_THRESHOLD)) move(0, -1);
        if (fire(DOWN, down(Action.MENU_DOWN) || !horizontal && stick[1] > STICK_THRESHOLD)) move(0, 1);
        if (fire(LEFT, down(Action.MENU_LEFT) || horizontal && stick[0] < -STICK_THRESHOLD)) move(-1, 0);
        if (fire(RIGHT, down(Action.MENU_RIGHT) || horizontal && stick[0] > STICK_THRESHOLD)) move(1, 0);

        if (fire(TYPE, down(Action.MENU_CLICK))) press(screen, ROWS.get(row)[column]);
        else if (fire(BACKSPACE, down(Action.MENU_RIGHT_CLICK))) Reflect.keyTyped(screen, '\b', Keyboard.KEY_BACK);
        else if (fire(SPACE, down(Action.MENU_CLOSE_INVENTORY))) Reflect.keyTyped(screen, ' ', Keyboard.KEY_SPACE);
        else if (fire(CARET_LEFT, down(Action.MENU_PREVIOUS_TAB))) Reflect.keyTyped(screen, (char) 0, Keyboard.KEY_LEFT);
        else if (fire(CARET_RIGHT, down(Action.MENU_NEXT_TAB))) Reflect.keyTyped(screen, (char) 0, Keyboard.KEY_RIGHT);
    }

    private boolean down(Action a) { return controller.isDown(ThumbstickConfig.getBinding(a)); }

    private boolean pressed(Action a) { return controller.isPressed(ThumbstickConfig.getBinding(a)); }

    /** True on the tick an input goes down and then repeatedly while it is held */
    private boolean fire(int input, boolean down) {
        if (!down) {
            heldTicks[input] = 0;
            return false;
        }
        int held = heldTicks[input]++;
        return held == 0 || held >= REPEAT_DELAY && (held - REPEAT_DELAY) % REPEAT_INTERVAL == 0;
    }

    /** Left and right wrap around a row, up and down pick the key in the next row nearest to the current one */
    private void move(int dx, int dy) {
        Key[] keys = ROWS.get(row);
        if (dx != 0) {
            column = Math.floorMod(column + dx, keys.length);
            return;
        }
        int next = Math.max(0, Math.min(ROWS.size() - 1, row + dy));
        if (next == row) return;
        int center = keys[column].x * 2 + keys[column].width;
        Key[] target = ROWS.get(next);
        int best = 0;
        for (int i = 0; i < target.length; i++) {
            if (Math.abs(target[i].x * 2 + target[i].width - center) < Math.abs(target[best].x * 2 + target[best].width - center)) best = i;
        }
        row = next;
        column = best;
    }

    private void press(GuiScreen screen, Key key) {
        switch (key.special) {
            case SHIFT -> shift = !shift;
            case TAB -> Reflect.keyTyped(screen, '\t', Keyboard.KEY_TAB);
            case SPACE -> Reflect.keyTyped(screen, ' ', Keyboard.KEY_SPACE);
            case BACKSPACE -> Reflect.keyTyped(screen, '\b', Keyboard.KEY_BACK);
            case SEND -> Reflect.keyTyped(screen, '\r', Keyboard.KEY_RETURN);
            case NONE -> {
                Reflect.keyTyped(screen, shift ? key.shifted : key.normal, 0);
                shift = false;
            }
        }
    }

    /** Centered right above the chat input field */
    void draw(GuiScreen screen) {
        FontRenderer font = mc.fontRenderer;
        int width = ROW_HALVES * HALF - GAP, height = ROWS.size() * (KEY_HEIGHT + GAP) - GAP;
        int left = (screen.width - width) / 2, top = screen.height - 16 - PADDING - height;
        Gui.drawRect(left - PADDING, top - PADDING, left + width + PADDING, top + height + PADDING, 0xC0000000);
        for (int r = 0; r < ROWS.size(); r++) {
            Key[] keys = ROWS.get(r);
            for (int i = 0; i < keys.length; i++) {
                Key key = keys[i];
                int x0 = left + key.x * HALF, y0 = top + r * (KEY_HEIGHT + GAP), x1 = x0 + key.width * HALF - GAP;
                boolean selected = r == row && i == column, lit = key.special == Special.SHIFT && shift;
                Gui.drawRect(x0, y0, x1, y0 + KEY_HEIGHT, selected ? 0xFFE0E0E0 : lit ? 0xC0406090 : 0x80404040);
                String label = key.label(shift);
                int x = (x0 + x1 - font.getStringWidth(label) + 1) / 2, y = y0 + (KEY_HEIGHT - 8) / 2;
                if (selected) font.drawString(label, x, y, 0x202020);
                else font.drawStringWithShadow(label, x, y, 0xFFFFFF);
            }
        }
    }
}
