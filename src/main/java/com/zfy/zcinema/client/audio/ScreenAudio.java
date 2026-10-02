package com.zfy.zcinema.client.audio;

import com.zfy.zcinema.client.playback.PlaybackSession;
import com.zfy.zcinema.ZCinema;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

import java.util.HashMap;
import java.util.Map;

/**
 * Keeps exactly one streamed sound per playing screen. Sounds are opened only once enough picture
 * is buffered - which is also what keeps them in sync - and are reopened whenever they drift away
 * from the shared clock, the clock stops moving, or opening them failed. Reopens are rate limited
 * and only happen while the timeline actually moves, so a dead source backs off instead of
 * spinning.
 */
public final class ScreenAudio {
    private static final Map<BlockPos, CinemaSoundInstance> INSTANCES = new HashMap<>();
    private static final Map<BlockPos, Long> RETRY_AT = new HashMap<>();
    private static final Map<BlockPos, Long> LAST_RESTART = new HashMap<>();
    private static final Map<BlockPos, Integer> FAILURES = new HashMap<>();

    private static final long RETRY_DELAY_MS = 3_000L;
    private static final long RETRY_DELAY_MEDIUM_MS = 15_000L;
    private static final long RETRY_DELAY_MAX_MS = 30_000L;

    private ScreenAudio() {}

    public static void update(PlaybackSession session, double masterSeconds) {
        Minecraft minecraft = Minecraft.getInstance();
        BlockPos pos = session.blockPos();
        CinemaSoundInstance current = INSTANCES.get(pos);
        if (current != null && current.isStopped()) {
            INSTANCES.remove(pos);
            minecraft.getSoundManager().stop(current);
            current = null;
        }

        if (!session.audioReady()) {
            if (current != null) {
                current.stopInstance();
                minecraft.getSoundManager().stop(current);
                INSTANCES.remove(pos);
            }
            RETRY_AT.remove(pos);
            LAST_RESTART.remove(pos);
            FAILURES.remove(pos);
            return;
        }

        long now = System.currentTimeMillis();
        if (current != null) {
            double position = current.audioPositionSeconds();
            boolean drifted = position > 0.0
                    && Math.abs(position - masterSeconds) > current.driftRestartSeconds();
            boolean broken = current.driftRestart() || current.openFailed() || current.unstarted();
            if (drifted || broken) {
                // Only reopen while the shared clock has moved since this sound last saw it:
                // otherwise a paused or stuck timeline would spin here forever.
                if (!session.advancedSince(current.latestPlayTime())) return;
                // Every reopen - drift or failure alike - is rate limited, and repeated failures
                // back off, so a source that cannot be opened is not retried every frame.
                int failures = FAILURES.getOrDefault(pos, 0);
                if (now - LAST_RESTART.getOrDefault(pos, 0L) < cooldown(failures)) return;
                LAST_RESTART.put(pos, now);
                FAILURES.put(pos, Math.min(failures + 1, 3));
                if (drifted) {
                    ZCinema.LOGGER.debug("Screen {} audio drifted to {}s while the clock is at {}s, "
                            + "reopening", pos,
                            String.format(java.util.Locale.ROOT, "%.3f", position),
                            String.format(java.util.Locale.ROOT, "%.3f", masterSeconds));
                } else {
                    ZCinema.LOGGER.warn("Screen {} audio needs a reopen (attempt {}), backing off", pos,
                            failures + 1);
                }
                current.stopInstance();
                minecraft.getSoundManager().stop(current);
                INSTANCES.remove(pos);
                current = null;
            } else if (Math.abs(position - masterSeconds) < 0.5) {
                // Healthy again: forget past failures so the next hiccup recovers quickly.
                FAILURES.remove(pos);
                RETRY_AT.remove(pos);
            }
        }

        if (current == null) {
            Long retryAt = RETRY_AT.get(pos);
            if (retryAt != null && now < retryAt) return;
            CinemaSoundInstance instance = new CinemaSoundInstance(session);
            INSTANCES.put(pos, instance);
            RETRY_AT.remove(pos);
            minecraft.getSoundManager().play(instance);
        }
    }

    private static long cooldown(int failures) {
        if (failures <= 1) return RETRY_DELAY_MS;
        return failures == 2 ? RETRY_DELAY_MEDIUM_MS : RETRY_DELAY_MAX_MS;
    }

    public static void clear() {
        Minecraft minecraft = Minecraft.getInstance();
        INSTANCES.values().forEach(instance -> {
            instance.stopInstance();
            minecraft.getSoundManager().stop(instance);
        });
        INSTANCES.clear();
        RETRY_AT.clear();
        LAST_RESTART.clear();
        FAILURES.clear();
    }

    /** Silences one screen immediately (used when the screen itself goes away). */
    public static void stop(BlockPos pos) {
        CinemaSoundInstance instance = INSTANCES.remove(pos);
        if (instance == null) return;
        instance.stopInstance();
        Minecraft.getInstance().getSoundManager().stop(instance);
        RETRY_AT.remove(pos);
        LAST_RESTART.remove(pos);
        FAILURES.remove(pos);
    }
}
