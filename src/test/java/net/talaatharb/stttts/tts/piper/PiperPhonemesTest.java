package net.talaatharb.stttts.tts.piper;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;

import org.junit.jupiter.api.Test;

class PiperPhonemesTest {
    @Test
    void expandsMisakiDiphthongsAndAffricates() {
        assertEquals("bɹˈaʊn", PiperPhonemes.fromMisaki("bɹˈWn", false));
        assertEquals("dʒˈʌmps", PiperPhonemes.fromMisaki("ʤˈʌmps", false));
        assertEquals("lˈeɪzi", PiperPhonemes.fromMisaki("lˈAzi", false).replace("ː", ""));
        assertEquals("tʃˈɔɪs", PiperPhonemes.fromMisaki("ʧˈYs", false));
    }

    @Test
    void writesAmericanRhoticVowelsLikeEspeak() {
        assertEquals("ˌoʊvɚ", PiperPhonemes.fromMisaki("ˌOvəɹ", false));
        assertEquals("bˈɜːd", PiperPhonemes.fromMisaki("bˈɜɹd", false));
        assertEquals("vˈɛɹiː", PiperPhonemes.fromMisaki("vˈɛɹi", false));
        assertEquals("əɹˈaʊnd", PiperPhonemes.fromMisaki("əɹˈWnd", false));
    }

    @Test
    void marksLongVowels() {
        assertEquals("dˈɑːɡ", PiperPhonemes.fromMisaki("dˈɑɡ", false));
        assertEquals("fˈuːd", PiperPhonemes.fromMisaki("fˈud", false));
        assertEquals("fˈɑːðə", PiperPhonemes.fromMisaki("fˈɑːðə", true));
    }

    @Test
    void buildsPaddedIdSequence() {
        Map<String, Long> ids = Map.of("_", 0L, "^", 1L, "$", 2L, " ", 3L, "a", 14L, "b", 15L);
        assertArrayEquals(new long[] {1, 0, 15, 0, 3, 0, 14, 0, 2}, PiperPhonemes.toIds("b ?a", ids));
    }
}
