package com.zfy.zcinema.event;

import com.zfy.zcinema.blockentity.CinemaScreenBlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.ChunkWatchEvent;

/**
 * Sends the current playback state to a player the moment they start watching a chunk, so joining
 * players land on the right position without waiting a full sync interval.
 */
public final class ServerEvents {
    public ServerEvents() {}

    @SubscribeEvent
    public void onChunkWatch(ChunkWatchEvent.Watch event) {
        if (!(event.getLevel().getChunk(event.getPos().x, event.getPos().z) instanceof LevelChunk chunk)) {
            return;
        }
        chunk.getBlockEntities().values().forEach(be -> {
            if (be instanceof CinemaScreenBlockEntity screen && !screen.clientUrl().isBlank()) {
                screen.broadcastState();
            }
        });
    }
}
