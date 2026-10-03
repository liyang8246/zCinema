package com.zfy.zcinema.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.zfy.zcinema.ZCinema;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Tiny JSON config helper. Fabric has no built-in config API, and the mod only has a handful of
 * knobs, so each config is a pretty-printed JSON object in {@code config/}. Unknown keys survive
 * a load/save round-trip, values are clamped to the same ranges the NeoForge build declared.
 */
public final class JsonConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private JsonConfig() {}

    public static JsonObject read(String fileName) {
        Path path = path(fileName);
        if (Files.isRegularFile(path)) {
            try {
                JsonElement parsed = JsonParser.parseString(Files.readString(path));
                if (parsed.isJsonObject()) {
                    return parsed.getAsJsonObject();
                }
                ZCinema.LOGGER.warn("{} is not a JSON object; using defaults", path);
            } catch (Exception error) {
                ZCinema.LOGGER.warn("Could not read {}; using defaults", path, error);
            }
        }
        return new JsonObject();
    }

    public static void write(String fileName, JsonObject object) {
        Path path = path(fileName);
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, GSON.toJson(object) + System.lineSeparator());
        } catch (IOException error) {
            ZCinema.LOGGER.warn("Could not write {}", path, error);
        }
    }

    public static int getInt(JsonObject root, String key, int fallback, int min, int max) {
        JsonElement value = root.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            return fallback;
        }
        try {
            return Math.max(min, Math.min(max, value.getAsInt()));
        } catch (RuntimeException error) {
            return fallback;
        }
    }

    public static double getDouble(JsonObject root, String key, double fallback, double min, double max) {
        JsonElement value = root.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            return fallback;
        }
        try {
            return Math.max(min, Math.min(max, value.getAsDouble()));
        } catch (RuntimeException error) {
            return fallback;
        }
    }

    public static boolean getBoolean(JsonObject root, String key, boolean fallback) {
        JsonElement value = root.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) {
            return fallback;
        }
        try {
            return value.getAsBoolean();
        } catch (RuntimeException error) {
            return fallback;
        }
    }

    private static Path path(String fileName) {
        return FabricLoader.getInstance().getConfigDir().resolve(fileName);
    }
}
