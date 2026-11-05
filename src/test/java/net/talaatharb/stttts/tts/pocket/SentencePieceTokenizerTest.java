package net.talaatharb.stttts.tts.pocket;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.io.InputStream;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Reference ids were produced with the Python {@code sentencepiece} package for Pocket TTS's tokenizer. */
class SentencePieceTokenizerTest {
    private static SentencePieceTokenizer tokenizer;

    @BeforeAll
    static void load() throws IOException {
        try (InputStream in = SentencePieceTokenizerTest.class.getResourceAsStream("tokenizer.model")) {
            tokenizer = SentencePieceTokenizer.parse(in.readAllBytes());
        }
    }

    @Test
    void readsVocabulary() {
        assertEquals(4000, tokenizer.vocabularySize());
        assertEquals("<s>", tokenizer.piece(1));
    }

    @Test
    void encodesLikeSentencePiece() {
        assertArrayEquals(new int[] {2994, 262, 285, 277, 267, 1115, 263}, tokenizer.encode("Hello, this is a test."));
        assertArrayEquals(new int[] {364, 976, 3683, 521, 1923, 1609, 261, 408, 265, 697, 690, 327, 1497, 263},
                tokenizer.encode("The quick brown fox jumps over the lazy dog."));
        assertArrayEquals(new int[] {260, 263, 682, 799, 292}, tokenizer.encode(".!...?"));
        assertArrayEquals(new int[] {260, 262, 1230, 3244}, tokenizer.encode(",;:"));
        assertArrayEquals(new int[] {260, 260, 689, 483, 660, 260, 947}, tokenizer.encode("  Double  space"));
    }

    @Test
    void fallsBackToBytes() {
        assertArrayEquals(new int[] {1130, 601, 745, 913, 199, 179, 314, 670, 745, 261, 766, 745, 260, 341, 365, 450,
            260, 230, 134, 176, 437, 682}, tokenizer.encode("Caf\u00e9 na\u00efve r\u00e9sum\u00e9 123 \u20ac5!"));
    }

    @Test
    void decodesBackToText() {
        String text = "Caf\u00e9 na\u00efve r\u00e9sum\u00e9 123 \u20ac5!";
        assertEquals(text, tokenizer.decode(tokenizer.encode(text)));
        assertEquals("Hello, this is a test.", tokenizer.decode(tokenizer.encode("Hello, this is a test.")));
    }
}
