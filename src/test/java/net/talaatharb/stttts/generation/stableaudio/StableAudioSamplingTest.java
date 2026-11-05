package net.talaatharb.stttts.generation.stableaudio;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Random;

import org.junit.jupiter.api.Test;

class StableAudioSamplingTest {
    @Test
    void scheduleMatchesOfficialDistributionShift() {
        assertEquals(1, StableAudioSampling.sigma(0, 256));
        assertEquals(0, StableAudioSampling.sigma(8, 256));
        assertEquals(0.62245933f, StableAudioSampling.sigma(4, 256), 1e-7);
        assertEquals(0.7595109f, StableAudioSampling.sigma(4, 4096), 1e-7);
        float previous = 1;
        for (int i = 1; i <= 8; i++) {
            float current = StableAudioSampling.sigma(i, 100);
            assertTrue(current < previous);
            previous = current;
        }
    }

    @Test
    void finalStepDoesNotAddNoise() {
        float[] x = {2, -1};
        StableAudioSampling.pingPong(x, new float[] {4, 2}, 0.25f, 0, new Random(42));
        assertArrayEquals(new float[] {1, -1.5f}, x);
    }

    @Test
    void rejectsInvalidLatents() {
        assertThrows(IllegalArgumentException.class, () ->
                StableAudioSampling.pingPong(new float[2], new float[1], 1, 0, new Random()));
        assertThrows(IllegalStateException.class, () ->
                StableAudioSampling.pingPong(new float[] {Float.NaN}, new float[1], 1, 0, new Random()));
    }
}
