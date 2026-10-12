package net.talaatharb.stttts.tts.supertonic;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** Text normalization and chunking used by Supertonic, ported from the official reference implementation. */
public final class SupertonicText {
    private static final Map<String, String> REPLACEMENTS = new LinkedHashMap<>();
    private static final String[] ABBREVIATIONS = {"Dr.", "Mr.", "Mrs.", "Ms.", "Prof.", "Sr.", "Jr.", "St.", "Ave.",
        "Rd.", "Blvd.", "Dept.", "Inc.", "Ltd.", "Co.", "Corp.", "etc.", "vs.", "i.e.", "e.g.", "Ph.D."};
    private static final Pattern SENTENCE_SPLIT;
    private static final Pattern ENDS_WITH_PUNCTUATION =
            Pattern.compile(".*[.!?;:,'\"\u201C\u201D\u2018\u2019)\\]}\u2026\u3002\u300D\u300F\u3011\u3009\u300B\u203A\u00BB]$",
                    Pattern.DOTALL);

    static {
        REPLACEMENTS.put("\u2013", "-");
        REPLACEMENTS.put("\u2011", "-");
        REPLACEMENTS.put("\u2014", "-");
        REPLACEMENTS.put("_", " ");
        REPLACEMENTS.put("\u201C", "\"");
        REPLACEMENTS.put("\u201D", "\"");
        REPLACEMENTS.put("\u2018", "'");
        REPLACEMENTS.put("\u2019", "'");
        REPLACEMENTS.put("\u00B4", "'");
        REPLACEMENTS.put("`", "'");
        REPLACEMENTS.put("[", " ");
        REPLACEMENTS.put("]", " ");
        REPLACEMENTS.put("|", " ");
        REPLACEMENTS.put("/", " ");
        REPLACEMENTS.put("#", " ");
        REPLACEMENTS.put("\u2192", " ");
        REPLACEMENTS.put("\u2190", " ");
        REPLACEMENTS.put("@", " at ");
        REPLACEMENTS.put("e.g.,", "for example, ");
        REPLACEMENTS.put("i.e.,", "that is, ");
        StringBuilder abbreviations = new StringBuilder();
        for (String abbreviation : ABBREVIATIONS) {
            if (abbreviations.length() > 0) {
                abbreviations.append('|');
            }
            abbreviations.append(Pattern.quote(abbreviation));
        }
        SENTENCE_SPLIT = Pattern.compile("(?<!(?:" + abbreviations + "))(?<=[.!?])\\s+");
    }

    private SupertonicText() {
    }

    /** Normalizes the text and wraps it in the language tags the model expects. */
    public static String preprocess(String text, String language) {
        String result = Normalizer.normalize(text, Normalizer.Form.NFKD);
        result = removeEmojis(result);
        for (Map.Entry<String, String> entry : REPLACEMENTS.entrySet()) {
            result = result.replace(entry.getKey(), entry.getValue());
        }
        result = result.replaceAll("[\u2665\u2606\u2661\u00A9\\\\]", "");
        result = result.replaceAll(" ([,.!?;:'])", "$1");
        result = result.replaceAll("\"{2,}", "\"").replaceAll("'{2,}", "'");
        result = result.replaceAll("\\s+", " ").trim();
        if (!ENDS_WITH_PUNCTUATION.matcher(result).matches()) {
            result += ".";
        }
        return "<" + language + ">" + result + "</" + language + ">";
    }

    private static String removeEmojis(String text) {
        StringBuilder out = new StringBuilder(text.length());
        text.codePoints().filter(cp -> !isEmoji(cp)).forEach(out::appendCodePoint);
        return out.toString();
    }

    private static boolean isEmoji(int cp) {
        return (cp >= 0x1F300 && cp <= 0x1FAFF) || (cp >= 0x2600 && cp <= 0x27BF) || (cp >= 0x1F1E6 && cp <= 0x1F1FF);
    }

    /** Splits long input into paragraph/sentence chunks no longer than {@code maxLength} characters. */
    public static List<String> chunk(String text, int maxLength) {
        List<String> chunks = new ArrayList<>();
        for (String paragraph : text.trim().split("\\n\\s*\\n")) {
            paragraph = paragraph.trim();
            if (paragraph.isEmpty()) {
                continue;
            }
            if (paragraph.length() <= maxLength) {
                chunks.add(paragraph);
                continue;
            }
            StringBuilder current = new StringBuilder();
            for (String sentence : SENTENCE_SPLIT.split(paragraph)) {
                sentence = sentence.trim();
                if (sentence.isEmpty()) {
                    continue;
                }
                if (sentence.length() > maxLength) {
                    flush(chunks, current);
                    splitLong(sentence, maxLength, chunks);
                    continue;
                }
                if (current.length() + sentence.length() + 1 > maxLength) {
                    flush(chunks, current);
                }
                if (current.length() > 0) {
                    current.append(' ');
                }
                current.append(sentence);
            }
            flush(chunks, current);
        }
        return chunks;
    }

    private static void splitLong(String sentence, int maxLength, List<String> chunks) {
        StringBuilder current = new StringBuilder();
        for (String word : sentence.split("\\s+")) {
            if (current.length() + word.length() + 1 > maxLength) {
                flush(chunks, current);
            }
            if (current.length() > 0) {
                current.append(' ');
            }
            current.append(word);
        }
        flush(chunks, current);
    }

    private static void flush(List<String> chunks, StringBuilder current) {
        if (current.length() > 0) {
            chunks.add(current.toString().trim());
            current.setLength(0);
        }
    }
}
