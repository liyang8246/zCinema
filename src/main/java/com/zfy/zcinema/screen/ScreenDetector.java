package com.zfy.zcinema.screen;

import com.zfy.zcinema.registry.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Multi-block screen detection: flood-fill the connected run of black concrete (and existing
 * cores) around a position, confirm it is a flat wall and shrink it to its bounding rectangle.
 */
public final class ScreenDetector {
    private static final int MAX_SCAN = 16_384;

    private ScreenDetector() {}

    public static boolean isScreenMaterial(BlockState state) {
        return state.is(Blocks.BLACK_CONCRETE) || state.is(ModBlocks.SCREEN_CORE.get());
    }

    /**
     * @param origin  a block that is part of the screen (concrete or an existing core)
     * @param hitFace the block face the player clicked, used to decide which way the screen faces
     * @param player  where the player stood, used as a fallback when the hit face is ambiguous
     */
    public static ScreenArea detect(Level level, BlockPos origin, Direction hitFace, Vec3 player) {
        BlockState originState = level.getBlockState(origin);
        if (!isScreenMaterial(originState)) return null;

        Set<BlockPos> found = new HashSet<>();
        Deque<BlockPos> queue = new ArrayDeque<>();
        queue.add(origin);
        found.add(origin);
        int minX = origin.getX(), minY = origin.getY(), minZ = origin.getZ();
        int maxX = minX, maxY = minY, maxZ = minZ;

        while (!queue.isEmpty() && found.size() < MAX_SCAN) {
            BlockPos current = queue.poll();
            for (Direction direction : Direction.values()) {
                BlockPos next = current.relative(direction);
                if (found.contains(next)) continue;
                if (!isScreenMaterial(level.getBlockState(next))) continue;
                found.add(next);
                queue.add(next);
                minX = Math.min(minX, next.getX());
                minY = Math.min(minY, next.getY());
                minZ = Math.min(minZ, next.getZ());
                maxX = Math.max(maxX, next.getX());
                maxY = Math.max(maxY, next.getY());
                maxZ = Math.max(maxZ, next.getZ());
            }
        }
        if (found.isEmpty()) return null;

        Direction normal = resolveNormal(level, origin, hitFace, player, minX, maxX, minY, maxY, minZ, maxZ);
        if (normal == null) return null;
        return new ScreenArea(new BlockPos(minX, minY, minZ), new BlockPos(maxX, maxY, maxZ), normal);
    }

    /**
     * A screen has to be flat: all blocks must share one coordinate, which picks the wall plane.
     * The normal points at the side the player is standing on (which is the face they clicked).
     */
    private static Direction resolveNormal(Level level, BlockPos origin, Direction hitFace, Vec3 player,
                                           int minX, int maxX, int minY, int maxY, int minZ, int maxZ) {
        List<Direction[]> planes = new ArrayList<>();
        if (minX == maxX) planes.add(new Direction[]{Direction.WEST, Direction.EAST});
        if (minY == maxY) planes.add(new Direction[]{Direction.DOWN, Direction.UP});
        if (minZ == maxZ) planes.add(new Direction[]{Direction.NORTH, Direction.SOUTH});
        if (planes.isEmpty()) return null;

        Vec3 center = new Vec3(origin.getX() + 0.5, origin.getY() + 0.5, origin.getZ() + 0.5);
        Direction best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Direction[] pair : planes) {
            for (Direction side : pair) {
                if (side == hitFace) return side;
                double distance = player.distanceToSqr(center.relative(side, 0.5));
                if (distance < bestDistance) {
                    bestDistance = distance;
                    best = side;
                }
            }
        }
        return best != null ? best : planes.get(0)[0];
    }
}
