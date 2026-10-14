package com.thumbstick.client.gui;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiListExtended;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.resources.I18n;
import net.minecraft.client.settings.GameSettings;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.util.EnumChatFormatting;

import org.lwjgl.input.Keyboard;

import com.thumbstick.ThumbstickConfig;
import com.thumbstick.binding.Action;
import com.thumbstick.client.ButtonRenderer;
import com.thumbstick.client.MenuNavigator;
import com.thumbstick.controller.ControllerManager;
import com.thumbstick.controller.GamepadButton;

/** Lists every action and key binding (vanilla and modded) with the controller button bound to it */
public class GuiControllerBindings extends GuiScreen implements MenuNavigator.CaptureScreen {

    private static final int RESET_ALL = 0, DONE = 1;

    private final GuiScreen parent;
    private BindingList list;
    private Target listening;

    public GuiControllerBindings(GuiScreen parent) { this.parent = parent; }

    /** Something a controller button can be bound to */
    private abstract static class Target {
        abstract String getLabel();
        abstract GamepadButton get();
        abstract void set(GamepadButton button);
        abstract GamepadButton getDefault();
        abstract String getResetLabel();
        /** True if another target active at the same time uses the same button */
        abstract boolean hasConflict();
    }

    private static final class ActionTarget extends Target {

        final Action action;

        ActionTarget(Action action) { this.action = action; }

        @Override String getLabel() { return I18n.format(action.getTranslationKey()); }
        @Override GamepadButton get() { return ThumbstickConfig.getBinding(action); }
        @Override void set(GamepadButton button) { ThumbstickConfig.bindings.put(action, button); }
        @Override GamepadButton getDefault() { return action.defaultButton; }
        @Override String getResetLabel() { return I18n.format("controls.reset"); }

        @Override
        boolean hasConflict() {
            GamepadButton button = get();
            if (button == null) return false;
            for (Action other : Action.VALUES) {
                // Quick move and back share a button on purpose, quick move wins over an item
                boolean pair = other == Action.MENU_QUICK_MOVE && action == Action.MENU_BACK || other == Action.MENU_BACK && action == Action.MENU_QUICK_MOVE;
                if (other != action && other.menu == action.menu && ThumbstickConfig.getBinding(other) == button && !pair) return true;
            }
            return !action.menu && ThumbstickConfig.keyBindings.containsValue(button);
        }
    }

    private static final class KeyTarget extends Target {

        final String key;

        KeyTarget(KeyBinding key) { this.key = key.getKeyDescription(); }

        @Override String getLabel() { return I18n.format(key); }
        @Override GamepadButton get() { return ThumbstickConfig.keyBindings.get(key); }
        @Override GamepadButton getDefault() { return null; }
        @Override String getResetLabel() { return I18n.format("thumbstick.gui.clear"); }

        @Override
        void set(GamepadButton button) {
            if (button == null) ThumbstickConfig.keyBindings.remove(key);
            else ThumbstickConfig.keyBindings.put(key, button);
        }

