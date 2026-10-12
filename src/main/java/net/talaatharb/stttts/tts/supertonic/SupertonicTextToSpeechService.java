package net.talaatharb.stttts.tts.supertonic;

import java.io.IOException;
import java.nio.FloatBuffer;
import java.nio.LongBuffer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

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

/**
 * Multilingual text to speech with Supertone's Supertonic 3 (~99M parameters, 31 languages).
 *
 * <p>Pipeline: duration predictor, text encoder, a few flow-matching steps of the vector estimator, then the
 * vocoder. Text is fed as Unicode code points, so no phonemizer is required.
 */
@Singleton
public class SupertonicTextToSpeechService implements TextToSpeechService {
    static final String REPO = "Supertone/supertonic-3";
    private static final int DENOISING_STEPS = 8;
    private static final double CHUNK_SILENCE_SECONDS = 0.3;
    private static final String[] VOICE_IDS = {"F1", "F2", "F3", "F4", "F5", "M1", "M2", "M3", "M4", "M5"};
    private static final String[] ONNX_FILES = {"duration_predictor.onnx", "text_encoder.onnx",
        "vector_estimator.onnx", "vocoder.onnx", "tts.json", "unicode_indexer.json"};

    static final List<Language> LANGUAGES = List.of(new Language("en", "English"), new Language("ko", "Korean"),
            new Language("ja", "Japanese"), new Language("ar", "Arabic"), new Language("bg", "Bulgarian"),
            new Language("cs", "Czech"), new Language("da", "Danish"), new Language("de", "German"),
            new Language("el", "Greek"), new Language("es", "Spanish"), new Language("et", "Estonian"),
            new Language("fi", "Finnish"), new Language("fr", "French"), new Language("hi", "Hindi"),
            new Language("hr", "Croatian"), new Language("hu", "Hungarian"), new Language("id", "Indonesian"),
            new Language("it", "Italian"), new Language("lt", "Lithuanian"), new Language("lv", "Latvian"),
            new Language("nl", "Dutch"), new Language("pl", "Polish"), new Language("pt", "Portuguese"),
            new Language("ro", "Romanian"), new Language("ru", "Russian"), new Language("sk", "Slovak"),
            new Language("sl", "Slovenian"), new Language("sv", "Swedish"), new Language("tr", "Turkish"),
            new Language("uk", "Ukrainian"), new Language("vi", "Vietnamese"),
            new Language("na", "Language agnostic"));

    private final HuggingFaceDownloader downloader;
    private final Path directory;
    private final ObjectMapper json = new ObjectMapper();
    private final Random random = new Random();
    private final Map<String, Style> styles = new HashMap<>();
    private Engine engine;

    @Inject
    public SupertonicTextToSpeechService(ModelStore store, HuggingFaceDownloader downloader) {
        this.downloader = downloader;
        this.directory = store.directory("supertonic-3");
    }

    @Override
    public String id() {
        return "supertonic";
    }

    @Override
    public String displayName() {
        return "Supertonic 3 (multilingual)";
    }

    @Override
    public String description() {
        return "Supertone's ~99M parameter model, 31 languages, fast on CPU. ~400 MB download.";
    }

    @Override
    public List<Language> languages() {
        return LANGUAGES;
    }

    @Override
    public List<Voice> voices() {
        List<Voice> voices = new ArrayList<>();
        for (String id : VOICE_IDS) {
            boolean female = id.startsWith("F");
            voices.add(new Voice(id, (female ? "Female " : "Male ") + id.substring(1), null, female ? "F" : "M"));
        }
        return voices;
    }

    private List<RemoteFile> files() {
        List<RemoteFile> files = new ArrayList<>();
        for (String name : ONNX_FILES) {
            files.add(new RemoteFile(REPO, "onnx/" + name, directory.resolve("onnx").resolve(name)));
        }
        for (String voice : VOICE_IDS) {
            files.add(new RemoteFile(REPO, "voice_styles/" + voice + ".json",
                    directory.resolve("voice_styles").resolve(voice + ".json")));
        }
        return files;
    }

    @Override
    public boolean isInstalled() {
        return HuggingFaceDownloader.allPresent(files());
    }

    @Override
    public void install(ProgressListener progress) throws IOException {
        downloader.ensure(files(), progress);
    }

