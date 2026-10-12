package net.talaatharb.stttts.cloning.chatterbox;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Pure Java implementation of Chatterbox's Hugging Face BPE tokenizer (whole-string BPE plus added tokens). */
public class ChatterboxTokenizer {
    public static final long START_SPEECH = 6561;
    public static final long STOP_SPEECH = 6562;
    static final String EXAGGERATION = "[EXAGGERATION]";

    private final Map<String, Long> vocab;
    private final Map<String, Integer> mergeRanks;
    /** Added tokens keyed by content, longest first so that matching is greedy. */
    private final TreeMap<String, Long> addedTokens = new TreeMap<>((a, b) -> a.length() != b.length()
            ? Integer.compare(b.length(), a.length()) : a.compareTo(b));
    private final long unknown;

    public ChatterboxTokenizer(Map<String, Long> vocab, List<String> merges, Map<String, Long> added) {
        this.vocab = vocab;
        this.mergeRanks = new HashMap<>();
        for (int i = 0; i < merges.size(); i++) {
            mergeRanks.putIfAbsent(merges.get(i), i);
        }
        addedTokens.putAll(added);
        this.unknown = vocab.getOrDefault("[UNK]", 1L);
    }

    public static ChatterboxTokenizer load(Path file) throws IOException {
        return parse(new ObjectMapper().readTree(file.toFile()));
    }

    static ChatterboxTokenizer parse(JsonNode root) {
        JsonNode model = root.path("model");
        Map<String, Long> vocab = new HashMap<>();
        Iterator<Map.Entry<String, JsonNode>> fields = model.path("vocab").fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            vocab.put(entry.getKey(), entry.getValue().asLong());
        }
        List<String> merges = new ArrayList<>();
        for (JsonNode merge : model.path("merges")) {
            merges.add(merge.isArray() ? merge.get(0).asText() + " " + merge.get(1).asText() : merge.asText());
        }
        Map<String, Long> added = new HashMap<>();
        for (JsonNode token : root.path("added_tokens")) {
            added.put(token.get("content").asText(), token.get("id").asLong());
        }
        return new ChatterboxTokenizer(vocab, merges, added);
    }

    /** Applies the same light punctuation clean-up as the reference Chatterbox implementation. */
    public static String normalize(String text) {
        String cleaned = text.replaceAll("\\s+", " ").trim();
        if (cleaned.isEmpty()) {
            return "You need to add some text for me to talk.";
        }
        cleaned = Character.toUpperCase(cleaned.charAt(0)) + cleaned.substring(1);
        String[][] replacements = {{"...", ", "}, {"\u2026", ", "}, {":", ","}, {" - ", ", "}, {";", ", "},
            {"\u2014", "-"}, {"\u2013", "-"}, {" ,", ","}, {"\u201c", "\""}, {"\u201d", "\""}, {"\u2018", "'"},
            {"\u2019", "'"}};
        for (String[] replacement : replacements) {
            cleaned = cleaned.replace(replacement[0], replacement[1]);
        }
        cleaned = cleaned.replaceAll("\\s+", " ").trim();
        if (!cleaned.matches(".*[.!?\\-,]$")) {
            cleaned += ".";
        }
        return cleaned;
    }

    /** Encodes text with the template {@code [EXAGGERATION] [START] text [STOP] [START_SPEECH] [START_SPEECH]}. */
    public long[] encodeForGeneration(String text) {
        List<Long> ids = new ArrayList<>();
        ids.add(addedTokens.getOrDefault(EXAGGERATION, 6563L));
        ids.add(addedTokens.getOrDefault("[START]", 255L));
        ids.addAll(encode(text.replaceAll("\\s+", " ")));
        ids.add(addedTokens.getOrDefault("[STOP]", 0L));
        ids.add(START_SPEECH);
        ids.add(START_SPEECH);
        return ids.stream().mapToLong(Long::longValue).toArray();
    }

    /** Encodes text without the template, splitting out added tokens such as {@code [laughter]} first. */
    public List<Long> encode(String text) {
        List<Long> ids = new ArrayList<>();
        StringBuilder segment = new StringBuilder();
        int i = 0;
        outer:
        while (i < text.length()) {
            if (text.charAt(i) == '[') {
                for (Map.Entry<String, Long> token : addedTokens.entrySet()) {
                    if (text.startsWith(token.getKey(), i)) {
                        bpe(segment.toString(), ids);
                        segment.setLength(0);
                        ids.add(token.getValue());
                        i += token.getKey().length();
                        continue outer;
                    }
                }
            }
            segment.append(text.charAt(i++));
        }
        bpe(segment.toString(), ids);
        return ids;
    }

    private void bpe(String word, List<Long> out) {
        if (word.isEmpty()) {
            return;
        }
        List<String> symbols = new ArrayList<>();
        word.codePoints().forEach(cp -> symbols.add(new String(Character.toChars(cp))));
        while (symbols.size() > 1) {
            int best = -1;
            int bestRank = Integer.MAX_VALUE;
            for (int j = 0; j < symbols.size() - 1; j++) {
                Integer rank = mergeRanks.get(symbols.get(j) + " " + symbols.get(j + 1));
                if (rank != null && rank < bestRank) {
                    bestRank = rank;
                    best = j;
                }
            }
            if (best < 0) {
                break;
            }
            symbols.set(best, symbols.get(best) + symbols.remove(best + 1));
        }
        for (String symbol : symbols) {
            out.add(vocab.getOrDefault(symbol, unknown));
        }
    }
}
