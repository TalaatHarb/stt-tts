package net.talaatharb.stttts.audio;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;

import org.junit.jupiter.api.Test;

class WavIOTest {
    @Test
    void rejectsMalformedHeadersAndChunksBeforeDecoding() {
        assertThrows(IOException.class, () -> WavIO.parseWav(new byte[10]));
        assertThrows(IOException.class, () -> WavIO.parseWav(new byte[44]));
        byte[] wav = WavIO.toWavBytes(new AudioClip(new float[] {0.1f}, 16000));
        byte[] shortFmt = wav.clone();
        shortFmt[16] = 1;
        assertThrows(IOException.class, () -> WavIO.parseWav(shortFmt));
        byte[] oversized = wav.clone();
        oversized[16] = (byte) 0xFF;
        oversized[17] = (byte) 0xFF;
        oversized[18] = (byte) 0xFF;
        oversized[19] = (byte) 0xFF;
        assertThrows(IOException.class, () -> WavIO.parseWav(oversized));
        byte[] badBits = wav.clone();
        badBits[34] = 7;
        assertThrows(IOException.class, () -> WavIO.parseWav(badBits));
        assertThrows(IOException.class, () -> WavIO.parseWav(java.util.Arrays.copyOf(wav, wav.length - 1)));
    }

    @Test
    void roundTripsPcm16() throws IOException {
        float[] samples = new float[1600];
        for (int i = 0; i < samples.length; i++) {
            samples[i] = (float) (0.5 * Math.sin(2 * Math.PI * 440 * i / 16000.0));
        }
        AudioClip clip = WavIO.parseWav(WavIO.toWavBytes(new AudioClip(samples, 16000)));
        assertEquals(16000, clip.sampleRate());
        assertEquals(samples.length, clip.samples().length);
        for (int i = 0; i < samples.length; i++) {
            assertEquals(samples[i], clip.samples()[i], 1e-3);
        }
    }

    @Test
    void resamplesToTargetRateKeepingDuration() {
        float[] samples = new float[48000];
        for (int i = 0; i < samples.length; i++) {
            samples[i] = (float) Math.sin(2 * Math.PI * 200 * i / 48000.0);
        }
        AudioClip resampled = new AudioClip(samples, 48000).atSampleRate(16000);
        assertEquals(16000, resampled.sampleRate());
        assertEquals(1.0, resampled.durationSeconds(), 0.01);
        double expected = Math.sin(2 * Math.PI * 200 * 4000 / 16000.0);
        assertEquals(expected, resampled.samples()[4000], 0.05);
    }

    @Test
    void concatenatesWithSilence() {
        AudioClip a = new AudioClip(new float[] {1, 1}, 10);
        AudioClip b = new AudioClip(new float[] {2}, 10);
        AudioClip joined = AudioClip.concat(10, 0.2, java.util.List.of(a, b));
        assertArrayEquals(new float[] {1, 1, 0, 0, 2}, joined.samples());
    }
}
