package net.talaatharb.stttts.generation.stableaudio;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import net.talaatharb.stttts.audio.AudioClip;
import net.talaatharb.stttts.audio.WavIO;
import net.talaatharb.stttts.model.GpuRuntime;
import net.talaatharb.stttts.model.HuggingFaceDownloader;
import net.talaatharb.stttts.model.ModelStore;
import net.talaatharb.stttts.model.ProgressListener;
import net.talaatharb.stttts.service.AudioGenerationRequest;

@EnabledIfSystemProperty(named = "stttts.integration", matches = "true")
class StableAudioIntegrationTest {
    @Test
    void generatesSoundEffectsWithOfficialGraphs() throws Exception {
        generate(false);
    }

    @Test
    void generatesMusicWithOfficialGraphs() throws Exception {
        generate(true);
    }

    private void generate(boolean music) throws Exception {
        ModelStore store = new ModelStore(ModelStore.defaultRoot());
        GpuRuntime.initialize(store, false);
        try (StableAudioModels models = new StableAudioModels(store, new HuggingFaceDownloader())) {
            models.install(music, ProgressListener.NONE);
            GemmaTokenizer tokenizer = new GemmaTokenizer(store.directory("stable-audio-3").resolve("tokenizer.json"));
            assertArrayEquals(new long[] {235280, 5929, 117322, 575, 476, 11067, 2965},
                    tokenizer.encode("A dog barking in a quiet room"));
            assertArrayEquals(new long[] {139, 17534, 139, 9097}, tokenizer.encode("  hello  world"));
            assertArrayEquals(new long[] {51344, 236703, 235248, 240985}, tokenizer.encode("cafe\u0301 \ud83c\udfb5"));
            String prompt = music ? "Soft instrumental piano with gentle percussion" : "A dog barking in a quiet room";
            long started = System.nanoTime();
            AudioClip clip = models.generate(music, new AudioGenerationRequest(prompt, 5, 42),
                    (message, fraction) -> System.out.println(message));
            assertEquals(44100, clip.sampleRate());
            assertEquals(5 * 44100, clip.samples().length);
            double energy = 0;
            for (float sample : clip.samples()) {
                assertTrue(Float.isFinite(sample));
                assertTrue(Math.abs(sample) <= 1.0001f);
                energy += sample * sample;
            }
            assertTrue(Math.sqrt(energy / clip.samples().length) > 1e-6);
            System.out.printf("Stable Audio %s: %.1f s generation, RMS %.6f%n",
                    music ? "music" : "SFX", (System.nanoTime() - started) / 1e9,
                    Math.sqrt(energy / clip.samples().length));
            WavIO.write(clip, Path.of("target", music ? "stable-music.wav" : "stable-sfx.wav"));
        }
    }
}
