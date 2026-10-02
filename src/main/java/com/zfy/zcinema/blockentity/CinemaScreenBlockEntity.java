package com.zfy.zcinema.blockentity;

import com.zfy.zcinema.ZCinema;
import com.zfy.zcinema.config.CommonConfig;
import com.zfy.zcinema.gui.CinemaScreenMenu;
import com.zfy.zcinema.net.packets.S2CStatePacket;
import com.zfy.zcinema.registry.ModBlockEntities;
import com.zfy.zcinema.screen.ScreenArea;
import com.zfy.zcinema.screen.ScreenDetector;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Server-authoritative playback state for one screen, plus the rectangle of black concrete it
 * covers.
 *
 * <p>The server never touches the video data; it only owns the timeline:
 * <pre>
 *     effectivePosition = positionMs + (playing &amp;&amp; !waiting ? now - anchorMs : 0)
 * </pre>
 * Clients replicate that formula against the last received snapshot and drive their own local
 * FFmpeg session against it. Every human or network event (play/pause/seek/local stall recovery)
 * becomes a small control packet that rewrites the timeline and is broadcast to all viewers.
 */
public class CinemaScreenBlockEntity extends BlockEntity implements MenuProvider {
    private static final int CONTROL_RANGE_SQR = 24 * 24;

    // ---- persisted: screen geometry ----
    private BlockPos screenMin;
    private BlockPos screenMax;
    private Direction screenNormal = Direction.NORTH;

    // ---- persisted (server clock) ----
    private String url = "";
    private boolean playing;
    private boolean waiting; // a viewer stalled; the clock is frozen for everybody
    private long positionMs; // position at the moment anchorMs was taken
    private long anchorMs;   // server wall clock of the last change
    private long durationMs; // learned from clients, 0 = unknown

    // ---- client mirror of the last received snapshot ----
    private String netUrl = "";
    private long netPositionMs;
    private long netAtMs = System.currentTimeMillis();
    private boolean netPlaying;
    private boolean netWaiting;
    private long netDurationMs;
    private BlockPos netMin;
    private BlockPos netMax;
    private Direction netNormal = Direction.NORTH;

    private int syncCounter;
    private int validateCounter = 20;
    private boolean dirty;

