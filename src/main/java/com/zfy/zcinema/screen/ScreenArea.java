package com.zfy.zcinema.screen;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import java.util.function.Consumer;

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

    public boolean contains(BlockPos pos) {
        return pos.getX() >= min.getX()
                && pos.getX() <= max.getX()
                && pos.getY() >= min.getY()
                && pos.getY() <= max.getY()
                && pos.getZ() >= min.getZ()
                && pos.getZ() <= max.getZ();
    }

    /** Visits every block of the rectangle, so callers do not hand-roll the same triple loop. */
    public void forEachPos(Consumer<BlockPos.MutableBlockPos> action) {
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int x = min.getX(); x <= max.getX(); x++) {
            for (int y = min.getY(); y <= max.getY(); y++) {
                for (int z = min.getZ(); z <= max.getZ(); z++) {
                    cursor.set(x, y, z);
                    action.accept(cursor);
                }
            }
        }
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

    /** On the wire; null means "this screen has no measured rectangle (yet)". */
    public static final net.minecraft.network.codec.StreamCodec<
                    net.minecraft.network.RegistryFriendlyByteBuf, ScreenArea>
            STREAM_CODEC =
                    net.minecraft.network.codec.StreamCodec.of(
                            (buffer, area) -> {
                                if (area == null) {
                                    buffer.writeBoolean(false);
                                    return;
                                }
                                buffer.writeBoolean(true);
                                BlockPos.STREAM_CODEC.encode(buffer, area.min());
                                BlockPos.STREAM_CODEC.encode(buffer, area.max());
                                buffer.writeVarInt(area.normal().get3DDataValue());
                            },
                            buffer -> {
                                if (!buffer.readBoolean()) return null;
                                BlockPos min = BlockPos.STREAM_CODEC.decode(buffer);
                                BlockPos max = BlockPos.STREAM_CODEC.decode(buffer);
                                return new ScreenArea(
                                        min, max, Direction.from3DDataValue(buffer.readVarInt()));
                            });
}
