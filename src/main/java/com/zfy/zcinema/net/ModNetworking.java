package com.zfy.zcinema.net;

import com.zfy.zcinema.ZCinema;
import com.zfy.zcinema.blockentity.CinemaScreenBlockEntity;
import com.zfy.zcinema.net.packets.C2SControlPacket;
import com.zfy.zcinema.net.packets.C2SHealthPacket;
import com.zfy.zcinema.net.packets.C2SReportMediaPacket;
import com.zfy.zcinema.net.packets.C2SSetUrlPacket;
import com.zfy.zcinema.net.packets.S2CStatePacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

@EventBusSubscriber(modid = ZCinema.MODID)
public final class ModNetworking {
    private ModNetworking() {}

    @SubscribeEvent
    public static void registerPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(ZCinema.MODID);

        registrar.playToServer(C2SSetUrlPacket.TYPE, C2SSetUrlPacket.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> handleC2S(payload, context)));
        registrar.playToServer(C2SControlPacket.TYPE, C2SControlPacket.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> handleC2S(payload, context)));
        registrar.playToServer(C2SReportMediaPacket.TYPE, C2SReportMediaPacket.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> handleC2S(payload, context)));
        registrar.playToServer(C2SHealthPacket.TYPE, C2SHealthPacket.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> handleC2S(payload, context)));

        // Only ever received on the client; the lambda is what pulls the client classes in.
        registrar.playToClient(S2CStatePacket.TYPE, S2CStatePacket.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() ->
                        com.zfy.zcinema.client.playback.ClientPlayback.handleState(payload)));
    }

    private static void handleC2S(Object payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) return;
        if (!(player.level() instanceof ServerLevel server)) return;
        var pos = switch (payload) {
            case C2SSetUrlPacket p -> p.pos();
            case C2SControlPacket p -> p.pos();
            case C2SReportMediaPacket p -> p.pos();
            case C2SHealthPacket p -> p.pos();
            default -> null;
        };
        if (pos == null) return;
        if (player.distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(pos)) > 128 * 128) return;
        if (server.getBlockEntity(pos) instanceof CinemaScreenBlockEntity be) {
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

    public static void sendToPlayersTrackingChunk(ServerLevel level, net.minecraft.core.BlockPos pos,
                                                  net.minecraft.network.protocol.common.custom.CustomPacketPayload payload) {
        PacketDistributor.sendToPlayersTrackingChunk(level, new net.minecraft.world.level.ChunkPos(pos), payload);
    }
}
