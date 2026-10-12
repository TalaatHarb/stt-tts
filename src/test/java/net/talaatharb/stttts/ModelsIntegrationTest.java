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
import net.talaatharb.stttts.model.HuggingFaceDownloader;
import net.talaatharb.stttts.model.ModelStore;
import net.talaatharb.stttts.model.ProgressListener;
import net.talaatharb.stttts.service.SpeechRequest;
import net.talaatharb.stttts.service.Transcription;
import net.talaatharb.stttts.service.TranscriptionRequest;
import net.talaatharb.stttts.service.VoiceCloneRequest;
import net.talaatharb.stttts.stt.whistle.WhistleSpeechToTextService;
import net.talaatharb.stttts.tts.kokoro.KokoroTextToSpeechService;
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

    @BeforeAll
    static void setUp() {
        assumeTrue(Boolean.getBoolean("stttts.integration"), "integration tests disabled");
        store = new ModelStore(ModelStore.defaultRoot());
        downloader = new HuggingFaceDownloader();
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
    void whistleTranscribesChatterboxClone() throws Exception {
        try (ChatterboxVoiceCloningService chatterbox = new ChatterboxVoiceCloningService(store, downloader)) {
            chatterbox.install(LOG);
            AudioClip reference = WavIO.read(chatterbox.defaultVoice());
            AudioClip clip = chatterbox.cloneVoice(new VoiceCloneRequest(TEXT, reference, 0.5f), LOG);
            assertUnderstood(clip, "chatterbox");
        }
    }
}
