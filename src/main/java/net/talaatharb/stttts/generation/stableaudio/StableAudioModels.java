package net.talaatharb.stttts.generation.stableaudio;

import java.io.IOException;
import java.nio.FloatBuffer;
import java.nio.LongBuffer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Random;

import com.google.inject.Inject;
import com.google.inject.Singleton;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import net.talaatharb.stttts.audio.AudioClip;
import net.talaatharb.stttts.model.HuggingFaceDownloader;
import net.talaatharb.stttts.model.HuggingFaceDownloader.RemoteFile;
import net.talaatharb.stttts.model.ModelStore;
import net.talaatharb.stttts.model.Onnx;
import net.talaatharb.stttts.model.ProgressListener;
import net.talaatharb.stttts.service.AudioGenerationRequest;
import net.talaatharb.stttts.service.SpeechServiceException;

/** Shared cached artifacts; inference sessions are phase-scoped to bound RAM/VRAM usage. */
@Singleton
public class StableAudioModels implements AutoCloseable {
    static final String REPO = "stabilityai/stable-audio-3-optimized";
    static final String REVISION = "30cb2644da54bfa63275d1bc7f1fa67fc718680f";
    private final Path directory;
    private final HuggingFaceDownloader downloader;
    private GemmaTokenizer tokenizer;
    private OrtSession textEncoder;
    private OrtSession decoder;
    private OrtSession dit;

    @Inject
    public StableAudioModels(ModelStore store, HuggingFaceDownloader downloader) {
        directory = store.directory("stable-audio-3");
        this.downloader = downloader;
    }

    List<RemoteFile> files(boolean music) {
        List<RemoteFile> files = new ArrayList<>();
        files.add(file("onnx/t5gemma/encoder.onnx", "text-encoder.onnx"));
        files.add(file("tensorRT/sm_90/t5gemma/tokenizer.json", "tokenizer.json"));
        files.add(file("onnx/same-s/dec_bf16_limiter.onnx", "decoder-bf16.onnx"));
        files.add(file("onnx/sa3-sm-" + (music ? "music" : "sfx") + "/dit_fp16.onnx",
                music ? "music-dit.onnx" : "sfx-dit.onnx"));
        files.add(file("LICENSE.md", "LICENSE.md"));
        files.add(file("LICENSE_GEMMA.md", "LICENSE_GEMMA.md"));
        return files;
    }

    private RemoteFile file(String remote, String local) {
        return new RemoteFile(REPO, REVISION, remote, directory.resolve(local));
    }

    public synchronized boolean isInstalled(boolean music) {
        return HuggingFaceDownloader.allPresent(files(music));
    }

    public synchronized void install(boolean music, ProgressListener progress) throws IOException {
        progress.checkCancelled();
        downloader.ensure(files(music), progress);
    }

