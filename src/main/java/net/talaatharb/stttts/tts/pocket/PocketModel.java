package net.talaatharb.stttts.tts.pocket;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.LongBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import ai.onnxruntime.OnnxJavaType;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import net.talaatharb.stttts.audio.AudioClip;
import net.talaatharb.stttts.model.Onnx;
import net.talaatharb.stttts.model.ProgressListener;
import net.talaatharb.stttts.tts.pocket.PocketBundle.StateEntry;

/**
 * Runs one Pocket TTS ONNX language bundle: a text conditioner, a streaming flow-matching language model that
 * produces one 32 dimensional latent per 80 ms frame, and the Mimi codec decoder (plus its encoder for cloning).
 *
 * <p>Port of the reference Python implementation in {@code KevinAHM/pocket-tts-onnx}. All recurrent state is passed
 * as ONNX tensors and handed from one step's outputs straight to the next step's inputs.
 */
public final class PocketModel implements AutoCloseable {
    static final float TEMPERATURE = 0.7f;
    private static final float EOS_THRESHOLD = -4f;
    private static final double TOKENS_PER_SECOND = 3.0;
    private static final double PADDING_SECONDS = 2.0;
    private static final int DECODE_FRAMES = 15;

    private final Path directory;
    private final PocketBundle bundle;
    private final SentencePieceTokenizer tokenizer;
    private final float[] bos;
    private final Set<Integer> sentenceEnds;
    private final Set<Integer> clauseEnds;
    private OrtSession textConditioner;
    private OrtSession flowMain;
    private OrtSession flow;
    private OrtSession mimiDecoder;
    private OrtSession mimiEncoder;

    private PocketModel(Path directory, PocketBundle bundle, SentencePieceTokenizer tokenizer, float[] bos) {
        this.directory = directory;
        this.bundle = bundle;
        this.tokenizer = tokenizer;
        this.bos = bos;
        this.sentenceEnds = tail(tokenizer.encode(".!...?"));
        this.clauseEnds = tail(tokenizer.encode(",;:"));
    }

    /** Reads the bundle metadata and tokenizer; the ONNX sessions are opened on first use. */
    public static PocketModel load(Path directory) throws IOException {
        PocketBundle bundle = PocketBundle.read(directory.resolve("bundle.json"));
        SentencePieceTokenizer tokenizer = SentencePieceTokenizer.load(directory.resolve(bundle.tokenizerFile()));
        float[] bos = null;
        if (bundle.insertBosBeforeVoice() && bundle.bosFile() != null
                && Files.isRegularFile(directory.resolve(bundle.bosFile()))) {
            bos = NumpyArray.read(directory.resolve(bundle.bosFile())).data();
        }
        return new PocketModel(directory, bundle, tokenizer, bos);
    }

    private static Set<Integer> tail(int[] ids) {
        Set<Integer> set = new HashSet<>();
        for (int i = 1; i < ids.length; i++) {
            set.add(ids[i]);
        }
        return set;
    }

    public int sampleRate() {
        return bundle.sampleRate();
    }

    PocketBundle bundle() {
        return bundle;
    }

    SentencePieceTokenizer tokenizer() {
        return tokenizer;
    }

    private void openGenerator() throws OrtException {
        if (textConditioner == null) {
            textConditioner = Onnx.open(directory.resolve("text_conditioner.onnx"));
        }
        if (flowMain == null) {
            flowMain = Onnx.open(directory.resolve("flow_lm_main_int8.onnx"));
        }
        if (flow == null) {
            flow = Onnx.open(directory.resolve("flow_lm_flow_int8.onnx"));
        }
        if (mimiDecoder == null) {
            mimiDecoder = Onnx.open(directory.resolve("mimi_decoder_int8.onnx"));
        }
    }

    /** Recurrent language model state primed with a speaker; reusable for any number of generations. */
    public static final class VoiceState implements AutoCloseable {
        private final Map<String, OnnxTensor> tensors;
        private final List<AutoCloseable> owned;

