package com.zfy.zcinema.net.packets;

import com.zfy.zcinema.ZCinema;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** A viewer learned the real duration of the stream and shares it with everybody. */
public record C2SReportMediaPacket(BlockPos pos, long durationMs) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<C2SReportMediaPacket> TYPE =
            new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(ZCinema.MODID, "report_media"));

    public static final StreamCodec<ByteBuf, C2SReportMediaPacket> STREAM_CODEC = StreamCodec.composite(
            BlockPos.STREAM_CODEC, C2SReportMediaPacket::pos,
            ByteBufCodecs.VAR_LONG, C2SReportMediaPacket::durationMs,
            C2SReportMediaPacket::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
