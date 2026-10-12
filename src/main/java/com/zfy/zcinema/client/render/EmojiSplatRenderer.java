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
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;

/**
 * Draws the emoji picture a throw left behind: one flat quad, three blocks across and three blocks
 * tall, standing upright and always turned towards the viewer.
 *
 * <p>It goes through the vanilla emissive entity pipeline, the same one the screen uses: no lightmap
 * sampling, full brightness, so a picture thrown at night is visible, and shaderpacks treat it as a
 * glowing surface (Complementary, for one, runs it through its eyes program) instead of lighting it
 * like a block.
 */
public class EmojiSplatRenderer extends EntityRenderer<EmojiSplat> {
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

        float half = EmojiSplat.SIZE * 0.5F;
        // Upright billboard: turn on the vertical axis only, so the picture never leans back when
        // the viewer looks up or down.
        poseStack.pushPose();
        poseStack.mulPose(new Quaternionf().rotationY(billboardYaw(splat)));
        // Fade out at the end of its life instead of popping out of existence.
        int alpha = (int) (Mth.clamp((EmojiSplat.LIFETIME - splat.tickCount) / (float) EmojiSplat.FADE_TICKS,
                0.0F, 1.0F) * 255.0F);

        VertexConsumer consumer = bufferSource.getBuffer(RenderType.entityTranslucentEmissive(emoji.sprite()));
        PoseStack.Pose pose = poseStack.last();
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
        poseStack.popPose();
    }

    /**
     * The Y rotation, in radians, that turns the picture's face (+Z of the quad) towards the
     * viewer. Entity yaw convention: 0 faces south, 90 faces west.
     */
    private float billboardYaw(EmojiSplat splat) {
        Vec3 viewer = this.entityRenderDispatcher.camera.getPosition();
        double dx = viewer.x - splat.getX();
        double dz = viewer.z - splat.getZ();
        float lookYaw = (float) Mth.atan2(dz, dx) * (180.0F / (float) Math.PI) - 90.0F;
        // Rotating the quad by -yaw is what makes its +Z face the direction that yaw points at.
        return -lookYaw * Mth.DEG_TO_RAD;
    }

    @Override
    public ResourceLocation getTextureLocation(EmojiSplat splat) {
        ItemStack stack = splat.item();
        return stack.getItem() instanceof EmojiItem emoji ? emoji.sprite() : FALLBACK_TEXTURE;
    }
}
