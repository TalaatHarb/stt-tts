package net.talaatharb.stttts.config;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AppSettingsTest {
    @TempDir Path temp;

    @Test
    void defaultsToWhistleAndKokoroAndPersistsTokenOnlyOnDemand() {
        Path file = temp.resolve("settings.properties");
        AppSettings settings = new AppSettings(file);
        assertEquals(8765, settings.mcpPort());
        assertEquals("whistle", settings.mcpModel("stt"));
        assertEquals("kokoro", settings.mcpModel("tts"));
        assertFalse(Files.exists(file));
        String token = settings.mcpToken();
        assertTrue(token.length() >= 32);
        assertEquals(token, new AppSettings(file).mcpToken());
        assertFalse(settings.gpuEnabled());
        settings.setGpuEnabled(true);
        assertTrue(new AppSettings(file).gpuEnabled());
    }

    @Test
    void rejectsInvalidPortAndWeakToken() throws Exception {
        Path file = temp.resolve("settings.properties");
        for (String port : new String[] {"0", "65536", "not-a-number"}) {
            Files.writeString(file, "mcp.port=" + port);
            assertThrows(IllegalArgumentException.class, () -> new AppSettings(file).mcpPort());
        }
        Files.writeString(file, "mcp.token=weak");
        assertThrows(IllegalArgumentException.class, () -> new AppSettings(file).mcpToken());
    }

    @Test
    void savesMcpSettingsTogetherAndPreservesOtherPreferences() throws Exception {
        Path file = temp.resolve("settings.properties");
        Files.writeString(file, "gpu.enabled=true\ncustom.option=preserved\n");
        AppSettings settings = new AppSettings(file);
        String token = "a".repeat(40);
        settings.setMcpConfiguration(8888, "whistle", "piper", "stable-audio-3-music", "stable-audio-3-sfx", token);
        AppSettings loaded = new AppSettings(file);
        assertEquals(8888, loaded.mcpPort());
        assertEquals("piper", loaded.mcpModel("tts"));
        assertEquals("stable-audio-3-music", loaded.mcpModel("music"));
        assertEquals(token, loaded.configuredMcpToken());
        assertTrue(loaded.gpuEnabled());
        assertTrue(Files.readString(file).contains("custom.option=preserved"));
        settings.setMcpConfiguration(8765, "whistle", "kokoro", "musicgen-small", "stable-audio-3-sfx", "");
        assertEquals("", new AppSettings(file).configuredMcpToken());
    }

    @Test
    void invalidOrFailedSaveLeavesPreviousConfigurationIntact() throws Exception {
        Path file = temp.resolve("settings.properties");
        AppSettings settings = new AppSettings(file);
        settings.setMcpConfiguration(8888, "whistle", "kokoro", "musicgen-small", "stable-audio-3-sfx", "");
        String original = Files.readString(file);
        assertThrows(IllegalArgumentException.class, () -> settings.setMcpConfiguration(0, "whistle", "piper",
                "musicgen-small", "stable-audio-3-sfx", ""));
        assertThrows(IllegalArgumentException.class, () -> settings.setMcpConfiguration(9999, "whistle", "piper",
                "musicgen-small", "stable-audio-3-sfx", "weak"));
        assertEquals(original, Files.readString(file));
        assertEquals(8888, settings.mcpPort());
        Files.delete(file);
        Files.createDirectory(file);
        Files.writeString(file.resolve("block-replacement"), "not empty");
        assertThrows(java.io.IOException.class, () -> settings.setMcpConfiguration(9999, "whistle", "piper",
                "musicgen-small", "stable-audio-3-sfx", ""));
        assertEquals(8888, settings.mcpPort());
        assertEquals("kokoro", settings.mcpModel("tts"));
        try (var files = Files.list(temp)) {
            assertEquals(1, files.count(), "A failed save must not leave temporary files behind");
        }
    }
}
