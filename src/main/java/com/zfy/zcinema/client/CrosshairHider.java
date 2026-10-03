package com.zfy.zcinema.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

/**
 * Keeps the crosshair out of the picture while nobody is looking around: the moment the camera has
 * not turned for a couple of seconds the vanilla crosshair layer is simply not drawn, and the first
 * mouse movement brings it straight back. Nothing about aiming or interaction changes - a hidden
 * crosshair still points at the same block.
 *
 * <p>{@link com.zfy.zcinema.mixin.GuiMixin} consults {@link #shouldHide()} while drawing the HUD.
 */
public final class CrosshairHider {
    /** How long the camera has to stay still before the crosshair disappears. */
    private static final long IDLE_MILLIS = 2_000L;
    /** How much the view has to move to count as movement, in degrees. */
    private static final float MOVEMENT_EPSILON = 0.01F;

    private static float lastYaw = Float.NaN;
    private static float lastPitch = Float.NaN;
    private static long stillSince;
    private static boolean idle;

    private CrosshairHider() {}

    public static void tick() {
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

    public static boolean shouldHide() {
        return idle;
    }

    private static void reset() {
        lastYaw = Float.NaN;
        lastPitch = Float.NaN;
        idle = false;
    }
}
