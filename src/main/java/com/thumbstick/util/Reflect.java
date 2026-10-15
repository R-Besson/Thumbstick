package com.thumbstick.util;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiSlot;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.client.gui.inventory.GuiContainerCreative;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.inventory.Slot;

import com.thumbstick.Thumbstick;

/** Access to non public vanilla members by their development (MCP) and production (SRG) names */
public final class Reflect {

    private static final Class<?> I = int.class;
    private static final Method MOUSE_CLICKED = method(GuiScreen.class, "mouseClicked", "func_73864_a", I, I, I);
    private static final Method MOUSE_MOVED_OR_UP = method(GuiScreen.class, "mouseMovedOrUp", "func_146286_b", I, I, I);
    private static final Method MOUSE_CLICK_MOVE = method(GuiScreen.class, "mouseClickMove", "func_146273_a", I, I, I, long.class);
    private static final Method KEY_TYPED = method(GuiScreen.class, "keyTyped", "func_73869_a", char.class, I);
    private static final Field BUTTON_LIST = field(GuiScreen.class, "buttonList", "field_146292_n");
    private static final Field GUI_LEFT = field(GuiContainer.class, "guiLeft", "field_147003_i");
    private static final Field GUI_TOP = field(GuiContainer.class, "guiTop", "field_147009_r");
    private static final Method HANDLE_MOUSE_CLICK = method(GuiContainer.class, "handleMouseClick", "func_146984_a", Slot.class, I, I, I);
    private static final Field KEY_PRESSED = field(KeyBinding.class, "pressed", "field_74513_e");
    private static final Field KEY_PRESS_TIME = field(KeyBinding.class, "pressTime", "field_151474_i");
    private static final Field CREATIVE_SELECTED_TAB = field(GuiContainerCreative.class, "selectedTabIndex", "field_147058_w");
    private static final Field CREATIVE_TAB_PAGE = field(GuiContainerCreative.class, "tabPage");
    private static final Field CREATIVE_SCROLL = field(GuiContainerCreative.class, "currentScroll", "field_147067_x");
    private static final Method CREATIVE_SET_TAB = method(GuiContainerCreative.class, "setCurrentCreativeTab", "func_147050_b", CreativeTabs.class);
    private static final Method CREATIVE_NEEDS_SCROLL = method(GuiContainerCreative.class, "needsScrollBars", "func_147055_p");
    private static final Method SLOT_ELEMENT_CLICKED = method(GuiSlot.class, "elementClicked", "func_148144_a", I, boolean.class, I, I);
    private static final Method SLOT_GET_SIZE = method(GuiSlot.class, "getSize", "func_148127_b");
    private static final Method SLOT_IS_SELECTED = method(GuiSlot.class, "isSelected", "func_148131_a", I);

    private Reflect() {}

    private static Field field(Class<?> owner, String... names) {
        for (String name : names) {
            try {
                Field f = owner.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException ignored) {}
        }
        Thumbstick.LOG.error("Could not find field {} in {}", names[0], owner.getName());
        return null;
    }

    private static Method method(Class<?> owner, String mcp, String srg, Class<?>... params) {
        for (String name : new String[] { mcp, srg }) {
            try {
                Method m = owner.getDeclaredMethod(name, params);
                m.setAccessible(true);
                return m;
            } catch (NoSuchMethodException ignored) {}
        }
        Thumbstick.LOG.error("Could not find method {} in {}", mcp, owner.getName());
        return null;
    }

