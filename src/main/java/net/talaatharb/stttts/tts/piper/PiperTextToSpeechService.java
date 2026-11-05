package net.talaatharb.stttts.tts.piper;

import java.io.IOException;
import java.nio.FloatBuffer;
import java.nio.LongBuffer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.inject.Inject;
import com.google.inject.Singleton;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import net.talaatharb.stttts.audio.AudioClip;
import net.talaatharb.stttts.model.HuggingFaceDownloader;
import net.talaatharb.stttts.model.HuggingFaceDownloader.RemoteFile;
import net.talaatharb.stttts.model.ModelStore;
import net.talaatharb.stttts.model.Onnx;
import net.talaatharb.stttts.model.ProgressListener;
import net.talaatharb.stttts.service.Language;
import net.talaatharb.stttts.service.SpeechRequest;
import net.talaatharb.stttts.service.SpeechServiceException;
import net.talaatharb.stttts.service.TextToSpeechService;
import net.talaatharb.stttts.service.Voice;
import net.talaatharb.stttts.tts.kokoro.EnglishG2P;
import net.talaatharb.stttts.tts.kokoro.KokoroTextToSpeechService;
import net.talaatharb.stttts.tts.supertonic.SupertonicText;

/**
 * English text to speech with Piper (MIT) VITS voices on ONNX Runtime. Piper normally needs the native espeak-ng
 * phonemizer; here the pure Java misaki {@link EnglishG2P} is used and its output converted to espeak IPA.
 */
@Singleton
public class PiperTextToSpeechService implements TextToSpeechService {
    static final String REPO = "rhasspy/piper-voices";
    private static final String DEFAULT_VOICE = "en_US-lessac-medium";
    static final Language AMERICAN = new Language("en-us", "English (US)");
    static final Language BRITISH = new Language("en-gb", "English (UK)");
    private static final String[][] VOICES = {{"en_US-lessac-medium", "Lessac", "F"},
        {"en_US-amy-medium", "Amy", "F"}, {"en_US-kristin-medium", "Kristin", "F"},
        {"en_US-hfc_female-medium", "HFC female", "F"}, {"en_US-bryce-medium", "Bryce", "M"},
        {"en_US-joe-medium", "Joe", "M"}, {"en_US-john-medium", "John", "M"}, {"en_US-kusal-medium", "Kusal", "M"},
        {"en_US-hfc_male-medium", "HFC male", "M"}, {"en_GB-alba-medium", "Alba", "F"},
        {"en_GB-cori-medium", "Cori", "F"}, {"en_GB-jenny_dioco-medium", "Jenny", "F"},
        {"en_GB-alan-medium", "Alan", "M"}, {"en_GB-northern_english_male-medium", "Northern English male", "M"}};

    private final HuggingFaceDownloader downloader;
    private final Path directory;
    private final Path dictionaryDirectory;
    private final Map<String, EnglishG2P> g2p = new HashMap<>();
    private String loadedVoice;
    private OrtSession session;
    private PiperConfig config;

