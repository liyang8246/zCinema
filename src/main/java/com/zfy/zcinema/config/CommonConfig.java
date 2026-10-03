package com.zfy.zcinema.config;

import com.google.gson.JsonObject;

/**
 * Server side knobs. Loaded on server start from {@code config/zcinema-common.json}.
 */
public final class CommonConfig {
    private static final String FILE_NAME = "zcinema-common.json";

    /** How often (in ticks) the server broadcasts the full playback state to viewers. */
    public static int syncIntervalTicks = 20;
    /** When viewers agree the source is unreachable, pause the shared clock for everyone. */
    public static boolean globalStallPause = true;

    private CommonConfig() {}

    public static void load() {
        JsonObject root = JsonConfig.read(FILE_NAME);
        syncIntervalTicks = JsonConfig.getInt(root, "syncIntervalTicks", syncIntervalTicks, 2, 200);
        globalStallPause = JsonConfig.getBoolean(root, "globalStallPause", globalStallPause);

        JsonObject out = root.deepCopy();
        out.addProperty("syncIntervalTicks", syncIntervalTicks);
        out.addProperty("globalStallPause", globalStallPause);
        JsonConfig.write(FILE_NAME, out);
    }
}
