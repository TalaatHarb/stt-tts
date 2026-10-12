package net.talaatharb.stttts.service;

import java.io.IOException;

import net.talaatharb.stttts.model.ProgressListener;

/** Common lifecycle of a locally executed speech model: install (download) once, load lazily, release on exit. */
public interface SpeechModelService extends AutoCloseable {
    /** Stable identifier, e.g. {@code kokoro}. */
    String id();

    String displayName();

    /** One-line description of the model, its size and license. */
    String description();

    /** True when every file needed to run the model is in the local cache. */
    boolean isInstalled();

    /** Downloads any missing model files. Safe to call repeatedly. */
    void install(ProgressListener progress) throws IOException;

    @Override
    default void close() {
    }
}
