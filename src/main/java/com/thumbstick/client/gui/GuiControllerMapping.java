package com.thumbstick.client.gui;

import java.util.Locale;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.resources.I18n;

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
import com.thumbstick.util.Reflect;

/**
 * Wizard building a mapping for the active controller by asking for each input in turn and watching which raw
 * button, axis or hat changes. The menu cursor is off here since the current mapping may be wrong, so steps time out
 * on their own and the finished mapping is saved with the newly mapped A button.
 */
public class GuiControllerMapping extends GuiScreen implements MenuNavigator.CaptureScreen {

    private static final long STEP_TIMEOUT_MS = 10_000L, SETTLE_MS = 500L;
    private static final float AXIS_THRESHOLD = 0.6F;
    private static final int BACK = 0, SKIP = 1, SAVE = 2, RESET = 3, CANCEL = 4;

    private enum Phase {
        /** Waiting for the controller to be left alone to record its resting state */
        SETTLING,
        LISTENING,
        /** Waiting for the input just assigned to be released */
        RELEASING,
        FINISHED
    }

    private final GuiScreen parent;
    private final Device device;
    private final ControllerMapping mapping;

    private Phase phase = Phase.SETTLING;
    private int step;
    private long phaseStart = System.currentTimeMillis(), settleSince;
    private String lastAssigned;

    private boolean[] restButtons;
    private float[] restAxes, settleAxes;
    private int[] restHatX, restHatY;

    private ControllerMapping.Resolved finishedMapping;
    private boolean confirmWasDown = true, restartWasDown = true;

    public GuiControllerMapping(GuiScreen parent) {
        this.parent = parent;
        device = ControllerManager.INSTANCE.getActiveDevice();
        mapping = device == null ? new ControllerMapping() : new ControllerMapping(MappingStore.getMapping(device));
    }

    @Override
    public boolean isCapturingController() { return true; }

    @Override
    @SuppressWarnings("unchecked")
    public void initGui() {
        buttonList.clear();
        int left = width / 2 - 155, right = width / 2 + 5;
        buttonList.add(new GuiButton(BACK, left, height - 52, 150, 20, I18n.format("thumbstick.gui.mapping.back")));
        buttonList.add(new GuiButton(SKIP, right, height - 52, 150, 20, I18n.format("thumbstick.gui.mapping.skip")));
        buttonList.add(new GuiButton(SAVE, right, height - 52, 150, 20, I18n.format("thumbstick.gui.mapping.save")));
        buttonList.add(new GuiButton(RESET, left, height - 28, 150, 20, I18n.format("thumbstick.gui.mapping.reset")));
        buttonList.add(new GuiButton(CANCEL, right, height - 28, 150, 20, I18n.format("gui.cancel")));
        updateButtons();
    }

    private void updateButtons() {
        for (GuiButton b : Reflect.getButtons(this)) {
            if (b.id == SKIP) b.visible = phase != Phase.FINISHED;
            if (b.id == SAVE) b.visible = phase == Phase.FINISHED;
            if (b.id == BACK) b.enabled = step > 0;
            if (b.id != CANCEL) b.enabled &= device != null;
        }
    }

    private String currentKey() { return ControllerMapping.KEYS[step]; }

    private void setPhase(Phase newPhase) {
        phase = newPhase;
        phaseStart = System.currentTimeMillis();
        if (newPhase == Phase.FINISHED) {
            finishedMapping = new ControllerMapping.Resolved(mapping, device);
            confirmWasDown = restartWasDown = true;
        }
        updateButtons();
    }

    private void skip() {
        mapping.set(currentKey(), "");
        lastAssigned = null;
        advance();
    }

    private void advance() {
        if (step + 1 >= ControllerMapping.KEYS.length) setPhase(Phase.FINISHED);
        else {
            step++;
            setPhase(Phase.LISTENING);
        }
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        switch (button.id) {
            case BACK -> {
                if (phase != Phase.FINISHED) step = Math.max(0, step - 1);
                lastAssigned = null;
                setPhase(restButtons == null ? Phase.SETTLING : Phase.LISTENING);
            }
            case SKIP -> skip();
            case SAVE -> save();
            case RESET -> {
                MappingStore.resetMapping(device);
                ControllerManager.INSTANCE.reloadMapping();
                mc.displayGuiScreen(parent);
            }
            case CANCEL -> mc.displayGuiScreen(parent);
            default -> {}
        }
    }

    private void save() {
        MappingStore.saveMapping(device, mapping);
        ControllerManager.INSTANCE.reloadMapping();
        mc.displayGuiScreen(parent);
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) {
        if (keyCode == Keyboard.KEY_ESCAPE) mc.displayGuiScreen(parent);
    }

