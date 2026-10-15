# Android keyboard

The keyboard I use on my phone: a voice-first fork of [HeliBoard](https://github.com/Helium314/HeliBoard) (GPL-3.0, like HeliBoard). Source in [`keyboard/`](keyboard).

## What it does

- Voice panel in place of the keys: tap the mic, talk, tap again. The text is cleaned up and typed into the field.
- Speech runs on the phone: NVIDIA Parakeet TDT 0.6B v2 (English, int8) through sherpa-onnx. Cleanup uses Claude Haiku 5.5 through OpenRouter (zero data retention) with the same number and paragraph rules as the desktop.
- A mic key in the bottom row of the typing keyboard gets you back to voice. Swipe typing works in the typing mode (see below).
- Recording keeps going if you switch apps, collapse the keyboard or rotate the phone (a microphone foreground service). Reopen the keyboard and finish the same take.
- Select text and dictate an instruction ("make this all caps") to edit the selection.
- Spoken numbers become digits before cleanup ("twenty x" → "20x").
- Private (incognito) fields: nothing is saved or synced.
- Dictation history on the phone, with retry for takes that failed. Optional sync of that history to your own server.

## Build

```sh
cd keyboard
./gradlew assembleRelease        # if packageRelease fails once, run it again
adb install -r app/build/outputs/apk/release/HeliBoard_*-release.apk
```

Then enable the keyboard in Android's keyboard settings.

**Swipe typing** needs Google's gesture library, which can't be redistributed. Put `libjni_latinimegoogle.so` (arm64) in `app/src/main/jniLibs/arm64-v8a/` before building; HeliBoard's wiki explains where to get it. Without it everything else works.

## Configure

Open the keyboard's History screen and download the Parakeet model (about 630 MB), or let the setup script do it. Then give it your OpenRouter key:

```sh
OPENROUTER_API_KEY=sk-or-... ./configure.sh <adb-serial>
```

[`configure.sh`](configure.sh) pushes the settings over adb (the import only accepts them from the adb shell) and starts the model download if needed.
