package net.talaatharb.stttts.ui;

import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

import com.google.inject.Singleton;

import javafx.application.Platform;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.ReadOnlyBooleanWrapper;
import javafx.beans.property.ReadOnlyDoubleProperty;
import javafx.beans.property.ReadOnlyDoubleWrapper;
import javafx.beans.property.ReadOnlyStringProperty;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.concurrent.Task;
import net.talaatharb.stttts.model.ProgressListener;

/**
 * Runs model work (downloads and inference) one job at a time off the JavaFX thread and exposes its progress for
 * the status bar. Running jobs sequentially keeps memory use bounded when large models are involved.
 */
@Singleton
public class TaskRunner {
    /** Work that reports progress and may be cancelled. */
    @FunctionalInterface
    public interface Work<T> {
        T run(ProgressListener progress) throws Exception;
    }

    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "stt-tts-worker");
        thread.setDaemon(true);
        return thread;
    });
    private final ReadOnlyBooleanWrapper running = new ReadOnlyBooleanWrapper(false);
    private final ReadOnlyStringWrapper message = new ReadOnlyStringWrapper("Ready");
    private final ReadOnlyDoubleWrapper progress = new ReadOnlyDoubleWrapper(0);
    private Task<?> current;

    public ReadOnlyBooleanProperty runningProperty() {
        return running.getReadOnlyProperty();
    }

    public ReadOnlyStringProperty messageProperty() {
        return message.getReadOnlyProperty();
    }

    public ReadOnlyDoubleProperty progressProperty() {
        return progress.getReadOnlyProperty();
    }

    public boolean isRunning() {
        return running.get();
    }

    /** Starts work unless another job is running; callbacks are invoked on the JavaFX thread. */
    public <T> boolean submit(String title, Work<T> work, Consumer<T> onSuccess, Consumer<Throwable> onFailure) {
        if (running.get()) {
            UiSupport.error("Busy", "Please wait for \"" + message.get() + "\" to finish or cancel it first.");
            return false;
        }
        Task<T> task = new WorkTask<>(title, work);
        current = task;
        running.set(true);
        message.bind(task.messageProperty());
        progress.bind(task.progressProperty());
        task.setOnSucceeded(e -> {
            finish("Done: " + title);
            if (onSuccess != null) {
                onSuccess.accept(task.getValue());
            }
        });
        task.setOnCancelled(e -> finish("Cancelled: " + title));
        task.setOnFailed(e -> {
            Throwable error = task.getException();
            if (error instanceof CancellationException) {
                finish("Cancelled: " + title);
                return;
            }
            finish("Failed: " + title);
            if (onFailure != null) {
                onFailure.accept(error);
            }
            UiSupport.error(title + " failed", error.getMessage() == null ? error.toString() : error.getMessage());
        });
        executor.submit(task);
        return true;
    }

    private void finish(String status) {
        message.unbind();
        progress.unbind();
        message.set(status);
        progress.set(0);
        running.set(false);
        current = null;
    }

    public void cancel() {
        Task<?> task = current;
        if (task != null) {
            task.cancel(true);
        }
    }

    public void shutdown() {
        cancel();
        executor.shutdownNow();
    }

    static void runLater(Runnable action) {
        if (Platform.isFxApplicationThread()) {
            action.run();
        } else {
            Platform.runLater(action);
        }
    }

    private static final class WorkTask<T> extends Task<T> {
        private final String title;
        private final Work<T> work;

        WorkTask(String title, Work<T> work) {
            this.title = title;
            this.work = work;
        }

        @Override
        protected T call() throws Exception {
            ProgressListener listener = new ProgressListener() {
                @Override
                public void update(String text, double fraction) {
                    updateMessage(text);
                    updateProgress(fraction < 0 ? -1 : fraction, fraction < 0 ? -1 : 1);
                }

                @Override
                public boolean isCancelled() {
                    return WorkTask.this.isCancelled();
                }
            };
            listener.update(title + "...", -1);
            return work.run(listener);
        }
    }
}
