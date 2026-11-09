package net.talaatharb.stttts.ui;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

import com.google.inject.Inject;
import com.google.inject.Singleton;

import javafx.event.ActionEvent;
import javafx.beans.binding.Bindings;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar.ButtonData;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.stage.Window;
import net.talaatharb.stttts.config.AppSettings;
import net.talaatharb.stttts.mcp.LocalMcpServer;
import net.talaatharb.stttts.service.AudioGenerationService;
import net.talaatharb.stttts.service.SpeechModelService;
import net.talaatharb.stttts.service.SpeechToTextService;
import net.talaatharb.stttts.service.TextToSpeechService;

/** Stages MCP settings until Save; editing a live server's configuration is deliberately disallowed. */
@Singleton
public class SettingsDialog {
    private final AppSettings settings;
    private final LocalMcpServer server;
    private final SpeechToTextService stt;
    private final List<TextToSpeechService> tts;
    private final List<AudioGenerationService> generation;

    @Inject
    public SettingsDialog(AppSettings settings, LocalMcpServer server, SpeechToTextService stt,
            List<TextToSpeechService> tts, List<AudioGenerationService> generation) {
        this.settings = settings;
        this.server = server;
        this.stt = stt;
        this.tts = tts;
        this.generation = generation;
    }

    public void show(Window owner) {
        Dialog<Void> dialog = create();
        if (owner != null) {
            dialog.initOwner(owner);
        }
        dialog.showAndWait();
    }

    Dialog<Void> create() {
        boolean running = server.isRunning();
        Dialog<Void> dialog = new Dialog<>();
        dialog.setTitle("Settings");
        dialog.setHeaderText("Local MCP server");
        dialog.setResizable(true);
        ButtonType saveType = new ButtonType("Save", ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(saveType, ButtonType.CANCEL);

        GridPane grid = new GridPane();
        grid.setPadding(new Insets(12));
        grid.setHgap(12);
        grid.setVgap(10);
        grid.setPrefWidth(660);
        Label notice = new Label(running
                ? "MCP is running. Stop it using the MCP toggle before editing settings."
                : "Saved changes apply the next time you enable MCP. No application restart is needed.");
        notice.setWrapText(true);
        grid.add(notice, 0, 0, 2, 1);
        TextField port = new TextField(settings.mcpPortText());
        port.setId("mcpPort");
        port.setEditable(!running);
        grid.addRow(1, new Label("Localhost port"), port);
        TextField endpoint = new TextField();
        endpoint.setId("mcpEndpoint");
        endpoint.setEditable(false);
        endpoint.textProperty().bind(Bindings.concat("http://127.0.0.1:", port.textProperty(), "/mcp"));
        Button copyUrl = new Button("Copy URL");
        copyUrl.setOnAction(event -> copy(endpoint.getText()));
        grid.addRow(2, new Label("Endpoint (localhost only)"), new HBox(8, endpoint, copyUrl));
        ComboBox<SpeechToTextService> sttModel = models("stt", List.of(stt), running);
        ComboBox<TextToSpeechService> ttsModel = models("tts", tts, running);
        ComboBox<AudioGenerationService> musicModel = models("music",
                generation.stream().filter(model -> !model.id().endsWith("-sfx")).toList(), running);
        ComboBox<AudioGenerationService> sfxModel = models("sfx",
                generation.stream().filter(model -> model.id().endsWith("-sfx")).toList(), running);
        grid.addRow(3, new Label("Default STT model"), sttModel);
        grid.addRow(4, new Label("Default TTS model"), ttsModel);
        grid.addRow(5, new Label("Default music model"), musicModel);
        grid.addRow(6, new Label("Default sound-effects model"), sfxModel);

        PasswordField token = new PasswordField();
        token.setId("mcpToken");
        token.setText(settings.configuredMcpToken());
        token.setPromptText("Generated automatically on first MCP activation");
        token.setEditable(!running);
        Button copyToken = new Button("Copy token");
        copyToken.setId("copyMcpToken");
        copyToken.disableProperty().bind(token.textProperty().isEmpty());
        copyToken.setOnAction(event -> copy(token.getText()));
        Button regenerate = new Button("Generate token");
        regenerate.setId("generateMcpToken");
        regenerate.setDisable(running);
        regenerate.setOnAction(event -> token.setText(UUID.randomUUID().toString() + UUID.randomUUID()));
        grid.addRow(7, new Label("Bearer token"), token);
        grid.add(new HBox(8, copyToken, regenerate), 1, 8);
        Label tokenNote = new Label("Keep the token private. Copy uses the system clipboard; clear it after use.\n"
                + "Generating or clearing a token only takes effect after Save. Update your MCP client if it changes.\n"
                + "MCP is always off at startup. GPU options remain in the GPU checkbox.");
        tokenNote.setWrapText(true);
        grid.add(tokenNote, 0, 9, 2, 1);
        Label error = new Label();
        error.setId("settingsError");
        error.setWrapText(true);
        grid.add(error, 0, 10, 2, 1);
        dialog.getDialogPane().setContent(grid);
        Button save = (Button) dialog.getDialogPane().lookupButton(saveType);
        save.setId("saveSettings");
        save.setDisable(running);
        save.addEventFilter(ActionEvent.ACTION, event -> {
            try {
                if (server.isRunning()) {
                    throw new IllegalArgumentException("Stop MCP before saving settings");
                }
                int number;
                try {
                    number = Integer.parseInt(port.getText().strip());
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("Port must be a whole number between 1 and 65535", e);
                }
                settings.setMcpConfiguration(number, selected(sttModel), selected(ttsModel),
                        selected(musicModel), selected(sfxModel), token.getText().strip());
            } catch (IllegalArgumentException | IOException e) {
                error.setText("Could not save settings: " + e.getMessage());
                event.consume();
            }
        });
        return dialog;
    }

    private <T extends SpeechModelService> ComboBox<T> models(String kind, List<T> choices, boolean running) {
        ComboBox<T> combo = new ComboBox<>();
        combo.setId("mcp" + kind + "Model");
        combo.getItems().setAll(choices);
        combo.setMaxWidth(Double.MAX_VALUE);
        combo.setCellFactory(list -> new ModelCell<>());
        combo.setButtonCell(new ModelCell<>());
        combo.setPromptText("Select a model (configured: " + settings.mcpModel(kind) + ")");
        choices.stream().filter(model -> model.id().equals(settings.mcpModel(kind))).findFirst()
                .ifPresent(combo::setValue);
        combo.setDisable(running);
        return combo;
    }

    private static String selected(ComboBox<? extends SpeechModelService> combo) {
        if (combo.getValue() == null) {
            throw new IllegalArgumentException("Select every default model");
        }
        return combo.getValue().id();
    }

    private static void copy(String value) {
        ClipboardContent content = new ClipboardContent();
        content.putString(value);
        if (!Clipboard.getSystemClipboard().setContent(content)) {
            UiSupport.error("Copy failed", "Could not write to the system clipboard");
        }
    }

    private static class ModelCell<T extends SpeechModelService> extends ListCell<T> {
        @Override
        protected void updateItem(T item, boolean empty) {
            super.updateItem(item, empty);
            setText(empty || item == null ? null : item.displayName() + " (" + item.id() + ")");
        }
    }
}
