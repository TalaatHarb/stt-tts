package net.talaatharb.stttts.audio;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.TargetDataLine;

/** Captures mono 16-bit audio from the default microphone until {@link #stop()} is called. */
public class AudioRecorder {
    private static final float[] RATES = {48000f, 44100f, 16000f};

    private TargetDataLine line;
    private Thread worker;
    private ByteArrayOutputStream buffer;
    private int sampleRate;
    private volatile double level;

    public synchronized boolean isRecording() {
        return line != null;
    }

    /** Most recent peak level in [0, 1], for a simple level meter. */
    public double level() {
        return level;
    }

    public synchronized void start() throws LineUnavailableException {
        if (line != null) {
            throw new IllegalStateException("Already recording");
        }
        LineUnavailableException failure = null;
        for (float rate : RATES) {
            AudioFormat format = new AudioFormat(rate, 16, 1, true, false);
            DataLine.Info info = new DataLine.Info(TargetDataLine.class, format);
            if (!AudioSystem.isLineSupported(info)) {
                continue;
            }
            try {
                TargetDataLine candidate = (TargetDataLine) AudioSystem.getLine(info);
                candidate.open(format);
                candidate.start();
                line = candidate;
                sampleRate = (int) rate;
                break;
            } catch (LineUnavailableException e) {
                failure = e;
            }
        }
        if (line == null) {
            throw failure != null ? failure : new LineUnavailableException("No microphone is available");
        }
        buffer = new ByteArrayOutputStream();
        TargetDataLine active = line;
        ByteArrayOutputStream sink = buffer;
        worker = new Thread(() -> capture(active, sink), "audio-recorder");
        worker.setDaemon(true);
        worker.start();
    }

    private void capture(TargetDataLine active, ByteArrayOutputStream sink) {
        byte[] chunk = new byte[4096];
        while (active.isOpen()) {
            int read = active.read(chunk, 0, chunk.length);
            if (read <= 0) {
                if (!active.isActive()) {
                    break;
                }
                continue;
            }
            synchronized (sink) {
                sink.write(chunk, 0, read);
            }
            ByteBuffer samples = ByteBuffer.wrap(chunk, 0, read).order(ByteOrder.LITTLE_ENDIAN);
            int peak = 0;
            while (samples.remaining() >= 2) {
                peak = Math.max(peak, Math.abs((int) samples.getShort()));
            }
            level = peak / 32768.0;
        }
    }

    public synchronized AudioClip stop() {
        if (line == null) {
            throw new IllegalStateException("Not recording");
        }
        line.stop();
        line.close();
        try {
            worker.join(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        byte[] bytes;
        synchronized (buffer) {
            bytes = buffer.toByteArray();
        }
        line = null;
        worker = null;
        buffer = null;
        level = 0;
        ByteBuffer pcm = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        float[] samples = new float[bytes.length / 2];
        for (int i = 0; i < samples.length; i++) {
            samples[i] = pcm.getShort() / 32768f;
        }
        return new AudioClip(samples, sampleRate);
    }
}
