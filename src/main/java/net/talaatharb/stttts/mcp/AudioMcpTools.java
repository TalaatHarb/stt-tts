package net.talaatharb.stttts.mcp;

import java.io.IOException;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.inject.Inject;
import com.google.inject.Singleton;

import net.talaatharb.stttts.audio.AudioClip;
import net.talaatharb.stttts.audio.WavIO;
import net.talaatharb.stttts.config.AppSettings;
import net.talaatharb.stttts.model.ProgressListener;
import net.talaatharb.stttts.service.AudioGenerationRequest;
import net.talaatharb.stttts.service.AudioGenerationService;
import net.talaatharb.stttts.service.SpeechModelService;
import net.talaatharb.stttts.service.SpeechRequest;
import net.talaatharb.stttts.service.SpeechToTextService;
import net.talaatharb.stttts.service.TextToSpeechService;
import net.talaatharb.stttts.service.Transcription;
import net.talaatharb.stttts.service.TranscriptionRequest;
import net.talaatharb.stttts.service.Voice;
import net.talaatharb.stttts.ui.TaskRunner;

/** Bounded audio tools using the same services and sequential worker as the desktop. */
@Singleton
public class AudioMcpTools {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final AppSettings settings;
    private final SpeechToTextService stt;
    private final List<TextToSpeechService> tts;
    private final List<AudioGenerationService> generation;
    private final TaskRunner runner;
    private final ArrayNode definitions = JSON.createArrayNode();

    @Inject
    public AudioMcpTools(AppSettings settings, SpeechToTextService stt, List<TextToSpeechService> tts,
            List<AudioGenerationService> generation, TaskRunner runner) {
        this.settings = settings;
        this.stt = stt;
        this.tts = tts;
        this.generation = generation;
        this.runner = runner;
        define("list_models", "List model IDs, speech voices/languages, configured defaults and generation licenses.",
                JSON.createObjectNode(), List.of());
        ObjectNode transcribe = JSON.createObjectNode();
        transcribe.set("audio_base64", string(1, 12 * 1024 * 1024));
        transcribe.set("language", string(1, 32));
        transcribe.set("word_timestamps", type("boolean"));
        common(transcribe);
        define("transcribe", "Transcribe a base64 RIFF WAV (at most 8 MiB and 120 seconds). "
                + "Defaults to Whistle; audio stays local.", transcribe, List.of("audio_base64"));
        ObjectNode speak = JSON.createObjectNode();
        speak.set("text", string(1, 2000));
        speak.set("voice", string(1, 128));
        speak.set("language", string(1, 32));
        speak.set("speed", number(0.5, 2));
        common(speak);
        define("synthesize", "Generate speech as base64 audio/wav. Defaults to fast CPU Kokoro; "
                + "omit voice/language to use the model's first voice.", speak, List.of("text"));
        for (String name : List.of("generate_music", "generate_sound_effects")) {
            ObjectNode properties = JSON.createObjectNode();
            properties.set("prompt", string(1, 2000));
            properties.set("duration_seconds", number(1, 10));
            properties.set("seed", type("integer"));
            properties.set("accept_license", type("boolean"));
            common(properties);
            define(name, "Generate 1-10 seconds of local audio/wav. "
                    + "Read the selected model's terms from list_models and explicitly set accept_license=true. "
                    + "MusicGen is non-commercial; Stable Audio requires Community License and Gemma terms "
                    + "(Powered by Stability AI). CPU inference can take minutes.", properties,
                    List.of("prompt", "accept_license"));
        }
    }

    private static ObjectNode type(String name) {
        return JSON.createObjectNode().put("type", name);
    }

    private static ObjectNode string(int min, int max) {
        return type("string").put("minLength", min).put("maxLength", max);
    }

    private static ObjectNode number(double min, double max) {
        return type("number").put("minimum", min).put("maximum", max);
    }

    private static void common(ObjectNode properties) {
        properties.set("model", string(1, 128));
        properties.set("allow_download", type("boolean").put("default", false)
                .put("description", "Explicitly permit downloading missing model files; otherwise fail if not installed."));
    }

    private void define(String name, String description, ObjectNode properties, List<String> required) {
        ObjectNode tool = definitions.addObject().put("name", name).put("description", description);
        ObjectNode schema = tool.putObject("inputSchema").put("type", "object").put("additionalProperties", false);
        schema.set("properties", properties);
        schema.set("required", JSON.valueToTree(required));
    }

    public ArrayNode definitions() {
        return definitions.deepCopy();
    }

    public boolean hasTool(String name) {
        for (JsonNode definition : definitions) {
            if (definition.path("name").asText().equals(name)) {
                return true;
            }
        }
        return false;
    }

