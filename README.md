# My OpenWhispr setup

How I run [OpenWhispr](https://github.com/OpenWhispr/openwhispr) for dictation: which models, how cleanup works, and the changes I made on top of the stock app. Bring your own OpenRouter key.

## Models

| Step | Model | Where it runs |
| --- | --- | --- |
| Speech to text | NVIDIA Parakeet TDT 0.6B v3 (desktop), v2 int8 (phone) | Locally. On Apple Silicon Macs through [FluidAudio](https://github.com/FluidInference/FluidAudio) on the Neural Engine; on Linux through OpenWhispr's bundled sherpa-onnx engine; on Android through sherpa-onnx in the keyboard. |
| Cleanup | Claude Haiku 5.5 (`anthropic/claude-haiku-5.5`) | OpenRouter, zero data retention, reasoning off |
| Voice commands / assistant | Claude Haiku 5.5 | OpenRouter |

Why these: Parakeet on the Neural Engine transcribes about 80x faster than real time and was more accurate on my recordings than the CPU build and Whisper large-v3-turbo. Haiku 5.5 was the best of six cleanup models I tested on 94 real transcripts for speed, accuracy and cost. A cleanup costs a fraction of a cent.

## Cleanup

The cleanup prompt is in [`prompts/cleanup.txt`](prompts/cleanup.txt). Paste it into OpenWhispr's custom cleanup prompt. It:

- writes the punctuation itself (the recognizer gives little or none), putting sentence breaks where the speaker meant them;
- deletes fillers (um, uh, you know, and "like" when it is only filler) but keeps phrases like "okay, so basically" that the speaker chose to say, plus drops false starts and self-corrections ("Friday, no wait, Thursday" → "Thursday"), but keeps the speaker's wording;
- never answers or carries out a request that appears in the dictation;
- turns spoken numbers into digits ("five point five" → "5.5", "ten x" → "10x"), because the recognizer always spells them out;
- breaks longer dictations into paragraphs where the topic changes, and only makes lists when items are actually listed;
- treats "transcription instructions … resume dictation" as editing commands for that recording;
- converts spoken punctuation and "<thing> emoji".

The other settings are in [`settings/openwhispr-settings.json`](settings/openwhispr-settings.json).

## How this differs from stock OpenWhispr

Patches against OpenWhispr 1.9.2; details and code in [`patches/`](patches/README.md).

**Speech**
- Parakeet runs on the Mac's Neural Engine through FluidAudio (about 80x faster than real time); the stock CPU engine is only the fallback.
- Recordings of 30 seconds or more have long pauses removed before decoding, so Parakeet can't invent words in them.
- No more cutting every 15 seconds: clips under 30 seconds are decoded whole, longer ones are cut in natural pauses.

**Cleanup**
- Claude Haiku 5.5 through OpenRouter with zero data retention, the fastest provider and reasoning off. The long prompt is cached, about 10x cheaper per cleanup.
- A custom cleanup prompt: keeps your wording, writes numbers as digits, adds paragraphs to longer dictations, makes lists only when you list things, and supports spoken editing commands.
- Spoken numbers are converted to digits before Haiku sees the text, since the model alone misses some.
- An identical transcript reuses its earlier cleanup instantly.
- Leaked reasoning (`<think>` tags, stray code fences) is stripped.

**Editing and dictionary**
- Select text and speak an instruction ("make this all caps") to edit it instead of replacing it. Where you can't type, the result shows in a small popup.
- Selection edits come back wrapped in tags, so the model's commentary is never pasted.
- Auto-learned dictionary is stricter: it waits until you finish editing, saves names without "'s", and Haiku decides whether a word belongs, so common and half-typed words stay out.

**Reliability and controls**
- On a busy computer the old clipboard is restored later, so it never pastes the previous clipboard instead of your dictation.
- Left Option works as the dictation key: tap to start and stop, hold to talk.
- The floating icon only shows while you're dictating.

**Phone**
- A custom HeliBoard-based keyboard instead of OpenWhispr's mobile app: on-device Parakeet, the same Haiku cleanup, recording that survives app switches and rotation, voice editing of selected text, swipe typing, and nothing saved from private fields. See [`android/`](android/README.md).

## Mac extras

- [`mac/fluid-asr`](mac/fluid-asr/README.md): local Parakeet service on the Neural Engine, with speech detection so long pauses don't produce invented words.
- [`mac/left-option`](mac/left-option/README.md): use Left Option as the dictation key: tap to start and stop, hold to talk.

## Android keyboard

The keyboard I use on my phone is in [`android/`](android/README.md): a voice-first fork of HeliBoard with on-device Parakeet, the same Haiku cleanup, recording that survives switching apps, and voice editing of selected text. Build it, then run `android/configure.sh` with your OpenRouter key.

## License

`android/keyboard` is a HeliBoard fork and stays under GPL-3.0 (its `LICENSE`). Everything else here is MIT.
