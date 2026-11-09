// Spoken emoji requests -> emoji characters, run on the transcript before cleanup.
// "fire emoji" -> 🔥, "blushing smiling face emoji" -> 😊, "three fire emojis" -> 🔥🔥🔥.
// Talk *about* emoji stays as words: "the voice emoji doesn't work", "add emojis", "a great emoji".
// Requests it cannot resolve are wrapped as ⟦emoji: description⟧ so the cleanup model resolves
// them; owEmojiUnmark() turns any marker that survives back into "description emoji".
// Same rules as the phone's SpokenEmoji.kt.
const OW_EMOJI_ALIASES = {
  "laughing": "😂", "laughing crying": "😂", "crying laughing": "😂", "laugh cry": "😂", "joy": "😂", "tears of joy": "😂", "lol": "😂",
  "rofl": "🤣", "rotfl": "🤣", "rolling on the floor laughing": "🤣", "rolling laughing": "🤣",
  "skull": "💀", "dead": "💀", "fire": "🔥", "flame": "🔥", "eyes": "👀", "eye": "👁️", "thumbs up": "👍", "thumb up": "👍", "thumbs down": "👎",
  "heart": "❤️", "red heart": "❤️", "hundred": "💯", "one hundred": "💯", "100": "💯", "clown": "🤡", "clown face": "🤡",
  "popcorn": "🍿", "upside down face": "🙃", "upside down": "🙃", "salute": "🫡", "saluting": "🫡", "saluting face": "🫡",
  "thinking": "🤔", "thinking face": "🤔", "thread": "🧵", "sparkle": "✨", "sparkles": "✨", "rocket": "🚀",
  "party": "🎉", "celebration": "🎉", "tada": "🎉", "party popper": "🎉", "celebration face": "🥳", "party face": "🥳", "partying face": "🥳",
  "prayer": "🙏", "praying": "🙏", "praying hands": "🙏", "pray": "🙏", "please": "🙏", "shrug": "🤷", "shrugging": "🤷",
  "facepalm": "🤦", "face palm": "🤦", "face palming": "🤦", "face planting": "🤦", "facepalming": "🤦",
  "man facepalm": "🤦‍♂️", "man face palm": "🤦‍♂️", "man face palming": "🤦‍♂️", "man face planting": "🤦‍♂️", "man facepalming": "🤦‍♂️",
  "mind blown": "🤯", "mind blowing": "🤯", "exploding head": "🤯", "head exploding": "🤯",
  "gravestone": "🪦", "tombstone": "🪦", "headstone": "🪦", "grave": "🪦", "rip": "🪦",
  "easter island head": "🗿", "easter island": "🗿", "moai": "🗿", "stone face": "🗿", "moyai": "🗿",
  "low battery": "🪫", "battery running out": "🪫", "dying battery": "🪫", "battery low": "🪫", "dead battery": "🪫", "battery": "🔋",
  "racecar": "🏎️", "race car": "🏎️", "racing car": "🏎️", "cloud of dust": "💨", "dust cloud": "💨", "dash": "💨", "dashing away": "💨",
  "vomit": "🤮", "vomiting": "🤮", "vomiting face": "🤮", "throwing up": "🤮", "puke": "🤮", "puking": "🤮",
  "nauseated": "🤢", "nauseous": "🤢", "about to vomit": "🤢", "about to vomit face": "🤢", "sick": "🤢", "green face": "🤢",
  "hands covering eyes": "🫣", "hand covering eyes": "🫣", "peeking": "🫣", "peeking eye": "🫣", "see no evil": "🙈", "see no evil monkey": "🙈", "monkey covering eyes": "🙈",
  "embarrassed": "😳", "flushed": "😳", "blushing": "😊", "blush": "😊", "blushing smiling face": "😊", "smiling blushing": "😊", "smiling blushing face": "😊", "blushing smiling": "😊", "smiling face": "😊",
  "musical note": "🎵", "music note": "🎵", "music": "🎶", "musical notes": "🎶", "drums": "🥁", "drum": "🥁", "lizard": "🦎", "pie": "🥧", "robot": "🤖",
  "flexing": "💪", "flex": "💪", "muscle": "💪", "bicep": "💪", "biceps": "💪", "strong": "💪",
  "screaming": "😱", "scream": "😱", "screaming face": "😱", "sob": "😭", "sobbing": "😭", "crying": "😭", "loudly crying": "😭", "cry": "😢", "sad": "😢", "tear": "😢",
  "wave": "👋", "waving": "👋", "hand waving": "👋", "waving hand": "👋", "hand": "✋", "raised hand": "✋", "high five": "🙏",
  "bench": "🪑", "chair": "🪑", "chart up": "📈", "chart going up": "📈", "graph up": "📈", "stonks": "📈", "chart down": "📉", "chart going down": "📉", "chart": "📊", "bar chart": "📊",
  "check": "✅", "check mark": "✅", "checkmark": "✅", "green check": "✅", "x": "❌", "cross mark": "❌", "red x": "❌", "warning": "⚠️",
  "money": "💰", "money bag": "💰", "goat": "🐐", "chef's kiss": "🤌", "chefs kiss": "🤌", "chef kiss": "🤌", "pinched fingers": "🤌",
  "sweat smile": "😅", "sweating smile": "😅", "nervous laugh": "😅", "grimace": "😬", "grimacing": "😬", "grimacing face": "😬", "yikes": "😬",
  "melting": "🫠", "melting face": "🫠", "crown": "👑", "trophy": "🏆", "brain": "🧠", "nerd": "🤓", "nerd face": "🤓",
  "sunglasses": "😎", "cool": "😎", "wink": "😉", "winking": "😉", "smirk": "😏", "smirking": "😏", "eye roll": "🙄", "rolling eyes": "🙄", "eyeroll": "🙄",
  "angry": "😠", "mad": "😠", "rage": "😡", "furious": "😡", "poop": "💩", "ghost": "👻", "alien": "👽", "shush": "🤫", "shushing": "🤫",
  "monocle": "🧐", "star struck": "🤩", "starstruck": "🤩", "hug": "🤗", "hugging": "🤗", "pleading": "🥺", "puppy eyes": "🥺", "heart eyes": "😍",
  "kiss": "😘", "ok": "👌", "okay": "👌", "ok hand": "👌", "okay hand": "👌", "perfect": "👌", "clap": "👏", "clapping": "👏", "applause": "👏",
  "raised hands": "🙌", "raising hands": "🙌", "point up": "☝️", "pointing up": "☝️", "pointing right": "👉", "point right": "👉", "pointing down": "👇", "point down": "👇", "pointing left": "👈",
  "handshake": "🤝", "crossed fingers": "🤞", "fingers crossed": "🤞", "light bulb": "💡", "lightbulb": "💡", "idea": "💡", "lightning": "⚡", "lightning bolt": "⚡",
  "star": "⭐", "broken heart": "💔", "siren": "🚨", "alarm": "🚨", "hourglass": "⏳", "clock": "⏰", "confetti": "🎊", "balloon": "🎈", "gift": "🎁", "present": "🎁",
  "coffee": "☕", "beer": "🍺", "beers": "🍻", "cheers": "🥂", "pizza": "🍕", "exclamation point": "❗", "exclamation mark": "❗", "exclamation": "❗",
  "question mark": "❓", "zany": "🤪", "crazy": "🤪", "silly": "🤪", "smile": "🙂", "smiling": "🙂", "slight smile": "🙂", "grin": "😁", "grinning": "😀", "big grin": "😁",
  "upside down smile": "🙃", "money mouth": "🤑", "zipper mouth": "🤐", "sleeping": "😴", "sleepy": "😴", "tired": "😫", "yawn": "🥱", "yawning": "🥱",
  "cowboy": "🤠", "devil": "😈", "imp": "😈", "angel": "😇", "halo": "😇", "holding back tears": "🥹", "face holding back tears": "🥹",
  "pensive": "😔", "disappointed": "😞", "unamused": "😒", "expressionless": "😑", "neutral face": "😐", "no mouth": "😶", "dotted line face": "🫥",
  "eyes wide": "😳", "shocked": "😲", "astonished": "😲", "open mouth": "😮", "mouth open": "😮", "hot": "🥵", "hot face": "🥵", "cold": "🥶", "cold face": "🥶",
  "sweat": "😓", "relieved": "😌", "drooling": "🤤", "lying": "🤥", "pinocchio": "🤥", "mask": "😷", "nerd glasses": "🤓", "fist bump": "👊", "punch": "👊",
  "middle finger": "🖕", "victory": "✌️", "peace": "✌️", "peace sign": "✌️", "rock on": "🤘", "horns": "🤘", "call me": "🤙", "shaka": "🤙",
  "writing": "✍️", "writing hand": "✍️", "nail polish": "💅", "selfie": "🤳", "salt": "🧂", "cap": "🧢", "billed cap": "🧢", "no cap": "🧢",
  "target": "🎯", "bullseye": "🎯", "dart": "🎯", "bomb": "💣", "boom": "💥", "collision": "💥", "explosion": "💥", "sweat drops": "💦", "zzz": "💤",
  "speech bubble": "💬", "thought bubble": "💭", "pin": "📌", "pushpin": "📌", "link": "🔗", "lock": "🔒", "key": "🔑", "magnifying glass": "🔍", "search": "🔍",
  "laptop": "💻", "computer": "💻", "phone": "📱", "mobile phone": "📱", "camera": "📷", "movie camera": "🎥", "tv": "📺", "television": "📺", "microphone": "🎤", "mic": "🎤",
  "headphones": "🎧", "video game": "🎮", "game controller": "🎮", "controller": "🎮", "dice": "🎲", "puzzle": "🧩", "puzzle piece": "🧩",
  "book": "📖", "books": "📚", "memo": "📝", "note": "📝", "notes": "📝", "clipboard": "📋", "calendar": "📅", "folder": "📁", "package": "📦", "box": "📦",
  "hammer": "🔨", "wrench": "🔧", "tools": "🛠️", "gear": "⚙️", "shield": "🛡️", "sword": "🗡️", "crystal ball": "🔮", "magic": "🪄", "magic wand": "🪄",
  "earth": "🌍", "globe": "🌍", "world": "🌍", "sun": "☀️", "moon": "🌙", "rainbow": "🌈", "snowflake": "❄️", "cloud": "☁️", "umbrella": "☔", "water": "💧", "wave water": "🌊", "ocean": "🌊",
  "tree": "🌳", "plant": "🌱", "seedling": "🌱", "flower": "🌸", "rose": "🌹", "sunflower": "🌻", "cactus": "🌵", "mushroom": "🍄", "leaf": "🍃",
  "dog": "🐶", "cat": "🐱", "mouse": "🐭", "rabbit": "🐰", "bunny": "🐰", "fox": "🦊", "bear": "🐻", "panda": "🐼", "monkey": "🐒", "lion": "🦁", "tiger": "🐯", "cow": "🐮", "pig": "🐷", "frog": "🐸",
  "chicken": "🐔", "penguin": "🐧", "bird": "🐦", "eagle": "🦅", "duck": "🦆", "owl": "🦉", "bat": "🦇", "wolf": "🐺", "horse": "🐴", "unicorn": "🦄", "bee": "🐝", "bug": "🐛", "butterfly": "🦋",
  "snail": "🐌", "turtle": "🐢", "snake": "🐍", "dragon": "🐉", "dinosaur": "🦖", "t rex": "🦖", "whale": "🐳", "dolphin": "🐬", "fish": "🐟", "shark": "🦈", "octopus": "🐙", "crab": "🦀", "lobster": "🦞", "shrimp": "🦐",
  "apple": "🍎", "banana": "🍌", "avocado": "🥑", "taco": "🌮", "burger": "🍔", "hamburger": "🍔", "fries": "🍟", "hot dog": "🌭", "cake": "🍰", "birthday cake": "🎂", "cookie": "🍪", "donut": "🍩", "doughnut": "🍩", "ice cream": "🍦", "egg": "🥚", "bread": "🍞", "cheese": "🧀", "wine": "🍷", "tea": "🍵", "bubble tea": "🧋",
  "car": "🚗", "taxi": "🚕", "bus": "🚌", "train": "🚆", "airplane": "✈️", "plane": "✈️", "ship": "🚢", "boat": "⛵", "bike": "🚲", "bicycle": "🚲", "house": "🏠", "home": "🏠", "office": "🏢", "hospital": "🏥",
  "soccer": "⚽", "football": "🏈", "basketball": "🏀", "baseball": "⚾", "tennis": "🎾", "medal": "🏅", "gold medal": "🥇", "first place": "🥇", "running": "🏃", "runner": "🏃", "weightlifting": "🏋️", "weight lifting": "🏋️",
  "flag": "🚩", "red flag": "🚩", "checkered flag": "🏁", "white flag": "🏳️", "pirate flag": "🏴‍☠️", "american flag": "🇺🇸", "us flag": "🇺🇸",
  "new": "🆕", "free": "🆓", "sos": "🆘", "stop": "🛑", "stop sign": "🛑", "no entry": "⛔", "prohibited": "🚫", "recycle": "♻️", "infinity": "♾️", "plus": "➕", "minus": "➖",
  "green circle": "🟢", "red circle": "🔴", "yellow circle": "🟡", "blue circle": "🔵", "white circle": "⚪", "black circle": "⚫", "orange heart": "🧡", "yellow heart": "💛", "green heart": "💚", "blue heart": "💙", "purple heart": "💜", "black heart": "🖤", "white heart": "🤍",
  "pointing at you": "🫵", "index pointing at viewer": "🫵", "hand over mouth": "🤭", "giggle": "🤭", "giggling": "🤭", "saluting hand": "🫡", "skull and crossbones": "☠️", "nose": "👃", "tongue": "👅", "lips": "👄", "biting lip": "🫦", "ear": "👂",
  "baby": "👶", "old man": "👴", "old woman": "👵", "ninja": "🥷", "detective": "🕵️", "zombie": "🧟", "vampire": "🧛", "superhero": "🦸", "santa": "🎅", "mage": "🧙", "wizard": "🧙", "genie": "🧞", "mermaid": "🧜", "elf": "🧝",
  "man shrugging": "🤷‍♂️", "woman shrugging": "🤷‍♀️", "woman facepalming": "🤦‍♀️", "woman face palming": "🤦‍♀️", "person bowing": "🙇", "bowing": "🙇", "raising hand": "🙋", "person raising hand": "🙋", "tipping hand": "💁", "no gesture": "🙅", "ok gesture": "🙆",
  "ballot box": "🗳️", "money with wings": "💸", "flying money": "💸", "dollar": "💵", "dollar bill": "💵", "credit card": "💳", "gem": "💎", "diamond": "💎", "ring": "💍", "bell": "🔔", "megaphone": "📣", "loudspeaker": "📢", "trumpet": "🎺", "guitar": "🎸", "piano": "🎹", "violin": "🎻", "saxophone": "🎷",
  "hourglass done": "⌛", "stopwatch": "⏱️", "timer": "⏲️", "battery full": "🔋", "plug": "🔌", "electric plug": "🔌", "satellite": "🛰️", "telescope": "🔭", "microscope": "🔬", "test tube": "🧪", "dna": "🧬", "pill": "💊", "syringe": "💉", "bandage": "🩹", "thermometer": "🌡️",
  "volcano": "🌋", "mountain": "⛰️", "camping": "🏕️", "beach": "🏖️", "desert island": "🏝️", "night": "🌃", "city": "🏙️", "sunrise": "🌅", "sunset": "🌇", "fireworks": "🎆", "sparkler": "🎇", "jack o lantern": "🎃", "pumpkin": "🎃", "christmas tree": "🎄",
};
// Words that make the request talk *about* emoji ("the voice emoji", "a great emoji").
const OW_EMOJI_META_BEFORE = /^(a|an|the|this|that|these|those|my|your|his|her|our|their|its|any|no|some|which|what|whatever|every|each|voice|another|as|that's)$/i;
// Words that never belong inside an emoji description; they end the span.
const OW_EMOJI_BREAK = /^(and|or|but|so|is|are|was|were|be|been|it|it's|i|i'm|you|we|they|he|she|to|for|in|on|at|by|from|then|than|just|really|very|also|like|um|uh|okay|ok|yes|yeah|no|not|please|put|add|insert|use|type|send|with|of|the|a|an)$/i;
const OW_EMOJI_INNER_OK = /^(of|with|on|the|and|a)$/i; // allowed inside an alias/label, e.g. "cloud of dust", "rolling on the floor laughing"
const OW_NUM_WORDS = Object.assign(Object.create(null), { one: 1, two: 2, three: 3, four: 4, five: 5, six: 6, seven: 7, eight: 8, nine: 9, ten: 10 });

function owEmojiStem(w) {
  w = w.toLowerCase().replace(/[’']s$/, "").replace(/[^a-z0-9]/g, "");
  if (w.length > 5 && w.endsWith("ing")) w = w.slice(0, -3);
  else if (w.length > 4 && w.endsWith("ed")) w = w.slice(0, -2);
  else if (w.length > 4 && w.endsWith("es")) w = w.slice(0, -2);
  else if (w.length > 3 && w.endsWith("s") && !w.endsWith("ss")) w = w.slice(0, -1);
  if (w.length > 3 && /([b-df-hj-np-tv-z])\1$/.test(w)) w = w.slice(0, -1); // running -> run
  if (w.length > 3 && w.endsWith("e")) w = w.slice(0, -1);
  return w;
}

let owEmojiIndex = null;
function owEmojiLoad(tsv) {
  const rows = [];
  for (const line of tsv.split("\n")) {
    if (!line) continue;
    const [emoji, label, words] = line.split("\t");
    const vocab = new Set(words.split(" ").map(owEmojiStem));
    const labelWords = label.replace(/[:,]/g, "").split(/\s+/).filter((w) => !OW_EMOJI_INNER_OK.test(w)).map(owEmojiStem);
    rows.push({ emoji, label, vocab, labelWords, order: rows.length });
  }
  const aliases = new Map();
  for (const [k, v] of Object.entries(OW_EMOJI_ALIASES)) { const key = k.split(/\s+/).map(owEmojiStem).join(" "); if (!aliases.has(key)) aliases.set(key, v); }
  const vocab = new Set();
  for (const r of rows) for (const w of r.vocab) vocab.add(w);
  for (const k of aliases.keys()) for (const w of k.split(" ")) vocab.add(w);
  owEmojiIndex = { rows, aliases, vocab };
}

// Best emoji whose vocabulary covers every content word, or null.
function owEmojiLookup(words) {
  const key = words.map(owEmojiStem).join(" ");
  const alias = owEmojiIndex.aliases.get(key);
  if (alias) return alias;
  const content = words.filter((w) => !OW_EMOJI_INNER_OK.test(w)).map(owEmojiStem).filter(Boolean);
  if (!content.length) return null;
  let best = null, bestScore = -1;
  for (const r of owEmojiIndex.rows) {
    if (!content.every((w) => r.vocab.has(w))) continue;
    const labelHits = r.labelWords.filter((w) => content.includes(w)).length;
    const exact = labelHits === r.labelWords.length && labelHits === content.length;
    // Description words in the label beat words that only appear in tags; exact label wins.
    const score = (exact ? 100 : 0) + labelHits * 10 - r.labelWords.length - r.order / 10000;
    if (score > bestScore) { bestScore = score; best = r; }
  }
  // A single word that only matches tags (e.g. "good" -> 👍) is too loose; require a label hit.
  if (best && content.length === 1 && !best.labelWords.includes(content[0])) return null;
  return best ? best.emoji : null;
}

function owEmoji(text, opts) {
  const force = !!(opts && opts.force);
  if (!owEmojiIndex || !/emoji/i.test(text)) return text;
  // Tokens: words with their trailing punctuation, and the whitespace between them.
  const parts = text.split(/(\s+)/);
  const out = [];
  const made = new Set(); // indexes in `out` holding emoji this call produced
  for (let i = 0; i < parts.length; i++) {
    const tok = parts[i];
    const m = /^(emoji|emojis|emojie|emojies)([.,!?;:)"']*)$/i.exec(tok);
    if (!m) { out.push(tok); continue; }
    const plural = /s$/i.test(m[1]);
    const trail = m[2];
    // Walk back over preceding words (in `out`) collecting the description.
    const words = []; const idx = [];
    for (let j = out.length - 1; j >= 0 && words.length < 6; j--) {
      if (/^\s+$/.test(out[j]) || out[j] === "") continue;
      const raw = out[j];
      if (/[.!?;:]["')]*$/.test(raw)) break;            // previous sentence
      if (/[\p{Extended_Pictographic}]/u.test(raw)) break;
      const w = raw.replace(/^["'(]+/, "").replace(/[,"')]+$/, "");
      if (!/^[A-Za-z0-9][A-Za-z0-9'’-]*$/.test(w)) break;
      words.unshift(w.toLowerCase().replace(/-/g, " ")); idx.unshift(j);
    }
    // Choose the longest suffix of the collected words that resolves.
    let chosen = null, take = 0;
    for (let k = Math.min(words.length, 5); k >= 1; k--) {
      const span = words.slice(words.length - k).join(" ").split(" ");
      if (new Set(span.map(owEmojiStem)).size < span.length) continue; // "robot, robot emoji" is one robot
      const aliasHit = !plural && owEmojiIndex.aliases.get(span.map(owEmojiStem).join(" "));
      if (aliasHit) { chosen = aliasHit; take = k; break; }
      if (OW_EMOJI_BREAK.test(span[0]) || span.some((w, n) => n > 0 && OW_EMOJI_BREAK.test(w) && !OW_EMOJI_INNER_OK.test(w))) continue;
      let count = 1, body = span;
      if (plural && span.length > 1 && OW_NUM_WORDS[span[0]]) { count = OW_NUM_WORDS[span[0]]; body = span.slice(1); }
      else if (plural && span.length > 1 && /^\d+$/.test(span[0]) && +span[0] <= 10) { count = +span[0]; body = span.slice(1); }
      else if (plural) continue;                          // "add emojis" is talk about emoji
      const e = owEmojiLookup(body);
      if (e) { chosen = e.repeat(count); take = k; break; }
    }
    const before = words.length - take;
    const metaBefore = !force && before > 0 && OW_EMOJI_META_BEFORE.test(words[before - 1]);
    if (chosen && !metaBefore) {
      // Drop the description words (and the whitespace between them) from `out`.
      const firstIdx = idx[idx.length - take];
      out.length = firstIdx;
      // The model already inserted this emoji next to the words ("🪦 Gravestone emoji"): keep one.
      // Only an emoji that was already in the text counts, and only with nothing but a space between:
      // "fire emoji, fire emoji" is a deliberate repeat.
      let prevIdx = out.length - 1;
      while (prevIdx >= 0 && /^\s*$/.test(out[prevIdx])) prevIdx--;
      const prevTok = prevIdx >= 0 ? out[prevIdx] : "";
      const nextTok = parts.slice(i + 1).find((t) => t && !/^\s+$/.test(t)) || "";
      if ((prevTok.endsWith(chosen) && !made.has(prevIdx) && !trail) || (nextTok.startsWith(chosen) && /^\.?$/.test(trail))) {
        while (out.length && /^\s+$/.test(out[out.length - 1])) out.pop();
        if (trail.replace(/^\./, "")) out.push(trail.replace(/^\./, ""));
        if (i + 1 < parts.length && /^\s+$/.test(parts[i + 1])) { if (out.length) out.push(parts[i + 1]); i++; }
        continue;
      }
      // A sentence-final period after an emoji that ends the text is dropped ("Laughing emoji." -> 😂).
      const lastWord = !parts.slice(i + 1).some((t) => t && !/^\s+$/.test(t));
      made.add(out.length);
      out.push(chosen + (lastWord ? trail.replace(/^\./, "") : trail));
      continue;
    }
    if (!plural && take === 0 && words.length && !metaBefore && !OW_EMOJI_META_BEFORE.test(words[words.length - 1])) {
      // Unresolved but looks like a request: let the cleanup model pick the emoji.
      const n = Math.min(words.length, 4);
      const span = []; let s = words.length;
      for (let k = words.length - 1; k >= words.length - n; k--) { if (OW_EMOJI_BREAK.test(words[k]) || OW_EMOJI_META_BEFORE.test(words[k]) || !owEmojiIndex.vocab.has(owEmojiStem(words[k]))) break; span.unshift(words[k]); s = k; }
      if (s > 0 && OW_EMOJI_META_BEFORE.test(words[s - 1])) span.length = 0;
      if (span.length) {
        out.length = idx[s];
        out.push("⟦emoji: " + span.join(" ") + "⟧" + trail);
        continue;
      }
    }
    out.push(tok);
  }
  return out.join("");
}

// Selection edit: "just put the emoji" on a selection like "Gravestone emoji" -> "🪦", no model call.
// Returns null when the instruction asks for anything more than that.
const OW_EMOJI_EDIT_FILLER = /^(just|only|please|put|use|insert|make|turn|replace|convert|change|swap|it|that|this|those|them|into|to|with|in|the|a|an|actual|real|emoji|emojis|emoji's|instead|itself|themselves|nothing|else|and|i|meant|mean|want|wanted|no|words|word|text|description|characters|character|symbol|symbols|of|for|so|leave|keep|drop|remove|delete|get|rid|transcription|instructions|instruction|should|be|is|just|okay|ok|yeah|yes|not|don't|do|dont|literal|literally|spell|spelled|out|written)$/i;
function owEmojiEdit(instruction, selected) {
  if (!owEmojiIndex || !/\bemoji/i.test(instruction || "") || !/emoji/i.test(selected || "")) return null;
  const words = String(instruction).toLowerCase().replace(/[^a-z' ]+/g, " ").split(/\s+/).filter(Boolean);
  if (!words.length || !words.every((w) => OW_EMOJI_EDIT_FILLER.test(w))) return null;
  // Force-convert every "<description> emoji" in the selection, ignoring the talk-about-emoji guards.
  const converted = owEmoji(selected, { force: true });
  if (converted === selected || /⟦emoji:/.test(converted)) return null;
  return converted;
}

function owEmojiUnmark(text) {
  return text.replace(/⟦emoji:\s*([^⟧]*)⟧/g, "$1 emoji");
}

if (typeof module !== "undefined") module.exports = { owEmoji, owEmojiLoad, owEmojiUnmark, owEmojiLookup, owEmojiStem, owEmojiEdit };