    public void validateDefaults() {
        select(List.of(stt), settings.mcpModel("stt"));
        select(tts, settings.mcpModel("tts"));
        generationModel("music", settings.mcpModel("music"));
        generationModel("sfx", settings.mcpModel("sfx"));
    }

    public CompletableFuture<ObjectNode> call(String name, JsonNode arguments) {
        try {
            validate(name, arguments);
            if ("list_models".equals(name)) {
                return CompletableFuture.completedFuture(textResult(catalog()));
            }
            return runner.submitRemote("MCP: " + name, progress -> execute(name, arguments, progress));
        } catch (IllegalArgumentException e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    private ObjectNode execute(String name, JsonNode args, ProgressListener progress) throws IOException {
        if ("transcribe".equals(name)) {
            SpeechToTextService service = select(List.of(stt), model(args, "stt"));
            byte[] bytes = Base64.getDecoder().decode(args.get("audio_base64").textValue());
            if (bytes.length > 8 * 1024 * 1024) {
                throw new IllegalArgumentException("WAV must not exceed 8 MiB");
            }
            AudioClip audio = WavIO.parseWav(bytes);
            if (audio.isEmpty() || audio.durationSeconds() > 120
                    || audio.sampleRate() < 8000 || audio.sampleRate() > 192000) {
                throw new IllegalArgumentException("WAV must contain 0-120 seconds of audio at 8000-192000 Hz");
            }
            for (float sample : audio.samples()) {
                if (!Float.isFinite(sample)) {
                    throw new IllegalArgumentException("WAV contains non-finite samples");
                }
            }
            String language = args.path("language").asText("auto");
            if (service.languages().stream().noneMatch(item -> item.code().equals(language))) {
                throw new IllegalArgumentException("Unsupported STT language: " + language);
            }
            checkDownload(service, args);
            Transcription result = service.transcribe(audio,
                    new TranscriptionRequest(language, args.path("word_timestamps").asBoolean(false)), progress);
            ObjectNode data = JSON.createObjectNode().put("text", result.text()).put("language", result.language())
                    .put("elapsed_seconds", result.elapsed().toNanos() / 1_000_000_000.0);
            data.set("words", JSON.valueToTree(result.words()));
            return textResult(data);
        }
        if ("synthesize".equals(name)) {
            TextToSpeechService service = select(tts, model(args, "tts"));
            String requestedVoice = args.path("voice").asText(null);
            String requestedLanguage = args.path("language").asText(null);
            Voice voice = service.voices().stream()
                    .filter(item -> requestedVoice == null ? requestedLanguage == null || item.languageCode() == null
                            || item.languageCode().equals(requestedLanguage) : item.id().equals(requestedVoice))
                    .findFirst().orElseThrow(() -> new IllegalArgumentException("No matching voice for the request"));
            String voiceId = voice.id();
            String language = requestedLanguage != null ? requestedLanguage
                    : voice.languageCode() != null ? voice.languageCode() : service.languages().get(0).code();
            if (service.languages().stream().noneMatch(item -> item.code().equals(language))) {
                throw new IllegalArgumentException("Unsupported TTS language: " + language);
            }
            if (voice.languageCode() != null && !voice.languageCode().equals(language)) {
                throw new IllegalArgumentException("Voice " + voiceId + " requires language " + voice.languageCode());
            }
            SpeechRequest request = new SpeechRequest(args.get("text").textValue(),
                    voiceId, language, (float) args.path("speed").asDouble(1));
            checkDownload(service, args, service.isInstalled(request));
            return audioResult(service.id(), service.synthesize(request, progress));
        }
        String kind = "generate_music".equals(name) ? "music" : "sfx";
        AudioGenerationService service = generationModel(kind, model(args, kind));
        if (!args.path("accept_license").asBoolean(false)) {
            throw new IllegalArgumentException("Read and accept " + service.licenseName() + ": " + service.licenseUrl()
                    + "; then set accept_license=true");
        }
        double duration = args.path("duration_seconds").asDouble(5);
        if (duration > service.maxDurationSeconds()) {
            throw new IllegalArgumentException("Model duration limit is " + service.maxDurationSeconds());
        }
        checkDownload(service, args);
        // Like the desktop engine selector, release competing generation engines before opening another one.
        generation.stream().filter(other -> other != service).forEach(AudioGenerationService::close);
        return audioResult(service.id(), service.generate(new AudioGenerationRequest(args.get("prompt").textValue(),
                duration, args.path("seed").asLong(42)), progress));
    }

    private String model(JsonNode args, String kind) {
        return args.path("model").asText(settings.mcpModel(kind));
    }

    private AudioGenerationService generationModel(String kind, String id) {
        if ("sfx".equals(kind) && !"stable-audio-3-sfx".equals(id)
                || "music".equals(kind) && !List.of("musicgen-small", "stable-audio-3-music").contains(id)) {
            throw new IllegalArgumentException("Model " + id + " does not support " + kind);
        }
        return select(generation, id);
    }

    private static <T extends SpeechModelService> T select(List<T> services, String id) {
        return services.stream().filter(service -> service.id().equals(id)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown model: " + id));
    }

    private static void checkDownload(SpeechModelService service, JsonNode args) {
        checkDownload(service, args, service.isInstalled());
    }

    private static void checkDownload(SpeechModelService service, JsonNode args, boolean installed) {
        if (!installed && !args.path("allow_download").asBoolean(false)) {
            throw new IllegalArgumentException(service.displayName()
                    + " needs model/voice/language files. Install them in the desktop app "
                    + "or explicitly set allow_download=true.");
        }
    }

    private ObjectNode catalog() {
        ObjectNode data = JSON.createObjectNode();
        ObjectNode defaults = data.putObject("defaults");
        for (String kind : List.of("stt", "tts", "music", "sfx")) {
            defaults.put(kind, settings.mcpModel(kind));
        }
        ArrayNode models = data.putArray("models");
        describe(models, stt, "stt").set("languages", JSON.valueToTree(stt.languages()));
        for (TextToSpeechService service : tts) {
            ObjectNode entry = describe(models, service, "tts");
            entry.set("voices", JSON.valueToTree(service.voices()));
            entry.set("languages", JSON.valueToTree(service.languages()));
        }
        for (AudioGenerationService service : generation) {
            describe(models, service, service.id().endsWith("-sfx") ? "sfx" : "music")
                    .put("license", service.licenseName()).put("license_url", service.licenseUrl())
                    .put("max_duration_seconds", service.maxDurationSeconds());
        }
        return data;
    }

    private static ObjectNode describe(ArrayNode models, SpeechModelService service, String kind) {
        return models.addObject().put("id", service.id()).put("kind", kind)
                .put("name", service.displayName()).put("description", service.description());
    }

    private static ObjectNode textResult(ObjectNode data) {
        ObjectNode result = JSON.createObjectNode().put("isError", false);
        result.set("structuredContent", data);
        result.putArray("content").addObject().put("type", "text").put("text", data.toString());
        return result;
    }

    private static ObjectNode audioResult(String model, AudioClip audio) {
        ObjectNode result = textResult(JSON.createObjectNode().put("model", model).put("sample_rate", audio.sampleRate())
                .put("duration_seconds", audio.durationSeconds()).put("channels", 1));
        ((ArrayNode) result.get("content")).addObject().put("type", "audio").put("mimeType", "audio/wav")
                .put("data", Base64.getEncoder().encodeToString(WavIO.toWavBytes(audio)));
        return result;
    }

    private void validate(String name, JsonNode args) {
        JsonNode schema = null;
        for (JsonNode definition : definitions) {
            if (definition.path("name").asText().equals(name)) {
                schema = definition.get("inputSchema");
                break;
            }
        }
        if (schema == null || !args.isObject()) {
            throw new IllegalArgumentException("Unknown tool or arguments must be an object");
        }
        for (JsonNode field : schema.get("required")) {
            if (!args.has(field.asText())) {
                throw new IllegalArgumentException("Missing argument: " + field.asText());
            }
        }
        var names = args.fieldNames();
        while (names.hasNext()) {
            String field = names.next();
            JsonNode rule = schema.get("properties").get(field);
            JsonNode value = args.get(field);
            if (rule == null) {
                throw new IllegalArgumentException("Unknown argument: " + field);
            }
            boolean valid = switch (rule.get("type").asText()) {
                case "boolean" -> value.isBoolean();
                case "integer" -> value.isIntegralNumber() && value.canConvertToLong();
                case "number" -> value.isNumber() && Double.isFinite(value.doubleValue())
                        && value.doubleValue() >= rule.get("minimum").doubleValue()
                        && value.doubleValue() <= rule.get("maximum").doubleValue();
                case "string" -> value.isTextual() && !value.textValue().isBlank()
                        && value.textValue().length() >= rule.get("minLength").intValue()
                        && value.textValue().length() <= rule.get("maxLength").intValue();
                default -> false;
            };
            if (!valid) {
                throw new IllegalArgumentException("Invalid argument: " + field + "; expected " + rule);
            }
        }
    }
}
