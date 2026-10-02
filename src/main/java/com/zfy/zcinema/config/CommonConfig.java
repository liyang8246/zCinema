package com.zfy.zcinema.config;

import net.neoforged.neoforge.common.ModConfigSpec;
import org.apache.commons.lang3.tuple.Pair;

public final class CommonConfig {
    public static final ModConfigSpec SPEC;
    public static final CommonConfig INSTANCE;

    public final ModConfigSpec.IntValue syncIntervalTicks;
    public final ModConfigSpec.BooleanValue globalStallPause;

    static {
        Pair<CommonConfig, ModConfigSpec> pair = new ModConfigSpec.Builder().configure(CommonConfig::new);
        INSTANCE = pair.getLeft();
        SPEC = pair.getRight();
    }

    private CommonConfig(ModConfigSpec.Builder builder) {
        builder.push("sync");
        syncIntervalTicks = builder
                .comment("How often (in ticks) the server broadcasts the full playback state to viewers.")
                .defineInRange("syncIntervalTicks", 20, 2, 200);
        globalStallPause = builder
                .comment("When one client stalls (network hiccup), pause the clock for everyone until it recovers.")
                .define("globalStallPause", true);
        builder.pop();
    }

    private CommonConfig() {
        this(new ModConfigSpec.Builder());
    }
}
