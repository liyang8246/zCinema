package com.zfy.zcinema.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.zfy.zcinema.item.EmojiItem;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

/**
 * Shared bits of the emoji billboards - the picture a throw leaves behind and the emoji on its way
 * there are the same flat, upright, always-camera-facing quad at two different sizes.
 *
 * <p>Both draw through the vanilla emissive entity pipeline: no lightmap sampling, full brightness,
 * so they are visible at night, and shaderpacks treat them as glowing surfaces instead of lighting
 * them like blocks.
 */
final class Billboard {
    private Billboard() {}

    /** The emoji png of a stack, or null when the stack is not one of ours. */
    static ResourceLocation sprite(ItemStack stack) {
        return stack.getItem() instanceof EmojiItem emoji ? emoji.sprite() : null;
    }

    /**
     * Radians around Y that turn a quad facing +Z towards {@code viewer}, standing at
     * (x, z). Horizontal only, so the picture never leans back when the camera pitches.
     */
    static float yawTowards(Vec3 viewer, double x, double z) {
        float lookYaw = (float) Mth.atan2(viewer.z - z, viewer.x - x) * (180.0F / (float) Math.PI) - 90.0F;
        // Rotating the quad by -yaw is what makes its +Z face the direction that yaw points at.
        return -lookYaw * Mth.DEG_TO_RAD;
    }

    /** Adds one flat quad of the given half size (in blocks) to the buffer, centred on the pose. */
    static void draw(VertexConsumer consumer, PoseStack.Pose pose, float half, int alpha) {
        float[][] corners = {
                {-half, -half, 0.0F, 1.0F},   // bottom left
                {-half, half, 0.0F, 0.0F},    // top left
                {half, half, 1.0F, 0.0F},     // top right
                {half, -half, 1.0F, 1.0F},    // bottom right
        };
        for (float[] corner : corners) {
            consumer.addVertex(pose, corner[0], corner[1], 0.0F)
                    .setColor(255, 255, 255, alpha)
                    .setUv(corner[2], corner[3])
                    .setOverlay(OverlayTexture.NO_OVERLAY)
                    .setLight(LightTexture.FULL_BRIGHT)
                    .setNormal(pose, 0.0F, 0.0F, 1.0F);
        }
    }
}
