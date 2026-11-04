/* owModelNames: source in speed-patch/owModelNames.js */
// Speech-to-text mishears AI model names ("GPT 6.1 soul", "Clawed Code", "Kimmy K3"), and the
// cleanup model keeps them: Sol stayed wrong in 66% of mentions. Run on the raw transcript
// before cleanup so the model sees the right name. Rules only fire in model-talk contexts, so an
// ordinary "soul" or "codecs" in audio talk is left alone. Kotlin mirror: ModelNames.kt (same cases).
function owModelNames(text) {
  const NUM = '(?:zero|one|two|three|four|five|six|seven|eight|nine|ten)';
  const VER = `(?:\\d+(?:\\.\\d+)*|${NUM}(?:\\s+point\\s+(?:${NUM}|\\d+))?)`;
  const GPT = `\\bg\\.?[bp]\\.?[td][- ]?(?:${VER}[- ]*)?`;
  const SOL_BAD = '(?:soul|seoul|sole|sol)';
  const TERRA_BAD = '(?:terror|tera|terra)';
  const LUNA_BAD = '(?:llama|luna)';
  const TIER = '(?:astra|sol|terra|luna)';
  const SEP = '(?:\\s*,\\s*(?:and\\s+|or\\s+)?|\\s+(?:and|or|vs\\.?|versus|slash)\\s+|\\s*/\\s*)';
  let t = text;
  // Skipping already-correct matches keeps the function idempotent.
  const fix = (re, name) => { t = t.replace(re, (...a) => { const g = a.slice(1, -2); return (g[0] || '') + name + (g[2] || ''); }); };
  // GPT [version] <mishearing>  ("GPT six point one soul", "GPT-6.1 tera")
  fix(new RegExp(`(${GPT}[,]?\\s*)${SOL_BAD}\\b()`, 'gi'), 'Sol');
  fix(new RegExp(`(${GPT}[,]?\\s*)${TERRA_BAD}\\b()`, 'gi'), 'Terra');
  fix(new RegExp(`(${GPT}[,]?\\s*)${LUNA_BAD}\\b()`, 'gi'), 'Luna');
  // Tier lists: "Astra, soul and Terra" - a mishearing next to a correctly heard sibling.
  for (let pass = 0; pass < 3; pass++) {
    fix(new RegExp(`(\\b${TIER}${SEP})(?:soul|seoul)\\b()`, 'gi'), 'Sol');
    fix(new RegExp(`\\b(?:soul|seoul)\\b(?=${SEP}${TIER}\\b)`, 'gi'), 'Sol');
    fix(new RegExp(`(\\b${TIER}${SEP})(?:terror|tera)\\b()`, 'gi'), 'Terra');
    fix(new RegExp(`\\b(?:terror|tera)\\b(?=${SEP}${TIER}\\b)`, 'gi'), 'Terra');
    fix(new RegExp(`(\\b${TIER}${SEP})llama\\b()`, 'gi'), 'Luna');
    fix(new RegExp(`\\bllama\\b(?=${SEP}${TIER}\\b)`, 'gi'), 'Luna');
  }
  // Bare version prefix: "six point one soul", "6.1 Soul", "six soul".
  fix(/(\b(?:six\s+point\s+(?:one|oh\s+one)|6\.1|six|5\.6|five\s+point\s+six|point\s+(?:one|oh))\s+)(?:soul|seoul)\b()/gi, 'Sol');
  // Next to another model name in the same sentence ("Opus being on top, Seoul having ...",
  // "worse than Luna, let alone Soul"). The noun "soul" keeps its determiner/possessive or "of/document".
  {
    const MODEL = '(?:opus|sonnet|haiku|gemini|claude|codex|astra|terra|luna|gpt|grok|kimi|fable|deepseek|qwen|mythos)';
    const NOTSENT = '[^.!?\\n]';
    const guard = (m, off, str) => {
      const before = str.slice(Math.max(0, off - 40), off), after = str.slice(off + m.length, off + m.length + 14);
      if (/\b(?:a|an|the|his|her|my|your|their|its|our|no|of|one's|human)\s*$/i.test(before)) return m;
      if (/^\s*(?:document|documents|doc|of)\b/i.test(after)) return m;
      return 'Sol';
    };
    t = t.replace(new RegExp(`\\b${MODEL}\\b${NOTSENT}{0,40}?\\b(?:soul|seoul)\\b`, 'gi'), (m) => m.replace(/\b(?:soul|seoul)\b$/i, (w) => guard(w, m.length - w.length, m)));
    t = t.replace(new RegExp(`\\b(?:soul|seoul)\\b(?=${NOTSENT}{0,40}?\\b${MODEL}\\b)`, 'gi'), (w, off, str) => guard(w, off, str));
    // Capitalized mid-sentence "Soul" (the recognizer treats it as a name): "tell Soul to", "given to Soul".
    t = t.replace(/(?<=[a-z,;]\s)Soul\b(?!\s+(?:document|documents|doc|of)\b)/g, (w, off, str) => guard(w, off, str));
  }
  // "soul on extra high", "soul light": reasoning-effort and tier phrasing never used for the noun.
  fix(/\b(?:soul|seoul)\b(?=\s+(?:on\s+)?(?:extra\s+high|x\s?high|max|high|medium|low|light|mini)\b)/gi, 'Sol');
  // Codex: "codecs" is the product in Adam's speech; leave real media-codec talk alone.
  t = t.replace(/\bcodecs?\b/gi, (m, off, str) => /\b(?:audio|video|image|media|lossless|encod\w*|decod\w*|h\.?264|h\.?265|av1|mp3|stream\w*|bitrate|compress\w*|podcast|bandwidth|playback)\b/i.test(str.slice(Math.max(0, off - 60), off + m.length + 60)) ? m : 'Codex');
  // Claude: clawed / claud / clog+model / cloud+model / claw+limit. The verb "clawed the couch" stays.
  t = t.replace(/\bclawed\b(?!\s+(?:the|a|an|at|through|its|his|her|their|my|your|our|me|him|them|up|out|off|on|back|away|into|over)\b)/gi, 'Claude');
  t = t.replace(/\bclaw(?=\s+(?:limits?|usage|subscription|code|models?|plan|max)\b)/gi, 'Claude');
  t = t.replace(/\bclaud\b/gi, 'Claude');
  t = t.replace(/\b(?:clog|cloud)(?=\s+(?:code|haiku|opus|sonnet|desktop|cowork|design|md|max)\b)/gi, 'Claude');
  // Kimi, Grok, DeepSeek
  t = t.replace(/(?<!\b(?:friend|named|called|aunt|aunt|cousin|sister|girlfriend|wife|daughter|miss)\s)\bkimmy\b/gi, 'Kimi');
  t = t.replace(/\bgrock\b/gi, 'Grok').replace(/\b(?:groc|grog)(?=\s+(?:\d|bot\b|four|three|five))/gi, 'Grok');
  t = t.replace(/\bdeep\s+seek(?!ing)\b/gi, 'DeepSeek');
  return t;
}
if (typeof module !== 'undefined') module.exports = { owModelNames };
