package net.talaatharb.stttts.model;

import java.nio.file.Path;

import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;

/** Shared ONNX Runtime environment; the Maven artifact bundles the native CPU runtime for every desktop OS. */
public final class Onnx {
    private Onnx() {
    }

    public static OrtEnvironment env() {
        return OrtEnvironment.getEnvironment();
    }

    public static OrtSession open(Path model) throws OrtException {
        try (OrtSession.SessionOptions options = new OrtSession.SessionOptions()) {
            options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
            options.setIntraOpNumThreads(Math.max(1, Math.min(8, Runtime.getRuntime().availableProcessors())));
            return env().createSession(model.toString(), options);
        }
    }

    public static void closeQuietly(AutoCloseable... closeables) {
        for (AutoCloseable closeable : closeables) {
            if (closeable != null) {
                try {
                    closeable.close();
                } catch (Exception ignored) {
                    // Best effort cleanup.
                }
            }
        }
    }
}
