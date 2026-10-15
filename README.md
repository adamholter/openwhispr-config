# My OpenWhispr setup

How I run [OpenWhispr](https://github.com/OpenWhispr/openwhispr) for dictation: which models, how cleanup works, and the changes I made on top of the stock app. Bring your own OpenRouter key.

## Models

| Step | Model | Where it runs |
| --- | --- | --- |
| Speech to text | NVIDIA Parakeet TDT 0.6B v3 | Locally. On Apple Silicon Macs through [FluidAudio](https://github.com/FluidInference/FluidAudio) on the Neural Engine; on Linux and Android through the bundled sherpa-onnx build. |
| Cleanup | Claude Haiku 5.5 (`anthropic/claude-haiku-5.5`) | OpenRouter, zero data retention, reasoning off |
| Voice commands / assistant | Claude Haiku 5.5 | OpenRouter |

Why these: Parakeet on the Neural Engine transcribes about 80x faster than real time and was more accurate on my recordings than the CPU build and Whisper large-v3-turbo. Haiku 5.5 was the best of six cleanup models I tested on 94 real transcripts for speed, accuracy and cost. A cleanup costs a fraction of a cent.

## Cleanup

The cleanup prompt is in [`prompts/cleanup.txt`](prompts/cleanup.txt). Paste it into OpenWhispr's custom cleanup prompt. It:

- removes fillers, false starts and self-corrections ("Friday, no wait, Thursday" → "Thursday") but keeps the speaker's wording;
- turns spoken numbers into digits ("five point five" → "5.5", "ten x" → "10x"), because the recognizer always spells them out;
- breaks longer dictations into paragraphs where the topic changes, and only makes lists when items are actually listed;
- treats "transcription instructions … resume dictation" as editing commands for that recording;
- converts spoken punctuation and "<thing> emoji".

The other settings are in [`settings/openwhispr-settings.json`](settings/openwhispr-settings.json).

## Changes on top of OpenWhispr

These are patches to the app (version 1.9.2), described in [`patches/`](patches/README.md):

- **Faster, private OpenRouter calls.** Every cleanup request asks for zero data retention, the lowest-latency provider and no reasoning, and caches the long system prompt (about 10x cheaper per cleanup).
- **Spoken-number pre-pass.** A small converter turns "twenty x" into "20x" before the model sees the text, since the model alone misses sentence-initial numbers.
- **Repeat cache.** If a transcript is identical to an earlier one, the earlier cleanup is reused instantly.
- **Selection editing.** Select text, dictate an instruction ("make this all caps"), and the selection is edited instead of replaced. In places where you can't type, the result shows in a small popup.
- **Smarter auto-learned dictionary.** After you correct a dictation, Haiku decides which words belong in the dictionary (names, product names, jargon), using the base form. Ordinary words and half-typed edits are rejected. The judge prompt is in [`prompts/dictionary-judge.txt`](prompts/dictionary-judge.txt).
- **Reliable paste.** The clipboard is restored later when the computer is busy, so a slow machine never pastes the previous clipboard instead of the transcript.
- **Leaked reasoning tags stripped** (`<think>`, stray code fences).
- **Floating icon only while dictating.**

## Mac extras

- [`mac/fluid-asr`](mac/fluid-asr/README.md): local Parakeet service on the Neural Engine, with speech detection so long pauses don't produce invented words.
- [`mac/left-option`](mac/left-option/README.md): use Left Option as the dictation key: tap to start and stop, hold to talk.

## Android

I also use a voice-first Android keyboard built on HeliBoard with the same models and cleanup prompt; see [`android/README.md`](android/README.md).
