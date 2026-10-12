package com.zfy.zcinema.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.zfy.zcinema.entity.ThrownEmoji;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * The emoji on its way to whatever it is about to hit: the same upright billboard as the picture it
 * leaves behind, two blocks across instead of three.
 *
 * <p>It is not drawn near the thrower. An emoji leaving the hand would be a two block wide flat
 * image right in front of the camera, filling the screen for a frame or two. Everything here is
 * measured against the position the picture is actually drawn at this frame rather than the
 * entity's ticked position: at seven and a half blocks a tick those differ by several blocks, and
 * testing the ticked one made the emoji flash in and out of the thrower's face.
 */
public class ThrownEmojiRenderer extends EntityRenderer<ThrownEmoji> {
    /** Inside this distance from the camera the flying emoji is not drawn at all. */
    private static final double HIDDEN_WITHIN = 4.0D;
    /** Over the next blocks it fades in, so leaving the hand is not a hard cut. */
    private static final double FADE_IN_BLOCKS = 2.0D;
    private static final ResourceLocation FALLBACK_TEXTURE =
            ResourceLocation.withDefaultNamespace("textures/misc/white.png");

    public ThrownEmojiRenderer(EntityRendererProvider.Context context) {
        super(context);
    }

    @Override
    public void render(ThrownEmoji thrown, float entityYaw, float partialTick, PoseStack poseStack,
                       MultiBufferSource bufferSource, int packedLight) {
        ResourceLocation sprite = Billboard.sprite(thrown.getItem());
        if (sprite == null) return;

        poseStack.pushPose();
        // The dispatcher has already moved the pose to where the emoji is drawn this frame, so its
        // translation is the camera space offset of the picture itself.
        Vector3f offset = poseStack.last().pose().getTranslation(new Vector3f());
        double distance = Math.sqrt(offset.x * offset.x + offset.y * offset.y + offset.z * offset.z);
        int alpha = (int) (Mth.clamp((distance - HIDDEN_WITHIN) / FADE_IN_BLOCKS, 0.0D, 1.0D) * 255.0D);
        if (alpha <= 0) {
            poseStack.popPose();
            return;
        }

        // Same interpolation the renderer used to place the quad, so the facing matches the drawing.
        double drawnX = Mth.lerp(partialTick, thrown.xOld, thrown.getX());
        double drawnZ = Mth.lerp(partialTick, thrown.zOld, thrown.getZ());
        Vec3 viewer = this.entityRenderDispatcher.camera.getPosition();
        poseStack.mulPose(new Quaternionf().rotationY(Billboard.yawTowards(viewer, drawnX, drawnZ)));
        VertexConsumer consumer = bufferSource.getBuffer(RenderType.entityTranslucentEmissive(sprite));
        Billboard.draw(consumer, poseStack.last(), ThrownEmoji.SIZE * 0.5F, alpha);
        poseStack.popPose();
    }

    @Override
    public ResourceLocation getTextureLocation(ThrownEmoji thrown) {
        ResourceLocation sprite = Billboard.sprite(thrown.getItem());
        return sprite == null ? FALLBACK_TEXTURE : sprite;
    }
}
