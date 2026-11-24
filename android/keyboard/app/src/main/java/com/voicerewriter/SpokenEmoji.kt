package com.voicerewriter

import android.content.Context
import java.math.BigInteger

/**
 * Spoken emoji requests -> emoji characters, run on the transcript before cleanup.
 * "fire emoji" -> 🔥, "blushing smiling face emoji" -> 😊, "three fire emojis" -> 🔥🔥🔥.
 * Talk *about* emoji stays as words: "the voice emoji doesn't work", "add emojis", "a great emoji".
 * Requests it cannot resolve are wrapped as ⟦emoji: description⟧ so the cleanup model resolves
 * them; [unmark] turns any marker that survives back into "description emoji".
 * Line-for-line port of the desktop's speed-patch/owEmoji.js (SpokenEmojiParityTest proves
 * byte-identical output). The emoji table is the asset emoji-table.tsv, read once on first use.
 */
object SpokenEmoji {
    private const val ASSET = "emoji-table.tsv"

    /** Same entries, same order as OW_EMOJI_ALIASES in owEmoji.js (JS puts the integer key "100" first). */
    private val ALIASES: List<Pair<String, String>> = listOf(
        "100" to "💯", "laughing" to "😂", "laughing crying" to "😂", "crying laughing" to "😂", "laugh cry" to "😂",
        "joy" to "😂", "tears of joy" to "😂", "lol" to "😂", "rofl" to "🤣", "rotfl" to "🤣",
        "rolling on the floor laughing" to "🤣", "rolling laughing" to "🤣", "skull" to "💀", "dead" to "💀",
        "fire" to "🔥", "flame" to "🔥", "eyes" to "👀", "eye" to "👁️", "thumbs up" to "👍", "thumb up" to "👍",
        "thumbs down" to "👎", "heart" to "❤️", "red heart" to "❤️", "hundred" to "💯", "one hundred" to "💯",
        "clown" to "🤡", "clown face" to "🤡", "popcorn" to "🍿", "upside down face" to "🙃", "upside down" to "🙃",
        "salute" to "🫡", "saluting" to "🫡", "saluting face" to "🫡", "thinking" to "🤔", "thinking face" to "🤔",
        "thread" to "🧵", "sparkle" to "✨", "sparkles" to "✨", "rocket" to "🚀", "party" to "🎉",
        "celebration" to "🎉", "tada" to "🎉", "party popper" to "🎉", "celebration face" to "🥳",
        "party face" to "🥳", "partying face" to "🥳", "prayer" to "🙏", "praying" to "🙏", "praying hands" to "🙏",
        "pray" to "🙏", "please" to "🙏", "shrug" to "🤷", "shrugging" to "🤷", "facepalm" to "🤦",
        "face palm" to "🤦", "face palming" to "🤦", "face planting" to "🤦", "facepalming" to "🤦",
        "man facepalm" to "🤦‍♂️", "man face palm" to "🤦‍♂️", "man face palming" to "🤦‍♂️",
        "man face planting" to "🤦‍♂️", "man facepalming" to "🤦‍♂️", "mind blown" to "🤯", "mind blowing" to "🤯",
        "exploding head" to "🤯", "head exploding" to "🤯", "gravestone" to "🪦", "tombstone" to "🪦",
        "headstone" to "🪦", "grave" to "🪦", "rip" to "🪦", "easter island head" to "🗿", "easter island" to "🗿",
        "moai" to "🗿", "stone face" to "🗿", "moyai" to "🗿", "low battery" to "🪫", "battery running out" to "🪫",
        "dying battery" to "🪫", "battery low" to "🪫", "dead battery" to "🪫", "battery" to "🔋",
        "racecar" to "🏎️", "race car" to "🏎️", "racing car" to "🏎️", "cloud of dust" to "💨",
        "dust cloud" to "💨", "dash" to "💨", "dashing away" to "💨", "vomit" to "🤮", "vomiting" to "🤮",
        "vomiting face" to "🤮", "throwing up" to "🤮", "puke" to "🤮", "puking" to "🤮", "nauseated" to "🤢",
        "nauseous" to "🤢", "about to vomit" to "🤢", "about to vomit face" to "🤢", "sick" to "🤢",
        "green face" to "🤢", "hands covering eyes" to "🫣", "hand covering eyes" to "🫣", "peeking" to "🫣",
        "peeking eye" to "🫣", "see no evil" to "🙈", "see no evil monkey" to "🙈", "monkey covering eyes" to "🙈",
        "embarrassed" to "😳", "flushed" to "😳", "blushing" to "😊", "blush" to "😊",
        "blushing smiling face" to "😊", "smiling blushing" to "😊", "smiling blushing face" to "😊",
        "blushing smiling" to "😊", "smiling face" to "😊", "musical note" to "🎵", "music note" to "🎵",
        "music" to "🎶", "musical notes" to "🎶", "drums" to "🥁", "drum" to "🥁", "lizard" to "🦎", "pie" to "🥧",
        "robot" to "🤖", "flexing" to "💪", "flex" to "💪", "muscle" to "💪", "bicep" to "💪", "biceps" to "💪",
        "strong" to "💪", "screaming" to "😱", "scream" to "😱", "screaming face" to "😱", "sob" to "😭",
        "sobbing" to "😭", "crying" to "😭", "loudly crying" to "😭", "cry" to "😢", "sad" to "😢", "tear" to "😢",
        "wave" to "👋", "waving" to "👋", "hand waving" to "👋", "waving hand" to "👋", "hand" to "✋",
        "raised hand" to "✋", "high five" to "🙏", "bench" to "🪑", "chair" to "🪑", "chart up" to "📈",
        "chart going up" to "📈", "graph up" to "📈", "stonks" to "📈", "chart down" to "📉",
        "chart going down" to "📉", "chart" to "📊", "bar chart" to "📊", "check" to "✅", "check mark" to "✅",
        "checkmark" to "✅", "green check" to "✅", "x" to "❌", "cross mark" to "❌", "red x" to "❌", "warning" to "⚠️",
        "money" to "💰", "money bag" to "💰", "goat" to "🐐", "chef's kiss" to "🤌", "chefs kiss" to "🤌",
        "chef kiss" to "🤌", "pinched fingers" to "🤌", "sweat smile" to "😅", "sweating smile" to "😅",
        "nervous laugh" to "😅", "grimace" to "😬", "grimacing" to "😬", "grimacing face" to "😬", "yikes" to "😬",
        "melting" to "🫠", "melting face" to "🫠", "crown" to "👑", "trophy" to "🏆", "brain" to "🧠",
        "nerd" to "🤓", "nerd face" to "🤓", "sunglasses" to "😎", "cool" to "😎", "wink" to "😉", "winking" to "😉",
        "smirk" to "😏", "smirking" to "😏", "eye roll" to "🙄", "rolling eyes" to "🙄", "eyeroll" to "🙄",
        "angry" to "😠", "mad" to "😠", "rage" to "😡", "furious" to "😡", "poop" to "💩", "ghost" to "👻",
        "alien" to "👽", "shush" to "🤫", "shushing" to "🤫", "monocle" to "🧐", "star struck" to "🤩",
        "starstruck" to "🤩", "hug" to "🤗", "hugging" to "🤗", "pleading" to "🥺", "puppy eyes" to "🥺",
        "heart eyes" to "😍", "kiss" to "😘", "ok" to "👌", "okay" to "👌", "ok hand" to "👌", "okay hand" to "👌",
        "perfect" to "👌", "clap" to "👏", "clapping" to "👏", "applause" to "👏", "raised hands" to "🙌",
        "raising hands" to "🙌", "point up" to "☝️", "pointing up" to "☝️", "pointing right" to "👉",
        "point right" to "👉", "pointing down" to "👇", "point down" to "👇", "pointing left" to "👈",
        "handshake" to "🤝", "crossed fingers" to "🤞", "fingers crossed" to "🤞", "light bulb" to "💡",
        "lightbulb" to "💡", "idea" to "💡", "lightning" to "⚡", "lightning bolt" to "⚡", "star" to "⭐",
        "broken heart" to "💔", "siren" to "🚨", "alarm" to "🚨", "hourglass" to "⏳", "clock" to "⏰",
        "confetti" to "🎊", "balloon" to "🎈", "gift" to "🎁", "present" to "🎁", "coffee" to "☕", "beer" to "🍺",
        "beers" to "🍻", "cheers" to "🥂", "pizza" to "🍕", "exclamation point" to "❗", "exclamation mark" to "❗",
        "exclamation" to "❗", "question mark" to "❓", "zany" to "🤪", "crazy" to "🤪", "silly" to "🤪",
        "smile" to "🙂", "smiling" to "🙂", "slight smile" to "🙂", "grin" to "😁", "grinning" to "😀",
        "big grin" to "😁", "upside down smile" to "🙃", "money mouth" to "🤑", "zipper mouth" to "🤐",
        "sleeping" to "😴", "sleepy" to "😴", "tired" to "😫", "yawn" to "🥱", "yawning" to "🥱", "cowboy" to "🤠",
        "devil" to "😈", "imp" to "😈", "angel" to "😇", "halo" to "😇", "holding back tears" to "🥹",
        "face holding back tears" to "🥹", "pensive" to "😔", "disappointed" to "😞", "unamused" to "😒",
        "expressionless" to "😑", "neutral face" to "😐", "no mouth" to "😶", "dotted line face" to "🫥",
        "eyes wide" to "😳", "shocked" to "😲", "astonished" to "😲", "open mouth" to "😮", "mouth open" to "😮",
        "hot" to "🥵", "hot face" to "🥵", "cold" to "🥶", "cold face" to "🥶", "sweat" to "😓", "relieved" to "😌",
        "drooling" to "🤤", "lying" to "🤥", "pinocchio" to "🤥", "mask" to "😷", "nerd glasses" to "🤓",
        "fist bump" to "👊", "punch" to "👊", "middle finger" to "🖕", "victory" to "✌️", "peace" to "✌️",
        "peace sign" to "✌️", "rock on" to "🤘", "horns" to "🤘", "call me" to "🤙", "shaka" to "🤙",
        "writing" to "✍️", "writing hand" to "✍️", "nail polish" to "💅", "selfie" to "🤳", "salt" to "🧂",
        "cap" to "🧢", "billed cap" to "🧢", "no cap" to "🧢", "target" to "🎯", "bullseye" to "🎯", "dart" to "🎯",
        "bomb" to "💣", "boom" to "💥", "collision" to "💥", "explosion" to "💥", "sweat drops" to "💦",
        "zzz" to "💤", "speech bubble" to "💬", "thought bubble" to "💭", "pin" to "📌", "pushpin" to "📌",
        "link" to "🔗", "lock" to "🔒", "key" to "🔑", "magnifying glass" to "🔍", "search" to "🔍",
        "laptop" to "💻", "computer" to "💻", "phone" to "📱", "mobile phone" to "📱", "camera" to "📷",
        "movie camera" to "🎥", "tv" to "📺", "television" to "📺", "microphone" to "🎤", "mic" to "🎤",
        "headphones" to "🎧", "video game" to "🎮", "game controller" to "🎮", "controller" to "🎮", "dice" to "🎲",
        "puzzle" to "🧩", "puzzle piece" to "🧩", "book" to "📖", "books" to "📚", "memo" to "📝", "note" to "📝",
        "notes" to "📝", "clipboard" to "📋", "calendar" to "📅", "folder" to "📁", "package" to "📦", "box" to "📦",
        "hammer" to "🔨", "wrench" to "🔧", "tools" to "🛠️", "gear" to "⚙️", "shield" to "🛡️", "sword" to "🗡️",
        "crystal ball" to "🔮", "magic" to "🪄", "magic wand" to "🪄", "earth" to "🌍", "globe" to "🌍",
        "world" to "🌍", "sun" to "☀️", "moon" to "🌙", "rainbow" to "🌈", "snowflake" to "❄️", "cloud" to "☁️",
        "umbrella" to "☔", "water" to "💧", "wave water" to "🌊", "ocean" to "🌊", "tree" to "🌳", "plant" to "🌱",
        "seedling" to "🌱", "flower" to "🌸", "rose" to "🌹", "sunflower" to "🌻", "cactus" to "🌵",
        "mushroom" to "🍄", "leaf" to "🍃", "dog" to "🐶", "cat" to "🐱", "mouse" to "🐭", "rabbit" to "🐰",
        "bunny" to "🐰", "fox" to "🦊", "bear" to "🐻", "panda" to "🐼", "monkey" to "🐒", "lion" to "🦁",
        "tiger" to "🐯", "cow" to "🐮", "pig" to "🐷", "frog" to "🐸", "chicken" to "🐔", "penguin" to "🐧",
        "bird" to "🐦", "eagle" to "🦅", "duck" to "🦆", "owl" to "🦉", "bat" to "🦇", "wolf" to "🐺",
        "horse" to "🐴", "unicorn" to "🦄", "bee" to "🐝", "bug" to "🐛", "butterfly" to "🦋", "snail" to "🐌",
        "turtle" to "🐢", "snake" to "🐍", "dragon" to "🐉", "dinosaur" to "🦖", "t rex" to "🦖", "whale" to "🐳",
        "dolphin" to "🐬", "fish" to "🐟", "shark" to "🦈", "octopus" to "🐙", "crab" to "🦀", "lobster" to "🦞",
        "shrimp" to "🦐", "apple" to "🍎", "banana" to "🍌", "avocado" to "🥑", "taco" to "🌮", "burger" to "🍔",
        "hamburger" to "🍔", "fries" to "🍟", "hot dog" to "🌭", "cake" to "🍰", "birthday cake" to "🎂",
        "cookie" to "🍪", "donut" to "🍩", "doughnut" to "🍩", "ice cream" to "🍦", "egg" to "🥚", "bread" to "🍞",
        "cheese" to "🧀", "wine" to "🍷", "tea" to "🍵", "bubble tea" to "🧋", "car" to "🚗", "taxi" to "🚕",
        "bus" to "🚌", "train" to "🚆", "airplane" to "✈️", "plane" to "✈️", "ship" to "🚢", "boat" to "⛵",
        "bike" to "🚲", "bicycle" to "🚲", "house" to "🏠", "home" to "🏠", "office" to "🏢", "hospital" to "🏥",
        "soccer" to "⚽", "football" to "🏈", "basketball" to "🏀", "baseball" to "⚾", "tennis" to "🎾",
        "medal" to "🏅", "gold medal" to "🥇", "first place" to "🥇", "running" to "🏃", "runner" to "🏃",
        "weightlifting" to "🏋️", "weight lifting" to "🏋️", "flag" to "🚩", "red flag" to "🚩",
        "checkered flag" to "🏁", "white flag" to "🏳️", "pirate flag" to "🏴‍☠️", "american flag" to "🇺🇸",
        "us flag" to "🇺🇸", "new" to "🆕", "free" to "🆓", "sos" to "🆘", "stop" to "🛑", "stop sign" to "🛑",
        "no entry" to "⛔", "prohibited" to "🚫", "recycle" to "♻️", "infinity" to "♾️", "plus" to "➕",
        "minus" to "➖", "green circle" to "🟢", "red circle" to "🔴", "yellow circle" to "🟡", "blue circle" to "🔵",
        "white circle" to "⚪", "black circle" to "⚫", "orange heart" to "🧡", "yellow heart" to "💛",
        "green heart" to "💚", "blue heart" to "💙", "purple heart" to "💜", "black heart" to "🖤",
        "white heart" to "🤍", "pointing at you" to "🫵", "index pointing at viewer" to "🫵",
        "hand over mouth" to "🤭", "giggle" to "🤭", "giggling" to "🤭", "saluting hand" to "🫡",
        "skull and crossbones" to "☠️", "nose" to "👃", "tongue" to "👅", "lips" to "👄", "biting lip" to "🫦",
        "ear" to "👂", "baby" to "👶", "old man" to "👴", "old woman" to "👵", "ninja" to "🥷", "detective" to "🕵️",
        "zombie" to "🧟", "vampire" to "🧛", "superhero" to "🦸", "santa" to "🎅", "mage" to "🧙", "wizard" to "🧙",
        "genie" to "🧞", "mermaid" to "🧜", "elf" to "🧝", "man shrugging" to "🤷‍♂️", "woman shrugging" to "🤷‍♀️",
        "woman facepalming" to "🤦‍♀️", "woman face palming" to "🤦‍♀️", "person bowing" to "🙇", "bowing" to "🙇",
        "raising hand" to "🙋", "person raising hand" to "🙋", "tipping hand" to "💁", "no gesture" to "🙅",
        "ok gesture" to "🙆", "ballot box" to "🗳️", "money with wings" to "💸", "flying money" to "💸",
        "dollar" to "💵", "dollar bill" to "💵", "credit card" to "💳", "gem" to "💎", "diamond" to "💎",
        "ring" to "💍", "bell" to "🔔", "megaphone" to "📣", "loudspeaker" to "📢", "trumpet" to "🎺",
        "guitar" to "🎸", "piano" to "🎹", "violin" to "🎻", "saxophone" to "🎷", "hourglass done" to "⌛",
        "stopwatch" to "⏱️", "timer" to "⏲️", "battery full" to "🔋", "plug" to "🔌", "electric plug" to "🔌",
        "satellite" to "🛰️", "telescope" to "🔭", "microscope" to "🔬", "test tube" to "🧪", "dna" to "🧬",
        "pill" to "💊", "syringe" to "💉", "bandage" to "🩹", "thermometer" to "🌡️", "volcano" to "🌋",
        "mountain" to "⛰️", "camping" to "🏕️", "beach" to "🏖️", "desert island" to "🏝️", "night" to "🌃",
        "city" to "🏙️", "sunrise" to "🌅", "sunset" to "🌇", "fireworks" to "🎆", "sparkler" to "🎇",
        "jack o lantern" to "🎃", "pumpkin" to "🎃", "christmas tree" to "🎄"
    )

