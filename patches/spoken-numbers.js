// Spoken number words -> digits, run on the raw transcript before cleanup.
// Parakeet always spells numbers out, and the cleanup model only converts them
// some of the time (it leaves sentence-initial numbers alone, for example).
// Only unambiguous numbers are converted; a run that does not parse stays as
// words for the cleanup model, never half-converted. A lone "one" stays a word
// unless a unit follows it. Also: decimals ("zero point five", "one point oh"),
// scale words ("six hundred billion" -> 600 billion), years ("twenty twenty six"),
// clock times ("at three thirty" -> 3:30), multipliers ("five x" -> 5x) and
// model sizes ("nine B model" -> 9B). Same rules as the phone's SpokenNumbers.kt.
function owNumbers(text) {
  const UNITS = Object.assign(Object.create(null), { zero: 0, one: 1, two: 2, three: 3, four: 4, five: 5, six: 6, seven: 7, eight: 8, nine: 9, ten: 10, eleven: 11, twelve: 12, thirteen: 13, fourteen: 14, fifteen: 15, sixteen: 16, seventeen: 17, eighteen: 18, nineteen: 19 });
  const TENS = Object.assign(Object.create(null), { twenty: 20, thirty: 30, forty: 40, fifty: 50, sixty: 60, seventy: 70, eighty: 80, ninety: 90 });
  const SCALES = Object.assign(Object.create(null), { hundred: 100, thousand: 1000, million: 1e6, billion: 1e9, trillion: 1e12 });
  const BIG = Object.assign(Object.create(null), { million: 1, billion: 1, trillion: 1 });
  const ORD = Object.assign(Object.create(null), { first: 1, second: 2, third: 3, fourth: 4, fifth: 5, sixth: 6, seventh: 7, eighth: 8, ninth: 9 });
  const UNIT_AFTER = /^(x|percent|%|k|times|minutes?|mins?|hours?|hrs?|seconds?|secs?|days?|weeks?|months?|years?|dollars?|bucks|cents?|gigs?|gb|mb|tb|ms|am|pm|p\.m\.|a\.m\.|point|o'clock|star|stars|people|users?|b|billion|million|trillion|thousand)$/i;
  const KEEP_BEFORE = /^(high|cloud|take|catch)$/i; // high five, cloud nine, take five, catch twenty-two
  const KEEP_AFTER = /^(factor|way|ways)$/i; // two-factor, three-way
  const TIME_BEFORE = /^(at|by|around|until|till|til|before|after|from|since|about|eta)$/i;
  const YEAR_BEFORE = /^(in|since|by|from|until|till|of|year|circa|early|late|mid|back|summer|winter|spring|fall|autumn|class)$/i;
  const B_AFTER = /^(model|models|parameter|parameters|params|param|dense|moe|active|total|version|variant|class|range|size|scale|sized)$/i;
  const tokens = text.split(/(\s+|-)/);
  const word = (i) => (tokens[i] || '').replace(/[.,!?;:"')]+$/, '').replace(/^["'(]+/, '').toLowerCase();
  const isNum = (w) => w in UNITS || w in TENS || w in SCALES;
  const isSep = (i) => /^(\s+|-)$/.test(tokens[i] || '');
  const endsAt = (i) => /[.,!?;:]["')]*$/.test(tokens[i] || '');
  const prevWord = (o) => { for (let k = o.length - 1; k >= 0; k--) if (o[k] && !/^(\s+|-)$/.test(o[k])) return o[k]; return ''; };
  const out = [];
  let i = 0;
  while (i < tokens.length) {
    let w = word(i);
    const pw = prevWord(out).replace(/[^A-Za-z']/g, '').toLowerCase();
    // "a hundred", "a thousand": the article stands for one.
    const article = w === 'a' && /^(hundred|thousand|million|billion)$/.test(word(i + 2)) && isSep(i + 1) && !endsAt(i);
    // A lone digit word right after "point" belongs to a decimal we could not parse; leave it alone.
    if (pw === 'point' && !article) { out.push(tokens[i]); i++; continue; }
    if (!article && (!isNum(w) || (w in SCALES) || (w === 'zero' && !(word(i + 2) === 'point' && UNITS[word(i + 4)] !== undefined)))) { out.push(tokens[i]); i++; continue; }
    // Collect a run of number words joined by spaces or hyphens, stopping at punctuation.
    const idx = [i];
    let j = i;
    while (!endsAt(j) && j + 2 < tokens.length && isSep(j + 1) && (isNum(word(j + 2)) || word(j + 2) === 'and' && isNum(word(j + 4)) && word(j + 4) !== 'one')) {
      if (word(j + 2) === 'and') { if (!(word(j) in SCALES)) break; idx.push(j + 2); j += 2; }
      j += 2; idx.push(j);
    }
    const ws = idx.map(word).filter((x) => x !== 'and').map((x) => (x === 'a' ? 'one' : x));
    // Parse; reject anything that is not a single well-formed number.
    let total = 0, cur = 0, ok = true, last = null, bigScale = null, bigBase = 0;
    for (const x of ws) {
      if (x in UNITS) { if (last === 'unit' || last === 'teen' || (last === 'tens' && UNITS[x] >= 10) || (last === 'tens' && UNITS[x] === 0)) { ok = false; break; } cur += UNITS[x]; last = UNITS[x] >= 10 ? 'teen' : 'unit'; }
      else if (x in TENS) { if (last && last !== 'scale') { ok = false; break; } cur += TENS[x]; last = 'tens'; }
      else { const s = SCALES[x]; if (!last || last === 'scale' && s !== 100 && cur === 0) { ok = false; break; } if (s === 100) { if (cur >= 100 || cur === 0) { ok = false; break; } cur *= 100; } else { if (x in BIG && total === 0) { bigScale = x; bigBase = cur; } else bigScale = null; total += cur * s; cur = 0; } last = 'scale'; }
    }
    if (bigScale && cur !== 0) bigScale = null; // "two million five hundred" is a plain number
    const end = idx[idx.length - 1];
    let nextI = end + 2;
    let after = word(nextI);
    const endsClause = endsAt(end);
    const keepWords = () => { for (let k = i; k <= end; k++) out.push(tokens[k]); i = end + 1; };
    if (!ok) {
      // Years: "twenty twenty six" -> 2026, "nineteen ninety nine" -> 1999, "twenty oh five" -> 2005.
      const year = parseYear(ws);
      if (year && (ws.length >= 3 || YEAR_BEFORE.test(pw))) { emit(String(year), end); continue; }
      // Aspect ratios: "sixteen nine aspect ratio" -> 16:9.
      if (ws.length === 2 && ws.every((x) => UNITS[x] >= 1 && UNITS[x] <= 32) && /^(aspect|ratio)$/.test(after)) { emit(UNITS[ws[0]] + ':' + UNITS[ws[1]], end); continue; }
      // Clock times: "at three thirty" -> 3:30, "seven fifteen pm" -> 7:15 pm.
      const time = parseTime(ws);
      if (time && (TIME_BEFORE.test(pw) || /^(am|pm|a\.m|p\.m)$/i.test(after))) { emit(time, end); continue; }
      keepWords(); continue;
    }
    let n = total + cur;
    // "twenty first" -> 21st
    let suffix = '';
    if (!endsClause && last === 'tens' && after in ORD) { n += ORD[after]; suffix = { 1: 'st', 2: 'nd', 3: 'rd' }[ORD[after]] || 'th'; nextI += 2; after = word(nextI); }
    // "five point five" -> 5.5, "two point one four" -> 2.14, "one point oh" -> 1.0
    let decimals = '';
    const digitAt = (k) => (UNITS[word(k)] < 10 ? UNITS[word(k)] : /^(oh|o)$/.test(word(k)) ? 0 : undefined);
    if (!suffix && !endsClause && !bigScale && after === 'point' && digitAt(nextI + 2) !== undefined) {
      let k = nextI + 2;
      while (digitAt(k) !== undefined) { decimals += digitAt(k); if (endsAt(k) || !/^\s+$/.test(tokens[k + 1] || '')) break; k += 2; }
      if (digitAt(k) === undefined) k -= 2;
      nextI = k + 2; after = word(nextI);
      // A run like "one forty point two" (n has a tens part after a unit) never reaches here: it fails to parse.
    }
    // "two point five million" -> 2.5 million
    let bigAfter = '';
    if (decimals && !endsAt(nextI - 2) && after in BIG) { bigAfter = ' ' + after; nextI += 2; after = word(nextI); }
    const lone = ws.length === 1;
    const prev = pw;
    const unitFollows = !endsClause && UNIT_AFTER.test(after) && !(ws[0] === 'one' && lone && after === 'day');
    const listNext = isNum(word(end + 2)) || /^\d/.test(word(end + 2)) || (/^(or|to|and)$/.test(after) && !endsClause && (isNum(word(nextI + 2)) || /^\d/.test(word(nextI + 2))));
    if ((lone && ws[0] === 'one' && !unitFollows && !suffix && !decimals && !listNext) || (KEEP_BEFORE.test(prev) && lone) || (!endsClause && KEEP_AFTER.test(after))) { keepWords(); continue; }
    const lastTok = suffix || decimals ? nextI - 2 : end;
    if (decimals) suffix = '.' + decimals + bigAfter;
    let digits = bigScale ? (bigBase >= 10000 ? bigBase.toLocaleString('en-US') : String(bigBase)) + ' ' + bigScale : n >= 10000 ? n.toLocaleString('en-US') : String(n);
    if (article && ws.length === 2 && !suffix && !/^(percent|%)$/.test(after)) { keepWords(); continue; } // "a hundred times" reads fine as words
    // A single capital letter before the number is a product name: "T three" -> "T3", "M four" -> "M4".
    if (out.length >= 2 && /^\s+$/.test(out[out.length - 1]) && /^[B-HJ-Z]$/.test(out[out.length - 2])) out.pop();
    emit(digits + suffix, lastTok);
  }
  return out.join('').replace(/\b(\d{1,2})[- ](\d{1,2})(?=\s+(?:aspect\s+)?ratio\b)/gi, '$1:$2');

  // Push the converted number, then glue on "x" (5x), "B" (9B) and "o" (GPT-4o) when they follow.
  function emit(value, lastTok) {
    const lead = (tokens[i].match(/^["'(]+/) || [''])[0];
    let trail = (tokens[lastTok].match(/[.,!?;:"')]+$/) || [''])[0];
    let k = lastTok;
    if (!trail && /^\s+$/.test(tokens[k + 1] || '') && /^\d/.test(value)) {
      const nxt = tokens[k + 2] || '';
      const bare = nxt.replace(/[.,!?;:"')]+$/, '');
      const nxtWord = (tokens[k + 4] || '').replace(/[^A-Za-z]/g, '');
      const glue = /^[xX]$/.test(bare) ? 'x'
        : /^[bB]$/.test(bare) && (B_AFTER.test(nxtWord) || /[.,!?;:]$/.test(nxt) || !tokens[k + 3]) ? 'B'
        : /^o$/i.test(bare) && /gpt$/i.test(prevWord(out).replace(/[^A-Za-z]/g, '')) ? 'o' : '';
      if (glue) { value += glue; trail = (nxt.match(/[.,!?;:"')]+$/) || [''])[0]; k += 2; }
    }
    out.push(lead + value + trail);
    i = k + 1;
  }
  function parseYear(ws) {
    if (ws.length < 2 || ws.length > 3 || !/^(nineteen|twenty)$/.test(ws[0])) return null;
    const century = UNITS[ws[0]] ?? TENS[ws[0]];
    const rest = ws.slice(1);
    let yy = null;
    if (rest.length === 1 && rest[0] in TENS) yy = TENS[rest[0]];
    else if (rest.length === 1 && UNITS[rest[0]] >= 10) yy = UNITS[rest[0]];
    else if (rest.length === 2 && rest[0] in TENS && UNITS[rest[1]] >= 1 && UNITS[rest[1]] <= 9) yy = TENS[rest[0]] + UNITS[rest[1]];
    if (yy === null) return null;
    return century * 100 + yy;
  }
  function parseTime(ws) {
    if (ws.length < 2 || ws.length > 3) return null;
    const h = UNITS[ws[0]];
    if (!(h >= 1 && h <= 12)) return null;
    const rest = ws.slice(1);
    let mm = null;
    if (rest.length === 1 && rest[0] in TENS && TENS[rest[0]] <= 50) mm = TENS[rest[0]];
    else if (rest.length === 1 && UNITS[rest[0]] >= 10) mm = UNITS[rest[0]];
    else if (rest.length === 2 && rest[0] in TENS && TENS[rest[0]] <= 50 && UNITS[rest[1]] >= 1 && UNITS[rest[1]] <= 9) mm = TENS[rest[0]] + UNITS[rest[1]];
    if (mm === null) return null;
    return h + ':' + String(mm).padStart(2, '0');
  }
}
if (typeof module !== 'undefined') module.exports = owNumbers;
