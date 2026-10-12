package net.talaatharb.stttts.cloning.chatterbox;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.io.InputStream;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

class ChatterboxTokenizerTest {
    private static ChatterboxTokenizer load() throws IOException {
        try (InputStream in = ChatterboxTokenizerTest.class.getResourceAsStream("tokenizer.json")) {
            return ChatterboxTokenizer.parse(new ObjectMapper().readTree(in));
        }
    }

    @Test
    void matchesHuggingFaceTokenizerOutput() throws IOException {
        // Expected ids produced by the Python "tokenizers" library for the same tokenizer.json.
        long[] expected = {6563, 255, 284, 18, 84, 28, 2, 172, 7, 2, 607, 2, 21, 69, 2, 127, 2, 74, 2, 51, 17, 88,
            13, 0, 6561, 6561};
        assertArrayEquals(expected, load().encodeForGeneration("Hello there, [laughter] how are you today?"));
    }

    @Test
    void normalizesPunctuation() {
        assertEquals("Hello world.", ChatterboxTokenizer.normalize("  hello   world "));
        assertEquals("Wait, what?", ChatterboxTokenizer.normalize("wait... what?"));
    }
}
