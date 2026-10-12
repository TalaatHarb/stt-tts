package net.talaatharb.stttts.ui;

import java.util.function.Consumer;

import javafx.animation.AnimationTimer;
import javafx.scene.control.Button;
import javafx.scene.control.ProgressBar;
import net.talaatharb.stttts.audio.AudioClip;
import net.talaatharb.stttts.audio.AudioRecorder;

/** Wires a record/stop toggle button and a level meter to the shared microphone recorder. */
final class RecordingControl {
    private final AudioRecorder recorder;
    private final Button button;
    private final ProgressBar level;
    private final Consumer<AudioClip> onRecorded;
    private final String idleText;
    private final AnimationTimer meter;
    private boolean active;

    RecordingControl(AudioRecorder recorder, Button button, ProgressBar level, Consumer<AudioClip> onRecorded) {
        this.recorder = recorder;
        this.button = button;
        this.level = level;
        this.onRecorded = onRecorded;
        this.idleText = button.getText();
        this.meter = new AnimationTimer() {
            @Override
            public void handle(long now) {
                level.setProgress(Math.min(1, recorder.level() * 3));
            }
        };
        level.setProgress(0);
        button.setOnAction(e -> toggle());
    }

    void toggle() {
        if (active) {
            stop();
            return;
        }
        if (recorder.isRecording()) {
            UiSupport.error("Microphone busy", "Another tab is recording. Stop that recording first.");
            return;
        }
        try {
            recorder.start();
            active = true;
            button.setText("Stop recording");
            button.getStyleClass().add("recording");
            meter.start();
        } catch (Exception e) {
            UiSupport.error("Could not open the microphone", e.getMessage());
        }
    }

    void stop() {
        if (!active) {
            return;
        }
        active = false;
        meter.stop();
        level.setProgress(0);
        button.setText(idleText);
        button.getStyleClass().remove("recording");
        AudioClip clip = recorder.stop();
        if (clip != null && !clip.isEmpty()) {
            onRecorded.accept(clip);
        }
    }
}
