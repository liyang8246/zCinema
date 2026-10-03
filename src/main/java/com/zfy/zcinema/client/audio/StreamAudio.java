package com.zfy.zcinema.client.audio;

import com.zfy.zcinema.ZCinema;
import com.zfy.zcinema.ZCinemaLog;
import com.zfy.zcinema.client.playback.SourceResolver;
import net.minecraft.client.sounds.AudioStream;

import javax.sound.sampled.AudioFormat;
import java.io.IOException;
import java.io.InputStream;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.DoubleBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.DoubleSupplier;

/**
 * Streamed PCM for one screen, decoded from the same direct link the video comes from. This is a
 * straight port of Create Cinema's network audio stream: the grabber seeks to the requested
 * position, waits for a second of PCM so playback never starts with a gap, and trims the seek
 * pre-roll so picture and sound line up instead of the sound leading by a GOP length.
 */
public final class StreamAudio implements AudioStream {
    private static final int MAX_BUFFERED_PCM_CHUNKS = 96;
    private static final int STARTUP_BUFFER_MILLIS = 1_000;
    private static final int STARTUP_WAIT_TIMEOUT_MILLIS = 2_000;
    private static final int MAX_BUFFERED_PCM_MILLIS = 4_000;
    private static final int MAX_READ_MILLIS = 250;
    /** Far jumps are treated as a new stream rather than a catch-up; the controller resyncs those. */
    private static final double MAX_CATCH_UP_SECONDS = 3.0;
    private static final ExecutorService CLOSE_EXECUTOR = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "ZCinema Audio Close");
        thread.setDaemon(true);
        return thread;
    });

    private final org.bytedeco.javacv.FFmpegFrameGrabber grabber;
    private final AudioFormat format;
    private final double duration;
    private final InputStream input;
    private final DoubleSupplier startSeconds;
    private final double currentStart;
    private volatile double startTime = Double.NaN;
    private volatile boolean started;
    private volatile long firstReadAt;
    private final ArrayBlockingQueue<ByteBuffer> decoded = new ArrayBlockingQueue<>(MAX_BUFFERED_PCM_CHUNKS);
    private final AtomicInteger bufferedBytes = new AtomicInteger();
    private final AtomicBoolean resourcesClosed = new AtomicBoolean();
    private final Thread decoderThread;

    private ByteBuffer pending = ByteBuffer.allocate(0);
    private volatile boolean closed;
    private volatile boolean decoderEnded;
    private long discardFrames;
    private double exactStartSeconds = Double.NaN;
    /** Content frames still to drop so silence already handed to the engine cannot slide the sound. */
    private int healFrames;
    private long lastStarvationLogAt;

    public StreamAudio(String url, DoubleSupplier startSeconds, double knownDuration) throws IOException {
        long openedAt = System.currentTimeMillis();
        org.bytedeco.javacv.FFmpegFrameGrabber opened = null;
        InputStream openedInput = null;
        try {
            opened = new org.bytedeco.javacv.FFmpegFrameGrabber(url);
            opened.setVideoStream(-1);
            SourceResolver.applyStreamOptions(opened, url);
            opened.start();
            double resolvedDuration = opened.getLengthInTime() / 1_000_000.0;
            // A streamed source often reports the length of what it has read so far, which would
            // be nonsense as a duration: prefer it only when it looks like the whole thing, and
            // otherwise lean on the duration the video side already learned.
            duration = resolvedDuration > 1.0 ? resolvedDuration : Math.max(0.0, knownDuration);
            grabber = opened;
            input = openedInput;
            // Where the media is *now*, not where it was before opening the decoder: start() can
            // take a moment on a network stream, and seeking to a stale position is exactly what
            // makes the sound walk half a second behind the picture.
            this.startSeconds = startSeconds;
            double currentSeconds = Math.max(0.0, startSeconds.getAsDouble());
            this.currentStart = duration > 0.0 ? wrap(currentSeconds, duration) : currentSeconds;
            // Seek after start(): JavaCV clears a pending timestamp inside start(), so a seek set
            // before it would be dropped and the audio would decode from the very beginning.
            if (currentStart > 0.0) {
                try {
                    grabber.setTimestamp((long) (currentStart * 1_000_000.0));
                } catch (Exception error) {
                    ZCinema.LOGGER.debug("Audio seek to {}s failed, decoding from the start",
                            currentStart, error);
                    ZCinemaLog.log("audio", "stream seek FAILED target=%.3fs: %s", currentStart,
                            error.getMessage());
                }
            }
            int sampleRate = grabber.getSampleRate() > 0 ? grabber.getSampleRate() : 48_000;
            format = new AudioFormat(sampleRate, 16, Math.max(1, Math.min(2, grabber.getAudioChannels())),
                    true, false);
            if (currentStart > 0.0) exactStartSeconds = currentStart;
            decoderThread = new Thread(this::decodeAudio, "ZCinema Audio Decode");
            decoderThread.setDaemon(true);
            decoderThread.start();
            // Wait for a second of PCM, then keep filling until the buffer also covers the media
            // time spent buffering. The start position is picked last and everything before it is
            // trimmed, and the trim must not eat into the second the engine queues up front:
            // otherwise the engine tops the shortfall up with silence and every later sample sits
            // exactly that far behind the picture, permanently and invisibly.
            waitForStartupBuffer();
            waitForCatchUpBuffer();
            double catchUp = currentCatchUp();
            double discarded = 0.0;
            try {
                discarded = discardBufferedSeconds(catchUp);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            }
            startTime = wrap(currentStart + discarded, duration);
            double shortfall = Math.max(0.0, catchUp - discarded);
            if (shortfall > 0.0) healFrames += (int) Math.round(shortfall * format.getSampleRate());
            ZCinemaLog.log("audio", "stream ready url=%s duration=%.3fs %.0fHz x%d start=%.3fs "
                            + "catchUp=%.3fs discarded=%.3fs heal=%.3fs buffered=%.3fs took=%dms",
                    ZCinemaLog.shorten(url, 200), duration, format.getSampleRate(),
                    format.getChannels(), startTime, catchUp, discarded, shortfall, bufferedSeconds(),
                    System.currentTimeMillis() - openedAt);
        } catch (Exception error) {
            if (opened != null) {
                try {
                    opened.close();
                } catch (Exception ignored) {
                }
            }
            if (openedInput != null) {
                try {
                    openedInput.close();
                } catch (IOException ignored) {
                }
            }
            throw new IOException("Failed to open screen audio", error);
        }
    }

    @Override
    public AudioFormat getFormat() {
        return format;
    }

    /** Position in the media this stream starts sounding at, chosen once it opens. */
    public double startTime() {
        return startTime;
    }

    /** True once the sound engine pulled its first samples. */
    public boolean started() {
        return started;
    }

    /**
     * Wall-clock position of this stream's audible timeline, counted from the first read. The
     * engine consumes at the playback rate, so wall time tracks the speakers - while the data
     * written into OpenAL runs up to about one queued buffer ahead of the play cursor and must
     * not be mistaken for what is audible right now. Silence padded over a data gap is trimmed
     * off the following content, so it never shifts this timeline either.
     */
    public double playedSeconds() {
        long base = firstReadAt;
        if (!started || base == 0L) return Double.NaN;
        return startTime + (System.currentTimeMillis() - base) / 1000.0;
    }

    public boolean decoderEnded() {
        return decoderEnded;
    }

    @Override
    public ByteBuffer read(int requestedBytes) throws IOException {
        prepareFirstRead();
        return readPcm(requestedBytes);
    }

    /** Records when the engine actually pulled sound, for the wall-clock position estimate. */
    private void prepareFirstRead() {
        if (started) return;
        started = true;
        firstReadAt = System.currentTimeMillis();
        double engineDelay = forwardDelta(wrap(startSeconds.getAsDouble(), duration), startTime, duration);
        ZCinemaLog.log("audio", "first read start=%.3fs engineDelay=%.3fs buffered=%.3fs",
                startTime, engineDelay, bufferedSeconds());
    }

    private ByteBuffer readPcm(int requestedBytes) throws IOException {
        ByteBuffer output = ByteBuffer.allocateDirect(
                Math.max(1, Math.min(requestedBytes, bytesForMillis(MAX_READ_MILLIS))))
                .order(ByteOrder.LITTLE_ENDIAN);
        try {
            while (output.hasRemaining() && !closed) {
                if (!pending.hasRemaining()) {
                    ByteBuffer next = decoded.poll();
                    if (next == null) break;
                    bufferedBytes.addAndGet(-next.remaining());
                    pending = next;
                }
                if (skipHealedFrames(pending)) continue;
                if (!pending.hasRemaining()) continue;
                int count = Math.min(output.remaining(), pending.remaining());
                int limit = pending.limit();
                pending.limit(pending.position() + count);
                output.put(pending);
                pending.limit(limit);
            }
            if (output.hasRemaining() && !closed) {
                int frameSize = Math.max(1, format.getFrameSize());
                int silentFrames = output.remaining() / frameSize;
                while (output.hasRemaining()) output.put((byte) 0);
                if (silentFrames > 0) {
                    healFrames += silentFrames;
                    logStarvation();
                }
            }
            return output.flip();
        } catch (Throwable error) {
            if (error instanceof IOException io) throw io;
            throw new IOException("Failed to read screen audio", error);
        }
    }

    /**
     * Drops content the stream has already moved past: when the decoder runs dry, the silence
     * handed to the engine plays in place of the samples that should have been there, so the next
     * samples have to be trimmed by exactly as much. Without this the whole sound would stay
     * behind the picture for good and nothing in the byte stream would show it.
     */
    private boolean skipHealedFrames(ByteBuffer buffer) {
        if (healFrames <= 0 || !buffer.hasRemaining()) return false;
        int frameSize = Math.max(1, format.getFrameSize());
        int frames = Math.min(healFrames, buffer.remaining() / frameSize);
        if (frames <= 0) return false;
        buffer.position(buffer.position() + frames * frameSize);
        healFrames -= frames;
        return true;
    }

    private void logStarvation() {
        long now = System.currentTimeMillis();
        if (now - lastStarvationLogAt < 2_000L) return;
        lastStarvationLogAt = now;
        int framesPerSecond = Math.max(1, Math.round(format.getSampleRate()));
        ZCinemaLog.log("audio", "starved: padded silence, heal pending=%dms buffered=%.3fs",
                Math.round(healFrames * 1000.0 / framesPerSecond), bufferedSeconds());
    }

    private void decodeAudio() {
        try {
            while (!closed) {
                org.bytedeco.javacv.Frame frame = grabSamples();
                if (frame == null) break;
                ByteBuffer pcm = convert(frame, format.getChannels());
                enqueueDecoded(pcm);
            }
        } catch (Throwable error) {
            if (!closed) {
                ZCinema.LOGGER.debug("Screen audio decoder stopped", error);
            }
        } finally {
            decoderEnded = true;
            ZCinemaLog.log("audio", "decode thread ended");
            closeResources();
        }
    }

    private void enqueueDecoded(ByteBuffer pcm) throws InterruptedException {
        if (!pcm.hasRemaining()) return;
        while (!closed && bufferedBytes.get() >= bytesForMillis(MAX_BUFFERED_PCM_MILLIS)) {
            TimeUnit.MILLISECONDS.sleep(10L);
        }
        while (!closed) {
            int bytes = pcm.remaining();
            bufferedBytes.addAndGet(bytes);
            if (decoded.offer(pcm, 100L, TimeUnit.MILLISECONDS)) return;
            bufferedBytes.addAndGet(-bytes);
        }
    }

    private void waitForStartupBuffer() throws InterruptedException {
        long deadline = System.currentTimeMillis() + STARTUP_WAIT_TIMEOUT_MILLIS;
        int targetBytes = bytesForMillis(STARTUP_BUFFER_MILLIS);
        while (!closed && !decoderEnded && bufferedBytes.get() < targetBytes
                && System.currentTimeMillis() < deadline) {
            TimeUnit.MILLISECONDS.sleep(10L);
        }
    }

    /**
     * Keeps buffering until the decoded queue can cover the engine's prefill <em>and</em> the
     * media time this stream has to catch up, which is exactly what the start trim about to run
     * will drop from the head. Bounded by the same timeout as the plain startup wait.
     */
    private void waitForCatchUpBuffer() throws InterruptedException {
        long deadline = System.currentTimeMillis() + STARTUP_WAIT_TIMEOUT_MILLIS;
        double prefillSeconds = STARTUP_BUFFER_MILLIS / 1000.0;
        while (!closed && !decoderEnded && System.currentTimeMillis() < deadline
                && bufferedSeconds() < prefillSeconds + currentCatchUp()) {
            TimeUnit.MILLISECONDS.sleep(10L);
        }
    }

    /** Media time between where the decoder opened and where the timeline is now. */
    private double currentCatchUp() {
        return Math.min(MAX_CATCH_UP_SECONDS,
                forwardDelta(wrap(startSeconds.getAsDouble(), duration), currentStart, duration));
    }

    /** Throws away already decoded PCM so the stream starts where the video is. */
    private double discardBufferedSeconds(double seconds) throws InterruptedException {
        int frameSize = Math.max(1, format.getFrameSize());
        int bytesPerSecond = bytesPerSecond();
        int remaining = Math.max(0, (int) Math.min(Integer.MAX_VALUE, seconds * bytesPerSecond));
        remaining -= remaining % frameSize;
        int discarded = 0;
        while (remaining > 0) {
            ByteBuffer next = decoded.poll();
            if (next != null) bufferedBytes.addAndGet(-next.remaining());
            if (next == null) break;
            int count = Math.min(remaining, next.remaining());
            count -= count % frameSize;
            next.position(next.position() + count);
            discarded += count;
            remaining -= count;
            if (next.hasRemaining()) {
                pending = next;
                break;
            }
        }
        return discarded / (double) bytesPerSecond;
    }

    private org.bytedeco.javacv.Frame grabSamples() throws Exception {
        org.bytedeco.javacv.Frame frame;
        while (!closed && (frame = grabber.grabSamples()) != null) {
            if (frame.samples != null && frame.samples.length > 0 && trimDiscardedSamples(frame)) {
                return frame;
            }
        }
        if (!closed) {
            // End of file: start over rather than going silent for the rest of the stream.
            try {
                grabber.setTimestamp(0L);
                while (!closed && (frame = grabber.grabSamples()) != null) {
                    if (frame.samples != null && frame.samples.length > 0 && trimDiscardedSamples(frame)) {
                        return frame;
                    }
                }
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private boolean trimDiscardedSamples(org.bytedeco.javacv.Frame frame) {
        if (!Double.isNaN(exactStartSeconds)) {
            int frameCount = sampleFrameCount(frame);
            double frameStart = frame.timestamp / 1_000_000.0;
            int skipped = (int) Math.min(frameCount, Math.max(0L,
                    Math.round((exactStartSeconds - frameStart) * format.getSampleRate())));
            skipSampleFrames(frame, skipped);
            if (skipped >= frameCount) return false;
            exactStartSeconds = Double.NaN;
        }
        if (discardFrames <= 0) return true;
        int frameCount = sampleFrameCount(frame);
        int skipped = (int) Math.min(discardFrames, frameCount);
        skipSampleFrames(frame, skipped);
        discardFrames -= skipped;
        return skipped < frameCount;
    }

    private int sampleFrameCount(org.bytedeco.javacv.Frame frame) {
        boolean planar = frame.samples.length >= format.getChannels();
        return planar ? frame.samples[0].remaining()
                : frame.samples[0].remaining() / Math.max(1, frame.audioChannels);
    }

    private void skipSampleFrames(org.bytedeco.javacv.Frame frame, int skipped) {
        boolean planar = frame.samples.length >= format.getChannels();
        for (Buffer samples : frame.samples) {
            int values = planar ? skipped : skipped * Math.max(1, frame.audioChannels);
            samples.position(Math.min(samples.limit(), samples.position() + values));
        }
    }

    private static ByteBuffer convert(org.bytedeco.javacv.Frame frame, int channels) {
        Buffer[] samples = frame.samples;
        boolean planar = samples.length >= channels;
        int frames = planar ? samples[0].remaining()
                : samples[0].remaining() / Math.max(1, frame.audioChannels);
        ByteBuffer pcm = ByteBuffer.allocate(Math.max(0, frames * channels * 2))
                .order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < frames; i++) {
            for (int c = 0; c < channels; c++) {
                Buffer buffer = planar ? samples[c] : samples[0];
                int index = planar ? buffer.position() + i
                        : buffer.position() + i * Math.max(1, frame.audioChannels) + c;
                pcm.putShort((short) Math.round(Math.max(-1, Math.min(1, sample(buffer, index))) * 32767));
            }
        }
        return pcm.flip();
    }

    private static double sample(Buffer buffer, int index) {
        if (buffer instanceof FloatBuffer value) return value.get(index);
        if (buffer instanceof DoubleBuffer value) return value.get(index);
        if (buffer instanceof ShortBuffer value) return value.get(index) / 32768.0;
        if (buffer instanceof IntBuffer value) return value.get(index) / 2147483648.0;
        if (buffer instanceof ByteBuffer value) return value.get(index) / 128.0;
        return 0;
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        ZCinemaLog.log("audio", "stream close start=%.3fs", startTime);
        decoderThread.interrupt();
        decoded.clear();
        bufferedBytes.set(0);
        closeResources();
    }

    private void closeResources() {
        if (!resourcesClosed.compareAndSet(false, true)) return;
        // Closing a grabber can block on the connection for a while; never do it on the sound
        // engine's thread.
        CLOSE_EXECUTOR.execute(() -> {
            try {
                grabber.close();
            } catch (Exception ignored) {
            }
            if (input != null) {
                try {
                    input.close();
                } catch (IOException ignored) {
                }
            }
        });
    }

    private static double wrap(double value, double duration) {
        if (duration <= 0) return Math.max(0, value);
        double wrapped = value % duration;
        return wrapped < 0 ? wrapped + duration : wrapped;
    }

    private static double forwardDelta(double value, double reference, double duration) {
        double delta = value - reference;
        if (duration > 0 && delta < 0) delta += duration;
        return Math.max(0, delta);
    }

    private int bytesForMillis(int millis) {
        int frameSize = Math.max(1, format.getFrameSize());
        float frameRate = format.getFrameRate() > 0 ? format.getFrameRate() : format.getSampleRate();
        int frames = Math.max(1, Math.round(frameRate * millis / 1000.0f));
        return frames * frameSize;
    }

    private int bytesPerSecond() {
        int frameSize = Math.max(1, format.getFrameSize());
        float frameRate = format.getFrameRate() > 0 ? format.getFrameRate() : format.getSampleRate();
        return Math.max(1, Math.round(frameRate) * frameSize);
    }

    /** Decoded PCM currently waiting for the engine, in media seconds. */
    private double bufferedSeconds() {
        return bufferedBytes.get() / (double) bytesPerSecond();
    }
}
