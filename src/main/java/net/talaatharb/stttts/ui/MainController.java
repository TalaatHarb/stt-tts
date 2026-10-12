package net.talaatharb.stttts.ui;

import com.google.inject.Inject;

import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;

/** Hosts the three tabs and the shared status bar. */
public class MainController {
    private final TaskRunner runner;

    @FXML private Label statusLabel;
    @FXML private ProgressBar statusProgress;
    @FXML private Button cancelButton;

    @Inject
    public MainController(TaskRunner runner) {
        this.runner = runner;
    }

    @FXML
    private void initialize() {
        statusLabel.textProperty().bind(runner.messageProperty());
        statusProgress.progressProperty().bind(runner.progressProperty());
        statusProgress.visibleProperty().bind(runner.runningProperty());
        cancelButton.disableProperty().bind(runner.runningProperty().not());
    }

    @FXML
    private void onCancel() {
        runner.cancel();
    }
}