    // Words that make the request talk *about* emoji ("the voice emoji", "a great emoji").
    private val META_BEFORE = JsRegex.compile("^(a|an|the|this|that|these|those|my|your|his|her|our|their|its|any|no|some|which|what|whatever|every|each|voice|another|as|that's)$", true)
    // Words that never belong inside an emoji description; they end the span.
    private val BREAK = JsRegex.compile("^(and|or|but|so|is|are|was|were|be|been|it|it's|i|i'm|you|we|they|he|she|to|for|in|on|at|by|from|then|than|just|really|very|also|like|um|uh|okay|ok|yes|yeah|no|not|please|put|add|insert|use|type|send|with|of|the|a|an)$", true)
    private val INNER_OK = JsRegex.compile("^(of|with|on|the|and|a)$", true) // allowed inside an alias/label, e.g. "cloud of dust"
    private val NUM_WORDS = mapOf("one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10)
    private val EDIT_FILLER = JsRegex.compile("^(just|only|please|put|use|insert|make|turn|replace|convert|change|swap|it|that|this|those|them|into|to|with|in|the|a|an|actual|real|emoji|emojis|emoji's|instead|itself|themselves|nothing|else|and|i|meant|mean|want|wanted|no|words|word|text|description|characters|character|symbol|symbols|of|for|so|leave|keep|drop|remove|delete|get|rid|transcription|instructions|instruction|should|be|is|just|okay|ok|yeah|yes|not|don't|do|dont|literal|literally|spell|spelled|out|written)$", true)

    private val POSSESSIVE = JsRegex.compile("[’']s$")
    private val NON_ALNUM = JsRegex.compile("[^a-z0-9]")
    private val DOUBLE_CONSONANT = JsRegex.compile("([b-df-hj-np-tv-z])\\1$")
    private val LABEL_PUNCT = JsRegex.compile("[:,]")
    private val WS_RUN = JsRegex.compile("\\s+")
    private val WS_TOK = JsRegex.compile("^\\s+$")
    private val WS_OR_EMPTY = JsRegex.compile("^\\s*$")
    private val EMOJI_I = JsRegex.compile("emoji", true)
    private val EMOJI_WORD_START = JsRegex.compile("\\bemoji", true)
    private val EMOJI_TOKEN = JsRegex.compile("^(emoji|emojis|emojie|emojies)([.,!?;:)\"']*)$", true)
    private val PLURAL = JsRegex.compile("s$", true)
    private val SENTENCE_END = JsRegex.compile("[.!?;:][\"')]*$")
    private val LEAD = JsRegex.compile("^[\"'(]+")
    private val TRAIL = JsRegex.compile("[,\"')]+$")
    private val WORDLIKE = JsRegex.compile("^[A-Za-z0-9][A-Za-z0-9'’-]*$")
    private val DIGITS = JsRegex.compile("^\\d+$")
    private val LEADING_DOT = JsRegex.compile("^\\.")
    private val OPTIONAL_DOT = JsRegex.compile("^\\.?$")
    private val NON_AZ_APOS_SPACE = JsRegex.compile("[^a-z' ]+")
    private val MARKER = JsRegex.compile("⟦emoji:\\s*([^⟧]*)⟧")
    private val MARKER_START = JsRegex.compile("⟦emoji:")

    /** Unicode 16 Extended_Pictographic as inclusive [start, end] pairs (generated from node's ICU, same as JS `\p{Extended_Pictographic}`). */
    private val PICTOGRAPHIC = intArrayOf(
        0xA9, 0xA9, 0xAE, 0xAE, 0x203C, 0x203C, 0x2049, 0x2049, 0x2122, 0x2122, 0x2139, 0x2139,
        0x2194, 0x2199, 0x21A9, 0x21AA, 0x231A, 0x231B, 0x2328, 0x2328, 0x2388, 0x2388, 0x23CF, 0x23CF,
        0x23E9, 0x23F3, 0x23F8, 0x23FA, 0x24C2, 0x24C2, 0x25AA, 0x25AB, 0x25B6, 0x25B6, 0x25C0, 0x25C0,
        0x25FB, 0x25FE, 0x2600, 0x2605, 0x2607, 0x2612, 0x2614, 0x2685, 0x2690, 0x2705, 0x2708, 0x2712,
        0x2714, 0x2714, 0x2716, 0x2716, 0x271D, 0x271D, 0x2721, 0x2721, 0x2728, 0x2728, 0x2733, 0x2734,
        0x2744, 0x2744, 0x2747, 0x2747, 0x274C, 0x274C, 0x274E, 0x274E, 0x2753, 0x2755, 0x2757, 0x2757,
        0x2763, 0x2767, 0x2795, 0x2797, 0x27A1, 0x27A1, 0x27B0, 0x27B0, 0x27BF, 0x27BF, 0x2934, 0x2935,
        0x2B05, 0x2B07, 0x2B1B, 0x2B1C, 0x2B50, 0x2B50, 0x2B55, 0x2B55, 0x3030, 0x3030, 0x303D, 0x303D,
        0x3297, 0x3297, 0x3299, 0x3299, 0x1F000, 0x1F0FF, 0x1F10D, 0x1F10F, 0x1F12F, 0x1F12F,
        0x1F16C, 0x1F171, 0x1F17E, 0x1F17F, 0x1F18E, 0x1F18E, 0x1F191, 0x1F19A, 0x1F1AD, 0x1F1E5,
        0x1F201, 0x1F20F, 0x1F21A, 0x1F21A, 0x1F22F, 0x1F22F, 0x1F232, 0x1F23A, 0x1F23C, 0x1F23F,
        0x1F249, 0x1F3FA, 0x1F400, 0x1F53D, 0x1F546, 0x1F64F, 0x1F680, 0x1F6FF, 0x1F774, 0x1F77F,
        0x1F7D5, 0x1F7FF, 0x1F80C, 0x1F80F, 0x1F848, 0x1F84F, 0x1F85A, 0x1F85F, 0x1F888, 0x1F88F,
        0x1F8AE, 0x1F8FF, 0x1F90C, 0x1F93A, 0x1F93C, 0x1F945, 0x1F947, 0x1FAFF, 0x1FC00, 0x1FFFD
    )

    private class Row(val emoji: String, val vocab: Set<String>, val labelWords: List<String>, val order: Int)
    private class Index(val rows: List<Row>, val aliases: Map<String, String>, val vocab: Set<String>)

    @Volatile private var index: Index? = null
    @Volatile private var loadFailed = false

    /** Converts "<description> emoji" requests in [text]; [force] ignores the talk-about-emoji guards. */
    fun convert(context: Context?, text: String, force: Boolean = false): String {
        val idx = index(context) ?: return text
        return runCatching { convertWith(idx, text, force) }.getOrDefault(text)
    }

    /** Turns any ⟦emoji: description⟧ marker the cleanup model left behind back into "description emoji". */
    fun unmark(text: String): String = MARKER.replace(text) { m -> m.groupValues[1] + " emoji" }

    /**
     * Selection edit: "just put the emoji" on a selection like "Gravestone emoji" -> "🪦", no model call.
     * Returns null when the instruction asks for anything more than that.
     */
    fun edit(context: Context?, instruction: String, selected: String): String? {
        val idx = index(context) ?: return null
        return runCatching { editWith(idx, instruction, selected) }.getOrNull()
    }

    /** Test hook: load the table from TSV text instead of the asset (JVM unit tests have no Context). */
    fun loadTable(tsv: String) {
        index = buildIndex(tsv)
        loadFailed = false
    }

    private fun index(context: Context?): Index? {
        index?.let { return it }
        if (context == null || loadFailed) return null
        synchronized(this) {
            index?.let { return it }
            return try {
                val tsv = context.applicationContext.assets.open(ASSET).bufferedReader(Charsets.UTF_8).use { it.readText() }
                buildIndex(tsv).also { index = it }
            } catch (e: Exception) {
                loadFailed = true
                null
            }
        }
    }

    internal fun stem(input: String): String {
        var w = NON_ALNUM.replace(POSSESSIVE.replaceFirst(input.lowercase(), ""), "")
        if (w.length > 5 && w.endsWith("ing")) w = w.dropLast(3)
        else if (w.length > 4 && w.endsWith("ed")) w = w.dropLast(2)
        else if (w.length > 4 && w.endsWith("es")) w = w.dropLast(2)
        else if (w.length > 3 && w.endsWith("s") && !w.endsWith("ss")) w = w.dropLast(1)
        if (w.length > 3 && DOUBLE_CONSONANT.containsMatchIn(w)) w = w.dropLast(1) // running -> run
        if (w.length > 3 && w.endsWith("e")) w = w.dropLast(1)
        return w
    }

    /** JS `s.split(/\s+/)` (no capture group): the pieces only. */
    private fun splitWs(s: String): List<String> = JsRegex.split(s, WS_RUN).filterIndexed { n, _ -> n % 2 == 0 }

    private fun buildIndex(tsv: String): Index {
        val rows = ArrayList<Row>()
        for (line in tsv.split("\n")) {
            if (line.isEmpty()) continue
            val cols = line.split("\t")
            val emoji = cols[0]; val label = cols[1]; val words = cols[2]
            val vocab = words.split(" ").map { stem(it) }.toHashSet()
            val labelWords = splitWs(LABEL_PUNCT.replace(label, "")).filter { !INNER_OK.containsMatchIn(it) }.map { stem(it) }
            rows.add(Row(emoji, vocab, labelWords, rows.size))
        }
        val aliases = LinkedHashMap<String, String>()
        for ((k, v) in ALIASES) {
            val key = splitWs(k).joinToString(" ") { stem(it) }
            if (!aliases.containsKey(key)) aliases[key] = v
        }
        val vocab = HashSet<String>()
        for (r in rows) vocab.addAll(r.vocab)
        for (k in aliases.keys) vocab.addAll(k.split(" "))
        return Index(rows, aliases, vocab)
    }

    private fun hasPictographic(s: String): Boolean {
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            var lo = 0; var hi = PICTOGRAPHIC.size / 2 - 1
            while (lo <= hi) {
                val mid = (lo + hi) ushr 1
                if (cp < PICTOGRAPHIC[2 * mid]) hi = mid - 1
                else if (cp > PICTOGRAPHIC[2 * mid + 1]) lo = mid + 1
                else return true
            }
            i += Character.charCount(cp)
        }
        return false
    }

