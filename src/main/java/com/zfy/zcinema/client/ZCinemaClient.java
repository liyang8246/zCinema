package com.zfy.zcinema.client;

import com.zfy.zcinema.ZCinema;
import com.zfy.zcinema.client.config.ClientConfig;
import com.zfy.zcinema.client.gui.CinemaScreenUI;
import com.zfy.zcinema.client.playback.ClientPlayback;
import com.zfy.zcinema.client.render.CinemaScreenRenderer;
import com.zfy.zcinema.client.render.EmojiSplatRenderer;
import com.zfy.zcinema.client.render.ThrownEmojiRenderer;
import com.zfy.zcinema.net.packets.S2CStatePacket;
import com.zfy.zcinema.registry.ModBlockEntities;
import com.zfy.zcinema.registry.ModEntities;
import com.zfy.zcinema.registry.ModMenus;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderers;

public final class ZCinemaClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        ClientConfig.load();

        BlockEntityRenderers.register(ModBlockEntities.SCREEN_BE, CinemaScreenRenderer::new);
        MenuScreens.register(ModMenus.SCREEN, CinemaScreenUI::create);

        EntityRendererRegistry.register(ModEntities.THROWN_EMOJI, ThrownEmojiRenderer::new);
        EntityRendererRegistry.register(ModEntities.EMOJI_SPLAT, EmojiSplatRenderer::new);

        ClientPlayNetworking.registerGlobalReceiver(S2CStatePacket.TYPE, (payload, context) ->
                context.client().execute(() -> ClientPlayback.handleState(payload)));

        ClientTickEvents.END_CLIENT_TICK.register(client -> ClientPlayback.tick());
        ClientTickEvents.END_CLIENT_TICK.register(client -> HudHider.tick());

        ZCinema.LOGGER.info("Z Cinema client ready");
    }
}
