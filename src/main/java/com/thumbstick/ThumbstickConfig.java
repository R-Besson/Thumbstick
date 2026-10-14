package com.thumbstick;

import java.io.File;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;

import net.minecraftforge.common.config.ConfigCategory;
import net.minecraftforge.common.config.Configuration;
import net.minecraftforge.common.config.Property;

import com.thumbstick.binding.Action;
import com.thumbstick.controller.GamepadButton;

public final class ThumbstickConfig {

    private static final String G = Configuration.CATEGORY_GENERAL, BINDINGS = "bindings", KEY_BINDINGS = "keybindings";

    public enum ButtonIcons {
        AUTO(-1), GENERIC(0), XBOX(4), PLAYSTATION(2), SWITCH(7);

        /** Row in buttons.png, or -1 to pick from the controller name */
        public final int row;

        ButtonIcons(int row) { this.row = row; }
    }

    public enum HintSize {
        SMALL(0.75F), MEDIUM(1F), LARGE(1.5F);

        public final float scale;

        HintSize(float scale) { this.scale = scale; }
    }

    public enum HintPosition {
        TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT;

        public boolean bottom() { return ordinal() >= 2; }
        public boolean right() { return ordinal() % 2 == 1; }
    }

    /** How many hints to show, from the essentials to every bound control */
    public enum HintDetail { MINIMAL, BALANCED, ALL }

    /** LIST puts one hint per line, ROW puts them side by side and wraps */
    public enum HintLayout { LIST, ROW }

    public enum CursorStick { LEFT, RIGHT }

    /** Styles in cursor.png (from Controllable), one 32px row each */
    public enum CursorStyle {
        LIGHT, DARK, CONSOLE, CONSOLE_PLUS, LEGACY_LIGHT, LEGACY_DARK;

        /** Hidden while carrying an item since the item marks the position */
        public boolean hideWhenCarrying() { return this != CONSOLE; }
    }

    public static String selectedController = "";
    public static float lookSpeedX = 1F, lookSpeedY = 1F, cursorSpeed = 1F, deadzone = 0.15F, triggerThreshold = 0.35F;
    public static boolean invertLook, analogMovement = true, toggleSneak, cursorSnapping = true, showHints = true;
    public static ButtonIcons buttonIcons = ButtonIcons.AUTO;
    public static CursorStick cursorStick = CursorStick.LEFT;
    public static CursorStyle cursorStyle = CursorStyle.CONSOLE;
    public static HintSize hintSize = HintSize.MEDIUM;
    public static HintPosition hintPosition = HintPosition.TOP_RIGHT;
    public static HintLayout hintLayout = HintLayout.LIST;
    public static HintDetail hintDetail = HintDetail.BALANCED;

    public static final Map<Action, GamepadButton> bindings = new EnumMap<>(Action.class);
    /** Extra bindings from a controller button to any key binding, by key description */
    public static final Map<String, GamepadButton> keyBindings = new LinkedHashMap<>();

    private static Configuration config;

    private ThumbstickConfig() {}

