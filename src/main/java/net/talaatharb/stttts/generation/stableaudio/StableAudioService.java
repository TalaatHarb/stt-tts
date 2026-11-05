package net.talaatharb.stttts.generation.stableaudio;

import java.io.IOException;

import net.talaatharb.stttts.audio.AudioClip;
import net.talaatharb.stttts.model.ProgressListener;
import net.talaatharb.stttts.service.AudioGenerationRequest;
import net.talaatharb.stttts.service.AudioGenerationService;

/** One of the official small Stable Audio 3 diffusion models. */
public final class StableAudioService implements AudioGenerationService {
    private final StableAudioModels models;
    private final boolean music;

    public StableAudioService(StableAudioModels models, boolean music) {
        this.models = models;
        this.music = music;
    }

    @Override
    public String id() {
        return music ? "stable-audio-3-music" : "stable-audio-3-sfx";
    }

    @Override
    public String displayName() {
        return music ? "Stable Audio 3 Small Music" : "Stable Audio 3 Small SFX";
    }

    @Override
    public String description() {
        return "Powered by Stability AI. English prompt-to-" + (music ? "music" : "sound-effects")
                + ", 44.1 kHz (stereo downmixed to mono), 1-10 s, 8 diffusion steps. "
                + "~1.7 GB download; another ~0.9 GB for the other variant. Sessions reload per generation "
                + "to limit memory use; CPU can take minutes. "
                + "Stability AI Community License (commercial registration and revenue restrictions) "
                + "plus Gemma Terms of Use for the text encoder. Experimental official ONNX exports.";
    }

    @Override
    public String licenseName() {
        return "Stability AI Community License + Gemma Terms of Use";
    }

    @Override
    public String licenseUrl() {
        return "https://huggingface.co/stabilityai/stable-audio-3-optimized/tree/" + StableAudioModels.REVISION;
    }

    @Override
    public double maxDurationSeconds() {
        return 10;
    }

    @Override
    public boolean isInstalled() {
        return models.isInstalled(music);
    }

    @Override
    public void install(ProgressListener progress) throws IOException {
        models.install(music, progress);
    }

    @Override
    public AudioClip generate(AudioGenerationRequest request, ProgressListener progress) {
        return models.generate(music, request, progress);
    }

    @Override
    public void close() {
        models.close();
    }
}
