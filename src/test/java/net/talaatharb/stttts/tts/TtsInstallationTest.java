package net.talaatharb.stttts.tts;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.talaatharb.stttts.model.HuggingFaceDownloader;
import net.talaatharb.stttts.model.ModelStore;
import net.talaatharb.stttts.model.ProgressListener;
import net.talaatharb.stttts.service.SpeechRequest;
import net.talaatharb.stttts.service.TextToSpeechService;
import net.talaatharb.stttts.tts.kokoro.KokoroTextToSpeechService;
import net.talaatharb.stttts.tts.piper.PiperTextToSpeechService;
import net.talaatharb.stttts.tts.pocket.PocketModels;
import net.talaatharb.stttts.tts.pocket.PocketTextToSpeechService;

class TtsInstallationTest {
    @TempDir Path temp;

    @Test
    void checksExactRequestedVoiceAndLanguageRatherThanOnlyDefaultBundle() throws Exception {
        ModelStore store = new ModelStore(temp);
        HuggingFaceDownloader downloader = new HuggingFaceDownloader() {
            @Override
            public void download(RemoteFile file, ProgressListener progress) throws IOException {
                Files.createDirectories(file.target().getParent());
                Files.writeString(file.target(), "test asset");
            }
        };
        List<TextToSpeechService> services = List.of(new KokoroTextToSpeechService(store, downloader),
                new PiperTextToSpeechService(store, downloader),
                new PocketTextToSpeechService(new PocketModels(store, downloader)));
        for (TextToSpeechService service : services) {
            assertFalse(service.isInstalled());
            service.install(ProgressListener.NONE);
            assertTrue(service.isInstalled());
            assertTrue(service.isInstalled(new SpeechRequest("hello", null, null, 1)), service.id());
            var alternate = service.voices().get(service.voices().size() - 1);
            assertFalse(service.isInstalled(new SpeechRequest("hello", alternate.id(), alternate.languageCode(), 1)),
                    service.id() + " must not claim an undownloaded voice is installed");
        }
        TextToSpeechService pocket = services.get(2);
        assertFalse(pocket.isInstalled(new SpeechRequest("Bonjour", null, "fr", 1)),
                "The installed English Pocket bundle must not imply the French bundle is installed");
    }
}
