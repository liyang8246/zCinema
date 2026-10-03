package com.zfy.zcinema.net.packets;

import com.zfy.zcinema.ZCinema;
import com.zfy.zcinema.blockentity.CinemaScreenBlockEntity;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * How the local decoder is doing, reported about once a second by every viewer. The server folds
 * all reports together and only freezes the shared clock when they agree that something is wrong
 * for a while - so one viewer's hiccup never stutters playback for everyone else.
 */
public record C2SHealthPacket(BlockPos pos, CinemaScreenBlockEntity.PlaybackHealth health)
        implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<C2SHealthPacket> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(ZCinema.MODID, "health"));

    public static final StreamCodec<ByteBuf, C2SHealthPacket> STREAM_CODEC = StreamCodec.composite(
            BlockPos.STREAM_CODEC, C2SHealthPacket::pos,
            ByteBufCodecs.stringUtf8(16), p -> p.health().name(),
            (pos, name) -> new C2SHealthPacket(pos, CinemaScreenBlockEntity.PlaybackHealth.valueOf(name)));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
