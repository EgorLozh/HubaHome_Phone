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
  - send `wakeword_detected`,
  - stream microphone chunks as `audio_chunk` (WAV/base64),
  - send `final_transcript` (manual text or empty trigger after voice capture).
- Response handling:
  - `assistant_text` in UI,
  - `assistant_audio_chunk` playback via `AudioTrack`,
  - server/client error rendering.
- Real wakeword detector (SpeechRecognizer-based keyword spotting for `хуба` / `huba`).
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
