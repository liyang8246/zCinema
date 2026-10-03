package com.zfy.zcinema.client.playback;

import com.zfy.zcinema.blockentity.CinemaScreenBlockEntity;
import com.zfy.zcinema.client.audio.ScreenAudio;
import com.zfy.zcinema.net.packets.C2SControlPacket;
import com.zfy.zcinema.net.packets.S2CStatePacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Registry of every local playback session plus the client-side entry points used by the renderer,
 * the GUI, the audio controller and the state packet handler.
 */
public final class ClientPlayback {
    private static final Map<BlockPos, PlaybackSession> SESSIONS = new HashMap<>();
    private static final long EXPIRE_MS = 10_000L;

    private ClientPlayback() {}

    /** Called from the screen renderer every frame it is visible. */
    public static FrameView frame(CinemaScreenBlockEntity be) {
        if (be == null || be.getLevel() == null || be.clientUrl().isBlank()) return null;
        PlaybackSession session = SESSIONS.get(be.getBlockPos());
        if (session != null && !session.valid()) {
            // A screen that used to sit here is gone; its session must not play on.
            session.close();
            SESSIONS.remove(be.getBlockPos());
            session = null;
        }
        if (session == null || !session.matchesUrl(be.clientUrl())) {
            if (session != null) session.close();
            session = PlaybackSession.open(be);
            if (session == null) return null;
            SESSIONS.put(be.getBlockPos(), session);
        }
        return session.renderFrame();
    }

    /** Called once per client tick. */
    public static void tick() {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        if (level == null || minecraft.player == null) {
            clearAll();
            return;
        }
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<BlockPos, PlaybackSession>> iterator = SESSIONS.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<BlockPos, PlaybackSession> entry = iterator.next();
            PlaybackSession session = entry.getValue();
            if (!session.valid() || session.blockEntity().clientUrl().isBlank()
                    || !session.matchesUrl(session.blockEntity().clientUrl())) {
                // The screen was taken down, emptied, its chunk went away, or it switched links:
                // stop everything right now instead of letting it play on against a stale block
                // entity or report the previous film's duration for the new one.
                session.close();
                iterator.remove();
                continue;
            }
            session.tick();
            if (now - session.touchedAt() > EXPIRE_MS) {
                session.close();
                iterator.remove();
            }
        }
    }

    public static PlaybackSession.Status status(BlockPos pos) {
        PlaybackSession session = SESSIONS.get(pos);
        if (session != null) return session.status();
        CinemaScreenBlockEntity be = find(pos);
        if (be == null || be.clientUrl().isBlank()) return PlaybackSession.Status.IDLE;
        return PlaybackSession.Status.LOADING;
    }

    public static double progress(BlockPos pos) {
        PlaybackSession session = SESSIONS.get(pos);
        return session == null ? 0.0 : session.progress();
    }

    /** Position to show in the UI: the shared clock, or our decode position before it arrives. */
    public static double displaySeconds(BlockPos pos) {
        PlaybackSession session = SESSIONS.get(pos);
        if (session != null) {
            double anchor = find(pos) != null ? find(pos).clientPositionSeconds() : 0.0;
            double local = session.displayedLocalSeconds();
            return anchor > local ? anchor : local;
        }
        CinemaScreenBlockEntity be = find(pos);
        return be == null ? 0.0 : be.clientPositionSeconds();
    }

    // =============================== ui helpers ===============================

    /** True while the local session is repositioning its stream. */
    public static boolean seeking(BlockPos pos) {
        PlaybackSession session = SESSIONS.get(pos);
        return session != null && session.seeking();
    }

    /** Human readable reason the session failed, if it did. */
    public static String errorMessage(BlockPos pos) {
        PlaybackSession session = SESSIONS.get(pos);
        if (session == null) {
            CinemaScreenBlockEntity be = find(pos);
            return be == null || be.clientUrl().isBlank() ? null : "gui.zcinema.status.loading";
        }
        return session.errorMessage();
    }

    /**
     * Whether the source can be seeked cheaply, so the panel can explain slow drags instead of
     * just sitting in "buffering".
     */
    public record SeekStatus(boolean active, boolean noRange) {}

    public static SeekStatus seekStatus(BlockPos pos) {
        PlaybackSession session = SESSIONS.get(pos);
        if (session == null) return new SeekStatus(false, false);
        return new SeekStatus(session.seeking(), !session.rangeSupported());
    }

    /** Duration to show in the UI: whatever the session or the server has learned. */
    public static double displayDurationSeconds(BlockPos pos) {
        PlaybackSession session = SESSIONS.get(pos);
        CinemaScreenBlockEntity be = find(pos);
        double duration = session != null ? session.durationSeconds() : 0.0;
        if (duration <= 0.0 && be != null) duration = be.clientDurationSeconds();
        return duration;
    }

    public static CinemaScreenBlockEntity find(BlockPos pos) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) return null;
        return level.getBlockEntity(pos) instanceof CinemaScreenBlockEntity be ? be : null;
    }

    /**
     * Rewrites the shared timeline at {@code seconds}. We send the event first so the server owns
     * the new position, and tell our own decoder to seek right away so it does not decode through
     * everything in between.
     */
    public static void seek(BlockPos pos, double seconds) {
        PlaybackSession session = SESSIONS.get(pos);
        if (session != null) session.requestSeek(seconds);
        ClientPacketDistributor.sendToServer(new C2SControlPacket(pos, C2SControlPacket.Action.SEEK,
                (long) (seconds * 1000L)));
    }

    public static void control(BlockPos pos, C2SControlPacket.Action action) {
        ClientPacketDistributor.sendToServer(new C2SControlPacket(pos, action, 0L));
    }

    /** S2C state arrived: refresh the client mirror, the session picks it up on its next frame. */
    public static void handleState(S2CStatePacket packet) {
        CinemaScreenBlockEntity be = find(packet.pos());
        if (be == null) return;
        be.applyClientState(packet.url(), packet.positionMs(), packet.playing(), packet.frozen(),
                packet.durationMs(), packet.hasArea(), packet.minX(), packet.minY(), packet.minZ(), packet.maxX(),
                packet.maxY(), packet.maxZ(), packet.normal());
        // Stop a session that was playing the previous link right away: the renderer would replace
        // it on the next frame anyway, but until then its tick could report the old film's
        // duration (or health) for the new one.
        PlaybackSession session = SESSIONS.get(packet.pos());
        if (session != null && !session.matchesUrl(packet.url())) {
            session.close();
            SESSIONS.remove(packet.pos());
        }
    }

    public static void clearAll() {
        for (PlaybackSession session : SESSIONS.values()) session.close();
        SESSIONS.clear();
        ScreenAudio.clear();
    }
}
