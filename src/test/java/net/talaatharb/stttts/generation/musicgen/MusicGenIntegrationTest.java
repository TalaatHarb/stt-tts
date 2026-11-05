package net.talaatharb.stttts.generation.musicgen;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import net.talaatharb.stttts.audio.AudioClip;
import net.talaatharb.stttts.model.HuggingFaceDownloader;
import net.talaatharb.stttts.model.GpuRuntime;
import net.talaatharb.stttts.model.ModelStore;
import net.talaatharb.stttts.model.Onnx;
import net.talaatharb.stttts.model.ProgressListener;
import net.talaatharb.stttts.service.AudioGenerationRequest;
import ai.onnxruntime.OrtSession;

class MusicGenIntegrationTest {
    @Test
    void acceptsMaximumDurationAndRejectsLongerRequestsBeforeDownloading() {
        Path modelRoot = unusedModelRoot();
        MusicGenSmallService service = new MusicGenSmallService(new ModelStore(modelRoot), new HuggingFaceDownloader());
        try {
            double maximum = service.maxDurationSeconds();
            assertTrue(maximum > 0 && maximum <= 30);
            assertThrows(CancellationException.class,
                    () -> service.generate(new AudioGenerationRequest("test", maximum, 1), cancelled()));
            assertThrows(IllegalArgumentException.class,
                    () -> service.generate(new AudioGenerationRequest("test", maximum + 0.01, 1), ProgressListener.NONE));
            assertFalse(Files.exists(modelRoot));
        } finally {
            service.close();
        }
    }

    @Test
    void rejectsNonPositiveSeedsBeforeDownloading() {
        Path modelRoot = unusedModelRoot();
        MusicGenSmallService service = new MusicGenSmallService(new ModelStore(modelRoot), new HuggingFaceDownloader());
        try {
            assertThrows(IllegalArgumentException.class,
                    () -> service.generate(new AudioGenerationRequest("test", 0.2, 0), ProgressListener.NONE));
            assertThrows(IllegalArgumentException.class,
                    () -> service.generate(new AudioGenerationRequest("test", 0.2, -1), ProgressListener.NONE));
            assertFalse(Files.exists(modelRoot));
        } finally {
            service.close();
        }
    }

    @Test
    void checksCancellationBeforeStartingDownloads() {
        Path modelRoot = unusedModelRoot();
        MusicGenSmallService service = new MusicGenSmallService(new ModelStore(modelRoot), new HuggingFaceDownloader());
        try {
            assertThrows(CancellationException.class,
                    () -> service.generate(new AudioGenerationRequest("test", 0.2, 1), cancelled()));
            assertFalse(Files.exists(modelRoot));
        } finally {
            service.close();
        }
    }

    @Test
    void generatesStructuredDeterministicOneSecondAudio() throws ReflectiveOperationException {
        Assumptions.assumeTrue(Boolean.getBoolean("stttts.integration"));
        ModelStore store = new ModelStore(ModelStore.defaultRoot());
        GpuRuntime.initialize(store, true);
        assertTrue(GpuRuntime.isActive(), "DirectML must be active for the GPU integration test");
        assertTrue(GpuRuntime.isEnabled(), "DirectML must be enabled for the GPU integration test");
        MusicGenSmallService service = new MusicGenSmallService(store, new HuggingFaceDownloader());
        try {
            AudioGenerationRequest firstRequest = new AudioGenerationRequest(
                    "A mellow piano melody with soft strings", 1, 42);
            AudioClip first = service.generate(firstRequest, ProgressListener.NONE);
            AudioClip repeated = service.generate(firstRequest, ProgressListener.NONE);
            AudioClip differentSeed = service.generate(new AudioGenerationRequest(
                    firstRequest.prompt(), firstRequest.durationSeconds(), 43), ProgressListener.NONE);

            assertEquals(32_000, first.sampleRate());
            assertEquals(32_000, first.samples().length);
            assertEquals(32_000, repeated.sampleRate());
            assertEquals(32_000, repeated.samples().length);
            if (!GpuRuntime.isEnabled()) {
                assertArrayEquals(first.samples(), repeated.samples(), 0);
            }
            assertFalse(Arrays.equals(first.samples(), differentSeed.samples()),
                    "Different seeds should produce different music");
            assertStructuredAudio(first);
            assertStructuredAudio(repeated);
            assertStructuredAudio(differentSeed);
            assertMusicGenSessionsUseGpu(service);
        } finally {
            service.close();
        }
    }

    private static void assertMusicGenSessionsUseGpu(MusicGenSmallService service) throws ReflectiveOperationException {
        var engineField = MusicGenSmallService.class.getDeclaredField("engine");
        engineField.setAccessible(true);
        Object engine = engineField.get(service);
        for (String name : new String[] {"textEncoder", "decoder", "audioDecoder"}) {
            var sessionField = engine.getClass().getDeclaredField(name);
            sessionField.setAccessible(true);
            assertTrue(Onnx.isAccelerated((OrtSession) sessionField.get(engine)),
                    "MusicGen " + name + " session should use DirectML");
        }
    }

    private static void assertStructuredAudio(AudioClip audio) {
        float[] samples = audio.samples();
        double energy = 0;
        int nonSilentWindows = 0;
        double minimumWindowRms = Double.POSITIVE_INFINITY;
        double maximumWindowRms = 0;
        int windowSize = audio.sampleRate() / 10;
        for (int window = 0; window < 10; window++) {
            double windowEnergy = 0;
            for (int i = window * windowSize; i < (window + 1) * windowSize; i++) {
                float sample = samples[i];
                assertTrue(Float.isFinite(sample));
                assertTrue(Math.abs(sample) <= 1);
                energy += sample * (double) sample;
                windowEnergy += sample * (double) sample;
            }
            double windowRms = Math.sqrt(windowEnergy / windowSize);
            minimumWindowRms = Math.min(minimumWindowRms, windowRms);
            maximumWindowRms = Math.max(maximumWindowRms, windowRms);
            if (windowRms > 0.001) {
                nonSilentWindows++;
            }
        }
        assertTrue(Math.sqrt(energy / samples.length) > 0.02, "Audio should have meaningful overall energy");
        assertTrue(nonSilentWindows >= 8, "Audio should contain sound across most 100 ms windows");
        assertTrue(maximumWindowRms - minimumWindowRms > 1e-4,
                "Windowed energy should vary rather than produce a constant signal");
    }

    private static ProgressListener cancelled() {
        AtomicBoolean cancelled = new AtomicBoolean(true);
        return new ProgressListener() {
            @Override
            public void update(String message, double fraction) {
            }

            @Override
            public boolean isCancelled() {
                return cancelled.get();
            }
        };
    }

    private static Path unusedModelRoot() {
        return Path.of("target", "musicgen-test-" + java.util.UUID.randomUUID());
    }
}
