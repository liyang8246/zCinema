package com.zfy.zcinema.mixin;

import com.zfy.zcinema.event.ServerEvents;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Fabric has no "player starts watching a chunk" callback, so hook the exact vanilla spot
 * NeoForge does: right after a chunk is queued for a watching player.
 */
@Mixin(ChunkMap.class)
public abstract class ChunkMapMixin {
    @Inject(
            method = "markChunkPendingToSend(Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/world/level/chunk/LevelChunk;)V",
            at = @At("TAIL"))
    private static void zcinema$onChunkWatch(ServerPlayer player, LevelChunk chunk, CallbackInfo ci) {
        if (player.level() instanceof ServerLevel level) {
            ServerEvents.onChunkWatch(level, chunk.getPos());
        }
    }
}
