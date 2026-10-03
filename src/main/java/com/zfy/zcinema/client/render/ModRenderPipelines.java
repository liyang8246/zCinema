package com.zfy.zcinema.client.render;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The video surface draws through the core {@code position_tex} shader: texture colour straight to
 * the framebuffer, no lightmap, no per-face diffuse shading and no fog. That is the 1.21.1
 * equivalent of the dedicated emissive render pipeline the newer branch registers.
 *
 * <p>The surface is always opaque: no blending, no alpha cutout, whatever the frame's alpha byte
 * happens to be.
 */
public final class ModRenderPipelines {
    private static final Map<ResourceLocation, RenderType> SCREEN_TYPES = new ConcurrentHashMap<>();

    private ModRenderPipelines() {}

    /** The render type for one screen's texture. Cached because a screen's texture is stable. */
    public static RenderType screen(ResourceLocation texture) {
        return SCREEN_TYPES.computeIfAbsent(texture, id -> RenderType.create(
                "zcinema_screen",
                DefaultVertexFormat.POSITION_TEX,
                VertexFormat.Mode.QUADS,
                256,
                false,
                false,
                RenderType.CompositeState.builder()
                        .setShaderState(new RenderStateShard.ShaderStateShard(GameRenderer::getPositionTexShader))
                        .setTextureState(new RenderStateShard.TextureStateShard(id, false, false))
                        .setTransparencyState(RenderStateShard.NO_TRANSPARENCY)
                        .setCullState(RenderStateShard.NO_CULL)
                        .setLightmapState(RenderStateShard.NO_LIGHTMAP)
                        .setOverlayState(RenderStateShard.NO_OVERLAY)
                        .createCompositeState(false)));
    }
}
