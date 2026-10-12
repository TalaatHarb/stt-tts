package net.talaatharb.stttts.service;

import java.util.List;

import net.talaatharb.stttts.audio.AudioClip;
import net.talaatharb.stttts.model.ProgressListener;

/** Converts recorded speech into text. */
public interface SpeechToTextService extends SpeechModelService {
    /** Languages the model can transcribe; {@link Language#AUTO} means automatic detection is supported. */
    List<Language> languages();

    Transcription transcribe(AudioClip audio, TranscriptionRequest request, ProgressListener progress);
}