        VoiceState(Map<String, OnnxTensor> tensors, List<AutoCloseable> owned) {
            this.tensors = tensors;
            this.owned = owned;
        }

        @Override
        public void close() {
            Onnx.closeQuietly(owned.toArray(AutoCloseable[]::new));
        }
    }

    /** Loads one of the predefined voices (a pre-computed language model state). */
    public VoiceState voiceFromStateFile(Path file) throws IOException, OrtException {
        Map<String, SafeTensors.Tensor> stored = SafeTensors.read(file);
        OrtEnvironment env = Onnx.env();
        Map<String, OnnxTensor> tensors = new HashMap<>();
        List<AutoCloseable> owned = new ArrayList<>();
        try {
            for (StateEntry entry : bundle.flowState()) {
                SafeTensors.Tensor source = stored.get(entry.module() + "/" + entry.key());
                OnnxTensor tensor;
                if (source == null && entry.key().equals("step")) {
                    tensor = OnnxTensor.createTensor(env, LongBuffer.wrap(new long[] {deriveStep(stored, entry)}),
                            entry.shape());
                } else if (source == null) {
                    tensor = filled(env, entry);
                } else {
                    tensor = adapt(env, source, entry);
                }
                owned.add(tensor);
                tensors.put(entry.inputName(), tensor);
            }
        } catch (OrtException | RuntimeException e) {
            Onnx.closeQuietly(owned.toArray(AutoCloseable[]::new));
            throw e;
        }
        return new VoiceState(tensors, owned);
    }

    private static long deriveStep(Map<String, SafeTensors.Tensor> stored, StateEntry entry) {
        SafeTensors.Tensor offset = stored.get(entry.module() + "/offset");
        if (offset != null && !stored.containsKey(entry.module() + "/end_offset")) {
            return offset.longs()[0];
        }
        SafeTensors.Tensor end = stored.get(entry.module() + "/current_end");
        return end == null || end.shape().length == 0 ? 0 : end.shape()[0];
    }

    /** Builds a voice state from a reference recording with the Mimi encoder (zero-shot cloning). */
    public VoiceState voiceFromAudio(AudioClip reference) throws OrtException {
        if (mimiEncoder == null) {
            mimiEncoder = Onnx.open(directory.resolve("mimi_encoder.onnx"));
        }
        openGenerator();
        OrtEnvironment env = Onnx.env();
        float[] samples = reference.atSampleRate(bundle.sampleRate()).samples();
        float[] embeddings;
        try (OnnxTensor audio = OnnxTensor.createTensor(env, FloatBuffer.wrap(samples),
                new long[] {1, 1, samples.length});
                OrtSession.Result encoded = mimiEncoder.run(Map.of("audio", audio))) {
            embeddings = floats((OnnxTensor) encoded.get(0));
        }
        if (bos != null) {
            float[] joined = new float[bos.length + embeddings.length];
            System.arraycopy(bos, 0, joined, 0, bos.length);
            System.arraycopy(embeddings, 0, joined, bos.length, embeddings.length);
            embeddings = joined;
        }
        int dim = bundle.conditioningDim();
        Map<String, OnnxTensor> initial = initialState(env, bundle.flowState());
        try (OnnxTensor sequence = OnnxTensor.createTensor(env, FloatBuffer.allocate(0),
                new long[] {1, 0, bundle.latentDim()});
                OnnxTensor text = OnnxTensor.createTensor(env, FloatBuffer.wrap(embeddings),
                        new long[] {1, embeddings.length / dim, dim})) {
            Map<String, OnnxTensor> inputs = new HashMap<>(initial);
            inputs.put("sequence", sequence);
            inputs.put("text_embeddings", text);
            OrtSession.Result result = flowMain.run(inputs);
            Map<String, OnnxTensor> state = new HashMap<>();
            updateState(state, result, bundle.flowState(), 2);
            return new VoiceState(state, new ArrayList<>(List.of(result)));
        } finally {
            Onnx.closeQuietly(initial.values().toArray(AutoCloseable[]::new));
        }
    }

