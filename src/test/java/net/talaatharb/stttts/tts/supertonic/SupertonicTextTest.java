package net.talaatharb.stttts.tts.supertonic;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class SupertonicTextTest {
    @Test
    void wrapsTextInLanguageTags() {
        String processed = SupertonicText.preprocess("Hello world", "en");
        assertTrue(processed.startsWith("<en>") && processed.endsWith("</en>"), processed);
    }

    @Test
    void chunksRespectMaximumLength() {
        String text = "This is a sentence. ".repeat(50);
        List<String> chunks = SupertonicText.chunk(text, 100);
        assertTrue(chunks.size() > 1);
        chunks.forEach(c -> assertTrue(c.length() <= 100, c));
    }
}
