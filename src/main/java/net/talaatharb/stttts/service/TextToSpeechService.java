package net.talaatharb.stttts.service;

import java.util.List;

import net.talaatharb.stttts.audio.AudioClip;
import net.talaatharb.stttts.model.ProgressListener;

/** Synthesizes speech from text using one of the model's built-in voices. */
public interface TextToSpeechService extends SpeechModelService {
    List<Language> languages();

    List<Voice> voices();

    /** Whether the exact requested voice/language can run without downloading additional assets. */
    default boolean isInstalled(SpeechRequest request) {
        return isInstalled();
    }

    AudioClip synthesize(SpeechRequest request, ProgressListener progress);
}