    @Override
    public synchronized AudioClip synthesize(SpeechRequest request, ProgressListener progress) {
        String language = request.language() == null ? "en" : request.language();
        if (LANGUAGES.stream().noneMatch(l -> l.code().equals(language))) {
            throw new IllegalArgumentException("Supertonic does not support language '" + language + "'");
        }
        try {
            install(progress.slice(0, 0.3));
            progress.update("Loading Supertonic...", -1);
            Engine loaded = engine();
            Style style = style(request.voiceId() == null ? "F1" : request.voiceId());
            int maxLength = ("ko".equals(language) || "ja".equals(language)) ? 120 : 300;
            List<String> chunks = SupertonicText.chunk(request.text(), maxLength);
            List<AudioClip> parts = new ArrayList<>();
            for (int i = 0; i < chunks.size(); i++) {
                progress.checkCancelled();
                progress.update(String.format("Synthesizing part %d of %d", i + 1, chunks.size()),
                        0.3 + 0.7 * i / chunks.size());
                parts.add(loaded.infer(chunks.get(i), language, style, request.speed(), progress));
            }
            progress.update("Done", 1);
            return AudioClip.concat(loaded.sampleRate, CHUNK_SILENCE_SECONDS, parts);
        } catch (IOException | OrtException e) {
            throw new SpeechServiceException("Supertonic synthesis failed: " + e.getMessage(), e);
        }
    }

    private Engine engine() throws IOException, OrtException {
        if (engine == null) {
            engine = new Engine(directory.resolve("onnx"));
        }
        return engine;
    }

    private Style style(String voiceId) throws IOException, OrtException {
        Style cached = styles.get(voiceId);
        if (cached != null) {
            return cached;
        }
        JsonNode root = json.readTree(directory.resolve("voice_styles").resolve(voiceId + ".json").toFile());
        Style style = new Style(tensor(root.get("style_ttl")), tensor(root.get("style_dp")));
        styles.put(voiceId, style);
        return style;
    }

    private static OnnxTensor tensor(JsonNode node) throws OrtException {
        JsonNode dims = node.get("dims");
        long[] shape = new long[dims.size()];
        int size = 1;
        for (int i = 0; i < shape.length; i++) {
            shape[i] = dims.get(i).asLong();
            size *= (int) shape[i];
        }
        float[] flat = new float[size];
        int[] index = {0};
        flatten(node.get("data"), flat, index);
        return OnnxTensor.createTensor(Onnx.env(), FloatBuffer.wrap(flat), shape);
    }

    private static void flatten(JsonNode node, float[] out, int[] index) {
        if (node.isArray()) {
            for (JsonNode child : node) {
                flatten(child, out, index);
            }
        } else {
            out[index[0]++] = (float) node.asDouble();
        }
    }

    @Override
    public synchronized void close() {
        styles.values().forEach(s -> Onnx.closeQuietly(s.ttl, s.dp));
        styles.clear();
        if (engine != null) {
            engine.close();
            engine = null;
        }
    }

    private record Style(OnnxTensor ttl, OnnxTensor dp) {
    }

    private final class Engine implements AutoCloseable {
        final int sampleRate;
        final int baseChunkSize;
        final int chunkCompress;
        final int latentDim;
        final long[] indexer;
        final OrtSession durationPredictor;
        final OrtSession textEncoder;
        final OrtSession vectorEstimator;
        final OrtSession vocoder;

        Engine(Path onnxDir) throws IOException, OrtException {
            JsonNode config = json.readTree(onnxDir.resolve("tts.json").toFile());
            sampleRate = config.get("ae").get("sample_rate").asInt();
            baseChunkSize = config.get("ae").get("base_chunk_size").asInt();
            chunkCompress = config.get("ttl").get("chunk_compress_factor").asInt();
            latentDim = config.get("ttl").get("latent_dim").asInt();
            JsonNode indexNode = json.readTree(onnxDir.resolve("unicode_indexer.json").toFile());
            indexer = new long[indexNode.size()];
            for (int i = 0; i < indexer.length; i++) {
                indexer[i] = indexNode.get(i).asLong();
            }
            OrtSession dp = null;
            OrtSession te = null;
            OrtSession ve = null;
            try {
                dp = Onnx.open(onnxDir.resolve("duration_predictor.onnx"));
                te = Onnx.open(onnxDir.resolve("text_encoder.onnx"));
                ve = Onnx.open(onnxDir.resolve("vector_estimator.onnx"));
                vocoder = Onnx.open(onnxDir.resolve("vocoder.onnx"));
            } catch (OrtException | RuntimeException e) {
                Onnx.closeQuietly(dp, te, ve);
                throw e;
            }
            durationPredictor = dp;
            textEncoder = te;
            vectorEstimator = ve;
        }

