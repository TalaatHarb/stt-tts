package net.talaatharb.stttts.ui;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;

import javafx.scene.control.Alert;
import javafx.scene.control.Alert.AlertType;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.stage.FileChooser;
import javafx.stage.Window;
import net.talaatharb.stttts.audio.AudioClip;
import net.talaatharb.stttts.audio.AudioPlayer;
import net.talaatharb.stttts.audio.WavIO;
import net.talaatharb.stttts.service.SpeechModelService;

/** Small helpers shared by the tab controllers. */
final class UiSupport {
    private static File lastDirectory;

    private UiSupport() {
    }

    static void error(String title, String message) {
        TaskRunner.runLater(() -> {
            Alert alert = new Alert(AlertType.ERROR, message);
            alert.setHeaderText(title);
            alert.showAndWait();
        });
    }

    static Optional<AudioClip> openAudio(Window owner) {
        FileChooser chooser = chooser("Open audio");
        chooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("Audio files", "*.wav", "*.aiff", "*.aif", "*.au"),
                new FileChooser.ExtensionFilter("All files", "*.*"));
        File file = chooser.showOpenDialog(owner);
        if (file == null) {
            return Optional.empty();
        }
        lastDirectory = file.getParentFile();
        try {
            return Optional.of(WavIO.read(file.toPath()));
        } catch (IOException | RuntimeException e) {
            error("Could not open " + file.getName(), e.getMessage());
            return Optional.empty();
        }
    }

    static void saveAudio(Window owner, AudioClip clip, String suggestedName) {
        if (clip == null) {
            return;
        }
        FileChooser chooser = chooser("Save audio");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("WAV audio", "*.wav"));
        chooser.setInitialFileName(suggestedName);
        File file = chooser.showSaveDialog(owner);
        if (file == null) {
            return;
        }
        lastDirectory = file.getParentFile();
        Path path = file.getName().toLowerCase().endsWith(".wav") ? file.toPath()
                : file.toPath().resolveSibling(file.getName() + ".wav");
        try {
            WavIO.write(clip, path);
        } catch (IOException e) {
            error("Could not save " + path.getFileName(), e.getMessage());
        }
    }

    private static FileChooser chooser(String title) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle(title);
        if (lastDirectory != null && lastDirectory.isDirectory()) {
            chooser.setInitialDirectory(lastDirectory);
        }
        return chooser;
    }

    static void play(AudioPlayer player, AudioClip clip, Runnable onFinished) {
        if (clip == null) {
            return;
        }
        try {
            player.play(clip, () -> TaskRunner.runLater(onFinished));
        } catch (Exception e) {
            error("Playback failed", e.getMessage());
        }
    }

    static String describe(AudioClip clip) {
        return clip == null ? "No audio" : String.format("%.1f s @ %d Hz", clip.durationSeconds(), clip.sampleRate());
    }

    /** Asks before downloading a model that is not cached yet; returns false when the user declines. */
    static boolean confirmDownload(SpeechModelService service) {
        if (service.isInstalled()) {
            return true;
        }
        Alert alert = new Alert(AlertType.CONFIRMATION, service.description()
                + "\n\nThe model files will be downloaded once and cached in ~/.stt-tts/models. Continue?",
                ButtonType.OK, ButtonType.CANCEL);
        alert.setHeaderText("Download " + service.displayName() + "?");
        return alert.showAndWait().filter(b -> b == ButtonType.OK).isPresent();
    }

    static void install(TaskRunner runner, SpeechModelService service, Label status) {
        if (!confirmDownload(service)) {
            return;
        }
        runner.submit("Installing " + service.displayName(), progress -> {
            service.install(progress);
            return null;
        }, ignored -> updateStatus(service, status), null);
    }

    static void updateStatus(SpeechModelService service, Label status) {
        if (status != null) {
            status.setText(service.isInstalled() ? "Installed" : "Not downloaded yet (downloaded on first use)");
        }
    }
}
