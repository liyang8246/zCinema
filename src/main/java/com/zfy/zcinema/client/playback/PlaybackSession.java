package com.zfy.zcinema.client.playback;

import com.zfy.zcinema.ZCinema;
import com.zfy.zcinema.blockentity.CinemaScreenBlockEntity;
import com.zfy.zcinema.blockentity.CinemaScreenBlockEntity.PlaybackHealth;
import com.zfy.zcinema.client.audio.ScreenAudio;
import com.zfy.zcinema.client.config.ClientConfig;
import com.zfy.zcinema.net.packets.C2SControlPacket;
import com.zfy.zcinema.net.packets.C2SHealthPacket;
import com.zfy.zcinema.net.packets.C2SReportMediaPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import com.zfy.zcinema.net.ModNetworking;
import org.bytedeco.ffmpeg.avutil.AVFrame;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.FrameGrabber;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * One screen's local playback. The session pulls the MP4 itself (the server never relays video)
 * and keeps itself glued to the shared timeline the server owns. This is Create Cinema's design
 * for network streams, adapted to a single direct link:
 *
 * <ul>
 *   <li>The shared clock is the server snapshot extrapolated locally; the media position is that
 *       clock minus {@code itemStartSeconds}.</li>
 *   <li>Frames are decoded a bounded amount ahead and the one matching the clock is shown, so a
 *       slow network only freezes this client for a moment.</li>
 *   <li>Drift is corrected gently - a few percent per second - and only a real divergence seeks
 *       the stream, never restarts it. No jump detection, nothing that can feed back into
 *       itself.</li>
 *   <li>Every second this session tells the server how healthy it is; the server freezes the
 *       shared clock for everybody only when the reports agree for a few seconds.</li>
 * </ul>
 */
public final class PlaybackSession {
    public enum Status {
        IDLE,
        LOADING,
        PLAYING,
        PAUSED,
        ENDED,
        ERROR
    }

    private static final int MAX_BUFFERED_FRAMES = 240;
    private static final double STARTUP_BUFFER_SECONDS = 1.0;
    private static final double DISPLAY_LEAD_SECONDS = 0.035;
    private static final long VIDEO_UNDERRUN_GRACE_MILLIS = 250L;
    private static final long RECOVERY_DELAY_MILLIS = 1_000L;
    private static final int MAX_EOF_RECOVERIES = 5;
    private static final long SEEKING_MESSAGE_MILLIS = 20_000L;
    private static final long DECODER_DEAD_MILLIS = 15_000L;
    private static final long RETRY_DELAY_MILLIS = 4_000L;
    private static final long HEALTH_REPORT_INTERVAL_MILLIS = 1_000L;
    private static final long MEDIA_REPORT_INTERVAL_MILLIS = 1_000L;

    private final CinemaScreenBlockEntity be;
    private final BlockPos pos;
    private final AtomicLong touchStamp = new AtomicLong();

    private final String url;
    /** The direct stream the decoders actually open; resolved from {@link #url} when needed. */
    private volatile String streamUrl;
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

    /**
     * Offset between the shared clock and our own media timeline. Correcting drift means nudging
     * this by a few percent per second towards the server anchor, which keeps playback smooth
     * instead of jumping around.
     */
    private volatile double itemStartSeconds;

    // Buffer lifecycle, so "buffering" means "really waiting for frames" and nothing else.
    private volatile boolean bufferReady;
    private volatile boolean rebuffering;
    private volatile float progress = 0.03F;
    private volatile long lastFrameUploadedAt = System.currentTimeMillis();
    private volatile double displayedTs = Double.NaN;

    // Media position, tracked every tick so the audio controller can notice a frozen clock.
    private volatile double previousMediaSeconds = Double.NaN;
    private volatile boolean clockMoving;
    /** Timestamp of the newest frame our decoder produced (used to decide how to seek). */
    private volatile double lastDecodedTs = Double.NaN;
    /**
     * Whether the resolved stream honours HTTP Range. The decoder thread finds out while opening
     * the stream (probing the resolved link, not the parsing API that stands in front of it);
     * until then we assume Range, because a rewind cannot happen before anything is decoded.
     */
    private volatile boolean rangeOk = true;