    // Best emoji whose vocabulary covers every content word, or null.
    private fun lookup(idx: Index, words: List<String>): String? {
        val key = words.joinToString(" ") { stem(it) }
        idx.aliases[key]?.let { if (it.isNotEmpty()) return it }
        val content = words.filter { !INNER_OK.containsMatchIn(it) }.map { stem(it) }.filter { it.isNotEmpty() }
        if (content.isEmpty()) return null
        var best: Row? = null
        var bestScore = -1.0
        for (r in idx.rows) {
            if (!content.all { it in r.vocab }) continue
            val labelHits = r.labelWords.count { it in content }
            val exact = labelHits == r.labelWords.size && labelHits == content.size
            // Description words in the label beat words that only appear in tags; exact label wins.
            val score = (if (exact) 100.0 else 0.0) + labelHits * 10.0 - r.labelWords.size - r.order / 10000.0
            if (score > bestScore) { bestScore = score; best = r }
        }
        // A single word that only matches tags (e.g. "good" -> 👍) is too loose; require a label hit.
        if (best != null && content.size == 1 && content[0] !in best.labelWords) return null
        return best?.emoji
    }

    private fun truncate(out: MutableList<String>, size: Int) { while (out.size > size) out.removeAt(out.size - 1) }

    private fun convertWith(idx: Index, text: String, force: Boolean): String {
        if (!EMOJI_I.containsMatchIn(text)) return text
        // Tokens: words with their trailing punctuation, and the whitespace between them.
        val parts = JsRegex.split(text, WS_RUN)
        val out = ArrayList<String>()
        val made = HashSet<Int>() // indexes in `out` holding emoji this call produced
        fun nonBlank(t: String) = t.isNotEmpty() && !WS_TOK.containsMatchIn(t)
        var i = 0
        while (i < parts.size) {
            val tok = parts[i]
            val m = EMOJI_TOKEN.find(tok)
            if (m == null) { out.add(tok); i++; continue }
            val plural = PLURAL.containsMatchIn(m.groupValues[1])
            val trail = m.groupValues[2]
            // Walk back over preceding words (in `out`) collecting the description.
            val words = ArrayList<String>(); val wIdx = ArrayList<Int>()
            var j = out.size - 1
            while (j >= 0 && words.size < 6) {
                if (WS_TOK.containsMatchIn(out[j]) || out[j].isEmpty()) { j--; continue }
                val raw = out[j]
                if (SENTENCE_END.containsMatchIn(raw)) break // previous sentence
                if (hasPictographic(raw)) break
                val w = TRAIL.replaceFirst(LEAD.replaceFirst(raw, ""), "")
                if (!WORDLIKE.containsMatchIn(w)) break
                words.add(0, w.lowercase().replace("-", " ")); wIdx.add(0, j)
                j--
            }
            // Choose the longest suffix of the collected words that resolves.
            var chosen: String? = null
            var take = 0
            var k = minOf(words.size, 5)
            while (k >= 1) {
                val span = words.subList(words.size - k, words.size).joinToString(" ").split(" ")
                val stems = span.map { stem(it) }
                if (stems.toHashSet().size < span.size) { k--; continue } // "robot, robot emoji" is one robot
                val aliasHit = if (!plural) idx.aliases[stems.joinToString(" ")] else null
                if (!aliasHit.isNullOrEmpty()) { chosen = aliasHit; take = k; break }
                if (BREAK.containsMatchIn(span[0]) || span.withIndex().any { (n, w) -> n > 0 && BREAK.containsMatchIn(w) && !INNER_OK.containsMatchIn(w) }) { k--; continue }
                var count = 1
                var body: List<String> = span
                val numWord = NUM_WORDS[span[0]]
                if (plural && span.size > 1 && numWord != null) { count = numWord; body = span.subList(1, span.size) }
                else if (plural && span.size > 1 && DIGITS.containsMatchIn(span[0]) && BigInteger(span[0]) <= BigInteger.TEN) { count = span[0].toBigInteger().toInt(); body = span.subList(1, span.size) }
                else if (plural) { k--; continue } // "add emojis" is talk about emoji
                val e = lookup(idx, body)
                if (!e.isNullOrEmpty()) { chosen = e.repeat(count); take = k; break }
                k--
            }
            val before = words.size - take
            val metaBefore = !force && before > 0 && META_BEFORE.containsMatchIn(words[before - 1])
            if (!chosen.isNullOrEmpty() && !metaBefore) {
                // Drop the description words (and the whitespace between them) from `out`.
                val firstIdx = wIdx[wIdx.size - take]
                truncate(out, firstIdx)
                // The model already inserted this emoji next to the words ("🪦 Gravestone emoji"): keep one.
                // Only an emoji that was already in the text counts, and only with nothing but a space
                // between: "fire emoji, fire emoji" is a deliberate repeat.
                var prevIdx = out.size - 1
                while (prevIdx >= 0 && WS_OR_EMPTY.containsMatchIn(out[prevIdx])) prevIdx--
                val prevTok = if (prevIdx >= 0) out[prevIdx] else ""
                val nextTok = parts.subList(i + 1, parts.size).firstOrNull { nonBlank(it) } ?: ""
                if ((prevTok.endsWith(chosen) && prevIdx !in made && trail.isEmpty()) || (nextTok.startsWith(chosen) && OPTIONAL_DOT.containsMatchIn(trail))) {
                    while (out.isNotEmpty() && WS_TOK.containsMatchIn(out[out.size - 1])) out.removeAt(out.size - 1)
                    val t2 = LEADING_DOT.replaceFirst(trail, "")
                    if (t2.isNotEmpty()) out.add(t2)
                    if (i + 1 < parts.size && WS_TOK.containsMatchIn(parts[i + 1])) { if (out.isNotEmpty()) out.add(parts[i + 1]); i++ }
                    i++; continue
                }
                // A sentence-final period after an emoji that ends the text is dropped ("Laughing emoji." -> 😂).
                val lastWord = parts.subList(i + 1, parts.size).none { nonBlank(it) }
                made.add(out.size)
                out.add(chosen + if (lastWord) LEADING_DOT.replaceFirst(trail, "") else trail)
                i++; continue
            }
            if (!plural && take == 0 && words.isNotEmpty() && !metaBefore && !META_BEFORE.containsMatchIn(words[words.size - 1])) {
                // Unresolved but looks like a request: let the cleanup model pick the emoji.
                val n = minOf(words.size, 4)
                val span = ArrayList<String>()
                var s = words.size
                var q = words.size - 1
                while (q >= words.size - n) {
                    if (BREAK.containsMatchIn(words[q]) || META_BEFORE.containsMatchIn(words[q]) || stem(words[q]) !in idx.vocab) break
                    span.add(0, words[q]); s = q
                    q--
                }
                if (s > 0 && META_BEFORE.containsMatchIn(words[s - 1])) span.clear()
                if (span.isNotEmpty()) {
                    truncate(out, wIdx[s])
                    out.add("⟦emoji: " + span.joinToString(" ") + "⟧" + trail)
                    i++; continue
                }
            }
            out.add(tok)
            i++
        }
        return out.joinToString("")
    }

    private fun editWith(idx: Index, instruction: String, selected: String): String? {
        if (!EMOJI_WORD_START.containsMatchIn(instruction) || !EMOJI_I.containsMatchIn(selected)) return null
        val words = splitWs(NON_AZ_APOS_SPACE.replace(instruction.lowercase(), " ")).filter { it.isNotEmpty() }
        if (words.isEmpty() || !words.all { EDIT_FILLER.containsMatchIn(it) }) return null
        // Force-convert every "<description> emoji" in the selection, ignoring the talk-about-emoji guards.
        val converted = convertWith(idx, selected, true)
        if (converted == selected || MARKER_START.containsMatchIn(converted)) return null
        return converted
    }
}
