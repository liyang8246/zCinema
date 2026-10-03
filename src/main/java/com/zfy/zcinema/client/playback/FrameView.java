package com.zfy.zcinema.client.playback;

import net.minecraft.resources.ResourceLocation;

/** What the renderer needs for one screen: the uploaded frame texture and its size. */
public record FrameView(ResourceLocation texture, int width, int height) {
}
