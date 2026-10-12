package net.talaatharb.stttts.tts.kokoro;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Pure Java English grapheme-to-phoneme conversion producing the phoneme alphabet used by Kokoro.
 *
 * <p>Words are looked up in the misaki "gold" and "silver" pronunciation dictionaries (the same data Kokoro's
 * official tokenizer uses). Inflections are derived from their stems, numbers are spelled out, and unknown words
 * fall back to dictionary sub-pieces or simple letter-to-sound rules. No espeak installation is required.
 */
public class EnglishG2P {
    private static final Pattern TOKEN = Pattern.compile(
            "(?<num>\\d+(?:[.,]\\d+)*(?:st|nd|rd|th)?)|(?<word>[A-Za-z]+(?:['\u2019][A-Za-z]+)*)|(?<punct>[;:,.!?\u2014\u2026\"()\u201c\u201d-])");
    private static final String VOWELS = "AIOQWYaeiouæɑɒɔəɚɛɜɪʊʌᵊᵻ";
    private static final Map<Character, String> LETTERS = new HashMap<>();
    private static final Map<String, String> ABBREVIATIONS = new HashMap<>();
    private static final String[] ONES = {"zero", "one", "two", "three", "four", "five", "six", "seven", "eight",
        "nine", "ten", "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen",
        "nineteen"};
    private static final String[] TENS = {"", "", "twenty", "thirty", "forty", "fifty", "sixty", "seventy",
        "eighty", "ninety"};
    private static final String[] SCALES = {"", "thousand", "million", "billion", "trillion"};
    /** Ordered letter-to-sound rules for words missing from every dictionary. */
    private static final String[][] RULES = {{"tion", "ʃən"}, {"sion", "ʒən"}, {"ough", "ʌf"}, {"igh", "I"},
        {"sch", "sk"}, {"tch", "ʧ"}, {"dge", "ʤ"}, {"ch", "ʧ"}, {"sh", "ʃ"}, {"th", "θ"}, {"ph", "f"},
        {"wh", "w"}, {"ck", "k"}, {"ng", "ŋ"}, {"qu", "kw"}, {"kn", "n"}, {"ee", "i"}, {"ea", "i"}, {"oo", "u"},
        {"ou", "W"}, {"ow", "O"}, {"oa", "O"}, {"ai", "A"}, {"ay", "A"}, {"oi", "Y"}, {"oy", "Y"}, {"au", "ɔ"},
        {"aw", "ɔ"}, {"ie", "i"}, {"ey", "i"}, {"er", "əɹ"}, {"ar", "ɑɹ"}, {"or", "ɔɹ"}, {"ir", "ɜɹ"},
        {"ur", "ɜɹ"}, {"a", "æ"}, {"b", "b"}, {"c", "k"}, {"d", "d"}, {"e", "ɛ"}, {"f", "f"}, {"g", "ɡ"},
        {"h", "h"}, {"i", "ɪ"}, {"j", "ʤ"}, {"k", "k"}, {"l", "l"}, {"m", "m"}, {"n", "n"}, {"o", "ɑ"},
        {"p", "p"}, {"q", "k"}, {"r", "ɹ"}, {"s", "s"}, {"t", "t"}, {"u", "ʌ"}, {"v", "v"}, {"w", "w"},
        {"x", "ks"}, {"y", "j"}, {"z", "z"}};

    static {
        String[] names = {"ˈA", "bˈi", "sˈi", "dˈi", "ˈi", "ˈɛf", "ʤˈi", "ˈAʧ", "ˈI", "ʤˈA", "kˈA", "ˈɛl", "ˈɛm",
            "ˈɛn", "ˈO", "pˈi", "kjˈu", "ˈɑɹ", "ˈɛs", "tˈi", "jˈu", "vˈi", "dˈʌbᵊlju", "ˈɛks", "wˈI", "zˈi"};
        for (int i = 0; i < names.length; i++) {
            LETTERS.put((char) ('a' + i), names[i]);
        }
        ABBREVIATIONS.put("mr", "mister");
        ABBREVIATIONS.put("mrs", "missus");
        ABBREVIATIONS.put("ms", "miz");
        ABBREVIATIONS.put("dr", "doctor");
        ABBREVIATIONS.put("st", "saint");
        ABBREVIATIONS.put("prof", "professor");
        ABBREVIATIONS.put("jr", "junior");
        ABBREVIATIONS.put("sr", "senior");
        ABBREVIATIONS.put("vs", "versus");
        ABBREVIATIONS.put("etc", "et cetera");
    }

