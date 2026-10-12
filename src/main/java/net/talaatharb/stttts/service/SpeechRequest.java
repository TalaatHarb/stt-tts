package net.talaatharb.stttts.service;

import java.util.Objects;

/**
 * @param text     what to say
 * @param voiceId  one of {@link TextToSpeechService#voices()}
 * @param language ISO code; models that only speak one language ignore it
 * @param speed    1.0 is the model's natural pace
 */
public record SpeechRequest(String text, String voiceId, String language, float speed) {
    public SpeechRequest {
        Objects.requireNonNull(text, "text");
        if (text.isBlank()) {
            throw new IllegalArgumentException("Enter some text to speak");
        }
        if (speed <= 0) {
            throw new IllegalArgumentException("Speed must be positive");
        }
    }
}
