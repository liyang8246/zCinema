package com.zfy.zcinema.client.playback;

import com.zfy.zcinema.ZCinema;
import com.zfy.zcinema.blockentity.CinemaScreenBlockEntity;
import com.zfy.zcinema.client.audio.ScreenAudio;
import com.zfy.zcinema.client.config.ClientConfig;
import com.zfy.zcinema.net.packets.C2SControlPacket;
import com.zfy.zcinema.net.packets.C2SReportMediaPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import org.bytedeco.ffmpeg.avutil.AVFrame;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.FrameGrabber;

import java.util.ArrayDeque;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * One screen's local playback. The session pulls the MP4 itself (the server never relays video)
 * and keeps itself glued to the shared timeline owned by the server.
 *
 * <ul>
 *   <li>The master clock is the last received state snapshot, extrapolated locally.</li>
 *   <li>Video frames are decoded ahead of the clock and the one matching it is shown, so a slow
 *       network only freezes this client for a moment. Audio runs on its own decoder thread.</li>
 *   <li>If this client starves for too long it tells the server (STALL), which freezes the clock
 *       for everyone, and tells it again when it recovers (RESUME).</li>
 *   <li>Divergence beyond {@code hardResyncSeconds} reopens the stream at the right position.</li>
 * </ul>
 */
public final class PlaybackSession {
    public enum Status {
        IDLE,
        LOADING,
        PLAYING,
        PAUSED,
        WAITING,
        STALLED,
        ENDED,
        ERROR
    }

    private static final int MAX_BUFFERED_FRAMES = 240;
    private static final long RETRY_DELAY_MS = 4_000L;
    private static final long DECODER_DEAD_MS = 15_000L;

    private final CinemaScreenBlockEntity be;
    private final BlockPos pos;
    private final AtomicLong touchStamp = new AtomicLong();

    private final String url;
    private final ArrayDeque<DecodedFrame> queue = new ArrayDeque<>();
    private final AtomicInteger generation = new AtomicInteger();
    private volatile boolean closed;
    private volatile boolean failed;
    private volatile long failedAt;
    private volatile String errorMessage;
    private volatile boolean ended;
    private volatile double duration;
    private volatile long decodeHeartbeat = System.currentTimeMillis();
    private volatile Thread decodeThread;
    private volatile boolean durationReported;

    private volatile double displayedTs = Double.NaN;
    private volatile double latestFrameTs = Double.NaN;
    private volatile boolean starving;
    private long resyncCooldownUntil;
    private boolean stallReported;
    private long stallSince;
    private long recoveredSince;
    private long lastStallReportAt;
    private long lastAnchorAdjustAt;
    /**
     * Local nudge between the shared clock and our own decode timeline, slowly corrected towards
     * the server anchor (Create Cinema's approach). This is what keeps playback smooth: no jump
     * detection, just a gentle 5% per second pull, plus a hard reopen only on real divergence.
     */
    private double clockOffsetSeconds;

    private DynamicTexture texture;
    private Identifier textureLocation;
    private int width;
    private int height;
    private DecodedFrame uploaded;

    private PlaybackSession(CinemaScreenBlockEntity be, double startSeconds) {
        this.be = be;
        this.pos = be.getBlockPos();
        this.url = be.clientUrl();
        openDecoder(startSeconds);
    }

    public static PlaybackSession open(CinemaScreenBlockEntity be) {
        if (be.clientUrl().isBlank()) return null;
        return new PlaybackSession(be, be.clientPositionSeconds());
    }

    // =============================== clock ===============================

    /** The shared clock, straight from the last server snapshot. */
    private double masterSeconds() {
        return be.clientPositionSeconds();
    }

    /**
     * The shared clock as seen by this client's decoder: the anchor plus our gently corrected
     * local offset. Everything timing related (pacing, frame picking, starvation) uses this.
     */
    private double localMasterSeconds() {
        return masterSeconds() + clockOffsetSeconds;
    }

    private double bufferSeconds() {
        return ClientConfig.INSTANCE.bufferSeconds.get();
    }

    public boolean matchesUrl(String other) {
        return url.equals(other);
    }

    public BlockPos blockPos() {
        return pos;
    }

    public CinemaScreenBlockEntity blockEntity() {
        return be;
    }

    public String url() {
        return url;
    }

    public double durationSeconds() {
        return duration > 0.0 ? duration : be.clientDurationSeconds();
    }

    // =============================== lifecycle ===============================

    private void openDecoder(double startSeconds) {
        int generation = this.generation.incrementAndGet();
        Thread thread = new Thread(() -> decodeLoop(generation, startSeconds), "ZCinema Decoder " + pos);
        thread.setDaemon(true);
        this.decodeThread = thread;
        thread.start();
    }

