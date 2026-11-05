package net.talaatharb.stttts.ui;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import com.google.inject.Inject;

import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.Slider;
import javafx.scene.control.TextArea;
import javafx.scene.layout.HBox;
import net.talaatharb.stttts.audio.AudioClip;
import net.talaatharb.stttts.audio.AudioPlayer;
import net.talaatharb.stttts.audio.AudioRecorder;
import net.talaatharb.stttts.audio.WavIO;
import net.talaatharb.stttts.cloning.chatterbox.ChatterboxVoiceCloningService;
import net.talaatharb.stttts.service.Language;
import net.talaatharb.stttts.service.VoiceCloneRequest;
import net.talaatharb.stttts.service.VoiceCloningService;

/** Voice cloning: capture a short reference recording and speak new text in that voice. */
public class VoiceCloningTabController {
    private final List<VoiceCloningService> services;
    private final ChatterboxVoiceCloningService chatterbox;
    private final TaskRunner runner;
    private final AudioPlayer player;
    private final AudioRecorder recorder;
    private AudioClip reference;
    private AudioClip result;

    @FXML private ComboBox<VoiceCloningService> engineCombo;
    @FXML private Label engineDescription;
    @FXML private Label modelStatus;
    @FXML private ComboBox<Language> languageCombo;
    @FXML private Label referenceHint;
    @FXML private Button recordButton;
    @FXML private ProgressBar levelMeter;
    @FXML private Label referenceInfo;
    @FXML private Button playReferenceButton;
    @FXML private HBox exaggerationRow;
    @FXML private Slider exaggerationSlider;
    @FXML private Label exaggerationLabel;
    @FXML private TextArea textInput;
    @FXML private Button cloneButton;
    @FXML private Button playButton;
    @FXML private Button saveButton;
    @FXML private Label resultInfo;

    @Inject
    public VoiceCloningTabController(List<VoiceCloningService> services, ChatterboxVoiceCloningService chatterbox,
            TaskRunner runner, AudioPlayer player, AudioRecorder recorder) {
        this.services = services;
        this.chatterbox = chatterbox;
        this.runner = runner;
        this.player = player;
        this.recorder = recorder;
    }

    @FXML
    private void initialize() {
        engineCombo.getItems().setAll(services);
        engineCombo.setCellFactory(list -> new ServiceCell());
        engineCombo.setButtonCell(new ServiceCell());
        engineCombo.valueProperty().addListener((obs, old, now) -> engineChanged(now));
        new RecordingControl(recorder, recordButton, levelMeter, this::setReference);
        exaggerationLabel.textProperty().bind(exaggerationSlider.valueProperty().asString("%.2f"));
        exaggerationRow.managedProperty().bind(exaggerationRow.visibleProperty());
        cloneButton.disableProperty().bind(runner.runningProperty());
        engineCombo.getSelectionModel().selectFirst();
        setReference(null);
        setResult(null);
    }

    private void engineChanged(VoiceCloningService service) {
        if (service == null) {
            return;
        }
        engineDescription.setText(service.description());
        UiSupport.updateStatus(service, modelStatus);
        languageCombo.getItems().setAll(service.languages());
        languageCombo.getSelectionModel().selectFirst();
        languageCombo.setDisable(service.languages().size() < 2);
        exaggerationRow.setVisible(service.supportsExaggeration());
        referenceHint.setText(String.format(
                "Record or open about %.0f seconds of clean speech from the voice you want to clone.",
                service.recommendedReferenceSeconds()));
    }

    private void setReference(AudioClip clip) {
        reference = clip;
        referenceInfo.setText(UiSupport.describe(clip));
        playReferenceButton.setDisable(clip == null);
    }

    private void setResult(AudioClip clip) {
        result = clip;
        playButton.setDisable(clip == null);
        saveButton.setDisable(clip == null);
        resultInfo.setText(clip == null ? "" : "Generated " + UiSupport.describe(clip));
    }

    @FXML
    private void onOpenReference() {
        UiSupport.openAudio(textInput.getScene().getWindow()).ifPresent(this::setReference);
    }

    @FXML
    private void onSampleVoice() {
        runner.submit("Downloading the sample voice", chatterbox::ensureDefaultVoice, this::loadSample, null);
    }

    private void loadSample(Path file) {
        try {
            setReference(WavIO.read(file));
        } catch (IOException e) {
            UiSupport.error("Could not load the sample voice", e.getMessage());
        }
    }

    @FXML
    private void onPlayReference() {
        if (player.isPlaying()) {
            player.stop();
            return;
        }
        playReferenceButton.setText("Stop");
        UiSupport.play(player, reference, () -> playReferenceButton.setText("Play reference"));
    }

    @FXML
    private void onInstall() {
        UiSupport.install(runner, engineCombo.getValue(), modelStatus);
    }

    @FXML
    private void onClone() {
        VoiceCloningService service = engineCombo.getValue();
        if (service == null) {
            return;
        }
        if (reference == null) {
            UiSupport.error("No reference voice", "Record or open a reference recording first.");
            return;
        }
        String text = textInput.getText();
        if (text == null || text.isBlank()) {
            UiSupport.error("Nothing to say", "Type some text first.");
            return;
        }
        Language language = languageCombo.getValue();
        VoiceCloneRequest request;
        try {
            request = new VoiceCloneRequest(text, reference, (float) exaggerationSlider.getValue(),
                    language == null ? null : language.code());
        } catch (IllegalArgumentException e) {
            UiSupport.error("Invalid input", e.getMessage());
            return;
        }
        if (!UiSupport.confirmDownload(service)) {
            return;
        }
        player.stop();
        runner.submit("Cloning voice", progress -> service.cloneVoice(request, progress), clip -> {
            UiSupport.updateStatus(service, modelStatus);
            setResult(clip);
            onPlay();
        }, null);
    }

    @FXML
    private void onPlay() {
        if (player.isPlaying()) {
            player.stop();
            return;
        }
        playButton.setText("Stop");
        UiSupport.play(player, result, () -> playButton.setText("Play"));
    }

    @FXML
    private void onSave() {
        UiSupport.saveAudio(textInput.getScene().getWindow(), result, "cloned-voice.wav");
    }

    private static final class ServiceCell extends ListCell<VoiceCloningService> {
        @Override
        protected void updateItem(VoiceCloningService item, boolean empty) {
            super.updateItem(item, empty);
            setText(empty || item == null ? null : item.displayName());
        }
    }
}
