package com.zfy.zcinema.client.playback;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.renderer.texture.DynamicTexture;

/**
 * Small indirection around {@link DynamicTexture} so frame upload stays in one place.
 * Both methods hand the NativeImage over to the texture, which then owns and closes it.
 */
final class FrameUploader {
    private FrameUploader() {}

    static DynamicTexture create(NativeImage image) {
        return new DynamicTexture(() -> "zcinema_stream", image);
    }

    static void update(DynamicTexture texture, NativeImage image) {
        texture.setPixels(image);
        texture.upload();
    }
}
