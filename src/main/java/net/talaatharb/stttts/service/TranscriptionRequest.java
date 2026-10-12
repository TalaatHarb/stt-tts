package net.talaatharb.stttts.service;

/**
 * @param language       ISO code to force, or null / {@code auto} to detect it
 * @param wordTimestamps whether to return per-word timing
 */
public record TranscriptionRequest(String language, boolean wordTimestamps) {
    public static final TranscriptionRequest DEFAULT = new TranscriptionRequest(null, false);

    public boolean autoDetect() {
        return language == null || language.isBlank() || Language.AUTO.code().equals(language);
    }
}
