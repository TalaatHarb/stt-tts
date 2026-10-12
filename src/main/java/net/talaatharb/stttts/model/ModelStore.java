package net.talaatharb.stttts.model;

import java.nio.file.Path;
import java.nio.file.Paths;

/** Root of the local model cache; every service keeps its files in a sub-directory. */
public final class ModelStore {
    public static final String MODELS_DIR_PROPERTY = "stttts.models";

    private final Path root;

    public ModelStore(Path root) {
        this.root = root;
    }

    public static Path defaultRoot() {
        String override = System.getProperty(MODELS_DIR_PROPERTY);
        if (override != null && !override.isBlank()) {
            return Paths.get(override);
        }
        return Paths.get(System.getProperty("user.home"), ".stt-tts", "models");
    }

    public Path root() {
        return root;
    }

    public Path directory(String name) {
        return root.resolve(name);
    }
}
