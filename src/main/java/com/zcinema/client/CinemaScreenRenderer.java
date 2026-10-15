package com.zcinema.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.math.Axis;
import com.zcinema.CinemaCoreBlockEntity;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.watermedia.api.media.players.MediaPlayer;

public class CinemaScreenRenderer implements BlockEntityRenderer<CinemaCoreBlockEntity> {
    public CinemaScreenRenderer(BlockEntityRendererProvider.Context ctx) {}

    @Override
    public void render(CinemaCoreBlockEntity be, float partialTick, PoseStack poseStack, MultiBufferSource bufferSource, int packedLight, int packedOverlay) {
        ScreenPlayerManager.Entry entry = ScreenPlayerManager.get(be.getBlockPos());
        if (entry == null || entry.player == null) return;
        MediaPlayer player = entry.player;
        int texId = (int) player.texture();
        if (texId <= 0) return;

        poseStack.pushPose();
        poseStack.translate(0.5f, 0.5f, 0.5f);
        poseStack.mulPose(Axis.YP.rotationDegrees(-be.facing.toYRot()));

        // Core block is 1 block left of the screen's bottom-left corner:
        // In local space (+X = right along screen, +Y = up, +Z = facing viewer):
        // Screen spans X in [0.5, 0.5 + width], Y in [-0.5, -0.5 + height].
        // Front face floats 0.1 blocks off the block (Z = 0.6) to avoid depth z-fighting that
        // shows up as black flicker when viewed from far away.
        float x0 = 0.5f;
        float x1 = 0.5f + be.width;
        float y0 = -0.5f;
        float y1 = -0.5f + be.height;
        float z = 0.6f;

        RenderSystem.enableDepthTest();
        RenderSystem.setShader(GameRenderer::getPositionTexColorShader);
        RenderSystem.setShaderTexture(0, texId);

        BufferBuilder buf = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
        Matrix4f mat = poseStack.last().pose();
        buf.addVertex(mat, x0, y0, z).setUv(0.0f, 1.0f).setColor(255, 255, 255, 255);
        buf.addVertex(mat, x1, y0, z).setUv(1.0f, 1.0f).setColor(255, 255, 255, 255);
        buf.addVertex(mat, x1, y1, z).setUv(1.0f, 0.0f).setColor(255, 255, 255, 255);
        buf.addVertex(mat, x0, y1, z).setUv(0.0f, 0.0f).setColor(255, 255, 255, 255);
        BufferUploader.drawWithShader(buf.buildOrThrow());

        poseStack.popPose();
    }

    @Override
    public boolean shouldRenderOffScreen(CinemaCoreBlockEntity be) {
        return true;
    }

    @Override
    public int getViewDistance() {
        return 512;
    }

    @Override
    public boolean shouldRender(CinemaCoreBlockEntity be, Vec3 cameraPos) {
        return true;
    }
}
