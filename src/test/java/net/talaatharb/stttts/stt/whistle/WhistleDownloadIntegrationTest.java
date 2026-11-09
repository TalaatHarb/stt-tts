package net.talaatharb.stttts.stt.whistle;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.Locale;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import net.talaatharb.stttts.audio.AudioClip;
import net.talaatharb.stttts.model.HuggingFaceDownloader;
import net.talaatharb.stttts.model.ModelStore;
import net.talaatharb.stttts.model.ProgressListener;
import net.talaatharb.stttts.service.SpeechRequest;
import net.talaatharb.stttts.service.Transcription;
import net.talaatharb.stttts.service.TranscriptionRequest;
import net.talaatharb.stttts.tts.kokoro.KokoroTextToSpeechService;

/** Exercises actual model/runtime downloads into a fresh cache, not an already installed engine. */
@EnabledIfSystemProperty(named = "stttts.integration", matches = "true")
class WhistleDownloadIntegrationTest {
    @TempDir Path models;

    @Test
    void freshDownloadsTranscribeWithCurrentRuntime() {
        HuggingFaceDownloader downloader = new HuggingFaceDownloader();
        AudioClip audio;
        try (KokoroTextToSpeechService kokoro = new KokoroTextToSpeechService(
                new ModelStore(ModelStore.defaultRoot()), downloader)) {
            audio = kokoro.synthesize(new SpeechRequest("The quick brown fox jumps over the lazy dog.",
                    "af_heart", "en-us", 1f), ProgressListener.NONE);
        }
        WhistleSpeechToTextService whistle = new WhistleSpeechToTextService(new ModelStore(models), downloader);
        assertFalse(whistle.isInstalled());
        Transcription result = whistle.transcribe(audio, new TranscriptionRequest("en", true),
                (message, fraction) -> System.out.println(message));
        assertTrue(whistle.isInstalled());
        String text = result.text().toLowerCase(Locale.ROOT);
        assertTrue(text.contains("fox") && text.contains("dog"), result.text());
        assertTrue(!result.words().isEmpty(), "The new runtime must preserve word timestamps");
    }
}
