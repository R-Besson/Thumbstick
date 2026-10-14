package com.thumbstick.client.gui;

import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.resources.I18n;

import org.lwjgl.opengl.GL11;

import com.thumbstick.ThumbstickConfig;
import com.thumbstick.controller.ControllerManager;
import com.thumbstick.controller.Device;
import com.thumbstick.util.Reflect;

public class GuiControllerSettings extends GuiScreen {

    // Button ids follow the layout order, two per row
    private static final int INVERT_LOOK = 0, ANALOG_MOVEMENT = 1, SNEAK_MODE = 2, CURSOR_SNAPPING = 3, BUTTON_ICONS = 4,
        SHOW_HINTS = 5, CURSOR_STICK = 6, CURSOR_STYLE = 7, BINDINGS = 8, MAPPING = 9, RESCAN = 10, DONE = 11, CONTROLLER = 12;

    private final GuiScreen parent;
    private GuiButton controllerButton;

    public GuiControllerSettings(GuiScreen parent) { this.parent = parent; }

    @Override
    @SuppressWarnings("unchecked")
    public void initGui() {
        buttonList.clear();
        int left = width / 2 - 155, right = width / 2 + 5, top = height / 6 - 18;
        buttonList.add(controllerButton = new GuiButton(CONTROLLER, left, top, 310, 20, ""));
        buttonList.add(new Slider(100, left, top + 24, "thumbstick.option.look_speed_x", 0.1F, 3F, 0.05F, ThumbstickConfig.lookSpeedX, v -> ThumbstickConfig.lookSpeedX = v));
        buttonList.add(new Slider(101, right, top + 24, "thumbstick.option.look_speed_y", 0.1F, 3F, 0.05F, ThumbstickConfig.lookSpeedY, v -> ThumbstickConfig.lookSpeedY = v));
        buttonList.add(new Slider(102, left, top + 48, "thumbstick.option.cursor_speed", 0.1F, 3F, 0.05F, ThumbstickConfig.cursorSpeed, v -> ThumbstickConfig.cursorSpeed = v));
        buttonList.add(new Slider(103, right, top + 48, "thumbstick.option.deadzone", 0F, 0.6F, 0.01F, ThumbstickConfig.deadzone, v -> ThumbstickConfig.deadzone = v));
        for (int id = 0; id <= DONE; id++) buttonList.add(new GuiButton(id, id % 2 == 0 ? left : right, top + 72 + id / 2 * 24, 150, 20, ""));
        updateLabels();
    }

    private void updateLabels() {
        Device active = ControllerManager.INSTANCE.getActiveDevice();
        String name = active != null ? active.getName()
            : I18n.format(ControllerManager.NONE.equals(ThumbstickConfig.selectedController) ? "thumbstick.gui.controller.disabled" : "thumbstick.gui.controller.none");
        controllerButton.displayString = I18n.format("thumbstick.gui.controller", name);
        String[] labels = { toggle("invert_look", ThumbstickConfig.invertLook), toggle("analog_movement", ThumbstickConfig.analogMovement),
            label("sneak", ThumbstickConfig.toggleSneak ? "toggle" : "hold"), toggle("cursor_snapping", ThumbstickConfig.cursorSnapping),
            option("button_icons", ThumbstickConfig.buttonIcons), I18n.format("thumbstick.gui.hints"),
            label("cursor_stick", lower(ThumbstickConfig.cursorStick)), option("cursor_style", ThumbstickConfig.cursorStyle),
            I18n.format("thumbstick.gui.bindings"), I18n.format("thumbstick.gui.mapping"), I18n.format("thumbstick.gui.rescan"), I18n.format("gui.done") };
        for (GuiButton b : Reflect.getButtons(this)) {
            if (b.id < labels.length) b.displayString = labels[b.id];
            if (b.id == MAPPING) b.enabled = active != null;
        }
    }

    static String lower(Enum<?> e) { return e.name().toLowerCase(Locale.ROOT); }

