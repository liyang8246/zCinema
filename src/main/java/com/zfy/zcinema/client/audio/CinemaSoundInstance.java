package com.zfy.zcinema.client.audio;

import com.zfy.zcinema.ZCinema;
import com.zfy.zcinema.blockentity.CinemaScreenBlockEntity;
import com.zfy.zcinema.client.config.ClientConfig;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.resources.sounds.AbstractSoundInstance;
import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.resources.sounds.TickableSoundInstance;
import net.minecraft.client.sounds.AudioStream;
import net.minecraft.client.sounds.SoundBufferLibrary;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.client.sounds.WeighedSoundEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.util.valueproviders.ConstantFloat;

import javax.sound.sampled.AudioFormat;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The screen's audio: a positional, streamed sound that reads PCM from the session's single
 * FFmpeg connection. It stops itself as soon as playback is paused, the clock is frozen (someone
 * stalled), the URL changed or the viewer walked away; the controller reopens it at the right
 * position.
 */
class CinemaSoundInstance extends AbstractSoundInstance implements TickableSoundInstance {
    private static final ExecutorService OPEN_EXECUTOR = Executors.newFixedThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "ZCinema Audio Open");
        thread.setDaemon(true);
        return thread;
    });

    private final BlockPos pos;
    private final String url;
    private final double startSeconds;
    private final CinemaScreenBlockEntity be;
    private final long createdAt = System.currentTimeMillis();

    private volatile boolean stopped;
    private volatile boolean openFailed;
    private volatile long streamOpenedAt;
    private volatile AudioStream stream;

    CinemaSoundInstance(CinemaScreenBlockEntity be, String url, double startSeconds) {
        super(Identifier.fromNamespaceAndPath(ZCinema.MODID, "screen_audio/" + be.getBlockPos().asLong()),
                SoundSource.RECORDS, RandomSource.create());
        this.be = be;
        this.pos = be.getBlockPos();
        this.url = url;
        this.startSeconds = startSeconds;
        this.x = pos.getX() + 0.5;
        this.y = pos.getY() + 0.5;
        this.z = pos.getZ() + 0.5;
        this.volume = 1.0F;
        this.pitch = 1.0F;
        this.attenuation = SoundInstance.Attenuation.LINEAR;
        this.sound = new Sound(identifier, ConstantFloat.of(1.0F), ConstantFloat.of(1.0F), 1,
                Sound.Type.FILE, true, false, ClientConfig.INSTANCE.audioDistance.get());
    }

    double startedAtSeconds() {
        return startSeconds;
    }

    long createdAtMillis() {
        return createdAt;
    }

    boolean openFailed() {
        return openFailed;
    }

    @Override
    public WeighedSoundEvents resolve(SoundManager manager) {
        WeighedSoundEvents events = new WeighedSoundEvents(identifier, null);
        events.addSound(sound);
        return events;
    }

    @Override
    public CompletableFuture<AudioStream> getStream(SoundBufferLibrary buffers, Sound sound, boolean looping) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                StreamAudio opened = new StreamAudio(url, startSeconds);
                if (stopped) {
                    opened.close();
                } else {
                    stream = opened;
                    streamOpenedAt = System.currentTimeMillis();
                }
                return (AudioStream) opened;
            } catch (Exception error) {
                ZCinema.LOGGER.warn("Failed to open screen audio for {} at {}s (will retry)", url, startSeconds, error);
                openFailed = true;
                return new SilentAudio();
            }
        }, OPEN_EXECUTOR);
    }

    @Override
    public void tick() {
        if (stopped) return;
        net.minecraft.client.Minecraft minecraft = net.minecraft.client.Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        if (level == null || minecraft.player == null) {
            stop();
            return;
        }
        double distance = ClientConfig.INSTANCE.audioDistance.get();
        if (minecraft.player.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5)
                > distance * distance) {
            stop();
            return;
        }
        if (!be.clientUrl().equals(url) || !be.clientPlaying() || be.clientWaiting()) {
            stop();
        }
    }

    @Override
    public boolean isStopped() {
        return stopped;
    }

    void stop() {
        if (stopped) return;
        stopped = true;
        AudioStream current = stream;
        if (current != null) {
            try {
                current.close();
            } catch (Exception ignored) {
            }
        }
    }

    /** Where the audio clock currently sits, measured from when its stream actually opened. */
    double audioPositionSeconds() {
        long base = streamOpenedAt > 0 ? streamOpenedAt : createdAt;
        return startSeconds + (System.currentTimeMillis() - base) / 1000.0;
    }

    boolean readyForDriftCheck() {
        return streamOpenedAt > 0 && System.currentTimeMillis() - streamOpenedAt > 2_000L;
    }

    /** Empty stream so the sound engine always has something to attach. */
    private static final class SilentAudio implements AudioStream {
        private static final AudioFormat FORMAT = new AudioFormat(44_100.0F, 16, 1, true, false);

        @Override
        public AudioFormat getFormat() {
            return FORMAT;
        }

        @Override
        public ByteBuffer read(int size) {
            ByteBuffer buffer = ByteBuffer.allocateDirect(Math.max(1, size)).order(ByteOrder.LITTLE_ENDIAN);
            while (buffer.hasRemaining()) buffer.put((byte) 0);
            buffer.flip();
            return buffer;
        }

        @Override
        public void close() {
        }
    }
}
