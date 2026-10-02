package com.zfy.zcinema.client.playback;

import net.minecraft.resources.Identifier;

/** What the renderer needs for one screen: the uploaded frame texture and its size. */
public record FrameView(Identifier texture, int width, int height) {
}
