package net.talaatharb.stttts.cloning.pocket;

import java.io.IOException;
import java.util.List;
import java.util.Random;

import com.google.inject.Inject;
import com.google.inject.Singleton;

import ai.onnxruntime.OrtException;
import net.talaatharb.stttts.audio.AudioClip;
import net.talaatharb.stttts.model.ProgressListener;
import net.talaatharb.stttts.service.Language;
import net.talaatharb.stttts.service.SpeechServiceException;
import net.talaatharb.stttts.service.VoiceCloneRequest;
import net.talaatharb.stttts.service.VoiceCloningService;
import net.talaatharb.stttts.tts.pocket.PocketModel;
import net.talaatharb.stttts.tts.pocket.PocketModels;

/**
 * Zero-shot voice cloning with Kyutai's Pocket TTS: the reference is encoded with the Mimi codec and used to prime
 * the language model, then the text is spoken in that voice. Light enough for real-time use on a CPU.
 */
@Singleton
public class PocketVoiceCloningService implements VoiceCloningService {
    static final double MAX_REFERENCE_SECONDS = 30;
    private static final double RECOMMENDED_REFERENCE_SECONDS = 10;

    private final PocketModels models;
    private final Random random = new Random();

    @Inject
    public PocketVoiceCloningService(PocketModels models) {
        this.models = models;
    }

    @Override
    public String id() {
        return "pocket";
    }

    @Override
    public String displayName() {
        return "Pocket TTS (fast, multilingual voice cloning)";
    }

    @Override
    public String description() {
        return "Kyutai's ~100M parameter model (CC-BY 4.0); clones a voice in seconds on a CPU. "
                + "~160 MB per language (French ~380 MB).";
    }

    @Override
    public List<Language> languages() {
        return PocketModels.languages();
    }

    @Override
    public double recommendedReferenceSeconds() {
        return RECOMMENDED_REFERENCE_SECONDS;
    }

    @Override
    public boolean isInstalled() {
        return models.isInstalled(true);
    }

    @Override
    public void install(ProgressListener progress) throws IOException {
        models.install(true, progress);
    }

    @Override
    public AudioClip cloneVoice(VoiceCloneRequest request, ProgressListener progress) {
        synchronized (models) {
            try {
                PocketModel model = models.cloningModel(request.language(), progress.slice(0, 0.2));
                progress.update("Analyzing the reference voice...", -1);
                AudioClip reference = request.reference().truncate(MAX_REFERENCE_SECONDS);
                try (PocketModel.VoiceState state = model.voiceFromAudio(reference)) {
                    AudioClip clip = model.generate(request.text(), state, random, progress.slice(0.25, 1));
                    progress.update("Done", 1);
                    return clip;
                }
            } catch (IOException | OrtException e) {
                throw new SpeechServiceException("Pocket TTS voice cloning failed: " + e.getMessage(), e);
            }
        }
    }

    @Override
    public void close() {
        models.close();
    }
}
