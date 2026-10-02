package com.zfy.zcinema.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.zfy.zcinema.blockentity.CinemaScreenBlockEntity;
import com.zfy.zcinema.client.playback.ClientPlayback;
import com.zfy.zcinema.client.playback.FrameView;
import com.zfy.zcinema.screen.ScreenArea;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * Draws the decoded video frame across the whole concrete rectangle of a screen. Picking the
 * frame happens here too, which is also what keeps the local stream session alive while the
 * screen is visible.
 */
public class CinemaScreenRenderer implements BlockEntityRenderer<CinemaScreenBlockEntity, CinemaScreenRenderState> {
    // The quad is nudged off the concrete face so it can never z-fight with the block itself.
    private static final float OFFSET = 0.03F;

    public CinemaScreenRenderer(BlockEntityRendererProvider.Context context) {
    }

    @Override
    public CinemaScreenRenderState createRenderState() {
        return new CinemaScreenRenderState();
    }

    @Override
    public void extractRenderState(CinemaScreenBlockEntity blockEntity, CinemaScreenRenderState renderState,
                                   float partialTick, Vec3 cameraPosition,
                                   ModelFeatureRenderer.@Nullable CrumblingOverlay breakProgress) {
        BlockEntityRenderer.super.extractRenderState(blockEntity, renderState, partialTick, cameraPosition, breakProgress);
        renderState.frame = ClientPlayback.frame(blockEntity);
        renderState.area = blockEntity.screenArea();
    }

    @Override
    public void submit(CinemaScreenRenderState renderState, PoseStack poseStack, SubmitNodeCollector collector,
                       CameraRenderState cameraRenderState) {
        FrameView frame = renderState.frame;
        ScreenArea area = renderState.area;
        if (frame == null || frame.texture() == null || area == null) return;

        BlockPos anchor = renderState.blockPos;
        // Wall-local rectangle extents (block units, relative to the anchor block).
        float minA;
        float minB;
        float maxA;
        float maxB;
        Direction normal;
        float[][] corners;
        boolean flipU;
        int axisA;
        int axisB;

        if (area.normal().getAxis() == Direction.Axis.Z) {
            axisA = 0;
            axisB = 1;
            minA = area.min().getX() - anchor.getX();
            maxA = area.max().getX() + 1 - anchor.getX();
            minB = area.min().getY() - anchor.getY();
            maxB = area.max().getY() + 1 - anchor.getY();
            boolean north = area.normal() == Direction.NORTH;
            float z = area.min().getZ() - anchor.getZ() + (north ? -OFFSET : 1.0F + OFFSET);
            normal = north ? Direction.NORTH : Direction.SOUTH;
            corners = corners(minA, minB, maxA, maxB, z);
            flipU = north; // a viewer north of the wall reads the picture left to right along -X
        } else if (area.normal().getAxis() == Direction.Axis.X) {
            axisA = 2;
            axisB = 1;
            minA = area.min().getZ() - anchor.getZ();
            maxA = area.max().getZ() + 1 - anchor.getZ();
            minB = area.min().getY() - anchor.getY();
            maxB = area.max().getY() + 1 - anchor.getY();
            boolean west = area.normal() == Direction.WEST;
            float x = area.min().getX() - anchor.getX() + (west ? -OFFSET : 1.0F + OFFSET);
            normal = west ? Direction.WEST : Direction.EAST;
            corners = cornersWithAxisX(minA, minB, maxA, maxB, x);
            flipU = !west; // a viewer east of the wall sees z run backwards
        } else {
            axisA = 0;
            axisB = 2;
            minA = area.min().getX() - anchor.getX();
            maxA = area.max().getX() + 1 - anchor.getX();
            minB = area.min().getZ() - anchor.getZ();
            maxB = area.max().getZ() + 1 - anchor.getZ();
            boolean up = area.normal() == Direction.UP;
            float y = area.min().getY() - anchor.getY() + (up ? 1.0F + OFFSET : -OFFSET);
            normal = up ? Direction.UP : Direction.DOWN;
            corners = cornersWithAxisY(minA, minB, maxA, maxB, y);
            flipU = false;
        }

        float[][] fitted = letterbox(area.screenWidth(), area.screenHeight(), frame.width(), frame.height(),
                corners, axisA, axisB);
        poseStack.pushPose();
        float[][] finalCorners = fitted;
        boolean finalFlip = flipU;
        Direction finalNormal = normal;
        collector.submitCustomGeometry(poseStack, RenderTypes.entityTranslucentEmissive(frame.texture()),
                (pose, consumer) -> drawQuad(consumer, pose, finalNormal, finalCorners, finalFlip));
        poseStack.popPose();
    }