    private volatile double pendingSeek = Double.NaN;
    private long lastSeekAt;
    private long lastAnchorAdjustAt;
    private long lastHealthReportAt;
    private long lastMediaReportAt;
    /** Early-EOF recovery: how often we may reposition, and how long we keep trying. */
    private long lastRecoveryAt;
    private int recoveries;

    private DynamicTexture texture;
    private ResourceLocation textureLocation;
    private int width;
    private int height;

    private PlaybackSession(CinemaScreenBlockEntity be, double startSeconds) {
        this.be = be;
        this.pos = be.getBlockPos();
        this.url = be.clientUrl();
        this.itemStartSeconds = 0.0;
        openDecoder(startSeconds);
    }

    public static PlaybackSession open(CinemaScreenBlockEntity be) {
        if (be.clientUrl().isBlank()) return null;
        return new PlaybackSession(be, be.clientPositionSeconds());
    }

    // =============================== clock ===============================

    /** The shared clock, straight from the last server snapshot. */
    private double targetSeconds() {
        return be.clientPositionSeconds();
    }

    /** Where our own media timeline currently is; this is what video and audio follow. */
    public double mediaSeconds() {
        return Math.max(0.0, targetSeconds() - itemStartSeconds);
    }

    private double hardResyncSeconds() {
        return ClientConfig.hardResyncSeconds;
    }

    public boolean matchesUrl(String other) {
        return url.equals(other);
    }

