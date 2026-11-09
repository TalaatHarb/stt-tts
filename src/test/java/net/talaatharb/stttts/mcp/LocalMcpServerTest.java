package net.talaatharb.stttts.mcp;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import net.talaatharb.stttts.audio.AudioClip;
import net.talaatharb.stttts.audio.WavIO;
import net.talaatharb.stttts.config.AppSettings;
import net.talaatharb.stttts.model.ProgressListener;
import net.talaatharb.stttts.service.*;
import net.talaatharb.stttts.ui.TaskRunner;

class LocalMcpServerTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String TOKEN = "test-mcp-token-that-is-at-least-32-characters";
    @TempDir Path temp;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final RecordingRunner runner = new RecordingRunner();
    private final StubStt stt = new StubStt();
    private final StubTts kokoro = new StubTts("kokoro");
    private final StubTts piper = new StubTts("piper");
    private final StubGeneration music = new StubGeneration("musicgen-small");
    private final StubGeneration stableMusic = new StubGeneration("stable-audio-3-music");
    private final StubGeneration sfx = new StubGeneration("stable-audio-3-sfx");
    private LocalMcpServer server;

    @BeforeEach
    void start() throws IOException {
        server = createServer(new AppSettings(temp.resolve("settings.properties")));
        assertFalse(server.isRunning(), "Creating/injecting the server must not start it");
        server.start(0, TOKEN);
        assertEquals("127.0.0.1", server.boundAddress().getAddress().getHostAddress());
        assertTrue(server.boundAddress().getAddress().isLoopbackAddress());
    }

    private LocalMcpServer createServer(AppSettings settings) {
        return new LocalMcpServer(settings,
                new AudioMcpTools(settings, stt, List.of(kokoro, piper), List.of(music, stableMusic, sfx), runner));
    }

    @AfterEach
    void stop() {
        server.close();
        runner.shutdown();
    }

    @Test
    void negotiatesInitializesListsToolsAndAcceptsNotifications() throws Exception {
        JsonNode initialized = rpc("initialize", """
                {"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"test","version":"1"}}
                """);
        assertEquals("2025-11-25", initialized.at("/result/protocolVersion").asText());
        assertTrue(initialized.at("/result/capabilities/tools").isObject());
        assertEquals(5, rpc("tools/list", "{}").at("/result/tools").size());
        assertTrue(rpc("ping", "{}").get("result").isObject());
        assertEquals(-32601, rpc("unknown", "{}").at("/error/code").asInt());
        assertEquals(-32602, rpc("initialize", "{}").at("/error/code").asInt());
        assertEquals(202, post("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}").statusCode());
        assertEquals(202, post("{\"jsonrpc\":\"2.0\",\"id\":7,\"result\":{}}").statusCode());
        JsonNode old = rpc("initialize", """
                {"protocolVersion":"2025-03-26","capabilities":{},"clientInfo":{"name":"test","version":"1"}}
                """);
        assertEquals("2025-03-26", old.at("/result/protocolVersion").asText());
        assertEquals("2025-11-25", rpc("initialize", """
                {"protocolVersion":"unsupported","capabilities":{},"clientInfo":{"name":"test","version":"1"}}
                """).at("/result/protocolVersion").asText());
    }

    @Test
    void guardsTransportAndRejectsMalformedJsonRpc() throws Exception {
        assertEquals(405, client.send(request().GET().build(), HttpResponse.BodyHandlers.ofString()).statusCode());
        assertEquals(405, client.send(request().DELETE().build(), HttpResponse.BodyHandlers.ofString()).statusCode());
        assertEquals(403, client.send(request().header("Origin", "https://evil.example")
                .POST(HttpRequest.BodyPublishers.ofString("{}")).build(),
                HttpResponse.BodyHandlers.ofString()).statusCode());
        assertEquals(403, client.send(request().header("Origin", "null")
                .POST(HttpRequest.BodyPublishers.ofString("{}")).build(),
                HttpResponse.BodyHandlers.ofString()).statusCode());
        assertEquals(200, client.send(request().header("Origin", "http://localhost:" + server.endpoint().getPort())
                .POST(HttpRequest.BodyPublishers.ofString("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}")).build(),
                HttpResponse.BodyHandlers.ofString()).statusCode());
        assertEquals(401, client.send(HttpRequest.newBuilder(server.endpoint()).GET().build(),
                HttpResponse.BodyHandlers.ofString()).statusCode());
        assertEquals(401, client.send(HttpRequest.newBuilder(server.endpoint()).header("Authorization", "Bearer wrong")
                .GET().build(), HttpResponse.BodyHandlers.ofString()).statusCode());
        assertEquals(400, client.send(request().header("MCP-Protocol-Version", "unsupported").GET().build(),
                HttpResponse.BodyHandlers.ofString()).statusCode());
        for (String invalid : List.of("[]", "null", "{}", "{bad", "{\"jsonrpc\":\"2.0\",\"id\":null,\"method\":\"ping\"}",
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"id\":2,\"method\":\"ping\"}",
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}{}")) {
            assertEquals(400, post(invalid).statusCode(), invalid);
        }
        String raw = "GET /mcp HTTP/1.1\r\nHost: evil.example:" + server.endpoint().getPort()
                + "\r\nAuthorization: Bearer " + TOKEN + "\r\nConnection: close\r\n\r\n";
        try (Socket socket = new Socket("127.0.0.1", server.endpoint().getPort())) {
            socket.setSoTimeout(5000);
            socket.getOutputStream().write(raw.getBytes(StandardCharsets.UTF_8));
            assertTrue(new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8)
                    .startsWith("HTTP/1.1 403"));
        }
        assertEquals(413, post(" ".repeat(12 * 1024 * 1024 + 1)).statusCode());
    }

    @Test
    void callsAllAudioCapabilitiesWithStructuredAndAudioResults() throws Exception {
        String wav = Base64.getEncoder().encodeToString(WavIO.toWavBytes(audio()));
        ObjectNode args = JSON.createObjectNode().put("audio_base64", wav).put("language", "en")
                .put("word_timestamps", true);
        JsonNode transcribed = tool("transcribe", args);
        assertEquals("recognized speech", transcribed.at("/result/structuredContent/text").asText());
        assertEquals(1, transcribed.at("/result/structuredContent/words").size());
        assertTrue(stt.request.wordTimestamps());
        assertEquals(audio().samples().length, stt.audio.samples().length);
        JsonNode speech = tool("synthesize", JSON.createObjectNode().put("text", "hello"));
        assertEquals("kokoro", speech.at("/result/structuredContent/model").asText());
        assertEquals("voice", kokoro.request.voiceId());
        assertEquals("en", kokoro.request.language());
        assertAudio(speech);
        JsonNode generated = tool("generate_music", JSON.createObjectNode().put("prompt", "gentle piano")
                .put("accept_license", true).put("duration_seconds", 2.5).put("seed", 12));
        assertAudio(generated);
        assertEquals(2.5, music.request.durationSeconds());
        assertEquals(12, music.request.seed());
        assertAudio(tool("generate_sound_effects", JSON.createObjectNode().put("prompt", "rain")
                .put("accept_license", true)));
        assertEquals("rain", sfx.request.prompt());
        assertEquals(4, runner.jobs);
    }

    @Test
    void honorsConfiguredDefaultsAndPerCallModelOverrides() throws Exception {
        Path file = temp.resolve("configured.properties");
        Files.writeString(file, "mcp.tts.model=piper\nmcp.music.model=stable-audio-3-music\n");
        server.close();
        server = createServer(new AppSettings(file));
        server.start(0, TOKEN);
        JsonNode catalog = tool("list_models", JSON.createObjectNode());
        assertEquals("piper", catalog.at("/result/structuredContent/defaults/tts").asText());
        assertEquals("stable-audio-3-music", catalog.at("/result/structuredContent/defaults/music").asText());
        assertEquals(6, catalog.at("/result/structuredContent/models").size());
        assertEquals("piper", tool("synthesize", JSON.createObjectNode().put("text", "hello"))
                .at("/result/structuredContent/model").asText());
        assertEquals("kokoro", tool("synthesize", JSON.createObjectNode().put("text", "hello").put("model", "kokoro"))
                .at("/result/structuredContent/model").asText());
        assertEquals("stable-audio-3-music", tool("generate_music", JSON.createObjectNode().put("prompt", "piano")
                .put("accept_license", true)).at("/result/structuredContent/model").asText());
    }

    @Test
    void requiresConsentAndReportsInvalidInputsWithoutInference() throws Exception {
        music.installed = false;
        assertToolError(tool("generate_music", JSON.createObjectNode().put("prompt", "piano")
                .put("accept_license", false)), "accept_license");
        assertNull(music.request);
        assertToolError(tool("generate_music", JSON.createObjectNode().put("prompt", "piano")
                .put("accept_license", true)), "allow_download");
        assertNull(music.request);
        assertAudio(tool("generate_music", JSON.createObjectNode().put("prompt", "piano")
                .put("accept_license", true).put("allow_download", true)));
        assertEquals(-32602, tool("not_a_tool", JSON.createObjectNode()).at("/error/code").asInt());
        for (ObjectNode args : List.of(JSON.createObjectNode().put("model", "kokoro"),
                JSON.createObjectNode().put("text", "  "),
                JSON.createObjectNode().put("text", "x".repeat(2001)))) {
            assertTrue(tool("synthesize", args).at("/result/isError").asBoolean());
        }
        assertNull(kokoro.request);
        assertToolError(tool("generate_music", JSON.createObjectNode().put("prompt", "")
                .put("accept_license", true)), "prompt");
        for (String audio : List.of("not-base64", Base64.getEncoder().encodeToString(new byte[10]),
                Base64.getEncoder().encodeToString(WavIO.toWavBytes(new AudioClip(new float[121 * 8000], 8000))))) {
            assertTrue(tool("transcribe", JSON.createObjectNode().put("audio_base64", audio))
                    .at("/result/isError").asBoolean());
        }
        assertNull(stt.request);
    }

    @Test
    void fallsBackToDefaultsForPlaceholderUnknownAndMistypedOptionalArguments() throws Exception {
        for (String placeholder : List.of("", "default", "DEFAULT", " auto ", "none")) {
            JsonNode speech = tool("synthesize", JSON.createObjectNode().put("text", "hello").put("model", placeholder)
                    .put("voice", placeholder).put("language", placeholder).put("speed", placeholder));
            assertAudio(speech);
            assertEquals("kokoro", speech.at("/result/structuredContent/model").asText());
            assertEquals("voice", kokoro.request.voiceId());
            assertEquals("en", kokoro.request.language());
            assertEquals(1f, kokoro.request.speed());
            assertTrue(speech.at("/result/structuredContent/warnings").isMissingNode());
        }
        JsonNode speech = tool("synthesize", JSON.createObjectNode().put("input", "hi").put("model", "unknown")
                .put("voice", "missing").put("language", "xx").put("speed", 0).put("allowDownload", "true")
                .put("unexpected", 1));
        assertAudio(speech);
        assertEquals("hi", kokoro.request.text());
        assertEquals("voice", kokoro.request.voiceId());
        assertEquals("en", kokoro.request.language());
        assertEquals(0.5f, kokoro.request.speed());
        assertEquals(5, speech.at("/result/structuredContent/warnings").size(), speech.toString());
        assertEquals("kokoro", tool("synthesize", JSON.createObjectNode().put("text", "hello").put("model", "KOKORO")
                .put("voice", "Voice")).at("/result/structuredContent/model").asText());

        JsonNode effects = tool("generate_sound_effects", JSON.createObjectNode().put("prompt", "rain")
                .put("accept_license", "yes").put("model", "musicgen-small").put("duration_seconds", "12s")
                .put("seed", "default"));
        assertAudio(effects);
        assertEquals("stable-audio-3-sfx", effects.at("/result/structuredContent/model").asText());
        assertEquals(10, sfx.request.durationSeconds());
        assertEquals(42, sfx.request.seed());
        assertEquals(2, effects.at("/result/structuredContent/warnings").size(), effects.toString());
        assertToolError(tool("generate_music", JSON.createObjectNode().put("prompt", "piano")
                .put("accept_license", "default")), "accept_license");

        String wav = Base64.getMimeEncoder().encodeToString(WavIO.toWavBytes(audio()));
        assertEquals("recognized speech", tool("transcribe", JSON.createObjectNode()
                .put("audio", "data:audio/wav;base64," + wav).put("language", "default").put("model", "")
                .put("word_timestamps", "default")).at("/result/structuredContent/text").asText());
        assertEquals("auto", stt.request.language());
        assertFalse(stt.request.wordTimestamps());
        tool("transcribe", JSON.createObjectNode().put("audio_base64", wav).put("language", "klingon"));
        assertEquals("auto", stt.request.language());

        assertAudio(rpc("tools/call", "{\"name\":\"synthesize\",\"arguments\":\"{\\\"text\\\":\\\"hey\\\"}\"}"));
        assertEquals("hey", kokoro.request.text());
        assertTrue(rpc("tools/call", "{\"name\":\"list_models\",\"arguments\":null}").at("/result/isError").isBoolean());
    }

    @Test
    void handlesLanguageNeutralVoicesAndChecksExactVoiceAssets() throws Exception {
        kokoro.languageNeutral = true;
        assertAudio(tool("synthesize", JSON.createObjectNode().put("text", "hello")));
        assertEquals("en", kokoro.request.language(), "A language-neutral voice must use the model's first language");
        kokoro.request = null;
        kokoro.selectedInstalled = false;
        assertTrue(kokoro.isInstalled(), "The default bundle may be present while selected assets are missing");
        assertToolError(tool("synthesize", JSON.createObjectNode().put("text", "hello")), "allow_download");
        assertNull(kokoro.request);
        assertAudio(tool("synthesize", JSON.createObjectNode().put("text", "hello").put("allow_download", true)));
    }

    @Test
    void surfacesBusyAndModelFailuresAndStopsAndRestarts() throws Exception {
        runner.failure = new IllegalStateException("Busy: another desktop job");
        assertToolError(tool("synthesize", JSON.createObjectNode().put("text", "hello")), "Busy");
        assertNull(kokoro.request);
        runner.failure = new SpeechServiceException("Native model failed");
        assertToolError(tool("synthesize", JSON.createObjectNode().put("text", "hello")), "Native model failed");
        runner.failure = null;
        int port = server.endpoint().getPort();
        server.close();
        assertFalse(server.isRunning());
        assertThrows(IOException.class, () -> client.send(
                HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:" + port + "/mcp")).GET().build(),
                HttpResponse.BodyHandlers.ofString()));
        server.start(port, TOKEN);
        assertTrue(server.isRunning());
        assertTrue(rpc("ping", "{}").has("result"));
    }

    @Test
    void rejectsInvalidConfigurationAndOccupiedPort() throws Exception {
        server.close();
        Path file = temp.resolve("invalid.properties");
        Files.writeString(file, "mcp.tts.model=unknown\n");
        server = createServer(new AppSettings(file));
        assertThrows(IllegalArgumentException.class, () -> server.start(0, TOKEN));
        assertFalse(server.isRunning());
        server = createServer(new AppSettings(temp.resolve("empty.properties")));
        try (ServerSocket occupied = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            assertThrows(IOException.class, () -> server.start(occupied.getLocalPort(), TOKEN));
            assertFalse(server.isRunning());
        }
    }

    @Test
    void stoppingServerCancelsItsOutstandingModelCall() throws Exception {
        runner.defer = true;
        CompletableFuture<HttpResponse<String>> request = client.sendAsync(
                request().POST(HttpRequest.BodyPublishers.ofString("""
                        {"jsonrpc":"2.0","id":1,"method":"tools/call",
                        "params":{"name":"synthesize","arguments":{"text":"hello"}}}
                        """)).build(), HttpResponse.BodyHandlers.ofString());
        assertTrue(runner.queued.await(5, TimeUnit.SECONDS));
        server.close();
        assertThrows(java.util.concurrent.CancellationException.class, () -> runner.pending.get(5, TimeUnit.SECONDS));
        request.handle((value, failure) -> null).get(5, TimeUnit.SECONDS);
    }

    private HttpRequest.Builder request() {
        return HttpRequest.newBuilder(server.endpoint()).timeout(Duration.ofSeconds(10))
                .header("Authorization", "Bearer " + TOKEN).header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream");
    }

    @Test
    void appliesSavedPortModelsAndTokenOnNextActivationWithoutAppRestart() throws Exception {
        server.close();
        AppSettings settings = new AppSettings(temp.resolve("saved.properties"));
        server = createServer(settings);
        int port;
        try (ServerSocket available = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            port = available.getLocalPort();
        }
        String changedToken = "changed-token-for-settings-window-test";
        settings.setMcpConfiguration(port, "whistle", "piper", "stable-audio-3-music", "stable-audio-3-sfx",
                changedToken);
        assertFalse(server.isRunning(), "Saving settings must not start the server");
        server.start();
        assertEquals(port, server.endpoint().getPort());
        assertEquals(401, post("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}").statusCode(),
                "The previous token must not work after changing it");
        HttpRequest updated = request().setHeader("Authorization", "Bearer " + changedToken)
                .POST(HttpRequest.BodyPublishers.ofString("""
                        {"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"list_models"}}
                        """)).build();
        HttpResponse<String> response = client.send(updated, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        assertEquals("piper", JSON.readTree(response.body()).at("/result/structuredContent/defaults/tts").asText());
    }

    private HttpResponse<String> post(String body) throws IOException, InterruptedException {
        return client.send(request().POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode rpc(String method, String params) throws Exception {
        ObjectNode request = JSON.createObjectNode().put("jsonrpc", "2.0").put("id", 1).put("method", method);
        request.set("params", JSON.readTree(params));
        HttpResponse<String> response = post(request.toString());
        assertEquals(200, response.statusCode(), response.body());
        assertFalse(response.headers().firstValue("Access-Control-Allow-Origin").isPresent());
        return JSON.readTree(response.body());
    }

    private JsonNode tool(String name, JsonNode args) throws Exception {
        ObjectNode params = JSON.createObjectNode().put("name", name);
        params.set("arguments", args);
        return rpc("tools/call", params.toString());
    }

    private static void assertToolError(JsonNode response, String contains) {
        assertTrue(response.at("/result/isError").asBoolean(), response.toString());
        assertTrue(response.at("/result/content/0/text").asText().contains(contains), response.toString());
    }

    private static void assertAudio(JsonNode response) throws IOException {
        assertFalse(response.at("/result/isError").asBoolean(), response.toString());
        assertEquals("audio", response.at("/result/content/1/type").asText());
        assertEquals("audio/wav", response.at("/result/content/1/mimeType").asText());
        AudioClip decoded = WavIO.parseWav(Base64.getDecoder().decode(response.at("/result/content/1/data").asText()));
        assertEquals(16000, decoded.sampleRate());
        assertEquals(audio().samples().length, decoded.samples().length);
    }

    private static AudioClip audio() {
        return new AudioClip(new float[] {0, .1f, -.1f, .2f}, 16000);
    }

    private static class RecordingRunner extends TaskRunner {
        int jobs;
        RuntimeException failure;
        boolean defer;
        CompletableFuture<?> pending;
        final CountDownLatch queued = new CountDownLatch(1);

        @Override
        public <T> CompletableFuture<T> submitRemote(String title, Work<T> work) {
            jobs++;
            if (defer) {
                CompletableFuture<T> result = new CompletableFuture<>();
                pending = result;
                queued.countDown();
                return result;
            }
            try {
                if (failure != null) {
                    throw failure;
                }
                return CompletableFuture.completedFuture(work.run(ProgressListener.NONE));
            } catch (Exception e) {
                return CompletableFuture.failedFuture(e);
            }
        }
    }

    private abstract static class StubModel implements SpeechModelService {
        final String id;
        boolean installed = true;
        StubModel(String id) { this.id = id; }
        public String id() { return id; }
        public String displayName() { return id; }
        public String description() { return "test model"; }
        public boolean isInstalled() { return installed; }
        public void install(ProgressListener progress) { installed = true; }
    }

    private static class StubStt extends StubModel implements SpeechToTextService {
        AudioClip audio;
        TranscriptionRequest request;
        StubStt() { super("whistle"); }
        public List<Language> languages() { return List.of(Language.AUTO, new Language("en", "English")); }
        public Transcription transcribe(AudioClip audio, TranscriptionRequest request, ProgressListener progress) {
            this.audio = audio;
            this.request = request;
            return new Transcription("recognized speech", "en",
                    List.of(new Transcription.WordTiming("recognized", 0, .1, .9)), Duration.ofMillis(10));
        }
    }

    private static class StubTts extends StubModel implements TextToSpeechService {
        SpeechRequest request;
        boolean languageNeutral;
        boolean selectedInstalled = true;
        StubTts(String id) { super(id); }
        public List<Language> languages() { return List.of(new Language("en", "English")); }
        public List<Voice> voices() {
            return List.of(new Voice("voice", "Voice", languageNeutral ? null : "en", "female"));
        }
        public boolean isInstalled(SpeechRequest request) { return installed && selectedInstalled; }
        public AudioClip synthesize(SpeechRequest request, ProgressListener progress) {
            this.request = request;
            return audio();
        }
    }

    private static class StubGeneration extends StubModel implements AudioGenerationService {
        AudioGenerationRequest request;
        StubGeneration(String id) { super(id); }
        public String licenseName() { return "Test license"; }
        public String licenseUrl() { return "https://example.com/license"; }
        public double maxDurationSeconds() { return 10; }
        public AudioClip generate(AudioGenerationRequest request, ProgressListener progress) {
            this.request = request;
            return audio();
        }
    }
}
