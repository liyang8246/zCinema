package com.zfy.zcinema.client;

import com.zfy.zcinema.ZCinema;
import com.zfy.zcinema.blockentity.CinemaScreenBlockEntity;
import com.zfy.zcinema.client.config.ClientConfig;
import com.zfy.zcinema.client.gui.CinemaScreenUI;
import com.zfy.zcinema.client.playback.ClientPlayback;
import com.zfy.zcinema.client.render.CinemaScreenRenderer;
import com.zfy.zcinema.registry.ModBlockEntities;
import com.zfy.zcinema.registry.ModMenus;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;
import net.neoforged.neoforge.common.NeoForge;

@EventBusSubscriber(modid = ZCinema.MODID, value = Dist.CLIENT)
public final class ZCinemaClient {
    private ZCinemaClient() {}

    public static void init(IEventBus modEventBus, ModContainer modContainer) {
        modContainer.registerConfig(ModConfig.Type.CLIENT, ClientConfig.SPEC);
        NeoForge.EVENT_BUS.register(new ClientGameEvents());
    }

    @SubscribeEvent
    static void onClientSetup(FMLClientSetupEvent event) {
        ZCinema.LOGGER.info("Z Cinema client ready");
    }

    @SubscribeEvent
    static void onRegisterRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerBlockEntityRenderer(ModBlockEntities.SCREEN_BE.get(), CinemaScreenRenderer::new);
    }

    @SubscribeEvent
    static void onRegisterScreens(RegisterMenuScreensEvent event) {
        event.register(ModMenus.SCREEN.get(), CinemaScreenUI::create);
    }

    /** Game bus events: per-tick playback driving and cleanup when the world goes away. */
    public static final class ClientGameEvents {
        @SubscribeEvent
        public void onClientTick(ClientTickEvent.Post event) {
            ClientPlayback.tick();
        }

        @SubscribeEvent
        public void onLevelUnload(net.neoforged.neoforge.event.level.LevelEvent.Unload event) {
            if (event.getLevel().isClientSide()) {
                ClientPlayback.clearAll();
            }
        }
    }
}
