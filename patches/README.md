# App patches

Against OpenWhispr 1.9.2. These describe each change and where it hooks in; the snippets are readable versions of what runs.

## OpenRouter request options

In the reasoning service, wherever the OpenRouter chat request body is built:

```js
body.provider = { zdr: true, sort: "latency", ...(body.provider || {}) };
body.reasoning ??= { enabled: false };
// Cache the long cleanup prompt (Anthropic models): ~10x cheaper per call.
if (model.startsWith("anthropic/") && body.messages[0]?.role === "system"
    && typeof body.messages[0].content === "string" && body.messages[0].content.length > 4000) {
  body.messages[0] = { role: "system", content: [
    { type: "text", text: body.messages[0].content, cache_control: { type: "ephemeral" } },
  ] };
}
```

Model output also goes through a stripper that removes `<think>…</think>`, `<|think_end|>` leftovers and unbalanced code fences.

## Spoken numbers

[`spoken-numbers.js`](spoken-numbers.js) runs on the raw transcript before it is wrapped for the cleanup model. It converts only numbers that parse cleanly ("twenty x" → "20x", "five point five" → "5.5", "six hundred billion" → "600 billion", "twenty first" → "21st", "twenty twenty six" → "2026", "at three thirty" → "3:30", "T three" → "T3", "nine B model" → "9B"). A run that doesn't parse stays as words, never half converted. It leaves "one of them" and "one day" alone.

## Spoken emoji

[`spoken-emoji.js`](spoken-emoji.js) and [`emoji-table.tsv`](emoji-table.tsv) (CLDR names and keywords from emojibase-data 16.0.3) run after the number pass:

- "&lt;description&gt; emoji" becomes the emoji ("blushing smiling face emoji" → 😊, "three fire emojis" → 🔥🔥🔥).
- Talk about emoji ("the voice emoji doesn't work", "add emojis") stays as words.
- A request it can't resolve is marked `⟦emoji: …⟧`, and the prompt tells the model to pick the emoji.
- On the output it removes description words the model left next to an emoji, and turns any marker that's left back into words.
- A take that is only emoji after conversion skips the model; it used to describe the emoji instead.
- In selection editing, "just put the emoji" is answered from the table without the model.

## Answer guard

[`answer-guard.js`](answer-guard.js) rejects cleanup output that answers or refuses the dictation, or adds a lot of new words. When that happens, the pre-processed transcript is pasted instead.

## Repeat cache

In `processText`, for plain cleanup only (no custom system prompt), transcripts up to 400 characters:

```js
const key = hash(`${provider}|${model}|${systemPrompt}|${text}`); // prompt, dictionary and model are part of the key
const hit = cache.get(key);                                     // localStorage, 2,000 entries, least recently used evicted
if (hit != null) return hit;
const out = await provider.call(...);
if (out.trim()) cache.set(key, out);
```

## Selection editing

When dictation starts with text selected, the selection and the spoken instruction go to the model in selection-edit mode. The model must answer as `<replacement>…</replacement>`, and anything outside the tags is dropped, so an explanation can never be pasted. If the selected text isn't editable (a web page), the answer opens in the assistant panel instead. On macOS, editability is checked by [`mac/ax-editable`](../mac/ax-editable/README.md), because the stock probe reports every field with a selection as read-only.

## Dictionary auto-learn

After a dictation is pasted and you edit it, OpenWhispr can learn words from your correction. Changes:

- wait 6 seconds after the last edit (not 1.5) so half-typed words aren't learned;
- strip possessives ("Jordan's" → "Jordan");
- ask Haiku (zero data retention) with [`../prompts/dictionary-judge.txt`](../prompts/dictionary-judge.txt) which candidates qualify. Only words that appear in your edited text are accepted, and nothing is added if the call fails.

## Paste reliability (macOS)

OpenWhispr pastes by putting the transcript on the clipboard, pressing Cmd+V and then restoring your old clipboard. On a busy machine the restore could run before the target app read the clipboard, so the previous clipboard was pasted. The restore delay now scales with system load (1.5 s up to 8 s). A new paste cancels a pending restore and keeps the original clipboard.

## Long recordings

The bundled CPU engine used to cut audio into fixed 15-second pieces, which could split a word. Clips up to 30 seconds are now decoded whole. Longer ones are cut at the quietest 100 ms in the 6 seconds before each 30-second mark.

## Local speech service (macOS)

The Parakeet server first sends audio to the FluidAudio service on `127.0.0.1:6017` (see `mac/fluid-asr`) and falls back to the bundled sherpa server if it fails or times out.
