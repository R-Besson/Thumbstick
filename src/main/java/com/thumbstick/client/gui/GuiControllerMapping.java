package com.thumbstick.client.gui;

import java.util.Locale;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiListExtended;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.resources.I18n;
import net.minecraft.util.EnumChatFormatting;

import org.lwjgl.input.Keyboard;

import com.thumbstick.client.ButtonRenderer;
import com.thumbstick.client.MenuNavigator;
import com.thumbstick.controller.ControllerManager;
import com.thumbstick.controller.ControllerMapping;
import com.thumbstick.controller.Device;
import com.thumbstick.controller.GamepadButton;
import com.thumbstick.controller.InputSource;
import com.thumbstick.controller.InputSource.Mode;
import com.thumbstick.controller.MappingStore;

/**
 * Lists every logical input of the active controller with the raw button, axis or hat it reads from. Clicking one
 * listens for that input alone by watching which raw input leaves its resting state. Changes are saved right away.
 */
public class GuiControllerMapping extends GuiScreen implements MenuNavigator.CaptureScreen {

    private static final long LISTEN_TIMEOUT_MS = 10_000L, SETTLE_MS = 250L;
    private static final float AXIS_THRESHOLD = 0.6F;
    private static final int RESET = 0, DONE = 1;

    private enum Phase {
        /** Recording the resting state for a moment, so the press that clicked the row is not taken */
        SETTLING,
        LISTENING,
        /** Waiting for the input just assigned to be released */
        RELEASING
    }

    private final GuiScreen parent;
    private final Device device;
    private ControllerMapping mapping;
    private MappingList list;

    /** The input being listened for, null when idle */
    private String listening;
    private Phase phase;
    private long listenStart;

    private boolean[] restButtons;
    private float[] restAxes;
    private int[] restHatX, restHatY;

    public GuiControllerMapping(GuiScreen parent) {
        this.parent = parent;
        device = ControllerManager.INSTANCE.getActiveDevice();
        mapping = device == null ? new ControllerMapping() : new ControllerMapping(MappingStore.getMapping(device));
    }

    @Override
    public boolean isCapturingController() { return listening != null; }

    @Override
    @SuppressWarnings("unchecked")
    public void initGui() {
        list = new MappingList();
        buttonList.clear();
        GuiButton reset = new GuiButton(RESET, width / 2 - 155, height - 29, 150, 20, I18n.format("thumbstick.gui.mapping.reset"));
        reset.enabled = device != null;
        buttonList.add(reset);
        buttonList.add(new GuiButton(DONE, width / 2 + 5, height - 29, 150, 20, I18n.format("gui.done")));
    }

    private void listen(String key) {
        listening = key;
        phase = Phase.SETTLING;
        listenStart = System.currentTimeMillis();
        // The press that clicked the row must not leave a menu click held
        ControllerManager.INSTANCE.suppressHeldButtons();
    }

    private void assign(String key, String source) {
        mapping.set(key, source);
        MappingStore.saveMapping(device, mapping);
        ControllerManager.INSTANCE.reloadMapping();
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        if (button.id == RESET) {
            MappingStore.resetMapping(device);
            ControllerManager.INSTANCE.reloadMapping();
            mapping = new ControllerMapping(MappingStore.getMapping(device));
        } else if (button.id == DONE) mc.displayGuiScreen(parent);
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) {
        if (listening != null) listening = null;
        else if (mouseButton != 0 || !list.func_148179_a(mouseX, mouseY, mouseButton)) super.mouseClicked(mouseX, mouseY, mouseButton);
    }

    @Override
    protected void mouseMovedOrUp(int mouseX, int mouseY, int state) {
        if (state != 0 || !list.func_148181_b(mouseX, mouseY, state)) super.mouseMovedOrUp(mouseX, mouseY, state);
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) {
        if (listening == null) super.keyTyped(typedChar, keyCode);
        else if (keyCode == Keyboard.KEY_ESCAPE) listening = null;
    }

    @Override
    public void updateScreen() {
        if (listening == null || device == null) return;
        long now = System.currentTimeMillis();
        switch (phase) {
            case SETTLING -> settle(now);
            case LISTENING -> {
                // Inputs held since the click count as resting until let go
                for (int i = 0; i < restButtons.length; i++) restButtons[i] &= device.isButtonDown(i);
                for (int i = 0; i < restHatX.length; i++) {
                    if (device.getHatX(i) == 0 && device.getHatY(i) == 0) restHatX[i] = restHatY[i] = 0;
                }
                String detected = detect(listening);
                if (detected != null) {
                    assign(listening, detected);
                    phase = Phase.RELEASING;
                }
            }
            case RELEASING -> {
                if (isAtRest()) listening = null;
            }
        }
        if (now - listenStart > LISTEN_TIMEOUT_MS) listening = null;
    }

