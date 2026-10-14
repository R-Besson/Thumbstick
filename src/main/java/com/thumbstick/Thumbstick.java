package com.thumbstick;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.SidedProxy;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPostInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;

@Mod(modid = Thumbstick.MODID, name = "Thumbstick", version = Tags.VERSION, acceptedMinecraftVersions = "[1.7.10]",
    acceptableRemoteVersions = "*", guiFactory = "com.thumbstick.client.gui.ThumbstickGuiFactory")
public class Thumbstick {

    public static final String MODID = "thumbstick";
    public static final Logger LOG = LogManager.getLogger(MODID);

    @SidedProxy(clientSide = "com.thumbstick.ClientProxy", serverSide = "com.thumbstick.Thumbstick$Proxy")
    public static Proxy proxy;

    /** Client only mod, the server side does nothing */
    public static class Proxy {
        public void preInit(FMLPreInitializationEvent e) {}
        public void init(FMLInitializationEvent e) {}
        public void postInit(FMLPostInitializationEvent e) {}
    }

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent e) { proxy.preInit(e); }

    @Mod.EventHandler
    public void init(FMLInitializationEvent e) { proxy.init(e); }

    @Mod.EventHandler
    public void postInit(FMLPostInitializationEvent e) { proxy.postInit(e); }
}
