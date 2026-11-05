package net.talaatharb.stttts.generation.musicgen;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Random;

import org.junit.jupiter.api.Test;

class MusicGenSamplingTest {
    @Test
    void convertsDurationToFramesAndAccountsForCodebookDelay() {
        assertEquals(10, MusicGenSampling.frameCount(0.2));
        assertEquals(13, MusicGenSampling.generatedTokenCount(10));
        assertEquals(503, MusicGenSampling.generatedTokenCount(500));
    }

    @Test
    void appliesClassifierFreeGuidanceToEachCodebookRow() {
        float[] logits = new float[2 * MusicGenSampling.CODEBOOKS * 2];
        for (int codebook = 0; codebook < MusicGenSampling.CODEBOOKS; codebook++) {
            logits[codebook * 2] = 2;
            logits[codebook * 2 + 1] = 4;
            logits[(MusicGenSampling.CODEBOOKS + codebook) * 2] = 1;
            logits[(MusicGenSampling.CODEBOOKS + codebook) * 2 + 1] = 3;
        }

        float[][] guided = MusicGenSampling.guidedLogits(logits, 2, 3);

        for (float[] row : guided) {
            assertArrayEquals(new float[] {4, 6}, row, 0);
        }
    }

    @Test
    void topKSamplerIsSeededAndNeverSelectsOutsideTheTopK() {
        float[] logits = {1, 5, 3, 4};

        int first = MusicGenSampling.sampleTopK(logits, 2, 1, new Random(7));
        int second = MusicGenSampling.sampleTopK(logits, 2, 1, new Random(7));

        assertEquals(first, second);
        org.junit.jupiter.api.Assertions.assertTrue(first == 1 || first == 3);
    }

    @Test
    void restoresTheDelayedCodebookPatternToFrameMajorOutput() {
        int[][] generated = new int[6][MusicGenSampling.CODEBOOKS];
        for (int step = 0; step < generated.length; step++) {
            for (int codebook = 0; codebook < MusicGenSampling.CODEBOOKS; codebook++) {
                generated[step][codebook] = step * 10 + codebook;
            }
        }

        assertArrayEquals(new long[] {0, 10, 20, 11, 21, 31, 22, 32, 42, 33, 43, 53},
                MusicGenSampling.delayedCodes(generated, 3));
    }

    @Test
    void rejectsIncompleteDelayedSequences() {
        assertThrows(IllegalArgumentException.class,
                () -> MusicGenSampling.delayedCodes(new int[2][MusicGenSampling.CODEBOOKS], 1));
    }
}
