package net.talaatharb.stttts.model;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;

/**
 * Shared ONNX Runtime environment; the Maven artifact bundles the native CPU runtime for every desktop OS and
 * {@link GpuRuntime} can swap in the DirectML build on Windows.
 */
public final class Onnx {
    /** DirectML adapter index; by default the high-performance GPU is chosen. */
    public static final String GPU_DEVICE_PROPERTY = "stttts.gpu.device";
    private static final Logger LOG = LoggerFactory.getLogger(Onnx.class);
    private static final Set<OrtSession> ACCELERATED = Collections.synchronizedSet(
            Collections.newSetFromMap(new WeakHashMap<>()));

    private Onnx() {
    }

    /** True when the session was opened on the GPU. */
    public static boolean isAccelerated(OrtSession session) {
        return ACCELERATED.contains(session);
    }

    public static OrtEnvironment env() {
        GpuRuntime.markNativeLoaded();
        return OrtEnvironment.getEnvironment();
    }

    /** Opens a session on the CPU. */
    public static OrtSession open(Path model) throws OrtException {
        return open(model, OrtSession.SessionOptions.OptLevel.ALL_OPT);
    }

    /**
     * Opens a session on the CPU with a specific optimization level; very large graphs initialize noticeably faster
     * with {@code EXTENDED_OPT} than with {@code ALL_OPT} at the same inference speed.
     */
    public static OrtSession open(Path model, OrtSession.SessionOptions.OptLevel level) throws OrtException {
        OrtEnvironment env = env();
        try (OrtSession.SessionOptions options = cpuOptions()) {
            options.setOptimizationLevel(level);
            return env.createSession(model.toString(), options);
        }
    }

    /** Opens a session on the GPU when acceleration is enabled, falling back to the CPU if that fails. */
    public static OrtSession openAccelerated(Path model) throws OrtException {
        return openAccelerated(model, OrtSession.SessionOptions.OptLevel.ALL_OPT);
    }

    /** Like {@link #openAccelerated(Path)} with a specific optimization level. */
    public static OrtSession openAccelerated(Path model, OrtSession.SessionOptions.OptLevel level)
            throws OrtException {
        OrtEnvironment env = env();
        if (GpuRuntime.isEnabled()) {
            try (OrtSession.SessionOptions options = new OrtSession.SessionOptions()) {
                options.setOptimizationLevel(level);
                // Required by the DirectML execution provider.
                options.setMemoryPatternOptimization(false);
                options.setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL);
                addDirectMl(options);
                OrtSession session = env.createSession(model.toString(), options);
                ACCELERATED.add(session);
                LOG.info("Opened {} on the GPU (DirectML)", model.getFileName());
                return session;
            } catch (OrtException | RuntimeException e) {
                LOG.warn("GPU session for {} failed, using the CPU instead: {}", model.getFileName(), e.getMessage());
            }
        }
        return open(model, level);
    }

    private static OrtSession.SessionOptions cpuOptions() throws OrtException {
        OrtSession.SessionOptions options = new OrtSession.SessionOptions();
        options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
        options.setIntraOpNumThreads(Math.max(1, Math.min(8, Runtime.getRuntime().availableProcessors())));
        return options;
    }

    /**
     * The Java binding only exposes DirectML through a JNI entry point that is compiled out of the CPU artifact, so
     * the provider is registered through ONNX Runtime's generic (name based) registration instead.
     */
    private static void addDirectMl(OrtSession.SessionOptions options) throws OrtException {
        try {
            Method method = OrtSession.SessionOptions.class.getDeclaredMethod("addExecutionProvider", String.class,
                    Map.class);
            method.setAccessible(true);
            method.invoke(options, "DML", directMlOptions());
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof OrtException ort) {
                throw ort;
            }
            throw new IllegalStateException(e.getCause());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("This ONNX Runtime version cannot register DirectML", e);
        }
    }

    /**
     * Prefers the high-performance (discrete) adapter: on hybrid laptops adapter 0 is usually the integrated GPU,
     * which is slower than the CPU for these models. {@value #GPU_DEVICE_PROPERTY} picks an adapter explicitly.
     */
    static Map<String, String> directMlOptions() {
        String device = System.getProperty(GPU_DEVICE_PROPERTY);
        if (device != null && !device.isBlank()) {
            return Map.of("device_id", device.trim());
        }
        return Map.of("performance_preference", "high_performance", "device_filter", "gpu");
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
