package net.talaatharb.stttts.tts.pocket;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** The {@code bundle.json} metadata of one Pocket TTS ONNX language bundle. */
public record PocketBundle(int sampleRate, double frameRate, int latentDim, int conditioningDim,
        int maxTokensPerChunk, boolean removeSemicolons, boolean padShortInputs, Integer recommendedFramesAfterEos,
        boolean insertBosBeforeVoice, String bosFile, String tokenizerFile, List<StateEntry> flowState,
        List<StateEntry> mimiState) {

    /** One recurrent state tensor fed to a model as {@code inputName} and returned at output {@code index}. */
    public record StateEntry(String inputName, int index, String module, String key, long[] shape, String dtype,
            String fill) {
        public long elementCount() {
            long count = 1;
            for (long dim : shape) {
                count *= dim;
            }
            return count;
        }
    }

    public static PocketBundle read(Path file) throws IOException {
        return parse(new ObjectMapper().readTree(file.toFile()));
    }

    static PocketBundle parse(JsonNode json) {
        JsonNode frames = json.path("model_recommended_frames_after_eos");
        String bos = json.path("bos_before_voice_file").asText(null);
        return new PocketBundle(json.path("sample_rate").asInt(24_000), json.path("frame_rate").asDouble(12.5),
                json.path("latent_dim").asInt(32), json.path("conditioning_dim").asInt(1024),
                json.path("max_token_per_chunk").asInt(50), json.path("remove_semicolons").asBoolean(false),
                json.path("pad_with_spaces_for_short_inputs").asBoolean(false),
                frames.isNumber() ? frames.asInt() : null, json.path("insert_bos_before_voice").asBoolean(false),
                bos == null || bos.isEmpty() ? null : bos, json.path("tokenizer_file").asText("tokenizer.model"),
                entries(json.path("flow_lm_state_manifest")), entries(json.path("mimi_state_manifest")));
    }

    private static List<StateEntry> entries(JsonNode manifest) {
        List<StateEntry> entries = new ArrayList<>();
        for (JsonNode node : manifest) {
            JsonNode shapeNode = node.path("shape");
            long[] shape = new long[shapeNode.size()];
            for (int i = 0; i < shape.length; i++) {
                shape[i] = shapeNode.get(i).asLong();
            }
            entries.add(new StateEntry(node.path("input_name").asText(), node.path("index").asInt(),
                    node.path("module").asText(), node.path("key").asText(), shape, node.path("dtype").asText(),
                    node.path("fill").asText("zeros")));
        }
        return entries;
    }
}
