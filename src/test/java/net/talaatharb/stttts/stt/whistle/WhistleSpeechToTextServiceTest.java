package net.talaatharb.stttts.stt.whistle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.nio.file.Files;
import java.io.IOException;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.talaatharb.stttts.model.HuggingFaceDownloader;
import net.talaatharb.stttts.model.ModelStore;
import net.talaatharb.stttts.model.ProgressListener;
import net.talaatharb.stttts.service.Transcription;
import net.talaatharb.stttts.service.TranscriptionRequest;

class WhistleSpeechToTextServiceTest {
    @TempDir
    Path models;

    private WhistleSpeechToTextService service() {
        return new WhistleSpeechToTextService(new ModelStore(models), new HuggingFaceDownloader());
    }

    @Test
    void detectsPlatforms() {
        assertEquals("windows-x86_64", NeedlePlatform.detect("Windows 11", "amd64").folder());
        assertEquals("cactus.exe", NeedlePlatform.detect("Windows 11", "amd64").executable());
        assertEquals("macos-arm64", NeedlePlatform.detect("Mac OS X", "aarch64").folder());
        assertEquals("cactus", NeedlePlatform.detect("Mac OS X", "aarch64").executable());
        assertEquals("linux-armv7", NeedlePlatform.detect("Linux", "armv7l").folder());
        for (String arch : List.of("x86_64", "arm64", "armv7l", "riscv64")) {
            assertEquals("cactus", NeedlePlatform.detect("Linux", arch).executable());
        }
        assertEquals("cactus.exe", NeedlePlatform.detect("Windows 11", "arm64").executable());
        assertThrows(UnsupportedOperationException.class, () -> NeedlePlatform.detect("Mac OS X", "x86_64"));
    }

    @Test
    void buildsCommandLine() {
        List<String> auto = service().command(Path.of("a.wav"), new TranscriptionRequest(null, false));
        assertEquals(service().engineFile().toString(), auto.get(0));
        assertFalse(auto.contains("--audio-language"));
        List<String> french = service().command(Path.of("a.wav"), new TranscriptionRequest("fr", true));
        assertTrue(french.containsAll(List.of("--audio-language", "fr", "--audio-word-timestamps")));
    }

    @Test
    void downloadsPinnedRuntimeFromNewRepositoryAndPreservesCachedModel() throws Exception {
        NeedlePlatform platform = NeedlePlatform.current();
        Path model = models.resolve("whistle").resolve("whistle.cact");
        Files.createDirectories(model.getParent());
        Files.writeString(model, "existing model");
        Path legacy = model.getParent().resolve(platform.folder())
                .resolve(platform.executable().endsWith(".exe") ? "needle.exe" : "needle");
        Files.createDirectories(legacy.getParent());
        Files.writeString(legacy, "legacy engine");
        List<HuggingFaceDownloader.RemoteFile> downloads = new java.util.ArrayList<>();
        HuggingFaceDownloader downloader = new HuggingFaceDownloader() {
            @Override
            public void download(RemoteFile file, ProgressListener progress) throws IOException {
                downloads.add(file);
                Files.createDirectories(file.target().getParent());
                Files.writeString(file.target(), "new engine");
            }
        };
        WhistleSpeechToTextService service = new WhistleSpeechToTextService(new ModelStore(models), downloader);
        assertFalse(service.isInstalled());
        service.install(ProgressListener.NONE);
        assertTrue(service.isInstalled());
        assertEquals(1, downloads.size());
        HuggingFaceDownloader.RemoteFile runtime = downloads.get(0);
        assertEquals("Cactus-Compute/cactus", runtime.repo());
        assertEquals("4e822c9036be2eb0e47ef556df32a818bb1572ef", runtime.revision());
        assertEquals("releases/4.1.0/" + platform.remotePath(), runtime.path());
        assertEquals(model.getParent().resolve("runtime-4.1.0").resolve(platform.folder())
                .resolve(platform.executable()), runtime.target());
        assertEquals("existing model", Files.readString(model));
        assertEquals("legacy engine", Files.readString(legacy));
        service.install(ProgressListener.NONE);
        assertEquals(1, downloads.size(), "Repeated installation should reuse the pinned runtime");
    }

    @Test
    void freshInstallDownloadsWhistleWeightsFromTheirOwnRepository() throws Exception {
        List<HuggingFaceDownloader.RemoteFile> downloads = new java.util.ArrayList<>();
        HuggingFaceDownloader downloader = new HuggingFaceDownloader() {
            @Override
            public void download(RemoteFile file, ProgressListener progress) throws IOException {
                downloads.add(file);
                Files.createDirectories(file.target().getParent());
                Files.writeString(file.target(), "download");
            }
        };
        WhistleSpeechToTextService service = new WhistleSpeechToTextService(new ModelStore(models), downloader);
        service.install(ProgressListener.NONE);
        assertEquals(2, downloads.size());
        HuggingFaceDownloader.RemoteFile model = downloads.get(1);
        assertEquals("Cactus-Compute/whistle", model.repo());
        assertEquals("main", model.revision());
        assertEquals("whistle.cact", model.path());
        assertEquals(service.modelFile(), model.target());
    }

    @Test
    void parsesEngineOutput() throws Exception {
        String output = "loading...\n{\"text\":\" Hello world\",\"language\":\"en\",\"words\":["
                + "{\"word\":\"Hello\",\"start\":0.1,\"end\":0.4,\"probability\":0.9}]}";
        Transcription result = service().parse(output, Duration.ofMillis(5));
        assertEquals("Hello world", result.text());
        assertEquals("en", result.language());
        assertEquals(1, result.words().size());
        assertEquals(0.4, result.words().get(0).end(), 1e-9);
    }
}
