package com.zfy.zcinema.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.zfy.zcinema.entity.ThrownEmoji;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;

/**
 * The emoji on its way to whatever it is about to hit: the same upright billboard as the picture it
 * leaves behind, two blocks across instead of three.
 *
 * <p>It is not drawn near the thrower. An emoji leaving the hand would be a two block wide flat
 * image right in front of the camera, filling the screen for a frame or two.
 */
public class ThrownEmojiRenderer extends EntityRenderer<ThrownEmoji> {
    /** What the flying emoji is drawn at: two blocks across and two blocks tall. */
    public static final float SIZE = 2.0F;
    /** Inside this distance from the camera the flying emoji is skipped. */
    private static final double HIDE_WITHIN = 4.0D;
    private static final ResourceLocation FALLBACK_TEXTURE =
            ResourceLocation.withDefaultNamespace("textures/misc/white.png");

    public ThrownEmojiRenderer(EntityRendererProvider.Context context) {
        super(context);
    }

    @Override
    public boolean shouldRender(ThrownEmoji thrown, Frustum frustum, double camX, double camY, double camZ) {
        if (thrown.distanceToSqr(camX, camY, camZ) < HIDE_WITHIN * HIDE_WITHIN) {
            return false;
        }
        return super.shouldRender(thrown, frustum, camX, camY, camZ);
    }

    @Override
    public void render(ThrownEmoji thrown, float entityYaw, float partialTick, PoseStack poseStack,
                       MultiBufferSource bufferSource, int packedLight) {
        ResourceLocation sprite = Billboard.sprite(thrown.getItem());
        if (sprite == null) return;

        Vec3 viewer = this.entityRenderDispatcher.camera.getPosition();
        poseStack.pushPose();
        poseStack.mulPose(new Quaternionf().rotationY(Billboard.yawTowards(viewer, thrown.getX(), thrown.getZ())));
        VertexConsumer consumer = bufferSource.getBuffer(RenderType.entityTranslucentEmissive(sprite));
        Billboard.draw(consumer, poseStack.last(), SIZE * 0.5F, 255);
        poseStack.popPose();
    }

    @Override
    public ResourceLocation getTextureLocation(ThrownEmoji thrown) {
        ResourceLocation sprite = Billboard.sprite(thrown.getItem());
        return sprite == null ? FALLBACK_TEXTURE : sprite;
    }
}
