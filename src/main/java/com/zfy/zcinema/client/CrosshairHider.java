package com.zfy.zcinema.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderGuiLayerEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;

/**
 * Keeps the crosshair out of the picture while nobody is looking around: the moment the camera has
 * not turned for a couple of seconds the vanilla crosshair layer is simply not drawn, and the first
 * mouse movement brings it straight back. Nothing about aiming or interaction changes - a hidden
 * crosshair still points at the same block.
 */
public final class CrosshairHider {
    /** How long the camera has to stay still before the crosshair disappears. */
    private static final long IDLE_MILLIS = 2_000L;
    /** How much the view has to move to count as movement, in degrees. */
    private static final float MOVEMENT_EPSILON = 0.01F;

    private float lastYaw = Float.NaN;
    private float lastPitch = Float.NaN;
    private long stillSince;
    private boolean idle;

    @SubscribeEvent
    public void onClientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (player == null || minecraft.level == null) {
            reset();
            return;
        }
        float yaw = player.getYRot();
        float pitch = player.getXRot();
        long now = System.currentTimeMillis();
        if (Float.isNaN(lastYaw)
                || Math.abs(yaw - lastYaw) > MOVEMENT_EPSILON
                || Math.abs(pitch - lastPitch) > MOVEMENT_EPSILON) {
            stillSince = now;
            idle = false;
        } else if (!idle && now - stillSince >= IDLE_MILLIS) {
            idle = true;
        }
        lastYaw = yaw;
        lastPitch = pitch;
    }

    @SubscribeEvent
    public void onRenderGuiLayer(RenderGuiLayerEvent.Pre event) {
        if (idle && event.getName().equals(VanillaGuiLayers.CROSSHAIR)) {
            event.setCanceled(true);
        }
    }

    private void reset() {
        lastYaw = Float.NaN;
        lastPitch = Float.NaN;
        idle = false;
    }
}
