package net.talaatharb.stttts.ui;

import java.awt.Desktop;
import java.io.IOException;
import java.net.URI;
import java.util.List;

import com.google.inject.Inject;

import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.Slider;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import net.talaatharb.stttts.audio.AudioClip;
import net.talaatharb.stttts.audio.AudioPlayer;
import net.talaatharb.stttts.service.AudioGenerationRequest;
import net.talaatharb.stttts.service.AudioGenerationService;

/** Prompt-to-audio generation with explicit model-license consent. */
public class AudioGenerationTabController {
    private final List<AudioGenerationService> services;
    private final TaskRunner runner;
    private final AudioPlayer player;
    private AudioClip result;

    @FXML private ComboBox<AudioGenerationService> engineCombo;
    @FXML private Label engineDescription;
    @FXML private Label modelStatus;
    @FXML private Hyperlink licenseLink;
    @FXML private CheckBox licenseAccepted;
    @FXML private Slider durationSlider;
    @FXML private Label durationLabel;
    @FXML private TextField seedInput;
    @FXML private TextArea promptInput;
    @FXML private Button installButton;
    @FXML private Button generateButton;
    @FXML private Button playButton;
    @FXML private Button saveButton;
    @FXML private Label resultInfo;

    @Inject
    public AudioGenerationTabController(List<AudioGenerationService> services, TaskRunner runner, AudioPlayer player) {
        this.services = services;
        this.runner = runner;
        this.player = player;
    }

    @FXML
    private void initialize() {
        engineCombo.getItems().setAll(services);
        engineCombo.setCellFactory(list -> new ServiceCell());
        engineCombo.setButtonCell(new ServiceCell());
        engineCombo.valueProperty().addListener((obs, old, now) -> {
            if (old != null) {
                old.close();
            }
            engineChanged(now);
        });
        engineCombo.disableProperty().bind(runner.runningProperty());
        installButton.disableProperty().bind(runner.runningProperty().or(licenseAccepted.selectedProperty().not()));
        generateButton.disableProperty().bind(runner.runningProperty().or(licenseAccepted.selectedProperty().not()));
        durationLabel.textProperty().bind(durationSlider.valueProperty().asString("%.1f s"));
        engineCombo.getSelectionModel().selectFirst();
        setResult(null);
    }

    private void engineChanged(AudioGenerationService service) {
        if (service != null) {
            engineDescription.setText(service.description());
            licenseLink.setText(service.licenseName() + " - read terms");
            licenseAccepted.setSelected(false);
            durationSlider.setMax(service.maxDurationSeconds());
            durationSlider.setValue(Math.min(durationSlider.getValue(), service.maxDurationSeconds()));
            UiSupport.updateStatus(service, modelStatus);
        }
    }

    @FXML
    private void onLicense() {
        try {
            if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                UiSupport.error("Open model license", engineCombo.getValue().licenseUrl());
                return;
            }
            Desktop.getDesktop().browse(URI.create(engineCombo.getValue().licenseUrl()));
        } catch (IOException | RuntimeException e) {
            UiSupport.error("Could not open model license", e.getMessage());
        }
    }

    @FXML
    private void onInstall() {
        if (licenseAccepted.isSelected()) {
            UiSupport.install(runner, engineCombo.getValue(), modelStatus);
        }
    }

    @FXML
    private void onGenerate() {
        AudioGenerationService service = engineCombo.getValue();
        if (service == null || !licenseAccepted.isSelected()) {
            UiSupport.error("License acceptance required", "Read and accept the selected model's license first.");
            return;
        }
        AudioGenerationRequest request;
        try {
            request = new AudioGenerationRequest(promptInput.getText(), durationSlider.getValue(),
                    Long.parseLong(seedInput.getText().strip()));
        } catch (IllegalArgumentException e) {
            UiSupport.error("Invalid generation request", "Enter a prompt and a whole-number seed.\n" + e.getMessage());
            return;
        }
        if (!UiSupport.confirmDownload(service)) {
            return;
        }
        player.stop();
        playButton.setText("Play");
        runner.submit("Generating audio", progress -> service.generate(request, progress), clip -> {
            UiSupport.updateStatus(service, modelStatus);
            setResult(clip);
        }, null);
    }

    private void setResult(AudioClip clip) {
        result = clip;
        playButton.setDisable(clip == null);
        saveButton.setDisable(clip == null);
        resultInfo.setText(clip == null ? "" : "Generated " + UiSupport.describe(clip) + " (mono)");
    }

    @FXML
    private void onPlay() {
        if (player.isPlaying()) {
            player.stop();
            playButton.setText("Play");
        } else {
            playButton.setText("Stop");
            UiSupport.play(player, result, () -> playButton.setText("Play"));
        }
    }

    @FXML
    private void onSave() {
        UiSupport.saveAudio(promptInput.getScene().getWindow(), result, "generated-audio.wav");
    }

    private static final class ServiceCell extends ListCell<AudioGenerationService> {
        @Override
        protected void updateItem(AudioGenerationService item, boolean empty) {
            super.updateItem(item, empty);
            setText(empty || item == null ? null : item.displayName());
        }
    }
}
