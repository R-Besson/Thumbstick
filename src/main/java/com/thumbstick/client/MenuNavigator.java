package com.thumbstick.client;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.client.Minecraft;
import net.minecraft.client.audio.PositionedSoundRecord;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiIngameMenu;
import net.minecraft.client.gui.GuiListExtended;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiSlot;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.client.gui.inventory.GuiContainerCreative;
import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.inventory.Slot;
import net.minecraft.util.ResourceLocation;

import org.lwjgl.BufferUtils;
import org.lwjgl.input.Cursor;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

import com.thumbstick.Thumbstick;
import com.thumbstick.ThumbstickConfig;
import com.thumbstick.binding.Action;
import com.thumbstick.client.gui.GuiControllerSettings;
import com.thumbstick.controller.ControllerManager;
import com.thumbstick.controller.GamepadButton;
import com.thumbstick.util.Reflect;

/**
 * Drives any screen with a virtual cursor. The cursor is reported to the game as the mouse position and presses go
 * to the screen's own mouse handlers, so modded screens work without special support. Moving the real mouse hands
 * control back to it, touching the controller takes it again.
 * <p>
 * LWJGL 2 keeps the mouse position in static fields that Display.update refreshes from the OS every frame, so the
 * position is written again before every tick and frame. Moving the real cursor instead breaks on Windows with
 * display scaling, where the OS and LWJGL disagree on window coordinates.
 */
public final class MenuNavigator {

    /** Screens reading the controller themselves. While capturing, the menu cursor and menu actions are off */
    public interface CaptureScreen {
        boolean isCapturingController();
    }

    /** Full deflection cursor speed in GUI pixels per second */
    private static final double CURSOR_SPEED = 312D;
    /** Releasing the stick within this many GUI pixels of a slot pulls the cursor onto it */
    private static final double SNAP_RADIUS = 10D;
    private static final int NAV_REPEAT_DELAY = 8, NAV_REPEAT_INTERVAL = 3;
    private static final Action[] NAV_ACTIONS = { Action.MENU_UP, Action.MENU_DOWN, Action.MENU_LEFT, Action.MENU_RIGHT };
    private static final int[][] NAV_DIRECTIONS = { { 0, -1 }, { 0, 1 }, { -1, 0 }, { 1, 0 } };
    /** LWJGL's mouse position fields, null on implementations without them where the real cursor is moved */
    private static final Field MOUSE_X = mouseField("x"), MOUSE_Y = mouseField("y");
    private static final boolean OVERRIDE = MOUSE_X != null && MOUSE_Y != null;

    private static Cursor blankCursor;
    private static boolean cursorHidden;

    private final Minecraft mc = Minecraft.getMinecraft();
    private final ControllerManager controller = ControllerManager.INSTANCE;
    private final Map<Class<?>, List<Field>> listFieldCache = new HashMap<>();

    /** Cursor position in GUI coordinates */
    private double cursorX, cursorY, snapX, snapY;
    /** True while the controller owns the cursor (drawn by us, OS cursor hidden) */
    private boolean controllerCursor, stickWasMoving, snapping, scrolling;
    private GuiScreen frameScreen, heldScreen;

    /** Last position the OS reported, to notice when the real mouse moves */
    private int lastOsX = -1, lastOsY = -1, mouseSettleFrames;
    /** Position last written into LWJGL, reading it back means the OS did not update it since */
    private int writtenX = -1, writtenY = -1;

    private int heldButton = -1, lastDragX, lastDragY;
    private long heldSince;
    private float scrollAccumulator;
    private final int[] repeatTicks = new int[NAV_ACTIONS.length];

    private GuiSlot lastClickedList;
    private int lastClickedIndex = -1;
    private long lastClickedTime;

    MenuNavigator() {}

    private static Field mouseField(String name) {
        try {
            Field f = Mouse.class.getDeclaredField(name);
            f.setAccessible(true);
            if (f.getType() == int.class) return f;
        } catch (Throwable ignored) {}
        return null;
    }

