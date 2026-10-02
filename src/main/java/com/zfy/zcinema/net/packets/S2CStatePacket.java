package com.zfy.zcinema.net.packets;

import com.zfy.zcinema.ZCinema;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.resources.Identifier;

/**
 * Full playback snapshot for one screen. Sent on every change and on a short interval so every
 * viewer converges on the same timeline, no matter when they joined. Carries the screen geometry
 * as well so clients always know which rectangle of concrete is showing the video.
 */
public record S2CStatePacket(BlockPos pos, String url, long positionMs, boolean playing, boolean frozen,
                             long durationMs, boolean hasArea, long minX, long minY, long minZ, long maxX,
                             long maxY, long maxZ, int normal) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<S2CStatePacket> TYPE =
            new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(ZCinema.MODID, "state"));

    public static final StreamCodec<RegistryFriendlyByteBuf, S2CStatePacket> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public void encode(RegistryFriendlyByteBuf buffer, S2CStatePacket packet) {
            BlockPos.STREAM_CODEC.encode(buffer, packet.pos());
            ByteBufCodecs.STRING_UTF8.encode(buffer, packet.url());
            buffer.writeVarLong(packet.positionMs());
            buffer.writeBoolean(packet.playing());
            buffer.writeBoolean(packet.frozen());
            buffer.writeVarLong(packet.durationMs());
            buffer.writeBoolean(packet.hasArea());
            buffer.writeVarLong(packet.minX());
            buffer.writeVarLong(packet.minY());
            buffer.writeVarLong(packet.minZ());
            buffer.writeVarLong(packet.maxX());
            buffer.writeVarLong(packet.maxY());
            buffer.writeVarLong(packet.maxZ());
            buffer.writeVarInt(packet.normal());
        }

        @Override
        public S2CStatePacket decode(RegistryFriendlyByteBuf buffer) {
            BlockPos pos = BlockPos.STREAM_CODEC.decode(buffer);
            String url = ByteBufCodecs.STRING_UTF8.decode(buffer);
            long positionMs = buffer.readVarLong();
            boolean playing = buffer.readBoolean();
            boolean frozen = buffer.readBoolean();
            long durationMs = buffer.readVarLong();
            boolean hasArea = buffer.readBoolean();
            long minX = buffer.readVarLong();
            long minY = buffer.readVarLong();
            long minZ = buffer.readVarLong();
            long maxX = buffer.readVarLong();
            long maxY = buffer.readVarLong();
            long maxZ = buffer.readVarLong();
            int normal = buffer.readVarInt();
            return new S2CStatePacket(pos, url, positionMs, playing, frozen, durationMs, hasArea,
                    minX, minY, minZ, maxX, maxY, maxZ, normal);
        }
    };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