        @Override
        boolean hasConflict() {
            GamepadButton button = get();
            if (button == null) return false;
            for (Action a : Action.VALUES) if (!a.menu && ThumbstickConfig.getBinding(a) == button) return true;
            int uses = 0;
            for (GamepadButton other : ThumbstickConfig.keyBindings.values()) if (other == button) uses++;
            return uses > 1;
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public void initGui() {
        list = new BindingList();
        buttonList.clear();
        buttonList.add(new GuiButton(RESET_ALL, width / 2 - 155, height - 29, 150, 20, I18n.format("controls.resetAll")));
        buttonList.add(new GuiButton(DONE, width / 2 + 5, height - 29, 150, 20, I18n.format("gui.done")));
    }

    @Override
    public boolean isCapturingController() { return listening != null; }

    @Override
    public void updateScreen() {
        if (listening == null) return;
        GamepadButton pressed = ControllerManager.INSTANCE.getAnyPressed();
        if (pressed == null) return;
        listening.set(pressed == listening.get() ? null : pressed);
        listening = null;
        ThumbstickConfig.save();
        ControllerManager.INSTANCE.suppressHeldButtons();
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        if (button.id == RESET_ALL) {
            for (Action a : Action.VALUES) ThumbstickConfig.bindings.put(a, a.defaultButton);
            ThumbstickConfig.keyBindings.clear();
            ThumbstickConfig.save();
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
    public void onGuiClosed() { ThumbstickConfig.save(); }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();
        list.drawScreen(mouseX, mouseY, partialTicks);
        drawCenteredString(fontRendererObj, I18n.format("thumbstick.gui.bindings.title"), width / 2, 8, 0xFFFFFF);
        String hint = listening != null ? I18n.format("thumbstick.gui.bindings.listening", listening.getLabel()) : I18n.format("thumbstick.gui.bindings.help");
        drawCenteredString(fontRendererObj, hint, width / 2, 19, listening != null ? 0xFFFF55 : 0xA0A0A0);
        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    private class BindingList extends GuiListExtended {

        private final List<IGuiListEntry> entries = new ArrayList<>();

        BindingList() {
            super(GuiControllerBindings.this.mc, GuiControllerBindings.this.width, GuiControllerBindings.this.height, 32,
                GuiControllerBindings.this.height - 36, 20);
            for (boolean menu : new boolean[] { false, true }) {
                entries.add(new CategoryEntry(I18n.format(menu ? "thumbstick.gui.category.menu" : "thumbstick.gui.category.game")));
                for (Action a : Action.VALUES) if (a.menu == menu) entries.add(new BindingEntry(new ActionTarget(a)));
            }
            // Keys already covered by an action are left out so they are not bound twice
            GameSettings s = GuiControllerBindings.this.mc.gameSettings;
            Set<KeyBinding> covered = new HashSet<>(Arrays.asList(s.keyBindForward, s.keyBindBack, s.keyBindLeft, s.keyBindRight,
                s.keyBindJump, s.keyBindSneak, s.keyBindSprint, s.keyBindAttack, s.keyBindUseItem, s.keyBindPickBlock,
                s.keyBindDrop, s.keyBindInventory, s.keyBindTogglePerspective, s.keyBindPlayerList, s.keyBindChat,
                s.keyBindScreenshot));
            KeyBinding[] keys = s.keyBindings.clone();
            Arrays.sort(keys);
            String category = null;
            for (KeyBinding key : keys) {
                if (covered.contains(key)) continue;
                if (!key.getKeyCategory().equals(category)) {
                    category = key.getKeyCategory();
                    entries.add(new CategoryEntry(I18n.format(category)));
                }
                entries.add(new BindingEntry(new KeyTarget(key)));
            }
        }

        @Override protected int getSize() { return entries.size(); }
        @Override public IGuiListEntry getListEntry(int index) { return entries.get(index); }
        @Override public int getListWidth() { return 310; }
        @Override protected int getScrollBarX() { return width / 2 + 160; }
    }

    private class CategoryEntry implements GuiListExtended.IGuiListEntry {

        private final String text;

        CategoryEntry(String text) { this.text = text; }

        @Override
        public void drawEntry(int index, int x, int y, int listWidth, int slotHeight, Tessellator t, int mouseX, int mouseY, boolean selected) {
            drawCenteredString(fontRendererObj, text, width / 2, y + slotHeight - fontRendererObj.FONT_HEIGHT - 1, 0xFFFF55);
        }

        @Override public boolean mousePressed(int index, int x, int y, int event, int relX, int relY) { return false; }
        @Override public void mouseReleased(int index, int x, int y, int event, int relX, int relY) {}
    }

    private class BindingEntry implements GuiListExtended.IGuiListEntry {

        private final Target target;
        private final BindButton bindButton = new BindButton();
        private final GuiButton resetButton;

        BindingEntry(Target target) {
            this.target = target;
            resetButton = new GuiButton(0, 0, 0, 50, 18, target.getResetLabel());
        }

        @Override
        public void drawEntry(int index, int x, int y, int listWidth, int slotHeight, Tessellator t, int mouseX, int mouseY, boolean selected) {
            String label = fontRendererObj.trimStringToWidth(target.getLabel(), 140);
            fontRendererObj.drawString(label, x, y + slotHeight / 2 - fontRendererObj.FONT_HEIGHT / 2, 0xFFFFFF);
            bindButton.xPosition = x + 145;
            bindButton.yPosition = y;
            bindButton.value = target.get();
            bindButton.listening = listening == target;
            bindButton.conflict = target.hasConflict();
            bindButton.drawButton(mc, mouseX, mouseY);
            resetButton.xPosition = x + 255;
            resetButton.yPosition = y;
            resetButton.enabled = target.get() != target.getDefault();
            resetButton.drawButton(mc, mouseX, mouseY);
        }

        @Override
        public boolean mousePressed(int index, int x, int y, int event, int relX, int relY) {
            if (bindButton.mousePressed(mc, x, y)) {
                bindButton.func_146113_a(mc.getSoundHandler());
                listening = target;
                // The press that clicked the button must not become the new binding
                ControllerManager.INSTANCE.suppressHeldButtons();
                return true;
            }
            if (resetButton.mousePressed(mc, x, y)) {
                resetButton.func_146113_a(mc.getSoundHandler());
                target.set(target.getDefault());
                ThumbstickConfig.save();
                return true;
            }
            return false;
        }

        @Override
        public void mouseReleased(int index, int x, int y, int event, int relX, int relY) {
            bindButton.mouseReleased(x, y);
            resetButton.mouseReleased(x, y);
        }
    }

    /** Shows a binding: the button's icon and name */
    private static class BindButton extends GuiButton {

        GamepadButton value;
        boolean listening, conflict;

        BindButton() { super(0, 0, 0, 105, 18, ""); }

        @Override
        public void drawButton(Minecraft mc, int mouseX, int mouseY) {
            if (!visible) return;
            displayString = "";
            super.drawButton(mc, mouseX, mouseY);
            String text = listening ? EnumChatFormatting.WHITE + "> " + EnumChatFormatting.YELLOW + I18n.format("thumbstick.gui.press_button") + EnumChatFormatting.WHITE + " <"
                : value == null ? EnumChatFormatting.GRAY + I18n.format("thumbstick.button.none")
                : (conflict ? EnumChatFormatting.RED : "") + ButtonRenderer.getName(value);
            int textX = xPosition + width / 2;
            if (!listening && value != null) {
                ButtonRenderer.draw(mc, value, xPosition + 3, yPosition + (height - ButtonRenderer.SIZE) / 2);
                textX += ButtonRenderer.SIZE / 2 + 1;
            }
            drawCenteredString(mc.fontRenderer, mc.fontRenderer.trimStringToWidth(text, width - ButtonRenderer.SIZE - 8), textX, yPosition + (height - 8) / 2, 0xFFFFFF);
        }
    }
}
