package net.talaatharb.stttts.model;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Optional GPU acceleration through ONNX Runtime's DirectML execution provider (any DirectX 12 GPU on Windows:
 * NVIDIA, AMD or Intel). Nothing has to be installed: the DirectML build of {@code onnxruntime.dll} and
 * {@code DirectML.dll} are extracted on demand from Microsoft's official NuGet packages (about 15 MB of download)
 * and swapped in for the CPU-only native library before ONNX Runtime is first loaded.
 *
 * <p>The DirectML build also contains the regular CPU execution provider, so once it is active GPU usage can be
 * toggled at runtime; sessions opened afterwards use the new setting.
 */
public final class GpuRuntime {
    /** {@code gpu} or {@code cpu}; overrides the saved preference. */
    public static final String DEVICE_PROPERTY = "stttts.device";
    static final String ORT_VERSION = "1.23.0";
    static final String DIRECTML_VERSION = "1.15.4";
    static final String ORT_PACKAGE = "https://api.nuget.org/v3-flatcontainer/microsoft.ml.onnxruntime.directml/"
            + ORT_VERSION + "/microsoft.ml.onnxruntime.directml." + ORT_VERSION + ".nupkg";
    static final String DIRECTML_PACKAGE = "https://api.nuget.org/v3-flatcontainer/microsoft.ai.directml/"
            + DIRECTML_VERSION + "/microsoft.ai.directml." + DIRECTML_VERSION + ".nupkg";
    static final String ORT_LIBRARY = "onnxruntime.dll";
    static final String DIRECTML_LIBRARY = "DirectML.dll";
    private static final Logger LOG = LoggerFactory.getLogger(GpuRuntime.class);

    private static volatile boolean nativeLoaded;
    private static volatile boolean active;
    private static volatile boolean enabled;

    private GpuRuntime() {
    }

    /** DirectML is only published for Windows on x64. */
    public static boolean isSupported() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        return os.startsWith("windows") && (arch.equals("amd64") || arch.equals("x86_64"));
    }

    public static Path directory(ModelStore store) {
        return store.directory("directml-runtime-" + ORT_VERSION);
    }

    public static boolean isInstalled(ModelStore store) {
        Path dir = directory(store);
        return Files.isRegularFile(dir.resolve(ORT_LIBRARY)) && Files.isRegularFile(dir.resolve(DIRECTML_LIBRARY));
    }

    /** Downloads the DirectML runtime libraries (only the two needed files are fetched from the packages). */
    public static void install(ModelStore store, ProgressListener progress) throws IOException {
        if (!isSupported()) {
            throw new IOException("GPU acceleration (DirectML) is only available on 64-bit Windows");
        }
        Path dir = directory(store);
        if (!Files.isRegularFile(dir.resolve(ORT_LIBRARY))) {
            new RemoteZip(ORT_PACKAGE).extract("runtimes/win-x64/native/" + ORT_LIBRARY, dir.resolve(ORT_LIBRARY),
                    progress.slice(0, 0.4));
        }
        if (!Files.isRegularFile(dir.resolve(DIRECTML_LIBRARY))) {
            new RemoteZip(DIRECTML_PACKAGE).extract("bin/x64-win/" + DIRECTML_LIBRARY,
                    dir.resolve(DIRECTML_LIBRARY), progress.slice(0.4, 1));
        }
        progress.update("GPU runtime is available", 1);
    }

    /**
     * Makes ONNX Runtime use the DirectML build. Must run before the first ONNX session is created; returns whether
     * the DirectML runtime is (now) active.
     */
    public static synchronized boolean activate(ModelStore store) {
        if (active) {
            return true;
        }
        if (!isSupported() || !isInstalled(store) || nativeLoaded) {
            return false;
        }
        Path dir = directory(store).toAbsolutePath();
        try {
            // Loading our DirectML.dll first stops Windows from resolving the older copy in System32.
            System.load(dir.resolve(DIRECTML_LIBRARY).toString());
            System.setProperty("onnxruntime.native.onnxruntime.path", dir.resolve(ORT_LIBRARY).toString());
            active = true;
            LOG.info("Using the DirectML build of ONNX Runtime from {}", dir);
        } catch (UnsatisfiedLinkError e) {
            LOG.warn("Could not load DirectML, staying on the CPU runtime", e);
        }
        return active;
    }

    /** Applies the saved preference (or the {@value #DEVICE_PROPERTY} override) at start-up. */
    public static void initialize(ModelStore store, boolean preferGpu) {
        String override = System.getProperty(DEVICE_PROPERTY);
        boolean wanted = override == null || override.isBlank() ? preferGpu : override.equalsIgnoreCase("gpu");
        activate(store);
        enabled = wanted;
    }

    /** True when the DirectML runtime was loaded in this process. */
    public static boolean isActive() {
        return active;
    }

    /** True when new sessions should run on the GPU. */
    public static boolean isEnabled() {
        return enabled && active;
    }

    public static boolean isRequested() {
        return enabled;
    }

    public static void setEnabled(boolean value) {
        enabled = value;
    }

    /** Called once ONNX Runtime's native library has been loaded; the runtime can't be swapped afterwards. */
    static void markNativeLoaded() {
        nativeLoaded = true;
    }
}
