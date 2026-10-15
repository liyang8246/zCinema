package com.zcinema;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class CinemaCoreBlockEntity extends BlockEntity {
    public static final Set<CinemaCoreBlockEntity> ACTIVE = ConcurrentHashMap.newKeySet();

    public int width = 1;
    public int height = 1;
    public Direction facing = Direction.SOUTH;
    public String url = "";
    public boolean playing = false;
    public long mediaPosMs = 0L;
    public long serverTimestampMs = 0L;
    public long stateVersion = 0L;

    public CinemaCoreBlockEntity(BlockPos pos, BlockState state) {
        super(ZCinemaMod.CINEMA_CORE_BE, pos, state);
        ACTIVE.add(this);
    }

    public Direction rightDir() {
        return this.facing.getCounterClockWise();
    }

    public boolean containsScreenBlock(BlockPos pos) {
        return containsScreenBlock(this.worldPosition, this.facing, this.width, this.height, pos);
    }

    public static boolean containsScreenBlock(BlockPos corePos, Direction facing, int width, int height, BlockPos pos) {
        Direction right = facing.getCounterClockWise();
        BlockPos bottomLeft = corePos.relative(right, 1);
        BlockPos topRight = corePos.relative(right, width).above(height - 1);
        int minX = Math.min(bottomLeft.getX(), topRight.getX());
        int maxX = Math.max(bottomLeft.getX(), topRight.getX());
        int minY = bottomLeft.getY();
        int maxY = topRight.getY();
        int minZ = Math.min(bottomLeft.getZ(), topRight.getZ());
        int maxZ = Math.max(bottomLeft.getZ(), topRight.getZ());
        return pos.getX() >= minX && pos.getX() <= maxX
                && pos.getY() >= minY && pos.getY() <= maxY
                && pos.getZ() >= minZ && pos.getZ() <= maxZ;
    }

    public boolean isScreenIntact(Level level) {
        Direction right = rightDir();
        for (int x = 1; x <= this.width; x++) {
            BlockPos colBase = this.worldPosition.relative(right, x);
            for (int y = 0; y < this.height; y++) {
                BlockPos p = colBase.above(y);
                if (!level.isLoaded(p)) return true;
                if (!level.getBlockState(p).is(Blocks.BLACK_CONCRETE)) {
                    return false;
                }
            }
        }
        return true;
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state, CinemaCoreBlockEntity be) {
        if ((level.getGameTime() & 7L) == 0L && !be.isScreenIntact(level)) {
            level.removeBlock(pos, false);
        }
    }

    public static CinemaCoreBlockEntity findByAnyBlock(Level level, BlockPos pos) {
        if (level.getBlockEntity(pos) instanceof CinemaCoreBlockEntity core && !core.isRemoved()) {
            return core;
        }
        for (CinemaCoreBlockEntity be : ACTIVE) {
            if (!be.isRemoved() && be.level == level && be.containsScreenBlock(pos)) {
                if (level.getBlockEntity(be.worldPosition) == be) {
                    return be;
                }
            }
        }
        return null;
    }

    public void updateState(String newUrl, boolean newPlaying, long newMediaPosMs, long newServerTimestampMs) {
        this.url = newUrl == null ? "" : newUrl.trim();
        this.playing = newPlaying;
        this.mediaPosMs = Math.max(0L, newMediaPosMs);
        this.serverTimestampMs = newServerTimestampMs;
        this.stateVersion++;
        setChanged();
    }

    @Override
    public void setRemoved() {
        super.setRemoved();
        ACTIVE.remove(this);
        if (this.level != null && this.level.isClientSide) {
            CinemaClientHooks.onBlockEntityRemoved.accept(this.worldPosition);
        }
    }

    @Override
    public void clearRemoved() {
        super.clearRemoved();
        ACTIVE.add(this);
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putInt("Width", this.width);
        tag.putInt("Height", this.height);
        tag.putString("Facing", this.facing.getName());
        tag.putString("Url", this.url);
        tag.putBoolean("Playing", this.playing);
        tag.putLong("MediaPosMs", this.mediaPosMs);
        tag.putLong("ServerTimestampMs", this.serverTimestampMs);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        this.width = Math.max(1, tag.getInt("Width"));
        this.height = Math.max(1, tag.getInt("Height"));
        Direction dir = Direction.byName(tag.getString("Facing"));
        this.facing = (dir != null && dir.getAxis().isHorizontal()) ? dir : Direction.SOUTH;
        this.url = tag.getString("Url");
        this.playing = tag.getBoolean("Playing");
        this.mediaPosMs = tag.getLong("MediaPosMs");
        this.serverTimestampMs = tag.getLong("ServerTimestampMs");
        this.stateVersion++;
        ACTIVE.add(this);
        if (this.level != null && this.level.isClientSide) {
            CinemaClientHooks.onBlockEntitySync.accept(this);
        }
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        return saveWithoutMetadata(registries);
    }

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }
}
