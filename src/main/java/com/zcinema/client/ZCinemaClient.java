package com.zcinema.client;

import com.zcinema.CinemaClientHooks;
import com.zcinema.CinemaCoreBlockEntity;
import com.zcinema.ScreenStatePayload;
import com.zcinema.TimeSyncPayloads;
import com.zcinema.ZCinemaMod;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderers;
import org.watermedia.WaterMedia;

public class ZCinemaClient implements ClientModInitializer {
    private static double lastMouseX = Double.NaN;
    private static double lastMouseY = Double.NaN;
    private static long lastMouseMoveMs = System.currentTimeMillis();
    private static int pingWarmupTicks = 0;

    @Override
    public void onInitializeClient() {
        if (!WaterMedia.started()) {
            WaterMedia.start("Fabric", null, FabricLoader.getInstance().getGameDir(), true);
        }

        BlockEntityRenderers.register(ZCinemaMod.CINEMA_CORE_BE, CinemaScreenRenderer::new);

        CinemaClientHooks.openControlScreen = pos -> {
            Minecraft mc = Minecraft.getInstance();
            mc.execute(() -> mc.setScreen(new CinemaControlScreen(pos)));
        };

        CinemaClientHooks.onBlockEntitySync = be -> {
            Minecraft mc = Minecraft.getInstance();
            mc.execute(() -> ScreenPlayerManager.applyState(
                    be.getBlockPos(), be.url, be.playing, be.mediaPosMs, be.serverTimestampMs, be.stateVersion
            ));
        };

        CinemaClientHooks.onBlockEntityRemoved = pos -> {
            Minecraft mc = Minecraft.getInstance();
            mc.execute(() -> ScreenPlayerManager.remove(pos));
        };

        ClientPlayNetworking.registerGlobalReceiver(TimeSyncPayloads.Pong.TYPE, (payload, context) ->
                ClientClock.onPong(payload.clientSendMs(), payload.serverTimeMs())
        );

        ClientPlayNetworking.registerGlobalReceiver(ScreenStatePayload.TYPE, (payload, context) ->
                context.client().execute(() -> {
                    if (context.client().level != null
                            && context.client().level.getBlockEntity(payload.pos()) instanceof CinemaCoreBlockEntity be) {
                        be.updateState(payload.url(), payload.playing(), payload.mediaPosMs(), payload.serverTimestampMs());
                        ScreenPlayerManager.applyState(
                                be.getBlockPos(), be.url, be.playing, be.mediaPosMs, be.serverTimestampMs, be.stateVersion
                        );
                    }
                })
        );

        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            ClientClock.reset();
            pingWarmupTicks = 60;
            sender.sendPacket(new TimeSyncPayloads.Ping(System.currentTimeMillis()));
        });

        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            ClientClock.reset();
            ScreenPlayerManager.releaseAll();
        });

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (pingWarmupTicks > 0 && client.getConnection() != null) {
                pingWarmupTicks--;
                if (pingWarmupTicks % 15 == 0) {
                    ClientPlayNetworking.send(new TimeSyncPayloads.Ping(System.currentTimeMillis()));
                }
            }
            ScreenPlayerManager.tick(client);
        });
    }

    public static boolean shouldHideHud() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null || mc.screen != null) {
            lastMouseMoveMs = System.currentTimeMillis();
            return false;
        }
        double mx = mc.mouseHandler.xpos();
        double my = mc.mouseHandler.ypos();
        if (mx != lastMouseX || my != lastMouseY) {
            lastMouseX = mx;
            lastMouseY = my;
            lastMouseMoveMs = System.currentTimeMillis();
            return false;
        }
        return System.currentTimeMillis() - lastMouseMoveMs >= 2000L;
    }
}