    /** Sets the position Mouse.getX and getY report, in display pixels from the bottom left */
    private static void setMouse(int x, int y) {
        if (OVERRIDE) {
            try {
                MOUSE_X.setInt(null, x);
                MOUSE_Y.setInt(null, y);
                return;
            } catch (Throwable t) {
                Thumbstick.LOG.debug("Could not override the mouse position", t);
            }
        }
        Mouse.setCursorPosition(x, y);
    }

    /** Hides the OS cursor while the controller cursor is drawn in its place */
    private static void setCursorHidden(boolean hidden) {
        if (hidden == cursorHidden) return;
        try {
            if (hidden) {
                if ((Cursor.getCapabilities() & Cursor.CURSOR_ONE_BIT_TRANSPARENCY) == 0) return;
                if (blankCursor == null) blankCursor = new Cursor(1, 1, 0, 0, 1, BufferUtils.createIntBuffer(1), null);
                Mouse.setNativeCursor(blankCursor);
            } else Mouse.setNativeCursor(null);
            cursorHidden = hidden;
        } catch (Throwable t) {
            Thumbstick.LOG.debug("Could not change the native cursor", t);
        }
    }

    void onScreenChanged(GuiScreen screen) {
        heldButton = -1;
        heldScreen = null;
        scrolling = false;
        scrollAccumulator = 0F;
        Arrays.fill(repeatTicks, 0);
        if (screen == null) {
            frameScreen = null;
            setCursorHidden(false);
        }
    }

    /** Gives the cursor back to the mouse, like when the controller is disconnected */
    void releaseCursor() {
        if (!controllerCursor) return;
        controllerCursor = false;
        snapping = false;
        setCursorHidden(false);
    }

    private static boolean isCapturing(GuiScreen s) { return s instanceof CaptureScreen c && c.isCapturingController(); }

    /** True when the controller cursor should be drawn over the screen */
    public boolean isCursorVisible(GuiScreen s) { return controllerCursor && s != null && s == frameScreen && !isCapturing(s); }

    public int getCursorX() { return (int) cursorX; }

    public int getCursorY() { return (int) cursorY; }

    private int mouseGuiX(GuiScreen s) { return Mouse.getX() * s.width / mc.displayWidth; }

    private int mouseGuiY(GuiScreen s) { return s.height - Mouse.getY() * s.height / mc.displayHeight - 1; }

    // Per frame: cursor movement

    void onFrame(GuiScreen screen, float delta) {
        if (screen != frameScreen) {
            // Screens opened from the game start with the cursor in the middle, like the vanilla mouse
            if (frameScreen == null) {
                cursorX = screen.width / 2D;
                cursorY = screen.height / 2D;
            }
            frameScreen = screen;
            stickWasMoving = false;
            snapping = false;
            // Opening a screen centers the OS mouse, that is not the player moving it
            mouseSettleFrames = 3;
        }
        clampCursor(screen);
        if (isCapturing(screen)) {
            setCursorHidden(false);
            return;
        }
        detectRealMouse();

        float[] stick = ThumbstickConfig.cursorStick == ThumbstickConfig.CursorStick.LEFT ? controller.getLeftStick() : controller.getRightStick();
        boolean moving = stick[0] != 0F || stick[1] != 0F;
        if (moving) {
            takeControl(screen);
            snapping = false;
            double magnitude = Math.sqrt(stick[0] * stick[0] + stick[1] * stick[1]);
            double curved = magnitude * magnitude * 0.6D + magnitude * 0.4D;
            double speed = CURSOR_SPEED * ThumbstickConfig.cursorSpeed;
            // Slower over a slot so it is easier to stop on
            if (screen instanceof GuiContainer c && getSlotAt(c, getCursorX(), getCursorY()) != null) speed *= 0.6D;
            cursorX += stick[0] / magnitude * curved * speed * delta;
            cursorY += stick[1] / magnitude * curved * speed * delta;
            clampCursor(screen);
        } else if (stickWasMoving && controllerCursor && ThumbstickConfig.cursorSnapping && screen instanceof GuiContainer c) {
            startSnap(c);
        }
        stickWasMoving = moving;

        if (snapping) {
            double t = 1D - Math.exp(-delta * 25D);
            cursorX += (snapX - cursorX) * t;
            cursorY += (snapY - cursorY) * t;
            if (Math.abs(snapX - cursorX) < 0.3D && Math.abs(snapY - cursorY) < 0.3D) {
                cursorX = snapX;
                cursorY = snapY;
                snapping = false;
            }
        }
        applyCursor(screen);
    }

