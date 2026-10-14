package com.thumbstick.client;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiIngameMenu;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.client.gui.inventory.GuiContainerCreative;
import net.minecraft.client.resources.I18n;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.Slot;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.client.event.RenderGameOverlayEvent;

import org.lwjgl.opengl.GL11;

import com.thumbstick.ThumbstickConfig;
import com.thumbstick.ThumbstickConfig.HintDetail;
import com.thumbstick.binding.Action;
import com.thumbstick.controller.ControllerManager;
import com.thumbstick.controller.GamepadButton;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/** Renders the button hints, the controller cursor and the connected and disconnected notification */
public final class HudRenderer {

    public static final HudRenderer INSTANCE = new HudRenderer();

    private static final long TOAST_DURATION_MS = 4000L, TOAST_SLIDE_MS = 250L;
    /** PADDING is the dark box around each hint, GAP the space between hints */
    private static final int MARGIN = 6, PADDING = 2, GAP = 3, ICON_SPACING = 1;
    private static final ResourceLocation CURSOR_TEXTURE = new ResourceLocation("thumbstick", "textures/gui/cursor.png");

    private final Minecraft mc = Minecraft.getMinecraft();
    private String toastTitle, toastSubtitle;
    private long toastStart;

    private HudRenderer() {}

    public void showToast(String title, String subtitle) {
        toastTitle = title;
        toastSubtitle = subtitle;
        // Starts when first drawn, so it is not missed while the game loads
        toastStart = 0L;
    }

    /** One hint: button icons and what they do */
    private static final class Hint {

        final List<GamepadButton> buttons;
        final String label;

        Hint(List<GamepadButton> buttons, String label) {
            this.buttons = buttons;
            this.label = label;
        }
    }

    private static boolean shown(HintDetail level) { return level.ordinal() <= ThumbstickConfig.hintDetail.ordinal(); }

    private static void add(List<Hint> hints, HintDetail level, Action action, String labelKey) {
        add(hints, level, action, action, labelKey);
    }

    /** One hint for two actions shown together, like LB and RB for the hotbar */
    private static void add(List<Hint> hints, HintDetail level, Action first, Action second, String labelKey) {
        if (!shown(level)) return;
        List<GamepadButton> buttons = new ArrayList<>();
        for (Action a : new Action[] { first, second }) {
            GamepadButton b = ThumbstickConfig.getBinding(a);
            if (b != null && !buttons.contains(b)) buttons.add(b);
        }
        if (!buttons.isEmpty()) hints.add(new Hint(buttons, I18n.format(labelKey)));
    }

    private static void add(List<Hint> hints, GamepadButton button, String labelKey) {
        hints.add(new Hint(Collections.singletonList(button), I18n.format(labelKey)));
    }

    private boolean shouldShowHints() { return ThumbstickConfig.showHints && ControllerManager.INSTANCE.isInUse(); }

    private boolean isCarrying() { return mc.thePlayer != null && mc.thePlayer.inventory.getItemStack() != null; }

    // Hint contents

