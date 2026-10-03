package com.zfy.zcinema.client.render;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.zfy.zcinema.ZCinema;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.resources.Identifier;
import net.neoforged.neoforge.client.event.RegisterRenderPipelinesEvent;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The video surface gets its own render pipeline instead of borrowing a vanilla one, because the
 * picture must come out exactly as decoded:
 *
 * <ul>
 *   <li>{@code EMISSIVE}: the lightmap is not applied, so a dark room, the Nether's warm lightmap
 *       or a night-time sky cannot tint or dim the film.</li>
 *   <li>{@code NO_CARDINAL_LIGHTING}: the per-face diffuse factor is skipped, which otherwise
 *       darkens a wall depending on the direction it faces.</li>
 *   <li>No blending and no alpha cutout: the surface is always opaque, whatever the frame's alpha
 *       byte happens to be.</li>
 * </ul>
 *
 * <p>Using our own pipeline is also the hook for shader pack support later: packs patch vanilla
 * pipelines, and this one is ours to declare.
 */
public final class ModRenderPipelines {
    public static final RenderPipeline SCREEN = RenderPipeline.builder(RenderPipelines.MATRICES_FOG_SNIPPET)
            .withLocation(Identifier.fromNamespaceAndPath(ZCinema.MODID, "pipeline/screen"))
            .withVertexShader("core/entity")
            .withFragmentShader("core/entity")
            .withShaderDefine("EMISSIVE")
            .withShaderDefine("NO_OVERLAY")
            .withShaderDefine("NO_CARDINAL_LIGHTING")
            .withSampler("Sampler0")
            .withCull(false)
            .withVertexFormat(DefaultVertexFormat.NEW_ENTITY, VertexFormat.Mode.QUADS)
            .build();

    private static final Map<Identifier, RenderType> SCREEN_TYPES = new ConcurrentHashMap<>();

    private ModRenderPipelines() {}

    /** Called on the mod event bus, before the shader manager compiles the pipelines. */
    public static void register(RegisterRenderPipelinesEvent event) {
        event.registerPipeline(SCREEN);
    }

    /** The render type for one screen's texture. Cached because a screen's texture is stable. */
    public static RenderType screen(Identifier texture) {
        return SCREEN_TYPES.computeIfAbsent(texture, id -> RenderType.create("zcinema_screen",
                RenderSetup.builder(SCREEN).withTexture("Sampler0", id).createRenderSetup()));
    }
}
