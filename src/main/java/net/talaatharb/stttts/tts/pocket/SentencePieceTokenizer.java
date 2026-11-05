package net.talaatharb.stttts.tts.pocket;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal pure Java SentencePiece tokenizer: reads the {@code tokenizer.model} protobuf directly and implements
 * unigram (Viterbi) encoding with byte fallback, which is what Pocket TTS uses.
 */
public final class SentencePieceTokenizer {
    static final char SPACE = '\u2581';
    private static final int NORMAL = 1;
    private static final int UNKNOWN = 2;
    private static final int CONTROL = 3;
    private static final int USER_DEFINED = 4;
    private static final int BYTE = 6;
    private static final float UNKNOWN_PENALTY = 10;

    private final String[] pieces;
    private final float[] scores;
    private final int[] types;
    private final Map<String, Integer> index = new HashMap<>();
    private final int[] byteIds = new int[256];
    private final int unknownId;
    private final float unknownScore;
    private final int maxPieceLength;
    private final boolean addDummyPrefix;
    private final boolean removeExtraWhitespace;
    private final boolean nfkc;

    private SentencePieceTokenizer(List<String> pieces, List<Float> scores, List<Integer> types,
            boolean addDummyPrefix, boolean removeExtraWhitespace, boolean nfkc) {
        int size = pieces.size();
        this.pieces = pieces.toArray(String[]::new);
        this.scores = new float[size];
        this.types = new int[size];
        Arrays.fill(byteIds, -1);
        int unk = 0;
        float minScore = 0;
        int maxLength = 1;
        for (int i = 0; i < size; i++) {
            this.scores[i] = scores.get(i);
            this.types[i] = types.get(i);
            String piece = this.pieces[i];
            switch (this.types[i]) {
                case UNKNOWN -> unk = i;
                case BYTE -> byteIds[Integer.parseInt(piece.substring(3, 5), 16)] = i;
                case NORMAL, USER_DEFINED -> {
                    index.putIfAbsent(piece, i);
                    maxLength = Math.max(maxLength, piece.length());
                    minScore = Math.min(minScore, this.scores[i]);
                }
                default -> {
                    // Control and unused pieces never match text.
                }
            }
        }
        this.unknownId = unk;
        this.unknownScore = minScore - UNKNOWN_PENALTY;
        this.maxPieceLength = maxLength;
        this.addDummyPrefix = addDummyPrefix;
        this.removeExtraWhitespace = removeExtraWhitespace;
        this.nfkc = nfkc;
    }

    public static SentencePieceTokenizer load(Path model) throws IOException {
        return parse(Files.readAllBytes(model));
    }

    static SentencePieceTokenizer parse(byte[] data) throws IOException {
        List<String> pieces = new ArrayList<>();
        List<Float> scores = new ArrayList<>();
        List<Integer> types = new ArrayList<>();
        boolean addDummyPrefix = true;
        boolean removeExtraWhitespace = true;
        boolean nfkc = false;
        Proto proto = new Proto(data, 0, data.length);
        while (proto.hasMore()) {
            int tag = proto.varint32();
            int field = tag >>> 3;
            if (field == 1 && (tag & 7) == 2) {
                Proto piece = proto.message();
                String text = "";
                float score = 0;
                int type = NORMAL;
                while (piece.hasMore()) {
                    int pieceTag = piece.varint32();
                    switch (pieceTag) {
                        case (1 << 3) | 2 -> text = piece.string();
                        case (2 << 3) | 5 -> score = piece.float32();
                        case 3 << 3 -> type = piece.varint32();
                        default -> piece.skip(pieceTag);
                    }
                }
                pieces.add(text);
                scores.add(score);
                types.add(type);
            } else if (field == 3 && (tag & 7) == 2) {
                Proto spec = proto.message();
                while (spec.hasMore()) {
                    int specTag = spec.varint32();
                    switch (specTag) {
                        case (1 << 3) | 2 -> nfkc = spec.string().toLowerCase(java.util.Locale.ROOT).contains("nfkc");
                        case 3 << 3 -> addDummyPrefix = spec.varint32() != 0;
                        case 4 << 3 -> removeExtraWhitespace = spec.varint32() != 0;
                        default -> spec.skip(specTag);
                    }
                }
            } else {
                proto.skip(tag);
            }
        }
        if (pieces.isEmpty()) {
            throw new IOException("Not a SentencePiece model");
        }
        return new SentencePieceTokenizer(pieces, scores, types, addDummyPrefix, removeExtraWhitespace, nfkc);
    }

    public int vocabularySize() {
        return pieces.length;
    }

    public String piece(int id) {
        return pieces[id];
    }

    String normalize(String text) {
        String normalized = nfkc ? Normalizer.normalize(text, Normalizer.Form.NFKC) : text;
        if (removeExtraWhitespace) {
            normalized = normalized.strip().replaceAll(" {2,}", " ");
        }
        if (normalized.isEmpty()) {
            return normalized;
        }
        normalized = normalized.replace(' ', SPACE);
        return addDummyPrefix ? SPACE + normalized : normalized;
    }

