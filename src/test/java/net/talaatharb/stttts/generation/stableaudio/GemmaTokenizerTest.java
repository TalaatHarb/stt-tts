package net.talaatharb.stttts.generation.stableaudio;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GemmaTokenizerTest {
    @TempDir Path temp;

    @Test
    void appliesBpeWithoutAddingBosOrNormalizingWhitespace() throws Exception {
        Path file = temp.resolve("tokenizer.json");
        Files.writeString(file, """
                {
                  "normalizer": {"type":"Replace","pattern":{"String":" "},"content":"\\u2581"},
                  "model": {
                    "type":"BPE",
                    "vocab":{"<pad>":0,"h":1,"i":2,"\\u2581":3,"hi":4,"\\u2581hi":5,"<0xF0>":6,
                             "<0x9F>":7,"<0x8E>":8,"<0xB5>":9},
                    "merges":[["h","i"],["\\u2581","hi"]]
                  },
                  "added_tokens":[{"content":"<pad>","id":0}]
                }
                """);
        GemmaTokenizer tokenizer = new GemmaTokenizer(file);
        assertArrayEquals(new long[] {4}, tokenizer.encode("hi"));
        assertArrayEquals(new long[] {3, 5}, tokenizer.encode("  hi"));
        assertArrayEquals(new long[] {0, 4}, tokenizer.encode("<pad>hi"));
        assertArrayEquals(new long[] {6, 7, 8, 9}, tokenizer.encode("\ud83c\udfb5"));
    }
}