    /** "Name: Value" where value is thumbstick.option.VALUE */
    static String label(String key, String value) { return I18n.format("thumbstick.option." + key) + ": " + I18n.format("thumbstick.option." + value); }

    static String toggle(String key, boolean on) { return I18n.format("thumbstick.option." + key) + ": " + I18n.format(on ? "options.on" : "options.off"); }

    /** "Name: Value" where value is thumbstick.option.KEY.VALUE */
    static String option(String key, Enum<?> value) { return label(key, key + "." + lower(value)); }

    static <T extends Enum<T>> T next(T value) {
        T[] values = value.getDeclaringClass().getEnumConstants();
        return values[(value.ordinal() + 1) % values.length];
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        switch (button.id) {
            case CONTROLLER -> cycleController();
            case INVERT_LOOK -> ThumbstickConfig.invertLook = !ThumbstickConfig.invertLook;
            case ANALOG_MOVEMENT -> ThumbstickConfig.analogMovement = !ThumbstickConfig.analogMovement;
            case SNEAK_MODE -> ThumbstickConfig.toggleSneak = !ThumbstickConfig.toggleSneak;
            case CURSOR_SNAPPING -> ThumbstickConfig.cursorSnapping = !ThumbstickConfig.cursorSnapping;
            case BUTTON_ICONS -> ThumbstickConfig.buttonIcons = next(ThumbstickConfig.buttonIcons);
            case CURSOR_STICK -> ThumbstickConfig.cursorStick = next(ThumbstickConfig.cursorStick);
            case CURSOR_STYLE -> ThumbstickConfig.cursorStyle = next(ThumbstickConfig.cursorStyle);
            case RESCAN -> ControllerManager.INSTANCE.scan(true);
            case SHOW_HINTS -> {
                mc.displayGuiScreen(new Hints(this));
                return;
            }
            case BINDINGS -> {
                mc.displayGuiScreen(new GuiControllerBindings(this));
                return;
            }
            case MAPPING -> {
                mc.displayGuiScreen(new GuiControllerMapping(this));
                return;
            }
            case DONE -> {
                ThumbstickConfig.save();
                mc.displayGuiScreen(parent);
                return;
            }
            default -> {
                return;
            }
        }
        ThumbstickConfig.save();
        updateLabels();
    }

    /** Cycles through the connected controllers, then disabled */
    private void cycleController() {
        ControllerManager manager = ControllerManager.INSTANCE;
        List<Device> devices = manager.getDevices();
        Device active = manager.getActiveDevice();
        int next = active == null ? 0 : devices.indexOf(active) + 1;
        ThumbstickConfig.selectedController = next < devices.size() ? devices.get(next).getName() : ControllerManager.NONE;
        manager.selectPreferred();
    }

    /** Controllers can be plugged in or out while this screen is open */
    @Override
    public void updateScreen() { updateLabels(); }

    @Override
    public void onGuiClosed() { ThumbstickConfig.save(); }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();
        drawCenteredString(fontRendererObj, I18n.format("thumbstick.gui.settings.title"), width / 2, 8, 0xFFFFFF);
        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    /** A slider for a float setting, styled like the vanilla option sliders */
    public static class Slider extends GuiButton {

        private final String labelKey;
        private final float min, max, step;
        private final Consumer<Float> onChange;
        private float value;
        private boolean dragging;

        Slider(int id, int x, int y, String labelKey, float min, float max, float step, float value, Consumer<Float> onChange) {
            super(id, x, y, 150, 20, "");
            this.labelKey = labelKey;
            this.min = min;
            this.max = max;
            this.step = step;
            this.onChange = onChange;
            this.value = value;
            updateLabel();
        }

        private void updateLabel() { displayString = I18n.format(labelKey) + ": " + String.format("%.2f", value); }

        private void setFromMouse(int mouseX) {
            float fraction = (mouseX - (xPosition + 4)) / (float) (width - 8);
            setValue(min + Math.max(0F, Math.min(1F, fraction)) * (max - min));
        }

