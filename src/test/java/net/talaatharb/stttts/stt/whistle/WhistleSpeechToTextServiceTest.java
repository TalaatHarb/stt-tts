package net.talaatharb.stttts.stt.whistle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.talaatharb.stttts.model.HuggingFaceDownloader;
import net.talaatharb.stttts.model.ModelStore;
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
        assertEquals("needle.exe", NeedlePlatform.detect("Windows 11", "amd64").executable());
        assertEquals("macos-arm64", NeedlePlatform.detect("Mac OS X", "aarch64").folder());
        assertEquals("linux-armv7", NeedlePlatform.detect("Linux", "armv7l").folder());
        assertThrows(UnsupportedOperationException.class, () -> NeedlePlatform.detect("Mac OS X", "x86_64"));
    }

    @Test
    void buildsCommandLine() {
        List<String> auto = service().command(Path.of("a.wav"), new TranscriptionRequest(null, false));
        assertFalse(auto.contains("--audio-language"));
        List<String> french = service().command(Path.of("a.wav"), new TranscriptionRequest("fr", true));
        assertTrue(french.containsAll(List.of("--audio-language", "fr", "--audio-word-timestamps")));
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