    /** Encodes text into token ids (no BOS/EOS), matching {@code SentencePieceProcessor.Encode}. */
    public int[] encode(String text) {
        String normalized = normalize(text);
        int[] codePoints = normalized.codePoints().toArray();
        int length = codePoints.length;
        if (length == 0) {
            return new int[0];
        }
        int[] offsets = new int[length + 1];
        for (int i = 0; i < length; i++) {
            offsets[i + 1] = offsets[i] + Character.charCount(codePoints[i]);
        }
        float[] best = new float[length + 1];
        int[] backPointer = new int[length + 1];
        int[] backId = new int[length + 1];
        Arrays.fill(best, Float.NEGATIVE_INFINITY);
        best[0] = 0;
        for (int start = 0; start < length; start++) {
            if (best[start] == Float.NEGATIVE_INFINITY) {
                continue;
            }
            boolean singleCharacterFound = false;
            for (int end = start + 1; end <= length && offsets[end] - offsets[start] <= maxPieceLength; end++) {
                Integer id = index.get(normalized.substring(offsets[start], offsets[end]));
                if (id == null) {
                    continue;
                }
                if (end == start + 1) {
                    singleCharacterFound = true;
                }
                float score = best[start] + (types[id] == USER_DEFINED ? 0 : scores[id]);
                if (score > best[end]) {
                    best[end] = score;
                    backPointer[end] = start;
                    backId[end] = id;
                }
            }
            if (!singleCharacterFound && best[start] + unknownScore > best[start + 1]) {
                best[start + 1] = best[start] + unknownScore;
                backPointer[start + 1] = start;
                backId[start + 1] = -1;
            }
        }
        List<int[]> segments = new ArrayList<>();
        for (int end = length; end > 0; end = backPointer[end]) {
            segments.add(new int[] {backPointer[end], end, backId[end]});
        }
        List<Integer> ids = new ArrayList<>();
        for (int i = segments.size() - 1; i >= 0; i--) {
            int[] segment = segments.get(i);
            if (segment[2] >= 0) {
                ids.add(segment[2]);
                continue;
            }
            byte[] utf8 = normalized.substring(offsets[segment[0]], offsets[segment[1]])
                    .getBytes(StandardCharsets.UTF_8);
            boolean fallback = true;
            for (byte b : utf8) {
                fallback &= byteIds[b & 0xff] >= 0;
            }
            if (fallback) {
                for (byte b : utf8) {
                    ids.add(byteIds[b & 0xff]);
                }
            } else {
                ids.add(unknownId);
            }
        }
        return ids.stream().mapToInt(Integer::intValue).toArray();
    }

    /** Decodes token ids back to text, matching {@code SentencePieceProcessor.Decode}. */
    public String decode(int[] ids) {
        StringBuilder text = new StringBuilder();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        for (int id : ids) {
            if (id < 0 || id >= pieces.length || types[id] == CONTROL) {
                continue;
            }
            if (types[id] == BYTE) {
                bytes.write(Integer.parseInt(pieces[id].substring(3, 5), 16));
                continue;
            }
            flush(bytes, text);
            text.append(types[id] == UNKNOWN ? " \u2047 " : pieces[id]);
        }
        flush(bytes, text);
        String decoded = text.toString().replace(SPACE, ' ');
        return addDummyPrefix && decoded.startsWith(" ") ? decoded.substring(1) : decoded;
    }

    private static void flush(ByteArrayOutputStream bytes, StringBuilder text) {
        if (bytes.size() > 0) {
            text.append(bytes.toString(StandardCharsets.UTF_8));
            bytes.reset();
        }
    }

    /** Just enough protobuf wire-format decoding for the SentencePiece model. */
    private static final class Proto {
        private final ByteBuffer buffer;
        private final int end;

        Proto(byte[] data, int start, int end) {
            this.buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
            this.buffer.position(start);
            this.end = end;
        }

        boolean hasMore() {
            return buffer.position() < end;
        }

        long varint() {
            long value = 0;
            for (int shift = 0; shift < 64; shift += 7) {
                byte b = buffer.get();
                value |= (long) (b & 0x7f) << shift;
                if ((b & 0x80) == 0) {
                    return value;
                }
            }
            throw new IllegalStateException("Malformed varint");
        }

        int varint32() {
            return (int) varint();
        }

        float float32() {
            return buffer.getFloat();
        }

        String string() {
            int length = varint32();
            String value = new String(buffer.array(), buffer.position(), length, StandardCharsets.UTF_8);
            buffer.position(buffer.position() + length);
            return value;
        }

        Proto message() {
            int length = varint32();
            int start = buffer.position();
            buffer.position(start + length);
            return new Proto(buffer.array(), start, start + length);
        }

        void skip(int tag) {
            switch (tag & 7) {
                case 0 -> varint();
                case 1 -> buffer.position(buffer.position() + 8);
                case 2 -> {
                    int length = varint32();
                    buffer.position(buffer.position() + length);
                }
                case 5 -> buffer.position(buffer.position() + 4);
                default -> throw new IllegalStateException("Unsupported protobuf wire type " + (tag & 7));
            }
        }
    }
}
