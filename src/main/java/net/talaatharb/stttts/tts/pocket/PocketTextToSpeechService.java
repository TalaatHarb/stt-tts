package net.talaatharb.stttts.tts.pocket;

import java.io.IOException;
import java.util.List;
import java.util.Random;

import com.google.inject.Inject;
import com.google.inject.Singleton;

import ai.onnxruntime.OrtException;
import net.talaatharb.stttts.audio.AudioClip;
import net.talaatharb.stttts.model.ProgressListener;
import net.talaatharb.stttts.service.Language;
import net.talaatharb.stttts.service.SpeechRequest;
import net.talaatharb.stttts.service.SpeechServiceException;
import net.talaatharb.stttts.service.TextToSpeechService;
import net.talaatharb.stttts.service.Voice;
import net.talaatharb.stttts.tts.pocket.PocketModels.PocketLanguage;

/** Multilingual text to speech with Kyutai's Pocket TTS (~100M parameters, CC-BY 4.0) and its 27 stock voices. */
@Singleton
public class PocketTextToSpeechService implements TextToSpeechService {
    private final PocketModels models;
    private final Random random = new Random();

    @Inject
    public PocketTextToSpeechService(PocketModels models) {
        this.models = models;
    }

    @Override
    public String id() {
        return "pocket";
    }

    @Override
    public String displayName() {
        return "Pocket TTS (EN, DE, ES, IT, PT, FR)";
    }

    @Override
    public String description() {
        return "Kyutai's ~100M parameter streaming model (CC-BY 4.0), runs faster than real time on a CPU. "
                + "~120 MB per language (French ~340 MB). Speed is not adjustable.";
    }

    @Override
    public List<Language> languages() {
        return PocketModels.languages();
    }

    @Override
    public List<Voice> voices() {
        return PocketModels.voices();
    }

    @Override
    public boolean isInstalled() {
        return models.isInstalled(false);
    }

    @Override
    public void install(ProgressListener progress) throws IOException {
        models.install(false, progress);
    }

    @Override
    public AudioClip synthesize(SpeechRequest request, ProgressListener progress) {
        String voice = request.voiceId() == null ? PocketModels.DEFAULT_VOICE : request.voiceId();
        if (voices().stream().noneMatch(v -> v.id().equals(voice))) {
            throw new IllegalArgumentException("Unknown Pocket TTS voice '" + voice + "'");
        }
        PocketLanguage language = PocketModels.language(request.language());
        synchronized (models) {
            try {
                PocketModel model = models.model(language, false, progress.slice(0, 0.2));
                progress.update("Loading Pocket TTS voice...", -1);
                try (PocketModel.VoiceState state = models.voice(model, language, voice, progress.slice(0.2, 0.25))) {
                    AudioClip clip = model.generate(request.text(), state, random, progress.slice(0.25, 1));
                    progress.update("Done", 1);
                    return clip;
                }
            } catch (IOException | OrtException e) {
                throw new SpeechServiceException("Pocket TTS synthesis failed: " + e.getMessage(), e);
            }
        }
    }

    @Override
    public void close() {
        models.close();
    }
}
