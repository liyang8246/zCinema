package com.zfy.zcinema.net.packets;

import com.zfy.zcinema.ZCinema;
import com.zfy.zcinema.screen.ScreenArea;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Full playback snapshot for one screen. Sent on every change and on a short interval so every
 * viewer converges on the same timeline, no matter when they joined. Carries the screen geometry
 * as well so clients always know which rectangle of concrete is showing the video.
 */
public record S2CStatePacket(
        BlockPos pos,
        String url,
        long positionMs,
        boolean playing,
        boolean frozen,
        long durationMs,
        ScreenArea area)
        implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<S2CStatePacket> TYPE =
            new CustomPacketPayload.Type<>(ZCinema.id("state"));

    public static final StreamCodec<RegistryFriendlyByteBuf, S2CStatePacket> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public void encode(RegistryFriendlyByteBuf buffer, S2CStatePacket packet) {
                    BlockPos.STREAM_CODEC.encode(buffer, packet.pos());
                    ByteBufCodecs.STRING_UTF8.encode(buffer, packet.url());
                    buffer.writeVarLong(packet.positionMs());
                    buffer.writeBoolean(packet.playing());
                    buffer.writeBoolean(packet.frozen());
                    buffer.writeVarLong(packet.durationMs());
                    ScreenArea.STREAM_CODEC.encode(buffer, packet.area());
                }

                @Override
                public S2CStatePacket decode(RegistryFriendlyByteBuf buffer) {
                    return new S2CStatePacket(
                            BlockPos.STREAM_CODEC.decode(buffer),
                            ByteBufCodecs.STRING_UTF8.decode(buffer),
                            buffer.readVarLong(),
                            buffer.readBoolean(),
                            buffer.readBoolean(),
                            buffer.readVarLong(),
                            ScreenArea.STREAM_CODEC.decode(buffer));
                }
            };

    /** The snapshot a screen without a measured rectangle sends. */
    public static S2CStatePacket withoutArea(
            BlockPos pos,
            String url,
            long positionMs,
            boolean playing,
            boolean frozen,
            long durationMs) {
        return new S2CStatePacket(pos, url, positionMs, playing, frozen, durationMs, null);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
