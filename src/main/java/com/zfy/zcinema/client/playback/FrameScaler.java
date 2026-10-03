package com.zfy.zcinema.client.playback;

import com.mojang.blaze3d.platform.NativeImage;
import com.zfy.zcinema.ZCinema;
import org.bytedeco.ffmpeg.avutil.AVFrame;
import org.bytedeco.ffmpeg.global.avutil;
import org.bytedeco.ffmpeg.global.swscale;
import org.bytedeco.ffmpeg.swscale.SwsContext;
import org.bytedeco.javacpp.BytePointer;
import org.bytedeco.javacpp.IntPointer;
import org.bytedeco.javacpp.PointerPointer;
import org.bytedeco.javacv.Frame;
import org.lwjgl.system.MemoryUtil;

import java.io.IOException;
import java.lang.reflect.Field;

/**
 * Scales raw FFmpeg frames into RGBA {@link NativeImage}s. Adapted from the approach used by
 * Create Cinema's network projector streams: decode in RAW mode and convert with swscale, then
 * copy straight into the native image memory when possible.
 */
final class FrameScaler implements AutoCloseable {
    private static final Field NATIVE_IMAGE_PIXELS = findNativeImagePixels();

    private SwsContext context;
    private BytePointer output;
    private PointerPointer<BytePointer> outputPlanes;
    private IntPointer outputStrides;
    private int sourceWidth;
    private int sourceHeight;
    private int sourceFormat;
    private int width;
    private int height;

    DecodedFrame decode(Frame frame, int maxWidth, int maxHeight) throws IOException {
        if (!(frame.opaque instanceof AVFrame raw)) return null;
        if (raw.format() < 0) return null;
        int rawWidth = raw.width();
        int rawHeight = raw.height();
        if (rawWidth <= 0 || rawHeight <= 0) return null;
        double factor = Math.min(1.0, Math.min(maxWidth / (double) rawWidth, maxHeight / (double) rawHeight));
        int targetWidth = Math.max(1, (int) Math.round(rawWidth * factor));
        int targetHeight = Math.max(1, (int) Math.round(rawHeight * factor));
        ensure(rawWidth, rawHeight, raw.format(), targetWidth, targetHeight);
        int rows = swscale.sws_scale(context, raw.data(), raw.linesize(), 0, rawHeight, outputPlanes, outputStrides);
        if (rows != targetHeight) throw new IOException("FFmpeg scaled " + rows + " of " + targetHeight + " rows");

        NativeImage image = new NativeImage(targetWidth, targetHeight, false);
        try {
            long byteCount = (long) targetWidth * targetHeight * 4L;
            output.position(0);
            if (NATIVE_IMAGE_PIXELS != null) {
                MemoryUtil.memCopy(output.address(), NATIVE_IMAGE_PIXELS.getLong(image), byteCount);
            } else {
                java.nio.IntBuffer pixels = output.position(0).capacity(byteCount)
                        .asByteBuffer().order(java.nio.ByteOrder.nativeOrder()).asIntBuffer();
                for (int y = 0; y < targetHeight; y++) {
                    for (int x = 0; x < targetWidth; x++) image.setPixelRGBA(x, y, pixels.get());
                }
            }
            return new DecodedFrame(image, 0.0);
        } catch (Throwable error) {
            image.close();
            if (error instanceof IOException io) throw io;
            throw new IOException("Could not copy a decoded video frame", error);
        }
    }

    private static String pixFmtName(int format) {
        try {
            BytePointer name = avutil.av_get_pix_fmt_name(format);
            return name == null ? String.valueOf(format) : name.getString();
        } catch (Throwable error) {
            return String.valueOf(format);
        }
    }

    private void ensure(int rawWidth, int rawHeight, int rawFormat, int targetWidth, int targetHeight) throws IOException {
        if (context != null && sourceWidth == rawWidth && sourceHeight == rawHeight && sourceFormat == rawFormat
                && width == targetWidth && height == targetHeight) return;
        close();
        ZCinema.LOGGER.info("Video scaler {}x{} {} -> {}x{}", rawWidth, rawHeight, pixFmtName(rawFormat),
                targetWidth, targetHeight);
        context = swscale.sws_getContext(rawWidth, rawHeight, rawFormat, targetWidth, targetHeight,
                avutil.AV_PIX_FMT_RGBA, swscale.SWS_BILINEAR, null, null, (double[]) null);
        if (context == null || context.isNull()) throw new IOException("FFmpeg could not create a video scaler");
        output = new BytePointer((long) targetWidth * targetHeight * 4L);
        // Belt and braces: every frame is RGBA with alpha 255, and pre-filling the buffer means a
        // scaler that leaves the alpha byte alone can never turn the screen translucent.
        MemoryUtil.memSet(output.address(), (byte) 0xFF, (long) targetWidth * targetHeight * 4L);
        outputPlanes = new PointerPointer<>(4);
        outputPlanes.put(0, output);
        outputStrides = new IntPointer(4);
        outputStrides.put(0, targetWidth * 4);
        sourceWidth = rawWidth;
        sourceHeight = rawHeight;
        sourceFormat = rawFormat;
        width = targetWidth;
        height = targetHeight;
    }

    private static Field findNativeImagePixels() {
        try {
            Field field = NativeImage.class.getDeclaredField("pixels");
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return null;
        }
    }

    @Override
    public void close() {
        if (context != null) swscale.sws_freeContext(context);
        if (output != null) output.close();
        if (outputPlanes != null) outputPlanes.close();
        if (outputStrides != null) outputStrides.close();
        context = null;
        output = null;
        outputPlanes = null;
        outputStrides = null;
    }
}
