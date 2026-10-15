package com.zcinema.client;

import com.zcinema.CinemaCoreBlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import org.lwjgl.openal.AL10;
import org.watermedia.api.media.MRL;
import org.watermedia.api.media.MediaAPI;
import org.watermedia.api.media.players.MediaPlayer;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ScreenPlayerManager {
    private static final Map<BlockPos, Entry> ENTRIES = new ConcurrentHashMap<>();
    private static final Pattern HTTP_URL_PATTERN = Pattern.compile("(https?://[^\\s\"<>]+)");
    private static final Pattern RAW_BV_PATTERN = Pattern.compile("^(BV[a-zA-Z0-9]+)$");

    public static final class Entry {
        public final BlockPos pos;
        public String rawUrl = "";
        public boolean playing;
        public long mediaPosMs;
        public long serverTimestampMs;
        public long syncedVersion = -1L;
        public MRL mrl;
        public MediaPlayer player;
        public boolean initialSyncDone;
        public boolean audioConfigured;

        public Entry(BlockPos pos) {
            this.pos = pos.immutable();
        }

        public long expectedMediaPosMs() {
            if (!this.playing) return Math.max(0L, this.mediaPosMs);
            return Math.max(0L, this.mediaPosMs + Math.max(0L, ClientClock.now() - this.serverTimestampMs));
        }

        public void release() {
            if (this.player != null) {
                try {
                    this.player.release();
                } catch (Throwable ignored) {}
                this.player = null;
            }
            this.mrl = null;
            this.initialSyncDone = false;
            this.audioConfigured = false;
        }
    }

    private ScreenPlayerManager() {}

    public static Entry get(BlockPos pos) {
        return ENTRIES.get(pos);
    }

    public static void applyState(BlockPos pos, String url, boolean playing, long mediaPosMs, long serverTimestampMs, long version) {
        String cleanUrl = url == null ? "" : url.trim();
        if (cleanUrl.isEmpty()) {
            remove(pos);
            return;
        }

        Entry entry = ENTRIES.computeIfAbsent(pos.immutable(), Entry::new);
        boolean urlChanged = !cleanUrl.equals(entry.rawUrl);
        entry.playing = playing;
        entry.mediaPosMs = Math.max(0L, mediaPosMs);
        entry.serverTimestampMs = serverTimestampMs;
        entry.syncedVersion = version;

        if (urlChanged) {
            entry.release();
            entry.rawUrl = cleanUrl;
            entry.mrl = MediaAPI.mrl(normalizeUrl(cleanUrl));
            return;
        }

        MediaPlayer player = entry.player;
        if (player != null && player.canPlay() && entry.initialSyncDone) {
            long targetMs = entry.expectedMediaPosMs();
            if (!playing) {
                player.pause(true);
                if (Math.abs(player.time() - targetMs) > 100L || player.ended()) {
                    player.seek(targetMs);
                }
            } else {
                if (player.ended() || Math.abs(player.time() - targetMs) > 400L) {
                    player.seek(targetMs);
                }
                player.pause(false);
            }
        }
    }

    public static void remove(BlockPos pos) {
        Entry removed = ENTRIES.remove(pos);
        if (removed != null) {
            removed.release();
        }
    }

    public static void releaseAll() {
        for (Entry entry : ENTRIES.values()) {
            entry.release();
        }
        ENTRIES.clear();
    }

    public static void tick(Minecraft client) {
        if (client.level == null) {
            if (!ENTRIES.isEmpty()) releaseAll();
            return;
        }

        for (CinemaCoreBlockEntity be : CinemaCoreBlockEntity.ACTIVE) {
            if (!be.isRemoved() && be.getLevel() == client.level) {
                Entry existing = ENTRIES.get(be.getBlockPos());
                if (existing == null ? !be.url.isEmpty() : existing.syncedVersion != be.stateVersion) {
                    applyState(be.getBlockPos(), be.url, be.playing, be.mediaPosMs, be.serverTimestampMs, be.stateVersion);
                }
            }
        }

        Thread renderThread = Thread.currentThread();
        Iterator<Map.Entry<BlockPos, Entry>> it = ENTRIES.entrySet().iterator();
        while (it.hasNext()) {
            Entry entry = it.next().getValue();
            if (!(client.level.getBlockEntity(entry.pos) instanceof CinemaCoreBlockEntity be) || be.isRemoved()) {
                entry.release();
                it.remove();
                continue;
            }

            if (entry.player == null && entry.mrl != null && entry.mrl.status() == MRL.Status.LOADED) {
                entry.player = MediaAPI.createPlayer(
                        entry.mrl,
                        () -> MediaAPI.glEngine(renderThread, client),
                        MediaAPI::alEngine
                );
                if (entry.player != null) {
                    if (entry.playing) {
                        entry.player.start();
                    } else {
                        entry.player.startPaused();
                    }
                }
            }

            MediaPlayer player = entry.player;
            if (player != null) {
                if (!entry.audioConfigured) {
                    int alSource = player.audioSource();
                    if (alSource != 0) {
                        AL10.alSourcei(alSource, AL10.AL_SOURCE_RELATIVE, AL10.AL_TRUE);
                        AL10.alSource3f(alSource, AL10.AL_POSITION, 0.0f, 0.0f, 0.0f);
                        AL10.alSourcef(alSource, AL10.AL_ROLLOFF_FACTOR, 0.0f);
                        entry.audioConfigured = true;
                    }
                }

                if (!entry.initialSyncDone && player.canPlay() && !player.loading()) {
                    entry.initialSyncDone = true;
                    long targetMs = entry.expectedMediaPosMs();
                    if (entry.playing) {
                        player.pause(false);
                        if (targetMs > 300L) {
                            player.seek(targetMs);
                        }
                    } else {
                        player.pause(true);
                        player.seek(targetMs);
                    }
                }
            }
        }
    }

    public static String normalizeUrl(String input) {
        String s = input.trim();
        Matcher httpMatcher = HTTP_URL_PATTERN.matcher(s);
        if (httpMatcher.find()) {
            return httpMatcher.group(1);
        }
        Matcher bvMatcher = RAW_BV_PATTERN.matcher(s);
        if (bvMatcher.matches()) {
            return "https://www.bilibili.com/video/" + bvMatcher.group(1);
        }
        if (s.startsWith("www.bilibili.com/") || s.startsWith("bilibili.com/") || s.startsWith("b23.tv/")) {
            return "https://" + s;
        }
        return s;
    }
}
