package net.talaatharb.stttts.config;

import java.nio.file.Path;
import java.util.List;

import com.google.inject.AbstractModule;
import com.google.inject.Provides;
import com.google.inject.Singleton;

import net.talaatharb.stttts.audio.AudioPlayer;
import net.talaatharb.stttts.audio.AudioRecorder;
import net.talaatharb.stttts.cloning.chatterbox.ChatterboxVoiceCloningService;
import net.talaatharb.stttts.cloning.pocket.PocketVoiceCloningService;
import net.talaatharb.stttts.model.HuggingFaceDownloader;
import net.talaatharb.stttts.model.ModelStore;
import net.talaatharb.stttts.service.SpeechToTextService;
import net.talaatharb.stttts.service.TextToSpeechService;
import net.talaatharb.stttts.service.VoiceCloningService;
import net.talaatharb.stttts.stt.whistle.WhistleSpeechToTextService;
import net.talaatharb.stttts.tts.kokoro.KokoroTextToSpeechService;
import net.talaatharb.stttts.tts.piper.PiperTextToSpeechService;
import net.talaatharb.stttts.tts.pocket.PocketTextToSpeechService;
import net.talaatharb.stttts.tts.supertonic.SupertonicTextToSpeechService;

/** Guice wiring for the application. */
public class AppModule extends AbstractModule {
    private final Path modelsRoot;
    private final AppSettings settings;

    public AppModule() {
        this(ModelStore.defaultRoot(), AppSettings.load());
    }

    public AppModule(Path modelsRoot, AppSettings settings) {
        this.modelsRoot = modelsRoot;
        this.settings = settings;
    }

    @Override
    protected void configure() {
        bind(ModelStore.class).toInstance(new ModelStore(modelsRoot));
        bind(AppSettings.class).toInstance(settings);
        bind(HuggingFaceDownloader.class).in(Singleton.class);
        bind(AudioPlayer.class).in(Singleton.class);
        bind(AudioRecorder.class).in(Singleton.class);
        bind(SpeechToTextService.class).to(WhistleSpeechToTextService.class);
    }

    /** Text to speech engines in the order they are offered in the UI. */
    @Provides
    @Singleton
    List<TextToSpeechService> textToSpeechServices(KokoroTextToSpeechService kokoro,
            SupertonicTextToSpeechService supertonic, PiperTextToSpeechService piper,
            PocketTextToSpeechService pocket) {
        return List.of(kokoro, supertonic, pocket, piper);
    }

    /** Voice cloning engines in the order they are offered in the UI. */
    @Provides
    @Singleton
    List<VoiceCloningService> voiceCloningServices(PocketVoiceCloningService pocket,
            ChatterboxVoiceCloningService chatterbox) {
        return List.of(pocket, chatterbox);
    }
}
