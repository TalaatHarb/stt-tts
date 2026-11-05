package net.talaatharb.stttts.ui;

import java.util.List;

import com.google.inject.Inject;

import javafx.fxml.FXML;
import javafx.scene.control.Alert;
import javafx.scene.control.Alert.AlertType;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.Tooltip;
import net.talaatharb.stttts.config.AppSettings;
import net.talaatharb.stttts.model.GpuRuntime;
import net.talaatharb.stttts.model.ModelStore;
import net.talaatharb.stttts.service.VoiceCloningService;
import net.talaatharb.stttts.service.AudioGenerationService;

/** Hosts the tabs and the shared status bar (with the GPU acceleration switch). */
public class MainController {
    private final TaskRunner runner;
    private final AppSettings settings;
    private final ModelStore store;
    private final List<VoiceCloningService> cloningServices;
    private final List<AudioGenerationService> generationServices;

    @FXML private Label statusLabel;
    @FXML private ProgressBar statusProgress;
    @FXML private Button cancelButton;
    @FXML private CheckBox gpuCheck;

    @Inject
    public MainController(TaskRunner runner, AppSettings settings, ModelStore store,
            List<VoiceCloningService> cloningServices, List<AudioGenerationService> generationServices) {
        this.runner = runner;
        this.settings = settings;
        this.store = store;
        this.cloningServices = cloningServices;
        this.generationServices = generationServices;
    }

    @FXML
    private void initialize() {
        statusLabel.textProperty().bind(runner.messageProperty());
        statusProgress.progressProperty().bind(runner.progressProperty());
        statusProgress.visibleProperty().bind(runner.runningProperty());
        cancelButton.disableProperty().bind(runner.runningProperty().not());
        if (GpuRuntime.isSupported()) {
            gpuCheck.setSelected(GpuRuntime.isEnabled());
            gpuCheck.disableProperty().bind(runner.runningProperty());
            gpuCheck.setTooltip(new Tooltip("Accelerates Chatterbox decoding and music/sound-effects generation "
                    + "through DirectML (~15 MB one-time download). Unsupported graphs run on the CPU."));
        } else {
            gpuCheck.setDisable(true);
            gpuCheck.setTooltip(new Tooltip("GPU acceleration (DirectML) is only available on 64-bit Windows"));
        }
    }

    @FXML
    private void onCancel() {
        runner.cancel();
    }

    @FXML
    private void onGpuToggled() {
        boolean wanted = gpuCheck.isSelected();
        if (!wanted) {
            apply(false);
            return;
        }
        if (GpuRuntime.isInstalled(store)) {
            enableInstalledRuntime();
            return;
        }
        Alert confirm = new Alert(AlertType.CONFIRMATION,
                "GPU acceleration needs the DirectML build of ONNX Runtime (about 15 MB, downloaded once from "
                        + "Microsoft's NuGet packages). Download it now?", ButtonType.OK, ButtonType.CANCEL);
        confirm.setHeaderText("Enable GPU acceleration");
        if (confirm.showAndWait().filter(ButtonType.OK::equals).isEmpty()) {
            gpuCheck.setSelected(false);
            return;
        }
        boolean started = runner.submit("Installing the GPU runtime", progress -> {
            GpuRuntime.install(store, progress);
            return null;
        }, ignored -> enableInstalledRuntime(), error -> gpuCheck.setSelected(false));
        if (!started) {
            gpuCheck.setSelected(false);
        }
    }

    private void enableInstalledRuntime() {
        if (GpuRuntime.activate(store)) {
            apply(true);
            return;
        }
        // ONNX Runtime's CPU build is already loaded in this process and can't be replaced.
        settings.setGpuEnabled(true);
        GpuRuntime.setEnabled(true);
        Alert info = new Alert(AlertType.INFORMATION,
                "The GPU runtime is installed. Restart STT-TTS Studio to start using the GPU.");
        info.setHeaderText("Restart required");
        info.showAndWait();
    }

    private void apply(boolean enabled) {
        GpuRuntime.setEnabled(enabled);
        settings.setGpuEnabled(enabled);
        // Loaded models keep their device; release them so the next run opens them with the new setting.
        cloningServices.forEach(VoiceCloningService::close);
        generationServices.forEach(AudioGenerationService::close);
    }
}
