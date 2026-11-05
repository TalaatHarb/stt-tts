package net.talaatharb.stttts.generation.stableaudio;

import java.util.Random;

final class StableAudioSampling {
    static final int SAMPLE_RATE = 44100;
    static final int LATENT_STRIDE = 4096;
    static final int CHANNELS = 256;
    static final int STEPS = 8;

    private StableAudioSampling() {
    }

    /** DistributionShift from the official optimized runtime, preserving both endpoints. */
    static float sigma(int step, int length) {
        if (step == 0) {
            return 1;
        }
        if (step == STEPS) {
            return 0;
        }
        double t = 1 - step / (double) STEPS;
        double clamped = Math.max(256, Math.min(4096, length));
        double mu = -(0.5 + 0.65 * (clamped - 256) / (4096 - 256));
        double e = Math.exp(mu);
        return (float) (1 - e / (e + 1 / (1 - t) - 1));
    }

    static void pingPong(float[] x, float[] velocity, float current, float next, Random random) {
        if (x.length != velocity.length) {
            throw new IllegalArgumentException("Velocity shape differs from latent shape");
        }
        for (int i = 0; i < x.length; i++) {
            float denoised = x[i] - current * velocity[i];
            x[i] = (1 - next) * denoised + (next == 0 ? 0 : next * (float) random.nextGaussian());
            if (!Float.isFinite(x[i])) {
                throw new IllegalStateException("Stable Audio produced non-finite latents");
            }
        }
    }

}