    /**
     * False once the world no longer knows this screen: the screen was taken back down, blown up or
     * the chunk went away. Keeps a stale session from playing on after its screen is gone.
     */
    public boolean valid() {
        return !be.isRemoved() && ClientPlayback.find(pos) == be;
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

    /** The direct link the local decoders open; equals {@link #url()} for direct MP4 links. */
    public String streamUrl() {
        String resolved = streamUrl;
        return resolved == null || resolved.isBlank() ? url : resolved;
    }

    public double durationSeconds() {
        return duration > 0.0 ? duration : be.clientDurationSeconds();
    }

    public double progress() {
        double total = durationSeconds();
        if (total <= 0.0) return 0.0;
        return Math.max(0.0, Math.min(1.0, mediaSeconds() / total));
    }

    /** True when the source honours HTTP Range, so seeking is instant. */
    public boolean rangeSupported() {
        return rangeOk;
    }

    /** True while this client is repositioning its stream (a seek, or catching up after one). */
    public boolean seeking() {
        return !Double.isNaN(pendingSeek)
                || (!bufferReady && System.currentTimeMillis() - lastSeekAt < SEEKING_MESSAGE_MILLIS);
    }

    /** Position of the last frame we actually showed, on our own media timeline. */
    public double displayedLocalSeconds() {
        return Double.isNaN(displayedTs) ? 0.0 : displayedTs;
    }

    /**
     * False while the shared clock is not moving (paused, globally frozen, or stuck). The audio
     * controller uses this to know when it has to restart at the new position instead of playing
     * stale samples.
     */
    public boolean clockMoving() {
        return clockMoving;
    }

    /** True when the sound for this screen may open: enough picture buffered, and not stopped. */
    public boolean audioReady() {
        return bufferReady && !rebuffering && !failed && !ended && !closed
                && be.clientPlaying() && !be.clientFrozen() && !be.clientUrl().isBlank();
    }

    /**
     * True once the media clock has moved a noticeable amount from {@code since}. Restarting
     * audio while the timeline is stuck would just spin, so the controller waits for this.
     */
    public boolean advancedSince(double since) {
        double current = mediaSeconds();
        double distance = Math.abs(current - since);
        double total = durationSeconds();
        return total > 0.0
                ? Math.min(distance, total - distance) >= 0.25
                : distance >= 0.25;
    }

    // =============================== lifecycle ===============================

    private void openDecoder(double startSeconds) {
        int generation = this.generation.incrementAndGet();
        Thread thread = new Thread(() -> decodeLoop(generation, startSeconds), "ZCinema Decoder " + pos);
        thread.setDaemon(true);
        this.decodeThread = thread;
        thread.start();
    }

    /**
     * Real divergence: jump our timeline onto the server anchor and let the decoder seek there.
     * The stream is never reopened - the decoder repositions inside its connection, so there is
     * no reconnect storm and no scaler churn.
     */
    private void hardResync() {
        if (closed) return;
        double anchor = targetSeconds();
        double current = mediaSeconds();
        double diff = current - anchor;
        itemStartSeconds += diff;
        double target = mediaSeconds();
        decodeHeartbeat = System.currentTimeMillis();
        failed = false;
        ended = false;
        errorMessage = null;
        recoveries = 0;
        lastSeekAt = System.currentTimeMillis();
        // The shared clock jumped. Everything the decoder produced sits at the *previous*
        // position, so drop it right now instead of waiting for the decode thread to notice:
        // stale frames must not keep the screen "ready" (which would also keep the old audio
        // instance alive) and must not be shown against the new clock.
        clearQueue(true);
        ScreenAudio.stop(pos);
        ZCinema.LOGGER.info("Screen {} jumped to the shared clock: was {}s, now {}s (delta {}s)",
                pos, String.format(java.util.Locale.ROOT, "%.3f", current),
                String.format(java.util.Locale.ROOT, "%.3f", target),
                String.format(java.util.Locale.ROOT, "%+.3f", diff));
        Thread thread = decodeThread;
        if (thread == null || !thread.isAlive()) {
            // The stream is gone (it blew up and was cleared by an earlier failure): reopen it.
            openDecoder(target);
            return;
        }
        if (!rangeOk && target < lastDecodedTs - 0.5) {
            // A source without Range can only ever move forward, so a rewind needs a fresh
            // connection. Letting the decoder "catch up" would park it forever, because the
            // frame it holds is ahead of the new clock.
            ZCinema.LOGGER.info("Screen {} rewinds to {}s: reopening the stream because the "
                    + "source has no Range support", pos,
                    String.format(java.util.Locale.ROOT, "%.3f", target));
            openDecoder(target);
            return;
        }
        pendingSeek = target;
    }

    /**
     * Recovers from a failure: if the decoder thread is gone (the stream blew up rather than
     * drifting) reopen it, otherwise reposition it inside the connection it already has.
     */
    private void restartDecoder() {
        if (closed) return;
        Thread thread = decodeThread;
        if (thread == null || !thread.isAlive()) {
            openDecoder(mediaSeconds());
            return;
        }
        hardResync();
    }

    /** Human drag on the seek slider: move this client's decoder immediately. */
    public void requestSeek(double targetSeconds) {
        if (closed) return;
        double target = Math.max(0.0, duration > 0.0 ? Math.min(targetSeconds, duration - 0.1) : targetSeconds);
        boolean needsFreshStream = !rangeOk
                && (target < lastDecodedTs - 0.5 || ended || failed);
        lastSeekAt = System.currentTimeMillis();
        // The user is moving the timeline: the old picture and sound belong to the old position,
        // so drop both at once instead of letting them play until the decoder thread reacts.
        clearQueue(true);
        ScreenAudio.stop(pos);
        if (ended || failed || needsFreshStream) {
            // Its decoder already stopped, or rewinding needs a stream that can only move
            // forward: start it again straight at the new position.
            ended = false;
            failed = false;
            errorMessage = null;
            recoveries = 0;
            if (needsFreshStream) {
                ZCinema.LOGGER.info("Screen {} rewinds to {}s: reopening the stream from the start "
                        + "because the source has no Range support", pos,
                        String.format(java.util.Locale.ROOT, "%.3f", target));
            }
            openDecoder(needsFreshStream ? 0.0 : target);
            return;
        }
        pendingSeek = target;
    }

    public void close() {
        if (closed) return;
        closed = true;
        Thread thread = decodeThread;
        if (thread != null) thread.interrupt();
        clearQueue(false);
        if (textureLocation != null) {
            ResourceLocation location = textureLocation;
            Minecraft.getInstance().execute(() -> Minecraft.getInstance().getTextureManager().release(location));
        }
        texture = null;
        textureLocation = null;
        ScreenAudio.stop(pos);
    }

    /** Drops every buffered frame; {@code resetReady} also puts the session back into buffering. */
    private void clearQueue(boolean resetReady) {
        synchronized (queue) {
            DecodedFrame frame;
            while ((frame = queue.pollFirst()) != null) frame.close();
            if (resetReady) {
                bufferReady = false;
                rebuffering = true;
                progress = 0.72F;
            }
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
            progress = 0.08F;
            // Resolve any indirect link (a parsing API, a share page) into a real stream first.
            String source;
            try {
                source = SourceResolver.resolve(url);
            } catch (IOException error) {
                failed = true;
                failedAt = System.currentTimeMillis();
                errorMessage = SourceResolver.describe(error);
                ZCinema.LOGGER.warn("Screen {} cannot resolve {}", pos, url, error);
                return;
            }
            streamUrl = source;
            grabber = new FFmpegFrameGrabber(source);
            grabber.setImageMode(FrameGrabber.ImageMode.RAW);
            configure(grabber);
            SourceResolver.applyStreamOptions(grabber, source);
            grabber.start();
            if (isRetired(generation)) return;
            progress = 0.18F;
            decodeHeartbeat = System.currentTimeMillis();

            double containerDuration = grabber.getLengthInTime() / 1_000_000.0;
            // Never let a re-parse shrink what we already know: a seeked HTTP stream can report
            // the length of what is left to read, which would clamp the timeline backwards.
            if (containerDuration > duration) duration = containerDuration;
            double knownDuration = durationSeconds();
            double requestedStart = knownDuration > 0.0
                    ? Math.min(Math.max(0.0, startSeconds), Math.max(0.0, knownDuration - 0.1))
                    : Math.max(0.0, startSeconds);

            double timestampTarget = 0.0;
            // Ask about the *resolved* link: a parsing API in front of a CDN would otherwise look
            // like the thing that has to honour Range, and its redirect answer usually does not.
            boolean canSeek = RangeSupport.supports(url, source);
            rangeOk = canSeek;
            if (requestedStart > 0.25 && canSeek) {
                try {
                    grabber.setTimestamp((long) (requestedStart * 1_000_000.0));
                    timestampTarget = requestedStart;
                } catch (Exception error) {
                    ZCinema.LOGGER.debug("Seek failed for {}, decoding from the start", url, error);
                    timestampTarget = 0.0;
                }
            } else if (requestedStart > 0.25) {
                ZCinema.LOGGER.info("Source {} does not support Range, decoding from the start and "
                        + "fast-forwarding to {}s", url,
                        String.format(java.util.Locale.ROOT, "%.3f", requestedStart));
            }
            progress = 0.60F;

            double timestampOrigin = Double.NaN;
            double maxBuffer = Math.max(0.25D, ClientConfig.bufferSeconds);
            progress = 0.72F;
            try (FrameScaler scaler = new FrameScaler()) {
                int maxWidth = ClientConfig.maxFrameWidth;
                int maxHeight = ClientConfig.maxFrameHeight;
                while (!isRetired(generation)) {
                    if (!Double.isNaN(pendingSeek)) {
                        double target = knownDuration > 0.0
                                ? Math.min(Math.max(0.0, pendingSeek), Math.max(0.0, knownDuration - 0.1))
                                : Math.max(0.0, pendingSeek);
                        pendingSeek = Double.NaN;
                        clearQueue(true);
                        if (canSeek) {
                            try {
                                grabber.setTimestamp((long) (target * 1_000_000.0));
                                timestampOrigin = Double.NaN;
                                timestampTarget = target;
                                ZCinema.LOGGER.info("Screen {} seeks its stream to {}s", pos,
                                        String.format(java.util.Locale.ROOT, "%.3f", target));
                            } catch (Exception error) {
                                // The connection refused to reposition. Continuing from the old
                                // position would leave the decoder ahead of the new clock (or
                                // decoding through minutes of video), so start a fresh stream.
                                ZCinema.LOGGER.warn("Screen {} could not seek to {}s, reopening the "
                                        + "stream", pos,
                                        String.format(java.util.Locale.ROOT, "%.3f", target), error);
                                openDecoder(target);
                                continue;
                            }
                        } else {
                            // No Range support: the connection can only ever move forward, so a
                            // target ahead is reached by decoding on, and one behind by the pacing
                            // loop holding frames back. Seeking here would break the connection.
                            ZCinema.LOGGER.debug("Screen {} fast-forwards to {}s (source has no Range)",
                                    pos, String.format(java.util.Locale.ROOT, "%.3f", target));
                        }
                        decodeHeartbeat = System.currentTimeMillis();
                        if (isRetired(generation)) return;
                    }

                    Frame frame = grabber.grabImage();
                    if (frame == null) {
                        double known = knownDuration > 0.0 ? knownDuration : durationSeconds();
                        boolean clockAtEnd = known <= 0.0 || mediaSeconds() >= known - 0.5;
                        if (clockAtEnd) {
                            // End of the file. Hold the last frame until the shared clock catches
                            // up with the end of the media, then call it a day.
                            boolean reachedEnd = awaitPlaybackEnd(generation);
                            if (isRetired(generation)) return;
                            if (!reachedEnd) {
                                // The timeline moved underneath us (a seek arrived, or playback
                                // resumed): keep going, the next loop iteration applies it.
                                continue;
                            }
                            ended = true;
                            if (!durationReported && duration > 0.0) sendDuration(duration);
                            ZCinema.LOGGER.info("Screen {} finished {} at {}s", pos, url,
                                    String.format(java.util.Locale.ROOT, "%.3f", mediaSeconds()));
                            return;
                        }
                        // The stream ran dry while the clock still has media to show - a seek the
                        // source could not honour, or a truncated download. Reposition instead of
                        // waiting for a clock that will never reach the end.
                        if (System.currentTimeMillis() - lastRecoveryAt < RECOVERY_DELAY_MILLIS) {
                            Thread.sleep(50L);
                            continue;
                        }
                        lastRecoveryAt = System.currentTimeMillis();
                        if (!canSeek || ++recoveries > MAX_EOF_RECOVERIES) {
                            throw new IOException("stream ended early at "
                                    + String.format(java.util.Locale.ROOT, "%.3f", mediaSeconds()) + "s");
                        }
                        ZCinema.LOGGER.debug("Screen {} stream ended early, repositioning to {}s",
                                pos, String.format(java.util.Locale.ROOT, "%.3f", mediaSeconds()));
                        clearQueue(true);
                        pendingSeek = Math.max(0.0, mediaSeconds() - 0.5);
                        continue;
                    }
                    decodeHeartbeat = System.currentTimeMillis();
                    recoveries = 0;
                    if (!(frame.opaque instanceof AVFrame)) continue;

                    double rawTs = frame.timestamp / 1_000_000.0;
                    if (Double.isNaN(timestampOrigin)) timestampOrigin = rawTs - timestampTarget;
                    double ts = Math.max(0.0, rawTs - timestampOrigin);
                    lastDecodedTs = ts;

                    while (!isRetired(generation)) {
                        // A new seek outranks waiting for the clock: the shared clock may have
                        // jumped backwards, in which case this frame's position would never be
                        // reached again and the loop would sleep here forever.
                        if (!Double.isNaN(pendingSeek)) break;
                        if (ts <= mediaSeconds() + maxBuffer) break;
                        decodeHeartbeat = System.currentTimeMillis();
                        Thread.sleep(4L);
                    }
                    if (isRetired(generation)) return;
                    if (ts < mediaSeconds() - 0.12) continue; // already past, do not scale it

                    DecodedFrame decoded = scaler.decode(frame, maxWidth, maxHeight);
                    if (decoded == null) continue;
                    enqueue(generation, new DecodedFrame(decoded.image, ts));
                    recoveries = 0;
                }
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        } catch (Throwable error) {
            if (!closed && !isRetired(generation)) {
                // The stream may have been rejected because its signed address expired; drop the
                // cached resolution so the next attempt fetches a fresh one.
                SourceResolver.forget(url);
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

    /**
     * Waits at the end of the stream for the shared clock to catch up with the media, so the last
     * frame simply holds instead of the screen going black. Returns false when the timeline moved
     * out from under us (seek, resume) and decoding should carry on.
     */
    private boolean awaitPlaybackEnd(int generation) throws InterruptedException {
        double known = duration > 0.0 ? duration : be.clientDurationSeconds();
        while (!isRetired(generation) && Double.isNaN(pendingSeek)
                && known > 0.0 && mediaSeconds() < known - 0.05) {
            decodeHeartbeat = System.currentTimeMillis();
            Thread.sleep(4L);
        }
        return known <= 0.0 || mediaSeconds() >= known - 0.05 || Double.isNaN(pendingSeek);
    }

    private void enqueue(int generation, DecodedFrame frame) {
        while (!isRetired(generation)) {
            synchronized (queue) {
                if (queue.size() < MAX_BUFFERED_FRAMES) {
                    queue.addLast(frame);
                    if (!bufferReady) {
                        double buffered = bufferedSeconds();
                        progress = (float) Math.min(0.95, 0.72 + 0.23 * buffered / STARTUP_BUFFER_SECONDS);
                        if (buffered >= STARTUP_BUFFER_SECONDS) markBufferReady();
                    }
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

    private void markBufferReady() {
        synchronized (queue) {
            if (queue.isEmpty()) return;
            bufferReady = true;
            rebuffering = false;
            progress = 0.95F;
            ZCinema.LOGGER.debug("Screen {} buffered {}s and is ready", pos,
                    String.format(java.util.Locale.ROOT, "%.3f", bufferedSeconds()));
        }
    }

    private double bufferedSeconds() {
        if (queue.size() < 2) return 0.0;
        return Math.max(0.0, queue.peekLast().ts - queue.peekFirst().ts);
    }

    // =============================== per-frame ===============================

    /**
     * Picks the frame matching our media clock and uploads it. Called from the block entity
     * renderer, so it only ever runs while the screen is actually visible.
     *
     * <p>Ownership rule: a frame handed to the texture belongs to the texture from then on
     * ({@code DynamicTexture.setPixels} takes it over and closes the previous one), so it must
     * never be closed here. Frames that were only skipped over are closed instead.
     */
    public FrameView renderFrame() {
        touchStamp.set(System.currentTimeMillis());
        if (closed || be.clientUrl().isBlank() || !valid()) return null;

        DecodedFrame frame = null;
        if (bufferReady) {
            double master = mediaSeconds();
            synchronized (queue) {
                while (!queue.isEmpty() && queue.peekFirst().ts <= master + DISPLAY_LEAD_SECONDS) {
                    if (frame != null) frame.close();
                    frame = queue.pollFirst();
                }
                if (frame == null && queue.isEmpty() && textureLocation != null
                        && System.currentTimeMillis() - lastFrameUploadedAt > VIDEO_UNDERRUN_GRACE_MILLIS) {
                    // Nothing left to show and nothing new arrived: we really are buffering.
                    bufferReady = false;
                    rebuffering = true;
                    progress = 0.72F;
                    ZCinema.LOGGER.debug("Screen {} ran dry at {}s, buffering again", pos,
                            String.format(java.util.Locale.ROOT, "%.3f", master));
                }
            }
        }

        if (frame != null) {
            lastFrameUploadedAt = System.currentTimeMillis();
            displayedTs = frame.ts;
            progress = 1.0F;
            upload(frame);
        }
        return textureLocation == null ? null : new FrameView(textureLocation, width, height);
    }

    private FrameView upload(DecodedFrame frame) {
        if (texture == null || width != frame.width || height != frame.height) {
            if (textureLocation != null) Minecraft.getInstance().getTextureManager().release(textureLocation);
            textureLocation = ResourceLocation.fromNamespaceAndPath(ZCinema.MODID,
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
        if (closed) return;
        if (!valid()) {
            close();
            return;
        }

        double media = mediaSeconds();
        clockMoving = !be.clientFrozen() && media != previousMediaSeconds;
        previousMediaSeconds = media;

        if (duration > 0.0 && Math.abs(be.clientDurationSeconds() - duration) > 0.05
                && System.currentTimeMillis() - lastMediaReportAt > MEDIA_REPORT_INTERVAL_MILLIS) {
            sendDuration(duration);
        }

        if (be.clientPlaying() && !be.clientFrozen() && !failed && !ended
                && System.currentTimeMillis() - decodeHeartbeat > DECODER_DEAD_MILLIS) {
            failed = true;
            failedAt = System.currentTimeMillis();
            errorMessage = "decoder stalled";
        }
        if (failed && System.currentTimeMillis() - failedAt > RETRY_DELAY_MILLIS) {
            restartDecoder();
        }
        if (ended && duration > 0.0 && targetSeconds() < duration - 0.5) {
            // The shared timeline left the end of the film (someone replayed it, seeked back, or a
            // link was reloaded under us). An ended decoder has no thread left, so it has to be
            // started again - otherwise the screen stays stuck on the last frame until somebody
            // seeks, which looks exactly like "nothing will play".
            ended = false;
            errorMessage = null;
            recoveries = 0;
            decodeHeartbeat = System.currentTimeMillis();
            ZCinema.LOGGER.info("Screen {} timeline moved back to {}s, decoding again", pos,
                    String.format(java.util.Locale.ROOT, "%.3f", mediaSeconds()));
            openDecoder(mediaSeconds());
        }

        applyServerAnchor();
        // The anchor may have jumped (a seek): the audio controller has to compare against where
        // the media timeline is NOW, not against the pre-resync value read above.
        media = mediaSeconds();
        reportHealth();
        ScreenAudio.update(this, media);
    }

    /**
     * Create Cinema's anchor correction: compare our media timeline against the shared clock and
     * pull it towards it by a few percent per second. Only a real divergence repositions the
     * stream, and that path has no cooldown dance it can get stuck in.
     */
    private void applyServerAnchor() {
        if (closed || failed || ended) return;
        double anchor = targetSeconds();
        double current = mediaSeconds();
        double diff = current - anchor;
        if (Math.abs(diff) <= 0.15) return;
        long now = System.currentTimeMillis();
        if (lastAnchorAdjustAt == 0L) {
            lastAnchorAdjustAt = now;
            return;
        }
        double elapsed = Math.min(1.0, (now - lastAnchorAdjustAt) / 1_000.0);
        lastAnchorAdjustAt = now;
        if (Math.abs(diff) >= hardResyncSeconds()) {
            hardResync();
            return;
        }
        double correction = Math.copySign(Math.min(Math.abs(diff), Math.max(0.002, elapsed * 0.05)), diff);
        itemStartSeconds += correction;
    }

    /** What this session tells the server about its own decoding. */
    private void reportHealth() {
        long now = System.currentTimeMillis();
        if (now - lastHealthReportAt < HEALTH_REPORT_INTERVAL_MILLIS) return;
        lastHealthReportAt = now;
        ModNetworking.sendToServer(new C2SHealthPacket(pos, health()));
    }

    public PlaybackHealth health() {
        if (failed) {
            String message = errorMessage == null ? "" : errorMessage.toLowerCase(java.util.Locale.ROOT);
            boolean networkish = message.contains("timed out") || message.contains("timeout")
                    || message.contains("connection") || message.contains("host")
                    || message.contains("http 4") || message.contains("http 5");
            return networkish ? PlaybackHealth.SOURCE_UNREACHABLE : PlaybackHealth.BUFFERING;
        }
        if (!bufferReady || rebuffering) return PlaybackHealth.BUFFERING;
        return PlaybackHealth.HEALTHY;
    }

    private void sendDuration(double seconds) {
        durationReported = true;
        lastMediaReportAt = System.currentTimeMillis();
        ModNetworking.sendToServer(new C2SReportMediaPacket(pos, url, (long) (seconds * 1000L)));
    }

    // =============================== status ===============================

    public long touchedAt() {
        return touchStamp.get();
    }

    public boolean starving() {
        return rebuffering;
    }

    public String errorMessage() {
        return errorMessage;
    }

    public Status status() {
        if (be.clientUrl().isBlank()) return Status.IDLE;
        if (failed) return Status.ERROR;
        if (ended) return Status.ENDED;
        if (textureLocation == null || !bufferReady) return Status.LOADING;
        if (be.clientFrozen()) return Status.LOADING;
        if (!be.clientPlaying()) return Status.PAUSED;
        if (rebuffering) return Status.LOADING;
        return Status.PLAYING;
    }
}