    private void clampCursor(GuiScreen s) {
        cursorX = Math.max(0D, Math.min(s.width - 1, cursorX));
        cursorY = Math.max(0D, Math.min(s.height - 1, cursorY));
    }

    /** Hands the cursor back to the mouse when the player moves it */
    private void detectRealMouse() {
        int x = Mouse.getX(), y = Mouse.getY();
        // Still our override, nothing new from the OS
        if (x == writtenX && y == writtenY) return;
        boolean moved = lastOsX >= 0 && mouseSettleFrames == 0 && (Math.abs(x - lastOsX) > 2 || Math.abs(y - lastOsY) > 2);
        if (mouseSettleFrames > 0) mouseSettleFrames--;
        lastOsX = x;
        lastOsY = y;
        if (!moved) return;
        controller.markOtherInputUsed();
        if (controllerCursor && OVERRIDE) releaseCursor();
    }

    /** Switches to the controller cursor, starting where the mouse is */
    private void takeControl(GuiScreen s) {
        if (controllerCursor) return;
        controllerCursor = true;
        cursorX = Mouse.getX() * s.width / (double) mc.displayWidth;
        cursorY = s.height - Mouse.getY() * s.height / (double) mc.displayHeight - 1;
        clampCursor(s);
        lastOsX = Mouse.getX();
        lastOsY = Mouse.getY();
    }

    /** Reports the cursor to the game as the mouse position */
    private void applyCursor(GuiScreen s) {
        if (!controllerCursor) return;
        setCursorHidden(OVERRIDE);
        int x = (int) ((Math.floor(cursorX) + 0.5D) * mc.displayWidth / s.width);
        int y = (int) ((s.height - Math.floor(cursorY) - 0.5D) * mc.displayHeight / s.height);
        setMouse(x, y);
        writtenX = x;
        writtenY = y;
        if (!OVERRIDE) {
            lastOsX = x;
            lastOsY = y;
        }
    }

    private void startSnap(GuiContainer s) {
        int left = Reflect.getGuiLeft(s), top = Reflect.getGuiTop(s);
        Slot best = null;
        double bestDistance = SNAP_RADIUS;
        for (Object o : s.inventorySlots.inventorySlots) {
            Slot slot = (Slot) o;
            // Distance from the cursor to the slot's 16x16 square, 0 inside it
            double dx = Math.max(Math.abs(cursorX - (left + slot.xDisplayPosition + 8)) - 8D, 0D);
            double dy = Math.max(Math.abs(cursorY - (top + slot.yDisplayPosition + 8)) - 8D, 0D);
            double distance = Math.sqrt(dx * dx + dy * dy);
            if (distance <= bestDistance) {
                bestDistance = distance;
                best = slot;
            }
        }
        if (best != null) {
            snapping = true;
            snapX = left + best.xDisplayPosition + 8;
            snapY = top + best.yDisplayPosition + 8;
        }
    }

    // Per tick: buttons

