package com.zfy.zcinema.client.playback;

import com.zfy.zcinema.ZCinema;
import com.zfy.zcinema.ZCinemaLog;
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

    private final long openedAt = System.currentTimeMillis();
    private long lastStateLogAt;
    private long seekAppliedAt;
    private double seekLandedTarget = Double.NaN;
    /** Decode throughput counters, so a slow machine can be told apart from a stalled one. */
    private final AtomicInteger decodedFrames = new AtomicInteger();
    private final AtomicInteger droppedFrames = new AtomicInteger();
    private int lastDecodedFrames;
    private int lastDroppedFrames;

    /**
     * Offset between the shared clock and our own media timeline. Normally 0: the local timeline
     * simply follows the shared clock, and a real divergence is handled by {@link #hardResync()}.
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
    /** When the decoded position first diverged from the shared clock by a real amount. */
    private long divergenceSince;
    /** True from issuing a reposition until its frames landed (or gave up). */
    private volatile boolean resyncing;
    private long resyncSince;
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
        ZCinemaLog.log("session", "opened screen=%s start=%.3fs url=%s", pos.toShortString(), startSeconds,
                ZCinemaLog.shorten(url, 300));
        openDecoder(startSeconds);
    }

    public static PlaybackSession open(CinemaScreenBlockEntity be) {
        if (be.isRemoved() || be.clientUrl().isBlank()) return null;
        // A render list can offer a block entity the level has already replaced; its session
        // could never become valid, so it must not be opened (and decoded) in the first place.
        if (ClientPlayback.find(be.getBlockPos()) != be) return null;
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

    /** Why the audio controller may not open right now; used by {@link ScreenAudio}'s diagnostics. */
    public String audioReadyBlocker() {
        if (closed) return "session closed";
        if (failed) return "decoder failed";
        if (ended) return "at end";
        if (!be.clientPlaying()) return "paused";
        if (be.clientFrozen()) return "clock frozen";
        if (be.clientUrl().isBlank()) return "no url";
        if (rebuffering || !bufferReady) return "buffering";
        return null;
    }

    /**
     * True once the media clock has moved a noticeable amount from {@code since}. Restarting
     * audio while the timeline is stuck would just spin, so the controller waits for this.
     */
    public boolean advancedSince(double since) {
        double current = audioReferenceSeconds();
        double distance = Math.abs(current - since);
        double total = durationSeconds();
        return total > 0.0
                ? Math.min(distance, total - distance) >= 0.25
                : distance >= 0.25;
    }

    /**
     * Where the sound for this screen should be right now: the picture the viewer is actually
     * looking at, pulled back by the configured audio delay. The delay compensates the display
     * pipeline (a rendered frame reaches the eyes later than the sound reaches the ears), which
     * would otherwise make the sound lead the picture by roughly that much. Anchoring to the
     * displayed frame also keeps the sound matched to this client's actual decode position
     * instead of the abstract shared clock.
     */
    public double audioReferenceSeconds() {
        double picture = Double.isNaN(displayedTs) ? mediaSeconds() : displayedTs;
        return Math.max(0.0, picture - ClientConfig.audioDelayMs / 1000.0);
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
     * Line this client up with the shared clock: drop everything decoded at the old position and
     * reposition the stream, or reopen it when the source cannot seek. The shared clock is the
     * timeline everything follows, so the media timeline is reset onto it.
     */
    private void hardResync() {
        if (closed) return;
        double anchor = targetSeconds();
        double local = Double.isNaN(displayedTs) ? lastDecodedTs : displayedTs;
        double diff = Double.isNaN(local) ? 0.0 : local - anchor;
        itemStartSeconds = 0.0;
        double target = anchor;
        decodeHeartbeat = System.currentTimeMillis();
        failed = false;
        ended = false;
        errorMessage = null;
        recoveries = 0;
        divergenceSince = 0L;
        lastSeekAt = System.currentTimeMillis();
        resyncing = true;
        resyncSince = System.currentTimeMillis();
        // Everything the decoder produced sits at the *previous* position, so drop it right now
        // instead of waiting for the decode thread to notice: stale frames must not keep the
        // screen "ready" (which would also keep the old audio instance alive).
        clearQueue(true);
        ScreenAudio.stop(pos, "shared clock jumped");
        ZCinema.LOGGER.info("Screen {} jumped to the shared clock: was {}s, now {}s (delta {}s)",
                pos, String.format(java.util.Locale.ROOT, "%.3f", local),
                String.format(java.util.Locale.ROOT, "%.3f", target),
                String.format(java.util.Locale.ROOT, "%+.3f", diff));
        ZCinemaLog.log("seek", "hard resync screen=%s from=%.3fs to=%.3fs delta=%+.3fs threadAlive=%s "
                        + "rangeOk=%s lastDecoded=%.3fs", pos.toShortString(), local, target, diff,
                decodeThread != null && decodeThread.isAlive(), rangeOk, lastDecodedTs);
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
        resyncing = true;
        resyncSince = System.currentTimeMillis();
        ZCinemaLog.log("seek", "request screen=%s target=%.3fs from=%.3fs rangeOk=%s ended=%s failed=%s "
                        + "freshStream=%s", pos.toShortString(), target, mediaSeconds(), rangeOk, ended,
                failed, needsFreshStream);
        // The user is moving the timeline: the old picture and sound belong to the old position,
        // so drop both at once instead of letting them play until the decoder thread reacts.
        clearQueue(true);
        ScreenAudio.stop(pos, "seek requested");
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
        close("unspecified");
    }

    public void close(String reason) {
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
        ScreenAudio.stop(pos, "session closed: " + reason);
        ZCinemaLog.log("session", "closed screen=%s reason=%s lifetime=%.1fs", pos.toShortString(), reason,
                (System.currentTimeMillis() - openedAt) / 1000.0);
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
            long resolveStarted = System.currentTimeMillis();
            String source;
            try {
                source = SourceResolver.resolve(url);
            } catch (IOException error) {
                failed = true;
                failedAt = System.currentTimeMillis();
                errorMessage = SourceResolver.describe(error);
                ZCinema.LOGGER.warn("Screen {} cannot resolve {}", pos, url, error);
                ZCinemaLog.log("decode", "resolve FAILED screen=%s url=%s error=%s", pos.toShortString(),
                        ZCinemaLog.shorten(url, 300), error.getMessage());
                return;
            }
            streamUrl = source;
            ZCinemaLog.log("decode", "resolved in %dms: %s", System.currentTimeMillis() - resolveStarted,
                    ZCinemaLog.shorten(source, 300));
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
            ZCinemaLog.log("decode", "stream open screen=%s requestedStart=%.3fs containerDuration=%.3fs "
                            + "knownDuration=%.3fs rangeOk=%s",
                    pos.toShortString(), requestedStart, containerDuration, knownDuration, canSeek);
            if (requestedStart > 0.25 && canSeek) {
                try {
                    grabber.setTimestamp((long) (requestedStart * 1_000_000.0));
                    timestampTarget = requestedStart;
                    seekAppliedAt = System.currentTimeMillis();
                    seekLandedTarget = requestedStart;
                } catch (Exception error) {
                    ZCinema.LOGGER.debug("Seek failed for {}, decoding from the start", url, error);
                    ZCinemaLog.log("seek", "initial seek FAILED target=%.3fs, decoding from 0: %s",
                            requestedStart, error.getMessage());
                    timestampTarget = 0.0;
                }
            } else if (requestedStart > 0.25) {
                ZCinema.LOGGER.info("Source {} does not support Range, decoding from the start and "
                        + "fast-forwarding to {}s", url,
                        String.format(java.util.Locale.ROOT, "%.3f", requestedStart));
                ZCinemaLog.log("seek", "join fast-forward target=%.3fs (no Range)", requestedStart);
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
                                seekAppliedAt = System.currentTimeMillis();
                                seekLandedTarget = target;
                                ZCinemaLog.log("seek", "applied target=%.3fs waited=%dms", target,
                                        seekAppliedAt - lastSeekAt);
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
                            ZCinemaLog.log("seek", "fast-forward target=%.3fs (no Range)", target);
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
                            ZCinemaLog.log("decode", "end of media screen=%s duration=%.3fs",
                                    pos.toShortString(), durationSeconds());
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
                        ZCinemaLog.log("decode", "early EOF screen=%s at=%.3fs recovery=%d",
                                pos.toShortString(), mediaSeconds(), recoveries);
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
                    if (!Double.isNaN(seekLandedTarget) && ts >= seekLandedTarget - 0.5) {
                        ZCinemaLog.log("seek", "landed target=%.3fs firstFrame=%.3fs took=%dms",
                                seekLandedTarget, ts, System.currentTimeMillis() - seekAppliedAt);
                        seekLandedTarget = Double.NaN;
                        resyncing = false;
                    }

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
                    if (ts < mediaSeconds() - 0.12) {
                        droppedFrames.incrementAndGet(); // already past, do not scale it
                        continue;
                    }

                    DecodedFrame decoded = scaler.decode(frame, maxWidth, maxHeight);
                    if (decoded == null) continue;
                    enqueue(generation, new DecodedFrame(decoded.image, ts));
                    decodedFrames.incrementAndGet();
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
                ZCinemaLog.log("decode", "FAILED screen=%s from=%.3fs cause=%s", pos.toShortString(),
                        startSeconds, ZCinemaLog.cause(error));
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
            resyncing = false;
            progress = 0.95F;
            ZCinema.LOGGER.debug("Screen {} buffered {}s and is ready", pos,
                    String.format(java.util.Locale.ROOT, "%.3f", bufferedSeconds()));
            ZCinemaLog.log("buffer", "ready screen=%s buffered=%.3fs queue=%d", pos.toShortString(),
                    bufferedSeconds(), queue.size());
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
                // Safety net: a shared-clock jump can leave the queue full of frames from the
                // previous position. If the oldest one is far beyond where we are, the whole
                // queue is stale - drop it and go back to buffering instead of showing nothing
                // forever while the session stays "ready" (which would also keep old audio).
                if (!queue.isEmpty() && queue.peekFirst().ts > master + hardResyncSeconds() + 0.5) {
                    ZCinemaLog.log("buffer", "stale queue dropped screen=%s first=%.3fs master=%.3fs",
                            pos.toShortString(), queue.peekFirst().ts, master);
                    clearQueue(true);
                }
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
                    ZCinemaLog.log("buffer", "underrun screen=%s master=%.3fs", pos.toShortString(), master);
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
            close("world no longer has this screen");
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
            ZCinemaLog.log("decode", "watchdog: no frames for %dms, marking failed",
                    System.currentTimeMillis() - decodeHeartbeat);
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
            ZCinemaLog.log("session", "replaying after end screen=%s at=%.3fs", pos.toShortString(),
                    mediaSeconds());
            openDecoder(mediaSeconds());
        }

        applyServerAnchor();
        // The anchor may have jumped (a seek): the audio controller has to compare against where
        // the media timeline is NOW, not against the pre-resync value read above.
        media = mediaSeconds();
        reportHealth();
        ScreenAudio.update(this, audioReferenceSeconds());
        logState(media);
    }

    /** One line per second per screen: everything needed to explain a desync after the fact. */
    private void logState(double media) {
        long now = System.currentTimeMillis();
        if (now - lastStateLogAt < 1000L) return;
        lastStateLogAt = now;
        int buffered;
        synchronized (queue) {
            buffered = queue.size();
        }
        double videoError = Double.isNaN(displayedTs) ? Double.NaN : displayedTs - media;
        double audioPosition = ScreenAudio.debugPosition(pos);
        double audioError = Double.isNaN(audioPosition) ? Double.NaN : audioPosition - media;
        int decoded = decodedFrames.get();
        int dropped = droppedFrames.get();
        int decodedRate = decoded - lastDecodedFrames;
        int droppedRate = dropped - lastDroppedFrames;
        lastDecodedFrames = decoded;
        lastDroppedFrames = dropped;
        ZCinemaLog.log("state", "screen=%s shared=%.3fs media=%.3fs itemStart=%+.3fs drift=%+.3fs "
                        + "videoErr=%s audioErr=%s frame=%s buf=%d/%s ready=%s rebuf=%s seeking=%s "
                        + "resyncing=%s playing=%s frozen=%s clockMoving=%s failed=%s ended=%s rangeOk=%s "
                        + "video=%d/s drop=%d/s",
                pos.toShortString(), targetSeconds(), media, itemStartSeconds, media - targetSeconds(),
                fmt(videoError), fmt(audioError), fmt(displayedTs), buffered, fmt(bufferedSeconds()),
                bufferReady, rebuffering, seeking(), resyncing, be.clientPlaying(), be.clientFrozen(),
                clockMoving, failed, ended, rangeOk, decodedRate, droppedRate);
    }

    private static String fmt(double value) {
        return Double.isNaN(value) ? "n/a" : String.format(java.util.Locale.ROOT, "%.3f", value);
    }

    /**
     * The decoder's real position against the shared clock. {@code mediaSeconds()} cannot be used
     * for this: it is <em>defined</em> as the shared clock minus {@code itemStartSeconds}, so
     * comparing the two always reports "in sync" and a reposition would never fire. Instead,
     * compare the frame the viewer is actually looking at (or the newest decoded one) and
     * reposition the stream once it is more than {@code hardResyncSeconds} away from the clock.
     */
    private void applyServerAnchor() {
        if (closed || failed || ended) return;
        long now = System.currentTimeMillis();
        if (resyncing) {
            // Do not stack repositions: the one in flight will land (or has failed).
            if (now - resyncSince < 15_000L) return;
            ZCinemaLog.log("clock", "resync watchdog screen=%s: no landing after %dms, retrying",
                    pos.toShortString(), now - resyncSince);
            resyncing = false;
        }
        double anchor = targetSeconds();
        double local = Double.isNaN(displayedTs) ? lastDecodedTs : displayedTs;
        if (Double.isNaN(local)) return;
        double diff = local - anchor;
        if (Math.abs(diff) < hardResyncSeconds()) {
            divergenceSince = 0L;
            return;
        }
        if (divergenceSince == 0L) {
            divergenceSince = now;
            ZCinemaLog.log("clock", "divergence screen=%s anchor=%.3fs decoded=%.3fs delta=%+.3fs",
                    pos.toShortString(), anchor, local, diff);
            return;
        }
        // Only act when the divergence persists: a single late frame is normal. Note this must
        // not use seeking(): that also reports true for every recent seek or rebuffer, which
        // used to block the reposition for 20s and left slow machines fast-forward-decoding.
        if (now - divergenceSince < 500L || now - lastSeekAt < 1_000L) return;
        ZCinemaLog.log("clock", "reposition screen=%s anchor=%.3fs decoded=%.3fs delta=%+.3fs "
                        + "sustained=%dms",
                pos.toShortString(), anchor, local, diff, now - divergenceSince);
        hardResync();
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
