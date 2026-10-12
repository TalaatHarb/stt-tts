package net.talaatharb.stttts.service;

import java.time.Duration;
import java.util.List;

/** Result of a transcription: the text, the detected language and optional word timings. */
public record Transcription(String text, String language, List<WordTiming> words, Duration elapsed) {
    public Transcription {
        words = words == null ? List.of() : List.copyOf(words);
    }

    /** A recognized word and when it was spoken, in seconds from the start of the clip. */
    public record WordTiming(String word, double start, double end, double probability) {
    }
}