    private static Object invoke(Method method, Object target, Object... args) {
        if (method == null) return null;
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException r) throw r;
            if (cause instanceof Error err) throw err;
            throw new RuntimeException(cause);
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }

    private static Object get(Field field, Object target) {
        if (field == null) return null;
        try {
            return field.get(target);
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }

    private static void set(Field field, Object target, Object value) {
        if (field == null) return;
        try {
            field.set(target, value);
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }

    private static int getInt(Field field, Object target, int fallback) {
        Object v = get(field, target);
        return v == null ? fallback : (Integer) v;
    }

    public static void mouseClicked(GuiScreen s, int x, int y, int button) { invoke(MOUSE_CLICKED, s, x, y, button); }

    public static void mouseMovedOrUp(GuiScreen s, int x, int y, int button) { invoke(MOUSE_MOVED_OR_UP, s, x, y, button); }

    public static void mouseClickMove(GuiScreen s, int x, int y, int button, long time) {
        invoke(MOUSE_CLICK_MOVE, s, x, y, button, time);
    }

    public static void keyTyped(GuiScreen s, char c, int keyCode) { invoke(KEY_TYPED, s, c, keyCode); }

    @SuppressWarnings("unchecked")
    public static List<GuiButton> getButtons(GuiScreen s) {
        Object list = get(BUTTON_LIST, s);
        return list == null ? Collections.emptyList() : (List<GuiButton>) list;
    }

    public static int getGuiLeft(GuiContainer s) { return getInt(GUI_LEFT, s, 0); }

    public static int getGuiTop(GuiContainer s) { return getInt(GUI_TOP, s, 0); }

    public static void handleMouseClick(GuiContainer s, Slot slot, int slotId, int button, int mode) {
        invoke(HANDLE_MOUSE_CLICK, s, slot, slotId, button, mode);
    }

    public static void setPressed(KeyBinding key, boolean pressed) { set(KEY_PRESSED, key, pressed); }

    public static void addPress(KeyBinding key) {
        Object v = get(KEY_PRESS_TIME, key);
        if (v != null) set(KEY_PRESS_TIME, key, (Integer) v + 1);
    }

    public static int getSelectedCreativeTab() { return getInt(CREATIVE_SELECTED_TAB, null, -1); }

    public static void setCreativeTab(GuiContainerCreative s, CreativeTabs tab) {
        set(CREATIVE_TAB_PAGE, null, tab.getTabPage());
        invoke(CREATIVE_SET_TAB, s, tab);
    }

    public static boolean creativeNeedsScrollBars(GuiContainerCreative s) { return Boolean.TRUE.equals(invoke(CREATIVE_NEEDS_SCROLL, s)); }

    public static float getCreativeScroll(GuiContainerCreative s) {
        Object v = get(CREATIVE_SCROLL, s);
        return v == null ? 0F : (Float) v;
    }

    public static void setCreativeScroll(GuiContainerCreative s, float scroll) { set(CREATIVE_SCROLL, s, scroll); }

    public static void elementClicked(GuiSlot list, int index, boolean doubleClick, int x, int y) {
        invoke(SLOT_ELEMENT_CLICKED, list, index, doubleClick, x, y);
    }

    public static int getSize(GuiSlot list) {
        Object v = invoke(SLOT_GET_SIZE, list);
        return v == null ? 0 : (Integer) v;
    }

    public static boolean isSelected(GuiSlot list, int index) { return Boolean.TRUE.equals(invoke(SLOT_IS_SELECTED, list, index)); }

    /** Reads a field by name from the object's class or its superclasses */
    public static Object getFieldValue(Object target, String... names) {
        for (Class<?> c = target.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (String name : names) {
                try {
                    Field f = c.getDeclaredField(name);
                    f.setAccessible(true);
                    return f.get(target);
                } catch (NoSuchFieldException ignored) {} catch (IllegalAccessException e) {
                    return null;
                }
            }
        }
        return null;
    }

    /** Invokes a method by name from the object's class or its superclasses */
    public static void invokeMethod(Object target, String[] names, Class<?>[] params, Object... args) {
        for (Class<?> c = target.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (String name : names) {
                try {
                    Method m = c.getDeclaredMethod(name, params);
                    m.setAccessible(true);
                    m.invoke(target, args);
                    return;
                } catch (NoSuchMethodException ignored) {} catch (Exception e) {
                    Thumbstick.LOG.debug("Failed to invoke {}", name, e);
                    return;
                }
            }
        }
    }
}
