# STT-TTS Studio

A JavaFX desktop app for local, offline speech and audio generation. It has four tabs; TTS, cloning and audio generation offer engine selectors:

| Tab | Service interface | Implementations |
| --- | --- | --- |
| Speech to Text | `SpeechToTextService` | `WhistleSpeechToTextService` |
| Text to Speech | `TextToSpeechService` | `KokoroTextToSpeechService`, `SupertonicTextToSpeechService`, `PocketTextToSpeechService`, `PiperTextToSpeechService` |
| Voice Cloning | `VoiceCloningService` | `PocketVoiceCloningService`, `ChatterboxVoiceCloningService` |
| Music & Sound Effects | `AudioGenerationService` | `MusicGenSmallService`, `StableAudioService` (Small SFX and Small Music) |

## Models and licenses

| Engine | Used for | Source | Languages / voices | License |
| --- | --- | --- | --- | --- |
| [Whistle](https://huggingface.co/Cactus-Compute/whistle) (Cactus Compute) | STT | 16.9 MB `whistle.cact`, run by the [Cactus runtime](https://huggingface.co/Cactus-Compute/cactus) | English, German, French, Spanish, Italian, Dutch, Polish | Apache 2.0 (model and engine) |
| [Kokoro-82M](https://huggingface.co/hexgrad/Kokoro-82M) | TTS | [ONNX export](https://huggingface.co/onnx-community/Kokoro-82M-v1.0-ONNX) (int8) | US and GB English voices | Apache 2.0 |
| [Supertonic 3](https://huggingface.co/Supertone/supertonic-3) (Supertone) | TTS | Official ONNX | 31 languages | Model: OpenRAIL-M (use-based restrictions). Sample code: MIT |
| [Pocket TTS](https://huggingface.co/kyutai/pocket-tts) (Kyutai) | TTS + cloning | [ONNX export](https://huggingface.co/KevinAHM/pocket-tts-onnx) (int8). Voices from [kyutai/pocket-tts-without-voice-cloning](https://huggingface.co/kyutai/pocket-tts-without-voice-cloning) | English, French, German, Spanish, Italian, Portuguese; 27 voices | CC-BY-4.0 (attribution required) |
| [Piper](https://huggingface.co/rhasspy/piper-voices) | TTS | Official ONNX voices (medium quality) | 14 US and GB English voices | Repository: MIT. Each voice also carries its training dataset's license; see below |
| [Chatterbox](https://huggingface.co/ResembleAI/chatterbox) (Resemble AI) | Cloning | [ONNX export](https://huggingface.co/onnx-community/chatterbox-ONNX) (4-bit LM) | English | MIT |
| [MusicGen Small](https://huggingface.co/facebook/musicgen-small) (Meta) | Background music | [Xenova ONNX export](https://huggingface.co/Xenova/musicgen-small), int8 text encoder/decoder, FP32 EnCodec | English prompts; instrumental music, 32 kHz mono | Weights: **CC-BY-NC-4.0 (non-commercial, attribution)**. AudioCraft code: MIT |
| [Stable Audio 3 Small SFX / Music](https://huggingface.co/stabilityai/stable-audio-3-optimized) (Stability AI) | Sound effects / background music | Official experimental ONNX, FP16 DiT + SAME-S codec + T5Gemma | English prompts, 44.1 kHz stereo downmixed to mono | **Stability AI Community License** (commercial registration and revenue restrictions); text encoder: **Gemma Terms of Use** |

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
| Tribuo ONNX protobuf types | Portable BF16-to-FP32 codec preparation | Apache 2.0 |
| Protocol Buffers Java | Reads/writes ONNX graph metadata and weights | BSD 3-Clause |

Only clone voices you have permission to use.

## No extra installs

You only need Java 17+ and Maven. There is no Python, espeak, CUDA or separate runtime to install.

- All TTS, cloning, music and sound-effects engines run in-process on ONNX Runtime for Java. Its native libraries ship inside the Maven dependency.
- Whistle runs on the prebuilt Cactus CPU runtime (`cactus.exe` on Windows, `cactus` on Linux/macOS), which the app downloads and calls as a subprocess. Runtime 4.1.0 is pinned to revision `4e822c9036be2eb0e47ef556df32a818bb1572ef`, under `Cactus-Compute/cactus/releases/4.1.0/<platform>/`. The weights remain at `Cactus-Compute/whistle/whistle.cact`.
- Whistle runtime upgrades use a separate cache directory (`whistle/runtime-4.1.0/<platform>/`), preserving cached weights and old `needle` binaries. The new runtime downloads automatically on first STT use; deleting the model cache is unnecessary.
- These parts are written in pure Java:
  - BPE (Chatterbox, Stable Audio T5Gemma) and SentencePiece (Pocket, MusicGen T5) tokenizers
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
| MusicGen Small | ~630 MB |
| Stable Audio 3, first variant | ~1.7 GB; ~210 MB extra disk cache for the prepared FP32 codec |
| Stable Audio 3, second variant | ~880 MB extra; shares text encoder, tokenizer and codec |
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

## Music and sound effects

In **Music & Sound Effects**, select an engine, read and accept its license, enter an English prompt, choose a duration and a whole-number seed, then **Generate**. Playback is explicit; use **Play** or **Save WAV...** after generation. Models are downloaded on demand and run locally; prompts are not uploaded. Changing engines clears license acceptance.

- **MusicGen Small** generates music, not sound effects. It uses four delayed EnCodec codebooks, classifier-free guidance (3.0), seeded top-k sampling and a KV-cached decoder. Generation is limited to 10 s; 10 s takes about 500 autoregressive steps.
- **Stable Audio 3 Small SFX** generates noises, ambience and effects; **Small Music** generates background music. Both support 1-10 s with an 8-step ping-pong sampler and the official distribution-shift schedule. Prompts must fit 256 T5Gemma tokens.
- Stable Audio's SAME-L exports need custom TensorRT operators and cannot run in plain ONNX Runtime. The app instead uses the standard-operator SAME-S codec, promotes its BF16 operations/tensors to FP32 once in Java, and caches the result. The published weights are already FP32; the original file is unchanged. Inference sessions load and release one phase at a time to bound RAM/VRAM, so repeated generations also incur model-loading time.
- Stable Audio output is downmixed to the app's existing mono PCM format. Stereo playback/export, long tracks, continuation, inpainting, negative prompts and configurable sampling settings are not implemented.
- CPU generation can take minutes and the models require several GB of free RAM. A seed is reproducible for the same model/runtime/device, not guaranteed bit-identical across different devices.
- Cancellation is checked during downloads and between inference steps. A native model-loading or inference call must finish before cancellation takes effect.
- **Powered by Stability AI.** License copies download alongside its models. Commercial use requires reviewing the full Community License (including registration and the $1M revenue threshold) and Gemma terms. MusicGen is non-commercial; do not assume generated background music is licensed for business use.

## GPU acceleration (optional)

Turn on **GPU (DirectML)** in the status bar to accelerate Chatterbox decoding and supported audio-generation graphs on a DirectX 12 GPU (Windows x64 only). The first time, the app downloads the DirectML build of ONNX Runtime (~15 MB, from Microsoft's NuGet packages), and you need to restart the app. The setting is saved in `~/.stt-tts/settings.properties`. Unsupported graphs fall back to CPU with a logged warning.

- On an RTX 3050 laptop GPU, a warm clone takes about 4 s instead of 12 s. Audio decoding drops from 10 s to 2 s.
- For Chatterbox, only the decoder is accelerated. DirectML can't run the language model's rotary GroupQueryAttention, so the language model stays on the CPU (about 1.6 s per sentence). The speech engines other than Chatterbox remain on CPU.
- For Chatterbox in GPU mode, text is split into ~70-character chunks and the reference is limited to 6 s. This keeps each decode under 250 tokens; longer sequences can trip the Windows GPU watchdog (TDR).
- The high-performance (discrete) GPU is chosen automatically. On hybrid laptops the integrated GPU is slower than the CPU.
- Loading Chatterbox still takes about 1.5 minutes either way. The decoder graph has ~24k nodes, so session creation dominates the load time.
- CUDA isn't used because it needs a 486 MB ONNX Runtime jar plus a system-wide CUDA 12 and cuDNN 9 installation.

## Configuration

### Local MCP server

The **MCP** toggle beside **GPU (DirectML)** starts a [Streamable HTTP MCP](https://modelcontextprotocol.io/specification/2025-11-25/basic/transports) endpoint at `http://127.0.0.1:8765/mcp`. It is **off on every startup**, binds only to IPv4 loopback (never `0.0.0.0`), and stops on toggle-off or application exit. Hiding the window in the tray does not stop it.

The tray context menu also provides **Settings...** (restores the window and opens settings) and a checked **MCP enabled** toggle. The tray and status-bar controls stay synchronized, including when server startup fails.

Click **Settings** beside the MCP toggle to open the settings window. Select the default STT, TTS, music and sound-effects models, enter a localhost port (1-65535), and **Save**. The window also shows the endpoint with **Copy URL**, and a masked bearer token with **Copy token** and **Generate token**. Cancel discards all edits; invalid input or a failed save leaves the window open with an error.

Stop MCP before editing; while it is running, settings are read-only but connection details can still be copied. Saved changes apply on the next MCP activation, **without restarting the app**, and never start the server automatically. Token generation is staged until Save; clearing the token creates a fresh one on next activation. Update your MCP client's token after changing it, keep it private, and clear the clipboard after copying.

The window persists these options in `~/.stt-tts/settings.properties` (which can still be edited manually while the app is closed):

```properties
mcp.port=8765
mcp.stt.model=whistle
mcp.tts.model=kokoro
mcp.music.model=musicgen-small
mcp.sfx.model=stable-audio-3-sfx
```

Kokoro is the default fast CPU TTS engine. TTS can also default to `supertonic`, `pocket` or `piper`; music can default to `stable-audio-3-music`. The `list_models` tool provides the exact available IDs, voice/language codes and generation license links. Individual tool calls can override the configured default with `model`.

First activation saves a random **`mcp.token`** in that settings file. Configure the MCP client's HTTP URL above and an **`Authorization: Bearer <mcp.token>`** header. Keep the token/settings file private; any local process possessing it can invoke the tools. Requests with foreign `Host` or `Origin` headers are rejected; no CORS access is granted to other websites. The host is deliberately not configurable.

| Tool | Inputs | Output |
| --- | --- | --- |
| `list_models` | None | Model IDs, configured defaults, voices/languages and generation licenses |
| `transcribe` | `audio_base64` (RIFF WAV), optional `model`, `language` (`auto`), `word_timestamps` (`false`) | Text, language, elapsed seconds and word timings |
| `synthesize` | `text`, optional `model`, `voice`, `language`, `speed` (`1`) | MCP audio content: base64 `audio/wav`, plus sample rate/duration metadata |
| `generate_music` | `prompt`, `accept_license: true`, optional `model`, `duration_seconds` (`5`), `seed` (`42`) | WAV audio content and metadata |
| `generate_sound_effects` | Same as music; default Stable Audio 3 Small SFX | WAV audio content and metadata |

Tool arguments are forgiving so that LLM clients do not fail on optional settings:

- Omitted, empty or placeholder values (`default`, `auto`, `none`, `null`, `any`, ...) for `model`, `voice`, `language`, `speed`, `duration_seconds`, `seed` and `word_timestamps` use the defaults (configured model, model's first voice, voice language or STT auto-detect).
- Unknown models/voices/languages, a model of the wrong kind, and unparsable numbers also fall back to defaults. Out-of-range `speed`/`duration_seconds` are clamped. A voice whose language conflicts with `language` wins.
- Models, voices and languages match case-insensitively by ID or name (`KOKORO`, `en-US` → `en`). Booleans and numbers may be strings (`"true"`, `"yes"`, `"3s"`). camelCase keys and common aliases (`input` → `text`, `audio` → `audio_base64`, `duration` → `duration_seconds`) are accepted; other unknown arguments are ignored. `audio_base64` may be a `data:` URI or wrapped/URL-safe base64, and `arguments` may be `null` or a JSON string.
- Each fallback is reported in the result's `structuredContent.warnings`.
- Calls fail only on essential input: a missing/blank `text`, `prompt` or `audio_base64`, text over 2,000 characters, invalid WAV audio, missing license consent, or a needed download without `allow_download`.

Missing models, voices and language bundles are **not downloaded without consent**: install them in the desktop app, or pass `allow_download: true` with the call. For generation, first read the license links returned by `list_models`, then explicitly pass `accept_license: true` for each call. This does not remove MusicGen's non-commercial restrictions or Stable Audio's Community License/Gemma obligations. **Powered by Stability AI.**

Audio input is limited to 8 MiB, 120 seconds and 8-192 kHz; HTTP request bodies to 12 MiB. Speech text/prompts are limited to 2,000 characters, speech speed to 0.5-2, and generation duration to 1-10 seconds. No filesystem paths, remote audio URLs or automatic playback are exposed. Audio stays on the local machine.

Desktop and MCP inference share one worker and the status/progress bar. Concurrent model jobs fail with a clear busy error rather than loading competing models. **Cancel** in the app cancels the current job; stopping MCP cancels its own active job. Native calls may need to finish before cancellation releases the worker. Set generous client timeouts for initial model downloads/loading and music generation (which can take minutes).

The transport is stateless, returning JSON to POST requests, with `initialize`, `ping`, `tools/list`, `tools/call` and notification acceptance. Clients must send `Content-Type: application/json`, `Accept: application/json, text/event-stream` and, after initialization, the negotiated `MCP-Protocol-Version` (`2025-11-25`, `2025-06-18` or `2025-03-26`). GET/SSE and DELETE return 405; there are no sessions, server-initiated messages, resumable streams or MCP task/cancellation-notification support. MCP support uses the JDK HTTP server and existing Jackson dependency, with no extra installs.

### Runtime properties

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
mvn test -Dtest=MusicGenIntegrationTest,StableAudioIntegrationTest -Dstttts.integration=true
mvn test -Dtest=McpModelsIntegrationTest -Dstttts.integration=true  # real speech/music/SFX through HTTP MCP
mvn test -Dtest=GenerationUiTest -Dstttts.ui=true                   # FXML, settings, MCP toggle, shared worker; desktop required
```

The integration test downloads every model. It synthesizes speech with Kokoro, Supertonic, Pocket TTS, Piper and Chatterbox (plus a Pocket clone), then checks that Whistle transcribes it back correctly. The Chatterbox test runs twice to measure the warm (already loaded) timing.

Audio-generation integration tests download their models and check sample rate, requested duration, finite non-silent PCM and (MusicGen) seed repeatability. They are structural tests, not a judgment of music quality or prompt adherence. Running them implies accepting the respective model licenses.

## Structure

The project setup follows [activity-dag](https://github.com/TalaatHarb/activity-dag):

- Guice `AppModule` and FXML controllers created through `injector::getInstance`.
- Single-instance lock and system tray.
- GitHub build and release workflows.

```
net.talaatharb.stttts
├── SttTtsApp / SttTtsLauncher / ApplicationTray / SingleInstance
├── config/                       AppModule (service lists, audio, model store), AppSettings (saved preferences)
├── service/                      speech and audio-generation interfaces + request/result records
├── stt/whistle/                  Whistle via the version-pinned Cactus runtime
├── tts/kokoro/                   Kokoro + pure Java English G2P
├── tts/supertonic/               Supertonic 3
├── tts/pocket/                   Pocket TTS engine (shared with cloning), SentencePiece, SafeTensors, NumPy
├── tts/piper/                    Piper + misaki-to-espeak phoneme mapping
├── cloning/pocket/               Pocket TTS voice cloning
├── cloning/chatterbox/           Chatterbox + pure Java BPE tokenizer, hybrid CPU/GPU decoder
├── generation/musicgen/          MusicGen autoregressive sampling and EnCodec decoding
├── generation/stableaudio/       Shared Stable Audio graphs, Gemma BPE, diffusion sampler, portable codec
├── mcp/                          Localhost HTTP MCP transport and shared audio tools
├── audio/                        WAV I/O, resampling, microphone recording, playback
├── model/                        Hugging Face downloader, model cache, ONNX helpers, DirectML runtime
└── ui/                           tab controllers, background task runner
```

## Roadmap (future work)

Unchecked items are future work. The research notes below come from checking the model repositories (November 2026).

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

### Additional sound effects and background music models

- [x] `AudioGenerationService` and a fourth tab with prompt, duration, seed, license acceptance, playback and WAV export.
- [x] MusicGen Small (ready ONNX; seeded autoregressive codebooks and EnCodec decoder).
- [x] Stable Audio 3 Small SFX / Music (official ONNX; shared T5Gemma/SAME-S, eight diffusion steps).
- [ ] Stereo output/export, longer tracks and additional generation controls.

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
    - **Stable Audio 3** small-sfx and small-music are now available in the app; see the implementation notes above.
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
- [ ] **ACE-Step** ([ACE-Step](https://huggingface.co/ACE-Step))
  - v1 (3.5B, Apache 2.0) is too heavy for CPU.
  - **v1.5 (MIT)** generates full songs with lyrics, at 48 kHz stereo. It has three parts:
    - A DiT; the turbo variants need ~8 steps.
    - An optional "5Hz" planner LM (0.6B or 4B).
    - A 1D VAE.
  - There is no official "Mini". The smallest setup is the turbo DiT plus the 0.6B LM.
  - Only community ONNX exports exist (for example [shreyask/ACE-Step-v1.5-ONNX](https://huggingface.co/shreyask/ACE-Step-v1.5-ONNX)). They are multi-GB: the fp16 DiT is ~3 GB and the q4 condition encoder ~331 MB. Check their licenses and quality.
  - Realistically it needs a GPU and 8 GB or more of RAM.

MusicGen Small and Stable Audio 3 are implemented; the remaining diffusion models still need custom exports.
