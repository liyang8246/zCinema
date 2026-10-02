package com.zfy.zcinema.screen;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/** A detected multi-block screen: the rectangle it covers and which way it faces. */
public record ScreenArea(BlockPos min, BlockPos max, Direction normal) {
    public int width() {
        return max.getX() - min.getX() + 1;
    }

    public int height() {
        return max.getY() - min.getY() + 1;
    }

    public int depth() {
        return max.getZ() - min.getZ() + 1;
    }

    /** Screen width in blocks, measured along the wall. */
    public int screenWidth() {
        return switch (normal.getAxis()) {
            case Z -> width();
            case X -> depth();
            case Y -> width();
        };
    }

    /** Screen height in blocks, measured along the wall. */
    public int screenHeight() {
        return switch (normal.getAxis()) {
            case Z -> height();
            case X -> height();
            case Y -> depth();
        };
    }

    public long blockCount() {
        return (long) width() * height() * depth();
    }

    public boolean contains(BlockPos pos) {
        return pos.getX() >= min.getX() && pos.getX() <= max.getX()
                && pos.getY() >= min.getY() && pos.getY() <= max.getY()
                && pos.getZ() >= min.getZ() && pos.getZ() <= max.getZ();
    }

    /** Centre of the rectangle, pushed {} blocks out along the normal. */
    public net.minecraft.world.phys.Vec3 centerOutward(double out) {
        double x = (min.getX() + max.getX() + 1) / 2.0;
        double y = (min.getY() + max.getY() + 1) / 2.0;
        double z = (min.getZ() + max.getZ() + 1) / 2.0;
        return new net.minecraft.world.phys.Vec3(
                x + normal.getStepX() * out,
                y + normal.getStepY() * out,
                z + normal.getStepZ() * out);
    }
}
