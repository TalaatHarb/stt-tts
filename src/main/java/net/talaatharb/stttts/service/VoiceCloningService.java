package net.talaatharb.stttts.service;

import java.util.List;

import net.talaatharb.stttts.audio.AudioClip;
import net.talaatharb.stttts.model.ProgressListener;

/** Speaks text in the voice of a short reference recording (zero-shot voice cloning). */
public interface VoiceCloningService extends SpeechModelService {
    List<Language> languages();

    /** Recommended length of the reference clip in seconds. */
    double recommendedReferenceSeconds();

    /** True when {@link VoiceCloneRequest#exaggeration()} has an effect. */
    default boolean supportsExaggeration() {
        return false;
    }

    AudioClip cloneVoice(VoiceCloneRequest request, ProgressListener progress);
}
