package net.talaatharb.stttts;

import java.awt.AWTException;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.Key;
import com.google.inject.TypeLiteral;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Alert.AlertType;
import javafx.stage.Stage;
import net.talaatharb.stttts.audio.AudioPlayer;
import net.talaatharb.stttts.config.AppModule;
import net.talaatharb.stttts.service.SpeechToTextService;
import net.talaatharb.stttts.service.TextToSpeechService;
import net.talaatharb.stttts.service.VoiceCloningService;
import net.talaatharb.stttts.ui.TaskRunner;

public class SttTtsApp extends Application {
    private Injector injector;
    private ApplicationTray tray;
    private SingleInstance instance;
    private Stage window;
    private boolean activationPending;

    @Override
    public void init() throws IOException {
        instance = SingleInstance.acquire(Path.of(System.getProperty("user.home"), ".stt-tts"),
                () -> Platform.runLater(this::activateWindow));
        if (instance == null) {
            return;
        }
        try {
            injector = Guice.createInjector(new AppModule());
        } catch (RuntimeException e) {
            instance.close();
            instance = null;
            throw e;
        }
    }

    @Override
    public void start(Stage stage) throws IOException {
        if (instance == null) {
            Platform.exit();
            return;
        }
        window = stage;
        FXMLLoader loader = new FXMLLoader(getClass().getResource("ui/main.fxml"));
        loader.setControllerFactory(injector::getInstance);
        Parent root = loader.load();
        stage.setTitle("STT-TTS Studio");
        Scene scene = new Scene(root, 1240, 760);
        scene.getStylesheets().add(getClass().getResource("ui/app.css").toExternalForm());
        stage.setScene(scene);
        stage.show();
        tray = new ApplicationTray(stage);
        if (activationPending) {
            activateWindow();
        }
        try {
            tray.install();
        } catch (AWTException e) {
            org.slf4j.LoggerFactory.getLogger(SttTtsApp.class).warn("System tray unavailable", e);
            Alert alert = new Alert(AlertType.WARNING,
                    "The system tray is unavailable. Closing the window will exit STT-TTS Studio.\n\n"
                            + e.getMessage());
            alert.initOwner(stage);
            alert.setHeaderText("Tray icon could not be installed");
            alert.showAndWait();
        }
    }

    private void activateWindow() {
        if (window == null || tray == null) {
            activationPending = true;
            return;
        }
        activationPending = false;
        tray.maximize();
    }

    @Override
    public void stop() throws IOException {
        try {
            if (tray != null) {
                tray.close();
            }
        } finally {
            try {
                if (injector != null) {
                    closeServices();
                }
            } finally {
                if (instance != null) {
                    instance.close();
                }
            }
        }
    }

    private void closeServices() {
        injector.getInstance(TaskRunner.class).shutdown();
        injector.getInstance(AudioPlayer.class).stop();
        injector.getInstance(SpeechToTextService.class).close();
        injector.getInstance(VoiceCloningService.class).close();
        injector.getInstance(Key.get(new TypeLiteral<List<TextToSpeechService>>() {
        })).forEach(TextToSpeechService::close);
    }

    public static void main(String[] args) {
        launch(args);
    }
}
