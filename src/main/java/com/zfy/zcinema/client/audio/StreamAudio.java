package com.zfy.zcinema.client.audio;

import com.zfy.zcinema.ZCinema;
import net.minecraft.client.sounds.AudioStream;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;

import javax.sound.sampled.AudioFormat;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Decodes the audio track of the same direct MP4 link into PCM for the sound engine.
 * One instance per screen; a fresh one is opened whenever the sound has to restart (seek, pause,
 * stall recovery), always starting at the current shared position. Opening failures are retried
 * by the controller, so a source that serialises connections cannot permanently mute a screen.
 */
final class StreamAudio implements AudioStream {
    private static final int MAX_BUFFERED_CHUNKS = 96;
    private static final int MAX_BUFFERED_MILLIS = 4_000;
    private static final int MAX_READ_MILLIS = 250;

    private final String url;
    private final double startSeconds;
    private final AudioFormat format;
    private final ArrayBlockingQueue<ByteBuffer> decoded = new ArrayBlockingQueue<>(MAX_BUFFERED_CHUNKS);
    private final AtomicInteger bufferedBytes = new AtomicInteger();
    private final Thread decoder;
    private final FFmpegFrameGrabber grabber;

    private ByteBuffer pending = ByteBuffer.allocate(0);
    private volatile boolean closed;

    StreamAudio(String url, double startSeconds) throws IOException {
        this.url = url;
        this.startSeconds = Math.max(0.0, startSeconds);
        FFmpegFrameGrabber opened = null;
        try {
            opened = new FFmpegFrameGrabber(url);
            opened.setVideoStream(-1);
            opened.start();
            // Seek after start(): JavaCV clears the pending timestamp inside start(), so a seek
            // set before it would be dropped and the audio would decode from the beginning.
            if (this.startSeconds > 0.0) {
                try {
                    opened.setTimestamp((long) (this.startSeconds * 1_000_000.0));
                } catch (Exception error) {
                    ZCinema.LOGGER.debug("Audio seek to {}s failed, decoding from the start", this.startSeconds, error);
                }
            }
            int sampleRate = opened.getSampleRate() > 0 ? opened.getSampleRate() : 44_100;
            int channels = Math.max(1, Math.min(2, opened.getAudioChannels() > 0 ? opened.getAudioChannels() : 2));
            format = new AudioFormat(sampleRate, 16, channels, true, false);
            grabber = opened;
            decoder = new Thread(this::decodeAudio, "ZCinema Audio Decode");
            decoder.setDaemon(true);
            decoder.start();
            ZCinema.LOGGER.debug("Screen audio {} opened at {}s ({} Hz {}ch)", url, this.startSeconds, sampleRate, channels);
        } catch (Throwable error) {
            if (opened != null) {
                try {
                    opened.close();
                } catch (Exception ignored) {
                }
            }
            throw error instanceof IOException io ? io : new IOException("Failed to open screen audio", error);
        }
    }

    @Override
    public AudioFormat getFormat() {
        return format;
    }

    @Override
    public ByteBuffer read(int requestedBytes) throws IOException {
        int size = Math.max(1, Math.min(requestedBytes, bytesForMillis(MAX_READ_MILLIS)));
        ByteBuffer output = ByteBuffer.allocateDirect(size).order(ByteOrder.LITTLE_ENDIAN);
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
            if (!closed) while (output.hasRemaining()) output.put((byte) 0);
            return output.flip();
        } catch (Throwable error) {
            if (error instanceof IOException io) throw io;
            throw new IOException("Failed to read screen audio", error);
        }
    }

    private void decodeAudio() {
        try {
            // A seek lands on the keyframe before the target, so samples before the exact start
            // position have to be dropped - otherwise the sound runs up to a GOP length early.
            boolean skippingPreRoll = startSeconds > 0.05;
            while (!closed) {
                Frame frame = grabber.grabSamples();
                if (frame == null) break;
                if (skippingPreRoll) {
                    double pts = frame.timestamp / 1_000_000.0;
                    if (pts >= 0.0 && pts < startSeconds - 0.02) {
                        continue;
                    }
                    skippingPreRoll = false;
                }
                ByteBuffer pcm = convert(frame, format.getChannels());
                if (pcm == null || !pcm.hasRemaining()) continue;
                byte[] copy = new byte[pcm.remaining()];
                pcm.get(copy);
                ByteBuffer chunk = ByteBuffer.wrap(copy).order(ByteOrder.LITTLE_ENDIAN);
                while (!closed && bufferedBytes.get() >= bytesForMillis(MAX_BUFFERED_MILLIS)) {
                    Thread.sleep(10L);
                }
                if (closed) break;
                decoded.put(chunk);
                bufferedBytes.addAndGet(copy.length);
            }
        } catch (Throwable error) {
            if (!closed) {
                ZCinema.LOGGER.debug("Screen audio decoder for {} stopped at {}s", url, startSeconds, error);
            }
        }
    }

    /** Interleaves JavaCV sample buffers into 16-bit little endian PCM. */
    private static ByteBuffer convert(Frame frame, int channels) {
        if (frame.samples == null || frame.samples.length == 0) return null;
        if (channels <= 1) {
            Object sample = frame.samples[0];
            if (!(sample instanceof ShortBuffer buffer)) return null;
            ByteBuffer out = ByteBuffer.allocate(buffer.remaining() * 2).order(ByteOrder.LITTLE_ENDIAN);
            out.asShortBuffer().put(buffer);
            return out;
        }
        // JavaCV usually hands back one interleaved buffer for stereo sources.
        if (frame.samples.length == 1 && frame.samples[0] instanceof ShortBuffer interleaved) {
            ByteBuffer out = ByteBuffer.allocate(interleaved.remaining() * 2).order(ByteOrder.LITTLE_ENDIAN);
            out.asShortBuffer().put(interleaved);
            return out;
        }
        ShortBuffer[] buffers = new ShortBuffer[Math.min(frame.samples.length, channels)];
        int frames = Integer.MAX_VALUE;
        for (int i = 0; i < buffers.length; i++) {
            if (!(frame.samples[i] instanceof ShortBuffer buffer)) return null;
            buffers[i] = buffer;
            frames = Math.min(frames, buffer.remaining());
        }
        ByteBuffer out = ByteBuffer.allocate(Math.max(0, frames) * channels * 2).order(ByteOrder.LITTLE_ENDIAN);
        ShortBuffer shorts = out.asShortBuffer();
        for (int i = 0; i < frames; i++) {
            for (ShortBuffer buffer : buffers) shorts.put(buffer.get());
        }
        return out;
    }

    private int bytesForMillis(int millis) {
        int frameSize = Math.max(1, format.getFrameSize());
        float rate = format.getFrameRate() > 0 ? format.getFrameRate() : format.getSampleRate();
        return Math.max(frameSize, Math.round(rate * millis / 1_000.0f) * frameSize);
    }

    @Override
    public void close() throws IOException {
        if (closed) return;
        closed = true;
        ZCinema.LOGGER.debug("Screen audio stream of {} closed at {}s", url, startSeconds);
        try {
            grabber.close();
        } catch (Throwable ignored) {
        }
    }
}
