package net.talaatharb.stttts.tts.kokoro;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class EnglishG2PTest {
    private final EnglishG2P g2p = new EnglishG2P(Map.of("hello", "həlˈO", "world", "wˈɜɹld", "cat", "kˈæt",
            "walk", "wˈɔk", "apple", "ˈæpᵊl", "is", "ɪz", "Dr", "dˈɑktəɹ", "doctor", "dˈɑktəɹ", "smith", "smˈɪθ"));

    @Test
    void looksUpWordsAndKeepsPunctuation() {
        assertEquals("həlˈO, wˈɜɹld!", g2p.phonemize("Hello, world!"));
    }

    @Test
    void reducesArticlesDependingOnNextSound() {
        assertEquals("ðə kˈæt", g2p.phonemize("the cat"));
        assertEquals("ði ˈæpᵊl", g2p.phonemize("the apple"));
        assertEquals("ɐ kˈæt", g2p.phonemize("a cat"));
    }

    @Test
    void derivesInflections() {
        assertEquals("kˈæts", g2p.phonemize("cats"));
        assertEquals("wˈɔkt", g2p.phonemize("walked"));
        assertEquals("wˈɔkɪŋ", g2p.phonemize("walking"));
    }

    @Test
    void expandsAbbreviationsAndNumbers() {
        assertEquals("dˈɑktəɹ smˈɪθ", g2p.phonemize("Dr. Smith"));
        assertEquals("one thousand nine hundred eighty four", EnglishG2P.cardinal(1984));
        assertEquals("nineteen eighty four", EnglishG2P.numberToWords("1984"));
        assertEquals("one thousand two hundred thirty four", EnglishG2P.numberToWords("1,234"));
        assertEquals("three point one four", EnglishG2P.numberToWords("3.14"));
        assertEquals("twenty first", EnglishG2P.numberToWords("21st"));
    }

    @Test
    void fallsBackToLetterRulesForUnknownWords() {
        String phonemes = g2p.phonemize("zib");
        assertTrue(phonemes.contains("ˈ"));
        assertFalse(phonemes.isBlank());
        assertEquals("ˈɛnbˈiˈA", g2p.phonemize("NBA").replace(" ", ""));
    }

    @Test
    void splitsLongPhonemeStringsAtSpaces() {
        String word = "abcdefghi ";
        List<String> chunks = new ArrayList<>();
        KokoroTextToSpeechService.splitPhonemes(word.repeat(120), chunks);
        assertTrue(chunks.size() > 1);
        chunks.forEach(c -> assertTrue(c.length() <= KokoroTextToSpeechService.MAX_PHONEMES));
    }
}
