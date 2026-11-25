// Chat abbreviations: Adam writes idk, tbh, btw, iirc... in lowercase, even at the start of a sentence
// (asked Nov 17). The recognizer emits them in capitals ("TBH") and the cleanup model sometimes spells
// them out ("to be honest"). owChat lowercases them; owChatRestore puts back an abbreviation that the
// cleanup expanded. ASAP, ETA, DM and NP stay as they are. Kotlin mirror: ChatAbbrev.kt.
const OW_CHAT = ['idk', 'btw', 'iirc', 'afaik', 'tbh', 'tbf', 'imo', 'imho', 'lol', 'lmao', 'lmk', 'omg', 'smh', 'ngl',
  'fwiw', 'irl', 'jk', 'nvm', 'ikr', 'icymi', 'fyi', 'wym', 'hmu', 'ofc', 'tldr'];
const OW_CHAT_LONG = {
  idk: ["i don't know", 'i do not know', 'i dunno'], btw: ['by the way'],
  iirc: ['if i recall correctly', 'if i remember correctly', 'if i remember right'], afaik: ['as far as i know'],
  tbh: ['to be honest'], tbf: ['to be fair'], imo: ['in my opinion'], imho: ['in my humble opinion'],
  lmk: ['let me know'], fwiw: ["for what it's worth", 'for what it is worth'], ngl: ['not gonna lie', 'not going to lie'],
  smh: ['shaking my head'], jk: ['just kidding'], fyi: ['for your information'], nvm: ['never mind', 'nevermind'],
  omg: ['oh my god', 'oh my gosh'], lol: ['laughing out loud'], icymi: ['in case you missed it'], irl: ['in real life'],
};
function owChat(text) {
  if (typeof text !== 'string') return text;
  const re = new RegExp(`\\b(${OW_CHAT.join('|')}|tl;dr)\\b`, 'gi');
  return text.replace(re, (m, w, off, s) => {
    if (m === m.toLowerCase()) return m;
    if (/^imo$/i.test(w)) { // the International Math Olympiad keeps its capitals
      const before = s.slice(Math.max(0, off - 12), off), after = s.slice(off + m.length, off + m.length + 14);
      if (/\b(?:the|an|at|this|last|year's)\s+$/i.test(before) || /^[\s-]*(?:gold|silver|bronze|medal|problem|level|grader|question|competition|team|\d)/i.test(after)) return m;
    }
    return m.toLowerCase();
  });
}
// The raw transcript said "TBH" but the cleanup wrote "to be honest": restore the abbreviation, as many
// times as the raw had it and the expansion was not already in the raw.
function owChatRestore(raw, out) {
  if (typeof raw !== 'string' || typeof out !== 'string') return out;
  const low = raw.toLowerCase();
  const count = (s, re) => (s.match(re) || []).length;
  for (const [ab, longs] of Object.entries(OW_CHAT_LONG)) {
    let n = count(low, new RegExp(`\\b${ab}\\b`, 'g'));
    if (!n) continue;
    for (const l of longs) {
      const lre = new RegExp(`\\b${l.replace(/'/g, "['’]")}\\b`, 'gi');
      let extra = count(out, lre) - count(raw, lre);
      if (extra <= 0) continue;
      out = out.replace(lre, (m) => (extra-- > 0 && n-- > 0 ? ab : m));
      if (n <= 0) break;
    }
  }
  // and the reverse: the speaker said "to be honest" but the cleanup wrote "tbh"
  for (const [ab, longs] of Object.entries(OW_CHAT_LONG)) {
    const are = new RegExp(`\\b${ab}\\b`, 'gi');
    let extra = count(out, are) - count(raw, are);
    if (extra <= 0) continue;
    const said = longs.find((l) => new RegExp(`\\b${l.replace(/'/g, "['’]")}\\b`, 'i').test(raw));
    if (!said) continue;
    out = out.replace(are, (m, off, s) => {
      if (extra-- <= 0) return m;
      const start = off === 0 || /[.!?]\s+$/.test(s.slice(Math.max(0, off - 3), off));
      const l = said.replace(/\bi\b/g, 'I');
      return start ? l[0].toUpperCase() + l.slice(1) : l;
    });
  }
  return owChat(out);
}
if (typeof module !== 'undefined') module.exports = { owChat, owChatRestore };
