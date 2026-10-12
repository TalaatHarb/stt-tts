package net.talaatharb.stttts.ui;

import com.google.inject.Inject;

import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.TextArea;
import net.talaatharb.stttts.audio.AudioClip;
import net.talaatharb.stttts.audio.AudioPlayer;
import net.talaatharb.stttts.audio.AudioRecorder;
import net.talaatharb.stttts.service.Language;
import net.talaatharb.stttts.service.SpeechToTextService;
import net.talaatharb.stttts.service.Transcription;
import net.talaatharb.stttts.service.TranscriptionRequest;

/** Speech to text: record or open audio and transcribe it locally. */
public class SpeechToTextTabController {
    private final SpeechToTextService service;
    private final TaskRunner runner;
    private final AudioPlayer player;
    private final AudioRecorder recorder;
    private AudioClip audio;

    @FXML private Label engineLabel;
    @FXML private Label modelStatus;
    @FXML private Button recordButton;
    @FXML private ProgressBar levelMeter;
    @FXML private Label audioInfo;
    @FXML private Button playButton;
    @FXML private ComboBox<Language> languageCombo;
    @FXML private CheckBox wordTimestamps;
    @FXML private Button transcribeButton;
    @FXML private TextArea transcript;
    @FXML private TextArea wordTimings;
    @FXML private Label resultInfo;

    @Inject
    public SpeechToTextTabController(SpeechToTextService service, TaskRunner runner, AudioPlayer player,
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
        languageCombo.getItems().setAll(service.languages());
        languageCombo.getSelectionModel().selectFirst();
        new RecordingControl(recorder, recordButton, levelMeter, this::setAudio);
        transcribeButton.disableProperty().bind(runner.runningProperty());
        setAudio(null);
    }

    private void setAudio(AudioClip clip) {
        audio = clip;
        audioInfo.setText(UiSupport.describe(clip));
        playButton.setDisable(clip == null);
    }

    @FXML
    private void onOpen() {
        UiSupport.openAudio(transcript.getScene().getWindow()).ifPresent(this::setAudio);
    }

    @FXML
    private void onPlay() {
        if (player.isPlaying()) {
            player.stop();
            return;
        }
        playButton.setText("Stop");
        UiSupport.play(player, audio, () -> playButton.setText("Play"));
    }

    @FXML
    private void onInstall() {
        UiSupport.install(runner, service, modelStatus);
    }

    @FXML
    private void onTranscribe() {
        if (audio == null) {
            UiSupport.error("No audio", "Record something or open an audio file first.");
            return;
        }
        if (!UiSupport.confirmDownload(service)) {
            return;
        }
        Language language = languageCombo.getValue();
        TranscriptionRequest request = new TranscriptionRequest(
                language == null || Language.AUTO.equals(language) ? null : language.code(),
                wordTimestamps.isSelected());
        AudioClip clip = audio;
        runner.submit("Transcribing", progress -> service.transcribe(clip, request, progress), this::show, null);
    }

    private void show(Transcription result) {
        UiSupport.updateStatus(service, modelStatus);
        transcript.setText(result.text());
        StringBuilder words = new StringBuilder();
        for (Transcription.WordTiming word : result.words()) {
            words.append(String.format("%7.2f - %7.2f  %s%n", word.start(), word.end(), word.word()));
        }
        wordTimings.setText(words.toString());
        resultInfo.setText(String.format("Language: %s, processed in %.2f s",
                result.language() == null ? "?" : result.language(), result.elapsed().toMillis() / 1000.0));
    }

    @FXML
    private void onCopy() {
        javafx.scene.input.ClipboardContent content = new javafx.scene.input.ClipboardContent();
        content.putString(transcript.getText());
        javafx.scene.input.Clipboard.getSystemClipboard().setContent(content);
    }
}
