package com.zfy.zcinema.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.zfy.zcinema.entity.EmojiSplat;
import com.zfy.zcinema.item.EmojiItem;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;

/**
 * Draws the emoji picture a throw left behind: one flat quad, three blocks across, centred on the
 * surface that was hit.
 *
 * <p>It goes through the vanilla emissive entity pipeline, the same one the screen uses: no lightmap
 * sampling, full brightness, so a picture thrown at night is visible, and shaderpacks treat it as a
 * glowing surface (Complementary, for one, runs it through its eyes program) instead of lighting it
 * like a block.
 */
public class EmojiSplatRenderer extends EntityRenderer<EmojiSplat> {
    /** Push the picture a hair off the surface so the wall never fights it for the same pixels. */
    private static final float OFFSET = 0.0125F;
    private static final ResourceLocation FALLBACK_TEXTURE =
            ResourceLocation.withDefaultNamespace("textures/misc/white.png");

    public EmojiSplatRenderer(EntityRendererProvider.Context context) {
        super(context);
    }

    @Override
    public void render(EmojiSplat splat, float entityYaw, float partialTick, PoseStack poseStack,
                       MultiBufferSource bufferSource, int packedLight) {
        ItemStack stack = splat.item();
        if (!(stack.getItem() instanceof EmojiItem emoji)) return;

        Direction face = splat.face();
        float nx = face.getStepX();
        float ny = face.getStepY();
        float nz = face.getStepZ();
        // Basis of the wall face: right cross up points away from the wall.
        float rightX;
        float rightY = 0.0F;
        float rightZ;
        float upX = 0.0F;
        float upY;
        float upZ;
        if (ny == 0.0F) {
            rightX = nz;
            rightZ = -nx;
            upY = 1.0F;
            upZ = 0.0F;
        } else {
            rightX = 1.0F;
            rightZ = 0.0F;
            upY = 0.0F;
            upZ = -ny;
        }
        float half = EmojiSplat.SIZE * 0.5F;
        // Fade out at the end of its life instead of popping out of existence.
        int alpha = (int) (Mth.clamp((EmojiSplat.LIFETIME - splat.tickCount) / (float) EmojiSplat.FADE_TICKS,
                0.0F, 1.0F) * 255.0F);

        VertexConsumer consumer = bufferSource.getBuffer(RenderType.entityTranslucentEmissive(emoji.sprite()));
        poseStack.pushPose();
        poseStack.translate(nx * OFFSET, ny * OFFSET, nz * OFFSET);
        PoseStack.Pose pose = poseStack.last();
        float[][] corners = {
                {-half, -half, 0.0F, 1.0F},   // bottom left
                {-half, half, 0.0F, 0.0F},    // top left
                {half, half, 1.0F, 0.0F},     // top right
                {half, -half, 1.0F, 1.0F},    // bottom right
        };
        for (float[] corner : corners) {
            float x = rightX * corner[0] + upX * corner[1];
            float y = rightY * corner[0] + upY * corner[1];
            float z = rightZ * corner[0] + upZ * corner[1];
            consumer.addVertex(pose, x, y, z)
                    .setColor(255, 255, 255, alpha)
                    .setUv(corner[2], corner[3])
                    .setOverlay(OverlayTexture.NO_OVERLAY)
                    .setLight(LightTexture.FULL_BRIGHT)
                    .setNormal(pose, nx, ny, nz);
        }
        poseStack.popPose();
    }

    @Override
    public ResourceLocation getTextureLocation(EmojiSplat splat) {
        ItemStack stack = splat.item();
        return stack.getItem() instanceof EmojiItem emoji ? emoji.sprite() : FALLBACK_TEXTURE;
    }
}