    private void hardResync(double target) {
        if (closed) return;
        if (System.currentTimeMillis() < resyncCooldownUntil) return;
        resyncCooldownUntil = System.currentTimeMillis() + 1_500L;
        // Bumping the generation retires the current decoder thread; it exits at its next check
        // and closes its own grabber.
        generation.incrementAndGet();
        Thread previous = decodeThread;
        if (previous != null) previous.interrupt();
        clearQueue();
        failed = false;
        ended = false;
        errorMessage = null;
        durationReported = false;
        stallReported = false;
        stallSince = 0L;
        displayedTs = Double.NaN;
        clockOffsetSeconds = 0.0;
        openDecoder(target);
    }

    public void requestSeek(double targetSeconds) {
        // An explicit seek always wins, even right after a resync.
        resyncCooldownUntil = 0L;
        hardResync(targetSeconds);
    }

    public void close() {
        if (closed) return;
        closed = true;
        if (stallReported) {
            ClientPacketDistributor.sendToServer(new C2SControlPacket(pos, C2SControlPacket.Action.RESUME, 0L));
            stallReported = false;
        }
        Thread thread = decodeThread;
        if (thread != null) thread.interrupt();
        clearQueue();
        if (textureLocation != null) {
            Identifier location = textureLocation;
            Minecraft.getInstance().execute(() -> Minecraft.getInstance().getTextureManager().release(location));
        }
        texture = null;
        textureLocation = null;
        uploaded = null;
    }

    private void clearQueue() {
        synchronized (queue) {
            DecodedFrame frame;
            while ((frame = queue.pollFirst()) != null) frame.close();
            if (uploaded != null) {
                uploaded.close();
                uploaded = null;
            }
            latestFrameTs = Double.NaN;
        }
    }

    // =============================== decoding ===============================

    private void configure(FFmpegFrameGrabber grabber) {
        grabber.setOption("rw_timeout", "5000000");
        grabber.setOption("timeout", "5000000");
        grabber.setOption("stimeout", "5000000");
        grabber.setOption("reconnect", "1");
        grabber.setOption("reconnect_streamed", "1");
        grabber.setOption("reconnect_on_network_error", "1");
        grabber.setOption("reconnect_delay_max", "2");
        grabber.setOption("reconnect_max_retries", "2");
    }

