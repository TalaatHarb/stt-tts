package net.talaatharb.stttts.service;

import java.util.Objects;

import net.talaatharb.stttts.audio.AudioClip;

/**
 * @param text         what the cloned voice should say
 * @param reference    a clean recording of the target speaker
 * @param exaggeration emotion intensity, 0.5 is neutral (ignored by models without emotion control)
 * @param language     ISO code of the text; single language models ignore it, null means the model's default
 */
public record VoiceCloneRequest(String text, AudioClip reference, float exaggeration, String language) {
    public VoiceCloneRequest(String text, AudioClip reference, float exaggeration) {
        this(text, reference, exaggeration, null);
    }

    public VoiceCloneRequest {
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(reference, "reference");
        if (text.isBlank()) {
            throw new IllegalArgumentException("Enter some text to speak");
        }
        if (reference.durationSeconds() < 1) {
            throw new IllegalArgumentException("The reference recording must be at least one second long");
        }
    }
}
