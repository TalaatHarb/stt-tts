package net.talaatharb.stttts.tts.pocket;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Reader for the {@code .safetensors} format (8 byte header length, JSON header, raw little endian data). */
public final class SafeTensors {
    /** One tensor; {@code data} is a little endian view of exactly the tensor's bytes. */
    public record Tensor(String dtype, long[] shape, ByteBuffer data) {
        public long elementCount() {
            long count = 1;
            for (long dim : shape) {
                count *= dim;
            }
            return count;
        }

        public float[] floats() {
            float[] values = new float[(int) elementCount()];
            ByteBuffer buffer = data.duplicate().order(ByteOrder.LITTLE_ENDIAN);
            switch (dtype) {
                case "F32" -> buffer.asFloatBuffer().get(values);
                case "F64" -> {
                    for (int i = 0; i < values.length; i++) {
                        values[i] = (float) buffer.getDouble();
                    }
                }
                case "F16" -> {
                    for (int i = 0; i < values.length; i++) {
                        values[i] = halfToFloat(buffer.getShort());
                    }
                }
                case "BF16" -> {
                    for (int i = 0; i < values.length; i++) {
                        values[i] = Float.intBitsToFloat((buffer.getShort() & 0xffff) << 16);
                    }
                }
                default -> {
                    long[] longs = longs();
                    for (int i = 0; i < values.length; i++) {
                        values[i] = longs[i];
                    }
                }
            }
            return values;
        }

        public long[] longs() {
            long[] values = new long[(int) elementCount()];
            ByteBuffer buffer = data.duplicate().order(ByteOrder.LITTLE_ENDIAN);
            for (int i = 0; i < values.length; i++) {
                values[i] = switch (dtype) {
                    case "I64", "U64" -> buffer.getLong();
                    case "I32" -> buffer.getInt();
                    case "U32" -> buffer.getInt() & 0xffffffffL;
                    case "I16" -> buffer.getShort();
                    case "I8" -> buffer.get();
                    case "U8", "BOOL" -> buffer.get() & 0xff;
                    case "F32" -> (long) buffer.getFloat();
                    case "F64" -> (long) buffer.getDouble();
                    default -> throw new IllegalStateException("Unsupported safetensors dtype " + dtype);
                };
            }
            return values;
        }
    }

    private SafeTensors() {
    }

    static float halfToFloat(short half) {
        int bits = half & 0xffff;
        int sign = (bits & 0x8000) << 16;
        int exponent = (bits >>> 10) & 0x1f;
        int mantissa = bits & 0x3ff;
        if (exponent == 0x1f) {
            return Float.intBitsToFloat(sign | 0x7f800000 | (mantissa << 13));
        }
        if (exponent == 0) {
            float value = mantissa * 0x1p-24f;
            return sign == 0 ? value : -value;
        }
        return Float.intBitsToFloat(sign | ((exponent + 112) << 23) | (mantissa << 13));
    }

    public static Map<String, Tensor> read(Path file) throws IOException {
        return parse(Files.readAllBytes(file));
    }

    public static Map<String, Tensor> parse(byte[] bytes) throws IOException {
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        long headerLength = buffer.getLong();
        if (headerLength <= 0 || headerLength > bytes.length - 8) {
            throw new IOException("Not a safetensors file");
        }
        JsonNode header = new ObjectMapper().readTree(new String(bytes, 8, (int) headerLength,
                StandardCharsets.UTF_8));
        int dataStart = 8 + (int) headerLength;
        Map<String, Tensor> tensors = new LinkedHashMap<>();
        Iterator<Map.Entry<String, JsonNode>> fields = header.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            if (field.getKey().equals("__metadata__")) {
                continue;
            }
            JsonNode node = field.getValue();
            JsonNode shapeNode = node.path("shape");
            long[] shape = new long[shapeNode.size()];
            for (int i = 0; i < shape.length; i++) {
                shape[i] = shapeNode.get(i).asLong();
            }
            int begin = dataStart + node.path("data_offsets").get(0).asInt();
            int end = dataStart + node.path("data_offsets").get(1).asInt();
            if (begin < dataStart || end > bytes.length || end < begin) {
                throw new IOException("Corrupt safetensors entry " + field.getKey());
            }
            ByteBuffer data = ByteBuffer.wrap(bytes, begin, end - begin).slice().order(ByteOrder.LITTLE_ENDIAN);
            tensors.put(field.getKey(), new Tensor(node.path("dtype").asText(), shape, data));
        }
        return tensors;
    }
}
