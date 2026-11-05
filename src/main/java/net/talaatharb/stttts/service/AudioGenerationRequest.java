package net.talaatharb.stttts.service;

/** A reproducible prompt-to-audio generation request. */
public record AudioGenerationRequest(String prompt, double durationSeconds, long seed) {
    public AudioGenerationRequest {
        if (prompt == null || prompt.isBlank()) {
            throw new IllegalArgumentException("An audio prompt is required");
        }
        if (!Double.isFinite(durationSeconds) || durationSeconds <= 0) {
            throw new IllegalArgumentException("Duration must be positive and finite");
        }
    }
}
