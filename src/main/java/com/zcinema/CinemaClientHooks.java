package com.zcinema;

import java.util.function.Consumer;
import net.minecraft.core.BlockPos;

public final class CinemaClientHooks {
  public static Consumer<BlockPos> openControlScreen = pos -> {};
  public static Consumer<CinemaCoreBlockEntity> onBlockEntitySync = be -> {};
  public static Consumer<BlockPos> onBlockEntityRemoved = pos -> {};

  private CinemaClientHooks() {}
}
