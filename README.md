# STT-TTS Studio

A JavaFX desktop app for local, offline speech processing. It has three tabs:

| Tab | Service interface | Implementation | Model |
| --- | --- | --- | --- |
| Speech to Text | `SpeechToTextService` | `WhistleSpeechToTextService` | [Whistle](https://huggingface.co/Cactus-Compute/whistle) by Cactus Compute (16.9 MB `whistle.cact`) |
| Text to Speech | `TextToSpeechService` | `KokoroTextToSpeechService` | [Kokoro-82M](https://huggingface.co/onnx-community/Kokoro-82M-v1.0-ONNX), English (Apache 2.0) |
| | | `SupertonicTextToSpeechService` | [Supertonic 3](https://huggingface.co/Supertone/supertonic-3), 31 languages |
| Voice Cloning | `VoiceCloningService` | `ChatterboxVoiceCloningService` | [Chatterbox](https://huggingface.co/onnx-community/chatterbox-ONNX), English zero-shot cloning (MIT) |

## No extra installs

You only need Java 17+ and Maven. There is no Python, espeak or separate runtime to install.

- Kokoro, Supertonic and Chatterbox run in-process on [ONNX Runtime for Java](https://onnxruntime.ai/). Its native libraries ship inside the Maven dependency.
- Whistle runs on Cactus' prebuilt `needle` engine executable for your OS/CPU, which the app downloads and calls as a subprocess.
- Tokenizers and English grapheme-to-phoneme conversion are written in pure Java. Kokoro uses the [misaki](https://github.com/hexgrad/misaki) pronunciation dictionaries.
- Model files are downloaded from Hugging Face the first time each model is used. They are cached in `~/.stt-tts/models` (override with `-Dstttts.models=<dir>`).

| Model | Download size |
| --- | --- |
| Whistle + engine | ~18 MB |
| Kokoro-82M (int8) + dictionaries | ~100 MB |
| Supertonic 3 | ~400 MB |
| Chatterbox (4-bit language model) | ~1.5 GB |

## Run

```
mvn javafx:run
```

To build a self-contained jar:

```
mvn package
java -jar target/stt-tts-*-jar-with-dependencies.jar
```

## Tests

```
mvn verify                                                    # unit tests
mvn test -Dtest=ModelsIntegrationTest -Dstttts.integration=true  # real models
```

The integration test downloads every model. It synthesizes speech with Kokoro, Supertonic and Chatterbox, then checks that Whistle transcribes it back correctly.

## Structure

The project setup follows [activity-dag](https://github.com/TalaatHarb/activity-dag):

- Guice `AppModule` and FXML controllers created through `injector::getInstance`.
- Single-instance lock and system tray.
- GitHub build and release workflows.

```
net.talaatharb.stttts
├── SttTtsApp / SttTtsLauncher / ApplicationTray / SingleInstance
├── config/AppModule              Guice bindings (services, audio, model store)
├── service/                      the three service interfaces + request/result records
├── stt/whistle/                  Whistle via the needle engine
├── tts/kokoro/                   Kokoro + pure Java English G2P
├── tts/supertonic/               Supertonic 3
├── cloning/chatterbox/           Chatterbox + pure Java BPE tokenizer
├── audio/                        WAV I/O, resampling, microphone recording, playback
├── model/                        Hugging Face downloader, model cache, ONNX helpers
└── ui/                           tab controllers, background task runner
```

Only clone voices you have permission to use.