    private final Map<String, String> dictionary;

    public EnglishG2P(Map<String, String> dictionary) {
        this.dictionary = dictionary;
    }

    /** Loads the misaki dictionaries; entries from earlier files win over later ones. */
    public static EnglishG2P load(Path... dictionaries) throws IOException {
        Map<String, String> merged = new HashMap<>(200_000);
        for (Path path : dictionaries) {
            try (InputStream in = Files.newInputStream(path)) {
                read(in, merged);
            }
        }
        return new EnglishG2P(merged);
    }

    static void read(InputStream in, Map<String, String> target) throws IOException {
        JsonNode root = new ObjectMapper().readTree(in);
        Iterator<Map.Entry<String, JsonNode>> fields = root.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            String value = pronunciation(entry.getValue());
            if (value != null) {
                target.putIfAbsent(entry.getKey(), value);
            }
        }
    }

    private static String pronunciation(JsonNode node) {
        if (node.isTextual()) {
            return node.asText();
        }
        if (node.isObject()) {
            JsonNode preferred = node.get("DEFAULT");
            if (preferred != null && preferred.isTextual()) {
                return preferred.asText();
            }
            for (JsonNode value : node) {
                if (value.isTextual()) {
                    return value.asText();
                }
            }
        }
        return null;
    }

    /** Converts English text to a Kokoro phoneme string. */
    public String phonemize(String text) {
        String normalized = text.replace('\u2019', '\'').replace('\u2018', '\'').replace("&", " and ")
                .replace("%", " percent").replace("@", " at ");
        normalized = normalized.replaceAll("\\$(\\d+(?:[.,]\\d+)*)", "$1 dollars");
        List<String> words = new ArrayList<>();
        Matcher matcher = TOKEN.matcher(normalized);
        while (matcher.find()) {
            if (matcher.group("num") != null) {
                for (String w : numberToWords(matcher.group("num")).split(" ")) {
                    words.add(w);
                }
            } else if (matcher.group("word") != null) {
                String word = matcher.group("word");
                String expansion = ABBREVIATIONS.get(word.toLowerCase(Locale.ROOT));
                boolean abbreviation = expansion != null && normalized.startsWith(".", matcher.end());
                if (abbreviation) {
                    for (String w : expansion.split(" ")) {
                        words.add(w);
                    }
                    matcher.region(matcher.end() + 1, normalized.length());
                } else {
                    words.add(word);
                }
            } else {
                String punct = matcher.group("punct");
                words.add("-".equals(punct) ? "\u2014" : punct.replace('"', '\u201c'));
            }
        }
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < words.size(); i++) {
            String word = words.get(i);
            if (isPunctuation(word)) {
                if ("\u2014".equals(word) && out.length() > 0) {
                    out.append(' ');
                }
                out.append(word);
                continue;
            }
            String phonemes = word(word, i + 1 < words.size() ? words.get(i + 1) : null);
            if (phonemes.isEmpty()) {
                continue;
            }
            if (out.length() > 0) {
                out.append(' ');
            }
            out.append(phonemes);
        }
        return out.toString().trim();
    }

    private static boolean isPunctuation(String token) {
        return token.length() == 1 && !Character.isLetterOrDigit(token.charAt(0));
    }

    String word(String word, String next) {
        String lower = word.toLowerCase(Locale.ROOT);
        if ("the".equals(lower)) {
            return next != null && startsWithVowelSound(next) ? "ði" : "ðə";
        }
        if ("a".equals(word)) {
            return "ɐ";
        }
        String found = lookup(word);
        if (found != null) {
            return found;
        }
        if (word.length() <= 4 && word.equals(word.toUpperCase(Locale.ROOT)) && word.length() > 1) {
            return spell(word);
        }
        found = inflected(lower);
        if (found != null) {
            return found;
        }
        return pieces(lower);
    }

    private boolean startsWithVowelSound(String next) {
        String phonemes = lookup(next);
        String probe = phonemes != null ? phonemes.replace("ˈ", "").replace("ˌ", "") : next.toLowerCase(Locale.ROOT);
        return !probe.isEmpty() && VOWELS.indexOf(probe.charAt(0)) >= 0;
    }

    String lookup(String word) {
        String value = dictionary.get(word);
        if (value == null) {
            value = dictionary.get(word.toLowerCase(Locale.ROOT));
        }
        if (value == null && !word.isEmpty()) {
            String lower = word.toLowerCase(Locale.ROOT);
            value = dictionary.get(Character.toUpperCase(lower.charAt(0)) + lower.substring(1));
        }
        return value;
    }

    private String inflected(String word) {
        if (word.endsWith("'s") || word.endsWith("s'")) {
            String stem = lookupOrNull(word.substring(0, word.length() - 2));
            if (stem != null) {
                return pluralize(stem);
            }
        }
        if (word.endsWith("s") && word.length() > 2) {
            String stem = firstKnown(word.substring(0, word.length() - 1),
                    word.endsWith("es") ? word.substring(0, word.length() - 2) : null,
                    word.endsWith("ies") ? word.substring(0, word.length() - 3) + "y" : null);
            if (stem != null) {
                return pluralize(stem);
            }
        }
        if (word.endsWith("ed") && word.length() > 3) {
            String base = word.substring(0, word.length() - 2);
            String stem = firstKnown(base, word.substring(0, word.length() - 1), undouble(base),
                    word.endsWith("ied") ? word.substring(0, word.length() - 3) + "y" : null);
            if (stem != null) {
                return pastTense(stem);
            }
        }
        if (word.endsWith("ing") && word.length() > 4) {
            String base = word.substring(0, word.length() - 3);
            String stem = firstKnown(base, base + "e", undouble(base));
            if (stem != null) {
                return stem + "ɪŋ";
            }
        }
        if (word.endsWith("ly") && word.length() > 4) {
            String stem = lookupOrNull(word.substring(0, word.length() - 2));
            if (stem != null) {
                return stem + "li";
            }
        }
        return null;
    }

    private static String undouble(String base) {
        int n = base.length();
        return n > 2 && base.charAt(n - 1) == base.charAt(n - 2) ? base.substring(0, n - 1) : null;
    }

    private String lookupOrNull(String word) {
        return word == null || word.isEmpty() ? null : lookup(word);
    }

    private String firstKnown(String... candidates) {
        for (String candidate : candidates) {
            String value = lookupOrNull(candidate);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    static String pluralize(String stem) {
        char last = stem.charAt(stem.length() - 1);
        if ("szʃʒʧʤ".indexOf(last) >= 0) {
            return stem + "ᵻz";
        }
        if ("ptkfθ".indexOf(last) >= 0) {
            return stem + "s";
        }
        return stem + "z";
    }

    static String pastTense(String stem) {
        char last = stem.charAt(stem.length() - 1);
        if (last == 't' || last == 'd') {
            return stem + "ᵻd";
        }
        if ("pkfθʃsʧ".indexOf(last) >= 0) {
            return stem + "t";
        }
        return stem + "d";
    }

    private static String spell(String word) {
        StringBuilder out = new StringBuilder();
        for (char c : word.toLowerCase(Locale.ROOT).toCharArray()) {
            String name = LETTERS.get(c);
            if (name != null) {
                out.append(name);
            }
        }
        return out.toString();
    }

    /** Covers an unknown word with the longest known dictionary pieces, falling back to letter rules. */
    private String pieces(String word) {
        StringBuilder out = new StringBuilder();
        StringBuilder unknown = new StringBuilder();
        int i = 0;
        while (i < word.length()) {
            int match = -1;
            String phonemes = null;
            for (int end = word.length(); end >= i + 3; end--) {
                String candidate = dictionary.get(word.substring(i, end));
                if (candidate != null) {
                    match = end;
                    phonemes = candidate;
                    break;
                }
            }
            if (match < 0) {
                unknown.append(word.charAt(i++));
                continue;
            }
            out.append(rules(unknown.toString()));
            unknown.setLength(0);
            out.append(out.length() > 0 ? phonemes.replace("ˈ", "ˌ") : phonemes);
            i = match;
        }
        out.append(rules(unknown.toString()));
        String result = out.toString();
        if (!result.contains("ˈ")) {
            result = stressFirstVowel(result.replace("ˌ", ""));
        }
        return result;
    }

    static String rules(String letters) {
        StringBuilder out = new StringBuilder();
        String text = letters.toLowerCase(Locale.ROOT);
        int i = 0;
        while (i < text.length()) {
            if (text.charAt(i) == 'e' && i == text.length() - 1 && i > 0) {
                i++;
                continue;
            }
            boolean matched = false;
            for (String[] rule : RULES) {
                if (text.startsWith(rule[0], i)) {
                    out.append(rule[1]);
                    i += rule[0].length();
                    matched = true;
                    break;
                }
            }
            if (!matched) {
                i++;
            }
        }
        return out.toString();
    }

    private static String stressFirstVowel(String phonemes) {
        for (int i = 0; i < phonemes.length(); i++) {
            if (VOWELS.indexOf(phonemes.charAt(i)) >= 0) {
                return phonemes.substring(0, i) + "ˈ" + phonemes.substring(i);
            }
        }
        return phonemes;
    }

    /** Spells out integers, decimals, comma grouped numbers and ordinals (1st, 22nd). */
    static String numberToWords(String token) {
        String suffix = "";
        String number = token;
        if (token.matches(".*\\d(st|nd|rd|th)$")) {
            suffix = token.substring(token.length() - 2);
            number = token.substring(0, token.length() - 2);
        }
        String integer = number;
        String fraction = null;
        boolean grouped = false;
        if (number.matches("\\d{1,3}(,\\d{3})+(\\.\\d+)?")) {
            integer = number.replace(",", "");
            grouped = true;
        }
        int dot = integer.indexOf('.');
        if (dot >= 0) {
            fraction = integer.substring(dot + 1);
            integer = integer.substring(0, dot);
        }
        if (integer.contains(",")) {
            StringBuilder parts = new StringBuilder();
            for (String part : integer.split(",")) {
                parts.append(parts.length() > 0 ? " " : "").append(numberToWords(part));
            }
            return parts.toString();
        }
        String words;
        if (integer.length() > 15) {
            StringBuilder digits = new StringBuilder();
            for (char c : integer.toCharArray()) {
                digits.append(digits.length() > 0 ? " " : "").append(ONES[c - '0']);
            }
            words = digits.toString();
        } else if (integer.length() == 4 && !grouped && suffix.isEmpty() && fraction == null && isYear(integer)) {
            words = year(Integer.parseInt(integer));
        } else {
            words = cardinal(Long.parseLong(integer));
        }
        if (fraction != null && !fraction.isEmpty()) {
            StringBuilder decimals = new StringBuilder(words).append(" point");
            for (char c : fraction.toCharArray()) {
                decimals.append(' ').append(ONES[c - '0']);
            }
            words = decimals.toString();
        }
        return suffix.isEmpty() ? words : ordinal(words);
    }

    private static boolean isYear(String digits) {
        int value = Integer.parseInt(digits);
        return value >= 1100 && value <= 2099 && (value < 2000 || value >= 2010) && value % 100 != 0;
    }

    private static String year(int value) {
        int high = value / 100;
        int low = value % 100;
        return cardinal(high) + " " + (low < 10 ? "oh " + ONES[low] : cardinal(low));
    }

    static String cardinal(long value) {
        if (value < 20) {
            return ONES[(int) value];
        }
        List<String> groups = new ArrayList<>();
        int scale = 0;
        while (value > 0) {
            int group = (int) (value % 1000);
            if (group > 0) {
                String text = hundreds(group);
                groups.add(0, scale > 0 ? text + " " + SCALES[scale] : text);
            }
            value /= 1000;
            scale++;
        }
        return String.join(" ", groups);
    }

    private static String hundreds(int value) {
        StringBuilder out = new StringBuilder();
        if (value >= 100) {
            out.append(ONES[value / 100]).append(" hundred");
            value %= 100;
            if (value > 0) {
                out.append(' ');
            }
        }
        if (value >= 20) {
            out.append(TENS[value / 10]);
            if (value % 10 > 0) {
                out.append(' ').append(ONES[value % 10]);
            }
        } else if (value > 0) {
            out.append(ONES[value]);
        }
        return out.toString();
    }

    private static String ordinal(String words) {
        int space = words.lastIndexOf(' ');
        String head = space < 0 ? "" : words.substring(0, space + 1);
        String last = words.substring(space + 1);
        String converted = switch (last) {
            case "one" -> "first";
            case "two" -> "second";
            case "three" -> "third";
            case "five" -> "fifth";
            case "eight" -> "eighth";
            case "nine" -> "ninth";
            case "twelve" -> "twelfth";
            default -> last.endsWith("y") ? last.substring(0, last.length() - 1) + "ieth" : last + "th";
        };
        return head + converted;
    }
}
