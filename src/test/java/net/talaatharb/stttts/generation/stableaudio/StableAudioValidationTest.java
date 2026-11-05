package net.talaatharb.stttts.generation.stableaudio;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CancellationException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.talaatharb.stttts.model.HuggingFaceDownloader;
import net.talaatharb.stttts.model.ModelStore;
import net.talaatharb.stttts.model.ProgressListener;
import net.talaatharb.stttts.service.AudioGenerationRequest;

class StableAudioValidationTest {
    @TempDir Path temp;

    private StableAudioModels models() {
        return new StableAudioModels(new ModelStore(temp), new HuggingFaceDownloader() {
            @Override
            public void ensure(List<RemoteFile> files, ProgressListener progress) {
                fail("Invalid or cancelled requests must not download models");
            }
        });
    }

    @Test
    void rejectsInvalidRequestsBeforeDownloading() {
        StableAudioModels models = models();
        assertThrows(IllegalArgumentException.class, () ->
                models.generate(false, new AudioGenerationRequest("rain", 11, 42), ProgressListener.NONE));
        assertThrows(IllegalArgumentException.class, () ->
                models.generate(true, new AudioGenerationRequest("piano", 0.5, 42), ProgressListener.NONE));
        assertThrows(IllegalArgumentException.class, () -> new AudioGenerationRequest(" ", 1, 42));
        assertThrows(IllegalArgumentException.class, () -> new AudioGenerationRequest("piano", Double.NaN, 42));
    }

    @Test
    void cancelledRequestsNeverDownload() {
        ProgressListener cancelled = new ProgressListener() {
            @Override public void update(String message, double fraction) {
            }
            @Override public boolean isCancelled() {
                return true;
            }
        };
        assertThrows(CancellationException.class, () ->
                models().generate(false, new AudioGenerationRequest("rain", 1, 42), cancelled));
    }
}
