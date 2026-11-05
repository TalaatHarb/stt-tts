package net.talaatharb.stttts.generation.musicgen;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
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

import ai.onnxruntime.NodeInfo;
import ai.onnxruntime.OnnxJavaType;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import ai.onnxruntime.TensorInfo;
import net.talaatharb.stttts.audio.AudioClip;
import net.talaatharb.stttts.model.HuggingFaceDownloader;
import net.talaatharb.stttts.model.HuggingFaceDownloader.RemoteFile;
import net.talaatharb.stttts.model.ModelStore;
import net.talaatharb.stttts.model.Onnx;
import net.talaatharb.stttts.model.ProgressListener;
import net.talaatharb.stttts.service.AudioGenerationRequest;
import net.talaatharb.stttts.service.AudioGenerationService;
import net.talaatharb.stttts.service.SpeechServiceException;
import net.talaatharb.stttts.tts.pocket.SentencePieceTokenizer;

/**
 * MusicGen Small prompt-to-music generation using the Xenova ONNX export.
 *
 * <p>Text embeddings, autoregressive delayed codebooks with classifier-free guidance, and EnCodec decoding all run
 * locally through ONNX Runtime. Model files are fetched only when the service is installed or first used.
 */
@Singleton
public final class MusicGenSmallService implements AudioGenerationService {
    static final String REPO = "Xenova/musicgen-small";
    static final String LICENSE_NAME = "CC-BY-NC-4.0";
    static final String LICENSE_URL = "https://creativecommons.org/licenses/by-nc/4.0/";
    static final double MAX_DURATION_SECONDS = 10.0;
    static final int MAX_TEXT_TOKENS = 512;
    static final int DECODER_PAD_ID = 2048;
    static final int TEXT_EOS_ID = 1;
    static final int TEXT_PAD_ID = 0;
    private static final String[] MODEL_FILES = {
        "config.json",
        "tokenizer_config.json",
        "spiece.model",
        "onnx/text_encoder_quantized.onnx",
        "onnx/decoder_model_merged_quantized.onnx",
        "onnx/encodec_decode.onnx"
    };

    private final HuggingFaceDownloader downloader;
    private final Path directory;
    private final ObjectMapper json = new ObjectMapper();
    private Engine engine;

    @Inject
    public MusicGenSmallService(ModelStore store, HuggingFaceDownloader downloader) {
        this.downloader = downloader;
        this.directory = store.directory("musicgen-small");
    }

    @Override
    public String id() {
        return "musicgen-small";
    }

    @Override
    public String displayName() {
        return "MusicGen Small";
    }

    @Override
    public String description() {
        return "Meta's MusicGen Small text-to-music model. About 660 MB of ONNX weights; CC-BY-NC-4.0.";
    }

    @Override
    public String licenseName() {
        return LICENSE_NAME;
    }

    @Override
    public String licenseUrl() {
        return LICENSE_URL;
    }

    @Override
    public double maxDurationSeconds() {
        return MAX_DURATION_SECONDS;
    }