    /** Speaks the text with the given voice, chunking it the same way as the reference implementation. */
    public AudioClip generate(String text, VoiceState voice, Random random, ProgressListener progress)
            throws OrtException {
        openGenerator();
        List<String> chunks = splitIntoChunks(text);
        List<float[]> latents = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            progress.checkCancelled();
            Prompt prompt = prepare(chunks.get(i));
            int framesAfterEos = bundle.recommendedFramesAfterEos() != null ? bundle.recommendedFramesAfterEos()
                    : prompt.framesAfterEosGuess() + 2;
            String label = chunks.size() > 1 ? String.format(" (part %d of %d)", i + 1, chunks.size()) : "";
            generateChunk(tokenizer.encode(prompt.text()), voice, framesAfterEos, random,
                    progress.slice(i / (double) chunks.size(), (i + 1) / (double) chunks.size()), label, latents);
        }
        progress.update("Decoding audio...", -1);
        return new AudioClip(decode(latents), bundle.sampleRate());
    }

    private void generateChunk(int[] ids, VoiceState voice, int framesAfterEos, Random random,
            ProgressListener progress, String label, List<float[]> latents) throws OrtException {
        OrtEnvironment env = Onnx.env();
        int latentDim = bundle.latentDim();
        int maxFrames = (int) Math.ceil((ids.length / TOKENS_PER_SECOND + PADDING_SECONDS) * bundle.frameRate());
        long[] tokenIds = Arrays.stream(ids).asLongStream().toArray();
        Map<String, OnnxTensor> state = new HashMap<>(voice.tensors);
        OrtSession.Result previous = null;
        OnnxTensor current = null;
        float std = (float) Math.sqrt(TEMPERATURE);
        try (OnnxTensor tokens = OnnxTensor.createTensor(env, LongBuffer.wrap(tokenIds),
                new long[] {1, tokenIds.length});
                OnnxTensor emptySequence = OnnxTensor.createTensor(env, FloatBuffer.allocate(0),
                        new long[] {1, 0, latentDim});
                OnnxTensor emptyText = OnnxTensor.createTensor(env, FloatBuffer.allocate(0),
                        new long[] {1, 0, bundle.conditioningDim()});
                OnnxTensor s = OnnxTensor.createTensor(env, new float[][] {{0f}});
                OnnxTensor t = OnnxTensor.createTensor(env, new float[][] {{1f}});
                OrtSession.Result conditioned = textConditioner.run(Map.of("token_ids", tokens));
                OnnxTensor textEmbeddings = batched(env, (OnnxTensor) conditioned.get(0))) {
            Map<String, OnnxTensor> inputs = new HashMap<>(state);
            inputs.put("sequence", emptySequence);
            inputs.put("text_embeddings", textEmbeddings);
            previous = flowMain.run(inputs);
            updateState(state, previous, bundle.flowState(), 2);

            float[] nan = new float[latentDim];
            Arrays.fill(nan, Float.NaN);
            current = OnnxTensor.createTensor(env, FloatBuffer.wrap(nan), new long[] {1, 1, latentDim});
            int eosStep = -1;
            for (int step = 0; step < maxFrames; step++) {
                progress.checkCancelled();
                progress.update(String.format("Generating speech%s: %.1f s", label, step / bundle.frameRate()),
                        Math.min(0.95, step / (double) maxFrames));
                inputs = new HashMap<>(state);
                inputs.put("sequence", current);
                inputs.put("text_embeddings", emptyText);
                OrtSession.Result result = flowMain.run(inputs);
                updateState(state, result, bundle.flowState(), 2);
                previous.close();
                previous = result;
                float eos = floats((OnnxTensor) result.get(1))[0];
                if (eos > EOS_THRESHOLD && eosStep < 0) {
                    eosStep = step;
                }
                if (eosStep >= 0 && step >= eosStep + framesAfterEos) {
                    break;
                }
                float[] x = new float[latentDim];
                for (int i = 0; i < latentDim; i++) {
                    x[i] = (float) random.nextGaussian() * std;
                }
                try (OnnxTensor noise = OnnxTensor.createTensor(env, FloatBuffer.wrap(x), new long[] {1, latentDim});
                        OrtSession.Result velocity = flow.run(Map.of("c", (OnnxTensor) result.get(0), "s", s,
                                "t", t, "x", noise))) {
                    float[] delta = floats((OnnxTensor) velocity.get(0));
                    for (int i = 0; i < latentDim; i++) {
                        x[i] += delta[i];
                    }
                }
                latents.add(x);
                current.close();
                current = OnnxTensor.createTensor(env, FloatBuffer.wrap(x), new long[] {1, 1, latentDim});
            }
        } finally {
            Onnx.closeQuietly(previous, current);
        }
    }

    private float[] decode(List<float[]> latents) throws OrtException {
        OrtEnvironment env = Onnx.env();
        int latentDim = bundle.latentDim();
        Map<String, OnnxTensor> initial = initialState(env, bundle.mimiState());
        Map<String, OnnxTensor> state = new HashMap<>(initial);
        List<float[]> audio = new ArrayList<>();
        OrtSession.Result previous = null;
        try {
            for (int start = 0; start < latents.size(); start += DECODE_FRAMES) {
                int frames = Math.min(DECODE_FRAMES, latents.size() - start);
                float[] chunk = new float[frames * latentDim];
                for (int i = 0; i < frames; i++) {
                    System.arraycopy(latents.get(start + i), 0, chunk, i * latentDim, latentDim);
                }
                try (OnnxTensor latent = OnnxTensor.createTensor(env, FloatBuffer.wrap(chunk),
                        new long[] {1, frames, latentDim})) {
                    Map<String, OnnxTensor> inputs = new HashMap<>(state);
                    inputs.put("latent", latent);
                    OrtSession.Result result = mimiDecoder.run(inputs);
                    audio.add(floats((OnnxTensor) result.get(0)));
                    updateState(state, result, bundle.mimiState(), 1);
                    if (previous != null) {
                        previous.close();
                    }
                    previous = result;
                }
            }
        } finally {
            Onnx.closeQuietly(previous);
            Onnx.closeQuietly(initial.values().toArray(AutoCloseable[]::new));
        }
        int length = audio.stream().mapToInt(a -> a.length).sum();
        float[] samples = new float[length];
        int position = 0;
        for (float[] part : audio) {
            System.arraycopy(part, 0, samples, position, part.length);
            position += part.length;
        }
        return samples;
    }

    /** Text after the reference implementation's clean-up, and its guess of the trailing frames to keep. */
    record Prompt(String text, int framesAfterEosGuess) {
    }

    Prompt prepare(String raw) {
        String text = raw.strip();
        if (text.isEmpty()) {
            throw new IllegalArgumentException("Text cannot be empty");
        }
        text = text.replace('\n', ' ').replace('\r', ' ').replace("  ", " ");
        if (bundle.removeSemicolons()) {
            text = text.replace(';', ',');
        }
        int words = text.strip().split("\\s+").length;
        int guess = words <= 4 ? 3 : 1;
        int first = text.codePointAt(0);
        if (!Character.isUpperCase(first)) {
            text = new StringBuilder().appendCodePoint(Character.toUpperCase(first))
                    .append(text.substring(Character.charCount(first))).toString();
        }
        if (Character.isLetterOrDigit(text.codePointBefore(text.length()))) {
            text = text + ".";
        }
        if (bundle.padShortInputs() && text.strip().split("\\s+").length < 5) {
            text = " ".repeat(8) + text;
        }
        return new Prompt(text, guess);
    }

    /** Splits text at sentence ends (then clause ends) into chunks of at most {@code max_token_per_chunk} tokens. */
    List<String> splitIntoChunks(String text) {
        int[] tokens = tokenizer.encode(prepare(text).text().strip());
        int limit = bundle.maxTokensPerChunk();
        List<Segment> refined = new ArrayList<>();
        for (Segment segment : segments(tokens, sentenceEnds)) {
            if (segment.tokens() <= limit) {
                refined.add(segment);
                continue;
            }
            List<Segment> parts = segments(tokenizer.encode(segment.text().strip()), clauseEnds);
            if (parts.size() > 1) {
                refined.addAll(parts);
            } else {
                refined.add(segment);
            }
        }
        List<String> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int count = 0;
        for (Segment segment : refined) {
            if (current.length() == 0) {
                current.append(segment.text());
                count = segment.tokens();
            } else if (count + segment.tokens() > limit) {
                chunks.add(current.toString().strip());
                current.setLength(0);
                current.append(segment.text());
                count = segment.tokens();
            } else {
                current.append(' ').append(segment.text());
                count += segment.tokens();
            }
        }
        if (current.length() > 0) {
            chunks.add(current.toString().strip());
        }
        chunks.removeIf(String::isBlank);
        return chunks;
    }

    private record Segment(int tokens, String text) {
    }

    private List<Segment> segments(int[] tokens, Set<Integer> boundaries) {
        List<Integer> indices = new ArrayList<>();
        indices.add(0);
        boolean previousWasBoundary = false;
        for (int i = 0; i < tokens.length; i++) {
            if (boundaries.contains(tokens[i])) {
                previousWasBoundary = true;
            } else {
                if (previousWasBoundary) {
                    indices.add(i);
                }
                previousWasBoundary = false;
            }
        }
        indices.add(tokens.length);
        List<Segment> segments = new ArrayList<>();
        for (int i = 0; i < indices.size() - 1; i++) {
            int start = indices.get(i);
            int end = indices.get(i + 1);
            segments.add(new Segment(end - start, tokenizer.decode(Arrays.copyOfRange(tokens, start, end))));
        }
        return segments;
    }

    private static void updateState(Map<String, OnnxTensor> state, OrtSession.Result result,
            List<StateEntry> manifest, int offset) {
        for (StateEntry entry : manifest) {
            state.put(entry.inputName(), (OnnxTensor) result.get(offset + entry.index()));
        }
    }

    private static Map<String, OnnxTensor> initialState(OrtEnvironment env, List<StateEntry> manifest)
            throws OrtException {
        Map<String, OnnxTensor> state = new HashMap<>();
        try {
            for (StateEntry entry : manifest) {
                state.put(entry.inputName(), filled(env, entry));
            }
        } catch (OrtException | RuntimeException e) {
            Onnx.closeQuietly(state.values().toArray(AutoCloseable[]::new));
            throw e;
        }
        return state;
    }

    static OnnxTensor filled(OrtEnvironment env, StateEntry entry) throws OrtException {
        int count = (int) entry.elementCount();
        switch (entry.dtype()) {
            case "float32" -> {
                float[] values = new float[count];
                if (entry.fill().equals("nan")) {
                    Arrays.fill(values, Float.NaN);
                } else if (entry.fill().equals("ones")) {
                    Arrays.fill(values, 1f);
                }
                return OnnxTensor.createTensor(env, FloatBuffer.wrap(values), entry.shape());
            }
            case "int64" -> {
                long[] values = new long[count];
                if (entry.fill().equals("ones")) {
                    Arrays.fill(values, 1L);
                }
                return OnnxTensor.createTensor(env, LongBuffer.wrap(values), entry.shape());
            }
            case "bool" -> {
                ByteBuffer values = ByteBuffer.allocateDirect(Math.max(1, count)).order(ByteOrder.nativeOrder());
                if (entry.fill().equals("ones") || entry.fill().equals("nan")) {
                    for (int i = 0; i < count; i++) {
                        values.put(i, (byte) 1);
                    }
                }
                values.limit(count);
                return OnnxTensor.createTensor(env, values, entry.shape(), OnnxJavaType.BOOL);
            }
            default -> throw new IllegalStateException("Unsupported state type " + entry.dtype());
        }
    }

    /** Converts a stored state tensor to the manifest's shape, copying the overlapping region like numpy slicing. */
    static OnnxTensor adapt(OrtEnvironment env, SafeTensors.Tensor source, StateEntry entry) throws OrtException {
        long[] target = entry.shape();
        boolean sameSize = source.elementCount() == entry.elementCount();
        boolean sameRank = source.shape().length == target.length;
        if (entry.dtype().equals("int64")) {
            long[] values = sameSize ? source.longs() : new long[(int) entry.elementCount()];
            if (!sameSize && sameRank) {
                sliceCopy(source.longs(), source.shape(), values, target);
            }
            return OnnxTensor.createTensor(env, LongBuffer.wrap(values), target);
        }
        if (!entry.dtype().equals("float32")) {
            return filled(env, entry);
        }
        float[] values;
        if (sameSize) {
            values = source.floats();
        } else {
            values = new float[(int) entry.elementCount()];
            if (entry.fill().equals("nan")) {
                Arrays.fill(values, Float.NaN);
            } else if (entry.fill().equals("ones")) {
                Arrays.fill(values, 1f);
            }
            if (sameRank) {
                sliceCopy(source.floats(), source.shape(), values, target);
            }
        }
        return OnnxTensor.createTensor(env, FloatBuffer.wrap(values), target);
    }

    /** Copies {@code source[:a, :b, ...]} into {@code target[:a, :b, ...]} for row major arrays of equal rank. */
    static void sliceCopy(Object source, long[] sourceShape, Object target, long[] targetShape) {
        int rank = sourceShape.length;
        if (rank == 0) {
            System.arraycopy(source, 0, target, 0, 1);
            return;
        }
        long[] common = new long[rank];
        for (int i = 0; i < rank; i++) {
            common[i] = Math.min(sourceShape[i], targetShape[i]);
            if (common[i] == 0) {
                return;
            }
        }
        long[] sourceStrides = strides(sourceShape);
        long[] targetStrides = strides(targetShape);
        int run = (int) common[rank - 1];
        int[] index = new int[rank];
        while (true) {
            long from = 0;
            long to = 0;
            for (int i = 0; i < rank - 1; i++) {
                from += index[i] * sourceStrides[i];
                to += index[i] * targetStrides[i];
            }
            System.arraycopy(source, (int) from, target, (int) to, run);
            int dim = rank - 2;
            while (dim >= 0) {
                if (++index[dim] < common[dim]) {
                    break;
                }
                index[dim] = 0;
                dim--;
            }
            if (dim < 0) {
                return;
            }
        }
    }

    private static long[] strides(long[] shape) {
        long[] strides = new long[shape.length];
        long stride = 1;
        for (int i = shape.length - 1; i >= 0; i--) {
            strides[i] = stride;
            stride *= shape[i];
        }
        return strides;
    }

    /** Text conditioner outputs may drop the batch dimension; the language model expects {@code [1, L, D]}. */
    private static OnnxTensor batched(OrtEnvironment env, OnnxTensor tensor) throws OrtException {
        long[] shape = tensor.getInfo().getShape();
        long[] batched = shape.length == 3 ? shape : new long[] {1, shape[0], shape[shape.length - 1]};
        return OnnxTensor.createTensor(env, FloatBuffer.wrap(floats(tensor)), batched);
    }

    private static float[] floats(OnnxTensor tensor) {
        FloatBuffer buffer = tensor.getFloatBuffer();
        float[] values = new float[buffer.remaining()];
        buffer.get(values);
        return values;
    }

    @Override
    public void close() {
        Onnx.closeQuietly(textConditioner, flowMain, flow, mimiDecoder, mimiEncoder);
        textConditioner = null;
        flowMain = null;
        flow = null;
        mimiDecoder = null;
        mimiEncoder = null;
    }
}
