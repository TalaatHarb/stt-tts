package net.talaatharb.stttts.tts.kokoro;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.LongBuffer;
import java.nio.file.Files;
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
import net.talaatharb.stttts.tts.supertonic.SupertonicText;

/**
 * English text to speech with Kokoro-82M (Apache 2.0) running on ONNX Runtime.
 *
 * <p>Uses the 8-bit quantized ONNX export (~92 MB) and the pure Java {@link EnglishG2P} phonemizer.
 */
@Singleton
public class KokoroTextToSpeechService implements TextToSpeechService {
    static final String REPO = "onnx-community/Kokoro-82M-v1.0-ONNX";
    static final String MISAKI = "https://raw.githubusercontent.com/hexgrad/misaki/main/misaki/data/";
    static final int SAMPLE_RATE = 24_000;
    static final int MAX_PHONEMES = 510;
    private static final int STYLE_DIM = 256;
    private static final String DEFAULT_VOICE = "af_heart";
    static final Language AMERICAN = new Language("en-us", "English (US)");
    static final Language BRITISH = new Language("en-gb", "English (UK)");
    private static final String[][] VOICES = {{"af_heart", "Heart"}, {"af_bella", "Bella"},
        {"af_nicole", "Nicole"}, {"af_sarah", "Sarah"}, {"af_sky", "Sky"}, {"af_nova", "Nova"},
        {"af_alloy", "Alloy"}, {"af_aoede", "Aoede"}, {"af_jessica", "Jessica"}, {"af_kore", "Kore"},
        {"af_river", "River"}, {"am_adam", "Adam"}, {"am_michael", "Michael"}, {"am_echo", "Echo"},
        {"am_eric", "Eric"}, {"am_fenrir", "Fenrir"}, {"am_liam", "Liam"}, {"am_onyx", "Onyx"},
        {"am_puck", "Puck"}, {"bf_emma", "Emma"}, {"bf_alice", "Alice"}, {"bf_isabella", "Isabella"},
        {"bf_lily", "Lily"}, {"bm_george", "George"}, {"bm_daniel", "Daniel"}, {"bm_fable", "Fable"},
        {"bm_lewis", "Lewis"}};

    private final HuggingFaceDownloader downloader;
    private final Path directory;
    private final Map<String, float[]> voiceCache = new HashMap<>();
    private final Map<String, EnglishG2P> g2p = new HashMap<>();
    private Map<Character, Long> vocab;
    private OrtSession session;

    @Inject
    public KokoroTextToSpeechService(ModelStore store, HuggingFaceDownloader downloader) {
        this.downloader = downloader;
        this.directory = store.directory("kokoro-82m");
    }

    @Override
    public String id() {
        return "kokoro";
    }

    @Override
    public String displayName() {
        return "Kokoro-82M (English)";
    }

    @Override
    public String description() {
        return "Hexgrad's 82M parameter English model (Apache 2.0), natural and fast on CPU. ~100 MB download.";
    }

    @Override
    public List<Language> languages() {
        return List.of(AMERICAN, BRITISH);
    }

    @Override
    public List<Voice> voices() {
        List<Voice> voices = new ArrayList<>();
        for (String[] voice : VOICES) {
            boolean british = voice[0].startsWith("b");
            boolean female = voice[0].charAt(1) == 'f';
            voices.add(new Voice(voice[0], voice[1] + (british ? " (UK " : " (US ") + (female ? "female)" : "male)"),
                    british ? BRITISH.code() : AMERICAN.code(), female ? "F" : "M"));
        }
        return voices;
    }

    private List<RemoteFile> baseFiles() {
        return List.of(new RemoteFile(REPO, "onnx/model_quantized.onnx", directory.resolve("model_quantized.onnx")),
                new RemoteFile(REPO, "tokenizer.json", directory.resolve("tokenizer.json")));
    }

    private List<RemoteFile> dictionaryFiles(boolean british) {
        return dictionaryFiles(directory, british);
    }

    /** The misaki pronunciation dictionaries used by {@link EnglishG2P}, stored in the given directory. */
    public static List<RemoteFile> dictionaryFiles(Path directory, boolean british) {
        String prefix = british ? "gb" : "us";
        return List.of(RemoteFile.url(MISAKI + prefix + "_gold.json", directory.resolve(prefix + "_gold.json")),
                RemoteFile.url(MISAKI + prefix + "_silver.json", directory.resolve(prefix + "_silver.json")));
    }

    private RemoteFile voiceFile(String voice) {
        return new RemoteFile(REPO, "voices/" + voice + ".bin", directory.resolve("voices").resolve(voice + ".bin"));
    }

