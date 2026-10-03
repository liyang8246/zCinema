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
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
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

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Server-authoritative playback state for one screen, plus the rectangle of black concrete it
 * covers.
 *
 * <p>The server never touches the video data; it only owns the timeline:
 * <pre>
 *     effectivePosition = positionMs + (playing &amp;&amp; !frozen ? now - anchorMs : 0)
 * </pre>
 * Clients replicate that formula against the last received snapshot and drive their own local
 * FFmpeg session against it. Every human or network event (play/pause/seek/report of a dying
 * decoder) becomes a small packet that rewrites the timeline and is broadcast to all viewers.
 *
 * <p>Freezing is deliberately lazy: viewers report how healthy their own decoding is about once a
 * second, and the clock only stops when those reports agree for a few seconds (Create Cinema's
 * approach). One person's network blip cannot stutter the film for everybody else, and cannot
 * freeze/unfreeze in a loop either.
 */
public class CinemaScreenBlockEntity extends BlockEntity implements MenuProvider {
    private static final int CONTROL_RANGE_SQR = 24 * 24;
    private static final double HEALTH_REPORT_RANGE_SQR = 128.0 * 128.0;

    // How long a health report stays trusted, and how long the state has to stay bad/good for.
    private static final long HEALTH_FRESH_TICKS = 60L;    // 3s
    private static final long HEALTH_LEASE_TICKS = 200L;   // 10s
    private static final long PAUSE_CONFIRM_TICKS = 60L;   // 3s
    private static final long RESUME_CONFIRM_TICKS = 40L;  // 2s
    private static final int DEGRADED_LATENCY_MILLIS = 1_000;

    /** What a viewer says about its own decoding right now. */
    public enum PlaybackHealth {
        HEALTHY,
        BUFFERING,
        SOURCE_UNREACHABLE
    }

    private record ViewerHealth(PlaybackHealth health, long reportedAt) {}

    // ---- persisted: screen geometry ----
    private BlockPos screenMin;
    private BlockPos screenMax;
    private Direction screenNormal = Direction.NORTH;

    // ---- persisted (server clock) ----
    private String url = "";
    private boolean playing;
    private boolean frozen; // viewers agreed playback cannot continue; clock stopped for everybody
    private long positionMs; // position at the moment anchorMs was taken
    private long anchorMs;   // server wall clock of the last change
    private long durationMs; // learned from clients, 0 = unknown

    // ---- client mirror of the last received snapshot ----
    private String netUrl = "";
    private long netPositionMs;
    private long netAtMs = System.currentTimeMillis();
    private boolean netPlaying;
    private boolean netFrozen;
    private long netDurationMs;
    private BlockPos netMin;
    private BlockPos netMax;
    private Direction netNormal = Direction.NORTH;

