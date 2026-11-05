package net.talaatharb.stttts.generation.stableaudio;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;

/** The BPE tokenizer bundled with the official Stable Audio 3 T5Gemma ONNX encoder. */
final class GemmaTokenizer {
    private final Map<String, Integer> vocabulary = new HashMap<>();
    private final Map<Long, Merge> merges = new HashMap<>();
    private final List<String> added = new ArrayList<>();

    GemmaTokenizer(Path file) throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        boolean normalizerSupported = false;
        String modelType = "";
        // Avoid retaining a second, hundreds-of-MB JSON tree while native models are loading.
        try (JsonParser parser = mapper.createParser(file.toFile())) {
            if (parser.nextToken() != JsonToken.START_OBJECT) {
                throw new IOException("Invalid Stable Audio tokenizer");
            }
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                String field = parser.currentName();
                parser.nextToken();
                if (field.equals("normalizer")) {
                    JsonNode normalizer = mapper.readTree(parser);
                    normalizerSupported = "Replace".equals(normalizer.path("type").asText())
                            && " ".equals(normalizer.path("pattern").path("String").asText())
                            && "\u2581".equals(normalizer.path("content").asText());
                } else if (field.equals("added_tokens")) {
                    while (parser.nextToken() != JsonToken.END_ARRAY) {
                        JsonNode token = mapper.readTree(parser);
                        added.add(token.path("content").asText());
                    }
                } else if (field.equals("model")) {
                    while (parser.nextToken() != JsonToken.END_OBJECT) {
                        String modelField = parser.currentName();
                        parser.nextToken();
                        switch (modelField) {
                            case "type" -> modelType = parser.getText();
                            case "vocab" -> {
                                while (parser.nextToken() != JsonToken.END_OBJECT) {
                                    String symbol = parser.currentName();
                                    parser.nextToken();
                                    vocabulary.put(symbol, parser.getIntValue());
                                }
                            }
                            case "merges" -> readMerges(parser);
                            default -> parser.skipChildren();
                        }
                    }
                } else {
                    parser.skipChildren();
                }
            }
        }
        if (!normalizerSupported || !"BPE".equals(modelType)) {
            throw new IOException("Unsupported Stable Audio tokenizer format");
        }
        added.sort(Comparator.comparingInt(String::length).reversed());
    }

    private void readMerges(JsonParser parser) throws IOException {
        int rank = 0;
        while (parser.nextToken() != JsonToken.END_ARRAY) {
            if (parser.currentToken() != JsonToken.START_ARRAY) {
                throw new IOException("Unsupported BPE merge format");
            }
            parser.nextToken();
            String left = parser.getText();
            parser.nextToken();
            String right = parser.getText();
            if (parser.nextToken() != JsonToken.END_ARRAY) {
                throw new IOException("Invalid BPE merge pair");
            }
            Integer a = vocabulary.get(left);
            Integer b = vocabulary.get(right);
            Integer merged = vocabulary.get(left + right);
            if (a == null || b == null || merged == null) {
                throw new IOException("Invalid BPE merge");
            }
            merges.put(key(a, b), new Merge(rank++, merged));
        }
    }

    long[] encode(String text) {
        List<Integer> output = new ArrayList<>();
        StringBuilder ordinary = new StringBuilder();
        for (int offset = 0; offset < text.length();) {
            String match = null;
            for (String token : added) {
                if (!token.isEmpty() && text.startsWith(token, offset)) {
                    match = token;
                    break;
                }
            }
            if (match != null) {
                encodeOrdinary(ordinary.toString(), output);
                ordinary.setLength(0);
                output.add(vocabulary.get(match));
                offset += match.length();
            } else {
                int cp = text.codePointAt(offset);
                ordinary.appendCodePoint(cp);
                offset += Character.charCount(cp);
            }
        }
        encodeOrdinary(ordinary.toString(), output);
        // The published tokenizer has no BOS/EOS post-processing.
        return output.stream().mapToLong(Integer::longValue).toArray();
    }

    private void encodeOrdinary(String text, List<Integer> output) {
        List<Integer> ids = new ArrayList<>();
        text.replace(' ', '\u2581').codePoints().forEach(cp -> {
            String symbol = new String(Character.toChars(cp));
            Integer id = vocabulary.get(symbol);
            if (id != null) {
                ids.add(id);
            } else {
                for (byte b : symbol.getBytes(StandardCharsets.UTF_8)) {
                    Integer fallback = vocabulary.get(String.format("<0x%02X>", b & 255));
                    if (fallback == null) {
                        throw new IllegalArgumentException("Tokenizer cannot encode character " + symbol);
                    }
                    ids.add(fallback);
                }
            }
        });
        while (ids.size() > 1) {
            int bestIndex = -1;
            Merge best = null;
            for (int i = 0; i < ids.size() - 1; i++) {
                Merge candidate = merges.get(key(ids.get(i), ids.get(i + 1)));
                if (candidate != null && (best == null || candidate.rank() < best.rank())) {
                    best = candidate;
                    bestIndex = i;
                }
            }
            if (best == null) {
                break;
            }
            ids.set(bestIndex, best.id());
            ids.remove(bestIndex + 1);
        }
        output.addAll(ids);
    }

    private static long key(int left, int right) {
        return ((long) left << 32) | (right & 0xffffffffL);
    }

    private record Merge(int rank, int id) {
    }
}
