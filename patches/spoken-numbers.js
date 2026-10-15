// Spoken number words -> digits, run on the raw transcript before cleanup.
// Parakeet always spells numbers out, and the cleanup model only converts them
// some of the time (it leaves sentence-initial numbers alone, for example).
// Only unambiguous single numbers are converted. Anything that does not parse
// as one number ("three thirty", "twenty twenty six") stays as words for the
// cleanup model. A lone "one" stays a word unless a unit follows it.
function owNumbers(text) {
  const UNITS = { zero: 0, one: 1, two: 2, three: 3, four: 4, five: 5, six: 6, seven: 7, eight: 8, nine: 9, ten: 10, eleven: 11, twelve: 12, thirteen: 13, fourteen: 14, fifteen: 15, sixteen: 16, seventeen: 17, eighteen: 18, nineteen: 19 };
  const TENS = { twenty: 20, thirty: 30, forty: 40, fifty: 50, sixty: 60, seventy: 70, eighty: 80, ninety: 90 };
  const SCALES = { hundred: 100, thousand: 1000, million: 1e6, billion: 1e9 };
  const ORD = { first: 1, second: 2, third: 3, fourth: 4, fifth: 5, sixth: 6, seventh: 7, eighth: 8, ninth: 9 };
  const UNIT_AFTER = /^(x|percent|%|k|times|minutes?|mins?|hours?|hrs?|seconds?|secs?|days?|weeks?|months?|years?|dollars?|bucks|cents?|gigs?|gb|mb|tb|ms|am|pm|p\.m\.|a\.m\.|point|o'clock|star|stars|people|users?)$/i;
  const KEEP_BEFORE = /^(high|cloud|take|catch)$/i; // high five, cloud nine, take five, catch twenty-two
  const KEEP_AFTER = /^(factor|way|ways)$/i; // two-factor, three-way
  const tokens = text.split(/(\s+|-)/);
  const word = (i) => (tokens[i] || '').replace(/[.,!?;:"')]+$/, '').replace(/^["'(]+/, '').toLowerCase();
  const isNum = (w) => w in UNITS || w in TENS || w in SCALES;
  const out = [];
  let i = 0;
  while (i < tokens.length) {
    const w = word(i);
    if (!isNum(w) || (w in SCALES) || w === 'zero') { out.push(tokens[i]); i++; continue; }
    // Collect a run of number words joined by spaces or hyphens, stopping at punctuation.
    const idx = [i];
    let j = i;
    while (!/[.,!?;:]$/.test(tokens[j]) && j + 2 < tokens.length && /^(\s+|-)$/.test(tokens[j + 1]) && (isNum(word(j + 2)) || word(j + 2) === 'and' && isNum(word(j + 4)) && word(j + 4) !== 'one')) {
      if (word(j + 2) === 'and') { if (!(word(j) in SCALES)) break; idx.push(j + 2); j += 2; }
      j += 2; idx.push(j);
    }
    const ws = idx.map(word).filter((x) => x !== 'and');
    // Parse; reject anything that is not a single well-formed number.
    let total = 0, cur = 0, ok = true, last = null;
    for (const x of ws) {
      if (x in UNITS) { if (last === 'unit' || last === 'teen' || (last === 'tens' && UNITS[x] >= 10) || (last === 'tens' && UNITS[x] === 0)) { ok = false; break; } cur += UNITS[x]; last = UNITS[x] >= 10 ? 'teen' : 'unit'; }
      else if (x in TENS) { if (last && last !== 'scale') { ok = false; break; } cur += TENS[x]; last = 'tens'; }
      else { const s = SCALES[x]; if (!last || last === 'scale' && s !== 100 && cur === 0) { ok = false; break; } if (s === 100) { if (cur >= 100 || cur === 0) { ok = false; break; } cur *= 100; } else { total += cur * s; cur = 0; } last = 'scale'; }
    }
    const end = idx[idx.length - 1];
    let nextI = end + 2;
    const prev = out.length >= 2 ? (out[out.length - 2] || '').replace(/[^A-Za-z]/g, '') : '';
    let after = word(nextI);
    const endsClause = /[.,!?;:]$/.test(tokens[end]);
    if (!ok) { for (let k = i; k <= end; k++) out.push(tokens[k]); i = end + 1; continue; }
    let n = total + cur;
    // "twenty first" -> 21st
    let suffix = '';
    if (!endsClause && last === 'tens' && after in ORD) { n += ORD[after]; suffix = { 1: 'st', 2: 'nd', 3: 'rd' }[ORD[after]] || 'th'; nextI += 2; after = word(nextI); }
    // "five point five" -> 5.5, "two point one four" -> 2.14
    let decimals = '';
    if (!suffix && !endsClause && after === 'point' && UNITS[word(nextI + 2)] < 10) {
      let k = nextI + 2;
      while (UNITS[word(k)] < 10) { decimals += UNITS[word(k)]; if (/[.,!?;:]$/.test(tokens[k]) || !/^\s+$/.test(tokens[k + 1] || '')) break; k += 2; }
      if (!(UNITS[word(k)] < 10)) k -= 2;
      nextI = k + 2; after = word(nextI);
    }
    const lone = ws.length === 1;
    const unitFollows = !endsClause && UNIT_AFTER.test(after) && !(ws[0] === 'one' && lone && after === 'day');
    const listNext = isNum(word(end + 2)) || /^\d/.test(word(end + 2));
    if ((lone && ws[0] === 'one' && !unitFollows && !suffix && !decimals && !listNext) || (KEEP_BEFORE.test(prev) && lone) || (!endsClause && KEEP_AFTER.test(after))) {
      for (let k = i; k <= end; k++) out.push(tokens[k]); i = end + 1; continue;
    }
    const lead = (tokens[i].match(/^["'(]+/) || [''])[0];
    const lastTok = suffix || decimals ? nextI - 2 : end;
    const trail = (tokens[lastTok].match(/[.,!?;:"')]+$/) || [''])[0];
    if (decimals) suffix = '.' + decimals;
    let digits = n >= 10000 ? n.toLocaleString('en-US') : String(n);
    // A single capital letter before the number is a product name: "T three" -> "T3", "M four" -> "M4".
    if (out.length >= 2 && /^\s+$/.test(out[out.length - 1]) && /^[B-HJ-Z]$/.test(out[out.length - 2])) out.pop();
    out.push(lead + digits + suffix + trail);
    i = lastTok + 1;
  }
  return out.join('');
}
module.exports = owNumbers;
