// Reject a cleanup output that answers, refuses or rewrites the dictation instead of cleaning it.
// Port of the phone's RewriteEngine.preservesContent + addsLittleNewContent. On rejection the app
// pastes the pre-processed transcript (numbers, names and emoji already fixed) instead.
function owAccepts(input, output) {
  if (/transcription instructions?/i.test(input)) return true; // instructions legitimately reshape the text
  const words = (s) => s.toLowerCase().split(/[^\p{L}\p{N}]+/u).filter(Boolean);
  const inW = words(input.replace(/⟦emoji:[^⟧]*⟧/g, ' ')), outW = words(output);
  if (!inW.length) return true;
  if (!outW.length) return false;
  const meta = /\b(?:the|this) transcript\b|\bnothing to clean\b|\bno (?:spoken|dictated) (?:words|content|text)\b|\bI(?:'m| am) (?:not able|unable) to\b|\bI can(?:'t|not) help\b|\bcould you (?:please )?(?:clarify|provide|share)\b/i;
  if (meta.test(output) && !meta.test(input)) return false;
  const relaxed = /\b(?:scratch that|actually|i mean|no wait|wait no|sorry|correction|delete that|never mind|nevermind|rather)\b/i.test(input);
  if (outW.length > inW.length * 2 + 12) return false;
  if (inW.length < 8) return true; // short takes: merged names and times ("five thirty" -> 5:30) look like dropped words
  const left = new Map(); for (const w of outW) left.set(w, (left.get(w) || 0) + 1);
  let kept = 0; for (const w of inW) { const n = left.get(w) || 0; if (n > 0) { left.set(w, n - 1); kept++; } }
  if (kept / inW.length < (relaxed ? 0.4 : 0.6)) return false;
  if (outW.length > inW.length * 1.4 + 6) return false;
  if (outW.length < 12) return true;
  const spoken = new Map(); for (const w of inW) spoken.set(w, (spoken.get(w) || 0) + 1);
  let from = 0; for (const w of outW) { if (/^\d+$/.test(w)) { from++; continue; } const n = spoken.get(w) || 0; if (n > 0) { spoken.set(w, n - 1); from++; } }
  return from / outW.length >= 0.72;
}
if (typeof module !== 'undefined') module.exports = { owAccepts };
