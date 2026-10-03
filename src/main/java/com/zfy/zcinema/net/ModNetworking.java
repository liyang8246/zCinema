package com.zfy.zcinema.net;

import com.zfy.zcinema.blockentity.CinemaScreenBlockEntity;
import com.zfy.zcinema.net.packets.C2SControlPacket;
import com.zfy.zcinema.net.packets.C2SHealthPacket;
import com.zfy.zcinema.net.packets.C2SReportMediaPacket;
import com.zfy.zcinema.net.packets.C2SSetUrlPacket;
import com.zfy.zcinema.net.packets.S2CStatePacket;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

/**
 * Fabric side of the wire protocol. The packet records themselves are plain vanilla
 * {@link CustomPacketPayload}s, so only registration, dispatch and addressing live here.
 */
public final class ModNetworking {
    private static final double CONTROL_RANGE_SQR = 128.0 * 128.0;

    private ModNetworking() {}

    /** Both sides: register the packet types. Must run before any packet is sent. */
    public static void registerCommon() {
        PayloadTypeRegistry.playC2S().register(C2SSetUrlPacket.TYPE, C2SSetUrlPacket.STREAM_CODEC);
        PayloadTypeRegistry.playC2S().register(C2SControlPacket.TYPE, C2SControlPacket.STREAM_CODEC);
        PayloadTypeRegistry.playC2S().register(C2SReportMediaPacket.TYPE, C2SReportMediaPacket.STREAM_CODEC);
        PayloadTypeRegistry.playC2S().register(C2SHealthPacket.TYPE, C2SHealthPacket.STREAM_CODEC);

        // Only ever received on the client; the client entrypoint registers the receiver.
        PayloadTypeRegistry.playS2C().register(S2CStatePacket.TYPE, S2CStatePacket.STREAM_CODEC);
    }

    /** Server side: C2S receivers. */
    public static void registerServerHandlers() {
        ServerPlayNetworking.registerGlobalReceiver(C2SSetUrlPacket.TYPE, (payload, context) ->
                context.server().execute(() -> handleC2S(payload, context.player())));
        ServerPlayNetworking.registerGlobalReceiver(C2SControlPacket.TYPE, (payload, context) ->
                context.server().execute(() -> handleC2S(payload, context.player())));
        ServerPlayNetworking.registerGlobalReceiver(C2SReportMediaPacket.TYPE, (payload, context) ->
                context.server().execute(() -> handleC2S(payload, context.player())));
        ServerPlayNetworking.registerGlobalReceiver(C2SHealthPacket.TYPE, (payload, context) ->
                context.server().execute(() -> handleC2S(payload, context.player())));
    }

    private static void handleC2S(CustomPacketPayload payload, ServerPlayer player) {
        if (player == null) return;
        var pos = switch (payload) {
            case C2SSetUrlPacket p -> p.pos();
            case C2SControlPacket p -> p.pos();
            case C2SReportMediaPacket p -> p.pos();
            case C2SHealthPacket p -> p.pos();
            default -> null;
        };
        if (pos == null) return;
        if (player.distanceToSqr(Vec3.atCenterOf(pos)) > CONTROL_RANGE_SQR) return;
        if (player.level().getBlockEntity(pos) instanceof CinemaScreenBlockEntity be) {
            switch (payload) {
                case C2SSetUrlPacket p -> be.setUrl(player, p.url());
                case C2SControlPacket p -> be.control(player, p.action(), p.positionMs());
                case C2SReportMediaPacket p -> be.reportDuration(p.url(), p.durationMs());
                case C2SHealthPacket p -> be.reportPlaybackHealth(player, p.health());
                default -> {
                }
            }
        }
    }

    /** Client side helper: send one payload to the server. */
    public static void sendToServer(CustomPacketPayload payload) {
        ClientPlayNetworking.send(payload);
    }

    /** Send a payload to every player within {@code radius} of {@code center}. */
    public static void sendToPlayersNear(ServerLevel level, Vec3 center, double radius, CustomPacketPayload payload) {
        for (ServerPlayer player : PlayerLookup.around(level, center, radius)) {
            ServerPlayNetworking.send(player, payload);
        }
    }
}