    public static void load(File file) {
        config = new Configuration(file);
        config.load();
        selectedController = config.getString("selectedController", G, "",
            "Name of the controller to use. Empty picks the first one found, <none> disables controllers.");
        lookSpeedX = config.getFloat("lookSpeedX", G, 1F, 0.1F, 3F, "Horizontal camera speed multiplier.");
        lookSpeedY = config.getFloat("lookSpeedY", G, 1F, 0.1F, 3F, "Vertical camera speed multiplier.");
        invertLook = config.getBoolean("invertLook", G, false, "Invert vertical camera movement.");
        cursorSpeed = config.getFloat("cursorSpeed", G, 1F, 0.1F, 3F, "Menu cursor speed multiplier.");
        deadzone = config.getFloat("deadzone", G, 0.15F, 0F, 0.6F, "Stick dead zone.");
        triggerThreshold = config.getFloat("triggerThreshold", G, 0.35F, 0.05F, 0.95F,
            "How far a trigger must be pulled to count as pressed.");
        analogMovement = config.getBoolean("analogMovement", G, true,
            "Walk at partial speed when the stick is partially pushed. When off, movement is all or nothing like a keyboard.");
        toggleSneak = config.getBoolean("toggleSneak", G, false, "Sneak toggles on press instead of being held.");
        cursorSnapping = config.getBoolean("cursorSnapping", G, true,
            "Center the menu cursor on the hovered inventory slot when the stick is released.");
        showHints = config.getBoolean("showHints", G, true, "Show button hints while using a controller.");
        buttonIcons = getEnum("buttonIcons", ButtonIcons.AUTO, "Button icon style: AUTO, GENERIC, XBOX, PLAYSTATION, SWITCH.");
        cursorStick = getEnum("cursorStick", CursorStick.LEFT,
            "Stick that moves the menu cursor: LEFT or RIGHT. The other one scrolls.");
        cursorStyle = getEnum("cursorStyle", CursorStyle.CONSOLE,
            "Menu cursor look: LIGHT, DARK, CONSOLE, CONSOLE_PLUS, LEGACY_LIGHT, LEGACY_DARK.");
        hintSize = getEnum("hintSize", HintSize.MEDIUM, "Button hint size: SMALL, MEDIUM, LARGE.");
        hintPosition = getEnum("hintPosition", HintPosition.TOP_RIGHT,
            "Button hint corner: TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT.");
        hintLayout = getEnum("hintLayout", HintLayout.LIST, "Button hint layout: LIST (one per line) or ROW (side by side).");
        hintDetail = getEnum("hintDetail", HintDetail.BALANCED, "Which hints to show: MINIMAL, BALANCED or ALL.");

        bindings.clear();
        for (Action a : Action.VALUES) {
            String def = a.defaultButton == null ? "NONE" : a.defaultButton.name();
            bindings.put(a, GamepadButton.byName(config.getString(a.name(), BINDINGS, def, "")));
        }
        keyBindings.clear();
        ConfigCategory category = config.getCategory(KEY_BINDINGS);
        category.setComment("Controller buttons bound to key bindings, by key binding name (key.xxx).");
        for (Map.Entry<String, Property> e : category.entrySet()) {
            GamepadButton button = GamepadButton.byName(e.getValue().getString());
            if (button != null) keyBindings.put(e.getKey(), button);
        }
        save();
    }

    private static <T extends Enum<T>> T getEnum(String key, T def, String comment) {
        String value = config.getString(key, G, def.name(), comment);
        for (T c : def.getDeclaringClass().getEnumConstants()) if (c.name().equalsIgnoreCase(value)) return c;
        return def;
    }

    public static void save() {
        if (config == null) return;
        Object[] general = { "selectedController", selectedController, "lookSpeedX", lookSpeedX, "lookSpeedY", lookSpeedY,
            "invertLook", invertLook, "cursorSpeed", cursorSpeed, "deadzone", deadzone, "triggerThreshold", triggerThreshold,
            "analogMovement", analogMovement, "toggleSneak", toggleSneak, "cursorSnapping", cursorSnapping, "showHints",
            showHints, "hintSize", hintSize, "hintPosition", hintPosition, "hintLayout", hintLayout, "hintDetail", hintDetail,
            "buttonIcons", buttonIcons, "cursorStick", cursorStick, "cursorStyle", cursorStyle };
        // Set directly on the properties, since Configuration.get would drop their comments
        for (int i = 0; i < general.length; i += 2) {
            Object v = general[i + 1];
            config.getCategory(G).get((String) general[i]).set(v instanceof Enum ? ((Enum<?>) v).name() : String.valueOf(v));
        }
        for (Action a : Action.VALUES) {
            GamepadButton button = bindings.get(a);
            config.getCategory(BINDINGS).get(a.name()).set(button == null ? "NONE" : button.name());
        }
        ConfigCategory category = config.getCategory(KEY_BINDINGS);
        for (String key : new ArrayList<>(category.keySet())) if (!keyBindings.containsKey(key)) category.remove(key);
        for (Map.Entry<String, GamepadButton> e : keyBindings.entrySet()) {
            config.get(KEY_BINDINGS, e.getKey(), "NONE").set(e.getValue().name());
        }
        if (config.hasChanged()) config.save();
    }

    public static GamepadButton getBinding(Action action) { return bindings.get(action); }
}
