# AKUJI Android Foundation

This branch is the first permanent, phone-native AKUJI shell. It replaces the old prompt-only simulations with an Android project whose behavior is visible in code and survives between sessions.

## Working in this first build

- AKUJI's locked full-body visual reference is stored inside the app.
- AKUJI has a full-screen body-first interface, not a chatbot box.
- Android speech recognition accepts one spoken request at a time.
- Android text-to-speech gives the body a phone-native voice.
- The avatar visibly breathes and reacts while speaking.
- `remember ...` writes private local memory to the phone.
- `what do you remember?` reads recent local memory aloud.
- The screen stays awake while AKUJI is open.
- No API key, Cloud Shell, Firebase setup, or paid service is required for this foundation.

## Not falsely claimed

Gemma, Qwen, and DeepSeek are **not connected yet**. `BrainEngine` is the stable connector point for those model runtimes. The first build uses `LocalAkujiCore`, an honest offline command core, so the avatar/voice/memory pipeline can be built and tested without pretending that a model has been installed.

The approved source image is a single flattened reference image, so this version uses breathing and speaking-state animation rather than fake lip-sync. True facial movement requires a rigged avatar or a verified talking-head engine.

## Source of truth

- DEFF ROW AKUJI Production Bible
- `AKUJI_Visual_Bible_Full_Body.png` from the approved Drive production-assets folder
- Package: `com.deffrow.akuji`
- Minimum Android: 8.0 (API 26)
- Target Android: API 36

## Build

GitHub Actions builds an installable debug APK on every push to `akuji-android-foundation`. The workflow uses Java 17, Gradle 8.13, Android Gradle Plugin 8.13.2, Kotlin 2.4.10, and the Compose 2026.08 BOM.

No credentials belong in this repository.
