package net.talaatharb.stttts.config;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Properties;
import java.util.UUID;

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

    public synchronized int mcpPort() {
        String value = mcpPortText();
        try {
            int port = Integer.parseInt(value);
            if (port < 1 || port > 65535) {
                throw new NumberFormatException();
            }
            return port;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("mcp.port must be between 1 and 65535: " + value, e);
        }
    }

    public synchronized String mcpPortText() {
        return properties.getProperty("mcp.port", "8765");
    }

    /** Does not create or persist a token just because the settings window is opened. */
    public synchronized String configuredMcpToken() {
        return properties.getProperty("mcp.token", "").strip();
    }

    public synchronized void setMcpConfiguration(int port, String stt, String tts, String music, String sfx,
            String token) throws IOException {
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("Port must be between 1 and 65535");
        }
        String[] kinds = {"stt", "tts", "music", "sfx"};
        String[] models = {stt, tts, music, sfx};
        Properties updated = new Properties();
        updated.putAll(properties);
        for (int i = 0; i < kinds.length; i++) {
            if (models[i] == null || !models[i].matches("[A-Za-z0-9._-]{1,128}")) {
                throw new IllegalArgumentException("Select a default " + kinds[i] + " model");
            }
            updated.setProperty("mcp." + kinds[i] + ".model", models[i]);
        }
        if (token == null || !token.isEmpty() && !validMcpToken(token)) {
            throw new IllegalArgumentException("Token must contain 32-256 ASCII letters, digits or ._~-");
        }
        updated.setProperty("mcp.port", Integer.toString(port));
        if (token.isEmpty()) {
            updated.remove("mcp.token");
        } else {
            updated.setProperty("mcp.token", token);
        }
        write(updated);
        properties.clear();
        properties.putAll(updated);
    }

    public synchronized String mcpModel(String kind) {
        String fallback = switch (kind) {
            case "stt" -> "whistle";
            case "tts" -> "kokoro";
            case "music" -> "musicgen-small";
            case "sfx" -> "stable-audio-3-sfx";
            default -> throw new IllegalArgumentException("Unknown MCP model kind: " + kind);
        };
        return properties.getProperty("mcp." + kind + ".model", fallback).strip();
    }

    /** Created only when the user starts MCP; enabling the server itself is never persisted. */
    public synchronized String mcpToken() {
        String token = properties.getProperty("mcp.token", "").strip();
        if (token.isEmpty()) {
            token = UUID.randomUUID().toString() + UUID.randomUUID();
            properties.setProperty("mcp.token", token);
            if (!save()) {
                properties.remove("mcp.token");
                throw new IllegalStateException("Could not persist MCP token in " + file);
            }
        }
        if (!validMcpToken(token)) {
            throw new IllegalArgumentException("mcp.token must contain 32-256 ASCII letters, digits or ._~-");
        }
        return token;
    }

    private static boolean validMcpToken(String token) {
        return token.matches("[A-Za-z0-9._~-]{32,256}");
    }

    private boolean save() {
        try {
            write(properties);
            return true;
        } catch (IOException e) {
            LOG.warn("Could not save settings to {}", file, e);
            return false;
        }
    }

    private void write(Properties values) throws IOException {
        Path destination = file.toAbsolutePath();
        Files.createDirectories(destination.getParent());
        Path temporary = Files.createTempFile(destination.getParent(), "settings-", ".tmp");
        try {
            try (OutputStream out = Files.newOutputStream(temporary)) {
                values.store(out, "STT-TTS Studio settings");
            }
            try {
                Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
