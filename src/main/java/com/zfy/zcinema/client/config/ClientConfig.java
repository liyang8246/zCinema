package com.zfy.zcinema.client.config;

import com.google.gson.JsonObject;
import com.zfy.zcinema.ZCinemaLog;
import com.zfy.zcinema.config.JsonConfig;

/**
 * Client side knobs for decoding, buffering, sync tolerance and audio delay. Loaded on client
 * start from {@code config/zcinema-client.json}.
 */
public final class ClientConfig {
    private static final String FILE_NAME = "zcinema-client.json";

    /** Frames wider than this are downscaled while decoding (saves bandwidth/CPU). */
    public static int maxFrameWidth = 1920;

    /** Frames taller than this are downscaled while decoding. */
    public static int maxFrameHeight = 1080;

    /** How far ahead of the shared clock this client decodes. */
    public static double bufferSeconds = 1.5;

    /** If the shared clock and local playback diverge by more than this, the stream seeks. */
    public static double hardResyncSeconds = 3.0;

    /**
     * The sound is played this many milliseconds behind the displayed picture.
     *
     * <p>The audio position is now measured on the media timeline (what the engine has been
     * given), so it is directly comparable with the displayed frame and needs no guessing. What
     * is left to compensate is only the part nobody in Java can see: the engine's own queue plus
     * your audio device's buffer, typically 20-50ms. That is a stable hardware constant, so this
     * is a one-time calibration rather than a moving target.
     *
     * <p>Calibrate with a clip that has a sharp sound (a clap, a click) and raise this by 20ms at
     * a time until the sound lands on the picture. If the sound leads the picture, raise it; if it
     * lags, lower it. 0 is the honest default: no compensation until you have measured yours.
     */
    public static int audioDelayMs = 0;

    private ClientConfig() {}

    public static void load() {
        JsonObject root = JsonConfig.read(FILE_NAME);
        maxFrameWidth = JsonConfig.getInt(root, "maxFrameWidth", maxFrameWidth, 320, 7680);
        maxFrameHeight = JsonConfig.getInt(root, "maxFrameHeight", maxFrameHeight, 240, 4320);
        bufferSeconds = JsonConfig.getDouble(root, "bufferSeconds", bufferSeconds, 0.25, 8.0);
        hardResyncSeconds =
                JsonConfig.getDouble(root, "hardResyncSeconds", hardResyncSeconds, 0.5, 30.0);
        audioDelayMs = JsonConfig.getInt(root, "audioDelayMs", audioDelayMs, 0, 500);

        JsonObject out = root.deepCopy();
        out.addProperty("maxFrameWidth", maxFrameWidth);
        out.addProperty("maxFrameHeight", maxFrameHeight);
        out.addProperty("bufferSeconds", bufferSeconds);
        out.addProperty("hardResyncSeconds", hardResyncSeconds);
        out.addProperty("audioDelayMs", audioDelayMs);
        JsonConfig.write(FILE_NAME, out);
        ZCinemaLog.log(
                "config",
                "client loaded: maxFrame=%dx%d buffer=%.2fs hardResync=%.2fs "
                        + "audioDelay=%dms (config/%s)",
                maxFrameWidth,
                maxFrameHeight,
                bufferSeconds,
                hardResyncSeconds,
                audioDelayMs,
                FILE_NAME);
    }
}
