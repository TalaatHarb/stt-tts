package net.talaatharb.stttts.stt.whistle;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.inject.Inject;
import com.google.inject.Singleton;

import net.talaatharb.stttts.audio.AudioClip;
import net.talaatharb.stttts.audio.WavIO;
import net.talaatharb.stttts.model.HuggingFaceDownloader;
import net.talaatharb.stttts.model.HuggingFaceDownloader.RemoteFile;
import net.talaatharb.stttts.model.ModelStore;
import net.talaatharb.stttts.model.ProgressListener;
import net.talaatharb.stttts.service.Language;
import net.talaatharb.stttts.service.SpeechServiceException;
import net.talaatharb.stttts.service.SpeechToTextService;
import net.talaatharb.stttts.service.Transcription;
import net.talaatharb.stttts.service.TranscriptionRequest;

/**
 * Speech to text with Cactus Compute's Whistle (16.9 MB {@code whistle.cact}).
 *
 * <p>The model runs on Cactus' C++ engine, which is published as a tiny self-contained executable per platform
 * on Hugging Face. Both the engine and the model are downloaded on first use, so nothing has to be installed.
 */
@Singleton
public class WhistleSpeechToTextService implements SpeechToTextService {
    static final String ENGINE_REPO = "Cactus-Compute/needle3";
    static final String MODEL_REPO = "Cactus-Compute/whistle";
    static final int SAMPLE_RATE = 16000;

    private static final List<Language> LANGUAGES = List.of(Language.AUTO,
            new Language("en", "English"), new Language("de", "German"), new Language("fr", "French"),
            new Language("es", "Spanish"), new Language("it", "Italian"), new Language("nl", "Dutch"),
            new Language("pl", "Polish"));

    private final HuggingFaceDownloader downloader;
    private final Path directory;
    private final ObjectMapper json = new ObjectMapper();
    private final NeedlePlatform platform;

    @Inject
    public WhistleSpeechToTextService(ModelStore store, HuggingFaceDownloader downloader) {
        this.downloader = downloader;
        this.directory = store.directory("whistle");
        NeedlePlatform detected;
        try {
            detected = NeedlePlatform.current();
        } catch (UnsupportedOperationException e) {
            detected = null;
        }
        this.platform = detected;
    }

    @Override
    public String id() {
        return "whistle";
    }

    @Override
    public String displayName() {
        return "Whistle (Cactus Compute)";
    }

    @Override
    public String description() {
        return "16.9 MB on-device speech recognition for 7 languages, Apache 2.0. Runs on the Cactus CPU engine.";
    }

    @Override
    public List<Language> languages() {
        return LANGUAGES;
    }

    Path modelFile() {
        return directory.resolve("whistle.cact");
    }

    Path engineFile() {
        return directory.resolve(requirePlatform().folder()).resolve(requirePlatform().executable());
    }

    private NeedlePlatform requirePlatform() {
        if (platform == null) {
            throw new SpeechServiceException("Whistle is not available for " + System.getProperty("os.name") + " / "
                    + System.getProperty("os.arch"));
        }
        return platform;
    }

    private List<RemoteFile> files() {
        return List.of(new RemoteFile(ENGINE_REPO, requirePlatform().remotePath(), engineFile()),
                new RemoteFile(MODEL_REPO, "whistle.cact", modelFile()));
    }

    @Override
    public boolean isInstalled() {
        return platform != null && HuggingFaceDownloader.allPresent(files());
    }

    @Override
    public void install(ProgressListener progress) throws IOException {
        downloader.ensure(files(), progress);
        Path engine = engineFile();
        if (!engine.toFile().canExecute() && !engine.toFile().setExecutable(true)) {
            throw new IOException("Could not mark the Whistle engine as executable: " + engine);
        }
    }

    @Override
    public Transcription transcribe(AudioClip audio, TranscriptionRequest request, ProgressListener progress) {
        if (audio.isEmpty()) {
            throw new IllegalArgumentException("The recording is empty");
        }
        long started = System.nanoTime();
        try {
            install(progress.slice(0, 0.2));
            progress.update("Transcribing with Whistle...", -1);
            Path wav = Files.createTempFile("whistle-", ".wav");
            try {
                WavIO.write(audio.atSampleRate(SAMPLE_RATE), wav);
                String output = run(command(wav, request), progress);
                Transcription result = parse(output, Duration.ofNanos(System.nanoTime() - started));
                progress.update("Done", 1);
                return result;
            } finally {
                Files.deleteIfExists(wav);
            }
        } catch (IOException e) {
            throw new SpeechServiceException("Whistle transcription failed: " + e.getMessage(), e);
        }
    }

    List<String> command(Path wav, TranscriptionRequest request) {
        List<String> command = new ArrayList<>(List.of(engineFile().toString(), "--model", modelFile().toString(),
                "--audio", wav.toString()));
        if (!request.autoDetect()) {
            command.add("--audio-language");
            command.add(request.language());
        }
        if (request.wordTimestamps()) {
            command.add("--audio-word-timestamps");
        }
        return command;
    }

    private String run(List<String> command, ProgressListener progress) throws IOException {
        Process process = new ProcessBuilder(command).directory(directory.toFile()).start();
        CompletableFuture<String> stdout = readAsync(process.getInputStream());
        CompletableFuture<String> stderr = readAsync(process.getErrorStream());
        try {
            while (!process.waitFor(100, TimeUnit.MILLISECONDS)) {
                if (progress.isCancelled() || Thread.currentThread().isInterrupted()) {
                    process.destroyForcibly();
                    throw new CancellationException("Cancelled");
                }
            }
            if (process.exitValue() != 0) {
                throw new IOException("engine exited with code " + process.exitValue() + ": " + stderr.join().trim());
            }
            return stdout.join();
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new CancellationException("Interrupted");
        }
    }

    private static CompletableFuture<String> readAsync(InputStream stream) {
        return CompletableFuture.supplyAsync(() -> {
            try (InputStream in = stream) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                return "";
            }
        });
    }

    Transcription parse(String output, Duration elapsed) throws IOException {
        String trimmed = output.trim();
        int start = trimmed.lastIndexOf("\n{");
        String line = start >= 0 ? trimmed.substring(start + 1) : trimmed;
        JsonNode root = json.readTree(line);
        if (root == null || !root.has("text")) {
            throw new IOException("unexpected engine output: " + trimmed);
        }
        List<Transcription.WordTiming> words = new ArrayList<>();
        for (JsonNode word : root.path("words")) {
            words.add(new Transcription.WordTiming(word.path("word").asText(), word.path("start").asDouble(),
                    word.path("end").asDouble(), word.path("probability").asDouble()));
        }
        return new Transcription(root.path("text").asText().trim(), root.path("language").asText(null), words,
                elapsed);
    }
}