    void onTick(GuiScreen screen) {
        if (isCapturing(screen)) {
            heldButton = -1;
            heldScreen = null;
            return;
        }
        if (controller.getAnyPressed() != null) takeControl(screen);
        if (!controllerCursor) {
            // The mouse has the cursor, the scroll stick still works where it points
            handleScrolling(screen, mouseGuiX(screen), mouseGuiY(screen));
            return;
        }
        applyCursor(screen);
        int x = getCursorX(), y = getCursorY();
        long now = Minecraft.getSystemTime();

        handleMouseButton(screen, Action.MENU_CLICK, 0, x, y, now);
        if (mc.currentScreen != screen) return;
        handleMouseButton(screen, Action.MENU_RIGHT_CLICK, 1, x, y, now);
        if (mc.currentScreen != screen) return;
        if (heldButton >= 0 && heldScreen == screen && (x != lastDragX || y != lastDragY)) {
            Reflect.mouseClickMove(screen, x, y, heldButton, now - heldSince);
            lastDragX = x;
            lastDragY = y;
        }
        if (!handleShortcuts(screen, x, y)) return;

        for (int i = 0; i < NAV_ACTIONS.length; i++) {
            GamepadButton button = ThumbstickConfig.getBinding(NAV_ACTIONS[i]);
            if (controller.isPressed(button)) {
                repeatTicks[i] = 0;
                navigate(screen, NAV_DIRECTIONS[i][0], NAV_DIRECTIONS[i][1]);
            } else if (controller.isDown(button)) {
                int held = ++repeatTicks[i];
                if (held >= NAV_REPEAT_DELAY && (held - NAV_REPEAT_DELAY) % NAV_REPEAT_INTERVAL == 0) {
                    navigate(screen, NAV_DIRECTIONS[i][0], NAV_DIRECTIONS[i][1]);
                }
            }
        }
        handleScrolling(screen, x, y);
    }

    /** Back, close, resume, drop and creative tabs. Returns false if the screen was closed */
    private boolean handleShortcuts(GuiScreen screen, int x, int y) {
        boolean quickMoved = pressed(Action.MENU_QUICK_MOVE) && tryQuickMove(screen, x, y);
        // Closing a container while carrying a stack would throw it on the ground
        if (pressed(Action.MENU_BACK) && !(quickMoved && ThumbstickConfig.getBinding(Action.MENU_QUICK_MOVE) == ThumbstickConfig.getBinding(Action.MENU_BACK))
            && !(screen instanceof GuiContainer && isCarryingItem())
            || pressed(Action.MENU_CLOSE_INVENTORY) && screen instanceof GuiContainer) {
            Reflect.keyTyped(screen, (char) 0, Keyboard.KEY_ESCAPE);
            return false;
        }
        if (pressed(Action.MENU_RESUME) && screen instanceof GuiIngameMenu) {
            mc.displayGuiScreen(null);
            mc.setIngameFocus();
            return false;
        }
        if (screen instanceof GuiContainer c) {
            boolean dropOne = pressed(Action.MENU_DROP_ONE), dropStack = pressed(Action.MENU_DROP_STACK);
            if (dropOne || dropStack) dropItem(c, x, y, dropStack);
        }
        if (screen instanceof GuiContainerCreative c) {
            if (pressed(Action.MENU_PREVIOUS_TAB)) cycleCreativeTab(c, -1);
            if (pressed(Action.MENU_NEXT_TAB)) cycleCreativeTab(c, 1);
        }
        return true;
    }

    private boolean pressed(Action a) { return controller.isPressed(ThumbstickConfig.getBinding(a)); }

    private boolean isCarryingItem() { return mc.thePlayer != null && mc.thePlayer.inventory.getItemStack() != null; }

    private void handleMouseButton(GuiScreen screen, Action action, int mouseButton, int x, int y, long now) {
        GamepadButton button = ThumbstickConfig.getBinding(action);
        if (controller.isPressed(button)) {
            if (heldButton >= 0 && heldScreen == screen) Reflect.mouseMovedOrUp(screen, x, y, heldButton);
            heldButton = mouseButton;
            heldScreen = screen;
            heldSince = now;
            lastDragX = x;
            lastDragY = y;
            Reflect.mouseClicked(screen, x, y, mouseButton);
            if (mouseButton == 0 && mc.currentScreen == screen) clickLists(screen, x, y, now);
        } else if (controller.isReleased(button) && heldButton == mouseButton) {
            if (heldScreen == screen) Reflect.mouseMovedOrUp(screen, x, y, mouseButton);
            heldButton = -1;
            heldScreen = null;
        }
    }

