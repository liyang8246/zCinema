package com.zfy.zcinema;

import com.mojang.logging.LogUtils;
import com.zfy.zcinema.config.CommonConfig;
import com.zfy.zcinema.event.PlayerInteractEvents;
import com.zfy.zcinema.event.ServerEvents;
import com.zfy.zcinema.net.ModNetworking;
import com.zfy.zcinema.registry.ModBlockEntities;
import com.zfy.zcinema.registry.ModBlocks;
import com.zfy.zcinema.registry.ModEntities;
import com.zfy.zcinema.registry.ModItems;
import com.zfy.zcinema.registry.ModMenus;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;

public class ZCinema implements ModInitializer {
    public static final String MODID = "zcinema";
    public static final Logger LOGGER = LogUtils.getLogger();

    /**
     * Longest URL the panel, the block entity and the wire all agree on. Every layer caps at the
     * same number so a link that fits the text box also survives the packet codec.
     */
    public static final int MAX_URL_LENGTH = 2048;

    public static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MODID, path);
    }

    @Override
    public void onInitialize() {
        ZCinemaLog.header();

        ModBlocks.register();
        ModBlockEntities.register();
        ModMenus.register();
        ModItems.register();
        ModEntities.register();

        ModNetworking.registerCommon();
        ModNetworking.registerServerHandlers();

        PlayerInteractEvents.register();
        ServerEvents.register();

        ServerLifecycleEvents.SERVER_STARTING.register(server -> CommonConfig.load());

        LOGGER.info("Z Cinema loaded - paste an MP4 direct link into a screen and watch together.");
    }
}