    private List<RemoteFile> files() {
        List<RemoteFile> files = new ArrayList<>();
        for (String name : MODEL_FILES) {
            files.add(new RemoteFile(REPO, name, directory.resolve(name)));
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
    public synchronized AudioClip generate(AudioGenerationRequest request, ProgressListener progress) {
        if (request.durationSeconds() > MAX_DURATION_SECONDS) {
            throw new IllegalArgumentException("MusicGen Small supports up to " + MAX_DURATION_SECONDS + " seconds");
        }
        if (request.seed() <= 0) {
            throw new IllegalArgumentException("MusicGen seed must be positive");
        }
        if (request.durationSeconds() * MusicGenSampling.AUDIO_SAMPLE_RATE > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Requested audio is too long");
        }
        try {
            progress.checkCancelled();
            install(progress.slice(0, 0.12));
            progress.checkCancelled();
            Engine loaded = engine();
            return loaded.generate(request, progress.slice(0.12, 1));
        } catch (IOException | OrtException e) {
            throw new SpeechServiceException("MusicGen generation failed: " + e.getMessage(), e);
        }
    }

    private Engine engine() throws IOException, OrtException {
        if (engine == null) {
            engine = new Engine(directory);
        }
        return engine;
    }

    @Override
    public synchronized void close() {
        if (engine != null) {
            engine.close();
            engine = null;
        }
    }

    private final class Engine implements AutoCloseable {
        private final SentencePieceTokenizer tokenizer;
        private final int codebooks;
        private final int decoderVocabulary;
        private final int textVocabulary;
        private final int textHeads;
        private final int sampleRate;
        private final OrtSession textEncoder;
        private final OrtSession decoder;
        private final OrtSession audioDecoder;

        Engine(Path modelDirectory) throws IOException, OrtException {
            JsonNode config = json.readTree(modelDirectory.resolve("config.json").toFile());
            codebooks = config.path("decoder").path("num_codebooks").asInt(MusicGenSampling.CODEBOOKS);
            decoderVocabulary = config.path("decoder").path("vocab_size").asInt(2048);
            textVocabulary = config.path("text_encoder").path("vocab_size").asInt(32128);
            textHeads = config.path("decoder").path("num_attention_heads").asInt(16);
            sampleRate = config.path("audio_encoder").path("sampling_rate")
                    .asInt(MusicGenSampling.AUDIO_SAMPLE_RATE);
            if (codebooks != MusicGenSampling.CODEBOOKS
                    || sampleRate != MusicGenSampling.AUDIO_SAMPLE_RATE
                    || decoderVocabulary <= 0 || textVocabulary <= TEXT_EOS_ID) {
                throw new IOException("Unexpected MusicGen Small config values");
            }
            tokenizer = SentencePieceTokenizer.load(modelDirectory.resolve("spiece.model"));
            if (tokenizer.vocabularySize() < 32_000) {
                throw new IOException("MusicGen tokenizer vocabulary is incomplete");
            }
            OrtSession text = null;
            OrtSession decode = null;
            OrtSession codec = null;
            try {
                text = Onnx.openAccelerated(modelDirectory.resolve("onnx/text_encoder_quantized.onnx"));
                decode = Onnx.openAccelerated(modelDirectory.resolve("onnx/decoder_model_merged_quantized.onnx"));
                codec = Onnx.openAccelerated(modelDirectory.resolve("onnx/encodec_decode.onnx"));
            } catch (OrtException | RuntimeException e) {
                Onnx.closeQuietly(text, decode, codec);
                throw e;
            }
            textEncoder = text;
            decoder = decode;
            audioDecoder = codec;
        }

        AudioClip generate(AudioGenerationRequest request, ProgressListener progress) throws OrtException {
            progress.checkCancelled();
            TextInputs text = tokenize(request.prompt());
            int frames = MusicGenSampling.frameCount(request.durationSeconds());
            int generatedCount = MusicGenSampling.generatedTokenCount(frames);
            int[][] generated = new int[generatedCount][MusicGenSampling.CODEBOOKS];
            Random random = new Random(request.seed());
            try (TextInputs input = text;
                    OrtSession.Result encoded = textEncoder.run(encoderFeeds(input))) {
                OnnxTensor embeddings = firstTensor(encoded);
                float[] hidden = floats(embeddings);
                long[] hiddenShape = embeddings.getInfo().getShape();
                if (hiddenShape.length != 3 || hiddenShape[0] != 1) {
                    throw new IllegalStateException("Unexpected MusicGen text-encoder output shape");
                }
                int textLength = (int) hiddenShape[1];
                int hiddenSize = (int) hiddenShape[2];
                float[] cfgHidden = new float[hidden.length * 2];
                System.arraycopy(hidden, 0, cfgHidden, 0, hidden.length);
                long[] cfgHiddenShape = {2, textLength, hiddenSize};
                long[] cfgMask = new long[2 * textLength];
                System.arraycopy(input.mask, 0, cfgMask, 0, textLength);
                try (OnnxTensor encoderHidden = OnnxTensor.createTensor(Onnx.env(),
                        FloatBuffer.wrap(cfgHidden), cfgHiddenShape);
                        OnnxTensor encoderMask = OnnxTensor.createTensor(Onnx.env(),
                                LongBuffer.wrap(cfgMask), new long[] {2, textLength});
                        DecoderCache cache = new DecoderCache()) {
                    for (int step = 0; step < generatedCount; step++) {
                        progress.checkCancelled();
                        progress.update(String.format("Generating music tokens (%d of %d)", step + 1, generatedCount),
                                (step + 1.0) / generatedCount);
                        int sequenceLength = step + 1;
                        long[] inputIds = delayedInput(generated, sequenceLength);
                        try (OnnxTensor ids = OnnxTensor.createTensor(Onnx.env(), LongBuffer.wrap(inputIds),
                                new long[] {2 * MusicGenSampling.CODEBOOKS, 1});
                                OnnxTensor cacheBranch = boolTensor(cache.hasCache())) {
                            OrtSession.Result result = decoder.run(decoderFeeds(ids, encoderHidden, encoderMask,
                                    cacheBranch, cache));
                            try {
                                float[] logits = floats(resultTensor(result, "logits", 0));
                                int expected = 2 * MusicGenSampling.CODEBOOKS * decoderVocabulary;
                                if (logits.length < expected) {
                                    throw new IllegalStateException("MusicGen decoder returned too few logits");
                                }
                                float[][] guided = MusicGenSampling.guidedLogits(
                                        lastLogits(logits, expected), decoderVocabulary,
                                        MusicGenSampling.GUIDANCE_SCALE);
                                for (int codebook = 0; codebook < MusicGenSampling.CODEBOOKS; codebook++) {
                                    generated[step][codebook] = MusicGenSampling.sampleTopK(guided[codebook],
                                            MusicGenSampling.TOP_K, MusicGenSampling.TEMPERATURE, random);
                                }
                                cache.replace(result, decoder);
                                result = null;
                            } finally {
                                if (result != null) {
                                    result.close();
                                }
                            }
                        }
                    }
                    progress.checkCancelled();
                    long[] codes = MusicGenSampling.delayedCodes(generated, frames);
                    try (OnnxTensor codeTensor = OnnxTensor.createTensor(Onnx.env(), LongBuffer.wrap(codes),
                            new long[] {1, 1, MusicGenSampling.CODEBOOKS, frames});
                            OrtSession.Result decoded = audioDecoder.run(Map.of("audio_codes", codeTensor))) {
                        OnnxTensor values = resultTensor(decoded, "audio_values", 0);
                        return normalizedAudio(floats(values), sampleRate,
                                (int) Math.round(request.durationSeconds() * sampleRate));
                    }
                }
            }
        }

        private TextInputs tokenize(String prompt) {
            int[] pieces = tokenizer.encode(prompt);
            int length = Math.min(MAX_TEXT_TOKENS - 1, pieces.length);
            long[] ids = new long[length + 1];
            for (int i = 0; i < length; i++) {
                int id = pieces[i];
                if (id < 0 || id >= textVocabulary) {
                    id = TEXT_PAD_ID;
                }
                ids[i] = id;
            }
            ids[length] = TEXT_EOS_ID;
            long[] mask = new long[ids.length];
            java.util.Arrays.fill(mask, 1);
            return new TextInputs(ids, mask);
        }

        private Map<String, OnnxTensor> encoderFeeds(TextInputs inputs) throws OrtException {
            OrtEnvironment env = Onnx.env();
            OnnxTensor ids = OnnxTensor.createTensor(env, LongBuffer.wrap(inputs.ids),
                    new long[] {1, inputs.ids.length});
            OnnxTensor mask = OnnxTensor.createTensor(env, LongBuffer.wrap(inputs.mask),
                    new long[] {1, inputs.mask.length});
            inputs.owned.add(ids);
            inputs.owned.add(mask);
            Map<String, OnnxTensor> feeds = new HashMap<>();
            putIfInput(feeds, textEncoder, "input_ids", ids);
            putIfInput(feeds, textEncoder, "attention_mask", mask);
            return feeds;
        }

        private Map<String, OnnxTensor> decoderFeeds(OnnxTensor ids, OnnxTensor encoderHidden,
                OnnxTensor encoderMask, OnnxTensor cacheBranch, DecoderCache cache)
                throws OrtException {
            Map<String, OnnxTensor> feeds = new HashMap<>();
            putIfInput(feeds, decoder, "input_ids", ids);
            putIfInput(feeds, decoder, "encoder_hidden_states", encoderHidden);
            putIfInput(feeds, decoder, "encoder_attention_mask", encoderMask);
            putIfInput(feeds, decoder, "use_cache_branch", cacheBranch);
            for (String name : decoder.getInputInfo().keySet()) {
                if (name.startsWith("past_key_values")) {
                    OnnxTensor tensor = cache.tensors.get(name);
                    if (tensor == null) {
                        tensor = cache.initial(name, decoder);
                    }
                    feeds.put(name, tensor);
                }
            }
            return feeds;
        }

        private final class DecoderCache implements AutoCloseable {
            private OrtSession.Result result;
            private OrtSession.Result encoderCacheResult;
            private Map<String, OnnxTensor> tensors = Map.of();
            private final List<OnnxTensor> initialTensors = new ArrayList<>();

            boolean hasCache() {
                return result != null;
            }

            void replace(OrtSession.Result next, OrtSession session) throws OrtException {
                Map<String, OnnxTensor> updated = new HashMap<>();
                for (Map.Entry<String, NodeInfo> output : session.getOutputInfo().entrySet()) {
                    String name = output.getKey();
                    if (name.startsWith("present")) {
                        String inputName = name.replaceFirst("^present", "past_key_values");
                        // The merged decoder emits placeholder encoder caches after its first cached pass.
                        OnnxTensor tensor = hasCache() && inputName.contains(".encoder.")
                                ? tensors.get(inputName) : null;
                        if (tensor == null) {
                            tensor = (OnnxTensor) next.get(name).orElseThrow(
                                    () -> new IllegalStateException("Missing decoder cache output " + name));
                        }
                        updated.put(inputName, tensor);
                    }
                }
                OrtSession.Result previous = result;
                result = next;
                if (encoderCacheResult == null) {
                    encoderCacheResult = next;
                }
                tensors = updated;
                Onnx.closeQuietly(initialTensors.toArray(AutoCloseable[]::new));
                initialTensors.clear();
                if (previous != null && previous != encoderCacheResult) {
                    previous.close();
                }
            }

            OnnxTensor initial(String name, OrtSession session) throws OrtException {
                NodeInfo info = session.getInputInfo().get(name);
                if (!(info.getInfo() instanceof TensorInfo tensorInfo)) {
                    throw new IllegalStateException("Unexpected cache input " + name);
                }
                long[] shape = tensorInfo.getShape().clone();
                if (shape.length == 0) {
                    throw new IllegalStateException("Cache input has no dimensions: " + name);
                }
                if (shape.length >= 3) {
                    if (shape[0] < 0) {
                        shape[0] = shape.length == 3 ? (long) 2 * MusicGenSampling.CODEBOOKS * textHeads
                                : 2L * MusicGenSampling.CODEBOOKS;
                    }
                    if (shape.length == 4 && shape[1] < 0) {
                        shape[1] = textHeads;
                    }
                    int sequenceAxis = shape.length - 2;
                    if (shape[sequenceAxis] < 0) {
                        shape[sequenceAxis] = 0;
                    }
                    for (int i = 0; i < shape.length; i++) {
                        if (shape[i] < 0) {
                            shape[i] = 0;
                        }
                    }
                } else {
                    throw new IllegalStateException("Unsupported MusicGen cache shape: " + name);
                }
                OnnxTensor empty;
                if (tensorInfo.type == OnnxJavaType.FLOAT) {
                    empty = OnnxTensor.createTensor(Onnx.env(), FloatBuffer.allocate(0), shape);
                } else {
                    throw new IllegalStateException("Unsupported cache type " + tensorInfo.type + " for " + name);
                }
                Map<String, OnnxTensor> copy = new HashMap<>(tensors);
                copy.put(name, empty);
                tensors = copy;
                initialTensors.add(empty);
                return empty;
            }

            @Override
            public void close() {
                if (result != null) {
                    result.close();
                }
                if (encoderCacheResult != null && encoderCacheResult != result) {
                    encoderCacheResult.close();
                }
                Onnx.closeQuietly(initialTensors.toArray(AutoCloseable[]::new));
            }
        }

        private void putIfInput(Map<String, OnnxTensor> feeds, OrtSession session, String name, OnnxTensor tensor)
                throws OrtException {
            if (session.getInputInfo().containsKey(name)) {
                feeds.put(name, tensor);
            }
        }

        private OnnxTensor boolTensor(boolean value) throws OrtException {
            ByteBuffer data = ByteBuffer.allocateDirect(1).order(ByteOrder.nativeOrder());
            data.put((byte) (value ? 1 : 0));
            data.flip();
            return OnnxTensor.createTensor(Onnx.env(), data, new long[] {1}, OnnxJavaType.BOOL);
        }

        private float[] lastLogits(float[] logits, int elementsPerStep) {
            if (logits.length == elementsPerStep) {
                return logits;
            }
            int offset = logits.length - elementsPerStep;
            return java.util.Arrays.copyOfRange(logits, offset, logits.length);
        }

        private OnnxTensor firstTensor(OrtSession.Result result) {
            return (OnnxTensor) result.get(0);
        }

        private OnnxTensor resultTensor(OrtSession.Result result, String name, int fallbackIndex) {
            return (OnnxTensor) result.get(name).orElseGet(() -> result.get(fallbackIndex));
        }

        private float[] floats(OnnxTensor tensor) {
            FloatBuffer source = tensor.getFloatBuffer();
            float[] values = new float[source.remaining()];
            source.get(values);
            return values;
        }

        private long[] delayedInput(int[][] generated, int sequenceLength) {
            int rows = 2 * MusicGenSampling.CODEBOOKS;
            long[] ids = new long[rows];
            int position = sequenceLength - 1;
            for (int row = 0; row < rows; row++) {
                int codebook = row % MusicGenSampling.CODEBOOKS;
                ids[row] = position == 0 || codebook >= position
                        ? DECODER_PAD_ID : generated[position - 1][codebook];
            }
            return ids;
        }

        private AudioClip normalizedAudio(float[] source, int outputRate, int requestedSamples) {
            if (source.length == 0) {
                throw new IllegalStateException("MusicGen decoder returned no audio");
            }
            float[] audio = new float[requestedSamples];
            int copied = Math.min(requestedSamples, source.length);
            double peak = 0;
            double energy = 0;
            for (int i = 0; i < copied; i++) {
                float sample = source[i];
                if (!Float.isFinite(sample)) {
                    throw new IllegalStateException("MusicGen returned non-finite audio");
                }
                peak = Math.max(peak, Math.abs(sample));
                energy += sample * (double) sample;
                audio[i] = sample;
            }
            if (copied == 0 || energy / copied < 1e-12 || peak < 1e-6) {
                throw new IllegalStateException("MusicGen returned silent audio");
            }
            float gain = (float) (0.95 / peak);
            for (int i = 0; i < audio.length; i++) {
                audio[i] = Math.max(-1f, Math.min(1f, audio[i] * gain));
            }
            return new AudioClip(audio, outputRate);
        }

        @Override
        public void close() {
            Onnx.closeQuietly(textEncoder, decoder, audioDecoder);
        }
    }

    private static final class TextInputs implements AutoCloseable {
        final long[] ids;
        final long[] mask;
        final List<AutoCloseable> owned = new ArrayList<>();

        TextInputs(long[] ids, long[] mask) {
            this.ids = ids;
            this.mask = mask;
        }

        @Override
        public void close() {
            Onnx.closeQuietly(owned.toArray(AutoCloseable[]::new));
        }
    }
}
