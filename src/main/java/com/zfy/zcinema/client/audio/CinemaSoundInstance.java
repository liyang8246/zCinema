package com.zfy.zcinema.client.audio;

import com.mojang.blaze3d.audio.Channel;
import com.zfy.zcinema.ZCinema;
import com.zfy.zcinema.blockentity.CinemaScreenBlockEntity;
import com.zfy.zcinema.client.config.ClientConfig;
import com.zfy.zcinema.client.playback.PlaybackSession;
import net.minecraft.client.Minecraft;
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
 * The screen's audio: a positional, streamed sound that decodes PCM from the same link the video
 * comes from. It terminates itself the moment it is wrong - paused, globally frozen, the clock
 * stuck, the viewer walked away, the URL changed, or it simply never started - and the controller
 * reopens it at the current position. That is what keeps it glued to the picture instead of
 * quietly drifting behind it.
 */
class CinemaSoundInstance extends AbstractSoundInstance implements TickableSoundInstance {
    private static final int FREEZE_STOP_TICKS = 40;          // 2s without a moving clock
    private static final long UNSTARTED_WATCHDOG_MILLIS = 5_000L;
    private static final double DRIFT_RESTART_SECONDS = 2.0;

    private static final ExecutorService OPEN_EXECUTOR = Executors.newFixedThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "ZCinema Audio Open");
        thread.setDaemon(true);
        return thread;
    });

    private final CinemaScreenBlockEntity be;
    private final BlockPos pos;
    private final String url;
    private final PlaybackSession session;
    private final long createdMillis = System.currentTimeMillis();

    private volatile boolean stopped;
    private volatile boolean openFailed;
    private volatile boolean openQueued;
    private volatile AudioStream stream;
    private volatile double streamStartTime = Double.NaN;
    private volatile long streamOpenedAt;

    private int freezeTicks;
    private double previousPlayTime = Double.NaN;
    private volatile boolean driftRestart;
    private volatile double latestPlayTime;

    CinemaSoundInstance(PlaybackSession session) {
        super(Identifier.fromNamespaceAndPath(ZCinema.MODID,
                        "screen_audio/" + Long.toUnsignedString(session.blockPos().asLong())),
                SoundSource.RECORDS, RandomSource.create());
        this.session = session;
        this.be = session.blockEntity();
        this.pos = session.blockPos();
        this.url = session.url();
        this.x = pos.getX() + 0.5;
        this.y = pos.getY() + 0.5;
        this.z = pos.getZ() + 0.5;
        this.volume = 1.0F;
        this.pitch = 1.0F;
        this.attenuation = SoundInstance.Attenuation.LINEAR;
        this.sound = new Sound(identifier, ConstantFloat.of(1.0F), ConstantFloat.of(1.0F), 1,
                Sound.Type.FILE, true, false, ClientConfig.INSTANCE.audioDistance.get());
        updatePosition();
    }

    @Override
    public WeighedSoundEvents resolve(SoundManager manager) {
        WeighedSoundEvents events = new WeighedSoundEvents(identifier, null);
        events.addSound(sound);
        return events;
    }

    @Override
    public CompletableFuture<AudioStream> getStream(SoundBufferLibrary buffers, Sound sound, boolean looping) {
        openQueued = true;
        return CompletableFuture.supplyAsync(this::openStream, OPEN_EXECUTOR);
    }

    private AudioStream openStream() {
        if (stopped || !session.matchesUrl(url)) {
            return failedStream();
        }
        try {
            StreamAudio opened = new StreamAudio(url, session::mediaSeconds, session.durationSeconds());
            if (stopped || !session.matchesUrl(url)) {
                opened.close();
                return failedStream();
            }
            stream = opened;
            streamStartTime = opened.startTime();
            streamOpenedAt = System.currentTimeMillis();
            return opened;
        } catch (Exception error) {
            ZCinema.LOGGER.warn("Failed to open screen audio for {} at {}s (will retry)", url,
                    session.mediaSeconds(), error);
            return failedStream();
        }
    }

    private AudioStream failedStream() {
        openFailed = true;
        SilentAudio failed = new SilentAudio();
        stream = failed;
        return failed;
    }

    @Override
    public void tick() {
        if (stopped) return;
        Minecraft minecraft = Minecraft.getInstance();
        updatePosition();
        if (minecraft.level == null || minecraft.player == null
                || !be.clientUrl().equals(url) || be.getLevel() == null || be.isRemoved()
                || com.zfy.zcinema.client.playback.ClientPlayback.find(pos) != be
                || !be.getLevel().dimension().equals(minecraft.level.dimension())) {
            stopInstance();
            return;
        }
        double distance = ClientConfig.INSTANCE.audioDistance.get();
        if (minecraft.player.distanceToSqr(x, y, z) > distance * distance) {
            stopInstance();
            return;
        }
        latestPlayTime = session.mediaSeconds();
        if (!Double.isNaN(previousPlayTime) && latestPlayTime == previousPlayTime) {
            freezeTicks++;
        } else {
            freezeTicks = 0;
        }
        previousPlayTime = latestPlayTime;
        if (openFailed) {
            ZCinema.LOGGER.debug("Screen {} audio failed to open, restarting", pos);
            driftRestart = true;
            stopInstance();
            return;
        }
        if (!session.clockMoving() && freezeTicks >= FREEZE_STOP_TICKS) {
            ZCinema.LOGGER.debug("Screen {} audio clock froze for {} ticks, restarting", pos, freezeTicks);
            driftRestart = true;
            stopInstance();
            return;
        }
    }

    /** True when the engine accepted the play request but the channel never came up. */
    boolean unstarted() {
        return openQueued && streamOpenedAt == 0L
                && System.currentTimeMillis() - createdMillis > UNSTARTED_WATCHDOG_MILLIS;
    }

    boolean driftRestart() {
        return driftRestart;
    }

    boolean openFailed() {
        return openFailed;
    }

    /** Where this sound's timeline currently sits, measured from when its stream opened. */
    double audioPositionSeconds() {
        long base = streamOpenedAt > 0L ? streamOpenedAt : createdMillis;
        double started = Double.isNaN(streamStartTime) ? latestPlayTime : streamStartTime;
        return started + (System.currentTimeMillis() - base) / 1000.0;
    }

    double driftRestartSeconds() {
        return DRIFT_RESTART_SECONDS;
    }

    /** Last media position this sound sampled while it was alive. */
    double latestPlayTime() {
        return latestPlayTime;
    }

    @Override
    public boolean isStopped() {
        return stopped;
    }

    void stopInstance() {
        if (stopped) return;
        stopped = true;
        // The sound engine polls isStopped() and closes its channel on the next tick; closing the
        // stream ourselves makes the sound go quiet immediately either way.
        closeStream();
    }

    private void closeStream() {
        AudioStream current = stream;
        if (current == null) return;
        stream = null;
        try {
            current.close();
        } catch (Exception ignored) {
        }
    }

    private void updatePosition() {
        var area = be.screenArea();
        if (area == null) {
            x = pos.getX() + 0.5;
            y = pos.getY() + 0.5;
            z = pos.getZ() + 0.5;
            return;
        }
        // Put the voice at the middle of the picture, a block out from the wall.
        net.minecraft.world.phys.Vec3 center = area.centerOutward(1.5);
        x = center.x;
        y = center.y;
        z = center.z;
    }

    @Override
    public boolean canStartSilent() {
        return true;
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
