# Architecture

## Overview

```
Compose UI (app)  ->  ViewModels  ->  audio-engine (Kotlin API)  ->  JNI  ->  C++ DSP + Oboe
                         |
                         +-> Room (projects, recordings, profiles)  +  DataStore (settings, encrypted token)
                         +-> CloudAiClient interface -> BackendClient -> FastAPI -> VoiceProvider adapter
```

## Android

- **app** — Jetpack Compose UI, single activity, navigation with 7 destinations (Home, Studio, Live, AI Lab, Editor, Library, Settings). Package layout: `ui/<feature>`, `data` (Room, DataStore, SecureStore), `effects` (EffectCatalog), `ai` (CloudAiClient, BackendClient).
- **audio-engine** — independent of UI.
  - `cpp/dsp.h`: header-only effect chain (pitch/formant shift, ring mod, distortion, band-pass, 3-band EQ, echo, reverb, adaptive noise suppression, limiter with pre-limit clip detection). No allocation in `processBlock`; parameters change between blocks.
  - `cpp/audio_engine.cpp`: Oboe full-duplex streams, lock-free meters/parameters. `jni_bridge.cpp` exposes it to `NativeAudioEngine.kt`.
  - `OfflineProcessor`, `AudioOps` (cut, slice, mix, fade, varispeed, resample, peaks), `WavIO`, `AudioDecoder` (MediaCodec import), `AacEncoder` (M4A export), `PcmRecorder`, `PcmPlayer`.
- The same C++ chain is used for live and offline processing, so previews match output.
- Real-time rule: no allocation, locks, I/O or network in the audio callback.

## Backend (FastAPI)

- `api/routes.py` thin HTTP; `services/ai_service.py` consent check, credit charge/refund, size limits; `services/store.py` SQLite users, hashed tokens, credit ledger, profiles; `ai_providers/` replaceable `VoiceProvider`.
- Credits are charged only on the server and refunded if the provider fails.
- `NullProvider` is the default: it reports "not configured" and never returns fake audio.
- Billing validation is an interface (`PurchaseValidator`) with no real implementation yet.

## Extension points

- New AI provider: implement `VoiceProvider`, register in `build_provider`.
- New cloud client on Android: implement `CloudAiClient`.
- New effect: add a `VoiceEffect` in `EffectCatalog` mapping intensity to `EffectParams`.

## Tests

- `backend/tests` (pytest), `audio-engine/src/test/cpp/dsp_test.cpp` (host g++), `audio-engine/src/test/java` (JUnit for AudioOps). CI runs all three; see [STATUS.md](STATUS.md) for what has actually been executed.
