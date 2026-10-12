package net.talaatharb.stttts.audio;

/** Band-limited (windowed sinc) sample-rate conversion, good enough for speech models. */
public final class Resampler {
    private static final int HALF_TAPS = 16;

    private Resampler() {
    }

    public static AudioClip resample(AudioClip clip, int targetRate) {
        if (clip.sampleRate() == targetRate) {
            return clip;
        }
        float[] in = clip.samples();
        double ratio = (double) targetRate / clip.sampleRate();
        int outLength = (int) Math.floor(in.length * ratio);
        float[] out = new float[outLength];
        // When downsampling, lower the cutoff to avoid aliasing.
        double cutoff = Math.min(1.0, ratio);
        double halfWidth = HALF_TAPS / cutoff;
        for (int i = 0; i < outLength; i++) {
            double center = i / ratio;
            int first = Math.max(0, (int) Math.ceil(center - halfWidth));
            int last = Math.min(in.length - 1, (int) Math.floor(center + halfWidth));
            double sum = 0;
            double weightSum = 0;
            for (int j = first; j <= last; j++) {
                double x = j - center;
                double weight = sinc(cutoff * x) * hann(x / halfWidth);
                sum += in[j] * weight;
                weightSum += weight;
            }
            out[i] = (float) (weightSum == 0 ? 0 : sum / weightSum);
        }
        return new AudioClip(out, targetRate);
    }

    private static double sinc(double x) {
        if (Math.abs(x) < 1e-9) {
            return 1.0;
        }
        double px = Math.PI * x;
        return Math.sin(px) / px;
    }

    private static double hann(double normalized) {
        if (Math.abs(normalized) >= 1) {
            return 0;
        }
        return 0.5 * (1 + Math.cos(Math.PI * normalized));
    }
}
