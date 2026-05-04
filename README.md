# HubaHome Phone

Android-native MVP client for `HubaHome_Server`.

## Implemented now

- Kotlin + Compose + Hilt skeleton.
- UI-configurable connection settings:
  - `WS URL`
  - `API key`
  - persisted in `SharedPreferences`.
- `OkHttp` WebSocket client for `ws /v1/voice/session`:
  - `x-api-key` handshake,
  - reconnect with bounded backoff,
  - session status transitions.
- Voice flow:
  - local wakeword detection via `huba_ru_v3.onnx`,
  - local ready beep in app,
  - send `wakeword_detected`,
  - send one final utterance as `audio_chunk` (WAV/base64),
  - send empty `final_transcript` to trigger server-side STT.
- Response handling:
  - `assistant_text` in UI,
  - `assistant_audio_chunk` playback via `AudioTrack`,
  - server/client error rendering.
- Real wakeword detector backed by ONNX Runtime and openWakeWord-compatible preprocessing assets.
- Foreground service scaffold for lifecycle hardening.

## Configure server endpoint

Do this from app UI (no rebuild required):

- fill `WS URL`
- fill `API key`
- press `Save settings`

Default values are still provided at first launch and target local backend from emulator:

- `ws://10.0.2.2:8000/v1/voice/session`
- `dev-api-key`

## Server note

`HubaHome_Server` already supports API key in WebSocket handshake (`x-api-key` header or `api_key` query).

## Wakeword model assets

Wakeword runtime assets are bundled from the standalone lab project:

- `../HubaHome_Wakeword_Lab/models/huba_ru_v3.onnx`
- plus shared openWakeWord preprocessing models (`melspectrogram.onnx`, `embedding_model.onnx`) in app assets
