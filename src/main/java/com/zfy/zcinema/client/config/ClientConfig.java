package com.zfy.zcinema.client.config;

import net.neoforged.neoforge.common.ModConfigSpec;
import org.apache.commons.lang3.tuple.Pair;

/** Client side knobs for decoding, buffering, sync tolerance and audio reach. */
public final class ClientConfig {
    public static final ModConfigSpec SPEC;
    public static final ClientConfig INSTANCE;

    public final ModConfigSpec.IntValue maxFrameWidth;
    public final ModConfigSpec.IntValue maxFrameHeight;
    public final ModConfigSpec.DoubleValue bufferSeconds;
    public final ModConfigSpec.IntValue fpsCap;
    public final ModConfigSpec.IntValue audioDistance;
    public final ModConfigSpec.DoubleValue hardResyncSeconds;
    public final ModConfigSpec.IntValue localStallMs;

    static {
        Pair<ClientConfig, ModConfigSpec> pair = new ModConfigSpec.Builder().configure(ClientConfig::new);
        INSTANCE = pair.getLeft();
        SPEC = pair.getRight();
    }

    private ClientConfig(ModConfigSpec.Builder builder) {
        builder.push("stream");
        maxFrameWidth = builder
                .comment("Frames wider than this are downscaled while decoding (saves bandwidth/CPU).")
                .defineInRange("maxFrameWidth", 1920, 320, 7680);
        maxFrameHeight = builder
                .comment("Frames taller than this are downscaled while decoding.")
                .defineInRange("maxFrameHeight", 1080, 240, 4320);
        bufferSeconds = builder
                .comment("How far ahead of the shared clock this client decodes.")
                .defineInRange("bufferSeconds", 1.5, 0.25, 8.0);
        fpsCap = builder
                .comment("Frame rate used to size the decode buffer (and to decide when a frame is stale).")
                .defineInRange("fpsCap", 30, 5, 120);
        builder.pop();

        builder.push("sync");
        hardResyncSeconds = builder
                .comment("If the shared clock and local playback diverge by more than this, reopen the stream.")
                .defineInRange("hardResyncSeconds", 2.5, 0.5, 30.0);
        localStallMs = builder
                .comment("If this client runs out of buffered video for this long it tells the server, which pauses everyone until it recovers.")
                .defineInRange("localStallMs", 700, 150, 10_000);
        builder.pop();

        builder.push("audio");
        audioDistance = builder
                .comment("How far away the screen audio can be heard.")
                .defineInRange("audioDistance", 48, 4, 256);
        builder.pop();
    }

    private ClientConfig() {
        this(new ModConfigSpec.Builder());
    }
}
