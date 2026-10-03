package com.zfy.zcinema.net.packets;

import com.zfy.zcinema.ZCinema;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Timeline control event. Every viewer sends these; the server folds them into the single shared
 * timeline and broadcasts the result, so a click from anyone moves everybody at once.
 */
public record C2SControlPacket(BlockPos pos, Action action, long positionMs) implements CustomPacketPayload {
    public enum Action {
        PLAY,
        PAUSE,
        SEEK,
        REMOVE
    }

    public static final CustomPacketPayload.Type<C2SControlPacket> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(ZCinema.MODID, "control"));

    public static final StreamCodec<ByteBuf, C2SControlPacket> STREAM_CODEC = StreamCodec.composite(
            BlockPos.STREAM_CODEC, C2SControlPacket::pos,
            ByteBufCodecs.stringUtf8(16), p -> p.action().name(),
            ByteBufCodecs.VAR_LONG, C2SControlPacket::positionMs,
            (pos, name, position) -> new C2SControlPacket(pos, Action.valueOf(name), position));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
