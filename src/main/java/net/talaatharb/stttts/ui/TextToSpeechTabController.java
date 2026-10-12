package net.talaatharb.stttts.ui;

import java.util.List;

import com.google.inject.Inject;

import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.Slider;
import javafx.scene.control.TextArea;
import net.talaatharb.stttts.audio.AudioClip;
import net.talaatharb.stttts.audio.AudioPlayer;
import net.talaatharb.stttts.service.Language;
import net.talaatharb.stttts.service.SpeechRequest;
import net.talaatharb.stttts.service.TextToSpeechService;
import net.talaatharb.stttts.service.Voice;

/** Text to speech: pick an engine, language and voice, then synthesize and play or save the result. */
public class TextToSpeechTabController {
    private final List<TextToSpeechService> services;
    private final TaskRunner runner;
    private final AudioPlayer player;
    private AudioClip result;

    @FXML private ComboBox<TextToSpeechService> engineCombo;
    @FXML private Label engineDescription;
    @FXML private Label modelStatus;
    @FXML private ComboBox<Language> languageCombo;
    @FXML private ComboBox<Voice> voiceCombo;
    @FXML private Slider speedSlider;
    @FXML private Label speedLabel;
    @FXML private TextArea textInput;
    @FXML private Button speakButton;
    @FXML private Button playButton;
    @FXML private Button saveButton;
    @FXML private Label resultInfo;

    @Inject
    public TextToSpeechTabController(List<TextToSpeechService> services, TaskRunner runner, AudioPlayer player) {
        this.services = services;
        this.runner = runner;
        this.player = player;
    }

    @FXML
    private void initialize() {
        engineCombo.getItems().setAll(services);
        engineCombo.setCellFactory(list -> new ServiceCell());
        engineCombo.setButtonCell(new ServiceCell());
        engineCombo.valueProperty().addListener((obs, old, now) -> engineChanged(now));
        languageCombo.valueProperty().addListener((obs, old, now) -> filterVoices());
        speedLabel.textProperty().bind(speedSlider.valueProperty().asString("%.2fx"));
        speakButton.disableProperty().bind(runner.runningProperty());
        engineCombo.getSelectionModel().selectFirst();
        setResult(null);
    }

    private void engineChanged(TextToSpeechService service) {
        if (service == null) {
            return;
        }
        engineDescription.setText(service.description());
        UiSupport.updateStatus(service, modelStatus);
        languageCombo.getItems().setAll(service.languages());
        languageCombo.getSelectionModel().selectFirst();
        filterVoices();
    }

    private void filterVoices() {
        TextToSpeechService service = engineCombo.getValue();
        Language language = languageCombo.getValue();
        if (service == null) {
            return;
        }
        List<Voice> voices = service.voices().stream()
                .filter(v -> v.languageCode() == null || language == null || v.languageCode().equals(language.code()))
                .toList();
        voiceCombo.getItems().setAll(voices);
        voiceCombo.getSelectionModel().selectFirst();
    }

    private void setResult(AudioClip clip) {
        result = clip;
        playButton.setDisable(clip == null);
        saveButton.setDisable(clip == null);
        resultInfo.setText(clip == null ? "" : "Generated " + UiSupport.describe(clip));
    }

    @FXML
    private void onInstall() {
        UiSupport.install(runner, engineCombo.getValue(), modelStatus);
    }

    @FXML
    private void onSpeak() {
        TextToSpeechService service = engineCombo.getValue();
        String text = textInput.getText();
        if (service == null || text == null || text.isBlank()) {
            UiSupport.error("Nothing to say", "Type some text first.");
            return;
        }
        if (!UiSupport.confirmDownload(service)) {
            return;
        }
        Voice voice = voiceCombo.getValue();
        Language language = languageCombo.getValue();
        SpeechRequest request = new SpeechRequest(text, voice == null ? null : voice.id(),
                language == null ? null : language.code(), (float) speedSlider.getValue());
        player.stop();
        runner.submit("Synthesizing speech", progress -> service.synthesize(request, progress), clip -> {
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
        UiSupport.saveAudio(textInput.getScene().getWindow(), result, "speech.wav");
    }

    private static final class ServiceCell extends ListCell<TextToSpeechService> {
        @Override
        protected void updateItem(TextToSpeechService item, boolean empty) {
            super.updateItem(item, empty);
            setText(empty || item == null ? null : item.displayName());
        }
    }
}