    private List<Hint> getGameHints(EntityPlayer player) {
        List<Hint> hints = new ArrayList<>();
        if (player == null) return hints;
        MovingObjectPosition target = mc.objectMouseOver;
        ItemStack held = player.getHeldItem();
        boolean block = target != null && target.typeOfHit == MovingObjectPosition.MovingObjectType.BLOCK;
        boolean entity = target != null && target.typeOfHit == MovingObjectPosition.MovingObjectType.ENTITY;

        add(hints, HintDetail.MINIMAL, Action.ATTACK, block ? "thumbstick.hint.mine" : "thumbstick.hint.attack");
        add(hints, HintDetail.MINIMAL, Action.USE_ITEM, entity ? "thumbstick.hint.interact"
            : block && held != null && held.getItem() instanceof ItemBlock ? "thumbstick.hint.place" : "thumbstick.hint.use");
        add(hints, HintDetail.BALANCED, Action.JUMP, "thumbstick.hint.jump");
        add(hints, HintDetail.ALL, Action.SNEAK, ThumbstickConfig.toggleSneak ? "thumbstick.hint.sneak_toggle" : "thumbstick.hint.sneak");
        add(hints, HintDetail.ALL, Action.SPRINT, "thumbstick.hint.sprint");
        add(hints, HintDetail.MINIMAL, Action.INVENTORY, "thumbstick.hint.inventory");
        add(hints, HintDetail.BALANCED, Action.PREVIOUS_ITEM, Action.NEXT_ITEM, "thumbstick.hint.hotbar");
        add(hints, HintDetail.BALANCED, Action.DROP_ITEM, "thumbstick.hint.drop");
        add(hints, HintDetail.ALL, Action.PICK_BLOCK, "thumbstick.hint.pick_block");
        add(hints, HintDetail.ALL, Action.TOGGLE_PERSPECTIVE, "thumbstick.hint.perspective");
        add(hints, HintDetail.ALL, Action.PLAYER_LIST, "thumbstick.hint.player_list");
        add(hints, HintDetail.ALL, Action.CHAT, "thumbstick.hint.chat");
        add(hints, HintDetail.ALL, Action.SCREENSHOT, "thumbstick.hint.screenshot");
        add(hints, HintDetail.ALL, Action.OPEN_SETTINGS, "thumbstick.hint.settings");
        add(hints, HintDetail.ALL, Action.PAUSE, "thumbstick.hint.pause");
        // Buttons bound to other key bindings, like from mods
        if (shown(HintDetail.ALL)) for (Map.Entry<String, GamepadButton> e : ThumbstickConfig.keyBindings.entrySet()) add(hints, e.getValue(), e.getKey());
        return hints;
    }

    private List<Hint> getMenuHints(GuiScreen screen) {
        List<Hint> hints = new ArrayList<>();
        MenuNavigator navigator = ControllerInput.INSTANCE.getMenuNavigator();
        if (screen instanceof GuiContainer container) {
            boolean carrying = isCarrying();
            Slot slot = navigator.getHoveredSlot(container);
            boolean creativeItem = MenuNavigator.isCreativeItemSlot(container, slot);
            if (carrying) {
                add(hints, HintDetail.MINIMAL, Action.MENU_CLICK, creativeItem ? "thumbstick.hint.put_away" : "thumbstick.hint.place");
                add(hints, HintDetail.BALANCED, Action.MENU_RIGHT_CLICK, "thumbstick.hint.place_one");
            } else if (creativeItem) {
                add(hints, HintDetail.MINIMAL, Action.MENU_CLICK, "thumbstick.hint.take_one");
                add(hints, HintDetail.MINIMAL, Action.MENU_QUICK_MOVE, "thumbstick.hint.take_stack");
            } else {
                add(hints, HintDetail.MINIMAL, Action.MENU_CLICK, "thumbstick.hint.take");
                add(hints, HintDetail.BALANCED, Action.MENU_RIGHT_CLICK, "thumbstick.hint.split");
                boolean hoveringStack = slot != null && slot.getHasStack();
                if (hoveringStack || ThumbstickConfig.getBinding(Action.MENU_QUICK_MOVE) != ThumbstickConfig.getBinding(Action.MENU_BACK)) {
                    add(hints, HintDetail.MINIMAL, Action.MENU_QUICK_MOVE, "thumbstick.hint.quick_move");
                }
                if (!hoveringStack) add(hints, HintDetail.MINIMAL, Action.MENU_BACK, "thumbstick.hint.back");
            }
            add(hints, HintDetail.BALANCED, Action.MENU_DROP_ONE, "thumbstick.hint.drop_one");
            add(hints, HintDetail.BALANCED, Action.MENU_DROP_STACK, carrying ? "thumbstick.hint.drop_all" : "thumbstick.hint.drop_stack");
            add(hints, HintDetail.MINIMAL, Action.MENU_CLOSE_INVENTORY, "thumbstick.hint.close");
            if (screen instanceof GuiContainerCreative) add(hints, HintDetail.BALANCED, Action.MENU_PREVIOUS_TAB, Action.MENU_NEXT_TAB, "thumbstick.hint.tabs");
        } else {
            add(hints, HintDetail.MINIMAL, Action.MENU_CLICK, "thumbstick.hint.select");
            add(hints, HintDetail.MINIMAL, Action.MENU_BACK, "thumbstick.hint.back");
            if (screen instanceof GuiIngameMenu) add(hints, HintDetail.BALANCED, Action.MENU_RESUME, "thumbstick.hint.resume");
        }
        add(hints, HintDetail.ALL, Action.MENU_UP, Action.MENU_DOWN, "thumbstick.hint.navigate");
        if (!shown(HintDetail.ALL)) return hints;
        boolean rightCursor = ThumbstickConfig.cursorStick == ThumbstickConfig.CursorStick.RIGHT;
        add(hints, rightCursor ? GamepadButton.RIGHT_STICK : GamepadButton.LEFT_STICK, "thumbstick.hint.cursor");
        if (navigator.canScroll(screen)) add(hints, rightCursor ? GamepadButton.LEFT_STICK : GamepadButton.RIGHT_STICK, "thumbstick.hint.scroll");
        return hints;
    }

