package net.talaatharb.stttts;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SingleInstanceTest {
    @TempDir Path directory;

    @Test
    void secondLaunchActivatesOwnerAndOwnershipCanBeReacquired() throws Exception {
        CountDownLatch activated = new CountDownLatch(1);
        try (SingleInstance owner = SingleInstance.acquire(directory, activated::countDown)) {
            assertNotNull(owner);
            assertNull(SingleInstance.acquire(directory, () -> {
                throw new AssertionError("Second launch cannot own the endpoint");
            }));
            assertTrue(activated.await(2, TimeUnit.SECONDS));
        }
        try (SingleInstance next = SingleInstance.acquire(directory, () -> {})) {
            assertNotNull(next);
        }
    }

    @Test
    void staleEndpointDoesNotPreventStartup() throws Exception {
        Files.writeString(directory.resolve("instance.endpoint"), "12345\nstale-token");
        try (SingleInstance owner = SingleInstance.acquire(directory, () -> {})) {
            assertNotNull(owner);
        }
    }

    @Test
    void separateProcessCannotAcquireOwnershipAndActivatesExistingInstance() throws Exception {
        CountDownLatch activated = new CountDownLatch(1);
        try (SingleInstance owner = SingleInstance.acquire(directory, activated::countDown)) {
            assertNotNull(owner);
            String executable = Path.of(System.getProperty("java.home"), "bin", "java").toString();
            Process child = new ProcessBuilder(executable, "-cp", System.getProperty("java.class.path"),
                    SingleInstanceTest.class.getName(), directory.toString()).redirectErrorStream(true).start();
            try {
                assertTrue(child.waitFor(10, TimeUnit.SECONDS), "Secondary process should exit promptly");
                org.junit.jupiter.api.Assertions.assertEquals(0, child.exitValue(),
                        new String(child.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
                assertTrue(activated.await(2, TimeUnit.SECONDS));
            } finally {
                if (child.isAlive()) {
                    child.destroyForcibly();
                    child.waitFor(5, TimeUnit.SECONDS);
                }
            }
        }
    }

    public static void main(String[] args) throws Exception {
        try (SingleInstance unexpected = SingleInstance.acquire(Path.of(args[0]), () -> {})) {
            if (unexpected != null) {
                throw new IllegalStateException("Secondary process acquired ownership");
            }
        }
    }
}