    /**
     * Plain GuiSlot lists (world selection, resource packs) poll Mouse.isButtonDown instead of handling clicks, so
     * they are selected by hand. Two quick presses on the same entry count as a double click.
     */
    private void clickLists(GuiScreen screen, int x, int y, long now) {
        for (GuiSlot list : findLists(screen)) {
            if (list instanceof GuiListExtended || !isInside(list, x, y)) continue;
            int half = list.getListWidth() / 2;
            int relativeY = y - list.top - list.headerPadding + list.getAmountScrolled() - 4;
            int index = relativeY / list.slotHeight;
            if (x >= list.width / 2 - half && x <= list.width / 2 + half && relativeY >= 0 && index < Reflect.getSize(list)) {
                boolean doubleClick = list == lastClickedList && index == lastClickedIndex && now - lastClickedTime < 500L;
                Reflect.elementClicked(list, index, doubleClick, x, y);
                lastClickedList = list;
                lastClickedIndex = doubleClick ? -1 : index;
                lastClickedTime = now;
                return;
            }
        }
    }

    /** Drops the carried stack, or else the hovered one, like the drop key with a mouse */
    private void dropItem(GuiContainer screen, int x, int y, boolean wholeStack) {
        if (isCarryingItem()) {
            // A click outside the window: left drops everything carried, right drops one
            Reflect.handleMouseClick(screen, null, -999, wholeStack ? 0 : 1, 0);
            return;
        }
        Slot slot = getSlotAt(screen, x, y);
        if (slot != null && slot.getHasStack()) Reflect.handleMouseClick(screen, slot, slot.slotNumber, wholeStack ? 1 : 0, 4);
    }

    /** True for the item slots of a creative tab, where A takes one and quick move takes a stack */
    public static boolean isCreativeItemSlot(GuiContainer screen, Slot slot) {
        Minecraft mc = Minecraft.getMinecraft();
        return screen instanceof GuiContainerCreative && slot != null && mc.thePlayer != null
            && slot.inventory != mc.thePlayer.inventory && Reflect.getSelectedCreativeTab() != CreativeTabs.tabInventory.getTabIndex();
    }

    private boolean tryQuickMove(GuiScreen screen, int x, int y) {
        if (!(screen instanceof GuiContainer c) || isCarryingItem()) return false;
        Slot slot = getSlotAt(c, x, y);
        if (slot == null || !slot.getHasStack()) return false;
        Reflect.handleMouseClick(c, slot, slot.slotNumber, 0, 1);
        return true;
    }

    private void cycleCreativeTab(GuiContainerCreative screen, int direction) {
        CreativeTabs[] tabs = CreativeTabs.creativeTabArray;
        int current = Reflect.getSelectedCreativeTab();
        for (int i = 1; i <= tabs.length; i++) {
            CreativeTabs tab = tabs[Math.floorMod(current + direction * i, tabs.length)];
            if (tab != null) {
                Reflect.setCreativeTab(screen, tab);
                mc.getSoundHandler().playSound(PositionedSoundRecord.func_147674_a(new ResourceLocation("gui.button.press"), 1.0F));
                return;
            }
        }
    }

    // Directional navigation

    /** A place the dpad can jump to: a rectangle and the point the cursor lands on */
    private static final class Target {

        final int x, y, left, top, right, bottom;

        Target(int x, int y, int left, int top, int right, int bottom) {
            this.x = x;
            this.y = y;
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
        }

        boolean contains(int px, int py) { return px >= left && px < right && py >= top && py < bottom; }
    }

