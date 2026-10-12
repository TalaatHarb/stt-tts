package net.talaatharb.stttts.service;

/** Raised when a model cannot be loaded or inference fails. */
public class SpeechServiceException extends RuntimeException {
    public SpeechServiceException(String message) {
        super(message);
    }

    public SpeechServiceException(String message, Throwable cause) {
        super(message, cause);
    }
}
