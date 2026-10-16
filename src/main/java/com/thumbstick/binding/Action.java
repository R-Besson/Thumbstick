package com.thumbstick.binding;

import static com.thumbstick.controller.GamepadButton.*;

import java.util.Locale;

import com.thumbstick.controller.GamepadButton;

/** Everything a controller button can be bound to, besides any key binding (ThumbstickConfig.keyBindings) */
public enum Action {

    JUMP(A, "key.jump"),
    SNEAK(RIGHT_STICK, "key.sneak"),
    SPRINT(LEFT_STICK, "key.sprint"),
    ATTACK(RIGHT_TRIGGER, "key.attack"),
    USE_ITEM(LEFT_TRIGGER, "key.use"),
    PICK_BLOCK(DPAD_LEFT, "key.pickItem"),
    DROP_ITEM(DPAD_DOWN, "key.drop"),
    PREVIOUS_ITEM(LEFT_BUMPER, null),
    NEXT_ITEM(RIGHT_BUMPER, null),
    INVENTORY(Y, "key.inventory"),
    TOGGLE_PERSPECTIVE(DPAD_UP, "key.togglePerspective"),
    PLAYER_LIST(BACK, "key.playerlist"),
    PAUSE(START, null),
    CHAT(DPAD_RIGHT, "key.chat"),
    SCREENSHOT(null, "key.screenshot"),
    OPEN_SETTINGS(null, null),

    MENU_CLICK(A, null),
    MENU_RIGHT_CLICK(X, null),
    MENU_QUICK_MOVE(B, null),
    MENU_BACK(B, null),
    MENU_CLOSE_INVENTORY(Y, null),
    MENU_RESUME(START, null),
    MENU_DROP_ONE(RIGHT_TRIGGER, null),
    MENU_DROP_STACK(LEFT_TRIGGER, null),
    MENU_PREVIOUS_TAB(LEFT_BUMPER, null),
    MENU_NEXT_TAB(RIGHT_BUMPER, null),
    MENU_UP(DPAD_UP, null),
    MENU_DOWN(DPAD_DOWN, null),
    MENU_LEFT(DPAD_LEFT, null),
    MENU_RIGHT(DPAD_RIGHT, null);

    public static final Action[] VALUES = values();

    public final GamepadButton defaultButton;
    /** True for actions used while a screen is open, false for in world ones */
    public final boolean menu = name().startsWith("MENU_");
    private final String vanillaKey;

    Action(GamepadButton defaultButton, String vanillaKey) {
        this.defaultButton = defaultButton;
        this.vanillaKey = vanillaKey;
    }

    /** Reuses vanilla key binding names where they exist so the text matches the Controls menu */
    public String getTranslationKey() {
        return vanillaKey != null ? vanillaKey : "thumbstick.action." + name().toLowerCase(Locale.ROOT);
    }
}
