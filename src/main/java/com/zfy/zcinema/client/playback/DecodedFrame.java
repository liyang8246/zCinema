package com.zfy.zcinema.client.playback;

import com.mojang.blaze3d.platform.NativeImage;

/** A decoded RGBA frame plus its position in the media timeline (seconds). */
public final class DecodedFrame {
    public final NativeImage image;
    public final double ts;
    public final int width;
    public final int height;

    private boolean closed;

    public DecodedFrame(NativeImage image, double ts) {
        this.image = image;
        this.ts = ts;
        this.width = image.getWidth();
        this.height = image.getHeight();
    }

    public void close() {
        if (closed) return;
        closed = true;
        image.close();
    }
}