    /** The parts of a voice's {@code .onnx.json} needed for inference. */
    record PiperConfig(int sampleRate, float noiseScale, float lengthScale, float noiseW, int speakers,
            Map<String, Long> phonemeIds) {
        static PiperConfig read(Path file) throws IOException {
            JsonNode json = new ObjectMapper().readTree(file.toFile());
            Map<String, Long> ids = new HashMap<>();
            Iterator<Map.Entry<String, JsonNode>> fields = json.path("phoneme_id_map").fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                ids.put(field.getKey(), field.getValue().get(0).asLong());
            }
            JsonNode inference = json.path("inference");
            return new PiperConfig(json.path("audio").path("sample_rate").asInt(22_050),
                    (float) inference.path("noise_scale").asDouble(0.667),
                    (float) inference.path("length_scale").asDouble(1),
                    (float) inference.path("noise_w").asDouble(0.8), json.path("num_speakers").asInt(1), ids);
        }
    }

    @Inject
    public PiperTextToSpeechService(ModelStore store, HuggingFaceDownloader downloader) {
        this.downloader = downloader;
        this.directory = store.directory("piper");
        // Shares the misaki dictionaries with Kokoro so they are only downloaded once.
        this.dictionaryDirectory = store.directory("kokoro-82m");
    }

    @Override
    public String id() {
        return "piper";
    }

    @Override
    public String displayName() {
        return "Piper (English)";
    }

    @Override
    public String description() {
        return "Rhasspy's fast VITS voices (MIT), lightweight even on old CPUs. ~60 MB per voice.";
    }

    @Override
    public List<Language> languages() {
        return List.of(AMERICAN, BRITISH);
    }

    @Override
    public List<Voice> voices() {
        List<Voice> voices = new ArrayList<>();
        for (String[] voice : VOICES) {
            boolean british = voice[0].startsWith("en_GB");
            voices.add(new Voice(voice[0], voice[1] + (british ? " (UK " : " (US ")
                    + (voice[2].equals("F") ? "female)" : "male)"), british ? BRITISH.code() : AMERICAN.code(),
                    voice[2]));
        }
        return voices;
    }

    static String repositoryPath(String voice) {
        String[] parts = voice.split("-");
        String locale = parts[0];
        return locale.substring(0, 2) + "/" + locale + "/" + parts[1] + "/" + parts[2] + "/" + voice;
    }

    private List<RemoteFile> voiceFiles(String voice) {
        String path = repositoryPath(voice);
        return List.of(new RemoteFile(REPO, path + ".onnx", directory.resolve(voice + ".onnx")),
                new RemoteFile(REPO, path + ".onnx.json", directory.resolve(voice + ".onnx.json")));
    }

    private List<RemoteFile> files(String voice) {
        List<RemoteFile> files = new ArrayList<>(voiceFiles(voice));
        files.addAll(KokoroTextToSpeechService.dictionaryFiles(dictionaryDirectory, voice.startsWith("en_GB")));
        return files;
    }

    @Override
    public boolean isInstalled() {
        return HuggingFaceDownloader.allPresent(files(DEFAULT_VOICE));
    }

    @Override
    public void install(ProgressListener progress) throws IOException {
        downloader.ensure(files(DEFAULT_VOICE), progress);
    }

    @Override
    public synchronized AudioClip synthesize(SpeechRequest request, ProgressListener progress) {
        String voice = request.voiceId() == null ? DEFAULT_VOICE : request.voiceId();
        if (voices().stream().noneMatch(v -> v.id().equals(voice))) {
            throw new IllegalArgumentException("Unknown Piper voice '" + voice + "'");
        }
        boolean british = request.language() != null ? BRITISH.code().equals(request.language())
                : voice.startsWith("en_GB");
        try {
            List<RemoteFile> needed = new ArrayList<>(files(voice));
            needed.addAll(KokoroTextToSpeechService.dictionaryFiles(dictionaryDirectory, british));
            downloader.ensure(needed, progress.slice(0, 0.3));
            progress.update("Loading Piper...", -1);
            load(voice);
            EnglishG2P phonemizer = phonemizer(british);
            List<String> chunks = SupertonicText.chunk(request.text(), 300);
            List<AudioClip> clips = new ArrayList<>();
            for (int i = 0; i < chunks.size(); i++) {
                progress.checkCancelled();
                progress.update(String.format("Synthesizing part %d of %d", i + 1, chunks.size()),
                        0.3 + 0.7 * i / chunks.size());
                String phonemes = PiperPhonemes.fromMisaki(phonemizer.phonemize(chunks.get(i)), british);
                long[] ids = PiperPhonemes.toIds(phonemes, config.phonemeIds());
                if (ids.length > 3) {
                    clips.add(infer(ids, request.speed()));
                }
            }
            if (clips.isEmpty()) {
                throw new SpeechServiceException("Nothing to synthesize: the text contains no pronounceable words");
            }
            progress.update("Done", 1);
            return AudioClip.concat(config.sampleRate(), 0.2, clips);
        } catch (IOException | OrtException e) {
            throw new SpeechServiceException("Piper synthesis failed: " + e.getMessage(), e);
        }
    }

    private void load(String voice) throws IOException, OrtException {
        if (voice.equals(loadedVoice) && session != null) {
            return;
        }
        Onnx.closeQuietly(session);
        session = null;
        loadedVoice = null;
        config = PiperConfig.read(directory.resolve(voice + ".onnx.json"));
        session = Onnx.open(directory.resolve(voice + ".onnx"));
        loadedVoice = voice;
    }

    private AudioClip infer(long[] ids, float speed) throws OrtException {
        OrtEnvironment env = Onnx.env();
        float[] scales = {config.noiseScale(), config.lengthScale() / speed, config.noiseW()};
        Map<String, OnnxTensor> inputs = new HashMap<>();
        try (OnnxTensor input = OnnxTensor.createTensor(env, LongBuffer.wrap(ids), new long[] {1, ids.length});
                OnnxTensor lengths = OnnxTensor.createTensor(env, new long[] {ids.length});
                OnnxTensor scalesTensor = OnnxTensor.createTensor(env, scales);
                OnnxTensor speaker = OnnxTensor.createTensor(env, new long[] {0})) {
            inputs.put("input", input);
            inputs.put("input_lengths", lengths);
            inputs.put("scales", scalesTensor);
            if (session.getInputNames().contains("sid")) {
                inputs.put("sid", speaker);
            }
            try (OrtSession.Result result = session.run(inputs)) {
                FloatBuffer buffer = ((OnnxTensor) result.get(0)).getFloatBuffer();
                float[] audio = new float[buffer.remaining()];
                buffer.get(audio);
                return new AudioClip(audio, config.sampleRate());
            }
        }
    }

    private EnglishG2P phonemizer(boolean british) throws IOException {
        String key = british ? "gb" : "us";
        EnglishG2P loaded = g2p.get(key);
        if (loaded == null) {
            loaded = EnglishG2P.load(dictionaryDirectory.resolve(key + "_gold.json"),
                    dictionaryDirectory.resolve(key + "_silver.json"));
            g2p.put(key, loaded);
        }
        return loaded;
    }

    @Override
    public synchronized void close() {
        Onnx.closeQuietly(session);
        session = null;
        loadedVoice = null;
    }
}
