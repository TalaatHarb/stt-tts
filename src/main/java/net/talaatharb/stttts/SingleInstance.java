package net.talaatharb.stttts;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.UUID;

/** OS-released ownership lock and authenticated loopback channel for window activation. */
final class SingleInstance implements AutoCloseable {
    private final FileChannel channel;
    private final FileLock lock;
    private final ServerSocket server;
    private final Path endpoint;
    private volatile boolean closed;

    private SingleInstance(FileChannel channel, FileLock lock, ServerSocket server, Path endpoint) {
        this.channel = channel;
        this.lock = lock;
        this.server = server;
        this.endpoint = endpoint;
    }

    /** Returns null after the already-running instance acknowledges activation. */
    static SingleInstance acquire(Path directory, Runnable activate) throws IOException {
        Files.createDirectories(directory);
        Path endpoint = directory.resolve("instance.endpoint");
        FileChannel channel = FileChannel.open(directory.resolve("instance.lock"),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        FileLock lock;
        try {
            lock = channel.tryLock();
        } catch (OverlappingFileLockException e) {
            lock = null;
        } catch (IOException e) {
            channel.close();
            throw e;
        }
        if (lock == null) {
            channel.close();
            signalExisting(endpoint);
            return null;
        }
        ServerSocket server = new ServerSocket();
        try {
            Files.deleteIfExists(endpoint);
            server.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
            String token = UUID.randomUUID().toString();
            Files.writeString(endpoint, server.getLocalPort() + "\n" + token);
            SingleInstance instance = new SingleInstance(channel, lock, server, endpoint);
            Thread listener = new Thread(() -> instance.listen(token, activate), "stt-tts-activation");
            listener.setDaemon(true);
            listener.start();
            return instance;
        } catch (IOException | RuntimeException e) {
            server.close();
            lock.release();
            channel.close();
            throw e;
        }
    }

    private static void signalExisting(Path endpoint) throws IOException {
        IOException lastFailure = null;
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        do {
            try {
                String[] details = Files.readString(endpoint).split("\n");
                if (details.length != 2) {
                    throw new IOException("Activation endpoint is not ready");
                }
                int port;
                try {
                    port = Integer.parseInt(details[0]);
                } catch (NumberFormatException e) {
                    throw new IOException("Invalid activation endpoint", e);
                }
                if (port < 1 || port > 65535) {
                    throw new IOException("Invalid activation port");
                }
                try (Socket socket = new Socket()) {
                    socket.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 500);
                    socket.setSoTimeout(1000);
                    new DataOutputStream(socket.getOutputStream()).writeUTF(details[1]);
                    if (!new DataInputStream(socket.getInputStream()).readBoolean()) {
                        throw new IOException("Existing instance rejected activation");
                    }
                    return;
                }
            } catch (IOException e) {
                lastFailure = e;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while activating the existing instance", e);
            }
        } while (System.nanoTime() < deadline);
        throw new IOException("Another STT-TTS Studio instance is running but could not be activated.", lastFailure);
    }

    private void listen(String token, Runnable activate) {
        while (!closed) {
            try (Socket socket = server.accept()) {
                socket.setSoTimeout(1000);
                boolean accepted = token.equals(new DataInputStream(socket.getInputStream()).readUTF());
                if (accepted) {
                    activate.run();
                }
                new DataOutputStream(socket.getOutputStream()).writeBoolean(accepted);
            } catch (IOException | RuntimeException e) {
                if (!closed) {
                    org.slf4j.LoggerFactory.getLogger(SingleInstance.class).warn("Activation request failed", e);
                }
            }
        }
    }

    @Override
    public void close() throws IOException {
        closed = true;
        try {
            server.close();
            Files.deleteIfExists(endpoint);
        } finally {
            try {
                lock.release();
            } finally {
                channel.close();
            }
        }
    }
}
