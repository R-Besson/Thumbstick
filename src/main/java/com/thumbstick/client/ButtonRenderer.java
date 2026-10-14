package com.thumbstick.client;

import java.util.Locale;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.resources.I18n;
import net.minecraft.util.ResourceLocation;

import org.lwjgl.opengl.GL11;

import com.thumbstick.ThumbstickConfig;
import com.thumbstick.ThumbstickConfig.ButtonIcons;
import com.thumbstick.controller.ControllerManager;
import com.thumbstick.controller.Device;
import com.thumbstick.controller.GamepadButton;
import com.thumbstick.controller.MappingProfiles;

/** Draws 13x13 button icons from buttons.png (from Controllable), one column per button and one row per style */
public final class ButtonRenderer {

    public static final int SIZE = 13;
    private static final ResourceLocation TEXTURE = new ResourceLocation("thumbstick", "textures/gui/buttons.png");

    private ButtonRenderer() {}

    public static void draw(Minecraft mc, GamepadButton button, int x, int y) {
        mc.getTextureManager().bindTexture(TEXTURE);
        GL11.glColor4f(1F, 1F, 1F, 1F);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        Gui.func_146110_a(x, y, button.ordinal() * SIZE, getRow() * SIZE, SIZE, SIZE, 31 * SIZE, 10 * SIZE);
    }

    private static int getRow() {
        if (ThumbstickConfig.buttonIcons.row >= 0) return ThumbstickConfig.buttonIcons.row;
        Device device = ControllerManager.INSTANCE.getActiveDevice();
        if (device == null) return ButtonIcons.GENERIC.row;
        String name = device.getName().toLowerCase(Locale.ROOT);
        return switch (MappingProfiles.detectFamily(device.getName())) {
            case XBOX -> ButtonIcons.XBOX.row;
            // Rows 1, 2 and 3 are PS5, PS4 and PS3
            case PLAYSTATION -> name.contains("dualsense") || name.contains("ps5") ? 1
                : name.contains("ps3") || name.contains("playstation(r)3") ? 3 : 2;
            case SWITCH -> ButtonIcons.SWITCH.row;
            default -> ButtonIcons.GENERIC.row;
        };
    }

    /** Localized name of a button, like "Right Trigger" */
    public static String getName(GamepadButton button) {
        return I18n.format(button == null ? "thumbstick.button.none" : button.getTranslationKey());
    }
}
