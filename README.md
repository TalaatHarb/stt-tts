# STT-TTS Studio

A JavaFX desktop app for local, offline speech processing. It has three tabs, and each tab has an engine selector:

| Tab | Service interface | Implementations |
| --- | --- | --- |
| Speech to Text | `SpeechToTextService` | `WhistleSpeechToTextService` |
| Text to Speech | `TextToSpeechService` | `KokoroTextToSpeechService`, `SupertonicTextToSpeechService`, `PocketTextToSpeechService`, `PiperTextToSpeechService` |
| Voice Cloning | `VoiceCloningService` | `PocketVoiceCloningService`, `ChatterboxVoiceCloningService` |

## Models and licenses

| Engine | Used for | Source | Languages / voices | License |
| --- | --- | --- | --- | --- |
| [Whistle](https://huggingface.co/Cactus-Compute/whistle) (Cactus Compute) | STT | 16.9 MB `whistle.cact`, run by the [needle](https://huggingface.co/Cactus-Compute/needle3) engine | English | Apache 2.0 (model and engine) |
| [Kokoro-82M](https://huggingface.co/hexgrad/Kokoro-82M) | TTS | [ONNX export](https://huggingface.co/onnx-community/Kokoro-82M-v1.0-ONNX) (int8) | US and GB English voices | Apache 2.0 |
| [Supertonic 3](https://huggingface.co/Supertone/supertonic-3) (Supertone) | TTS | Official ONNX | 31 languages | Model: OpenRAIL-M (use-based restrictions). Sample code: MIT |
| [Pocket TTS](https://huggingface.co/kyutai/pocket-tts) (Kyutai) | TTS + cloning | [ONNX export](https://huggingface.co/KevinAHM/pocket-tts-onnx) (int8). Voices from [kyutai/pocket-tts-without-voice-cloning](https://huggingface.co/kyutai/pocket-tts-without-voice-cloning) | English, French, German, Spanish, Italian, Portuguese; 27 voices | CC-BY-4.0 (attribution required) |
| [Piper](https://huggingface.co/rhasspy/piper-voices) | TTS | Official ONNX voices (medium quality) | 14 US and GB English voices | Repository: MIT. Each voice also carries its training dataset's license; see below |
| [Chatterbox](https://huggingface.co/ResembleAI/chatterbox) (Resemble AI) | Cloning | [ONNX export](https://huggingface.co/onnx-community/chatterbox-ONNX) (4-bit LM) | English | MIT |

Piper voice datasets (from each voice's `MODEL_CARD`):

| License | Voices |
| --- | --- |
| Public domain or CC0 | kristin, bryce, john, cori, joe |
| CC-BY 4.0 | alba |
| CC-BY-SA 4.0 | northern_english_male |
| **CC-BY-NC-SA 4.0 (non-commercial)** | hfc_female, hfc_male |
| Blizzard 2013 dataset license | lessac |
| Mycroft mimic3 / mimic2 dataset terms | amy, alan, kusal |
| Jenny (Dioco) dataset license | jenny_dioco |

Check these before any commercial use.

Other components:

| Component | Purpose | License |
| --- | --- | --- |
| [misaki](https://github.com/hexgrad/misaki) dictionaries | Pronunciations for Kokoro and Piper (downloaded) | Apache 2.0 |
| [ONNX Runtime](https://onnxruntime.ai/) for Java | Runs the models | MIT |
| DirectML ([Microsoft.AI.DirectML](https://www.nuget.org/packages/Microsoft.AI.DirectML)) | Optional GPU runtime (downloaded) | Microsoft DirectML license (redistributable) |
| JavaFX | UI | GPL 2.0 with Classpath Exception |
| Guice, Jackson | DI, JSON | Apache 2.0 |

Only clone voices you have permission to use.

## No extra installs

You only need Java 17+ and Maven. There is no Python, espeak, CUDA or separate runtime to install.

- All TTS and cloning engines run in-process on ONNX Runtime for Java. Its native libraries ship inside the Maven dependency.
- Whistle runs on Cactus' prebuilt `needle` engine executable for your OS/CPU, which the app downloads and calls as a subprocess.
- These parts are written in pure Java:
  - BPE (Chatterbox) and SentencePiece (Pocket) tokenizers
  - SafeTensors and NumPy `.npy` readers
  - English grapheme-to-phoneme conversion, with a misaki-to-espeak phoneme mapping for Piper
- Model files are downloaded from Hugging Face the first time each model is used. They are cached in `~/.stt-tts/models`.

| Model | Download size |
| --- | --- |
| Whistle + engine | ~18 MB |
| Kokoro-82M (int8) + dictionaries | ~100 MB |
| Supertonic 3 | ~400 MB |
| Pocket TTS (int8) | ~120 MB per language (French ~290 MB), plus 38 MB for the cloning encoder |
| Piper | ~63 MB per voice (dictionaries shared with Kokoro) |
| Chatterbox (4-bit language model) | ~1.5 GB |
| DirectML runtime (optional) | ~15 MB |

## Engine notes

- **Pocket TTS** is used both for TTS (built-in voices) and for cloning. Both share one loaded model per language.
  - Cloning takes a reference recording of up to 30 s; about 10 s is recommended. The language is chosen in the cloning tab.
  - Pocket ignores the speed setting.
- **Piper** applies speed through `length_scale`. Multi-speaker voices are not included.
- **Chatterbox** is listed after Pocket because loading it takes about 1.5 minutes. After that, a clone takes ~12 s on the CPU.
  - Only Chatterbox supports the emotion *exaggeration* slider; the slider is hidden for other engines.
  - The reference is truncated to 10 s.
  - **Use sample voice** downloads Chatterbox's bundled default voice as a reference.

## GPU acceleration (optional)

Turn on **GPU (DirectML)** in the status bar to run the Chatterbox audio decoder on any DirectX 12 GPU (Windows x64 only). The first time, the app downloads the DirectML build of ONNX Runtime (~15 MB, from Microsoft's NuGet packages), and you need to restart the app. The setting is saved in `~/.stt-tts/settings.properties`.

- On an RTX 3050 laptop GPU, a warm clone takes about 4 s instead of 12 s. Audio decoding drops from 10 s to 2 s.
- Only the decoder is accelerated. DirectML can't run the language model's rotary GroupQueryAttention, so the language model stays on the CPU (about 1.6 s per sentence). The other engines are small enough to run best on the CPU.
- In GPU mode, text is split into ~70-character chunks and the reference is limited to 6 s. This keeps each decode under 250 tokens; longer sequences can trip the Windows GPU watchdog (TDR).
- The high-performance (discrete) GPU is chosen automatically. On hybrid laptops the integrated GPU is slower than the CPU.
- Loading Chatterbox still takes about 1.5 minutes either way. The decoder graph has ~24k nodes, so session creation dominates the load time.
- CUDA isn't used because it needs a 486 MB ONNX Runtime jar plus a system-wide CUDA 12 and cuDNN 9 installation.

## Configuration

| System property | Default | Purpose |
| --- | --- | --- |
| `stttts.models` | `~/.stt-tts/models` | Model cache directory |
| `stttts.device` | saved setting | `gpu` or `cpu`; overrides the GPU checkbox |
| `stttts.gpu.device` | high-performance GPU | DirectML adapter index |
| `stttts.chatterbox.gpuTokenLimit` | `250` | Longest token sequence decoded on the GPU; longer ones fall back to the CPU |
| `stttts.integration` | `false` | Enables the model integration tests |

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
mvn verify                                                       # unit tests
mvn test -Dtest=ModelsIntegrationTest -Dstttts.integration=true  # real models (add -Dstttts.device=gpu for DirectML)
```

The integration test downloads every model. It synthesizes speech with Kokoro, Supertonic, Pocket TTS, Piper and Chatterbox (plus a Pocket clone), then checks that Whistle transcribes it back correctly. The Chatterbox test runs twice to measure the warm (already loaded) timing.

## Structure

The project setup follows [activity-dag](https://github.com/TalaatHarb/activity-dag):

- Guice `AppModule` and FXML controllers created through `injector::getInstance`.
- Single-instance lock and system tray.
- GitHub build and release workflows.

```
net.talaatharb.stttts
├── SttTtsApp / SttTtsLauncher / ApplicationTray / SingleInstance
├── config/                       AppModule (service lists, audio, model store), AppSettings (saved preferences)
├── service/                      the three service interfaces + request/result records
├── stt/whistle/                  Whistle via the needle engine
├── tts/kokoro/                   Kokoro + pure Java English G2P
├── tts/supertonic/               Supertonic 3
├── tts/pocket/                   Pocket TTS engine (shared with cloning), SentencePiece, SafeTensors, NumPy
├── tts/piper/                    Piper + misaki-to-espeak phoneme mapping
├── cloning/pocket/               Pocket TTS voice cloning
├── cloning/chatterbox/           Chatterbox + pure Java BPE tokenizer, hybrid CPU/GPU decoder
├── audio/                        WAV I/O, resampling, microphone recording, playback
├── model/                        Hugging Face downloader, model cache, ONNX helpers, DirectML runtime
└── ui/                           tab controllers, background task runner
```

## Roadmap (future work)

None of these are implemented yet. The notes below come from checking the model repositories (November 2026).

### More speech-to-text engines

- [ ] **Moonshine** ([moonshine-ai](https://huggingface.co/moonshine-ai), [ONNX](https://huggingface.co/onnx-community/moonshine-base-ONNX))
  - Tiny (27M) and Base (61M) English models are MIT. Streaming variants (tiny/small/medium, plus ja/de/es/vi/zh) are also MIT.
  - **The non-English Tiny/Base models (ar, ja, ko, uk, vi, zh, es) use the non-commercial Moonshine Community License.**
  - Good fit: it takes raw 16 kHz audio, so no mel frontend is needed. It is an encoder plus a KV-cached decoder, the same loop as Chatterbox's LM. The tokenizer is a HF `tokenizer.json`, so the existing BPE code may be reusable.
  - Caveats:
    - Keep segments under ~30 s.
    - Cap decoder steps (about 6 tokens per second of audio).
    - It hallucinates on silence, so add a VAD such as [Silero VAD](https://github.com/snakers4/silero-vad) (MIT, ~2 MB ONNX) to split the audio first.
- [ ] **Vosk** ([alphacephei](https://alphacephei.com/vosk/))
  - Available from Maven Central as `com.alphacephei:vosk` (0.3.45, Apache 2.0). It uses JNA, with native libraries for Windows, Linux and macOS inside the jar, so nothing needs installing.
  - Models are zip files from alphacephei.com, not Hugging Face, so the downloader needs zip support. The small English model is ~40 MB. Licenses vary per model (most are Apache 2.0).
  - Strengths: true streaming with word timestamps, and 20+ languages. Weakness: Kaldi-era accuracy, noticeably below Whisper or Moonshine.
  - The project has had few releases recently.
- [ ] **Sherpa-ONNX** ([k2-fsa](https://github.com/k2-fsa/sherpa-onnx), Apache 2.0)
  - Its Java bindings are **not on Maven Central**. They ship as `sherpa-onnx-jvm-<v>.jar` plus a per-platform native jar on GitHub releases, so the app would download the native jar at runtime.
  - It bundles its own `onnxruntime` library, which may clash with ONNX Runtime Java (and the DirectML swap) in the same process. Test this, or load it in a separate class loader or subprocess.
  - Alternative: run its models (Zipformer transducer, Paraformer, SenseVoice) directly with ONNX Runtime Java. That needs a Kaldi-compatible fbank feature extractor and transducer greedy search written in Java.
  - Model licenses vary (for example, SenseVoice uses the FunASR model license). Check each one.
- [ ] **Chatterbox as STT: not possible.** Chatterbox is a TTS model only; its speech tokenizer produces acoustic tokens, not text.

### Chatterbox as a TTS engine

- [ ] Add a `ChatterboxTextToSpeechService` that reuses the cloning engine with the bundled `default_voice.wav` as a fixed speaker. Optionally add a few preset reference voices as "voices".
  - Caveats: the ~1.5 minute load and ~12 s per sentence on CPU (4 s with GPU) make it the slowest TTS option. Share one loaded engine with the cloning service, as Pocket does.
- [ ] Consider **[Chatterbox Turbo](https://huggingface.co/ResembleAI/chatterbox-turbo-ONNX)** (official ONNX, MIT).
  - It is a smaller model with a faster, distilled decoder, which could fix the slow load. It supports paralinguistic tags such as `[laugh]`.
  - Its ONNX layout differs (q4 decoder ~235 MB), so it needs its own pipeline.
- [ ] Consider **[Chatterbox Multilingual](https://huggingface.co/onnx-community/chatterbox-multilingual-ONNX)** (MIT, 23 languages) for non-English cloning.

### New tab: sound effects and background music

Add an `AudioGenerationService` interface (prompt, duration, seed, steps → `AudioClip`) and a fourth tab with prompt, duration and seed controls.

General caveats:

- These models are 10–100× heavier than the TTS models. Expect minutes per clip on CPU, so GPU (DirectML) support matters much more here.
- Several have **non-commercial licenses**. Show the license in the engine description and ask for acceptance before downloading.
- Gated Hugging Face repositories need token support in `HuggingFaceDownloader` (an `Authorization: Bearer` header plus a settings field).
- Most have no official ONNX export. Exporting them (text encoder, diffusion model, VAE, vocoder) and writing the samplers in Java (DDIM, DPM++, flow matching) is the bulk of the work.

- [ ] **Stable Audio Open 1.0** ([stabilityai](https://huggingface.co/stabilityai/stable-audio-open-1.0))
  - 1.2B parameters (T5-base text encoder, DiT, Oobleck VAE). Produces 44.1 kHz stereo, up to 47 s. Good for sound effects and short loops.
  - **Stability AI Community License: free under $1M annual revenue; above that, an enterprise license is required.** The repo is gated (needs a token).
  - No official ONNX. It uses a v-objective DPM++ sampler with ~100 steps, which is slow on CPU.
  - **Better options:**
    - **Stable Audio Open Small** (341M, 11 s clips, gated).
    - The newer **Stable Audio 3** small-sfx and small-music models, which have **official ONNX** in [stable-audio-3-optimized](https://huggingface.co/stabilityai/stable-audio-3-optimized) (not gated; the fp16 DiT is ~878 MB). Its T5Gemma text encoder falls under the Gemma Terms of Use.
- [ ] **AudioLDM 2** ([cvssp/audioldm2](https://huggingface.co/cvssp/audioldm2))
  - **CC-BY-NC-SA 4.0 (non-commercial).** Produces 16 kHz mono sound effects.
  - There is no official "small" checkpoint. The base model is the smallest, at ~1.1B in total including the text encoders.
  - Complex pipeline: CLAP, Flan-T5, GPT-2 "language of audio" model, latent diffusion UNet, VAE and HiFi-GAN vocoder. That is about 6 ONNX graphs to export, and it needs ~100–200 DDIM steps.
- [ ] **AudioLDM 2 Music** ([cvssp/audioldm2-music](https://huggingface.co/cvssp/audioldm2-music))
  - Same architecture, caveats and **CC-BY-NC-SA 4.0** license as AudioLDM 2, trained for music. If AudioLDM 2 is implemented, this is just a weight swap.
- [ ] **Tango 2 / Tango** ([declare-lab/tango2](https://huggingface.co/declare-lab/tango2))
  - **CC-BY-NC-SA 4.0 (non-commercial).** Flan-T5-large plus a ~866M UNet, with the AudioLDM VAE and vocoder. Produces 16 kHz, ~10 s clips.
  - Tango 2 is DPO-aligned, so it follows prompts better.
  - No official small variant and no ONNX. The export effort is similar to AudioLDM, and the Flan-T5-large encoder alone is ~780M.
- [ ] **MusicGen Small** ([facebook/musicgen-small](https://huggingface.co/facebook/musicgen-small))
  - 300M decoder, plus a T5-base text encoder and a 32 kHz EnCodec decoder. **Weights are CC-BY-NC 4.0 (non-commercial)**; the code is MIT.
  - **Easiest to implement:** there is a ready [ONNX export](https://huggingface.co/Xenova/musicgen-small) (text encoder, merged decoder at int8 ~405 MB or q4 ~264 MB, EnCodec decoder, delay-pattern mask). It is an autoregressive loop like Chatterbox's LM, and the T5 tokenizer is SentencePiece, which already exists in Java.
  - Caveats:
    - Music only (no sound effects).
    - 50 steps per second of audio across 4 codebooks with a delay pattern, so 10 s is ~500 steps.
    - Needs classifier-free guidance (batch of 2) and top-k sampling.
- [ ] **ACE-Step** ([ACE-Step](https://huggingface.co/ACE-Step))
  - v1 (3.5B, Apache 2.0) is too heavy for CPU.
  - **v1.5 (MIT)** generates full songs with lyrics, at 48 kHz stereo. It has three parts:
    - A DiT; the turbo variants need ~8 steps.
    - An optional "5Hz" planner LM (0.6B or 4B).
    - A 1D VAE.
  - There is no official "Mini". The smallest setup is the turbo DiT plus the 0.6B LM.
  - Only community ONNX exports exist (for example [shreyask/ACE-Step-v1.5-ONNX](https://huggingface.co/shreyask/ACE-Step-v1.5-ONNX)). They are multi-GB: the fp16 DiT is ~3 GB and the q4 condition encoder ~331 MB. Check their licenses and quality.
  - Realistically it needs a GPU and 8 GB or more of RAM.

Suggested order: MusicGen Small (ready ONNX, existing autoregressive code), then Stable Audio 3 small-sfx/music (official ONNX, covers sound effects), then the diffusion models that need custom exports.
