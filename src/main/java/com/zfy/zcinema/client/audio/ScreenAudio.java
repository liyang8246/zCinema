package com.zfy.zcinema.client.audio;

import com.zfy.zcinema.ZCinema;
import com.zfy.zcinema.ZCinemaLog;
import com.zfy.zcinema.client.playback.PlaybackSession;
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

        // A singleplayer pause hard-pauses every non-music sound (SoundEngine.pauseAllExcept), our
        // streaming screen audio included, while the picture and the shared clock keep running on
        // the wall clock. The wall-clock drift estimate cannot see that, so leaving the frozen
        // sound alone would keep it behind by the whole pause with no way to notice. Close it and
        // let the controller reopen it at the current position once the game resumes.
        if (minecraft.isPaused()) {
            if (INSTANCES.containsKey(pos)) {
                ZCinema.LOGGER.debug("Screen {} audio stopped: the game is paused", pos);
                stop(pos, "game paused");
            }
            return;
        }

        CinemaSoundInstance current = INSTANCES.get(pos);
        if (current != null && current.isStopped()) {
            ZCinemaLog.log("audio", "sound stopped itself screen=%s", pos.toShortString());
            INSTANCES.remove(pos);
            minecraft.getSoundManager().stop(current);
            current = null;
        }

        if (!session.audioReady()) {
            if (current != null) {
                String blocker = session.audioReadyBlocker();
                ZCinemaLog.log("audio", "stopping screen=%s: not ready (%s)", pos.toShortString(), blocker);
                current.stopInstance("audio not ready: " + blocker);
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
                if (!session.advancedSince(current.latestPlayTime())) {
                    ZCinemaLog.log("audio", "reopen deferred screen=%s: clock has not moved since %.3fs",
                            pos.toShortString(), current.latestPlayTime());
                    return;
                }
                // Every reopen - drift or failure alike - is rate limited, and repeated failures
                // back off, so a source that cannot be opened is not retried every frame.
                int failures = FAILURES.getOrDefault(pos, 0);
                long cooldown = cooldown(failures);
                long sinceRestart = now - LAST_RESTART.getOrDefault(pos, 0L);
                if (sinceRestart < cooldown) {
                    ZCinemaLog.log("audio", "reopen throttled screen=%s: %dms of %dms cooldown, "
                                    + "failures=%d", pos.toShortString(), sinceRestart, cooldown, failures);
                    return;
                }
                LAST_RESTART.put(pos, now);
                FAILURES.put(pos, Math.min(failures + 1, 3));
                String reason = drifted ? "drifted" : "broken";
                ZCinemaLog.log("audio", "reopen screen=%s reason=%s position=%.3fs master=%.3fs "
                                + "drift=%+.3fs unstarted=%s openFailed=%s driftRestart=%s failures=%d",
                        pos.toShortString(), reason, position, masterSeconds, position - masterSeconds,
                        current.unstarted(), current.openFailed(), current.driftRestart(), failures + 1);
                if (drifted) {
                    ZCinema.LOGGER.debug("Screen {} audio drifted to {}s while the clock is at {}s, "
                            + "reopening", pos,
                            String.format(java.util.Locale.ROOT, "%.3f", position),
                            String.format(java.util.Locale.ROOT, "%.3f", masterSeconds));
                } else {
                    ZCinema.LOGGER.warn("Screen {} audio needs a reopen (attempt {}), backing off", pos,
                            failures + 1);
                }
                current.stopInstance("reopen: " + reason);
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
            ZCinemaLog.log("audio", "start screen=%s at=%.3fs master=%.3fs active=%d", pos.toShortString(),
                    session.mediaSeconds(), masterSeconds, INSTANCES.size());
            minecraft.getSoundManager().play(instance);
        }
    }

    private static long cooldown(int failures) {
        if (failures <= 1) return RETRY_DELAY_MS;
        return failures == 2 ? RETRY_DELAY_MEDIUM_MS : RETRY_DELAY_MAX_MS;
    }

    public static void clear() {
        Minecraft minecraft = Minecraft.getInstance();
        if (!INSTANCES.isEmpty()) {
            ZCinemaLog.log("audio", "clear: %d sounds", INSTANCES.size());
        }
        INSTANCES.values().forEach(instance -> {
            instance.stopInstance("screen audio cleared");
            minecraft.getSoundManager().stop(instance);
        });
        INSTANCES.clear();
        RETRY_AT.clear();
        LAST_RESTART.clear();
        FAILURES.clear();
    }

    /** Silences one screen immediately (used when the screen itself goes away). */
    public static void stop(BlockPos pos) {
        stop(pos, "unspecified");
    }

    public static void stop(BlockPos pos, String reason) {
        CinemaSoundInstance instance = INSTANCES.remove(pos);
        if (instance == null) return;
        ZCinemaLog.log("audio", "stop screen=%s reason=%s active=%d", pos.toShortString(), reason,
                INSTANCES.size());
        instance.stopInstance(reason);
        Minecraft.getInstance().getSoundManager().stop(instance);
        RETRY_AT.remove(pos);
        LAST_RESTART.remove(pos);
        FAILURES.remove(pos);
    }

    /**
     * Estimated media position of the sound currently playing for a screen, or NaN when there is
     * none. Used by the diagnostics state line to compare audio and video without guessing.
     */
    public static double debugPosition(BlockPos pos) {
        CinemaSoundInstance instance = INSTANCES.get(pos);
        return instance == null ? Double.NaN : instance.audioPositionSeconds();
    }

    /** Newest decoded frame timestamp of the sound for a screen, or NaN. Diagnostics only. */
    public static double debugDecodedTimestamp(BlockPos pos) {
        CinemaSoundInstance instance = INSTANCES.get(pos);
        return instance == null ? Double.NaN : instance.decodedTimestamp();
    }

    /** How many sound instances are alive right now. Diagnostics only. */
    public static int instanceCount() {
        return INSTANCES.size();
    }
}
