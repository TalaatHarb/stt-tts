package net.talaatharb.stttts.audio;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.UnsupportedAudioFileException;

/** Reads RIFF WAV files (PCM 8/16/24/32-bit or 32/64-bit float, any channel count) and writes 16-bit PCM mono. */
public final class WavIO {
    private static final int FORMAT_PCM = 1;
    private static final int FORMAT_FLOAT = 3;
    private static final int FORMAT_EXTENSIBLE = 0xFFFE;

    private WavIO() {
    }

    public static AudioClip read(Path path) throws IOException {
        byte[] data = Files.readAllBytes(path);
        if (data.length >= 12 && "RIFF".equals(ascii(data, 0)) && "WAVE".equals(ascii(data, 8))) {
            return parseWav(data);
        }
        // Fall back to Java Sound for other formats it understands (AIFF, AU).
        try (AudioInputStream in = AudioSystem.getAudioInputStream(path.toFile())) {
            return fromJavaSound(in);
        } catch (UnsupportedAudioFileException e) {
            throw new IOException("Unsupported audio file (use WAV): " + path.getFileName(), e);
        }
    }

    public static AudioClip parseWav(byte[] data) throws IOException {
        if (data.length < 12 || !"RIFF".equals(ascii(data, 0)) || !"WAVE".equals(ascii(data, 8))) {
            throw new IOException("Expected a RIFF WAV file");
        }
        ByteBuffer buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        buffer.position(12);
        int format = -1;
        int channels = 0;
        int sampleRate = 0;
        int bits = 0;
        while (buffer.remaining() >= 8) {
            String id = ascii(data, buffer.position());
            buffer.position(buffer.position() + 4);
            long size = Integer.toUnsignedLong(buffer.getInt());
            int start = buffer.position();
            if (size > buffer.remaining()) {
                throw new IOException("Truncated WAV chunk: " + id);
            }
            int available = (int) size;
            if ("fmt ".equals(id)) {
                if (size < 16) {
                    throw new IOException("WAV fmt chunk is too short");
                }
                format = Short.toUnsignedInt(buffer.getShort());
                channels = Short.toUnsignedInt(buffer.getShort());
                sampleRate = buffer.getInt();
                buffer.getInt();
                buffer.getShort();
                bits = Short.toUnsignedInt(buffer.getShort());
                if (format == FORMAT_EXTENSIBLE && size >= 26) {
                    buffer.position(start + 24);
                    format = Short.toUnsignedInt(buffer.getShort());
                }
            } else if ("data".equals(id)) {
                if (format < 0) {
                    throw new IOException("WAV data chunk appears before fmt chunk");
                }
                return decode(data, start, available, format, channels, sampleRate, bits);
            }
            int next = start + available + (available % 2);
            if (next > data.length) {
                break;
            }
            buffer.position(next);
        }
        throw new IOException("WAV file has no data chunk");
    }

    private static AudioClip decode(byte[] data, int offset, int length, int format, int channels, int rate, int bits)
            throws IOException {
        if (channels <= 0 || rate <= 0) {
            throw new IOException("Invalid WAV header");
        }
        if (format != FORMAT_PCM && format != FORMAT_FLOAT) {
            throw new IOException("Unsupported WAV encoding (format tag " + format + ")");
        }
        if (format == FORMAT_PCM && bits != 8 && bits != 16 && bits != 24 && bits != 32
                || format == FORMAT_FLOAT && bits != 32 && bits != 64) {
            throw new IOException("Unsupported WAV bit depth " + bits);
        }
        int bytesPerSample = bits / 8;
        int frames = length / (bytesPerSample * channels);
        float[] mono = new float[frames];
        ByteBuffer buffer = ByteBuffer.wrap(data, offset, length).order(ByteOrder.LITTLE_ENDIAN);
        for (int frame = 0; frame < frames; frame++) {
            double sum = 0;
            for (int channel = 0; channel < channels; channel++) {
                sum += readSample(buffer, format, bits);
            }
            mono[frame] = (float) (sum / channels);
        }
        return new AudioClip(mono, rate);
    }

    private static double readSample(ByteBuffer buffer, int format, int bits) throws IOException {
        if (format == FORMAT_FLOAT) {
            if (bits == 32) {
                return buffer.getFloat();
            }
            if (bits == 64) {
                return buffer.getDouble();
            }
            throw new IOException("Unsupported float WAV bit depth " + bits);
        }
        switch (bits) {
            case 8:
                return (Byte.toUnsignedInt(buffer.get()) - 128) / 128.0;
            case 16:
                return buffer.getShort() / 32768.0;
            case 24: {
                int b0 = Byte.toUnsignedInt(buffer.get());
                int b1 = Byte.toUnsignedInt(buffer.get());
                int b2 = buffer.get();
                return ((b2 << 16) | (b1 << 8) | b0) / 8388608.0;
            }
            case 32:
                return buffer.getInt() / 2147483648.0;
            default:
                throw new IOException("Unsupported PCM WAV bit depth " + bits);
        }
    }

    private static AudioClip fromJavaSound(AudioInputStream source) throws IOException {
        AudioFormat base = source.getFormat();
        AudioFormat target = new AudioFormat(AudioFormat.Encoding.PCM_SIGNED, base.getSampleRate(), 16,
                base.getChannels(), base.getChannels() * 2, base.getSampleRate(), false);
        try (AudioInputStream pcm = AudioSystem.getAudioInputStream(target, source)) {
            byte[] bytes = pcm.readAllBytes();
            ByteArrayOutputStream wav = new ByteArrayOutputStream();
            writeHeader(wav, Math.round(base.getSampleRate()), base.getChannels(), bytes.length);
            wav.writeBytes(bytes);
            return parseWav(wav.toByteArray());
        }
    }

    public static void write(AudioClip clip, Path path) throws IOException {
        Path parent = path.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.write(path, toWavBytes(clip));
    }

    public static byte[] toWavBytes(AudioClip clip) {
        byte[] pcm = toPcm16(clip.samples());
        ByteArrayOutputStream out = new ByteArrayOutputStream(pcm.length + 44);
        writeHeader(out, clip.sampleRate(), 1, pcm.length);
        out.writeBytes(pcm);
        return out.toByteArray();
    }

    public static byte[] toPcm16(float[] samples) {
        ByteBuffer buffer = ByteBuffer.allocate(samples.length * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (float sample : samples) {
            float clamped = Math.max(-1f, Math.min(1f, sample));
            buffer.putShort((short) Math.round(clamped * 32767f));
        }
        return buffer.array();
    }

    private static void writeHeader(ByteArrayOutputStream out, int rate, int channels, int dataLength) {
        ByteBuffer header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
        header.put("RIFF".getBytes(StandardCharsets.US_ASCII));
        header.putInt(36 + dataLength);
        header.put("WAVE".getBytes(StandardCharsets.US_ASCII));
        header.put("fmt ".getBytes(StandardCharsets.US_ASCII));
        header.putInt(16);
        header.putShort((short) FORMAT_PCM);
        header.putShort((short) channels);
        header.putInt(rate);
        header.putInt(rate * channels * 2);
        header.putShort((short) (channels * 2));
        header.putShort((short) 16);
        header.put("data".getBytes(StandardCharsets.US_ASCII));
        header.putInt(dataLength);
        out.writeBytes(header.array());
    }

    private static String ascii(byte[] data, int offset) {
        if (offset + 4 > data.length) {
            return "";
        }
        return new String(data, offset, 4, StandardCharsets.US_ASCII);
    }
}
