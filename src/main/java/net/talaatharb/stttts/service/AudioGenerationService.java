package net.talaatharb.stttts.service;

import net.talaatharb.stttts.audio.AudioClip;
import net.talaatharb.stttts.model.ProgressListener;

/** Locally executed music or sound-effects model. Output is downmixed to the app's mono PCM format. */
public interface AudioGenerationService extends SpeechModelService {
    String licenseName();

    String licenseUrl();

    double maxDurationSeconds();

    AudioClip generate(AudioGenerationRequest request, ProgressListener progress);
}
