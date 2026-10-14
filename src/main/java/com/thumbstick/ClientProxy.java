package com.thumbstick;

import java.io.File;

import net.minecraft.launchwrapper.Launch;
import net.minecraftforge.common.MinecraftForge;

import com.thumbstick.client.ControllerInput;
import com.thumbstick.client.HudRenderer;
import com.thumbstick.client.gui.ThumbstickGuiFactory;
import com.thumbstick.controller.ControllerManager;
import com.thumbstick.controller.MappingStore;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPostInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;

public class ClientProxy extends Thumbstick.Proxy {

    @Override
    public void preInit(FMLPreInitializationEvent e) {
        // JInput's jar is sealed, loading it through the LaunchClassLoader logs an error per class
        Launch.classLoader.addClassLoaderExclusion("net.java.games.input.");
        File dir = new File(e.getModConfigurationDirectory(), Thumbstick.MODID);
        ThumbstickConfig.load(new File(dir, "thumbstick.cfg"));
        MappingStore.init(new File(dir, "mappings.properties"));
    }

    @Override
    public void init(FMLInitializationEvent e) {
        FMLCommonHandler.instance().bus().register(ControllerInput.INSTANCE);
        FMLCommonHandler.instance().bus().register(HudRenderer.INSTANCE);
        MinecraftForge.EVENT_BUS.register(HudRenderer.INSTANCE);
        MinecraftForge.EVENT_BUS.register(new ThumbstickGuiFactory());
    }

    @Override
    public void postInit(FMLPostInitializationEvent e) {
        // Scan once everything is loaded so the first frame already knows the connected controllers
        ControllerManager.INSTANCE.start();
    }
}
