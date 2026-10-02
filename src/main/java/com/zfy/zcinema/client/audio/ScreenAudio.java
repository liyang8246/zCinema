package com.zfy.zcinema.client.audio;

import com.zfy.zcinema.client.playback.PlaybackSession;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

import java.util.HashMap;
import java.util.Map;

/**
 * Keeps exactly one streamed sound per playing screen. Sounds are opened at the current shared
 * position and are restarted whenever they drift away from the clock (seek, stall recovery) or
 * when opening them failed - so a source that serialises connections retries instead of staying
 * silent forever.
 */
public final class ScreenAudio {
    private static final Map<BlockPos, CinemaSoundInstance> INSTANCES = new HashMap<>();
    private static final Map<BlockPos, Long> RETRY_AT = new HashMap<>();
    private static final double DRIFT_RESTART_SECONDS = 2.0;
    private static final long RETRY_DELAY_MS = 1_500L;

    private ScreenAudio() {}

    public static void update(PlaybackSession session, double masterSeconds) {
        BlockPos pos = session.blockPos();
        CinemaSoundInstance current = INSTANCES.get(pos);
        // Audio keeps running while the picture is buffering; it only stops on pause, global
        // stall, end of stream, errors or when the URL changes.
        PlaybackSession.Status status = session.status();
        boolean audible = (status == PlaybackSession.Status.PLAYING || status == PlaybackSession.Status.STALLED);
        long now = System.currentTimeMillis();

        if (current != null && current.isStopped()) {
            INSTANCES.remove(pos);
            current = null;
        }
        if (!audible) {
            if (current != null) {
                current.stop();
                INSTANCES.remove(pos);
            }
            RETRY_AT.remove(pos);
            return;
        }
        if (current == null) {
            Long retryAt = RETRY_AT.get(pos);
            if (retryAt != null && now < retryAt) return;
            CinemaSoundInstance instance = new CinemaSoundInstance(session.blockEntity(), session.url(),
                    masterSeconds);
            INSTANCES.put(pos, instance);
            Minecraft.getInstance().getSoundManager().play(instance);
            return;
        }
        if (current.openFailed()) {
            current.stop();
            INSTANCES.remove(pos);
            RETRY_AT.put(pos, now + RETRY_DELAY_MS);
            return;
        }
        if (current.readyForDriftCheck()) {
            double duration = session.durationSeconds();
            double audioPosition = current.audioPositionSeconds();
            if (duration > 0.0 && audioPosition > duration) {
                current.stop();
                INSTANCES.remove(pos);
                RETRY_AT.put(pos, now + RETRY_DELAY_MS);
                return;
            }
            if (Math.abs(audioPosition - masterSeconds) > DRIFT_RESTART_SECONDS) {
                current.stop();
                INSTANCES.remove(pos);
                RETRY_AT.put(pos, now + RETRY_DELAY_MS);
                CinemaSoundInstance instance = new CinemaSoundInstance(session.blockEntity(), session.url(),
                        masterSeconds);
                INSTANCES.put(pos, instance);
                Minecraft.getInstance().getSoundManager().play(instance);
            }
        }
    }

    public static void clear() {
        INSTANCES.values().forEach(CinemaSoundInstance::stop);
        INSTANCES.clear();
        RETRY_AT.clear();
    }
}