        long[] encode(String text) {
            int[] codePoints = text.codePoints().toArray();
            long[] ids = new long[codePoints.length];
            int count = 0;
            for (int cp : codePoints) {
                if (cp < indexer.length && indexer[cp] >= 0) {
                    ids[count++] = indexer[cp];
                }
            }
            return java.util.Arrays.copyOf(ids, count);
        }

        AudioClip infer(String text, String language, Style style, float speed, ProgressListener progress)
                throws OrtException {
            OrtEnvironment env = Onnx.env();
            long[] ids = encode(SupertonicText.preprocess(text, language));
            int length = ids.length;
            float[] textMask = new float[length];
            java.util.Arrays.fill(textMask, 1f);
            try (OnnxTensor idsTensor = OnnxTensor.createTensor(env, LongBuffer.wrap(ids), new long[] {1, length});
                    OnnxTensor maskTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(textMask),
                            new long[] {1, 1, length})) {
                float duration;
                try (OrtSession.Result result = durationPredictor.run(
                        Map.of("text_ids", idsTensor, "style_dp", style.dp(), "text_mask", maskTensor))) {
                    duration = ((OnnxTensor) result.get(0)).getFloatBuffer().get(0) / speed;
                }
                try (OrtSession.Result encoded = textEncoder.run(
                        Map.of("text_ids", idsTensor, "style_ttl", style.ttl(), "text_mask", maskTensor))) {
                    OnnxTensor textEmbedding = (OnnxTensor) encoded.get(0);
                    long wavLength = (long) (duration * sampleRate);
                    int chunkSize = baseChunkSize * chunkCompress;
                    int latentLength = (int) Math.max(1, (wavLength + chunkSize - 1) / chunkSize);
                    int channels = latentDim * chunkCompress;
                    float[] latent = new float[channels * latentLength];
                    for (int i = 0; i < latent.length; i++) {
                        latent[i] = (float) random.nextGaussian();
                    }
                    float[] latentMask = new float[latentLength];
                    java.util.Arrays.fill(latentMask, 1f);
                    long[] latentShape = {1, channels, latentLength};
                    try (OnnxTensor latentMaskTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(latentMask),
                            new long[] {1, 1, latentLength});
                            OnnxTensor totalSteps = OnnxTensor.createTensor(env, new float[] {DENOISING_STEPS})) {
                        for (int step = 0; step < DENOISING_STEPS; step++) {
                            progress.checkCancelled();
                            Map<String, OnnxTensor> inputs = new HashMap<>();
                            try (OnnxTensor noisy = OnnxTensor.createTensor(env, FloatBuffer.wrap(latent), latentShape);
                                    OnnxTensor current = OnnxTensor.createTensor(env, new float[] {step})) {
                                inputs.put("noisy_latent", noisy);
                                inputs.put("text_emb", textEmbedding);
                                inputs.put("style_ttl", style.ttl());
                                inputs.put("latent_mask", latentMaskTensor);
                                inputs.put("text_mask", maskTensor);
                                inputs.put("current_step", current);
                                inputs.put("total_step", totalSteps);
                                try (OrtSession.Result denoised = vectorEstimator.run(inputs)) {
                                    FloatBuffer buffer = ((OnnxTensor) denoised.get(0)).getFloatBuffer();
                                    buffer.get(latent);
                                }
                            }
                        }
                    }
                    try (OnnxTensor finalLatent = OnnxTensor.createTensor(env, FloatBuffer.wrap(latent), latentShape);
                            OrtSession.Result wave = vocoder.run(Map.of("latent", finalLatent))) {
                        FloatBuffer buffer = ((OnnxTensor) wave.get(0)).getFloatBuffer();
                        int samples = (int) Math.min(buffer.remaining(), wavLength);
                        float[] audio = new float[samples];
                        buffer.get(audio);
                        return new AudioClip(audio, sampleRate);
                    }
                }
            }
        }

        @Override
        public void close() {
            Onnx.closeQuietly(durationPredictor, textEncoder, vectorEstimator, vocoder);
        }
    }
}