    private void decodeLoop(int generation, double startSeconds) {
        FFmpegFrameGrabber grabber = null;
        try {
            if (url.isBlank()) return;
            grabber = new FFmpegFrameGrabber(url);
            grabber.setImageMode(FrameGrabber.ImageMode.RAW);
            configure(grabber);
            grabber.start();
            if (isRetired(generation)) return;
            decodeHeartbeat = System.currentTimeMillis();

            double containerDuration = grabber.getLengthInTime() / 1_000_000.0;
            double known = containerDuration > 0.0 ? containerDuration : duration;
            double seekTarget = known > 0.0 ? Math.min(Math.max(0.0, startSeconds), known - 0.1) : Math.max(0.0, startSeconds);

            double origin = Double.NaN;
            double directSeek = 0.0;
            if (seekTarget > 0.25) {
                try {
                    grabber.setTimestamp((long) (seekTarget * 1_000_000.0));
                    directSeek = seekTarget;
                } catch (Exception error) {
                    ZCinema.LOGGER.debug("Seek failed for {}, fast-forwarding from the start", url, error);
                    directSeek = 0.0;
                }
            }

            try (FrameScaler scaler = new FrameScaler()) {
                int maxWidth = ClientConfig.INSTANCE.maxFrameWidth.get();
                int maxHeight = ClientConfig.INSTANCE.maxFrameHeight.get();
                while (!isRetired(generation)) {
                    Frame frame = grabber.grabImage();
                    if (frame == null) {
                        if (!isRetired(generation)) handleEof();
                        return;
                    }
                    decodeHeartbeat = System.currentTimeMillis();
                    if (!(frame.opaque instanceof AVFrame)) continue;

                    double rawTs = frame.timestamp / 1_000_000.0;
                    if (Double.isNaN(origin)) origin = directSeek > 0.0 ? rawTs - directSeek : rawTs;
                    double ts = Math.max(0.0, rawTs - origin);

                    while (!isRetired(generation)) {
                        if (ts <= localMasterSeconds() + bufferSeconds()) break;
                        Thread.sleep(4L);
                    }
                    if (isRetired(generation)) return;
                    if (ts < localMasterSeconds() - 1.0) continue; // fast-forward: discard frames we are past

                    DecodedFrame decoded = scaler.decode(frame, maxWidth, maxHeight);
                    if (decoded == null) continue;
                    enqueue(new DecodedFrame(decoded.image, ts));
                }
            }
        } catch (Throwable error) {
            if (!closed && !isRetired(generation)) {
                failed = true;
                failedAt = System.currentTimeMillis();
                errorMessage = String.valueOf(error.getMessage());
                ZCinema.LOGGER.warn("Screen {} failed to stream {} from {}s", pos, url, startSeconds, error);
            }
        } finally {
            if (grabber != null) {
                try {
                    grabber.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private boolean isRetired(int generation) {
        return closed || this.generation.get() != generation;
    }

    private void handleEof() {
        if (closed) return;
        double master = localMasterSeconds();
        double known = duration > 0.0 ? duration : be.clientDurationSeconds();
        if (known > 0.0 && master < known - 1.0) {
            failed = true;
            failedAt = System.currentTimeMillis();
            errorMessage = "stream ended early";
            return;
        }
        ended = true;
        if (be.clientDurationSeconds() <= 0.0 && known > 0.0) {
            sendDuration(known);
        }
    }

    private void enqueue(DecodedFrame frame) {
        while (!closed) {
            synchronized (queue) {
                if (queue.size() < MAX_BUFFERED_FRAMES) {
                    queue.addLast(frame);
                    return;
                }
            }
            try {
                Thread.sleep(4L);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                frame.close();
                return;
            }
        }
        frame.close();
    }

    // =============================== per-frame ===============================

    /**
     * Picks the frame matching the shared clock and uploads it. Called from the block entity
     * renderer, so it only ever runs while the screen is actually visible.
     *
     * <p>Ownership rule: exactly one frame may live outside the queue at a time (the one the
     * texture currently holds). Uploading the very same image twice would make {@code setPixels}
     * free the buffer it is about to upload, so repeats are skipped.
     */
    public FrameView renderFrame() {
        touchStamp.set(System.currentTimeMillis());
        double master = localMasterSeconds();
        DecodedFrame best = null;
        synchronized (queue) {
            for (DecodedFrame frame : queue) {
                if (frame.ts <= master + 0.25) best = frame;
                else break;
            }
            while (true) {
                DecodedFrame head = queue.peekFirst();
                if (head == null || head == best) break;
                if (head.ts < master - 1.5) {
                    queue.pollFirst().close();
                } else {
                    break;
                }
            }
        }
        if (best == null) {
            if (!queue.isEmpty()) {
                latestFrameTs = queue.peekLast().ts;
                // Every buffered frame sits in the future (just seeked back): that is not running
                // dry, it is the opposite.
                starving = false;
            } else {
                starving = be.clientPlaying() && !be.clientWaiting() && !ended;
            }
            return textureLocation == null ? null : new FrameView(textureLocation, width, height);
        }
        latestFrameTs = best.ts;
        displayedTs = best.ts;

        // Hysteresis: only flip to buffering when the decoder is really behind, and only flip
        // back once it has caught up, so the status text cannot flap.
        double behind = master - best.ts;
        if (be.clientPlaying() && !be.clientWaiting() && !ended) {
            if (!starving && behind > 0.6) starving = true;
            else if (starving && behind < 0.3) starving = false;
        } else {
            starving = false;
        }

        if (best == uploaded) {
            return new FrameView(textureLocation, width, height);
        }
        synchronized (queue) {
            queue.remove(best);
            DecodedFrame previous = uploaded;
            uploaded = best;
            if (previous != null) previous.close(); // no-op once the texture owns it
        }
        return upload(best);
    }

    private FrameView upload(DecodedFrame frame) {
        frame.handToTexture();
        if (texture == null || width != frame.width || height != frame.height) {
            if (textureLocation != null) Minecraft.getInstance().getTextureManager().release(textureLocation);
            textureLocation = Identifier.fromNamespaceAndPath(ZCinema.MODID,
                    "screen_stream/" + Long.toUnsignedString(pos.asLong()));
            texture = FrameUploader.create(frame.image);
            Minecraft.getInstance().getTextureManager().register(textureLocation, texture);
        } else {
            FrameUploader.update(texture, frame.image);
        }
        width = frame.width;
        height = frame.height;
        return new FrameView(textureLocation, width, height);
    }

    // =============================== per-tick ===============================

    public void tick() {
        touchStamp.set(System.currentTimeMillis());
        double master = masterSeconds();

        if (duration > 0.0 && !durationReported) sendDuration(duration);

        if (failed && System.currentTimeMillis() - failedAt > RETRY_DELAY_MS) {
            hardResync(master);
        }

        if (be.clientPlaying() && !be.clientWaiting() && !failed && !ended
                && System.currentTimeMillis() - decodeHeartbeat > DECODER_DEAD_MS) {
            failed = true;
            failedAt = System.currentTimeMillis();
            errorMessage = "decoder stalled";
        }

        applyServerAnchor();

        reportStallIfStarving(master);
        ScreenAudio.update(this, master);
    }

    /**
     * Create Cinema's anchor correction, ported over: compare our local timeline against the
     * shared clock and pull it towards it by a few percent per second. Only a real divergence
     * reopens the stream, and that path has its own cooldown, so it can never feed back into
     * itself the way jump detection did.
     */
    private void applyServerAnchor() {
        if (closed || failed || ended) return;
        double anchor = masterSeconds();
        double current = localMasterSeconds();
        double diff = anchor - current;
        if (Math.abs(diff) <= 0.15) return;
        long now = System.currentTimeMillis();
        if (lastAnchorAdjustAt == 0L) {
            lastAnchorAdjustAt = now;
            return;
        }
        double elapsed = Math.min(1.0, (now - lastAnchorAdjustAt) / 1_000.0);
        lastAnchorAdjustAt = now;
        if (Math.abs(diff) >= hardResyncSeconds()) {
            clockOffsetSeconds = 0.0;
            hardResync(anchor);
            return;
        }
        double correction = Math.copySign(Math.min(Math.abs(diff), Math.max(0.002, elapsed * 0.05)), diff);
        clockOffsetSeconds += correction;
    }

    /**
     * Tells the server this client ran dry, which freezes the shared clock for everyone until
     * this client recovers. Uses hysteresis so a fast-forward or a reopening stream cannot make
     * it flap: report only after the buffer has been dry for a while, resume only after frames
     * have been flowing again for a while.
     */
    private void reportStallIfStarving(double master) {
        boolean playing = be.clientPlaying() && !be.clientWaiting() && !ended && !failed;
        long now = System.currentTimeMillis();
        if (!playing) {
            if (!be.clientWaiting() && stallReported) {
                ClientPacketDistributor.sendToServer(new C2SControlPacket(pos, C2SControlPacket.Action.RESUME, 0L));
                stallReported = false;
            }
            stallSince = 0L;
            recoveredSince = 0L;
            return;
        }
        boolean dry = Double.isNaN(newestTs()) || (master + clockOffsetSeconds - newestTs()) > 0.35;
        if (dry) {
            recoveredSince = 0L;
            if (stallSince == 0L) stallSince = now;
            if (!stallReported
                    && now - stallSince > ClientConfig.INSTANCE.localStallMs.get()
                    && now - lastStallReportAt > 2_000L
                    && now > resyncCooldownUntil) {
                ClientPacketDistributor.sendToServer(new C2SControlPacket(pos, C2SControlPacket.Action.STALL,
                        (long) (master * 1000L)));
                stallReported = true;
                lastStallReportAt = now;
                ZCinema.LOGGER.info("Screen {}: local buffer ran dry at {}ms, pausing the shared clock",
                        pos, (long) (master * 1000L));
            }
            return;
        }
        stallSince = 0L;
        if (recoveredSince == 0L) recoveredSince = now;
        if (stallReported && now - recoveredSince > 1_000L) {
            ClientPacketDistributor.sendToServer(new C2SControlPacket(pos, C2SControlPacket.Action.RESUME, 0L));
            stallReported = false;
            ZCinema.LOGGER.info("Screen {}: buffer recovered, resuming the shared clock", pos);
        }
    }

    private double hardResyncSeconds() {
        return ClientConfig.INSTANCE.hardResyncSeconds.get();
    }

    private void sendDuration(double seconds) {
        durationReported = true;
        ClientPacketDistributor.sendToServer(new C2SReportMediaPacket(pos, (long) (seconds * 1000L)));
    }

    // =============================== status ===============================

    public long touchedAt() {
        return touchStamp.get();
    }

    public double newestTs() {
        if (!queue.isEmpty()) return queue.peekLast().ts;
        return latestFrameTs;
    }

    public boolean starving() {
        return starving;
    }

    public String errorMessage() {
        return errorMessage;
    }

    public double progress() {
        double total = durationSeconds();
        if (total <= 0.0) return 0.0;
        double value = masterSeconds() / total;
        return Math.max(0.0, Math.min(1.0, value));
    }

    /** Position of the last frame we actually showed, on our local timeline. */
    public double displayedLocalSeconds() {
        return Double.isNaN(displayedTs) ? 0.0 : displayedTs;
    }

    public Status status() {
        if (be.clientUrl().isBlank()) return Status.IDLE;
        if (failed) return Status.ERROR;
        if (textureLocation == null) return Status.LOADING;
        if (be.clientWaiting()) return Status.WAITING;
        if (!be.clientPlaying()) return Status.PAUSED;
        if (ended) return Status.ENDED;
        if (starving) return Status.STALLED;
        return Status.PLAYING;
    }
}
