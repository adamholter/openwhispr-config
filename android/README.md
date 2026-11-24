# Android keyboard

The keyboard I use on my phone: a voice-first fork of [HeliBoard](https://github.com/Helium314/HeliBoard) (GPL-3.0, like HeliBoard). Source in [`keyboard/`](keyboard).

## What it does

**Voice**
- Voice panel in place of the keys: tap the mic, talk, tap again. The text is cleaned up and typed into the field.
- Speech runs on the phone: NVIDIA Parakeet Unified EN 0.6B (int8) through sherpa-onnx. On my recordings it made fewer word errors than Parakeet TDT v2 (6.2% vs 7.3%). The model stays loaded for 10 minutes after use, so the next take starts right away.
- Cleanup uses DeepSeek V4.1 Flash through OpenRouter (zero data retention, reasoning off) with the same prompt rules as the desktop. Output that answers the dictation instead of cleaning it is thrown away and the plain transcript is used.
- Before cleanup, the same fixers as the desktop run (the Kotlin ports produce identical output): spoken numbers become digits ("twenty x" → "20x"), "&lt;description&gt; emoji" becomes the emoji, misheard AI model names are fixed, "at grok" becomes "@grok", and spoken email addresses become real ones ("my email is jane doe at gmail dot com").
- Recording keeps going if you switch apps, collapse the keyboard or rotate the phone (a microphone foreground service). Reopen the keyboard and finish the same take.
- Select text and dictate an instruction ("make this all caps") to edit the selection.

**History**
- The history button on the toolbar opens your past dictations inside the keyboard. Tap one to insert it, or long-press for the raw transcript.
- Recordings that were cut off or cancelled are kept for 7 days. "Transcribe and insert" runs them again from the same list.
- History is hidden on the lock screen and in password and private fields. Private (incognito) fields save and sync nothing.
- Optional sync of that history to your own server.

**Typing**
- A mic key in the bottom row of the typing keyboard gets you back to voice. Swipe typing works in the typing mode (see below).
- One backspace after a swiped word deletes the whole word. Backspacing into the end of a word brings its suggestions back.
- A short toolbar: voice, history, clipboard, emoji, undo, redo, settings.
- Clipboard history kept for 30 days by default (or forever), shown as cards with when you copied each one.
- Emoji search, and an easy-to-read emoticon tab.
- A pure black theme by default.

## Build

```sh
cd keyboard
./gradlew assembleRelease        # if packageRelease fails once, run it again
adb install -r app/build/outputs/apk/release/HeliBoard_*-release.apk
```

Then enable the keyboard in Android's keyboard settings.

**Swipe typing** needs Google's gesture library, which can't be redistributed. Put `libjni_latinimegoogle.so` (arm64) in `app/src/main/jniLibs/arm64-v8a/` before building; HeliBoard's wiki explains where to get it. Without it everything else works.

## Configure

Open the keyboard's History screen and download the Parakeet model (about 660 MB), or let the setup script do it. Then give it your OpenRouter key:

```sh
OPENROUTER_API_KEY=sk-or-... ./configure.sh <adb-serial>
```

[`configure.sh`](configure.sh) pushes the settings over adb (the import only accepts them from the adb shell) and starts the model download if needed.
