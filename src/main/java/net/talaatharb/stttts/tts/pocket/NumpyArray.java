package net.talaatharb.stttts.tts.pocket;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reader for little endian float32 {@code .npy} arrays. */
public final class NumpyArray {
    private static final Pattern SHAPE = Pattern.compile("'shape':\\s*\\(([^)]*)\\)");

    private final long[] shape;
    private final float[] data;

    private NumpyArray(long[] shape, float[] data) {
        this.shape = shape;
        this.data = data;
    }

    public long[] shape() {
        return shape.clone();
    }

    public float[] data() {
        return data;
    }

    public static NumpyArray read(Path file) throws IOException {
        return parse(Files.readAllBytes(file));
    }

    public static NumpyArray parse(byte[] bytes) throws IOException {
        if (bytes.length < 10 || bytes[0] != (byte) 0x93 || bytes[1] != 'N' || bytes[2] != 'U') {
            throw new IOException("Not a .npy file");
        }
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        int major = bytes[6];
        int headerLength;
        int headerStart;
        if (major == 1) {
            headerLength = buffer.getShort(8) & 0xffff;
            headerStart = 10;
        } else {
            headerLength = buffer.getInt(8);
            headerStart = 12;
        }
        String header = new String(bytes, headerStart, headerLength, StandardCharsets.ISO_8859_1);
        if (!header.contains("'<f4'") || header.contains("'fortran_order': True")) {
            throw new IOException("Only C ordered little endian float32 arrays are supported: " + header.trim());
        }
        Matcher matcher = SHAPE.matcher(header);
        if (!matcher.find()) {
            throw new IOException("Missing shape in .npy header");
        }
        String[] parts = matcher.group(1).split(",");
        long[] shape = java.util.Arrays.stream(parts).map(String::trim).filter(s -> !s.isEmpty())
                .mapToLong(Long::parseLong).toArray();
        buffer.position(headerStart + headerLength);
        float[] data = new float[buffer.remaining() / Float.BYTES];
        buffer.asFloatBuffer().get(data);
        return new NumpyArray(shape, data);
    }
}
