package net.talaatharb.stttts;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import net.talaatharb.stttts.audio.AudioClip;
import net.talaatharb.stttts.audio.WavIO;
import net.talaatharb.stttts.cloning.chatterbox.ChatterboxVoiceCloningService;
import net.talaatharb.stttts.cloning.pocket.PocketVoiceCloningService;
import net.talaatharb.stttts.model.GpuRuntime;
import net.talaatharb.stttts.model.HuggingFaceDownloader;
import net.talaatharb.stttts.model.ModelStore;
import net.talaatharb.stttts.model.ProgressListener;
import net.talaatharb.stttts.service.SpeechRequest;
import net.talaatharb.stttts.service.Transcription;
import net.talaatharb.stttts.service.TranscriptionRequest;
import net.talaatharb.stttts.service.VoiceCloneRequest;
import net.talaatharb.stttts.stt.whistle.WhistleSpeechToTextService;
import net.talaatharb.stttts.tts.kokoro.KokoroTextToSpeechService;
import net.talaatharb.stttts.tts.piper.PiperTextToSpeechService;
import net.talaatharb.stttts.tts.pocket.PocketModels;
import net.talaatharb.stttts.tts.pocket.PocketTextToSpeechService;
import net.talaatharb.stttts.tts.supertonic.SupertonicTextToSpeechService;

/**
 * End-to-end checks against the real models: speech produced by each engine is transcribed back by Whistle.
 * Downloads roughly 2 GB on first run, so it only runs with {@code -Dstttts.integration=true}.
 */
class ModelsIntegrationTest {
    private static final String TEXT = "The quick brown fox jumps over the lazy dog.";
    private static ModelStore store;
    private static HuggingFaceDownloader downloader;
    private static WhistleSpeechToTextService whistle;
    private static final ProgressListener LOG = (message, fraction) -> System.out.printf("  %s (%.0f%%)%n",
            message, fraction * 100);
    private static final ProgressListener QUIET = (message, fraction) -> {
        if (!message.startsWith("Generating")) {
            LOG.update(message, fraction);
        }
    };

    @BeforeAll
    static void setUp() {
        assumeTrue(Boolean.getBoolean("stttts.integration"), "integration tests disabled");
        store = new ModelStore(ModelStore.defaultRoot());
        downloader = new HuggingFaceDownloader();
        if ("gpu".equalsIgnoreCase(System.getProperty(GpuRuntime.DEVICE_PROPERTY)) && GpuRuntime.isSupported()) {
            try {
                GpuRuntime.install(store, ProgressListener.NONE);
            } catch (java.io.IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        }
        GpuRuntime.initialize(store, false);
        System.out.println("GPU runtime active: " + GpuRuntime.isActive() + ", enabled: " + GpuRuntime.isEnabled());
        whistle = new WhistleSpeechToTextService(store, downloader);
    }

    @AfterAll
    static void tearDown() {
        if (whistle != null) {
            whistle.close();
        }
    }

    private static void assertUnderstood(AudioClip clip, String name) throws Exception {
        WavIO.write(clip, Path.of("target", name + ".wav"));
        Transcription transcription = whistle.transcribe(clip, new TranscriptionRequest("en", false), LOG);
        System.out.println(name + " -> " + transcription.text());
        String heard = transcription.text().toLowerCase(Locale.ROOT);
        long matches = List.of("quick", "brown", "fox", "jumps", "lazy", "dog").stream().filter(heard::contains)
                .count();
        assertTrue(matches >= 4, name + " was transcribed as: " + transcription.text());
    }

    @Test
    void whistleTranscribesKokoroSpeech() throws Exception {
        try (KokoroTextToSpeechService kokoro = new KokoroTextToSpeechService(store, downloader)) {
            AudioClip clip = kokoro.synthesize(new SpeechRequest(TEXT, "af_heart", "en-us", 1f), LOG);
            assertUnderstood(clip, "kokoro");
        }
    }

    @Test
    void whistleTranscribesSupertonicSpeech() throws Exception {
        try (SupertonicTextToSpeechService supertonic = new SupertonicTextToSpeechService(store, downloader)) {
            AudioClip clip = supertonic.synthesize(new SpeechRequest(TEXT, "M1", "en", 1.05f), LOG);
            assertUnderstood(clip, "supertonic");
        }
    }

    @Test
    void whistleTranscribesPiperSpeech() throws Exception {
        try (PiperTextToSpeechService piper = new PiperTextToSpeechService(store, downloader)) {
            long start = System.nanoTime();
            AudioClip clip = piper.synthesize(new SpeechRequest(TEXT, "en_US-lessac-medium", "en-us", 1f), LOG);
            System.out.printf("Piper took %.1f s%n", (System.nanoTime() - start) / 1e9);
            assertUnderstood(clip, "piper");
            clip = piper.synthesize(new SpeechRequest(TEXT, "en_GB-alan-medium", "en-gb", 1f), LOG);
            assertUnderstood(clip, "piper-gb");
        }
    }

    @Test
    void whistleTranscribesPocketSpeechAndClone() throws Exception {
        PocketModels models = new PocketModels(store, downloader);
        try (PocketTextToSpeechService pocket = new PocketTextToSpeechService(models);
                PocketVoiceCloningService cloning = new PocketVoiceCloningService(models)) {
            long start = System.nanoTime();
            AudioClip clip = pocket.synthesize(new SpeechRequest(TEXT, "alba", "en", 1f), QUIET);
            System.out.printf("Pocket TTS took %.1f s for %.1f s of audio%n", (System.nanoTime() - start) / 1e9,
                    clip.durationSeconds());
            assertUnderstood(clip, "pocket");

            AudioClip reference = WavIO.read(Path.of("target", "kokoro.wav"));
            assumeTrue(reference.durationSeconds() >= 1, "run the Kokoro test first for a reference");
            start = System.nanoTime();
            clip = cloning.cloneVoice(new VoiceCloneRequest(TEXT, reference, 0.5f, "en"), QUIET);
            System.out.printf("Pocket cloning took %.1f s%n", (System.nanoTime() - start) / 1e9);
            assertUnderstood(clip, "pocket-clone");
        }
    }

    @Test
    void whistleTranscribesChatterboxClone() throws Exception {
        try (ChatterboxVoiceCloningService chatterbox = new ChatterboxVoiceCloningService(store, downloader)) {
            chatterbox.install(LOG);
            AudioClip reference = WavIO.read(chatterbox.defaultVoice());
            long start = System.nanoTime();
            AudioClip clip = chatterbox.cloneVoice(new VoiceCloneRequest(TEXT, reference, 0.5f), QUIET);
            System.out.printf("Chatterbox (%s) took %.1f s%n", GpuRuntime.isEnabled() ? "GPU" : "CPU",
                    (System.nanoTime() - start) / 1e9);
            assertUnderstood(clip, "chatterbox");
            start = System.nanoTime();
            clip = chatterbox.cloneVoice(new VoiceCloneRequest(TEXT, reference, 0.5f), QUIET);
            System.out.printf("Chatterbox (%s) second run took %.1f s%n", GpuRuntime.isEnabled() ? "GPU" : "CPU",
                    (System.nanoTime() - start) / 1e9);
            assertUnderstood(clip, "chatterbox (warm)");
        }
    }
}
