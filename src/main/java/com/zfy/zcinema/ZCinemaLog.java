package com.zfy.zcinema;

import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.FabricLoader;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Diagnostics log. Every interesting event is appended to {@code logs/zcinema.log} with an
 * absolute epoch timestamp, the side marker (C/S) and a category, so two machines' logs can be
 * lined up next to each other. The normal logger stays for user-facing messages only; this file
 * is the one to attach to a bug report.
 *
 * <p>Line format:
 * <pre>
 *   epochMillis HH:mm:ss.SSS side [category] thread message
 * </pre>
 */
public final class ZCinemaLog {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
    private static final long MAX_BYTES = 8L * 1024L * 1024L;
    private static final Object LOCK = new Object();

    private static BufferedWriter writer;
    private static boolean unusable;
    private static String side = "?";

    private ZCinemaLog() {}

    /** One line per launch: identifies which machine and which side a file came from. */
    public static void header() {
        side = FabricLoader.getInstance().getEnvironmentType() == EnvType.CLIENT ? "C" : "S";
        String version = FabricLoader.getInstance().getModContainer(ZCinema.MODID)
                .map(container -> container.getMetadata().getVersion().getFriendlyString())
                .orElse("dev");
        log("lifecycle", "=== Z Cinema %s | side=%s | %s %s | java %s | gameDir=%s ===",
                version, side, System.getProperty("os.name"), System.getProperty("os.version"),
                System.getProperty("java.version"), FabricLoader.getInstance().getGameDir());
    }

    public static void log(String category, String format, Object... args) {
        String message = args.length == 0 ? format : String.format(Locale.ROOT, format, args);
        synchronized (LOCK) {
            BufferedWriter out = writer();
            if (out == null) return;
            try {
                out.write(Long.toString(System.currentTimeMillis()));
                out.write(' ');
                out.write(LocalTime.now().format(TIME));
                out.write(' ');
                out.write(side);
                out.write(" [");
                out.write(category);
                out.write("] ");
                out.write(threadTag());
                out.write(' ');
                out.write(message);
                out.newLine();
                out.flush();
            } catch (IOException error) {
                unusable = true;
            }
        }
    }

    /** Keeps URLs and packet dumps readable in a log line. */
    public static String shorten(String value, int max) {
        if (value == null) return "null";
        return value.length() <= max ? value : value.substring(0, max) + "...";
    }

    private static BufferedWriter writer() {
        if (unusable) return null;
        if (writer != null) return writer;
        try {
            Path directory = FabricLoader.getInstance().getGameDir().resolve("logs");
            Files.createDirectories(directory);
            Path file = directory.resolve("zcinema.log");
            if (Files.isRegularFile(file) && Files.size(file) > MAX_BYTES) {
                Files.move(file, directory.resolve("zcinema.log.1"), StandardCopyOption.REPLACE_EXISTING);
                ZCinema.LOGGER.info("Rotated logs/zcinema.log to zcinema.log.1");
            }
            writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException error) {
            unusable = true;
            ZCinema.LOGGER.warn("Could not open logs/zcinema.log; diagnostics stay in latest.log", error);
        }
        return writer;
    }

    private static String threadTag() {
        String name = Thread.currentThread().getName();
        if (name.startsWith("ZCinema Decoder")) return "decode";
        if (name.startsWith("ZCinema Audio")) return "audio-decode";
        if (name.startsWith("ZCinema Audio Open")) return "audio-open";
        if (name.equals("Render thread")) return "render";
        if (name.equals("Server thread")) return "server";
        if (name.startsWith("Sound engine")) return "sound";
        if (name.startsWith("Netty")) return "netty";
        if (name.startsWith("Worker")) return "worker";
        return name.length() <= 16 ? name : name.substring(0, 16);
    }
}