    /** Takes the current state as resting, whatever is held */
    private void settle(long now) {
        restAxes = new float[device.getAxisCount()];
        for (int i = 0; i < restAxes.length; i++) restAxes[i] = device.getAxisValue(i);
        restButtons = new boolean[device.getButtonCount()];
        for (int i = 0; i < restButtons.length; i++) restButtons[i] = device.isButtonDown(i);
        restHatX = new int[device.getHatCount()];
        restHatY = new int[device.getHatCount()];
        for (int i = 0; i < restHatX.length; i++) {
            restHatX[i] = device.getHatX(i);
            restHatY[i] = device.getHatY(i);
        }
        if (now - listenStart < SETTLE_MS) return;
        phase = Phase.LISTENING;
        listenStart = now;
    }

    private boolean isAtRest() {
        for (int i = 0; i < device.getButtonCount(); i++) if (device.isButtonDown(i) != restButtons[i]) return false;
        for (int i = 0; i < device.getAxisCount(); i++) if (Math.abs(device.getAxisValue(i) - restAxes[i]) > 0.3F) return false;
        for (int i = 0; i < device.getHatCount(); i++) if (device.getHatX(i) != restHatX[i] || device.getHatY(i) != restHatY[i]) return false;
        return true;
    }

    private String detect(String key) {
        String button = null, hat = null;
        for (int i = 0; i < device.getButtonCount() && button == null; i++) {
            if (device.isButtonDown(i) && !restButtons[i]) button = InputSource.button(device.getButtonName(i));
        }
        for (int i = 0; i < device.getHatCount() && hat == null; i++) {
            int x = device.getHatX(i), y = device.getHatY(i);
            if (y != restHatY[i]) hat = InputSource.hat(device.getHatName(i), y < 0 ? Mode.UP : Mode.DOWN);
            else if (x != restHatX[i]) hat = InputSource.hat(device.getHatName(i), x < 0 ? Mode.LEFT : Mode.RIGHT);
        }
        int axis = -1;
        float bestDelta = AXIS_THRESHOLD;
        for (int i = 0; i < device.getAxisCount(); i++) {
            float delta = Math.abs(device.getAxisValue(i) - restAxes[i]);
            if (delta > bestDelta) {
                bestDelta = delta;
                axis = i;
            }
        }
        if (key.endsWith("_X") || key.endsWith("_Y")) {
            if (axis < 0) return null;
            return InputSource.axis(device.getAxisName(axis), device.getAxisValue(axis) > restAxes[axis] ? Mode.FULL : Mode.INVERTED);
        }
        if (key.endsWith("_TRIGGER")) return axis >= 0 ? triggerSource(axis) : button != null ? button : hat;
        if (key.startsWith("DPAD_")) return hat != null ? hat : button != null ? button : axis >= 0 ? halfAxisSource(axis) : null;
        return button != null ? button : hat != null ? hat : axis >= 0 ? triggerSource(axis) : null;
    }

    /** An axis used as an analog trigger: full range when it rests at an end, otherwise one half */
    private String triggerSource(int axis) {
        float rest = restAxes[axis], value = device.getAxisValue(axis);
        if (rest < -0.5F && value > rest) return InputSource.axis(device.getAxisName(axis), Mode.TRIGGER);
        if (rest > 0.5F && value < rest) return InputSource.axis(device.getAxisName(axis), Mode.TRIGGER_INVERTED);
        return halfAxisSource(axis);
    }

    private String halfAxisSource(int axis) {
        return InputSource.axis(device.getAxisName(axis), device.getAxisValue(axis) > restAxes[axis] ? Mode.POSITIVE : Mode.NEGATIVE);
    }

    /** The first candidate of the spec this device has, since built in profiles list several */
    private String describe(String spec) {
        for (String candidate : spec.split("\\|")) if (InputSource.resolve(candidate.trim(), device) != InputSource.NONE) return candidate.trim();
        return null;
    }

    private static String label(String key) {
        GamepadButton button = GamepadButton.byName(key);
        return button != null ? ButtonRenderer.getName(button) : I18n.format("thumbstick.gui.mapping.axis." + key.toLowerCase(Locale.ROOT));
    }

    private void text(String text, int y, int color) { drawCenteredString(fontRendererObj, text, width / 2, y, color); }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();
        if (device == null) {
            text(I18n.format("thumbstick.gui.mapping.title"), 8, 0xFFFFFF);
            text(I18n.format("thumbstick.gui.controller.none"), height / 2 - 20, 0xFF5555);
            super.drawScreen(mouseX, mouseY, partialTicks);
            return;
        }
        list.drawScreen(mouseX, mouseY, partialTicks);
        text(I18n.format("thumbstick.gui.mapping.title") + EnumChatFormatting.GRAY + " (" + device.getName() + ")", 8, 0xFFFFFF);
        text(hint(), 19, listening != null ? 0xFFFF55 : 0xA0A0A0);
        drawLiveInput(height - 56);
        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    private String hint() {
        if (listening == null) return I18n.format("thumbstick.gui.mapping.help");
        return switch (phase) {
            case RELEASING -> I18n.format("thumbstick.gui.mapping.release");
            case SETTLING, LISTENING -> {
                GamepadButton button = GamepadButton.byName(listening);
                String what = button != null ? I18n.format("thumbstick.gui.mapping.press", ButtonRenderer.getName(button))
                    : I18n.format("thumbstick.gui.mapping.prompt." + listening.toLowerCase(Locale.ROOT));
                long remaining = (LISTEN_TIMEOUT_MS - (System.currentTimeMillis() - listenStart) + 999) / 1000;
                yield I18n.format("thumbstick.gui.mapping.listening", what, Math.max(0, remaining));
            }
        };
    }

