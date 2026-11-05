package net.talaatharb.stttts.generation.musicgen;

import java.util.Arrays;
import java.util.Random;

/** Generation helpers for MusicGen's delayed multi-codebook sequence. */
final class MusicGenSampling {
    static final int CODEBOOKS = 4;
    static final int AUDIO_SAMPLE_RATE = 32_000;
    static final int FRAME_RATE = 50;
    static final int TOP_K = 50;
    static final float GUIDANCE_SCALE = 3.0f;
    static final float TEMPERATURE = 1.0f;

    private MusicGenSampling() {
    }

    static int frameCount(double seconds) {
        return (int) Math.ceil(seconds * FRAME_RATE);
    }

    static int generatedTokenCount(int frames) {
        return frames + CODEBOOKS - 1;
    }

    static float[][] guidedLogits(float[] logits, int vocabularySize, float guidanceScale) {
        float[][] guided = new float[CODEBOOKS][vocabularySize];
        for (int codebook = 0; codebook < CODEBOOKS; codebook++) {
            int conditionalOffset = codebook * vocabularySize;
            int unconditionalOffset = (CODEBOOKS + codebook) * vocabularySize;
            for (int token = 0; token < vocabularySize; token++) {
                float conditional = logits[conditionalOffset + token];
                float unconditional = logits[unconditionalOffset + token];
                guided[codebook][token] = unconditional + guidanceScale * (conditional - unconditional);
            }
        }
        return guided;
    }

    static int sampleTopK(float[] logits, int topK, float temperature, Random random) {
        if (logits.length == 0 || topK <= 0 || !Float.isFinite(temperature) || temperature <= 0) {
            throw new IllegalArgumentException("Invalid sampling parameters");
        }
        int count = Math.min(topK, logits.length);
        int[] best = new int[count];
        Arrays.fill(best, -1);
        for (int token = 0; token < logits.length; token++) {
            float value = logits[token];
            if (!Float.isFinite(value)) {
                continue;
            }
            int insert = count;
            while (insert > 0 && (best[insert - 1] < 0 || value > logits[best[insert - 1]])) {
                insert--;
            }
            if (insert < count) {
                for (int i = count - 1; i > insert; i--) {
                    best[i] = best[i - 1];
                }
                best[insert] = token;
            }
        }
        int valid = 0;
        while (valid < count && best[valid] >= 0) {
            valid++;
        }
        if (valid == 0) {
            throw new IllegalStateException("MusicGen returned no finite token logits");
        }
        double maximum = logits[best[0]] / temperature;
        double total = 0;
        double[] weights = new double[valid];
        for (int i = 0; i < valid; i++) {
            weights[i] = Math.exp(logits[best[i]] / temperature - maximum);
            total += weights[i];
        }
        double selected = random.nextDouble() * total;
        for (int i = 0; i < valid; i++) {
            selected -= weights[i];
            if (selected <= 0) {
                return best[i];
            }
        }
        return best[valid - 1];
    }

    static long[] delayedCodes(int[][] generated, int frameCount) {
        if (generated.length < frameCount + CODEBOOKS - 1) {
            throw new IllegalArgumentException("Not enough delayed tokens to form " + frameCount + " frames");
        }
        long[] codes = new long[CODEBOOKS * frameCount];
        for (int codebook = 0; codebook < CODEBOOKS; codebook++) {
            for (int frame = 0; frame < frameCount; frame++) {
                codes[codebook * frameCount + frame] = generated[frame + codebook][codebook];
            }
        }
        return codes;
    }
}
