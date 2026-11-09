package net.talaatharb.stttts.ui;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import com.google.inject.Guice;
import com.google.inject.Injector;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.TabPane;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.Dialog;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import net.talaatharb.stttts.config.AppModule;
import net.talaatharb.stttts.config.AppSettings;
import net.talaatharb.stttts.mcp.LocalMcpServer;

@EnabledIfSystemProperty(named = "stttts.ui", matches = "true")
class GenerationUiTest {
    @TempDir Path temp;

    @Test
    void loadsAllTabsAndRequiresLicenseAcceptance() throws Exception {
        Platform.startup(() -> Platform.setImplicitExit(false));
        AtomicReference<TaskRunner> worker = new AtomicReference<>();
        AtomicReference<Parent> loadedRoot = new AtomicReference<>();
        Path settingsFile = temp.resolve("settings.properties");
        try (ServerSocket port = new ServerSocket(0)) {
            Files.writeString(settingsFile, "mcp.port=" + port.getLocalPort());
        }
        FutureTask<Void> load = new FutureTask<>(() -> {
            Injector injector = Guice.createInjector(new AppModule(temp.resolve("models"),
                    new AppSettings(settingsFile)));
            worker.set(injector.getInstance(TaskRunner.class));
            try {
                FXMLLoader loader = new FXMLLoader(getClass().getResource("/net/talaatharb/stttts/ui/main.fxml"));
                loader.setControllerFactory(injector::getInstance);
                Parent root = loader.load();
                loadedRoot.set(root);
                ToggleButton mcp = (ToggleButton) root.lookup("#mcpToggle");
                LocalMcpServer server = injector.getInstance(LocalMcpServer.class);
                MainController controller = loader.getController();
                assertFalse(mcp.isSelected());
                assertFalse(server.isRunning());
                assertFalse(controller.mcpEnabledProperty().get());
                Button settingsButton = (Button) root.lookup("#settingsButton");
                assertNotNull(settingsButton.getOnAction());
                verifySettingsDialog(injector, settingsFile);
                mcp.fire();
                assertTrue(mcp.isSelected());
                assertTrue(server.isRunning());
                assertTrue(controller.mcpEnabledProperty().get());
                Dialog<Void> readOnly = injector.getInstance(SettingsDialog.class).create();
                readOnly.show();
                assertTrue(readOnly.getDialogPane().lookup("#saveSettings").isDisabled());
                assertFalse(((TextField) readOnly.getDialogPane().lookup("#mcpPort")).isEditable());
                assertFalse(((PasswordField) readOnly.getDialogPane().lookup("#mcpToken")).isEditable());
                assertTrue(readOnly.getDialogPane().lookup("#generateMcpToken").isDisabled());
                assertFalse(readOnly.getDialogPane().lookup("#copyMcpToken").isDisabled());
                ((Button) readOnly.getDialogPane().lookupButton(ButtonType.CANCEL)).fire();
                controller.toggleMcp();
                assertFalse(mcp.isSelected());
                assertFalse(server.isRunning());
                assertFalse(controller.mcpEnabledProperty().get());
                controller.toggleMcp();
                assertTrue(mcp.isSelected());
                assertTrue(server.isRunning());
                mcp.fire();
                assertFalse(controller.mcpEnabledProperty().get());
                TabPane tabs = (TabPane) root.lookupAll(".tab-pane").stream().findFirst().orElseThrow();
                assertEquals(4, tabs.getTabs().size());
                Parent generation = (Parent) tabs.getTabs().get(3).getContent();
                ComboBox<?> engines = (ComboBox<?>) generation.lookup("#engineCombo");
                CheckBox accepted = (CheckBox) generation.lookup("#licenseAccepted");
                Button generate = (Button) generation.lookup("#generateButton");
                Button install = (Button) generation.lookup("#installButton");
                assertEquals(3, engines.getItems().size());
                assertTrue(generate.isDisabled());
                assertTrue(install.isDisabled());
                accepted.setSelected(true);
                assertFalse(generate.isDisabled());
                assertFalse(install.isDisabled());
                engines.getSelectionModel().select(1);
                assertFalse(accepted.isSelected());
                assertTrue(generate.isDisabled());
            } finally {
                injector.getInstance(LocalMcpServer.class).close();
            }
            return null;
        });
        try {
            Platform.runLater(load);
            load.get(30, TimeUnit.SECONDS);
            CountDownLatch started = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            CompletableFuture<Void> job = worker.get().submitRemote("Test native inference", progress -> {
                started.countDown();
                boolean waiting = true;
                while (waiting) {
                    try {
                        release.await();
                        waiting = false;
                    } catch (InterruptedException ignored) {
                        // Simulate a native inference call that cannot stop until it returns.
                    }

                }
                return null;
            });
            try {
                assertTrue(started.await(10, TimeUnit.SECONDS));
                FutureTask<Void> busy = new FutureTask<>(() -> {
                    assertTrue(worker.get().isRunning());
                    assertTrue(loadedRoot.get().lookup("#gpuCheck").isDisabled());
                    worker.get().cancel();
                    return null;
                });
                Platform.runLater(busy);
                busy.get(10, TimeUnit.SECONDS);
                // Flush the cancellation event: the app must stay busy until the simulated native call returns.
                FutureTask<Void> stillBusy = new FutureTask<>(() -> {
                    assertTrue(worker.get().isRunning());
                    return null;
                });
                Platform.runLater(stillBusy);
                stillBusy.get(10, TimeUnit.SECONDS);
                CompletableFuture<Void> competing = worker.get().submitRemote("Competing job", progress -> null);
                ExecutionException error = assertThrows(ExecutionException.class,
                        () -> competing.get(10, TimeUnit.SECONDS));
                assertTrue(error.getCause().getMessage().contains("Busy"));
            } finally {
                release.countDown();
            }
            assertThrows(java.util.concurrent.CancellationException.class, () -> job.get(10, TimeUnit.SECONDS));
            FutureTask<Void> idle = new FutureTask<>(() -> {
                assertFalse(worker.get().isRunning());
                return null;
            });
            Platform.runLater(idle);
            idle.get(10, TimeUnit.SECONDS);
            assertEquals(42, worker.get().submitRemote("Resumed job", progress -> 42).get(10, TimeUnit.SECONDS));
        } finally {
            if (worker.get() != null) {
                worker.get().shutdown();
            }
            Platform.exit();
        }
    }