    @Override
    public void updateScreen() {
        if (device == null) return;
        long now = System.currentTimeMillis();
        switch (phase) {
            case SETTLING -> settle(now);
            case LISTENING -> {
                String detected = detect(currentKey());
                if (detected != null) {
                    mapping.set(currentKey(), detected);
                    lastAssigned = detected;
                    setPhase(Phase.RELEASING);
                } else if (now - phaseStart > STEP_TIMEOUT_MS) skip();
            }
            case RELEASING -> {
                if (isAtRest()) advance();
            }
            case FINISHED -> {
                boolean confirm = finishedMapping.isDown(GamepadButton.A), restart = finishedMapping.isDown(GamepadButton.B);
                if (confirm && !confirmWasDown) save();
                else if (restart && !restartWasDown) {
                    step = 0;
                    lastAssigned = null;
                    setPhase(Phase.SETTLING);
                } else {
                    confirmWasDown = confirm;
                    restartWasDown = restart;
                }
            }
        }
    }

    /** Records the resting state once no button is held and the axes stopped moving */
    private void settle(long now) {
        float[] axes = new float[device.getAxisCount()];
        for (int i = 0; i < axes.length; i++) axes[i] = device.getAxisValue(i);
        boolean stable = settleAxes != null;
        for (int i = 0; i < device.getButtonCount(); i++) if (device.isButtonDown(i)) stable = false;
        for (int i = 0; stable && i < axes.length; i++) if (Math.abs(axes[i] - settleAxes[i]) > 0.05F) stable = false;
        if (!stable) {
            settleAxes = axes;
            settleSince = now;
            return;
        }
        if (now - settleSince < SETTLE_MS) return;
        restAxes = axes;
        restButtons = new boolean[device.getButtonCount()];
        restHatX = new int[device.getHatCount()];
        restHatY = new int[device.getHatCount()];
        for (int i = 0; i < restHatX.length; i++) {
            restHatX[i] = device.getHatX(i);
            restHatY[i] = device.getHatY(i);
        }
        setPhase(Phase.LISTENING);
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

    private void text(String text, int y, int color) { drawCenteredString(fontRendererObj, text, width / 2, y, color); }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();
        text(I18n.format("thumbstick.gui.mapping.title"), 15, 0xFFFFFF);
        if (device == null) {
            text(I18n.format("thumbstick.gui.controller.none"), height / 2 - 20, 0xFF5555);
            super.drawScreen(mouseX, mouseY, partialTicks);
            return;
        }
        text(device.getName(), 27, 0xA0A0A0);
        int y = height / 2 - 50;
        switch (phase) {
            case SETTLING -> text(I18n.format("thumbstick.gui.mapping.settle"), y + 20, 0xFFFF55);
            case LISTENING, RELEASING -> {
                text(I18n.format("thumbstick.gui.mapping.step", step + 1, ControllerMapping.KEYS.length), y, 0xA0A0A0);
                drawPrompt(currentKey(), y + 18);
                if (phase == Phase.RELEASING) text(I18n.format("thumbstick.gui.mapping.release"), y + 40, 0x55FF55);
                else {
                    long remaining = (STEP_TIMEOUT_MS - (System.currentTimeMillis() - phaseStart) + 999) / 1000;
                    text(I18n.format("thumbstick.gui.mapping.timeout", Math.max(0, remaining)), y + 40, 0x808080);
                }
            }
            case FINISHED -> {
                text(I18n.format("thumbstick.gui.mapping.finished"), y + 10, 0x55FF55);
                text(I18n.format("thumbstick.gui.mapping.finished_hint"), y + 24, 0xFFFFFF);
            }
        }
        if (lastAssigned != null && phase != Phase.FINISHED) text(I18n.format("thumbstick.gui.mapping.assigned", lastAssigned), y + 54, 0xA0A0A0);
        drawLiveInput(height - 76);
        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    private void drawPrompt(String key, int y) {
        GamepadButton button = GamepadButton.byName(key);
        if (button == null) {
            text(I18n.format("thumbstick.gui.mapping.prompt." + key.toLowerCase(Locale.ROOT)), y, 0xFFFF55);
            return;
        }
        String text = I18n.format("thumbstick.gui.mapping.press", ButtonRenderer.getName(button));
        int x = width / 2 - (fontRendererObj.getStringWidth(text) + ButtonRenderer.SIZE + 4) / 2;
        ButtonRenderer.draw(mc, button, x, y - 3);
        fontRendererObj.drawStringWithShadow(text, x + ButtonRenderer.SIZE + 4, y, 0xFFFF55);
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
}
