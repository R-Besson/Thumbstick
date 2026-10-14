package com.thumbstick.client.gui;

import java.util.Set;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiControls;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.resources.I18n;
import net.minecraftforge.client.event.GuiScreenEvent;

import cpw.mods.fml.client.IModGuiFactory;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;

/** Opens the controller settings from the Mods list Config button, and from a button added to the Controls screen */
public class ThumbstickGuiFactory implements IModGuiFactory {

    private static final int BUTTON_ID = 0x7B5C;

    @Override public void initialize(Minecraft mc) {}
    @Override public Class<? extends GuiScreen> mainConfigGuiClass() { return GuiControllerSettings.class; }
    @Override public Set<RuntimeOptionCategoryElement> runtimeGuiCategories() { return null; }
    @Override public RuntimeOptionGuiHandler getHandlerFor(RuntimeOptionCategoryElement element) { return null; }

    /** Puts the button in the free option slot of the vanilla Controls screen */
    @SubscribeEvent
    @SuppressWarnings("unchecked")
    public void onInitGui(GuiScreenEvent.InitGuiEvent.Post e) {
        if (e.gui instanceof GuiControls) e.buttonList.add(new GuiButton(BUTTON_ID, e.gui.width / 2 + 5, 42, 150, 20, I18n.format("thumbstick.gui.open_settings")));
    }

    @SubscribeEvent
    public void onAction(GuiScreenEvent.ActionPerformedEvent.Pre e) {
        if (e.gui instanceof GuiControls && e.button.id == BUTTON_ID) {
            e.gui.mc.displayGuiScreen(new GuiControllerSettings(e.gui));
            e.setCanceled(true);
        }
    }
}