    private static void verifySettingsDialog(Injector injector, Path file) throws Exception {
        AppSettings settings = injector.getInstance(AppSettings.class);
        SettingsDialog factory = injector.getInstance(SettingsDialog.class);
        String original = Files.readString(file);
        Dialog<Void> cancelled = factory.create();
        cancelled.show();
        ((Button) cancelled.getDialogPane().lookup("#generateMcpToken")).fire();
        assertFalse(((PasswordField) cancelled.getDialogPane().lookup("#mcpToken")).getText().isEmpty());
        ((Button) cancelled.getDialogPane().lookupButton(ButtonType.CANCEL)).fire();
        assertEquals(original, Files.readString(file), "Cancel must discard staged settings and token");
        assertEquals("", settings.configuredMcpToken(), "Opening the dialog must not generate a stored token");

        Dialog<Void> dialog = factory.create();
        dialog.show();
        TextField port = (TextField) dialog.getDialogPane().lookup("#mcpPort");
        Button save = (Button) dialog.getDialogPane().lookup("#saveSettings");
        Label error = (Label) dialog.getDialogPane().lookup("#settingsError");
        PasswordField token = (PasswordField) dialog.getDialogPane().lookup("#mcpToken");
        String originalPort = port.getText();
        port.setText("not-a-port");
        save.fire();
        assertTrue(dialog.isShowing());
        assertTrue(error.getText().contains("whole number"));
        assertEquals(original, Files.readString(file));
        port.setText("65536");
        save.fire();
        assertTrue(dialog.isShowing());
        assertTrue(error.getText().contains("65535"));
        port.setText(originalPort);
        token.setText("weak");
        save.fire();
        assertTrue(dialog.isShowing());
        assertTrue(error.getText().contains("Token"));
        ((Button) dialog.getDialogPane().lookup("#generateMcpToken")).fire();
        String newToken = token.getText();
        assertTrue(newToken.length() >= 32);
        ComboBox<?> tts = (ComboBox<?>) dialog.getDialogPane().lookup("#mcpttsModel");
        tts.getSelectionModel().select(3);
        assertEquals(4, tts.getItems().size());
        assertEquals(2, ((ComboBox<?>) dialog.getDialogPane().lookup("#mcpmusicModel")).getItems().size());
        assertEquals(1, ((ComboBox<?>) dialog.getDialogPane().lookup("#mcpsttModel")).getItems().size());
        assertEquals(1, ((ComboBox<?>) dialog.getDialogPane().lookup("#mcpsfxModel")).getItems().size());
        assertEquals("http://127.0.0.1:" + originalPort + "/mcp",
                ((TextField) dialog.getDialogPane().lookup("#mcpEndpoint")).getText());
        save.fire();
        assertFalse(dialog.isShowing());
        assertEquals("piper", settings.mcpModel("tts"));
        AppSettings reloaded = new AppSettings(file);
        assertEquals("piper", reloaded.mcpModel("tts"));
        assertEquals(newToken, reloaded.configuredMcpToken());
        assertFalse(injector.getInstance(LocalMcpServer.class).isRunning(), "Save must not start MCP");
    }
}