        private void setValue(float v) {
            v = Math.max(min, Math.min(max, Math.round(v / step) * step));
            if (v != value) {
                value = v;
                onChange.accept(value);
            }
            updateLabel();
        }

        /** Steps the value, for dpad left and right on a hovered slider */
        public void nudge(int direction) { setValue(value + direction * step); }

        @Override
        public int getHoverState(boolean mouseOver) { return 0; }

        @Override
        protected void mouseDragged(Minecraft mc, int mouseX, int mouseY) {
            if (!visible) return;
            if (dragging) setFromMouse(mouseX);
            GL11.glColor4f(1F, 1F, 1F, 1F);
            int knobX = xPosition + (int) ((value - min) / (max - min) * (width - 8));
            drawTexturedModalRect(knobX, yPosition, 0, 66, 4, 20);
            drawTexturedModalRect(knobX + 4, yPosition, 196, 66, 4, 20);
        }

        @Override
        public boolean mousePressed(Minecraft mc, int mouseX, int mouseY) {
            if (!super.mousePressed(mc, mouseX, mouseY)) return false;
            setFromMouse(mouseX);
            dragging = true;
            return true;
        }

        @Override
        public void mouseReleased(int mouseX, int mouseY) { dragging = false; }
    }

    /** Size, corner and layout of the hints. They show here while the controller is used, as a live preview */
    public static class Hints extends GuiScreen {

        private static final int SHOW = 0, SIZE = 1, POSITION = 2, LAYOUT = 3, DETAIL = 4, DONE = 5;
        private final GuiScreen parent;

        Hints(GuiScreen parent) { this.parent = parent; }

        @Override
        @SuppressWarnings("unchecked")
        public void initGui() {
            buttonList.clear();
            int x = width / 2 - 100, top = height / 4;
            for (int id = 0; id < DONE; id++) buttonList.add(new GuiButton(id, x, top + id * 24, 200, 20, ""));
            buttonList.add(new GuiButton(DONE, x, top + 132, 200, 20, I18n.format("gui.done")));
            updateLabels();
        }

        private void updateLabels() {
            String[] labels = { toggle("show_hints", ThumbstickConfig.showHints), option("hint_size", ThumbstickConfig.hintSize),
                option("hint_position", ThumbstickConfig.hintPosition), option("hint_layout", ThumbstickConfig.hintLayout),
                option("hint_detail", ThumbstickConfig.hintDetail) };
            for (GuiButton b : Reflect.getButtons(this)) if (b.id < labels.length) b.displayString = labels[b.id];
        }

        @Override
        protected void actionPerformed(GuiButton button) {
            switch (button.id) {
                case SHOW -> ThumbstickConfig.showHints = !ThumbstickConfig.showHints;
                case SIZE -> ThumbstickConfig.hintSize = next(ThumbstickConfig.hintSize);
                case POSITION -> ThumbstickConfig.hintPosition = next(ThumbstickConfig.hintPosition);
                case DETAIL -> ThumbstickConfig.hintDetail = next(ThumbstickConfig.hintDetail);
                case LAYOUT -> ThumbstickConfig.hintLayout = next(ThumbstickConfig.hintLayout);
                case DONE -> {
                    ThumbstickConfig.save();
                    mc.displayGuiScreen(parent);
                    return;
                }
                default -> {
                    return;
                }
            }
            ThumbstickConfig.save();
            updateLabels();
        }

        @Override
        public void onGuiClosed() { ThumbstickConfig.save(); }

        @Override
        public void drawScreen(int mouseX, int mouseY, float partialTicks) {
            drawDefaultBackground();
            drawCenteredString(fontRendererObj, I18n.format("thumbstick.gui.hints.title"), width / 2, 15, 0xFFFFFF);
            drawCenteredString(fontRendererObj, I18n.format("thumbstick.gui.hints.help"), width / 2, height / 4 - 16, 0xA0A0A0);
            super.drawScreen(mouseX, mouseY, partialTicks);
        }
    }
}
