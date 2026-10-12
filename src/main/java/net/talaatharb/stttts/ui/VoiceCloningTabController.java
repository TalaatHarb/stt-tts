package net.talaatharb.stttts.ui;

import java.io.IOException;
import java.nio.file.Files;

import com.google.inject.Inject;

import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.Slider;
import javafx.scene.control.TextArea;
import net.talaatharb.stttts.audio.AudioClip;
import net.talaatharb.stttts.audio.AudioPlayer;
import net.talaatharb.stttts.audio.AudioRecorder;
import net.talaatharb.stttts.audio.WavIO;
import net.talaatharb.stttts.cloning.chatterbox.ChatterboxVoiceCloningService;
import net.talaatharb.stttts.service.VoiceCloneRequest;
import net.talaatharb.stttts.service.VoiceCloningService;

/** Voice cloning: capture a short reference recording and speak new text in that voice. */
public class VoiceCloningTabController {
    private final VoiceCloningService service;
    private final TaskRunner runner;
    private final AudioPlayer player;
    private final AudioRecorder recorder;
    private AudioClip reference;
    private AudioClip result;

    @FXML private Label engineLabel;
    @FXML private Label modelStatus;
    @FXML private Label referenceHint;
    @FXML private Button recordButton;
    @FXML private ProgressBar levelMeter;
    @FXML private Label referenceInfo;
    @FXML private Button playReferenceButton;
    @FXML private Button sampleVoiceButton;
    @FXML private Slider exaggerationSlider;
    @FXML private Label exaggerationLabel;
    @FXML private TextArea textInput;
    @FXML private Button cloneButton;
    @FXML private Button playButton;
    @FXML private Button saveButton;
    @FXML private Label resultInfo;

    @Inject
    public VoiceCloningTabController(VoiceCloningService service, TaskRunner runner, AudioPlayer player,
            AudioRecorder recorder) {
        this.service = service;
        this.runner = runner;
        this.player = player;
        this.recorder = recorder;
    }

    @FXML
    private void initialize() {
        engineLabel.setText(service.displayName());
        UiSupport.updateStatus(service, modelStatus);
        referenceHint.setText(String.format(
                "Record or open %.0f seconds of clean speech from the voice you want to clone.",
                service.recommendedReferenceSeconds()));
        new RecordingControl(recorder, recordButton, levelMeter, this::setReference);
        exaggerationLabel.textProperty().bind(exaggerationSlider.valueProperty().asString("%.2f"));
        cloneButton.disableProperty().bind(runner.runningProperty());
        sampleVoiceButton.setVisible(service instanceof ChatterboxVoiceCloningService);
        sampleVoiceButton.setManaged(sampleVoiceButton.isVisible());
        setReference(null);
        setResult(null);
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
        if (!(service instanceof ChatterboxVoiceCloningService chatterbox)) {
            return;
        }
        if (Files.isRegularFile(chatterbox.defaultVoice())) {
            loadSample(chatterbox);
            return;
        }
        if (UiSupport.confirmDownload(service)) {
            runner.submit("Installing " + service.displayName(), progress -> {
                service.install(progress);
                return null;
            }, ignored -> {
                UiSupport.updateStatus(service, modelStatus);
                loadSample(chatterbox);
            }, null);
        }
    }

    private void loadSample(ChatterboxVoiceCloningService chatterbox) {
        try {
            setReference(WavIO.read(chatterbox.defaultVoice()));
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
        UiSupport.install(runner, service, modelStatus);
    }

    @FXML
    private void onClone() {
        if (reference == null) {
            UiSupport.error("No reference voice", "Record or open a reference recording first.");
            return;
        }
        String text = textInput.getText();
        if (text == null || text.isBlank()) {
            UiSupport.error("Nothing to say", "Type some text first.");
            return;
        }
        VoiceCloneRequest request;
        try {
            request = new VoiceCloneRequest(text, reference, (float) exaggerationSlider.getValue());
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
}