    // Rendering

    @SubscribeEvent
    public void onRenderOverlay(RenderGameOverlayEvent.Post event) {
        if (event.type == RenderGameOverlayEvent.ElementType.ALL && mc.currentScreen == null && shouldShowHints()) {
            drawHints(getGameHints(mc.thePlayer), event.resolution);
        }
    }

    @SubscribeEvent
    public void onRenderTick(TickEvent.RenderTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        GuiScreen screen = mc.currentScreen;
        boolean menuHints = screen != null && !(screen instanceof MenuNavigator.CaptureScreen) && shouldShowHints();
        if (toastTitle != null && toastStart == 0L) toastStart = System.currentTimeMillis();
        boolean toast = toastTitle != null && System.currentTimeMillis() - toastStart < TOAST_DURATION_MS;
        MenuNavigator navigator = ControllerInput.INSTANCE.getMenuNavigator();
        boolean cursor = navigator.isCursorVisible(screen);
        if (!menuHints && !toast && !cursor) return;

        ScaledResolution resolution = new ScaledResolution(mc, mc.displayWidth, mc.displayHeight);
        mc.entityRenderer.setupOverlayRendering();
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glDisable(GL11.GL_LIGHTING);
        GL11.glEnable(GL11.GL_BLEND);
        if (menuHints) drawHints(getMenuHints(screen), resolution);
        if (toast) drawToast(resolution);
        if (cursor) drawCursor(screen, navigator);
        GL11.glEnable(GL11.GL_DEPTH_TEST);
        GL11.glColor4f(1F, 1F, 1F, 1F);
    }

    /** Width of a hint's content, without padding */
    private int hintWidth(Hint hint) {
        return hint.buttons.size() * (ButtonRenderer.SIZE + ICON_SPACING) + 2 + mc.fontRenderer.getStringWidth(hint.label);
    }

    /** Width a hint takes in a line, with padding and gap */
    private int hintSpan(Hint hint) { return hintWidth(hint) + PADDING * 2 + GAP; }

