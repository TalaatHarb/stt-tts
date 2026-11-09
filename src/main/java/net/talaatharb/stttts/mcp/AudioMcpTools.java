package net.talaatharb.stttts.mcp;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.function.Predicate;

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
import net.talaatharb.stttts.service.Language;
import net.talaatharb.stttts.service.SpeechModelService;
import net.talaatharb.stttts.service.SpeechRequest;
import net.talaatharb.stttts.service.SpeechToTextService;
import net.talaatharb.stttts.service.TextToSpeechService;
import net.talaatharb.stttts.service.Transcription;
import net.talaatharb.stttts.service.TranscriptionRequest;
import net.talaatharb.stttts.service.Voice;
import net.talaatharb.stttts.ui.TaskRunner;

/**
 * Bounded audio tools using the same services and sequential worker as the desktop.
 * Optional settings are forgiving: blank, placeholder ("default", "auto", ...), unknown or out-of-range values fall
 * back to defaults and are reported as {@code warnings}; only essential input, license consent and download
 * permission fail a call.
 */
@Singleton
public class AudioMcpTools {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_TEXT = 2000;
    private static final int MAX_WAV_BYTES = 8 * 1024 * 1024;
    private static final Set<String> PLACEHOLDERS = Set.of("", "default", "defaults", "auto", "automatic", "none",
            "null", "nil", "any", "unspecified", "undefined", "n/a", "na", "-");
    private static final Map<String, String> ALIASES = Map.ofEntries(Map.entry("audio", "audio_base64"),
            Map.entry("audio_data", "audio_base64"), Map.entry("wav", "audio_base64"),
            Map.entry("wav_base64", "audio_base64"), Map.entry("data", "audio_base64"), Map.entry("input", "text"),
            Map.entry("speaker", "voice"), Map.entry("voice_id", "voice"), Map.entry("lang", "language"),
            Map.entry("rate", "speed"), Map.entry("duration", "duration_seconds"),
            Map.entry("seconds", "duration_seconds"), Map.entry("length", "duration_seconds"),
            Map.entry("model_id", "model"), Map.entry("engine", "model"), Map.entry("description", "prompt"),
            Map.entry("timestamps", "word_timestamps"), Map.entry("download", "allow_download"),
            Map.entry("license_accepted", "accept_license"), Map.entry("accept_licence", "accept_license"));
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
        transcribe.set("audio_base64", string(1, 12 * 1024 * 1024, "Base64 RIFF WAV (a data: URI prefix is accepted)."));
        transcribe.set("language", optional("Language code; omit or 'auto' to auto-detect."));
        transcribe.set("word_timestamps", type("boolean").put("default", false));
        common(transcribe);
        define("transcribe", "Transcribe a base64 RIFF WAV (at most 8 MiB and 120 seconds). "
                + "Defaults to Whistle; audio stays local.", transcribe, List.of("audio_base64"));
        ObjectNode speak = JSON.createObjectNode();
        speak.set("text", string(1, MAX_TEXT, "Text to speak."));
        speak.set("voice", optional("Voice ID or name from list_models; omit or 'default' for the model's default voice."));
        speak.set("language", optional("Language code; omit or 'default' to use the voice's language."));
        speak.set("speed", number(0.5, 2, 1, "Speech rate; out-of-range values are clamped."));
        common(speak);
        define("synthesize", "Generate speech as base64 audio/wav. Defaults to fast CPU Kokoro; "
                + "omit voice/language to use the model's first voice.", speak, List.of("text"));
        for (String name : List.of("generate_music", "generate_sound_effects")) {
            ObjectNode properties = JSON.createObjectNode();
            properties.set("prompt", string(1, MAX_TEXT, "Description of the audio to generate."));
            properties.set("duration_seconds", number(1, 10, 5, "Length; clamped to the model's limit."));
            properties.set("seed", type("integer").put("default", 42));
            properties.set("accept_license", type("boolean")
                    .put("description", "Must be true after reading the model license from list_models."));
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

    private static ObjectNode string(int min, int max, String description) {
        return type("string").put("minLength", min).put("maxLength", max).put("description", description);
    }

    private static ObjectNode optional(String description) {
        return type("string").put("description", description);
    }

    private static ObjectNode number(double min, double max, double fallback, String description) {
        return type("number").put("minimum", min).put("maximum", max).put("default", fallback)
                .put("description", description);
    }

    private static void common(ObjectNode properties) {
        properties.set("model", optional("Model ID from list_models; omit or 'default' for the configured default."));
        properties.set("allow_download", type("boolean").put("default", false)
                .put("description", "Explicitly permit downloading missing model files; otherwise fail if not installed."));
    }

    private void define(String name, String description, ObjectNode properties, List<String> required) {
        ObjectNode tool = definitions.addObject().put("name", name).put("description", description);
        ObjectNode schema = tool.putObject("inputSchema").put("type", "object");
        schema.set("properties", properties);
        schema.set("required", JSON.valueToTree(required));
    }

    public ArrayNode definitions() {
        return definitions.deepCopy();
    }

    public boolean hasTool(String name) {
        return schema(name) != null;
    }

    private JsonNode schema(String name) {
        for (JsonNode definition : definitions) {
            if (definition.path("name").asText().equals(name)) {
                return definition.get("inputSchema");
            }
        }
        return null;
    }

    public void validateDefaults() {
        select(List.of(stt), settings.mcpModel("stt"));
        select(tts, settings.mcpModel("tts"));
        for (String kind : List.of("music", "sfx")) {
            AudioGenerationService service = select(generation, settings.mcpModel(kind));
            if (!kind.equals(kind(service))) {
                throw new IllegalArgumentException("Model " + service.id() + " does not support " + kind);
            }
        }
    }

    public CompletableFuture<ObjectNode> call(String name, JsonNode arguments) {
        try {
            JsonNode schema = schema(name);
            if (schema == null) {
                throw new IllegalArgumentException("Unknown tool: " + name);
            }
            Call call = new Call(schema.get("properties"), arguments);
            if ("list_models".equals(name)) {
                return CompletableFuture.completedFuture(textResult(catalog(), call));
            }
            return runner.submitRemote("MCP: " + name, progress -> execute(name, call, progress));
        } catch (IllegalArgumentException e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    private ObjectNode execute(String name, Call call, ProgressListener progress) throws IOException {
        if ("transcribe".equals(name)) {
            AudioClip audio = decodeWav(call.required("audio_base64", 12 * 1024 * 1024));
            SpeechToTextService service = resolve(call, List.of(stt), "stt", any -> true);
            String language = sttLanguage(call, service);
            checkDownload(service, call);
            Transcription result = service.transcribe(audio,
                    new TranscriptionRequest(language, call.flag("word_timestamps", false)), progress);
            ObjectNode data = JSON.createObjectNode().put("model", service.id()).put("text", result.text())
                    .put("language", result.language())
                    .put("elapsed_seconds", result.elapsed().toNanos() / 1_000_000_000.0);
            data.set("words", JSON.valueToTree(result.words()));
            return textResult(data, call);
        }
        if ("synthesize".equals(name)) {
            String text = call.required("text", MAX_TEXT);
            TextToSpeechService service = resolve(call, tts, "tts", any -> true);
            SpeechRequest request = speechRequest(call, service, text);
            checkDownload(service, call, service.isInstalled(request));
            return audioResult(service.id(), service.synthesize(request, progress), call);
        }
        String prompt = call.required("prompt", MAX_TEXT);
        String kind = "generate_music".equals(name) ? "music" : "sfx";
        AudioGenerationService service = resolve(call, generation, kind, candidate -> kind.equals(kind(candidate)));
        if (!call.flag("accept_license", false)) {
            throw new IllegalArgumentException("Read and accept " + service.licenseName() + ": " + service.licenseUrl()
                    + "; then set accept_license=true");
        }
        double duration = call.number("duration_seconds", 5, 1, Math.min(10, service.maxDurationSeconds()));
        long seed = call.integer("seed", 42);
        checkDownload(service, call);
        // Like the desktop engine selector, release competing generation engines before opening another one.
        generation.stream().filter(other -> other != service).forEach(AudioGenerationService::close);
        return audioResult(service.id(), service.generate(new AudioGenerationRequest(prompt, duration, seed), progress),
                call);
    }

    private static AudioClip decodeWav(String encoded) throws IOException {
        String data = encoded.strip();
        int comma = data.indexOf(',');
        if (data.regionMatches(true, 0, "data:", 0, 5) && comma > 0) {
            data = data.substring(comma + 1);
        }
        data = data.replaceAll("\\s", "");
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(data);
        } catch (IllegalArgumentException e) {
            try {
                bytes = Base64.getUrlDecoder().decode(data);
            } catch (IllegalArgumentException again) {
                throw new IllegalArgumentException("audio_base64 is not valid base64");
            }
        }
        if (bytes.length > MAX_WAV_BYTES) {
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
        return audio;
    }

    private static String sttLanguage(Call call, SpeechToTextService service) {
        String fallback = service.languages().stream().anyMatch(item -> Language.AUTO.code().equals(item.code()))
                ? Language.AUTO.code() : service.languages().get(0).code();
        String requested = call.text("language");
        if (requested == null) {
            return fallback;
        }
        Optional<String> match = language(service.languages(), requested);
        if (match.isEmpty()) {
            call.warn("Language '" + requested + "' is not supported by " + service.id() + "; using " + fallback);
        }
        return match.orElse(fallback);
    }

    private static SpeechRequest speechRequest(Call call, TextToSpeechService service, String text) {
        List<Voice> voices = service.voices();
        if (voices.isEmpty()) {
            throw new IllegalStateException(service.displayName() + " has no voices");
        }
        String language = null;
        String requestedLanguage = call.text("language");
        if (requestedLanguage != null) {
            language = language(service.languages(), requestedLanguage).orElse(null);
            if (language == null) {
                call.warn("Language '" + requestedLanguage + "' is not supported by " + service.id()
                        + "; using the voice's language");
            }
        }
        Voice voice = null;
        String requestedVoice = call.text("voice");
        if (requestedVoice != null) {
            voice = find(voices, requestedVoice, Voice::id, Voice::name).orElse(null);
            if (voice == null) {
                call.warn("Voice '" + requestedVoice + "' is not available for " + service.id()
                        + "; using a default voice");
            } else if (language != null && voice.languageCode() != null && !voice.languageCode().equals(language)) {
                call.warn("Voice " + voice.id() + " speaks " + voice.languageCode() + "; ignoring language " + language);
                language = null;
            }
        }
        if (voice == null) {
            String wanted = language;
            voice = voices.stream().filter(item -> wanted == null || item.languageCode() == null
                    || item.languageCode().equals(wanted)).findFirst().orElse(null);
            if (voice == null) {
                voice = voices.get(0);
                call.warn("No " + service.id() + " voice speaks " + language + "; using " + voice.id());
                language = null;
            }
        }
        if (language == null) {
            language = voice.languageCode() != null ? voice.languageCode() : service.languages().get(0).code();
        }
        return new SpeechRequest(text, voice.id(), language,
                (float) call.number("speed", 1, 0.5, 2));
    }

    private static Optional<String> language(List<Language> languages, String requested) {
        Optional<Language> match = find(languages, requested, Language::code, Language::name);
        if (match.isEmpty()) {
            String base = requested.split("[-_]", 2)[0];
            match = languages.stream().filter(item -> item.code().equalsIgnoreCase(base)).findFirst();
        }
        return match.map(Language::code);
    }

    /** Case and punctuation-insensitive match on ID or name, then a unique prefix match. */
    @SafeVarargs
    private static <T> Optional<T> find(List<T> items, String requested, Function<T, String>... names) {
        String wanted = normalize(requested);
        for (T item : items) {
            for (Function<T, String> name : names) {
                if (wanted.equals(normalize(name.apply(item)))) {
                    return Optional.of(item);
                }
            }
        }
        List<T> prefixed = items.stream().filter(item -> {
            String id = normalize(names[0].apply(item));
            return !wanted.isEmpty() && !id.isEmpty() && (id.startsWith(wanted) || wanted.startsWith(id));
        }).toList();
        return prefixed.size() == 1 ? Optional.of(prefixed.get(0)) : Optional.empty();
    }

    private static String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private <T extends SpeechModelService> T resolve(Call call, List<T> services, String kind, Predicate<T> supports) {
        String fallback = settings.mcpModel(kind);
        String requested = call.text("model");
        if (requested != null) {
            Optional<T> match = find(services.stream().filter(supports).toList(), requested,
                    SpeechModelService::id, SpeechModelService::displayName);
            if (match.isPresent()) {
                return match.get();
            }
            call.warn("Model '" + requested + "' is unknown or does not support " + kind
                    + "; using default " + fallback);
        }
        return select(services, fallback);
    }

    private static String kind(AudioGenerationService service) {
        return service.id().endsWith("-sfx") ? "sfx" : "music";
    }

    private static <T extends SpeechModelService> T select(List<T> services, String id) {
        return services.stream().filter(service -> service.id().equals(id)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown model: " + id));
    }

    private static void checkDownload(SpeechModelService service, Call call) {
        checkDownload(service, call, service.isInstalled());
    }

    private static void checkDownload(SpeechModelService service, Call call, boolean installed) {
        if (!installed && !call.flag("allow_download", false)) {
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
            describe(models, service, kind(service))
                    .put("license", service.licenseName()).put("license_url", service.licenseUrl())
                    .put("max_duration_seconds", service.maxDurationSeconds());
        }
        return data;
    }

    private static ObjectNode describe(ArrayNode models, SpeechModelService service, String kind) {
        return models.addObject().put("id", service.id()).put("kind", kind)
                .put("name", service.displayName()).put("description", service.description());
    }

    private static ObjectNode textResult(ObjectNode data, Call call) {
        if (!call.warnings.isEmpty()) {
            data.set("warnings", JSON.valueToTree(call.warnings));
        }
        ObjectNode result = JSON.createObjectNode().put("isError", false);
        result.set("structuredContent", data);
        result.putArray("content").addObject().put("type", "text").put("text", data.toString());
        return result;
    }

    private static ObjectNode audioResult(String model, AudioClip audio, Call call) {
        ObjectNode result = textResult(JSON.createObjectNode().put("model", model).put("sample_rate", audio.sampleRate())
                .put("duration_seconds", audio.durationSeconds()).put("channels", 1), call);
        ((ArrayNode) result.get("content")).addObject().put("type", "audio").put("mimeType", "audio/wav")
                .put("data", Base64.getEncoder().encodeToString(WavIO.toWavBytes(audio)));
        return result;
    }

    /** Normalized arguments of one tool call plus the fallbacks applied to them. */
    private static final class Call {
        private final ObjectNode args = JSON.createObjectNode();
        private final List<String> warnings = new ArrayList<>();

        Call(JsonNode properties, JsonNode arguments) {
            if (arguments == null || arguments.isNull() || arguments.isMissingNode()) {
                return;
            }
            if (!arguments.isObject()) {
                throw new IllegalArgumentException("arguments must be an object");
            }
            Iterator<Map.Entry<String, JsonNode>> fields = arguments.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                String key = field.getKey().replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(Locale.ROOT)
                        .replaceAll("[-\\s]+", "_");
                if (!properties.has(key) && ALIASES.containsKey(key) && properties.has(ALIASES.get(key))) {
                    key = ALIASES.get(key);
                }
                if (!properties.has(key)) {
                    warn("Ignored unknown argument: " + field.getKey());
                } else if (args.has(key)) {
                    warn("Ignored duplicate argument: " + field.getKey());
                } else {
                    args.set(key, field.getValue());
                }
            }
        }

        void warn(String warning) {
            warnings.add(warning);
        }

        /** Essential text; it is used verbatim, so placeholder words are valid content. */
        String required(String field, int max) {
            JsonNode value = args.get(field);
            String text = value == null || !value.isValueNode() || value.isNull() ? null : value.asText();
            if (text == null || text.isBlank()) {
                throw new IllegalArgumentException("Missing argument: " + field);
            }
            if (text.length() > max) {
                throw new IllegalArgumentException(field + " must not exceed " + max + " characters");
            }
            return text;
        }

        /** Optional setting, or null when omitted, blank or a placeholder such as "default". */
        String text(String field) {
            JsonNode value = args.get(field);
            if (value == null || value.isNull()) {
                return null;
            }
            if (!value.isValueNode()) {
                warn("Ignored " + field + ": expected a string");
                return null;
            }
            String text = value.asText().strip();
            return PLACEHOLDERS.contains(text.toLowerCase(Locale.ROOT)) ? null : text;
        }

        boolean flag(String field, boolean fallback) {
            JsonNode value = args.get(field);
            if (value != null && value.isBoolean()) {
                return value.booleanValue();
            }
            if (value != null && value.isNumber() && (value.asDouble() == 0 || value.asDouble() == 1)) {
                return value.asDouble() == 1;
            }
            String text = text(field);
            if (text == null) {
                return fallback;
            }
            switch (text.toLowerCase(Locale.ROOT)) {
                case "true", "yes", "y", "on", "1":
                    return true;
                case "false", "no", "n", "off", "0":
                    return false;
                default:
                    warn("Ignored " + field + "='" + text + "'; using " + fallback);
                    return fallback;
            }
        }

        double number(String field, double fallback, double min, double max) {
            Double value = parse(field);
            if (value == null) {
                return fallback;
            }
            double clamped = Math.max(min, Math.min(max, value));
            if (clamped != value) {
                warn(field + "=" + value + " is outside " + min + "-" + max + "; using " + clamped);
            }
            return clamped;
        }

        long integer(String field, long fallback) {
            Double value = parse(field);
            if (value == null || Math.abs(value) > Long.MAX_VALUE / 2.0) {
                return fallback;
            }
            return Math.round(value);
        }

        private Double parse(String field) {
            JsonNode value = args.get(field);
            if (value != null && value.isNumber() && Double.isFinite(value.doubleValue())) {
                return value.doubleValue();
            }
            String text = text(field);
            if (text == null) {
                return null;
            }
            try {
                double parsed = Double.parseDouble(text.replaceAll("(?i)\\s*(s|sec|secs|seconds|x)$", ""));
                if (Double.isFinite(parsed)) {
                    return parsed;
                }
            } catch (NumberFormatException e) {
                // Reported below.
            }
            warn("Ignored " + field + "='" + text + "'; using the default");
            return null;
        }
    }
}
