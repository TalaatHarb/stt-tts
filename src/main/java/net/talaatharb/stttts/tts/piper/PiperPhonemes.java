package net.talaatharb.stttts.tts.piper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Converts the misaki phonemes produced by {@link net.talaatharb.stttts.tts.kokoro.EnglishG2P} into the espeak-ng
 * style IPA Piper voices were trained on, and then into Piper's phoneme ids. This avoids a native espeak-ng install.
 */
public final class PiperPhonemes {
    private static final String VOWELS = "aeiouæɑɒɔəɚɛɜɪʊʌᵻAIOQWYᵊ";
    private static final Map<Character, String> EXPANSIONS = Map.ofEntries(
            Map.entry('A', "eɪ"), Map.entry('I', "aɪ"), Map.entry('O', "oʊ"), Map.entry('Q', "əʊ"),
            Map.entry('W', "aʊ"), Map.entry('Y', "ɔɪ"), Map.entry('ʤ', "dʒ"), Map.entry('ʧ', "tʃ"),
            Map.entry('ᵊ', "ə"), Map.entry('“', "\""), Map.entry('”', "\""), Map.entry('—', ","),
            Map.entry('…', "."));
    /** Vowels espeak always writes long in English (misaki leaves the length implicit). */
    private static final String LONG_VOWELS = "iuɑɔ";

    private PiperPhonemes() {
    }

    /** Rewrites misaki phonemes as espeak IPA. */
    public static String fromMisaki(String misaki, boolean british) {
        StringBuilder out = new StringBuilder();
        int length = misaki.length();
        for (int i = 0; i < length; i++) {
            char c = misaki.charAt(i);
            char next = i + 1 < length ? misaki.charAt(i + 1) : ' ';
            char afterNext = i + 2 < length ? misaki.charAt(i + 2) : ' ';
            if (!british && (c == 'ə' || c == 'ɜ') && next == 'ɹ' && !isVowel(afterNext) && afterNext != 'ˈ'
                    && afterNext != 'ˌ') {
                out.append(c == 'ə' ? "ɚ" : "ɜː");
                i++;
                continue;
            }
            String expansion = EXPANSIONS.get(c);
            if (expansion != null) {
                out.append(expansion);
            } else {
                out.append(c);
                if ((LONG_VOWELS.indexOf(c) >= 0 || (british && c == 'ɜ')) && next != 'ː') {
                    out.append('ː');
                }
            }
        }
        return out.toString();
    }

    private static boolean isVowel(char c) {
        return VOWELS.indexOf(c) >= 0;
    }

    /** Builds Piper's id sequence: BOS, then every phoneme followed by the pad symbol, then EOS. */
    public static long[] toIds(String phonemes, Map<String, Long> idMap) {
        List<Long> ids = new ArrayList<>();
        Long pad = idMap.get("_");
        add(ids, idMap.get("^"), pad);
        phonemes.codePoints().forEach(codePoint -> {
            Long id = idMap.get(new String(Character.toChars(codePoint)));
            if (id != null) {
                add(ids, id, pad);
            }
        });
        if (idMap.containsKey("$")) {
            ids.add(idMap.get("$"));
        }
        return ids.stream().mapToLong(Long::longValue).toArray();
    }

    private static void add(List<Long> ids, Long id, Long pad) {
        if (id != null) {
            ids.add(id);
            if (pad != null) {
                ids.add(pad);
            }
        }
    }
}