    public CinemaScreenBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.SCREEN_BE.get(), pos, state);
    }

    // =============================== screen geometry ===============================

    /** Stores a freshly detected rectangle (called once, when the core is created). */
    public void setScreenArea(ScreenArea area) {
        this.screenMin = area.min();
        this.screenMax = area.max();
        this.screenNormal = area.normal();
        this.dirty = true;
        setChanged();
        if (level != null && !level.isClientSide()) {
            // Push the new geometry to nearby clients right away.
            level.sendBlockUpdated(getBlockPos(), getBlockState(), getBlockState(), 3);
        }
    }

    public boolean hasScreenArea() {
        if (level != null && level.isClientSide()) return netMin != null && netMax != null;
        return screenMin != null && screenMax != null;
    }

    public ScreenArea screenArea() {
        if (level != null && level.isClientSide()) {
            return netMin != null && netMax != null ? new ScreenArea(netMin, netMax, netNormal) : null;
        }
        return screenMin != null && screenMax != null ? new ScreenArea(screenMin, screenMax, screenNormal) : null;
    }

    public Direction screenNormal() {
        return screenNormal;
    }

    /**
     * Keeps the rectangle honest: if players broke concrete out of the wall, the screen shrinks
     * to whatever is still connected.
     */
    private void validateScreenArea() {
        if (!hasScreenArea() || level == null) return;
        boolean intact = true;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int x = screenMin.getX(); x <= screenMax.getX() && intact; x++) {
            for (int y = screenMin.getY(); y <= screenMax.getY() && intact; y++) {
                for (int z = screenMin.getZ(); z <= screenMax.getZ(); z++) {
                    cursor.set(x, y, z);
                    if (!ScreenDetector.isScreenMaterial(level.getBlockState(cursor))) {
                        intact = false;
                        break;
                    }
                }
            }
        }
        if (intact) return;
        if (level.getBlockState(getBlockPos()).is(com.zfy.zcinema.registry.ModBlocks.SCREEN_CORE.get())) {
            // Keep the rectangle honest using the side the core itself faces.
            Direction facing = getBlockState().getOptionalValue(
                    com.zfy.zcinema.block.ScreenCoreBlock.FACING).orElse(screenNormal);
            ScreenArea area = ScreenDetector.detect(level, getBlockPos(), facing.getOpposite(),
                    Vec3.atBottomCenterOf(getBlockPos()).add(0.0, 1.0, 0.0));
            if (area != null) setScreenArea(area);
        }
    }

    // =============================== server side ===============================

    public void serverTick() {
        if (level == null || level.isClientSide()) return;
        if (validateCounter-- <= 0) {
            validateCounter = 20; // once per second is plenty for self-healing
            validateScreenArea();
        }
        if (playing && !waiting) {
            long effective = effectivePositionMs();
            if (durationMs > 0 && effective >= durationMs) {
                positionMs = durationMs;
                playing = false;
                anchorMs = System.currentTimeMillis();
                dirty = true;
            }
        }
        int interval = CommonConfig.INSTANCE.syncIntervalTicks.get();
        if (++syncCounter >= interval || dirty) {
            syncCounter = 0;
            dirty = false;
            broadcastState();
        }
    }

    /** The position every viewer should be at, right now. */
    public long effectivePositionMs() {
        long now = System.currentTimeMillis();
        long position = positionMs + (playing && !waiting ? Math.max(0L, now - anchorMs) : 0L);
        return clamp(position);
    }

    private long clamp(long value) {
        if (durationMs > 0) value = Math.min(value, durationMs);
        return Math.max(0L, value);
    }

    public void setUrl(Player player, String value) {
        if (level == null || level.isClientSide()) return;
        String next = value == null ? "" : value.strip();
        if (next.length() > 2048) next = next.substring(0, 2048);
        url = next;
        positionMs = 0L;
        anchorMs = System.currentTimeMillis();
        playing = false;
        waiting = false;
        durationMs = 0L;
        dirty = true;
        setChanged();
        ZCinema.LOGGER.info("Screen {} now plays {} (set by {})", getBlockPos(),
                url.isEmpty() ? "<empty>" : url, player.getName().getString());
    }

    public void control(Player player, com.zfy.zcinema.net.packets.C2SControlPacket.Action action, long positionMs) {
        if (level == null || level.isClientSide()) return;
        switch (action) {
            case PLAY -> {
                this.positionMs = effectivePositionMs();
                playing = true;
                waiting = false;
                anchorMs = System.currentTimeMillis();
                dirty = true;
            }
            case PAUSE -> {
                this.positionMs = effectivePositionMs();
                playing = false;
                anchorMs = System.currentTimeMillis();
                dirty = true;
            }
            case SEEK -> {
                this.positionMs = clamp(Math.max(0L, positionMs));
                waiting = false;
                anchorMs = System.currentTimeMillis();
                dirty = true;
            }
            case STALL -> {
                if (!waiting) {
                    waiting = true;
                    this.positionMs = clamp(Math.max(0L, positionMs));
                    anchorMs = System.currentTimeMillis();
                    dirty = true;
                    if (CommonConfig.INSTANCE.globalStallPause.get()) {
                        ZCinema.LOGGER.info("Screen {} stalled at {}ms, pausing for everyone (reported by {})",
                                getBlockPos(), this.positionMs, player.getName().getString());
                    }
                }
            }
            case RESUME -> {
                if (waiting) {
                    waiting = false;
                    this.positionMs = clamp(this.positionMs);
                    anchorMs = System.currentTimeMillis();
                    dirty = true;
                }
            }
            case REMOVE -> {
                url = "";
                playing = false;
                waiting = false;
                positionMs = 0L;
                anchorMs = System.currentTimeMillis();
                durationMs = 0L;
                dirty = true;
                if (level != null) {
                    level.setBlockAndUpdate(getBlockPos(),
                            net.minecraft.world.level.block.Blocks.BLACK_CONCRETE.defaultBlockState());
                }
            }
        }
        setChanged();
    }

    public void reportDuration(long millis) {
        if (level == null || level.isClientSide() || millis <= 0L) return;
        if (millis != durationMs) {
            durationMs = millis;
            dirty = true;
            setChanged();
        }
    }

    public void broadcastState() {
        if (level == null || level.isClientSide()) return;
        ScreenArea area = screenArea();
        Vec3 center = area != null ? area.centerOutward(4.0) : Vec3.atCenterOf(getBlockPos());
        S2CStatePacket packet = new S2CStatePacket(getBlockPos(), url, effectivePositionMs(), playing, waiting, durationMs,
                area != null,
                area != null ? area.min().getX() : 0, area != null ? area.min().getY() : 0,
                area != null ? area.min().getZ() : 0,
                area != null ? area.max().getX() : 0, area != null ? area.max().getY() : 0,
                area != null ? area.max().getZ() : 0,
                area != null ? area.normal().get3DDataValue() : Direction.NORTH.get3DDataValue());
        PacketDistributor.sendToPlayersNear((net.minecraft.server.level.ServerLevel) level, null,
                center.x, center.y, center.z, radiusFor(area), packet);
    }

    private static double radiusFor(ScreenArea area) {
        if (area == null) return 192.0;
        double width = Math.max(area.width(), area.depth());
        double height = Math.max(area.height(), 1);
        double diagonal = Math.sqrt(width * width + height * height + 1.0);
        return 128.0 + diagonal;
    }

    public boolean canControl(Player player) {
        return level != null && player.distanceToSqr(Vec3.atCenterOf(getBlockPos())) <= CONTROL_RANGE_SQR;
    }

    // =============================== client mirror ===============================

    public void applyClientState(String url, long positionMs, boolean playing, boolean waiting, long durationMs) {
        this.netUrl = url == null ? "" : url;
        this.netPositionMs = durationMs > 0 ? Math.min(Math.max(0L, positionMs), durationMs) : Math.max(0L, positionMs);
        this.netPlaying = playing;
        this.netWaiting = waiting;
        this.netDurationMs = durationMs;
        this.netAtMs = System.currentTimeMillis();
    }

    /** Applies a full state snapshot (playback + screen geometry). */
    public void applyClientState(String url, long positionMs, boolean playing, boolean waiting, long durationMs,
                                 boolean hasArea, long minX, long minY, long minZ, long maxX, long maxY, long maxZ,
                                 int normal) {
        applyClientState(url, positionMs, playing, waiting, durationMs);
        if (hasArea) {
            this.netMin = new BlockPos((int) minX, (int) minY, (int) minZ);
            this.netMax = new BlockPos((int) maxX, (int) maxY, (int) maxZ);
            this.netNormal = Direction.from3DDataValue(normal);
        } else {
            this.netMin = null;
            this.netMax = null;
        }
    }

    /** Position this client believes is playing right now (extrapolated from the last snapshot). */
    public double clientPositionSeconds() {
        double seconds = netPositionMs / 1000.0;
        if (netPlaying && !netWaiting) {
            seconds += Math.max(0.0, (System.currentTimeMillis() - netAtMs) / 1000.0);
        }
        if (netDurationMs > 0) seconds = Math.min(seconds, netDurationMs / 1000.0);
        return seconds;
    }

    public String clientUrl() {
        return netUrl;
    }

    public boolean clientPlaying() {
        return netPlaying;
    }

    public boolean clientWaiting() {
        return netWaiting;
    }

    public double clientDurationSeconds() {
        return netDurationMs / 1000.0;
    }

    // =============================== menu ===============================

    @Override
    public Component getDisplayName() {
        return Component.translatable("container.zcinema.screen");
    }

    @Override
    public AbstractContainerMenu createMenu(int id, Inventory inventory, Player player) {
        return CinemaScreenMenu.server(id, inventory, getBlockPos());
    }

    // =============================== persistence ===============================

    @Override
    protected void saveAdditional(ValueOutput output) {
        output.putString("Url", url);
        output.putBoolean("Playing", playing);
        output.putBoolean("Waiting", waiting);
        output.putLong("PositionMs", positionMs);
        output.putLong("AnchorMs", anchorMs);
        output.putLong("DurationMs", durationMs);
        if (hasScreenArea()) {
            output.putInt("MinX", screenMin.getX());
            output.putInt("MinY", screenMin.getY());
            output.putInt("MinZ", screenMin.getZ());
            output.putInt("MaxX", screenMax.getX());
            output.putInt("MaxY", screenMax.getY());
            output.putInt("MaxZ", screenMax.getZ());
            output.putInt("Normal", screenNormal.get3DDataValue());
        }
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        url = input.getStringOr("Url", "");
        playing = input.getBooleanOr("Playing", false);
        waiting = input.getBooleanOr("Waiting", false);
        positionMs = input.getLongOr("PositionMs", 0L);
        anchorMs = input.getLongOr("AnchorMs", 0L);
        durationMs = input.getLongOr("DurationMs", 0L);
        if (input.getLongOr("MinX", Long.MIN_VALUE) != Long.MIN_VALUE
                && input.getLongOr("MaxX", Long.MIN_VALUE) != Long.MIN_VALUE) {
            screenMin = new BlockPos((int) input.getLongOr("MinX", 0), (int) input.getLongOr("MinY", 0),
                    (int) input.getLongOr("MinZ", 0));
            screenMax = new BlockPos((int) input.getLongOr("MaxX", 0), (int) input.getLongOr("MaxY", 0),
                    (int) input.getLongOr("MaxZ", 0));
            screenNormal = Direction.from3DDataValue((int) input.getLongOr("Normal", Direction.NORTH.get3DDataValue()));
        }
        if (level != null && level.isClientSide()) {
            boolean hasArea = screenMin != null;
            applyClientState(input.getStringOr("ClientUrl", url), input.getLongOr("ClientPositionMs", positionMs),
                    input.getBooleanOr("ClientPlaying", playing), input.getBooleanOr("ClientWaiting", waiting),
                    input.getLongOr("ClientDurationMs", durationMs),
                    hasArea,
                    hasArea ? screenMin.getX() : 0, hasArea ? screenMin.getY() : 0, hasArea ? screenMin.getZ() : 0,
                    hasArea ? screenMax.getX() : 0, hasArea ? screenMax.getY() : 0, hasArea ? screenMax.getZ() : 0,
                    screenNormal.get3DDataValue());
        }
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        return saveFull(this);
    }

    @Override
    public net.minecraft.network.protocol.Packet<net.minecraft.network.protocol.game.ClientGamePacketListener> getUpdatePacket() {
        return net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket.create(this);
    }

    private CompoundTag saveFull(BlockEntity self) {
        TagValueOutput output = TagValueOutput.createWithContext(net.minecraft.util.ProblemReporter.DISCARDING,
                self.getLevel() != null ? self.getLevel().registryAccess() : net.minecraft.core.RegistryAccess.EMPTY);
        BlockEntity.addEntityType(output, self.getType());
        output.putInt("x", self.getBlockPos().getX());
        output.putInt("y", self.getBlockPos().getY());
        output.putInt("z", self.getBlockPos().getZ());
        saveAdditional(output);
        // The raw persisted position is anchored to the server wall clock, so clients need the
        // extrapolated position instead of re-deriving it with their own clock.
        output.putString("ClientUrl", url);
        output.putLong("ClientPositionMs", self.getLevel() != null ? effectivePositionMs() : positionMs);
        output.putBoolean("ClientPlaying", playing);
        output.putBoolean("ClientWaiting", waiting);
        output.putLong("ClientDurationMs", durationMs);
        return output.buildResult();
    }
}
