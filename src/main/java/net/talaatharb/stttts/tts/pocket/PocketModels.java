package net.talaatharb.stttts.tts.pocket;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.google.inject.Inject;
import com.google.inject.Singleton;

import ai.onnxruntime.OrtException;
import net.talaatharb.stttts.model.HuggingFaceDownloader;
import net.talaatharb.stttts.model.HuggingFaceDownloader.RemoteFile;
import net.talaatharb.stttts.model.ModelStore;
import net.talaatharb.stttts.model.ProgressListener;
import net.talaatharb.stttts.service.Language;
import net.talaatharb.stttts.service.Voice;

/**
 * Downloads and caches Kyutai's Pocket TTS (CC-BY 4.0) ONNX bundles, shared by the text-to-speech and the
 * voice-cloning services so a language is only loaded once.
 */
@Singleton
public class PocketModels implements AutoCloseable {
    static final String REPO = "KevinAHM/pocket-tts-onnx";
    static final String VOICE_REPO = "kyutai/pocket-tts-without-voice-cloning";
    static final String VOICE_REVISION = "a7056f505a4a8ca72d8b5fb1440071d6f1a35739";
    public static final String DEFAULT_VOICE = "alba";
    static final List<PocketLanguage> LANGUAGES = List.of(
            new PocketLanguage("en", "English", "english_2026-04"),
            new PocketLanguage("de", "German", "german"),
            new PocketLanguage("es", "Spanish", "spanish"),
            new PocketLanguage("it", "Italian", "italian"),
            new PocketLanguage("pt", "Portuguese", "portuguese"),
            new PocketLanguage("fr", "French", "french_24l"));
    private static final String[][] VOICES = {{"alba", "F"}, {"anna", "F"}, {"azelma", "F"},
        {"bill_boerst", "M"}, {"caro_davy", "F"}, {"charles", "M"}, {"cosette", "F"}, {"daan", "M"},
        {"eponine", "F"}, {"estelle", "F"}, {"eve", "F"}, {"fantine", "F"}, {"george", "M"}, {"giovanni", "M"},
        {"jane", "F"}, {"javert", "M"}, {"jean", "M"}, {"juergen", "M"}, {"lola", "F"}, {"marius", "M"},
        {"mary", "F"}, {"michael", "M"}, {"paul", "M"}, {"peter_yearsley", "M"}, {"rafael", "M"},
        {"stuart_bell", "M"}, {"vera", "F"}};
    private static final String[] GENERATOR_FILES = {"bundle.json", "tokenizer.model", "bos_before_voice.npy",
        "text_conditioner.onnx", "flow_lm_main_int8.onnx", "flow_lm_flow_int8.onnx", "mimi_decoder_int8.onnx"};
    private static final String ENCODER_FILE = "mimi_encoder.onnx";

    /** A supported language and the ONNX bundle that speaks it. */
    public record PocketLanguage(String code, String name, String bundle) {
        public Language language() {
            return new Language(code, name);
        }
    }

    private final HuggingFaceDownloader downloader;
    private final Path directory;
    private final Map<String, PocketModel> models = new HashMap<>();

    @Inject
    public PocketModels(ModelStore store, HuggingFaceDownloader downloader) {
        this.downloader = downloader;
        this.directory = store.directory("pocket-tts");
    }

    public static List<Language> languages() {
        return LANGUAGES.stream().map(PocketLanguage::language).toList();
    }

    public static List<Voice> voices() {
        List<Voice> voices = new ArrayList<>();
        for (String[] voice : VOICES) {
            String name = Character.toUpperCase(voice[0].charAt(0)) + voice[0].substring(1).replace('_', ' ');
            voices.add(new Voice(voice[0], name + (voice[1].equals("F") ? " (female)" : " (male)"), null, voice[1]));
        }
        return voices;
    }

    static PocketLanguage language(String code) {
        if (code == null) {
            return LANGUAGES.get(0);
        }
        return LANGUAGES.stream().filter(l -> l.code().equalsIgnoreCase(code)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Pocket TTS doesn't speak '" + code + "'"));
    }

    private Path bundleDirectory(PocketLanguage language) {
        return directory.resolve(language.bundle());
    }

    List<RemoteFile> files(PocketLanguage language, boolean encoder) {
        List<RemoteFile> files = new ArrayList<>();
        for (String name : GENERATOR_FILES) {
            files.add(new RemoteFile(REPO, "onnx/" + language.bundle() + "/" + name,
                    bundleDirectory(language).resolve(name)));
        }
        if (encoder) {
            files.add(new RemoteFile(REPO, "onnx/" + language.bundle() + "/" + ENCODER_FILE,
                    bundleDirectory(language).resolve(ENCODER_FILE)));
        }
        return files;
    }

    RemoteFile voiceFile(PocketLanguage language, String voice) {
        return new RemoteFile(VOICE_REPO, VOICE_REVISION,
                "languages/" + language.bundle() + "/embeddings/" + voice + ".safetensors",
                bundleDirectory(language).resolve("voices").resolve(voice + ".safetensors"));
    }

    public boolean isInstalled(boolean encoder) {
        PocketLanguage english = LANGUAGES.get(0);
        List<RemoteFile> files = files(english, encoder);
        if (!encoder) {
            files.add(voiceFile(english, DEFAULT_VOICE));
        }
        return HuggingFaceDownloader.allPresent(files);
    }

    /** Downloads the English bundle (plus the default voice, or the encoder for cloning). */
    public void install(boolean encoder, ProgressListener progress) throws IOException {
        PocketLanguage english = LANGUAGES.get(0);
        List<RemoteFile> files = files(english, encoder);
        if (!encoder) {
            files.add(voiceFile(english, DEFAULT_VOICE));
        }
        downloader.ensure(files, progress);
    }

    /** Downloads what the language needs and returns its (cached) model. */
    PocketModel model(PocketLanguage language, boolean encoder, ProgressListener progress) throws IOException {
        downloader.ensure(files(language, encoder), progress);
        synchronized (models) {
            PocketModel model = models.get(language.bundle());
            if (model == null) {
                model = PocketModel.load(bundleDirectory(language));
                models.put(language.bundle(), model);
            }
            return model;
        }
    }

    /** Downloads the language's bundle including the Mimi encoder and returns its (cached) model. */
    public PocketModel cloningModel(String languageCode, ProgressListener progress) throws IOException {
        return model(language(languageCode), true, progress);
    }

    /** Downloads (if needed) and loads one of the predefined voices for a language. */
    PocketModel.VoiceState voice(PocketModel model, PocketLanguage language, String voice,
            ProgressListener progress) throws IOException, OrtException {
        RemoteFile file = voiceFile(language, voice);
        downloader.ensure(List.of(file), progress);
        return model.voiceFromStateFile(file.target());
    }

    @Override
    public void close() {
        synchronized (models) {
            models.values().forEach(PocketModel::close);
            models.clear();
        }
    }
}