    public synchronized AudioClip generate(boolean music, AudioGenerationRequest request, ProgressListener progress) {
        if (request.durationSeconds() > 10 || request.durationSeconds() < 1) {
            throw new IllegalArgumentException("Stable Audio duration must be between 1 and 10 seconds");
        }
        if (request.prompt().length() > 4096) {
            throw new IllegalArgumentException("Stable Audio prompts must not exceed 4096 characters");
        }
        try {
            install(music, progress.slice(0, 0.25));
            progress.checkCancelled();
            progress.update("Loading Stable Audio text encoder", -1);
            if (tokenizer == null) {
                tokenizer = new GemmaTokenizer(directory.resolve("tokenizer.json"));
            }
            if (textEncoder == null) {
                textEncoder = Onnx.open(directory.resolve("text-encoder.onnx"));
            }
            long[] encoded = tokenizer.encode(request.prompt());
            if (encoded.length > 256) {
                throw new IllegalArgumentException("Stable Audio prompt exceeds the 256-token text encoder limit");
            }
            long[] ids = Arrays.copyOf(encoded, 256);
            long[] mask = new long[256];
            Arrays.fill(mask, 0, encoded.length, 1);
            float[] hidden;
            try (OnnxTensor input = OnnxTensor.createTensor(Onnx.env(), LongBuffer.wrap(ids), new long[] {1, 256});
                    OnnxTensor attention = OnnxTensor.createTensor(Onnx.env(), LongBuffer.wrap(mask),
                            new long[] {1, 256});
                    OrtSession.Result out = textEncoder.run(Map.of("input_ids", input, "attention_mask", attention))) {
                hidden = floats((OnnxTensor) out.get("hidden_states").orElseThrow());
            }
            textEncoder.close();
            textEncoder = null;
            progress.checkCancelled();
            progress.update("Loading Stable Audio diffusion model", -1);
            if (dit == null) {
                dit = Onnx.openAccelerated(directory.resolve(music ? "music-dit.onnx" : "sfx-dit.onnx"));
            }
            int length = (int) Math.ceil(request.durationSeconds() * StableAudioSampling.SAMPLE_RATE
                    / StableAudioSampling.LATENT_STRIDE);
            // SAME-S uses a two-latent attention grid.
            length += length % 2;
            float[] x = new float[StableAudioSampling.CHANNELS * length];
            Random noise = new Random(request.seed());
            for (int i = 0; i < x.length; i++) {
                x[i] = (float) noise.nextGaussian();
            }
            Random loopNoise = new Random(request.seed() + 1);
            float[] floatMask = new float[256];
            for (int i = 0; i < encoded.length; i++) {
                floatMask[i] = 1;
            }
            try (OnnxTensor conditioning = tensor(hidden, 1, 256, 768);
                    OnnxTensor conditioningMask = tensor(floatMask, 1, 256);
                    OnnxTensor seconds = tensor(new float[] {(float) request.durationSeconds()}, 1);
                    OnnxTensor local = tensor(new float[257 * length], 1, 257, length)) {
                for (int step = 0; step < StableAudioSampling.STEPS; step++) {
                    progress.checkCancelled();
                    progress.update("Stable Audio diffusion step " + (step + 1) + " of " + StableAudioSampling.STEPS,
                            0.3 + 0.5 * step / StableAudioSampling.STEPS);
                    float sigma = StableAudioSampling.sigma(step, length);
                    try (OnnxTensor latents = tensor(x, 1, 256, length);
                            OnnxTensor time = tensor(new float[] {sigma}, 1);
                            OrtSession.Result out = dit.run(Map.of("x", latents, "t", time, "t5_hidden", conditioning,
                                    "t5_mask", conditioningMask, "seconds_total", seconds, "local_add_cond", local))) {
                        StableAudioSampling.pingPong(x, floats((OnnxTensor) out.get("velocity").orElseThrow()),
                                sigma, StableAudioSampling.sigma(step + 1, length), loopNoise);
                    }
                }
            }
            dit.close();
            dit = null;
            progress.checkCancelled();
            progress.update("Decoding Stable Audio waveform", -1);
            if (decoder == null) {
                Path portable = directory.resolve("decoder-fp32-v1.onnx");
                BFloat16Model.prepare(directory.resolve("decoder-bf16.onnx"), portable, progress);
                decoder = Onnx.openAccelerated(portable, OrtSession.SessionOptions.OptLevel.EXTENDED_OPT);
            }
            int sampleCount = (int) Math.round(request.durationSeconds() * StableAudioSampling.SAMPLE_RATE);
            try (OnnxTensor latents = tensor(x, 1, 256, length);
                    OrtSession.Result out = decoder.run(Map.of("latent", latents))) {
                AudioClip clip = decode((OnnxTensor) out.get("pcm").orElseThrow(), sampleCount);
                progress.checkCancelled();
                progress.update("Done", 1);
                return clip;
            }
        } catch (IOException | OrtException e) {
            throw new SpeechServiceException("Stable Audio generation failed: " + e.getMessage(), e);
        } finally {
            close();
        }
    }

    private static AudioClip decode(OnnxTensor pcm, int samples) {
        long[] shape = pcm.getInfo().getShape();
        if (shape.length != 3 || shape[0] != 1 || shape[1] < samples || shape[2] != 2) {
            throw new IllegalStateException("Unexpected Stable Audio PCM output shape: " + Arrays.toString(shape));
        }
        java.nio.IntBuffer values = pcm.getIntBuffer();
        float[] mono = new float[samples];
        double energy = 0;
        for (int i = 0; i < samples; i++) {
            mono[i] = (values.get() / 32767f + values.get() / 32767f) * 0.5f;
            if (!Float.isFinite(mono[i]) || Math.abs(mono[i]) > 1.0001f) {
                throw new IllegalStateException("Stable Audio produced invalid PCM");
            }
            energy += mono[i] * mono[i];
        }
        if (Math.sqrt(energy / samples) < 1e-6) {
            throw new IllegalStateException("Stable Audio produced silent audio");
        }
        return new AudioClip(mono, StableAudioSampling.SAMPLE_RATE);
    }

    private static OnnxTensor tensor(float[] values, long... shape) throws OrtException {
        return OnnxTensor.createTensor(Onnx.env(), FloatBuffer.wrap(values), shape);
    }

    private static float[] floats(OnnxTensor tensor) {
        FloatBuffer buffer = tensor.getFloatBuffer();
        float[] values = new float[buffer.remaining()];
        buffer.get(values);
        return values;
    }

    @Override
    public synchronized void close() {
        Onnx.closeQuietly(textEncoder, dit, decoder);
        textEncoder = null;
        dit = null;
        decoder = null;
    }
}
