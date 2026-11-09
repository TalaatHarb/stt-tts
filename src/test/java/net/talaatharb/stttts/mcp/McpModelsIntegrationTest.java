package net.talaatharb.stttts.mcp;

import static org.junit.jupiter.api.Assertions.*;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.inject.AbstractModule;
import com.google.inject.Guice;
import com.google.inject.Key;
import com.google.inject.TypeLiteral;
import com.google.inject.util.Modules;

import net.talaatharb.stttts.audio.AudioClip;
import net.talaatharb.stttts.audio.WavIO;
import net.talaatharb.stttts.config.AppModule;
import net.talaatharb.stttts.config.AppSettings;
import net.talaatharb.stttts.model.ModelStore;
import net.talaatharb.stttts.model.ProgressListener;
import net.talaatharb.stttts.service.AudioGenerationService;
import net.talaatharb.stttts.service.TextToSpeechService;
import net.talaatharb.stttts.ui.TaskRunner;

/** Opt-in real model execution through HTTP. Running this test accepts the generation model licenses. */
@EnabledIfSystemProperty(named = "stttts.integration", matches = "true")
class McpModelsIntegrationTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String TOKEN = "integration-test-token-not-for-production";
    @TempDir Path temp;

    @Test
    void generatesSpeechTranscribesItAndGeneratesMusicAndEffectsThroughHttp() throws Exception {
        TaskRunner headless = new TaskRunner() {
            @Override
            public <T> CompletableFuture<T> submitRemote(String title, Work<T> work) {
                try {
                    return CompletableFuture.completedFuture(work.run(ProgressListener.NONE));
                } catch (Exception e) {
                    return CompletableFuture.failedFuture(e);
                }
            }
        };
        var injector = Guice.createInjector(Modules.override(
                new AppModule(ModelStore.defaultRoot(), new AppSettings(temp.resolve("settings.properties"))))
                .with(new AbstractModule() {
                    @Override protected void configure() {
                        bind(TaskRunner.class).toInstance(headless);
                    }
                }));
        LocalMcpServer server = injector.getInstance(LocalMcpServer.class);
        try {
            server.start(0, TOKEN);
            JsonNode spoken = call(server, "synthesize", JSON.createObjectNode()
                    .put("text", "The quick brown fox jumps over the lazy dog.").put("allow_download", true));
            String wav = spoken.at("/result/content/1/data").asText();
            assertFalse(wav.isEmpty());
            JsonNode transcription = call(server, "transcribe", JSON.createObjectNode()
                    .put("audio_base64", wav).put("word_timestamps", true).put("allow_download", true));
            String text = transcription.at("/result/structuredContent/text").asText().toLowerCase(Locale.ROOT);
            assertTrue(text.contains("fox") && text.contains("dog"), transcription.toString());
            assertFalse(transcription.at("/result/structuredContent/words").isEmpty());
            for (String name : List.of("generate_music", "generate_sound_effects")) {
                JsonNode generated = call(server, name, JSON.createObjectNode().put("prompt",
                        name.equals("generate_music") ? "Gentle instrumental piano background music" : "Rain falling")
                        .put("duration_seconds", 1).put("seed", 42).put("accept_license", true).put("allow_download", true));
                AudioClip clip = WavIO.parseWav(Base64.getDecoder().decode(generated.at("/result/content/1/data").asText()));
                assertEquals(1, clip.durationSeconds(), .05);
                double energy = 0;
                for (float sample : clip.samples()) {
                    assertTrue(Float.isFinite(sample));
                    energy += sample * sample;
                }
                assertTrue(energy / clip.samples().length > 1e-10, name + " must not return silence");
            }
        } finally {
            server.close();
            headless.shutdown();
            injector.getInstance(Key.get(new TypeLiteral<List<TextToSpeechService>>() {}))
                    .forEach(TextToSpeechService::close);
            injector.getInstance(Key.get(new TypeLiteral<List<AudioGenerationService>>() {}))
                    .forEach(AudioGenerationService::close);
        }
    }

    private static JsonNode call(LocalMcpServer server, String name, ObjectNode args) throws Exception {
        ObjectNode message = JSON.createObjectNode().put("jsonrpc", "2.0").put("id", 1).put("method", "tools/call");
        message.putObject("params").put("name", name).set("arguments", args);
        HttpRequest request = HttpRequest.newBuilder(server.endpoint()).timeout(Duration.ofMinutes(15))
                .header("Authorization", "Bearer " + TOKEN).header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream").header("MCP-Protocol-Version", "2025-11-25")
                .POST(HttpRequest.BodyPublishers.ofString(message.toString())).build();
        HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());
        JsonNode result = JSON.readTree(response.body());
        assertFalse(result.has("error"), result.toString());
        assertFalse(result.at("/result/isError").asBoolean(), result.toString());
        return result;
    }
}
