package com.zfy.zcinema.net.packets;

import com.zfy.zcinema.ZCinema;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Paste (or clear) the MP4 direct link this screen plays. */
public record C2SSetUrlPacket(BlockPos pos, String url) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<C2SSetUrlPacket> TYPE =
            new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(ZCinema.MODID, "set_url"));

    public static final StreamCodec<ByteBuf, C2SSetUrlPacket> STREAM_CODEC = StreamCodec.composite(
            BlockPos.STREAM_CODEC, C2SSetUrlPacket::pos,
            ByteBufCodecs.stringUtf8(2048), C2SSetUrlPacket::url,
            C2SSetUrlPacket::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
