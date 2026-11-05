package net.talaatharb.stttts.model;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * Extracts single entries from a remote zip archive (e.g. a NuGet package) with HTTP range requests, so only the
 * needed bytes are downloaded instead of the whole archive.
 */
public final class RemoteZip {
    private static final int EOCD_SIGNATURE = 0x06054b50;
    private static final int CENTRAL_SIGNATURE = 0x02014b50;
    private static final int LOCAL_SIGNATURE = 0x04034b50;
    private static final int TAIL_BYTES = 1 << 16;

    private final HttpClient client;
    private final String url;
    private ByteBuffer centralDirectory;

    public RemoteZip(String url) {
        this.url = url;
        this.client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.ALWAYS)
                .connectTimeout(Duration.ofSeconds(30))
                .build();
    }

    /** Downloads and inflates {@code entryName} into {@code target}. */
    public void extract(String entryName, Path target, ProgressListener progress) throws IOException {
        Entry entry = find(entryName);
        byte[] header = range(entry.localHeaderOffset, entry.localHeaderOffset + 29, null);
        ByteBuffer local = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
        if (local.getInt(0) != LOCAL_SIGNATURE) {
            throw new IOException("Corrupt zip entry header for " + entryName);
        }
        long dataStart = entry.localHeaderOffset + 30 + (local.getShort(26) & 0xffff) + (local.getShort(28) & 0xffff);
        Files.createDirectories(target.toAbsolutePath().getParent());
        Path partial = target.resolveSibling(target.getFileName() + ".part");
        String name = entryName.substring(entryName.lastIndexOf('/') + 1);
        try (OutputStream out = Files.newOutputStream(partial)) {
            byte[] data = range(dataStart, dataStart + entry.compressedSize - 1, (done, total) -> {
                progress.checkCancelled();
                progress.update(String.format("Downloading %s (%s of %s)", name, HuggingFaceDownloader.size(done),
                        HuggingFaceDownloader.size(total)), done / (double) total);
            });
            if (entry.method == 0) {
                out.write(data);
            } else if (entry.method == 8) {
                inflate(data, out);
            } else {
                throw new IOException("Unsupported zip compression method " + entry.method);
            }
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(partial);
            throw e;
        }
        if (Files.size(partial) != entry.size) {
            Files.deleteIfExists(partial);
            throw new IOException("Size mismatch while extracting " + entryName);
        }
        Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING);
    }

    private static void inflate(byte[] data, OutputStream out) throws IOException {
        Inflater inflater = new Inflater(true);
        try {
            inflater.setInput(data);
            byte[] buffer = new byte[1 << 16];
            while (!inflater.finished()) {
                int count = inflater.inflate(buffer);
                if (count == 0 && (inflater.needsInput() || inflater.needsDictionary())) {
                    break;
                }
                out.write(buffer, 0, count);
            }
        } catch (DataFormatException e) {
            throw new IOException("Corrupt zip data", e);
        } finally {
            inflater.end();
        }
    }

    private record Entry(int method, long compressedSize, long size, long localHeaderOffset) {
    }

    private Entry find(String entryName) throws IOException {
        if (centralDirectory == null) {
            centralDirectory = readCentralDirectory();
        }
        ByteBuffer cd = centralDirectory;
        int position = 0;
        while (position + 46 <= cd.limit() && cd.getInt(position) == CENTRAL_SIGNATURE) {
            int method = cd.getShort(position + 10) & 0xffff;
            long compressed = cd.getInt(position + 20) & 0xffffffffL;
            long size = cd.getInt(position + 24) & 0xffffffffL;
            int nameLength = cd.getShort(position + 28) & 0xffff;
            int extraLength = cd.getShort(position + 30) & 0xffff;
            int commentLength = cd.getShort(position + 32) & 0xffff;
            long offset = cd.getInt(position + 42) & 0xffffffffL;
            byte[] nameBytes = new byte[nameLength];
            cd.get(position + 46, nameBytes);
            if (new String(nameBytes, StandardCharsets.UTF_8).equals(entryName)) {
                return new Entry(method, compressed, size, offset);
            }
            position += 46 + nameLength + extraLength + commentLength;
        }
        throw new IOException("Entry " + entryName + " not found in " + url);
    }

    private ByteBuffer readCentralDirectory() throws IOException {
        byte[] tail = suffix(TAIL_BYTES);
        ByteBuffer buffer = ByteBuffer.wrap(tail).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = tail.length - 22; i >= 0; i--) {
            if (buffer.getInt(i) == EOCD_SIGNATURE) {
                long size = buffer.getInt(i + 12) & 0xffffffffL;
                long offset = buffer.getInt(i + 16) & 0xffffffffL;
                return ByteBuffer.wrap(range(offset, offset + size - 1, null)).order(ByteOrder.LITTLE_ENDIAN);
            }
        }
        throw new IOException("Not a zip archive: " + url);
    }

    private interface Progress {
        void update(long done, long total);
    }

    private byte[] suffix(int bytes) throws IOException {
        return fetch("bytes=-" + bytes, bytes, null);
    }

    private byte[] range(long start, long endInclusive, Progress progress) throws IOException {
        return fetch("bytes=" + start + "-" + endInclusive, endInclusive - start + 1, progress);
    }

    private byte[] fetch(String range, long expected, Progress progress) throws IOException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", "stt-tts-studio/1.0")
                .header("Range", range)
                .GET()
                .build();
        HttpResponse<InputStream> response;
        try {
            response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while downloading " + url, e);
        }
        try (InputStream in = response.body()) {
            if (response.statusCode() != 206) {
                throw new IOException("Server does not support partial downloads (HTTP " + response.statusCode()
                        + "): " + url);
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream((int) Math.min(expected, Integer.MAX_VALUE - 8));
            byte[] buffer = new byte[1 << 16];
            long done = 0;
            long lastReport = 0;
            int read;
            while ((read = in.read(buffer)) >= 0) {
                out.write(buffer, 0, read);
                done += read;
                if (progress != null && done - lastReport > (1 << 20)) {
                    lastReport = done;
                    progress.update(done, expected);
                }
            }
            return out.toByteArray();
        }
    }
}
