package net.talaatharb.stttts.service;

import java.util.List;

import net.talaatharb.stttts.audio.AudioClip;
import net.talaatharb.stttts.model.ProgressListener;

/** Synthesizes speech from text using one of the model's built-in voices. */
public interface TextToSpeechService extends SpeechModelService {
    List<Language> languages();

    List<Voice> voices();

    AudioClip synthesize(SpeechRequest request, ProgressListener progress);
}
