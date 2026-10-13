package com.zfy.zcinema.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.zfy.zcinema.entity.EmojiSplat;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;

/**
 * Draws the emoji picture a throw left behind: one flat quad, three blocks across and three blocks
 * tall, standing upright and always turned towards the viewer.
 */
public class EmojiSplatRenderer extends EntityRenderer<EmojiSplat> {
    private static final ResourceLocation FALLBACK_TEXTURE =
            ResourceLocation.withDefaultNamespace("textures/misc/white.png");

    public EmojiSplatRenderer(EntityRendererProvider.Context context) {
        super(context);
    }

    @Override
    public void render(
            EmojiSplat splat,
            float entityYaw,
            float partialTick,
            PoseStack poseStack,
            MultiBufferSource bufferSource,
            int packedLight) {
        ResourceLocation sprite = Billboard.sprite(splat.item());
        if (sprite == null) return;

        Vec3 viewer = this.entityRenderDispatcher.camera.getPosition();
        poseStack.pushPose();
        poseStack.mulPose(
                new Quaternionf()
                        .rotationY(Billboard.yawTowards(viewer, splat.getX(), splat.getZ())));
        // Fade out at the end of its life instead of popping out of existence.
        int alpha =
                (int)
                        (Mth.clamp(
                                        (EmojiSplat.LIFETIME - splat.tickCount)
                                                / (float) EmojiSplat.FADE_TICKS,
                                        0.0F,
                                        1.0F)
                                * 255.0F);
        VertexConsumer consumer =
                bufferSource.getBuffer(RenderType.entityTranslucentEmissive(sprite));
        Billboard.draw(consumer, poseStack.last(), EmojiSplat.SIZE * 0.5F, alpha);
        poseStack.popPose();
    }

    @Override
    public ResourceLocation getTextureLocation(EmojiSplat splat) {
        ResourceLocation sprite = Billboard.sprite(splat.item());
        return sprite == null ? FALLBACK_TEXTURE : sprite;
    }
}
