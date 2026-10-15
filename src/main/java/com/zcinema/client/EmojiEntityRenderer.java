package com.zcinema.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.zcinema.EmojiEntity;
import com.zcinema.EmojiItem;
import com.zcinema.ZCinemaMod;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import org.joml.Matrix4f;
import org.joml.Vector3f;

public class EmojiEntityRenderer extends EntityRenderer<EmojiEntity> {
    private static final ResourceLocation[] TEXTURES = new ResourceLocation[EmojiItem.EMOJI_IDS.length];

    static {
        for (int i = 0; i < EmojiItem.EMOJI_IDS.length; i++) {
            TEXTURES[i] = ResourceLocation.fromNamespaceAndPath(
                    ZCinemaMod.MOD_ID, "textures/item/" + EmojiItem.EMOJI_IDS[i] + ".png"
            );
        }
    }

    public EmojiEntityRenderer(EntityRendererProvider.Context context) {
        super(context);
    }

    @Override
    public void render(EmojiEntity entity, float entityYaw, float partialTick, PoseStack poseStack, MultiBufferSource bufferSource, int packedLight) {
        boolean landed = entity.isLanded();
        float halfSize = landed ? 1.5F : 1.0F; // 3x3 when landed, 2x2 in flight
        float alpha = 1.0F;
        float yOffset = 0.0F;

        if (landed) {
            float elapsed = entity.getLandedElapsedSeconds(partialTick);
            if (elapsed >= EmojiEntity.LANDED_DURATION_SEC) return;
            alpha = EmojiEntity.computeAlpha(elapsed);
            yOffset = EmojiEntity.computeRiseOffset(elapsed);
        }

        int alphaByte = Mth.clamp(Math.round(alpha * 255.0F), 0, 255);
        if (alphaByte <= 0) return;

        poseStack.pushPose();
        if (landed) {
            Vector3f lp = entity.getLandedPos();
            double lerpX = Mth.lerp(partialTick, entity.xOld, entity.getX());
            double lerpY = Mth.lerp(partialTick, entity.yOld, entity.getY());
            double lerpZ = Mth.lerp(partialTick, entity.zOld, entity.getZ());
            poseStack.translate(lp.x - lerpX, (lp.y - lerpY) + yOffset, lp.z - lerpZ);
        }

        // Always face the player's camera view (2D billboard)
        poseStack.mulPose(this.entityRenderDispatcher.cameraOrientation());

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(alphaByte == 255);
        RenderSystem.disableCull();
        RenderSystem.setShader(GameRenderer::getPositionTexColorShader);
        RenderSystem.setShaderTexture(0, getTextureLocation(entity));

        BufferBuilder buf = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
        Matrix4f mat = poseStack.last().pose();
        buf.addVertex(mat, -halfSize, -halfSize, 0.0F).setUv(0.0F, 1.0F).setColor(255, 255, 255, alphaByte);
        buf.addVertex(mat,  halfSize, -halfSize, 0.0F).setUv(1.0F, 1.0F).setColor(255, 255, 255, alphaByte);
        buf.addVertex(mat,  halfSize,  halfSize, 0.0F).setUv(1.0F, 0.0F).setColor(255, 255, 255, alphaByte);
        buf.addVertex(mat, -halfSize,  halfSize, 0.0F).setUv(0.0F, 0.0F).setColor(255, 255, 255, alphaByte);
        BufferUploader.drawWithShader(buf.buildOrThrow());

        RenderSystem.enableCull();
        RenderSystem.depthMask(true);
        RenderSystem.disableBlend();
        poseStack.popPose();
    }

    @Override
    public boolean shouldRender(EmojiEntity livingEntity, Frustum camera, double camX, double camY, double camZ) {
        return true;
    }

    @Override
    public ResourceLocation getTextureLocation(EmojiEntity entity) {
        return TEXTURES[entity.getEmojiIndex()];
    }
}
