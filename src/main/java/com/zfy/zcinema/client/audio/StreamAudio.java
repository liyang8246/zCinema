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
    private static final ExecutorService CLOSE_EXECUTOR = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "ZCinema Audio Close");
        thread.setDaemon(true);
        return thread;
    });

    private final org.bytedeco.javacv.FFmpegFrameGrabber grabber;
    private final AudioFormat format;
    private final double duration;
    private final double startTime;
    private final InputStream input;
    private final ArrayBlockingQueue<ByteBuffer> decoded = new ArrayBlockingQueue<>(MAX_BUFFERED_PCM_CHUNKS);
    private final AtomicInteger bufferedBytes = new AtomicInteger();
    private final AtomicBoolean resourcesClosed = new AtomicBoolean();
    private final Thread decoderThread;

    private ByteBuffer pending = ByteBuffer.allocate(0);
    private volatile boolean closed;
    private volatile boolean decoderEnded;
    private long discardFrames;
    private double exactStartSeconds = Double.NaN;

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
            double currentSeconds = Math.max(0.0, startSeconds.getAsDouble());
            double currentStart = duration > 0.0 ? wrap(currentSeconds, duration) : currentSeconds;
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
            waitForStartupBuffer();
            // Everything decoded above sits at currentStart, but the media clock kept running while
            // we buffered: throw away that much PCM so the first sample the engine plays is where
            // the picture is by now (Create Cinema's catch-up).
            double catchUpSeconds = forwardDelta(wrap(startSeconds.getAsDouble(), duration), currentStart, duration);
            startTime = wrap(currentStart + discardBufferedSeconds(catchUpSeconds), duration);
            ZCinemaLog.log("audio", "stream ready url=%s start=%.3fs duration=%.3fs %dHz x%d took=%dms",
                    ZCinemaLog.shorten(url, 200), startTime, duration, format.getSampleRate(),
                    format.getChannels(), System.currentTimeMillis() - openedAt);
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

    /** Position in the media this stream actually starts sounding at. */
    public double startTime() {
        return startTime;
    }

    public boolean decoderEnded() {
        return decoderEnded;
    }

    @Override
    public ByteBuffer read(int requestedBytes) throws IOException {
        return readPcm(requestedBytes, 0, true);
    }

    private ByteBuffer readPcm(int requestedBytes, int waitMillis, boolean padSilence) throws IOException {
        ByteBuffer output = ByteBuffer.allocateDirect(
                Math.max(1, Math.min(requestedBytes, bytesForMillis(MAX_READ_MILLIS))))
                .order(ByteOrder.LITTLE_ENDIAN);
        try {
            while (output.hasRemaining() && !closed) {
                if (pending.hasRemaining()) {
                    int count = Math.min(output.remaining(), pending.remaining());
                    int limit = pending.limit();
                    pending.limit(pending.position() + count);
                    output.put(pending);
                    pending.limit(limit);
                    continue;
                }
                ByteBuffer next = decoded.poll();
                if (next == null) break;
                bufferedBytes.addAndGet(-next.remaining());
                pending = next;
            }
            if (padSilence && !closed) {
                while (output.hasRemaining()) output.put((byte) 0);
            }
            return output.flip();
        } catch (Throwable error) {
            if (error instanceof IOException io) throw io;
            throw new IOException("Failed to read screen audio", error);
        }
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

    /** Throws away already decoded PCM so the stream starts where the video is. */
    private double discardBufferedSeconds(double seconds) throws InterruptedException {
        int frameSize = Math.max(1, format.getFrameSize());
        int bytesPerSecond = Math.max(frameSize, Math.round(format.getFrameRate() * frameSize));
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
}