    /** Lays the hints out in the configured corner, as a list or as wrapping rows, at the configured size */
    private void drawHints(List<Hint> hints, ScaledResolution resolution) {
        if (hints.isEmpty()) return;
        float scale = ThumbstickConfig.hintSize.scale;
        int width = (int) (resolution.getScaledWidth() / scale), height = (int) (resolution.getScaledHeight() / scale);
        int lineHeight = ButtonRenderer.SIZE + PADDING * 2 + GAP;

        boolean right = ThumbstickConfig.hintPosition.right(), bottom = ThumbstickConfig.hintPosition.bottom();
        GL11.glPushMatrix();
        GL11.glScalef(scale, scale, 1F);
        if (ThumbstickConfig.hintLayout == ThumbstickConfig.HintLayout.LIST) {
            // One hint per line, wrapping into more columns when the screen is too short
            int perColumn = Math.max(1, (height - MARGIN * 2 + GAP) / lineHeight), offset = 0;
            for (int start = 0; start < hints.size(); start += perColumn) {
                List<Hint> column = hints.subList(start, Math.min(start + perColumn, hints.size()));
                int columnWidth = 0;
                for (Hint hint : column) columnWidth = Math.max(columnWidth, hintSpan(hint));
                int y = bottom ? height - MARGIN - column.size() * lineHeight + GAP + PADDING : MARGIN + PADDING;
                for (Hint hint : column) {
                    drawHint(hint, right ? width - MARGIN - offset - hintSpan(hint) + GAP + PADDING : MARGIN + offset + PADDING, y);
                    y += lineHeight;
                }
                offset += columnWidth;
            }
            GL11.glPopMatrix();
            return;
        }
        // As many hints as fit side by side per line
        List<List<Hint>> lines = new ArrayList<>();
        List<Hint> current = new ArrayList<>();
        int currentWidth = 0;
        for (Hint hint : hints) {
            if (!current.isEmpty() && currentWidth + hintSpan(hint) > width - MARGIN * 2) {
                lines.add(current);
                current = new ArrayList<>();
                currentWidth = 0;
            }
            current.add(hint);
            currentWidth += hintSpan(hint);
        }
        lines.add(current);
        int y = bottom ? height - MARGIN - lines.size() * lineHeight + GAP + PADDING : MARGIN + PADDING;
        for (List<Hint> line : lines) {
            int lineWidth = -GAP;
            for (Hint hint : line) lineWidth += hintSpan(hint);
            int x = right ? width - MARGIN - lineWidth + PADDING : MARGIN + PADDING;
            for (Hint hint : line) {
                drawHint(hint, x, y);
                x += hintSpan(hint);
            }
            y += lineHeight;
        }
        GL11.glPopMatrix();
    }

    private void drawHint(Hint hint, int x, int y) {
        Gui.drawRect(x - PADDING, y - PADDING, x + hintWidth(hint) + PADDING, y + ButtonRenderer.SIZE + PADDING, 0x90000000);
        for (GamepadButton button : hint.buttons) {
            ButtonRenderer.draw(mc, button, x, y);
            x += ButtonRenderer.SIZE + ICON_SPACING;
        }
        mc.fontRenderer.drawStringWithShadow(hint.label, x + 2, y + 3, 0xFFFFFF);
    }

    private void drawCursor(GuiScreen screen, MenuNavigator navigator) {
        ThumbstickConfig.CursorStyle style = ThumbstickConfig.cursorStyle;
        if (isCarrying() && style.hideWhenCarrying()) return;
        boolean overSlot = screen instanceof GuiContainer c && navigator.getHoveredSlot(c) != null;
        mc.getTextureManager().bindTexture(CURSOR_TEXTURE);
        GL11.glColor4f(1F, 1F, 1F, 1F);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        // 32x32 sprites drawn at 16x16: the plain cursor, then the variant shown over a slot
        Gui.func_152125_a(navigator.getCursorX() - 8, navigator.getCursorY() - 8, overSlot ? 32 : 0, style.ordinal() * 32,
            32, 32, 16, 16, 64, ThumbstickConfig.CursorStyle.values().length * 32);
    }

    /** Slides down from the top center, out of the way of hints in any corner */
    private void drawToast(ScaledResolution resolution) {
        FontRenderer font = mc.fontRenderer;
        long elapsed = System.currentTimeMillis() - toastStart;
        String subtitle = font.trimStringToWidth(toastSubtitle == null ? "" : toastSubtitle, 180);
        int width = Math.max(font.getStringWidth(toastTitle), font.getStringWidth(subtitle)) + 12, height = 26;
        float visible = 1F;
        if (elapsed < TOAST_SLIDE_MS) visible = elapsed / (float) TOAST_SLIDE_MS;
        else if (elapsed > TOAST_DURATION_MS - TOAST_SLIDE_MS) visible = (TOAST_DURATION_MS - elapsed) / (float) TOAST_SLIDE_MS;
        int x = (resolution.getScaledWidth() - width) / 2, y = (int) ((height + 4) * visible) - height;
        Gui.drawRect(x, y, x + width, y + height, 0xD0101010);
        Gui.drawRect(x, y + height - 2, x + width, y + height, 0xFF55AAFF);
        font.drawStringWithShadow(toastTitle, x + 6, y + 4, 0xFFFF55);
        font.drawStringWithShadow(subtitle, x + 6, y + 14, 0xFFFFFF);
    }
}
