package com.zfy.zcinema.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

/**
 * Keeps everything out of the picture while nobody is looking around: once the camera has been
 * still for a couple of seconds the HUD is hidden exactly the way the F1 toggle does it, and the
 * first mouse movement brings it back. That switch is the one the whole screen reads - the HUD
 * layers, the first-person hand, the block outline, the item activation animation - so nothing is
 * left floating over the movie, and it is not written to options.txt, so an idle moment can never
 * leak into the next launch.
 *
 * <p>The player's own setting is remembered and put back, so a deliberate F1 stays deliberate.
 */
public final class HudHider {
    /** How long the camera has to stay still before the HUD disappears. */
    private static final long IDLE_MILLIS = 2_000L;

    /** How much the view has to move to count as movement, in degrees. */
    private static final float MOVEMENT_EPSILON = 0.01F;

    private static float lastYaw = Float.NaN;
    private static float lastPitch = Float.NaN;
    private static long stillSince;
    private static boolean idle;

    /** True while the hidden HUD is ours to undo. */
    private static boolean hiding;

    /** What the player's own HUD switch was worth before we flipped it. */
    private static boolean playerHudState;

    private HudHider() {}

    public static void tick() {
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (player == null || minecraft.level == null) {
            reset(minecraft);
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

        if (idle && !hiding) {
            hiding = true;
            playerHudState = minecraft.options.hideGui;
            minecraft.options.hideGui = true;
        } else if (!idle && hiding) {
            hiding = false;
            minecraft.options.hideGui = playerHudState;
        }
        // While idle the switch stays ours; if the player presses F1 in that time, their state is
        // what ends up being restored.
    }

    private static void reset(Minecraft minecraft) {
        lastYaw = Float.NaN;
        lastPitch = Float.NaN;
        idle = false;
        if (hiding) {
            hiding = false;
            minecraft.options.hideGui = playerHudState;
        }
    }
}
