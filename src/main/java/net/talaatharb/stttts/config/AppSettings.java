package net.talaatharb.stttts.config;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** User preferences persisted in {@code ~/.stt-tts/settings.properties}. */
public class AppSettings {
    private static final Logger LOG = LoggerFactory.getLogger(AppSettings.class);
    private static final String GPU = "gpu.enabled";

    private final Path file;
    private final Properties properties = new Properties();

    public AppSettings(Path file) {
        this.file = file;
        if (Files.isRegularFile(file)) {
            try (InputStream in = Files.newInputStream(file)) {
                properties.load(in);
            } catch (IOException e) {
                LOG.warn("Could not read settings from {}", file, e);
            }
        }
    }

    public static AppSettings load() {
        return new AppSettings(Path.of(System.getProperty("user.home"), ".stt-tts", "settings.properties"));
    }

    public synchronized boolean gpuEnabled() {
        return Boolean.parseBoolean(properties.getProperty(GPU, "false"));
    }

    public synchronized void setGpuEnabled(boolean enabled) {
        properties.setProperty(GPU, Boolean.toString(enabled));
        save();
    }

    private void save() {
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            try (OutputStream out = Files.newOutputStream(file)) {
                properties.store(out, "STT-TTS Studio settings");
            }
        } catch (IOException e) {
            LOG.warn("Could not save settings to {}", file, e);
        }
    }
}
