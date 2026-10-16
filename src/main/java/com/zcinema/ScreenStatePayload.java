package com.zcinema;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record ScreenStatePayload(
    BlockPos pos, String url, boolean playing, long mediaPosMs, long serverTimestampMs)
    implements CustomPacketPayload {

  public static final Type<ScreenStatePayload> TYPE =
      new Type<>(ResourceLocation.fromNamespaceAndPath(ZCinemaMod.MOD_ID, "screen_state"));

  public static final StreamCodec<ByteBuf, ScreenStatePayload> CODEC =
      StreamCodec.composite(
          BlockPos.STREAM_CODEC,
          ScreenStatePayload::pos,
          ByteBufCodecs.STRING_UTF8,
          ScreenStatePayload::url,
          ByteBufCodecs.BOOL,
          ScreenStatePayload::playing,
          ByteBufCodecs.VAR_LONG,
          ScreenStatePayload::mediaPosMs,
          ByteBufCodecs.VAR_LONG,
          ScreenStatePayload::serverTimestampMs,
          ScreenStatePayload::new);

  @Override
  public Type<? extends CustomPacketPayload> type() {
    return TYPE;
  }
}