    private void navigate(GuiScreen screen, int dx, int dy) {
        snapping = false;
        int x = getCursorX(), y = getCursorY();

        // Left and right on one of our sliders adjusts it
        if (dx != 0) {
            for (GuiButton b : Reflect.getButtons(screen)) {
                if (b instanceof GuiControllerSettings.Slider slider && b.visible && x >= b.xPosition && y >= b.yPosition
                    && x < b.xPosition + b.width && y < b.yPosition + b.height) {
                    slider.nudge(dx);
                    return;
                }
            }
        }
        // Inside a list, up and down step one entry and scroll at the edges
        if (dy != 0) {
            for (GuiSlot list : findLists(screen)) {
                if (!isInside(list, x, y) || Reflect.getSize(list) == 0) continue;
                int newY = y + dy * list.slotHeight;
                if (newY > list.top + 2 && newY < list.bottom - 2) {
                    cursorY = newY;
                    return;
                }
                if (dy > 0 ? list.getAmountScrolled() < list.func_148135_f() : list.getAmountScrolled() > 0) {
                    list.scrollBy(dy * list.slotHeight);
                    return;
                }
            }
        }

        List<Target> targets = collectTargets(screen, x, y);
        Target current = null;
        for (Target t : targets) if (t.contains(x, y)) current = t;
        // Prefer targets fully past the hovered one, so right from a left button picks its neighbor and not a wide button below
        Target best = current == null ? null : findTarget(targets, x, y, dx, dy, current);
        if (best == null) best = findTarget(targets, x, y, dx, dy, null);
        if (best != null) {
            cursorX = best.x;
            cursorY = best.y;
        }
    }

    private static Target findTarget(List<Target> targets, int x, int y, int dx, int dy, Target past) {
        Target best = null;
        double bestScore = Double.MAX_VALUE;
        for (Target t : targets) {
            if (t.contains(x, y)) continue;
            if (past != null && !(dx > 0 ? t.left >= past.right : dx < 0 ? t.right <= past.left : dy > 0 ? t.top >= past.bottom : t.bottom <= past.top)) continue;
            int along = (t.x - x) * dx + (t.y - y) * dy;
            if (along <= 0) continue;
            // Sideways distance to the edges, so wide buttons straight ahead count as aligned
            int across = dx != 0 ? distanceOutside(y, t.top, t.bottom) : distanceOutside(x, t.left, t.right);
            if (across > along * 2) continue;
            double score = along + across * 3.0D;
            if (score < bestScore) {
                bestScore = score;
                best = t;
            }
        }
        return best;
    }

    private static int distanceOutside(int value, int min, int max) {
        return value < min ? min - value : value >= max ? value - max + 1 : 0;
    }

    private List<Target> collectTargets(GuiScreen screen, int x, int y) {
        List<Target> targets = new ArrayList<>();
        if (screen instanceof GuiContainer c) {
            int left = Reflect.getGuiLeft(c), top = Reflect.getGuiTop(c);
            for (Object o : c.inventorySlots.inventorySlots) {
                Slot slot = (Slot) o;
                int sx = left + slot.xDisplayPosition, sy = top + slot.yDisplayPosition;
                // Hidden slots are moved far off screen
                if (sx + 8 < 0 || sy + 8 < 0 || sx + 8 >= screen.width || sy + 8 >= screen.height) continue;
                targets.add(new Target(sx + 8, sy + 8, sx - 1, sy - 1, sx + 17, sy + 17));
            }
        }
        for (GuiButton b : Reflect.getButtons(screen)) {
            if (b.visible && b.enabled) {
                targets.add(new Target(b.xPosition + b.width / 2, b.yPosition + b.height / 2, b.xPosition, b.yPosition,
                    b.xPosition + b.width, b.yPosition + b.height));
            }
        }
        for (GuiSlot list : findLists(screen)) {
            if (isInside(list, x, y) || Reflect.getSize(list) == 0) continue;
            int half = list.slotHeight / 2;
            int ty = Math.max(list.top + half + 4, Math.min(list.bottom - half, y));
            targets.add(new Target((list.left + list.right) / 2, ty, list.left, list.top, list.right, list.bottom));
        }
        return targets;
    }

    // Scrolling