    private List<RemoteFile> files(String voice) {
        List<RemoteFile> files = new ArrayList<>(baseFiles());
        files.addAll(dictionaryFiles(voice.startsWith("b")));
        files.add(voiceFile(voice));
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
            throw new IllegalArgumentException("Unknown Kokoro voice '" + voice + "'");
        }
        boolean british = request.language() != null ? BRITISH.code().equals(request.language())
                : voice.startsWith("b");
        try {
            List<RemoteFile> needed = new ArrayList<>(files(voice));
            needed.addAll(dictionaryFiles(british));
            downloader.ensure(needed, progress.slice(0, 0.2));
            progress.update("Loading Kokoro...", -1);
            OrtSession model = session();
            EnglishG2P phonemizer = phonemizer(british);
            float[] styles = voice(voice);
            List<String> chunks = new ArrayList<>();
            for (String part : SupertonicText.chunk(request.text(), 300)) {
                splitPhonemes(phonemizer.phonemize(part), chunks);
            }
            List<AudioClip> clips = new ArrayList<>();
            for (int i = 0; i < chunks.size(); i++) {
                progress.checkCancelled();
                progress.update(String.format("Synthesizing part %d of %d", i + 1, chunks.size()),
                        0.2 + 0.8 * i / chunks.size());
                long[] ids = tokenize(chunks.get(i));
                if (ids.length > 0) {
                    clips.add(infer(model, ids, styles, request.speed()));
                }
            }
            if (clips.isEmpty()) {
                throw new SpeechServiceException("Nothing to synthesize: the text contains no pronounceable words");
            }
            progress.update("Done", 1);
            return AudioClip.concat(SAMPLE_RATE, 0.15, clips);
        } catch (IOException | OrtException e) {
            throw new SpeechServiceException("Kokoro synthesis failed: " + e.getMessage(), e);
        }
    }

    /** Splits a phoneme string into pieces that fit the model's 510 token context, preferring word boundaries. */
    static void splitPhonemes(String phonemes, List<String> out) {
        String rest = phonemes.trim();
        while (rest.length() > MAX_PHONEMES) {
            int cut = rest.lastIndexOf(' ', MAX_PHONEMES);
            if (cut <= 0) {
                cut = MAX_PHONEMES;
            }
            out.add(rest.substring(0, cut).trim());
            rest = rest.substring(cut).trim();
        }
        if (!rest.isEmpty()) {
            out.add(rest);
        }
    }

    long[] tokenize(String phonemes) throws IOException {
        Map<Character, Long> table = vocab();
        long[] ids = new long[phonemes.length()];
        int count = 0;
        for (char c : phonemes.toCharArray()) {
            Long id = table.get(c);
            if (id != null) {
                ids[count++] = id;
            }
        }
        return java.util.Arrays.copyOf(ids, count);
    }

    private AudioClip infer(OrtSession model, long[] ids, float[] styles, float speed) throws OrtException {
        OrtEnvironment env = Onnx.env();
        long[] padded = new long[ids.length + 2];
        System.arraycopy(ids, 0, padded, 1, ids.length);
        int row = Math.min(ids.length, styles.length / STYLE_DIM - 1);
        float[] style = new float[STYLE_DIM];
        System.arraycopy(styles, row * STYLE_DIM, style, 0, STYLE_DIM);
        try (OnnxTensor input = OnnxTensor.createTensor(env, LongBuffer.wrap(padded), new long[] {1, padded.length});
                OnnxTensor styleTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(style),
                        new long[] {1, STYLE_DIM});
                OnnxTensor speedTensor = OnnxTensor.createTensor(env, new float[] {speed});
                OrtSession.Result result = model.run(
                        Map.of("input_ids", input, "style", styleTensor, "speed", speedTensor))) {
            FloatBuffer buffer = ((OnnxTensor) result.get(0)).getFloatBuffer();
            float[] audio = new float[buffer.remaining()];
            buffer.get(audio);
            return new AudioClip(audio, SAMPLE_RATE);
        }
    }

    private OrtSession session() throws OrtException {
        if (session == null) {
            session = Onnx.open(directory.resolve("model_quantized.onnx"));
        }
        return session;
    }

    private EnglishG2P phonemizer(boolean british) throws IOException {
        String key = british ? "gb" : "us";
        EnglishG2P loaded = g2p.get(key);
        if (loaded == null) {
            loaded = EnglishG2P.load(directory.resolve(key + "_gold.json"), directory.resolve(key + "_silver.json"));
            g2p.put(key, loaded);
        }
        return loaded;
    }

    private Map<Character, Long> vocab() throws IOException {
        if (vocab == null) {
            vocab = readVocab(directory.resolve("tokenizer.json"));
        }
        return vocab;
    }

    static Map<Character, Long> readVocab(Path tokenizer) throws IOException {
        JsonNode node = new ObjectMapper().readTree(tokenizer.toFile()).path("model").path("vocab");
        Map<Character, Long> table = new HashMap<>();
        Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            if (entry.getKey().length() == 1) {
                table.put(entry.getKey().charAt(0), entry.getValue().asLong());
            }
        }
        table.remove('$');
        return table;
    }

    private float[] voice(String voice) throws IOException {
        float[] cached = voiceCache.get(voice);
        if (cached == null) {
            byte[] bytes = Files.readAllBytes(voiceFile(voice).target());
            FloatBuffer floats = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer();
            cached = new float[floats.remaining()];
            floats.get(cached);
            voiceCache.put(voice, cached);
        }
        return cached;
    }

    @Override
    public synchronized void close() {
        Onnx.closeQuietly(session);
        session = null;
    }
}
