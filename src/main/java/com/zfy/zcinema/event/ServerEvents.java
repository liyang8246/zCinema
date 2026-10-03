package com.zfy.zcinema.event;

import com.zfy.zcinema.ZCinemaLog;
import com.zfy.zcinema.block.ScreenBlock;
import com.zfy.zcinema.blockentity.CinemaScreenBlockEntity;
import com.zfy.zcinema.registry.ModBlocks;
import com.zfy.zcinema.screen.ScreenArea;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.jetbrains.annotations.Nullable;

/**
 * Sends the current playback state to a player the moment they start watching a chunk, so joining
 * players land on the right position without waiting a full sync interval. Also takes a screen
 * down cleanly when one of its blocks is broken.
 */
public final class ServerEvents {
    /** How far the state-carrying block may sit from a broken one, along the wall. */
    private static final int SCAN_RANGE = 64;

    private ServerEvents() {}

    public static void register() {
        PlayerBlockBreakEvents.BEFORE.register((level, player, pos, state, blockEntity) -> {
            if (!(level instanceof ServerLevel serverLevel)) return true;
            if (!state.is(ModBlocks.SCREEN)) return true;
            CinemaScreenBlockEntity screen = findScreen(serverLevel, pos, state);
            if (screen != null) {
                ZCinemaLog.log("server", "screen block broken pos=%s -> dissolve %s", pos.toShortString(),
                        screen.getBlockPos().toShortString());
                screen.dissolveScreen();
            }
            return true;
        });
    }

    /** Called from the ChunkMap mixin right after a chunk is queued for a watching player. */
    public static void onChunkWatch(ServerLevel level, ChunkPos pos) {
        if (!(level.getChunk(pos.x, pos.z) instanceof LevelChunk chunk)) {
            return;
        }
        int pushed = 0;
        for (var blockEntity : chunk.getBlockEntities().values()) {
            if (blockEntity instanceof CinemaScreenBlockEntity screen && screen.hasScreenArea()
                    && !screen.serverUrl().isBlank()) {
                screen.broadcastState();
                pushed++;
            }
        }
        if (pushed > 0) {
            ZCinemaLog.log("net", "chunk watch push chunk=%s screens=%d", pos, pushed);
        }
    }

    /**
     * Breaking a block of a screen takes the whole screen down: the remaining screen blocks turn
     * back into plain concrete (visually they already are) and playback stops, so a wall with a
     * hole never keeps a half-working screen behind. The size was measured when the screen was
     * registered and nothing re-measures it.
     *
     * <p>Only one block of the screen carries the state; the broken one may be that block, and
     * otherwise the state sits somewhere else in the same flat rectangle - the block's facing tells
     * us which plane to search.
     */
    private static @Nullable CinemaScreenBlockEntity findScreen(ServerLevel level, BlockPos pos, BlockState state) {
        if (level.getChunkAt(pos).getBlockEntity(pos) instanceof CinemaScreenBlockEntity be && be.hasScreenArea()) {
            return be;
        }
        Direction facing = state.getOptionalValue(ScreenBlock.FACING).orElse(Direction.NORTH);
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int du = -SCAN_RANGE; du <= SCAN_RANGE; du++) {
            for (int dv = -SCAN_RANGE; dv <= SCAN_RANGE; dv++) {
                int x = pos.getX();
                int y = pos.getY();
                int z = pos.getZ();
                switch (facing.getAxis()) {
                    case X -> {
                        y += du;
                        z += dv;
                    }
                    case Y -> {
                        x += du;
                        z += dv;
                    }
                    default -> {
                        x += du;
                        y += dv;
                    }
                }
                cursor.set(x, y, z);
                if (level.getBlockEntity(cursor) instanceof CinemaScreenBlockEntity be && be.hasScreenArea()) {
                    ScreenArea area = be.screenArea();
                    if (area != null && area.contains(pos)) {
                        return be;
                    }
                }
            }
        }
        return null;
    }
}
