package com.zcinema;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public final class TimeSyncPayloads {
    private TimeSyncPayloads() {}

    public record Ping(long clientSendMs) implements CustomPacketPayload {
        public static final Type<Ping> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath(ZCinemaMod.MOD_ID, "time_ping"));
        public static final StreamCodec<ByteBuf, Ping> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_LONG, Ping::clientSendMs,
                Ping::new
        );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record Pong(long clientSendMs, long serverTimeMs) implements CustomPacketPayload {
        public static final Type<Pong> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath(ZCinemaMod.MOD_ID, "time_pong"));
        public static final StreamCodec<ByteBuf, Pong> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_LONG, Pong::clientSendMs,
                ByteBufCodecs.VAR_LONG, Pong::serverTimeMs,
                Pong::new
        );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }
}