    // ---- viewer health (server only) ----
    private final Map<UUID, ViewerHealth> viewerHealth = new HashMap<>();
    private boolean pendingPause;
    private long pendingPauseSince;
    private long healthySince;

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
        if (!hasScreenArea() || level == null || level.isClientSide()) return;
        boolean intact = true;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int x = screenMin.getX(); x <= screenMax.getX() && intact; x++) {
            for (int y = screenMin.getY(); y <= screenMax.getY() && intact; y++) {
                for (int z = screenMin.getZ(); z <= screenMax.getZ() && intact; z++) {
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
        evaluatePlaybackHealth();
        if (playing && !frozen) {
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
        long position = positionMs + (playing && !frozen ? Math.max(0L, now - anchorMs) : 0L);
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
        frozen = false;
        durationMs = 0L;
        resetPlaybackHealth();
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
                frozen = false;
                anchorMs = System.currentTimeMillis();
                resetPlaybackHealth();
                dirty = true;
            }
            case PAUSE -> {
                this.positionMs = effectivePositionMs();
                playing = false;
                frozen = false;
                anchorMs = System.currentTimeMillis();
                resetPlaybackHealth();
                dirty = true;
            }
            case SEEK -> {
                this.positionMs = clamp(Math.max(0L, positionMs));
                playing = true;
                frozen = false;
                anchorMs = System.currentTimeMillis();
                resetPlaybackHealth();
                dirty = true;
            }
            case REMOVE -> {
                url = "";
                playing = false;
                frozen = false;
                positionMs = 0L;
                anchorMs = System.currentTimeMillis();
                durationMs = 0L;
                resetPlaybackHealth();
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
        // Only ever grow the duration we know: a viewer that reports a smaller value would drag
        // the timeline (and every seek) backwards.
        if (millis <= durationMs) return;
        durationMs = millis;
        dirty = true;
        setChanged();
        ZCinema.LOGGER.info("Screen {} learned the stream is {}s long", getBlockPos(), millis / 1000L);
    }

    /**
     * Records what one viewer thinks of its own decoder. Ignored when the viewer is too far away,
     * watching something else, or already disconnected.
     */
    public void reportPlaybackHealth(ServerPlayer player, PlaybackHealth health) {
        if (level == null || level.isClientSide() || health == null || url.isBlank() || !playing) return;
        if (player.level() != level
                || player.distanceToSqr(Vec3.atCenterOf(getBlockPos())) > HEALTH_REPORT_RANGE_SQR
                || player.hasDisconnected()) {
            return;
        }
        ViewerHealth existing = viewerHealth.get(player.getUUID());
        // Once we know the source itself is unreachable, a weaker report cannot downgrade it.
        if (existing != null && existing.health() == PlaybackHealth.SOURCE_UNREACHABLE
                && health == PlaybackHealth.BUFFERING) {
            health = PlaybackHealth.SOURCE_UNREACHABLE;
        }
        viewerHealth.put(player.getUUID(), new ViewerHealth(health, level.getGameTime()));
    }

    /**
     * Decide, from all viewer reports, whether the shared clock has to stop. Every transition is
     * confirmed over several seconds in both directions, which is what keeps this stable.
     */
    private void evaluatePlaybackHealth() {
        if (level == null || level.isClientSide() || level.getServer() == null) return;
        if (url.isBlank() || !playing) {
            if (!viewerHealth.isEmpty() || frozen || pendingPause) resetPlaybackHealth();
            return;
        }
        long now = level.getGameTime();
        viewerHealth.entrySet().removeIf(entry -> now - entry.getValue().reportedAt() > HEALTH_LEASE_TICKS
                || level.getServer().getPlayerList().getPlayer(entry.getKey()) == null);

        boolean singleplayer = level.getServer().isSingleplayer() && !level.getServer().isPublished();
        int retained = 0;
        int fresh = 0;
        int healthy = 0;
        int sourceFailures = 0;
        int degraded = 0;
        for (Map.Entry<UUID, ViewerHealth> entry : viewerHealth.entrySet()) {
            ViewerHealth report = entry.getValue();
            retained++;
            boolean isFresh = now - report.reportedAt() <= HEALTH_FRESH_TICKS;
            if (isFresh) {
                fresh++;
                if (report.health() == PlaybackHealth.HEALTHY) healthy++;
                if (report.health() == PlaybackHealth.SOURCE_UNREACHABLE) sourceFailures++;
            }
            ServerPlayer player = level.getServer().getPlayerList().getPlayer(entry.getKey());
            if (!isFresh || player == null || player.hasDisconnected()
                    || player.connection.latency() >= DEGRADED_LATENCY_MILLIS) {
                degraded++;
            }
        }

        boolean shouldPause;
        if (!CommonConfig.INSTANCE.globalStallPause.get()) {
            shouldPause = false;
        } else if (singleplayer) {
            shouldPause = sourceFailures > 0;
        } else {
            shouldPause = fresh >= 2 && healthy == 0 && sourceFailures * 3 >= fresh * 2;
        }
        boolean serverLagging = CommonConfig.INSTANCE.globalStallPause.get()
                && retained >= 2 && degraded * 3 >= retained * 2;

        if (!frozen && !pendingPause) {
            if (!shouldPause && !serverLagging) return;
            pendingPause = true;
            pendingPauseSince = now;
            return;
        }
        if (frozen || pendingPause) {
            boolean keepFrozen = shouldPause || serverLagging || !recovered(retained, singleplayer,
                    fresh, healthy, sourceFailures, degraded);
            if (!keepFrozen) {
                if (healthySince == 0L) healthySince = now;
                if (now - healthySince >= RESUME_CONFIRM_TICKS) {
                    ZCinema.LOGGER.info("Screen {} recovered, the clock runs again", getBlockPos());
                    frozen = false;
                    pendingPause = false;
                    pendingPauseSince = 0L;
                    healthySince = 0L;
                }
                return;
            }
            healthySince = 0L;
            if (pendingPause && !frozen && now - pendingPauseSince >= PAUSE_CONFIRM_TICKS) {
                ZCinema.LOGGER.info("Screen {} stopped the clock: viewers report a broken source",
                        getBlockPos());
                frozen = true;
                dirty = true;
            }
            return;
        }
    }

    private static boolean recovered(int retained, boolean singleplayer, int fresh, int healthy,
                                    int sourceFailures, int degraded) {
        if (retained == 0) return true;
        if (singleplayer) return healthy > 0;
        if (degraded * 3 >= retained * 2) return fresh >= 1 && degraded == 0;
        return healthy > 0 && sourceFailures == 0;
    }

    private void resetPlaybackHealth() {
        viewerHealth.clear();
        pendingPause = false;
        pendingPauseSince = 0L;
        healthySince = 0L;
        if (frozen) {
            frozen = false;
            dirty = true;
            ZCinema.LOGGER.info("Screen {} unfroze the clock after a control action", getBlockPos());
        }
    }

    public void broadcastState() {
        if (level == null || level.isClientSide()) return;
        ScreenArea area = screenArea();
        Vec3 center = area != null ? area.centerOutward(4.0) : Vec3.atCenterOf(getBlockPos());
        S2CStatePacket packet = new S2CStatePacket(getBlockPos(), url, effectivePositionMs(), playing, frozen,
                durationMs,
                area != null,
                area != null ? area.min().getX() : 0, area != null ? area.min().getY() : 0,
                area != null ? area.min().getZ() : 0,
                area != null ? area.max().getX() : 0, area != null ? area.max().getY() : 0,
                area != null ? area.max().getZ() : 0,
                area != null ? area.normal().get3DDataValue() : Direction.NORTH.get3DDataValue());
        PacketDistributor.sendToPlayersNear((ServerLevel) level, null,
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
        if (level == null) return false;
        // Measuring to the core alone would close the panel the moment somebody uses it from the
        // far side of a big screen, so measure to the screen itself.
        ScreenArea area = screenArea();
        if (area == null) return player.distanceToSqr(Vec3.atCenterOf(getBlockPos())) <= CONTROL_RANGE_SQR;
        net.minecraft.world.phys.AABB box = new net.minecraft.world.phys.AABB(
                area.min().getX(), area.min().getY(), area.min().getZ(),
                area.max().getX() + 1.0, area.max().getY() + 1.0, area.max().getZ() + 1.0);
        return box.distanceToSqr(player.position()) <= CONTROL_RANGE_SQR;
    }

    // =============================== client mirror ===============================

    public void applyClientState(String url, long positionMs, boolean playing, boolean frozen, long durationMs) {
        this.netUrl = url == null ? "" : url;
        this.netPositionMs = durationMs > 0 ? Math.min(Math.max(0L, positionMs), durationMs) : Math.max(0L, positionMs);
        this.netPlaying = playing;
        this.netFrozen = frozen;
        this.netDurationMs = durationMs;
        this.netAtMs = System.currentTimeMillis();
    }

    /** Applies a full state snapshot (playback + screen geometry). */
    public void applyClientState(String url, long positionMs, boolean playing, boolean frozen, long durationMs,
                                 boolean hasArea, long minX, long minY, long minZ, long maxX, long maxY, long maxZ,
                                 int normal) {
        applyClientState(url, positionMs, playing, frozen, durationMs);
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
        if (netPlaying && !netFrozen) {
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

    public boolean clientFrozen() {
        return netFrozen;
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
        output.putBoolean("Frozen", frozen);
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
        frozen = input.getBooleanOr("Frozen", false);
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
                    input.getBooleanOr("ClientPlaying", playing), input.getBooleanOr("ClientFrozen", frozen),
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
        output.putBoolean("ClientFrozen", frozen);
        output.putLong("ClientDurationMs", durationMs);
        return output.buildResult();
    }
}
