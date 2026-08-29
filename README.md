# AKUJI Android Foundation

This branch is the first permanent, phone-native AKUJI shell. It replaces the old prompt-only simulations with an Android project whose behavior is visible in code and survives between sessions.

## Working in this first build

- AKUJI's locked full-body visual reference is stored inside the app.
- AKUJI has a full-screen body-first interface, not a chatbot box.
- Android speech recognition accepts one spoken request at a time.
- Android text-to-speech gives the body a phone-native voice.
- The avatar visibly breathes and moves from Android's real TTS utterance and word-range events while speaking.
- `remember ...` writes private local memory to the phone.
- `what do you remember?` reads recent local memory aloud.
- Each completed exchange is written to a private on-device transcript.
- Large imported archives are searched locally and only relevant passages are
  given to Gemma, preventing the 2,048-token overflow caused by full-file prompts.
- Every request starts with a fresh bounded model conversation so old chat turns
  cannot silently fill the model context window.
- The screen stays awake while AKUJI is open.
- No API key, Cloud Shell, Firebase setup, or paid service is required for this foundation.

## Connected local brain

Gemma 4 E2B runs through Google's LiteRT-LM Android runtime after AKUJI downloads her own private model copy. Android does not let AKUJI read AI Edge Gallery's private app storage, so this copy is required. `BrainEngine` remains the stable connector point for other compatible local runtimes later.

The approved source image is a single flattened reference image, so this version uses speech-event-driven body movement rather than claiming fake lip-sync. True facial movement still requires an identity-approved rigged avatar or a verified talking-head engine.

## Source of truth

- DEFF ROW AKUJI Production Bible
- `AKUJI_Visual_Bible_Full_Body.png` from the approved Drive production-assets folder
- Package: `com.deffrow.akuji`
- Minimum Android: 12 (API 31)
- Target Android: API 36

## Build

GitHub Actions builds an installable debug APK on every push to `akuji-android-foundation`. The workflow uses Java 17, Gradle 8.13, Android Gradle Plugin 8.13.2, Kotlin 2.4.10, and the Compose 2026.08 BOM.

No credentials belong in this repository.