    /** Raw values, handy to see whether the controller is read at all */
    private void drawLiveInput(int y) {
        StringBuilder buttons = new StringBuilder(), axes = new StringBuilder();
        for (int i = 0; i < device.getButtonCount(); i++) if (device.isButtonDown(i)) buttons.append(device.getButtonName(i)).append(' ');
        for (int i = 0; i < device.getAxisCount(); i++) {
            axes.append(device.getAxisName(i)).append('=').append(String.format(Locale.ROOT, "%.1f", device.getAxisValue(i))).append(' ');
        }
        for (int i = 0; i < device.getHatCount(); i++) {
            axes.append(device.getHatName(i)).append('=').append(device.getHatX(i)).append(',').append(device.getHatY(i)).append(' ');
        }
        text(fontRendererObj.trimStringToWidth(I18n.format("thumbstick.gui.mapping.live_buttons", buttons.toString().trim()), width - 20), y, 0x808080);
        text(fontRendererObj.trimStringToWidth(axes.toString().trim(), width - 20), y + 10, 0x808080);
    }

    private class MappingList extends GuiListExtended {

        private final MappingEntry[] entries = new MappingEntry[ControllerMapping.KEYS.length];

        MappingList() {
            super(GuiControllerMapping.this.mc, GuiControllerMapping.this.width, GuiControllerMapping.this.height, 32,
                GuiControllerMapping.this.height - 60, 20);
            for (int i = 0; i < entries.length; i++) entries[i] = new MappingEntry(ControllerMapping.KEYS[i]);
        }

        @Override protected int getSize() { return entries.length; }
        @Override public IGuiListEntry getListEntry(int index) { return entries[index]; }
        @Override public int getListWidth() { return 310; }
        @Override protected int getScrollBarX() { return width / 2 + 160; }
    }

    private class MappingEntry implements GuiListExtended.IGuiListEntry {

        private final String key;
        private final GamepadButton button;
        private final GuiButton sourceButton = new GuiButton(0, 0, 0, 105, 18, ""), clearButton;

        MappingEntry(String key) {
            this.key = key;
            button = GamepadButton.byName(key);
            clearButton = new GuiButton(0, 0, 0, 50, 18, I18n.format("thumbstick.gui.clear"));
        }

        @Override
        public void drawEntry(int index, int x, int y, int listWidth, int slotHeight, Tessellator t, int mouseX, int mouseY, boolean selected) {
            int textX = x;
            if (button != null) {
                ButtonRenderer.draw(mc, button, x, y + (slotHeight - ButtonRenderer.SIZE) / 2 - 1);
                textX += ButtonRenderer.SIZE + 4;
            }
            fontRendererObj.drawString(fontRendererObj.trimStringToWidth(label(key), x + 140 - textX), textX, y + slotHeight / 2 - fontRendererObj.FONT_HEIGHT / 2, 0xFFFFFF);
            String source = describe(mapping.get(key));
            sourceButton.xPosition = x + 145;
            sourceButton.yPosition = y;
            sourceButton.displayString = fontRendererObj.trimStringToWidth(key.equals(listening)
                ? EnumChatFormatting.WHITE + "> " + EnumChatFormatting.YELLOW + I18n.format("thumbstick.gui.press_button") + EnumChatFormatting.WHITE + " <"
                : source == null ? EnumChatFormatting.GRAY + I18n.format("thumbstick.button.none") : source, 100);
            sourceButton.drawButton(mc, mouseX, mouseY);
            clearButton.xPosition = x + 255;
            clearButton.yPosition = y;
            clearButton.enabled = !mapping.get(key).isEmpty();
            clearButton.drawButton(mc, mouseX, mouseY);
        }

        @Override
        public boolean mousePressed(int index, int x, int y, int event, int relX, int relY) {
            if (sourceButton.mousePressed(mc, x, y)) {
                sourceButton.func_146113_a(mc.getSoundHandler());
                listen(key);
                return true;
            }
            if (clearButton.mousePressed(mc, x, y)) {
                clearButton.func_146113_a(mc.getSoundHandler());
                assign(key, "");
                return true;
            }
            return false;
        }

        @Override
        public void mouseReleased(int index, int x, int y, int event, int relX, int relY) {
            sourceButton.mouseReleased(x, y);
            clearButton.mouseReleased(x, y);
        }
    }
}
