# AKUJI Android

AKUJI is Peachez's private, persistent, avatar-first AI for the DEFF ROW ecosystem. This repository is no longer a disposable Flutter counter demo.

## What this build establishes

- **Body first:** the approved AKUJI full-body asset is embedded in the app and remains visible in offline, listening, thinking, speaking, and error states.
- **Local model bridge:** `.litertlm`, `.task`, `.bin`, and `.tflite` model files can be selected on Android. The selected file is copied into AKUJI's private application storage before Flutter Gemma installs it.
- **On-device inference:** LiteRT-LM and MediaPipe engines run Gemma locally. No cloud inference service is required.
- **Persistent memory:** conversation turns are stored in a private SQLite database and restored into AKUJI's system context after relaunch.
- **Phone voice loop:** Android on-device speech recognition feeds the local model, and a US English device TTS voice reads the answer. This is explicitly an interim voice bridge, not AKUJI's locked canonical voice.
- **Avatar-first interface:** AKUJI's body is the primary interface. Keyboard input is a fallback sheet, not a permanent chatbot box.

## Honest boundaries of this milestone

- Google AI Edge Gallery does not currently expose its installed Gemma model to another Android app. AKUJI therefore maintains her own private model copy.
- The approved still image has presence states and a speaking glow, but no fake mouth animation. True lip sync requires an identity-approved rigged asset with visemes.
- Device TTS is temporary. A canonical AKUJI voice model will replace it without changing the local Gemma or memory bridge.
- Tool/action execution is the next layer. Actions must be allow-listed and confirm destructive or external effects before they are enabled.

## Model compatibility

| File | Engine | Typical families |
|---|---|---|
| `.litertlm` | LiteRT-LM | Gemma 4, Gemma 3/3n conversions |
| `.task` | MediaPipe | Gemma 3, Gemma 3n |
| `.bin` | MediaPipe | compatible manually templated models |

Gemma 4 filenames containing `gemma4` or `gemma-4` use `ModelType.gemma4`; other Gemma files use `ModelType.gemmaIt`.

## Privacy and permanence

- Model: private application-support storage
- Memory: private SQLite database in application documents storage
- Avatar and identity instructions: bundled app assets
- Logs: Flutter Gemma release logging disabled
- Android backup: disabled to avoid copying private memory into consumer cloud backups

## Verification

Every pull request to `main` runs analysis, tests, and an arm64 debug APK build. The workflow publishes the verified APK as a temporary GitHub Actions artifact for private phone testing.
