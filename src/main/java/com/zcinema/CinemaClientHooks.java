package com.zcinema;

import net.minecraft.core.BlockPos;
import java.util.function.Consumer;

public final class CinemaClientHooks {
    public static Consumer<BlockPos> openControlScreen = pos -> {};
    public static Consumer<CinemaCoreBlockEntity> onBlockEntitySync = be -> {};
    public static Consumer<BlockPos> onBlockEntityRemoved = pos -> {};

    private CinemaClientHooks() {}
}
