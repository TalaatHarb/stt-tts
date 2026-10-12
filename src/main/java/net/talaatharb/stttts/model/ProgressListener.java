package net.talaatharb.stttts.model;

import java.util.concurrent.CancellationException;

/** Reports progress of long-running model operations and lets the caller cancel them. */
public interface ProgressListener {
    ProgressListener NONE = (message, fraction) -> {
    };

    /**
     * @param message  human readable status
     * @param fraction completion in [0, 1], or a negative value when unknown
     */
    void update(String message, double fraction);

    default boolean isCancelled() {
        return false;
    }

    /** Throws {@link CancellationException} if the caller asked to stop. */
    default void checkCancelled() {
        if (isCancelled() || Thread.currentThread().isInterrupted()) {
            throw new CancellationException("Cancelled");
        }
    }

    /** Maps this listener's [0, 1] range onto [start, end] of a parent operation. */
    default ProgressListener slice(double start, double end) {
        ProgressListener parent = this;
        return new ProgressListener() {
            @Override
            public void update(String message, double fraction) {
                parent.update(message, fraction < 0 ? -1 : start + (end - start) * Math.min(1, fraction));
            }

            @Override
            public boolean isCancelled() {
                return parent.isCancelled();
            }
        };
    }
}
