package com.zfy.zcinema;

import com.mojang.logging.LogUtils;
import com.zfy.zcinema.config.CommonConfig;
import com.zfy.zcinema.registry.ModBlocks;
import com.zfy.zcinema.registry.ModBlockEntities;
import com.zfy.zcinema.registry.ModMenus;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;

@Mod(ZCinema.MODID)
public class ZCinema {
    public static final String MODID = "zcinema";
    public static final Logger LOGGER = LogUtils.getLogger();

    public ZCinema(IEventBus modEventBus, ModContainer modContainer) {
        ModBlocks.BLOCKS.register(modEventBus);
        ModBlockEntities.BLOCK_ENTITIES.register(modEventBus);
        ModMenus.MENUS.register(modEventBus);

        modContainer.registerConfig(ModConfig.Type.COMMON, CommonConfig.SPEC);

        modEventBus.addListener(this::commonSetup);

        NeoForge.EVENT_BUS.register(new com.zfy.zcinema.event.ServerEvents());
        com.zfy.zcinema.event.PlayerInteractEvents.register();

        if (FMLEnvironment.getDist() == Dist.CLIENT) {
            com.zfy.zcinema.client.ZCinemaClient.init(modEventBus, modContainer);
        }
    }

    private void commonSetup(FMLCommonSetupEvent event) {
        LOGGER.info("Z Cinema loaded - paste an MP4 direct link into a screen and watch together.");
    }
}
