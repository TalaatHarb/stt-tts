package net.talaatharb.stttts.audio;

import java.util.Objects;

/** Mono PCM audio held as normalized floats in [-1, 1]. */
public record AudioClip(float[] samples, int sampleRate) {
    public AudioClip {
        Objects.requireNonNull(samples, "samples");
        if (sampleRate <= 0) {
            throw new IllegalArgumentException("Sample rate must be positive: " + sampleRate);
        }
    }

    public double durationSeconds() {
        return samples.length / (double) sampleRate;
    }

    public boolean isEmpty() {
        return samples.length == 0;
    }

    /** Returns this clip at the requested rate, resampling only when needed. */
    public AudioClip atSampleRate(int targetRate) {
        return targetRate == sampleRate ? this : Resampler.resample(this, targetRate);
    }

    /** Returns at most {@code seconds} of audio from the start of the clip. */
    public AudioClip truncate(double seconds) {
        int max = (int) Math.min(samples.length, Math.round(seconds * sampleRate));
        if (max == samples.length) {
            return this;
        }
        float[] copy = new float[max];
        System.arraycopy(samples, 0, copy, 0, max);
        return new AudioClip(copy, sampleRate);
    }

    /** Joins clips (resampled to {@code sampleRate}) with a pause between consecutive parts. */
    public static AudioClip concat(int sampleRate, double silenceSeconds, java.util.List<AudioClip> parts) {
        int silence = (int) Math.round(silenceSeconds * sampleRate);
        java.util.List<float[]> converted = new java.util.ArrayList<>();
        int total = 0;
        for (int i = 0; i < parts.size(); i++) {
            float[] part = parts.get(i).atSampleRate(sampleRate).samples();
            converted.add(part);
            total += part.length + (i > 0 ? silence : 0);
        }
        float[] out = new float[total];
        int offset = 0;
        for (int i = 0; i < converted.size(); i++) {
            if (i > 0) {
                offset += silence;
            }
            float[] part = converted.get(i);
            System.arraycopy(part, 0, out, offset, part.length);
            offset += part.length;
        }
        return new AudioClip(out, sampleRate);
    }
}
