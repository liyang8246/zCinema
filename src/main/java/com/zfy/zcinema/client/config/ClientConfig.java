package com.zfy.zcinema.client.config;

import com.google.gson.JsonObject;
import com.zfy.zcinema.ZCinemaLog;
import com.zfy.zcinema.config.JsonConfig;

/**
 * Client side knobs for decoding, buffering, sync tolerance and audio reach. Loaded on client
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
    /** How far away the screen audio can be heard. */
    public static int audioDistance = 48;

    private ClientConfig() {}

    public static void load() {
        JsonObject root = JsonConfig.read(FILE_NAME);
        maxFrameWidth = JsonConfig.getInt(root, "maxFrameWidth", maxFrameWidth, 320, 7680);
        maxFrameHeight = JsonConfig.getInt(root, "maxFrameHeight", maxFrameHeight, 240, 4320);
        bufferSeconds = JsonConfig.getDouble(root, "bufferSeconds", bufferSeconds, 0.25, 8.0);
        hardResyncSeconds = JsonConfig.getDouble(root, "hardResyncSeconds", hardResyncSeconds, 0.5, 30.0);
        audioDistance = JsonConfig.getInt(root, "audioDistance", audioDistance, 4, 256);

        JsonObject out = root.deepCopy();
        out.addProperty("maxFrameWidth", maxFrameWidth);
        out.addProperty("maxFrameHeight", maxFrameHeight);
        out.addProperty("bufferSeconds", bufferSeconds);
        out.addProperty("hardResyncSeconds", hardResyncSeconds);
        out.addProperty("audioDistance", audioDistance);
        JsonConfig.write(FILE_NAME, out);
        ZCinemaLog.log("config", "client loaded: maxFrame=%dx%d buffer=%.2fs hardResync=%.2fs audioDistance=%d "
                        + "(config/%s)",
                maxFrameWidth, maxFrameHeight, bufferSeconds, hardResyncSeconds, audioDistance, FILE_NAME);
    }
}