    /** Full rectangle corners in block space (bl, tl, tr, br). */
    private float[][] corners(float minX, float minY, float maxX, float maxY, float z) {
        return new float[][]{
                {minX, minY, z},
                {minX, maxY, z},
                {maxX, maxY, z},
                {maxX, minY, z},
        };
    }

    private float[][] cornersWithAxisX(float minZ, float minY, float maxZ, float maxY, float x) {
        return new float[][]{
                {x, minY, minZ},
                {x, maxY, minZ},
                {x, maxY, maxZ},
                {x, minY, maxZ},
        };
    }

    private float[][] cornersWithAxisY(float minX, float minZ, float maxX, float maxZ, float y) {
        return new float[][]{
                {minX, y, minZ},
                {minX, y, maxZ},
                {maxX, y, maxZ},
                {maxX, y, minZ},
        };
    }

    /** Shrinks the rectangle to the video aspect ratio (letterboxing). */
    private float[][] letterbox(int screenWidth, int screenHeight, int frameWidth, int frameHeight, float[][] quad,
                                int axisA, int axisB) {
        float screenAspect = screenWidth / (float) Math.max(1, screenHeight);
        float videoAspect = frameWidth > 0 && frameHeight > 0 ? frameWidth / (float) frameHeight : 16.0F / 9.0F;
        float minA = Math.min(Math.min(quad[0][axisA], quad[1][axisA]), Math.min(quad[2][axisA], quad[3][axisA]));
        float maxA = Math.max(Math.max(quad[0][axisA], quad[1][axisA]), Math.max(quad[2][axisA], quad[3][axisA]));
        float minB = Math.min(Math.min(quad[0][axisB], quad[1][axisB]), Math.min(quad[2][axisB], quad[3][axisB]));
        float maxB = Math.max(Math.max(quad[0][axisB], quad[1][axisB]), Math.max(quad[2][axisB], quad[3][axisB]));
        float halfA = (maxA - minA) * 0.5F;
        float halfB = (maxB - minB) * 0.5F;
        if (videoAspect > screenAspect) halfB = halfA / videoAspect;
        else halfA = halfB * videoAspect;
        float centerA = (maxA + minA) * 0.5F;
        float centerB = (maxB + minB) * 0.5F;
        float[][] out = new float[4][3];
        for (int i = 0; i < 4; i++) out[i] = quad[i].clone();
        // corners are ordered bl, tl, tr, br
        out[0][axisA] = centerA - halfA;
        out[1][axisA] = centerA - halfA;
        out[2][axisA] = centerA + halfA;
        out[3][axisA] = centerA + halfA;
        out[0][axisB] = centerB - halfB;
        out[1][axisB] = centerB + halfB;
        out[2][axisB] = centerB + halfB;
        out[3][axisB] = centerB - halfB;
        return out;
    }
    private void drawQuad(VertexConsumer consumer, PoseStack.Pose pose, Direction normal, float[][] c, boolean flipU) {
        float[][] uvs = {
                {0.0F, 1.0F},
                {0.0F, 0.0F},
                {1.0F, 0.0F},
                {1.0F, 1.0F},
        };
        if (flipU) {
            for (float[] uv : uvs) uv[0] = 1.0F - uv[0];
        }
        for (int i = 0; i < 4; i++) {
            consumer.addVertex(pose, c[i][0], c[i][1], c[i][2])
                    .setColor(255, 255, 255, 255)
                    .setUv(uvs[i][0], uvs[i][1])
                    .setOverlay(OverlayTexture.NO_OVERLAY)
                    .setLight(15728880)
                    .setNormal(pose, normal.getStepX(), normal.getStepY(), normal.getStepZ());
        }
    }
}