    private void handleScrolling(GuiScreen screen, int x, int y) {
        float stickY = (ThumbstickConfig.cursorStick == ThumbstickConfig.CursorStick.LEFT ? controller.getRightStick() : controller.getLeftStick())[1];
        if (stickY == 0F) {
            scrolling = false;
            scrollAccumulator = 0F;
            return;
        }
        if (!scrolling) {
            scrolling = true;
            scrollAccumulator = Math.signum(stickY);
        } else scrollAccumulator += stickY * 0.6F;
        while (Math.abs(scrollAccumulator) >= 1F) {
            int direction = (int) Math.signum(scrollAccumulator);
            scroll(screen, direction, x, y);
            scrollAccumulator -= direction;
        }
    }

    /** Direction 1 scrolls down, -1 up */
    private void scroll(GuiScreen screen, int direction, int x, int y) {
        if (screen instanceof GuiContainerCreative c) {
            scrollCreative(c, direction);
            return;
        }
        GuiSlot target = null;
        for (GuiSlot list : findLists(screen)) if (target == null || isInside(list, x, y)) target = list;
        if (target != null) target.scrollBy(direction * target.slotHeight);
    }

    private void scrollCreative(GuiContainerCreative screen, int direction) {
        if (!Reflect.creativeNeedsScrollBars(screen)) return;
        if (!(Reflect.getFieldValue(screen.inventorySlots, "itemList", "field_148330_a") instanceof List<?> items)) return;
        // Same row count as vanilla's mouse wheel scrolling
        int rows = items.size() / 9 - 5 + 1;
        if (rows <= 0) return;
        float scroll = Math.max(0F, Math.min(1F, Reflect.getCreativeScroll(screen) + direction / (float) rows));
        Reflect.setCreativeScroll(screen, scroll);
        Reflect.invokeMethod(screen.inventorySlots, new String[] { "scrollTo", "func_148329_a" }, new Class<?>[] { float.class }, scroll);
    }

    // Helpers

    /** Whether the scroll stick does anything on this screen (creative items or a list) */
    public boolean canScroll(GuiScreen screen) {
        if (screen instanceof GuiContainerCreative c) return Reflect.creativeNeedsScrollBars(c);
        return !findLists(screen).isEmpty();
    }

    private static boolean isInside(GuiSlot l, int x, int y) { return x > l.left && x < l.right && y > l.top && y < l.bottom; }

    private List<GuiSlot> findLists(GuiScreen screen) {
        List<Field> fields = listFieldCache.computeIfAbsent(screen.getClass(), cls -> {
            List<Field> found = new ArrayList<>();
            for (Class<?> c = cls; c != null && c != GuiScreen.class; c = c.getSuperclass()) {
                for (Field f : c.getDeclaredFields()) {
                    if (!Modifier.isStatic(f.getModifiers()) && GuiSlot.class.isAssignableFrom(f.getType())) {
                        f.setAccessible(true);
                        found.add(f);
                    }
                }
            }
            return found;
        });
        List<GuiSlot> lists = new ArrayList<>(fields.size());
        for (Field f : fields) {
            try {
                if (f.get(screen) instanceof GuiSlot list) lists.add(list);
            } catch (IllegalAccessException ignored) {}
        }
        return lists;
    }

    /** The slot under the cursor (controller or mouse) of a container screen, if any */
    public Slot getHoveredSlot(GuiContainer s) {
        return controllerCursor ? getSlotAt(s, getCursorX(), getCursorY()) : getSlotAt(s, mouseGuiX(s), mouseGuiY(s));
    }

    private static Slot getSlotAt(GuiContainer s, int x, int y) {
        int rx = x - Reflect.getGuiLeft(s), ry = y - Reflect.getGuiTop(s);
        for (Object o : s.inventorySlots.inventorySlots) {
            Slot slot = (Slot) o;
            if (rx >= slot.xDisplayPosition - 1 && rx < slot.xDisplayPosition + 17 && ry >= slot.yDisplayPosition - 1
                && ry < slot.yDisplayPosition + 17) return slot;
        }
        return null;
    }
}
