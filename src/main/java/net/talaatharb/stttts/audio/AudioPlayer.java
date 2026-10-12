package net.talaatharb.stttts.audio;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;

/** Plays clips on the default output device, one at a time. */
public class AudioPlayer {
    private SourceDataLine line;

    public synchronized void play(AudioClip clip, Runnable onFinished) throws LineUnavailableException {
        stop();
        AudioFormat format = new AudioFormat(clip.sampleRate(), 16, 1, true, false);
        SourceDataLine candidate = AudioSystem.getSourceDataLine(format);
        candidate.open(format);
        candidate.start();
        line = candidate;
        byte[] pcm = WavIO.toPcm16(clip.samples());
        Thread worker = new Thread(() -> {
            int offset = 0;
            while (offset < pcm.length && candidate.isOpen()) {
                int written = candidate.write(pcm, offset, Math.min(8192, pcm.length - offset));
                if (written <= 0) {
                    break;
                }
                offset += written;
            }
            if (candidate.isOpen()) {
                candidate.drain();
                candidate.close();
            }
            synchronized (AudioPlayer.this) {
                if (line == candidate) {
                    line = null;
                }
            }
            if (onFinished != null) {
                onFinished.run();
            }
        }, "audio-player");
        worker.setDaemon(true);
        worker.start();
    }

    public synchronized boolean isPlaying() {
        return line != null;
    }

    public synchronized void stop() {
        if (line != null) {
            line.stop();
            line.flush();
            line.close();
            line = null;
        }
    }
}
